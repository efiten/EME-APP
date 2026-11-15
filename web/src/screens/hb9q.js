// Port of ChatScreen.kt and ChatCqScreen.kt: Messages, QRV and Chat CQ of the HB9Q chat, read only.
// Data comes from the shared HB9Q store (src/hb9q-store.js); this screen logs in with the stored login,
// polls while mounted and shows what the store holds. Messages, names and callsigns come from other
// people: only textContent, never markup.

import { loadCredentials } from '../credentials.js';
import { HB9Q_REFRESH_MS, errorFor, hb9qStore } from '../hb9q-store.js';
import { HB9Q_BANDS, bandByName, recentCqs, recentParticipants } from '../lib/hb9q.js';
import { saveSettings } from '../settings.js';
import { visibleInterval } from '../lib/visible-interval.js';

const POLL_CHECK_MS = 5_000;
const REFRESH_BUTTON_MAX_AGE_MS = 5_000;

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
const tagged = (node, role) => {
  node.dataset.role = role;
  return node;
};
const button = (label, onClick, className = 'text-button') => {
  const b = el('button', className, label);
  b.type = 'button';
  b.addEventListener('click', onClick);
  return b;
};

/** Screen contract { mount(el, ctx), unmount() }; ctx.hb9q is the store, ctx.credentials the login (defaults: shared store, stored login). */
export function createHb9qScreen() {
  let cleanup = null;

  return {
    mount(host, ctx) {
      const { settings, t } = ctx;
      const store = ctx.hb9q ?? hb9qStore;
      const creds = ctx.credentials !== undefined ? ctx.credentials : loadCredentials();
      const myCallsign = settings.callsign.toUpperCase();
      let band = bandByName(settings.chatBand) ? settings.chatBand : 'B1296';
      let subTab = 0; // 0 = messages, 1 = QRV, 2 = Chat CQ

      const root = el('div', 'n0uk hb9q');
      host.replaceChildren(root);

      if (!creds) {
        root.append(el('div', 'empty', t('need_login')));
        cleanup = () => {};
        return;
      }

      const chips = el('div', 'chips');
      const chipButtons = HB9Q_BANDS.map((b) => {
        const c = el('button', 'chip', b.label);
        c.type = 'button';
        c.addEventListener('click', () => selectBand(b.name));
        chips.append(c);
        return c;
      });
      const spinner = tagged(el('span', 'spinner'), 'loading');
      const refresh = button(t('refresh'), () => store.refreshIfOlderThan(REFRESH_BUTTON_MAX_AGE_MS, band, creds));
      const logOut = button(t('log_out'), () => store.logout());
      const bar = el('div', 'info-row');
      bar.append(logOut, el('div'), spinner, refresh);
      const tabs = el('div', 'subtabs');
      tabs.setAttribute('role', 'tablist');
      const tabButtons = [0, 1, 2].map((i) => {
        const b = el('button', 'subtab');
        b.type = 'button';
        b.setAttribute('role', 'tab');
        b.addEventListener('click', () => {
          subTab = i;
          render();
        });
        tabs.append(b);
        return b;
      });
      const errorBox = el('div');
      const pane = el('div', 'pane');
      const loggedOut = tagged(el('div', 'logged-out'), 'logged-out');
      loggedOut.append(el('div', '', t('logged_out')), button(t('log_in'), logInAgain, 'button'));
      const chat = el('div');
      chat.append(bar, tabs, errorBox, pane);
      root.append(chips, loggedOut, chat);

      function poll() {
        return store.refreshIfOlderThan(HB9Q_REFRESH_MS, band, creds);
      }

      function logInAgain() {
        store.logInAgain();
        poll();
      }

      function selectBand(name) {
        if (name === band) return;
        band = name;
        saveSettings({ chatBand: name });
        // refreshIfOlderThan does nothing while a request runs, and abort() does not notify the subscribers
        store.abort();
        render();
        poll();
      }

      function messageRow(msg) {
        const toMe = msg.recipient !== null && msg.recipient.toUpperCase() === myCallsign;
        const mine = msg.callsign.toUpperCase() === myCallsign;
        const row = tagged(el('div', toMe ? 'message to-me' : 'message'), 'message');
        const top = el('div', 'top');
        top.append(el('span', 'stamp', msg.time), el('span', mine ? 'call mine' : 'call', msg.callsign), el('span', 'small', msg.name));
        if (msg.recipient !== null) top.append(el('span', toMe ? 'small to-me-name' : 'small', `→ ${msg.recipient}`));
        row.append(top, el('div', msg.isCq ? 'text cq' : toMe ? 'text bold' : 'text', msg.text));
        return row;
      }

      function writerRow(p) {
        const row = tagged(el('div', 'writer'), 'writer');
        row.append(el('span', 'stamp', p.time), el('span', 'call', p.callsign), el('span', 'name', p.name));
        return row;
      }

      function cqTable(cqs, label) {
        const table = el('div', 'cq-table hb9q-cq');
        table.append(el('div', 'small cq-title', t('chatcq_title', label, cqs.length)));
        const header = tagged(el('div', 'cq-row cq-head'), 'cq-header');
        for (const [i, text] of t('chatcq_header').split(' - ').entries()) {
          header.append(el('span', i === 6 ? 'end' : '', text));
        }
        table.append(header);
        if (cqs.length === 0) table.append(el('div', 'empty', t('chatcq_empty')));
        cqs.forEach((c, i) => {
          const row = tagged(el('div', `cq-row${i % 2 === 1 ? ' alt' : ''}`), 'cq');
          row.append(
            el('span', 'utc', c.time),
            el('span', 'freq', c.freq),
            el('span', '', c.period),
            el('span', 'call', c.callsign),
            el('span', '', c.mode),
            el('span', '', c.cfom === 'own echo' ? 'echo' : c.cfom),
            el('span', 'end', c.hz),
          );
          table.append(row);
        });
        return table;
      }

      function render() {
        const s = store.getState();
        const mineBand = s.band === band; // a list of another band is not shown
        const messages = mineBand ? s.messages : [];
        const participants = recentParticipants(messages, settings.callsign);
        loggedOut.hidden = !s.loggedOut;
        chat.hidden = s.loggedOut;
        chipButtons.forEach((c, i) => c.setAttribute('aria-pressed', String(HB9Q_BANDS[i].name === band)));
        spinner.hidden = !s.loading;
        refresh.hidden = s.loading;
        [t('messages'), t('participants', participants.length), t('tab_chatcq')].forEach((label, i) => {
          tabButtons[i].textContent = label;
          tabButtons[i].setAttribute('aria-selected', String(i === subTab));
        });
        const error = errorFor(s, band);
        errorBox.className = error ? 'error-card' : '';
        errorBox.dataset.role = error ? 'error' : '';
        errorBox.textContent = error ? t(error.key, error.detail) : '';
        if (s.loggedOut) {
          pane.replaceChildren();
        } else if (subTab === 0) {
          pane.replaceChildren(...messages.map(messageRow));
        } else if (subTab === 1) {
          pane.replaceChildren(el('div', 'accent heading', t('recent_in_chat')), ...participants.map(writerRow));
        } else {
          pane.replaceChildren(cqTable(recentCqs(messages, ctx.now()), bandByName(band).label));
        }
      }

      render();
      const unsubscribe = store.subscribe(render);
      const stopPolling = visibleInterval(
        poll,
        POLL_CHECK_MS,
        () => {
          store.abort();
          render();
        },
      );
      cleanup = () => {
        unsubscribe();
        stopPolling();
        store.abort();
      };
    },

    unmount() {
      cleanup?.();
      cleanup = null;
    },
  };
}

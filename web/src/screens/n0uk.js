// Port of N0ukScreen.kt: Messages, QRV and Chat CQ of the N0UK WSJT EME chat (144 MHz), read only.
// Data comes from the shared N0UK store (src/data.js); this screen only subscribes while mounted.
// Message text and names come from other people: only textContent, never markup.

import { n0ukStore } from '../data.js';
import { stationLatLon } from '../lib/livecq.js';
import { quickElevationDeg, subLunarPoint } from '../lib/moon-calculator.js';
import { kindOf, recentCqs, recentWriters } from '../lib/n0uk.js';
import { visibleInterval } from '../lib/visible-interval.js';

/** Chat CQ shows the CQs of the last hour. */
const CQ_WINDOW_MINUTES = 60;
const CLOCK_MS = 5_000;

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
const hhmm = (ms) => new Date(ms).toISOString().slice(11, 16);

/** Screen contract { mount(el, ctx), unmount() }; ctx.n0uk is the store (default: the shared one). */
export function createN0ukScreen() {
  let cleanup = null;

  return {
    mount(host, ctx) {
      const { settings, t } = ctx;
      const store = ctx.n0uk ?? n0ukStore;
      const myCallsign = settings.callsign;
      let subTab = 0; // 0 = messages, 1 = QRV, 2 = Chat CQ

      const root = el('div', 'n0uk');
      const infoRow = el('div', 'info-row');
      const spinner = tagged(el('span', 'spinner'), 'loading');
      infoRow.append(el('div', 'small', t('n0uk_info')), spinner);
      const updated = tagged(el('div', 'small dim'), 'updated');
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
      root.append(infoRow, updated, tabs, errorBox, pane);
      host.replaceChildren(root);

      function messageRow(msg) {
        const kind = kindOf(msg);
        const row = tagged(el('div', 'message'), 'message');
        const top = el('div', 'top');
        const mine = msg.callsign.toUpperCase() === myCallsign.toUpperCase();
        top.append(
          el('span', 'stamp', `${msg.date} ${msg.time}`),
          el('span', mine ? 'call mine' : 'call', msg.callsign),
          el('span', 'small', `${msg.name} ${msg.locator}`.trim()),
        );
        row.append(top, el('div', kind === 'CQ' ? 'text cq' : kind === 'STOP' ? 'text stop' : 'text', msg.text));
        return row;
      }

      function writerRow(w) {
        const row = tagged(el('div', 'writer'), 'writer');
        row.append(el('span', 'stamp', w.dateTime), el('span', 'call', w.callsign), el('span', 'name', w.name), el('span', 'loc', w.locator));
        return row;
      }

      function cqTable(cqs, now) {
        const table = el('div', 'cq-table');
        table.append(el('div', 'small cq-title', t('n0uk_cq_title', cqs.length)));
        const header = tagged(el('div', 'cq-row cq-head'), 'cq-header');
        for (const [i, text] of t('n0uk_cq_header').split(' - ').entries()) {
          header.append(el('span', i === 6 ? 'end' : '', text));
        }
        table.append(header);
        if (cqs.length === 0) table.append(el('div', 'empty', t('n0uk_cq_empty')));
        const sub = subLunarPoint(now);
        cqs.forEach((c, i) => {
          const row = tagged(el('div', `cq-row${c.ended ? ' ended' : ''}${i % 2 === 1 ? ' alt' : ''}`), 'cq');
          const p = stationLatLon(c.locator);
          const elev = p ? quickElevationDeg(sub, p.latDeg, p.lonDeg) : null;
          const moon = el('span', `end ${elev === null || c.ended ? '' : elev > 0 ? 'up' : 'down'}`, elev === null ? '?' : elev > 0 ? `↑${elev.toFixed(0)}°` : '↓');
          row.append(
            el('span', 'utc', c.time),
            el('span', 'freq', c.freq),
            el('span', '', c.period),
            el('span', 'call', c.callsign),
            el('span', '', c.mode),
            el('span', '', c.locator || '?'),
            moon,
          );
          table.append(row);
        });
        return table;
      }

      function render() {
        const now = ctx.now();
        const s = store.getState();
        const writers = recentWriters(s.messages, myCallsign);
        spinner.hidden = !s.loading;
        updated.textContent = s.fetchedAt ? t('updated', hhmm(s.fetchedAt)) : '';
        updated.hidden = !s.fetchedAt;
        [t('messages'), t('participants', writers.length), t('tab_chatcq')].forEach((label, i) => {
          tabButtons[i].textContent = label;
          tabButtons[i].setAttribute('aria-selected', String(i === subTab));
        });
        errorBox.className = '';
        errorBox.dataset.role = '';
        errorBox.textContent = '';
        if (s.error) {
          errorBox.className = 'error-card';
          errorBox.dataset.role = 'error';
          errorBox.textContent = t('n0uk_error', s.error);
        }
        if (subTab === 0) {
          pane.replaceChildren(...s.messages.map(messageRow));
        } else if (subTab === 1) {
          pane.replaceChildren(el('div', 'accent heading', t('recent_in_chat')), ...writers.map(writerRow));
        } else {
          pane.replaceChildren(cqTable(recentCqs(s.messages, now, CQ_WINDOW_MINUTES), now));
        }
      }

      render();
      const unsubscribe = store.subscribe(render);
      const stopClock = visibleInterval(render, CLOCK_MS);
      cleanup = () => {
        unsubscribe();
        stopClock();
      };
    },

    unmount() {
      cleanup?.();
      cleanup = null;
    },
  };
}

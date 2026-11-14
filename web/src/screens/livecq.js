// Port of CqScreen.kt: band chips, summary, refresh, error and the spot cards.
// Data comes from the shared Live CQ store (src/data.js); this screen only subscribes while mounted.

import { liveCqStore } from '../data.js';
import { BANDS, selectSpots, sourceLabel, stationLatLon } from '../lib/livecq.js';
import { position } from '../lib/moon-calculator.js';
import { visibleInterval } from '../lib/visible-interval.js';
import { locatorLatLon, saveSettings } from '../settings.js';

/** Only CQs of the last hour. */
const MAX_AGE_MINUTES = 60;
/** Clock for the moon elevation and the age of the spots. */
const CLOCK_MS = 15_000;
const SVG = 'http://www.w3.org/2000/svg';
// Material "refresh" icon (24x24).
const REFRESH = 'M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z';

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

function refreshIcon() {
  const svg = document.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS(SVG, 'path');
  path.setAttribute('d', REFRESH);
  svg.append(path);
  return svg;
}

/** Screen contract { mount(el, ctx), unmount() }; ctx.liveCq is the store (default: the shared one). */
export function createLiveCqScreen() {
  let cleanup = null;

  return {
    mount(host, ctx) {
      const { settings, t } = ctx;
      const store = ctx.liveCq ?? liveCqStore;
      const me = locatorLatLon(settings);
      let bands = new Set(settings.cqBands);

      const root = el('div', 'cq');
      const chips = el('div', 'chips');
      const chipOf = new Map();
      for (const band of BANDS) {
        const chip = el('button', 'chip', band.label);
        chip.type = 'button';
        chip.dataset.band = band.id;
        chip.addEventListener('click', () => {
          const next = new Set(bands);
          if (!next.delete(band.id)) next.add(band.id);
          choose(next);
        });
        chipOf.set(band.id, chip);
        chips.append(chip);
      }
      const all = tagged(el('button', 'chip'), 'all');
      all.type = 'button';
      all.addEventListener('click', () => choose(bands.size === BANDS.length ? new Set() : new Set(BANDS.map((b) => b.id))));
      chips.append(all);

      const head = el('div', 'head');
      const info = el('div', 'info');
      const summary = tagged(el('div', 'small'), 'summary');
      info.append(summary);
      const refresh = tagged(el('button', 'icon-button'), 'refresh');
      refresh.type = 'button';
      refresh.setAttribute('aria-label', t('refresh'));
      refresh.append(refreshIcon());
      refresh.addEventListener('click', () => store.refresh());
      const spinner = tagged(el('span', 'spinner'), 'loading');
      head.append(info, refresh, spinner);

      const errorBox = el('div');
      const empty = el('div');
      const list = tagged(el('div', 'spots'), 'list');
      root.append(chips, head, errorBox, empty, list);
      host.replaceChildren(root);

      function choose(next) {
        bands = next;
        saveSettings({ cqBands: bands });
        render();
      }

      function spotCard(spot, now) {
        const card = tagged(el('div', 'spot'), 'spot');
        const top = el('div', 'top');
        const ageMin = Math.max(0, Math.floor((now - spot.utcMillis) / 60_000));
        top.append(
          el('span', 'call', spot.callsign),
          el('span', 'grid', spot.grid || '----'),
          el('span', 'age small', t('spot_age', hhmm(spot.utcMillis), ageMin)),
        );
        const freq = el('div', 'freq', `${spot.freqMhz.toFixed(3)} MHz   ${spot.mode}   ${spot.db} dB`);
        const bottom = el('div', 'bottom');
        const dt = spot.dt ? ` · DT ${spot.dt}` : '';
        const app = spot.app ? ` · ${spot.app}` : '';
        bottom.append(el('span', 'via small', t('via', spot.spotter) + dt + app), el('span', 'source small', sourceLabel(spot)), moonAtStation(spot.grid, now));
        card.append(top, freq, bottom);
        return card;
      }

      function moonAtStation(grid, now) {
        const p = stationLatLon(grid);
        if (!p) return el('span', 'moon small', t('moon_no_locator'));
        const m = position(p.latDeg, p.lonDeg, now);
        return el('span', `moon small ${m.isAboveHorizon ? 'up' : 'down'}`, m.isAboveHorizon ? t('moon_up_el', m.elevationDeg.toFixed(0)) : t('moon_down'));
      }

      function render() {
        const now = ctx.now();
        const s = store.getState();
        for (const [id, chip] of chipOf) chip.setAttribute('aria-pressed', String(bands.has(id)));
        all.textContent = bands.size === BANDS.length ? t('band_none') : t('band_all');
        const shown = selectSpots(s.spots, bands, now, MAX_AGE_MINUTES);

        const updated = s.fetchedAt ? t('updated', hhmm(s.fetchedAt)) : t('not_fetched');
        summary.textContent = t('cq_summary', shown.length, MAX_AGE_MINUTES, updated);
        info.querySelector('[data-role="mymoon"]')?.remove();
        if (me) {
          const m = position(me.latDeg, me.lonDeg, now);
          info.append(tagged(el('div', `small ${m.isAboveHorizon ? 'up' : 'down'}`, t('moon_at_you', m.elevationDeg.toFixed(1))), 'mymoon'));
        }
        refresh.hidden = s.loading;
        spinner.hidden = !s.loading;

        errorBox.replaceChildren();
        errorBox.className = '';
        errorBox.dataset.role = '';
        if (s.error) {
          errorBox.className = 'error-card';
          errorBox.dataset.role = 'error';
          errorBox.textContent = t('livecq_error', s.error);
        }
        const message = shown.length === 0 && !s.loading ? (bands.size === 0 ? t('choose_band') : t('no_cq')) : '';
        empty.className = message ? 'empty' : '';
        empty.textContent = message;
        list.replaceChildren(...shown.map((spot) => spotCard(spot, now)));
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

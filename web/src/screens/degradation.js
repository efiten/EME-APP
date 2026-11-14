// Port of DegradationScreen.kt and DegCalendar.kt: band picker, value now, 30-day graph (inline SVG), best days, month calendar.
// The value is recomputed every minute; the timer is paused while the page is hidden.

import { effectiveLanguage, locatorLatLon, saveSettings } from '../settings.js';
import { bestDaysOfMonth, computeDegradation, conditionOf, daily, EME_BANDS } from '../lib/degradation.js';
import { calendarDays, dayLabels, firstDayOfWeek, regionOf } from '../lib/deg-calendar.js';
import { phaseAt } from '../lib/moon-phase.js';

const MINUTE_MS = 60_000;
const DAY_MS = 86_400_000;
const DAYS = 30;
/** The chosen day sits at this place in the graph: 15 days before, 14 after. */
const CENTER_INDEX = 15;
const BEST_DAYS_PER_MONTH = 4;
const SVG_NS = 'http://www.w3.org/2000/svg';

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
function button(text, key, onClick, className) {
  const b = el('button', className, text);
  b.type = 'button';
  b.dataset.key = key;
  b.addEventListener('click', onClick);
  return b;
}
function svgEl(tag, attrs = {}, text) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, String(v));
  if (text !== undefined) node.textContent = text;
  return node;
}

const two = (n) => String(n).padStart(2, '0');
const noonOf = (iso) => Date.parse(`${iso}T12:00:00Z`);
const isoOfMs = (ms) => new Date(ms).toISOString().slice(0, 10);
const shiftDays = (iso, n) => isoOfMs(noonOf(iso) + n * DAY_MS);
const condClass = (condition) => `cond-${condition.toLowerCase().replace('_', '-')}`;

/** "EEE dd-MM-yyyy" of a UTC day. */
function formatDay(iso, lang) {
  const weekday = new Intl.DateTimeFormat(lang, { weekday: 'short', timeZone: 'UTC' }).format(noonOf(iso));
  return `${weekday} ${iso.slice(8, 10)}-${iso.slice(5, 7)}-${iso.slice(0, 4)}`;
}

// Graph geometry (viewBox units, 1:1 with the Kotlin dp values at 360 dp wide)
const W = 360;
const H = 230;
const LEFT = 22;
const RIGHT = W - 46;
const TOP = 8;
const BOTTOM = H - 18;

/** Graph as MMMonVHF: DEG (scale left), moon distance 350-410k km and declination -30..+30 deg, all on the same 7 grid lines. */
function buildChart(days, ages, shows, todayIso) {
  const svg = tagged(svgEl('svg', { viewBox: `0 0 ${W} ${H}`, class: 'chart', role: 'img' }), 'chart');
  const n = days.length;
  const maxDeg = Math.max(...days.map(([, r]) => r.degDb));
  const step = maxDeg <= 5.5 ? 1 : maxDeg <= 11.5 ? 2 : 3;
  const maxY = 6 * step;
  const x = (i) => LEFT + ((RIGHT - LEFT) * i) / Math.max(n - 1, 1);
  const yFrac = (f) => BOTTOM - (BOTTOM - TOP) * Math.min(1, Math.max(0, f));
  const yDeg = (v) => yFrac(v / maxY);
  const yDist = (km) => yFrac((km - 350_000) / 60_000);
  const yDec = (d) => yFrac((d + 30) / 60);

  if (shows.deg) {
    for (const [lo, hi] of [[0, 1.5], [1.5, 2.5], [2.5, 4], [4, 5.5], [5.5, maxY]]) {
      if (lo >= maxY) continue;
      const top = yDeg(Math.min(hi, maxY));
      svg.append(svgEl('rect', { class: `zone ${condClass(conditionOf((lo + hi) / 2))}`, 'fill-opacity': 0.1, x: LEFT, y: top, width: RIGHT - LEFT, height: yDeg(lo) - top }));
    }
  }
  for (let k = 0; k <= 6; k++) {
    const y = yFrac(k / 6);
    svg.append(svgEl('line', { class: 'grid', x1: LEFT, x2: RIGHT, y1: y, y2: y }));
    if (shows.deg) svg.append(svgEl('text', { class: 'axis', x: 2, y: y + 3 }, String(k * step)));
    if (shows.dist) svg.append(svgEl('text', { class: 'axis dist', x: RIGHT + 3, y: y + 3 }, `${350 + 10 * k}k`));
    if (shows.dec) svg.append(svgEl('text', { class: 'axis dec', x: RIGHT + 26, y: y + 3 }, `${-30 + 10 * k}°`));
  }
  for (const [i, text] of dayLabels(days.map(([iso]) => iso), todayIso, CENTER_INDEX)) {
    const half = (text.length * 5) / 2; // text width estimate for the 9 px label font
    svg.append(svgEl('text', { class: 'axis day-label', x: Math.min(W - half, Math.max(half, x(i))), y: BOTTOM + 12, 'text-anchor': 'middle' }, text));
  }
  const line = (cls, y) => svg.append(svgEl('path', { class: cls, d: days.map(([, r], i) => `${i === 0 ? 'M' : 'L'}${x(i)} ${y(r)}`).join(' ') }));
  if (shows.dist) line('line-dist', (r) => yDist(r.distanceKm));
  if (shows.dec) line('line-dec', (r) => yDec(r.decDeg));
  if (shows.deg) {
    line('line-deg', (r) => yDeg(r.degDb));
    days.forEach(([, r], i) => {
      svg.append(svgEl('circle', { class: `deg-dot ${condClass(conditionOf(r.degDb))}`, cx: x(i), cy: yDeg(r.degDb), r: 2.5 }));
    });
  }
  // Chosen day (middle) and today (thinner, when inside the graph)
  svg.append(svgEl('line', { class: 'center-line', x1: x(CENTER_INDEX), x2: x(CENTER_INDEX), y1: TOP, y2: BOTTOM }));
  const todayIndex = days.findIndex(([iso]) => iso === todayIso);
  if (todayIndex >= 0 && todayIndex !== CENTER_INDEX) svg.append(svgEl('line', { class: 'today-line', x1: x(todayIndex), x2: x(todayIndex), y1: TOP, y2: BOTTOM }));
  // Full and new moon: only the day closest to it
  const gap = (j, target) => {
    if (j < 0 || j >= ages.length) return 999;
    const a = Math.abs(ages[j] - target) % 360;
    return Math.min(a, 360 - a);
  };
  const nearest = (target, i) => gap(i, target) < 6.5 && gap(i, target) <= gap(i - 1, target) && gap(i, target) < gap(i + 1, target);
  ages.forEach((_, i) => {
    if (nearest(0, i)) svg.append(svgEl('circle', { class: 'new-moon', cx: x(i), cy: BOTTOM - 6, r: 4 }));
    if (nearest(180, i)) svg.append(svgEl('circle', { class: 'full-moon', cx: x(i), cy: BOTTOM - 6, r: 4 }));
  });
  return svg;
}

/** Screen contract { mount(el, ctx), unmount() }. */
export function createDegradationScreen() {
  let timer = null;
  let cleanup = null;

  return {
    mount(host, ctx) {
      const { settings, t } = ctx;
      const lang = effectiveLanguage(settings);
      const pos = locatorLatLon(settings);
      const locator = settings.locator;
      const sysCountry = regionOf(typeof navigator !== 'undefined' ? navigator.language : '');
      const firstDay = firstDayOfWeek(lang, sysCountry);

      let band = EME_BANDS.find((b) => b.id === settings.degBand) ?? EME_BANDS[EME_BANDS.length - 1];
      let now = ctx.now();
      let centerIso = isoOfMs(now);
      let calendar = null; // { year, month } while open
      const shows = { deg: true, dist: true, dec: true };

      const condLabel = (c) => t(`deg_${c.toLowerCase()}`);
      const dbText = (v) => `${v.toFixed(1)} dB`;

      const onKey = (e) => {
        if (e.key === 'Escape') closeCalendar();
      };
      function closeCalendar() {
        calendar = null;
        document.removeEventListener('keydown', onKey);
        render();
      }
      function openCalendar() {
        calendar = { year: Number(centerIso.slice(0, 4)), month: Number(centerIso.slice(5, 7)) };
        document.addEventListener('keydown', onKey);
        render();
      }
      const moveMonth = (delta) => {
        const index = calendar.year * 12 + calendar.month - 1 + delta;
        calendar = { year: Math.floor(index / 12), month: (index % 12) + 1 };
        render();
      };

      function renderCalendar(tsys, todayIso) {
        const overlay = tagged(el('div', 'deg-dialog'), 'calendar');
        overlay.setAttribute('role', 'dialog');
        overlay.setAttribute('aria-modal', 'true');
        overlay.addEventListener('click', (e) => {
          if (e.target === overlay) closeCalendar();
        });
        const card = el('div', 'card');
        const days = calendarDays(calendar.year, calendar.month);
        const bestSet = pos ? bestDaysOfMonth(calendar.year, calendar.month, pos.latDeg, pos.lonDeg, band, tsys, BEST_DAYS_PER_MONTH) : new Set();
        const title = new Intl.DateTimeFormat(lang, { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(days[0].noonMs);
        const prev = button('‹', 'prev-month', () => moveMonth(-1), 'icon');
        prev.dataset.action = 'prev-month';
        prev.setAttribute('aria-label', t('deg_prev_month'));
        const next = button('›', 'next-month', () => moveMonth(1), 'icon');
        next.dataset.action = 'next-month';
        next.setAttribute('aria-label', t('deg_next_month'));
        card.append(el('div', 'cal-head'));
        card.firstChild.append(prev, tagged(el('span', 'cal-title', title.charAt(0).toLocaleUpperCase(lang) + title.slice(1)), 'cal-title'), next);

        const grid = el('div', 'cal-grid');
        for (let i = 0; i < 7; i++) {
          const weekday = ((firstDay - 1 + i) % 7) + 1; // ISO 1..7; 2024-01-01 is a Monday
          grid.append(tagged(el('span', 'weekday', new Intl.DateTimeFormat(lang, { weekday: 'short', timeZone: 'UTC' }).format(Date.UTC(2024, 0, weekday, 12))), 'weekday'));
        }
        for (const d of days) {
          const cell = button(String(d.day), `day-${d.iso}`, () => {
            centerIso = d.iso;
            closeCalendar();
          }, 'day');
          cell.dataset.date = d.iso;
          cell.dataset.noon = String(d.noonMs);
          if (d.iso === centerIso) cell.classList.add('selected');
          if (d.iso === todayIso) {
            cell.classList.add('today');
            cell.setAttribute('aria-current', 'date');
          }
          if (bestSet.has(d.iso)) cell.dataset.best = 'true';
          if (d.day === 1) cell.style.gridColumnStart = String(((d.weekday - firstDay + 7) % 7) + 1);
          grid.append(cell);
        }
        card.append(grid);
        if (bestSet.size > 0) {
          const legend = el('div', 'cal-legend');
          legend.append(el('span', 'ring'), el('span', '', t('deg_calendar_best', BEST_DAYS_PER_MONTH)));
          card.append(legend);
        }
        const close = button(t('back'), 'close', closeCalendar, 'text-button');
        close.dataset.action = 'close';
        card.append(close);
        overlay.append(card);
        return overlay;
      }

      function render() {
        const focusKey = document.activeElement?.dataset?.key;
        const tsys = band.defaultTsys;
        const todayIso = isoOfMs(now);
        const root = el('div', 'deg');

        const bands = el('div', 'bands');
        for (const b of EME_BANDS) {
          const chip = button(b.label, `band-${b.id}`, () => {
            band = b;
            saveSettings({ degBand: b.id });
            render();
          }, 'chip');
          chip.dataset.band = b.id;
          chip.setAttribute('aria-pressed', String(b === band));
          bands.append(chip);
        }
        const centerRow = el('div', 'center-row');
        centerRow.append(el('div', 'small', t('deg_center_day')));
        const pick = button(`📅  ${formatDay(centerIso, lang)}`, 'center', openCalendar, 'outlined');
        pick.dataset.role = 'center-button';
        const row = el('div', 'center-buttons');
        row.append(pick);
        if (centerIso !== todayIso) {
          row.append(tagged(button(t('deg_back_today'), 'today', () => {
            centerIso = todayIso;
            render();
          }, 'text-button'), 'today-button'));
        }
        centerRow.append(row);
        root.append(bands, centerRow);
        if (calendar) root.append(renderCalendar(tsys, todayIso));

        if (!pos) {
          root.append(el('div', 'error', t('no_locator')));
        } else {
          const current = computeDegradation(now, pos.latDeg, pos.lonDeg, band, tsys);
          const card = el('div', 'now');
          const head = el('div', 'values');
          head.append(
            tagged(el('span', 'big', dbText(current.degDb)), 'value'),
            tagged(el('span', `condition ${condClass(current.condition)}`, condLabel(current.condition)), 'condition'),
          );
          card.append(
            tagged(el('div', 'small accent', t('deg_now', locator)), 'now-title'),
            head,
            tagged(el('div', 'small', t('deg_details', current.tskyK.toFixed(0), current.tskyMinK.toFixed(0), String(tsys), current.rangeFactorDb.toFixed(2), current.distanceKm.toLocaleString(lang, { maximumFractionDigits: 0 }))), 'details'),
          );
          root.append(card);

          // 30 days around the chosen day at 12:00 UTC, as MMMonVHF
          const days = daily(shiftDays(centerIso, -CENTER_INDEX), DAYS, pos.latDeg, pos.lonDeg, band, tsys);
          const ages = days.map(([iso]) => phaseAt(noonOf(iso)).ageDeg);
          const chartTitle = t('deg_chart_title', formatDay(centerIso, lang));
          const chart = buildChart(days, ages, shows, todayIso);
          chart.setAttribute('aria-label', chartTitle);
          root.append(tagged(el('h3', '', chartTitle), 'chart-title'), chart);

          const series = el('div', 'series');
          for (const [id, key, cls] of [['deg', 'deg_series_deg', 'deg'], ['dist', 'deg_series_dist', 'dist'], ['dec', 'deg_series_dec', 'dec']]) {
            const chip = button('', `series-${id}`, () => {
              shows[id] = !shows[id];
              render();
            }, `chip series-${cls}`);
            chip.dataset.series = id;
            chip.setAttribute('aria-pressed', String(shows[id]));
            chip.append(el('span', 'swatch', '━ '), el('span', '', t(key)));
            series.append(chip);
          }
          const moons = el('div', 'moons');
          moons.append(el('span', 'dot full', '●'), el('span', '', ` ${t('deg_full_moon')}    `), el('span', 'dot new', '●'), el('span', '', ` ${t('deg_new_moon')}`));
          root.append(series, moons, el('h3', '', t('deg_best_days')));

          [...days].sort((a, b) => a[1].degDb - b[1].degDb).slice(0, 5).sort((a, b) => (a[0] < b[0] ? -1 : 1)).forEach(([iso, r]) => {
            const best = tagged(el('div', 'best'), 'best-row');
            best.dataset.date = iso;
            best.append(el('span', '', formatDay(iso, lang)), el('span', 'bold', dbText(r.degDb)), el('span', condClass(r.condition), condLabel(r.condition)));
            root.append(best);
          });
          root.append(el('p', 'small info', t('deg_info')));
        }

        host.replaceChildren(root);
        if (focusKey) host.querySelector(`[data-key="${focusKey}"]`)?.focus();
      }

      const tick = () => {
        now = ctx.now();
        render();
      };
      const sync = () => {
        stop();
        if (document.visibilityState === 'visible') {
          if (ctx.now() - now >= MINUTE_MS) tick(); // catch up after being hidden
          timer = setInterval(tick, MINUTE_MS);
        }
      };
      document.addEventListener('visibilitychange', sync);
      cleanup = () => {
        document.removeEventListener('visibilitychange', sync);
        document.removeEventListener('keydown', onKey);
      };
      render();
      if (document.visibilityState === 'visible') timer = setInterval(tick, MINUTE_MS);
    },

    unmount() {
      stop();
      cleanup?.();
      cleanup = null;
    },
  };

  function stop() {
    if (timer !== null) clearInterval(timer);
    timer = null;
  }
}

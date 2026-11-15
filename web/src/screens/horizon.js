// "My horizon" (horizon/HorizonScreen.kt): summary in the settings and the editor with the chart.
import { t } from '../i18n.js';
import { toLatLon } from '../lib/maidenhead.js';
import { parseSurvey } from '../lib/horizon-survey.js';
import { EME_BANDS } from '../lib/degradation.js';
import { KINDS, beamMarginDeg, chartSpans, chartTopDeg, moonTrack, parseAngle, trackSegments } from '../lib/horizon.js';

const SVG_NS = 'http://www.w3.org/2000/svg';
/** The survey tool of ON6ZG (on6zg/horizon-survey); the app only opens the page and sends nothing to it. */
export const SURVEY_URL = 'https://horizon.dl6zg.de/';
/** A survey is a few kB; a larger file is not a survey (as MAX_IMPORT_BYTES in HorizonScreen.kt). */
const MAX_IMPORT_BYTES = 1_000_000;
const DAY_MS = 86_400_000;

function el(tag, props = {}, ...children) {
  const node = Object.assign(document.createElement(tag), props);
  node.append(...children);
  return node;
}
function svgEl(tag, attrs = {}, text) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, String(v));
  if (text !== undefined) node.textContent = text;
  return node;
}
const role = (node, r) => {
  node.dataset.role = r;
  return node;
};

/** Number with at most 2 decimals, no trailing ".0" (as in HorizonScreen.kt). */
export const fmtAngle = (v) => String(Math.round(v * 100) / 100);

/** Summary block in the settings, with an Edit button. */
export function horizonSection(horizon, onEdit) {
  const edit = role(el('button', { type: 'button', className: 'button outlined', textContent: t('horizon_edit') }), 'horizon-edit');
  edit.addEventListener('click', onEdit);
  return [
    el('h2', { textContent: t('horizon_title') }),
    role(el('div', { textContent: t('horizon_summary', fmtAngle(horizon.minElDeg), horizon.obstacles.length) }), 'horizon-summary'),
    edit,
  ];
}

// Chart geometry (viewBox units, as the Canvas in HorizonScreen.kt at 360 dp wide)
const W = 360;
const H = 170;
const LEFT = 34;
const RIGHT = W - 6;
const TOP = 6;
const BOTTOM = H - 18;

/** Azimuth 0-360 left to right, elevation 0..top bottom to top: minimum and obstacles as blocks, today's moon track (UTC). */
export function horizonChart(horizon, locator, nowMillis) {
  const pos = toLatLon(locator);
  const dayStart = nowMillis - (nowMillis % DAY_MS);
  const segments = pos ? trackSegments(moonTrack(pos.latDeg, pos.lonDeg, dayStart, dayStart + DAY_MS)) : [];
  const yMax = chartTopDeg(horizon, Math.max(0, ...segments.flat().map((p) => p.elDeg)));
  const x = (az) => LEFT + ((RIGHT - LEFT) * az) / 360;
  const y = (e) => BOTTOM - ((BOTTOM - TOP) * Math.min(Math.max(e, 0), yMax)) / yMax;

  const svg = role(svgEl('svg', { viewBox: `0 0 ${W} ${H}`, class: 'chart', role: 'img' }), 'horizon-chart');
  for (let az = 0; az <= 360; az += 90) {
    svg.append(svgEl('line', { class: 'grid', x1: x(az), x2: x(az), y1: TOP, y2: BOTTOM }));
    svg.append(svgEl('text', { class: 'axis', x: x(az), y: H - 4, 'text-anchor': az === 0 ? 'start' : az === 360 ? 'end' : 'middle' }, `${az}°`));
  }
  for (let e = 0; e <= yMax; e += 10) {
    svg.append(svgEl('line', { class: 'grid', x1: LEFT, x2: RIGHT, y1: y(e), y2: y(e) }));
    svg.append(svgEl('text', { class: 'axis', x: LEFT - 4, y: y(e) + 3, 'text-anchor': 'end' }, `${e}°`));
  }
  if (horizon.minElDeg > 0) {
    svg.append(svgEl('rect', { class: 'minimum', x: LEFT, y: y(horizon.minElDeg), width: RIGHT - LEFT, height: BOTTOM - y(horizon.minElDeg) }));
  }
  for (const o of horizon.obstacles) {
    for (const [a, b] of chartSpans(o)) {
      svg.append(svgEl('rect', { class: o.kind === KINDS.BLOCKED ? 'blocked' : 'partial', x: x(a), y: y(o.maxElDeg), width: x(b) - x(a), height: BOTTOM - y(o.maxElDeg) }));
    }
  }
  for (const seg of segments) {
    const d = seg.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.azDeg).toFixed(1)},${y(p.elDeg).toFixed(1)}`).join(' ');
    svg.append(role(svgEl('path', { class: 'moon', d }), 'moon-track'));
  }
  return svg;
}

/**
 * The editor. saved: the stored horizon; savedDishM: dish diameter (0 = none); savedDishBand: band id the
 * dish is used on, for the margin; onSaved(horizon, dishM, dishBand) stores them; onBack() leaves without saving.
 */
export function horizonEditor({ saved, locator, nowMillis, savedDishM = 0, savedDishBand = 'B1296', onSaved, onBack }) {
  let dishBand = EME_BANDS.some((b) => b.id === savedDishBand) ? savedDishBand : 'B1296';
  const bandMhz = () => EME_BANDS.find((b) => b.id === dishBand).mhz;
  let minText = fmtAngle(saved.minElDeg);
  let dishText = savedDishM > 0 ? fmtAngle(savedDishM) : '';
  // Empty = no dish; otherwise a diameter up to 100 m
  const dishValue = () => (dishText.trim() === '' ? 0 : parseAngle(dishText, 100));
  const rows = saved.obstacles.map((o) => ({ from: fmtAngle(o.fromAzDeg), to: fmtAngle(o.toAzDeg), el: fmtAngle(o.maxElDeg), kind: o.kind }));

  const root = el('div', { className: 'settings horizon' });
  const chartHost = el('div');
  const list = el('div', { className: 'obstacles' });
  const save = role(el('button', { type: 'button', className: 'button', textContent: t('save') }), 'horizon-save');
  const back = role(el('button', { type: 'button', className: 'text-button', textContent: t('back') }), 'horizon-back');

  const toObstacle = (r) => {
    const from = parseAngle(r.from, 360);
    const to = parseAngle(r.to, 360);
    const maxEl = parseAngle(r.el, 90);
    return from === null || to === null || maxEl === null ? null : { fromAzDeg: from, toAzDeg: to, maxElDeg: maxEl, kind: r.kind };
  };
  const current = () => {
    const minElDeg = parseAngle(minText, 90);
    const obstacles = rows.map(toObstacle);
    const dish = dishValue();
    return { valid: minElDeg !== null && obstacles.every((o) => o !== null) && dish !== null, minElDeg, obstacles, dish };
  };

  // Field with the same rules as AngleField in HorizonScreen.kt: red and "0 - max" when wrong.
  function angleField(label, value, max, onInput, r) {
    const input = role(el('input', { type: 'text', inputMode: 'decimal', value, spellcheck: false }), r);
    const support = el('div', { className: 'support' });
    const field = el('label', { className: 'field' }, label, input, support);
    const check = () => {
      const ok = parseAngle(input.value, max) !== null;
      field.classList.toggle('invalid', !ok);
      support.textContent = ok ? '' : t('horizon_invalid', max);
    };
    input.addEventListener('input', () => {
      input.value = input.value.slice(0, 6);
      onInput(input.value);
      check();
      refresh();
    });
    check();
    return field;
  }

  function renderRows() {
    list.replaceChildren(
      ...rows.map((r, i) => {
        const kindButton = (kind, label) => {
          const b = role(el('button', { type: 'button', className: `chip${r.kind === kind ? ' selected' : ''}`, textContent: t(label) }), `kind-${kind}`);
          b.setAttribute('aria-pressed', String(r.kind === kind));
          b.addEventListener('click', () => {
            r.kind = kind;
            renderRows();
            refresh();
          });
          return b;
        };
        const del = role(el('button', { type: 'button', className: 'icon-button', textContent: '🗑', title: t('horizon_delete') }), 'horizon-delete');
        del.setAttribute('aria-label', t('horizon_delete'));
        del.addEventListener('click', () => {
          rows.splice(i, 1);
          renderRows();
          refresh();
        });
        return el('div', { className: 'card obstacle' },
          el('div', { className: 'row2' },
            angleField(t('horizon_from'), r.from, 360, (v) => (r.from = v), 'from'),
            angleField(t('horizon_to'), r.to, 360, (v) => (r.to = v), 'to')),
          el('div', { className: 'row2' }, angleField(t('horizon_up_to'), r.el, 90, (v) => (r.el = v), 'up-to'), del),
          el('div', { className: 'chips' }, kindButton(KINDS.BLOCKED, 'horizon_blocked'), kindButton(KINDS.PARTIAL, 'horizon_partial')));
      }),
    );
  }

  function refresh() {
    const c = current();
    save.disabled = !c.valid;
    // The chart shows what is already valid, so a change is visible at once
    chartHost.replaceChildren(horizonChart({ minElDeg: c.minElDeg ?? saved.minElDeg, obstacles: c.obstacles.filter((o) => o !== null) }, locator, nowMillis));
  }

  // Measure with horizon-survey of ON6ZG and load the file here: replaces the obstacles on the screen (not saved yet)
  const measure = role(
    el('a', { className: 'button outlined', href: SURVEY_URL, target: '_blank', rel: 'noopener noreferrer', textContent: t('horizon_measure') }),
    'horizon-measure',
  );
  const fileInput = role(el('input', { type: 'file', accept: '.json,.csv,application/json,text/csv', hidden: true }), 'horizon-file');
  const load = role(el('button', { type: 'button', className: 'button outlined', textContent: t('horizon_load') }), 'horizon-load');
  const importMessage = role(el('div', { className: 'message' }), 'horizon-import-message');
  load.addEventListener('click', () => fileInput.click());
  fileInput.addEventListener('change', async () => {
    const file = fileInput.files?.[0];
    fileInput.value = '';
    if (!file) return;
    const text = file.size > MAX_IMPORT_BYTES ? null : await file.text().catch(() => null);
    const r = text === null ? { status: 'NOT_A_SURVEY' } : parseSurvey(text);
    if (r.status === 'OK') {
      rows.splice(0, rows.length, ...r.obstacles.map((o) => ({ from: fmtAngle(o.fromAzDeg), to: fmtAngle(o.toAzDeg), el: fmtAngle(o.maxElDeg), kind: o.kind })));
      importMessage.textContent = t('horizon_loaded', r.points, r.obstacles.length);
      importMessage.classList.remove('error');
      renderRows();
      refresh();
    } else {
      importMessage.textContent = t(r.status === 'TOO_FEW_POINTS' ? 'horizon_load_few' : 'horizon_load_error');
      importMessage.classList.add('error');
    }
  });

  const add = role(el('button', { type: 'button', className: 'button outlined', textContent: t('horizon_add') }), 'horizon-add');
  add.addEventListener('click', () => {
    rows.push({ from: '', to: '', el: '', kind: KINDS.BLOCKED });
    renderRows();
    refresh();
  });
  save.addEventListener('click', () => {
    const c = current();
    if (c.valid) onSaved({ minElDeg: c.minElDeg, obstacles: c.obstacles }, c.dish, dishBand);
  });
  back.addEventListener('click', onBack);

  // Dish diameter: margin of half the beamwidth above the horizon (as HorizonScreen.kt)
  const dishInput = role(el('input', { type: 'text', inputMode: 'decimal', value: dishText, spellcheck: false }), 'dish');
  const dishSupport = role(el('div', { className: 'support' }), 'dish-support');
  const dishField = el('label', { className: 'field' }, t('horizon_dish'), dishInput, dishSupport);
  const refreshDish = () => {
    const d = dishValue();
    dishField.classList.toggle('invalid', d === null);
    dishSupport.textContent =
      d === null ? t('horizon_invalid', 100) : d > 0 ? t('horizon_margin', fmtAngle(bandMhz()), beamMarginDeg(d, bandMhz()).toFixed(2)) : t('horizon_dish_info');
  };
  // Band the dish is used on (not the Deg tab), for the margin
  const bandChips = el('div', { className: 'chips' });
  const renderBands = () =>
    bandChips.replaceChildren(
      ...EME_BANDS.map((b) => {
        const chip = role(el('button', { type: 'button', className: `chip${b.id === dishBand ? ' selected' : ''}`, textContent: b.label }), `dish-band-${b.id}`);
        chip.setAttribute('aria-pressed', String(b.id === dishBand));
        chip.addEventListener('click', () => {
          dishBand = b.id;
          renderBands();
          refreshDish();
        });
        return chip;
      }),
    );
  renderBands();
  dishInput.addEventListener('input', () => {
    dishInput.value = dishInput.value.slice(0, 6);
    dishText = dishInput.value;
    refreshDish();
    refresh();
  });

  root.append(
    el('h2', { textContent: t('horizon_title') }),
    el('div', { className: 'support', textContent: t('horizon_info') }),
    el('div', { className: 'support', textContent: t('horizon_measure_info') }),
    el('div', { className: 'button-row wrap' }, measure, load, fileInput),
    importMessage,
    chartHost,
    el('div', { className: 'legend moon', textContent: `— ${t('horizon_moon_today')}` }),
    angleField(t('horizon_min_el'), minText, 90, (v) => (minText = v), 'min-el'),
    list,
    add,
    dishField,
    el('div', { className: 'support', textContent: t('horizon_dish_band') }),
    bandChips,
    el('div', { className: 'button-row' }, save, back),
  );
  renderRows();
  refreshDish();
  refresh();
  return root;
}

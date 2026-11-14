// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { createDegradationScreen } from '../src/screens/degradation.js';
import { setLanguage, t } from '../src/i18n.js';
import { bestDaysOfMonth, computeDegradation, daily, EME_BANDS } from '../src/lib/degradation.js';
import { locatorLatLon } from '../src/settings.js';
import { withTimeZone } from './tz.js';

const NOW = Date.parse('2026-11-14T10:00:00Z');
const pos = locatorLatLon({ locator: 'JO21SC' });
const band = (id) => EME_BANDS.find((b) => b.id === id);
let host;
let screen;
let restoreTz = () => {};

const settings = (over = {}) => ({ locator: 'JO21SC', callsign: 'ON5GS', cqBands: new Set(), degBand: 'B144', language: 'en', ...over });
const ctxOf = (s) => ({ settings: s, t, now: () => Date.now() });
const q = (role) => host.querySelector(`[data-role="${role}"]`);
const qa = (sel) => [...host.querySelectorAll(sel)];
const openCalendar = () => q('center-button').click();
const best = (b, tsys, y = 2026, m = 11) => bestDaysOfMonth(y, m, pos.latDeg, pos.lonDeg, band(b), tsys, 4);

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
  setLanguage('en');
  localStorage.clear();
  host = document.createElement('div');
  document.body.append(host);
  screen = createDegradationScreen();
});
afterEach(() => {
  screen.unmount();
  restoreTz();
  restoreTz = () => {};
  vi.useRealTimers();
  vi.restoreAllMocks();
  host.remove();
});

describe('deg screen: value now', () => {
  test('shows the value with 1 decimal, the condition text and the details line', () => {
    screen.mount(host, ctxOf(settings()));
    const r = computeDegradation(NOW, pos.latDeg, pos.lonDeg, band('B144'), 80);
    expect(q('value').textContent).toMatch(/^\d+\.\d dB$/);
    expect(q('value').textContent).toBe(`${r.degDb.toFixed(1)} dB`);
    expect(q('condition').textContent).toBe(t(`deg_${r.condition.toLowerCase()}`));
    expect(q('now-title').textContent).toBe(t('deg_now', 'JO21SC'));
    expect(q('details').textContent).toBe(
      t('deg_details', r.tskyK.toFixed(0), r.tskyMinK.toFixed(0), '80', r.rangeFactorDb.toFixed(2), r.distanceKm.toLocaleString('en', { maximumFractionDigits: 0 })),
    );
  });

  test('condition colour is a CSS class backed by a variable, no inline colour', () => {
    screen.mount(host, ctxOf(settings()));
    expect(q('condition').className).toMatch(/cond-[a-z-]+/);
    expect(q('condition').getAttribute('style') ?? '').not.toMatch(/#|rgb/);
  });

  test('band picker: the stored band is selected, a click saves degBand and recomputes with that Tsys', () => {
    screen.mount(host, ctxOf(settings({ degBand: 'B1296' })));
    expect(qa('[data-band][aria-pressed="true"]').map((b) => b.dataset.band)).toEqual(['B1296']);
    expect(qa('[data-band]').map((b) => b.textContent)).toEqual(['50', '144', '222', '432', '902', '1296']);
    qa('[data-band]')[3].click();
    expect(JSON.parse(localStorage.getItem('eme.settings')).degBand).toBe('B432');
    const r = computeDegradation(NOW, pos.latDeg, pos.lonDeg, band('B432'), 60);
    expect(q('value').textContent).toBe(`${r.degDb.toFixed(1)} dB`);
    expect(q('details').textContent).toContain('Tsys 60 K');
    expect(qa('[data-band][aria-pressed="true"]').map((b) => b.dataset.band)).toEqual(['B432']);
  });

  test('the value is recomputed every minute', () => {
    screen.mount(host, ctxOf(settings()));
    const before = q('details').textContent;
    vi.advanceTimersByTime(30 * 60_000);
    expect(q('details').textContent).not.toBe(before);
  });
});

describe('deg screen: no locator (Review Focus 1)', () => {
  test('message only: no numbers, no NaN, no chart, no exception', () => {
    screen.mount(host, ctxOf(settings({ locator: '' })));
    expect(host.textContent).toContain(t('no_locator'));
    expect(q('value')).toBeNull();
    expect(host.querySelector('svg')).toBeNull();
    vi.advanceTimersByTime(61_000);
    expect(host.textContent).not.toMatch(/NaN|dB|undefined|Infinity/);
  });

  test('the calendar still opens, with no best-day circles and no legend', () => {
    screen.mount(host, ctxOf(settings({ locator: '' })));
    openCalendar();
    expect(q('calendar')).not.toBeNull();
    expect(qa('[data-best]')).toHaveLength(0);
    expect(q('calendar').textContent).not.toContain(t('deg_calendar_best', 4));
  });
});

describe('deg screen: graph', () => {
  test('SVG with 30 degradation dots, "today" labelled, title with the chosen day', () => {
    screen.mount(host, ctxOf(settings()));
    const svg = q('chart');
    expect(svg.namespaceURI).toBe('http://www.w3.org/2000/svg');
    expect(svg.querySelectorAll('circle.deg-dot')).toHaveLength(30);
    expect([...svg.querySelectorAll('text.day-label')].map((e) => e.textContent)).toContain('today');
    expect(q('chart-title').textContent).toBe(t('deg_chart_title', 'Sat 14-11-2026'));
  });

  test('the dots sit at the daily() values for 30 Oct to 28 Nov (15 days before the chosen day)', () => {
    screen.mount(host, ctxOf(settings()));
    const days = daily('2026-10-30', 30, pos.latDeg, pos.lonDeg, band('B144'), 80);
    const max = Math.max(...days.map(([, r]) => r.degDb));
    const step = max <= 5.5 ? 1 : max <= 11.5 ? 2 : 3;
    const dots = qa('circle.deg-dot');
    days.forEach(([, r], i) => {
      const y = 212 - 204 * Math.min(1, Math.max(0, r.degDb / (6 * step)));
      expect(Number(dots[i].getAttribute('cy'))).toBeCloseTo(y, 1);
    });
  });

  test('series chips hide and show their line', () => {
    screen.mount(host, ctxOf(settings()));
    for (const [series, cls] of [['deg', 'line-deg'], ['dist', 'line-dist'], ['dec', 'line-dec']]) {
      const chip = () => host.querySelector(`[data-series="${series}"]`);
      expect(q('chart').querySelector(`.${cls}`)).not.toBeNull();
      chip().click();
      expect(q('chart').querySelector(`.${cls}`)).toBeNull();
      expect(chip().getAttribute('aria-pressed')).toBe('false');
      chip().click();
      expect(q('chart').querySelector(`.${cls}`)).not.toBeNull();
    }
  });

  test('full and new moon markers are drawn (30 days hold at least one of each)', () => {
    screen.mount(host, ctxOf(settings()));
    expect(qa('circle.full-moon').length).toBeGreaterThanOrEqual(1);
    expect(qa('circle.new-moon').length).toBeGreaterThanOrEqual(1);
  });

  test('best days list: the 5 lowest, in date order, with value and condition', () => {
    screen.mount(host, ctxOf(settings()));
    const days = daily('2026-10-30', 30, pos.latDeg, pos.lonDeg, band('B144'), 80);
    const five = [...days].sort((a, b) => a[1].degDb - b[1].degDb).slice(0, 5).sort((a, b) => (a[0] < b[0] ? -1 : 1));
    const rows = qa('[data-role="best-row"]');
    expect(rows).toHaveLength(5);
    rows.forEach((row, i) => {
      const [iso, r] = five[i];
      expect(row.dataset.date).toBe(iso);
      expect(row.textContent).toContain(`${r.degDb.toFixed(1)} dB`);
      expect(row.textContent).toContain(t(`deg_${r.condition.toLowerCase()}`));
    });
  });
});

describe('deg screen: calendar', () => {
  test('opens on the chosen month; best days of November 2026 equal bestDaysOfMonth', () => {
    screen.mount(host, ctxOf(settings()));
    openCalendar();
    expect(q('cal-title').textContent).toBe('November 2026');
    const marked = new Set(qa('[data-best]').map((c) => c.dataset.date));
    expect(marked).toEqual(best('B144', 80));
    expect(marked.size).toBe(4);
    expect(q('calendar').textContent).toContain(t('deg_calendar_best', 4));
  });

  test('weekday header and first-day offset follow the browser country', () => {
    const nav = vi.spyOn(navigator, 'language', 'get').mockReturnValue('en-US');
    screen.mount(host, ctxOf(settings()));
    openCalendar();
    expect(qa('[data-role="weekday"]').map((e) => e.textContent)).toEqual(['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']);
    expect(qa('.day')[0].style.gridColumnStart).toBe('1');
    screen.unmount();
    nav.mockReturnValue('en-BE');
    screen = createDegradationScreen();
    screen.mount(host, ctxOf(settings()));
    openCalendar();
    expect(qa('[data-role="weekday"]')[0].textContent).toBe('Mon');
    expect(qa('.day')[0].style.gridColumnStart).toBe('7');
  });

  test('month arrows, selected and today marks', () => {
    screen.mount(host, ctxOf(settings()));
    openCalendar();
    const today = host.querySelector('.day[data-date="2026-11-14"]');
    expect(today.getAttribute('aria-current')).toBe('date');
    expect(today.classList.contains('selected')).toBe(true);
    host.querySelector('[data-action="next-month"]').click();
    expect(q('cal-title').textContent).toBe('December 2026');
    expect(qa('.day')).toHaveLength(31);
    host.querySelector('[data-action="prev-month"]').click();
    host.querySelector('[data-action="prev-month"]').click();
    expect(q('cal-title').textContent).toBe('October 2026');
    expect(new Set(qa('[data-best]').map((c) => c.dataset.date))).toEqual(best('B144', 80, 2026, 10));
  });

  test('picking a day closes the calendar and centres the graph; "back to today" returns', () => {
    screen.mount(host, ctxOf(settings()));
    expect(q('today-button')).toBeNull();
    openCalendar();
    host.querySelector('.day[data-date="2026-11-24"]').click();
    expect(q('calendar')).toBeNull();
    expect(q('center-button').textContent).toContain('Tue 24-11-2026');
    expect(q('chart-title').textContent).toBe(t('deg_chart_title', 'Tue 24-11-2026'));
    expect(q('today-button').textContent).toBe(t('deg_back_today'));
    q('today-button').click();
    expect(q('center-button').textContent).toContain('Sat 14-11-2026');
    expect(q('today-button')).toBeNull();
  });

  test('Escape and the close button close the calendar without picking', () => {
    screen.mount(host, ctxOf(settings()));
    openCalendar();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(q('calendar')).toBeNull();
    openCalendar();
    host.querySelector('[data-action="close"]').click();
    expect(q('calendar')).toBeNull();
    expect(q('center-button').textContent).toContain('14-11-2026');
  });

  test('Review Focus 5: TZ=America/New_York, local 1 Nov 21:00 is already 2 Nov in UTC', () => {
    restoreTz = withTimeZone('America/New_York');
    vi.setSystemTime(Date.parse('2026-11-02T02:00:00Z'));
    expect(new Date().getDate()).toBe(1);
    screen.mount(host, ctxOf(settings()));
    expect(q('center-button').textContent).toContain('Mon 02-11-2026');
    openCalendar();
    expect(host.querySelector('.day[aria-current="date"]').dataset.date).toBe('2026-11-02');
    const first = host.querySelector('.day[data-date="2026-11-01"]');
    expect(first.textContent).toBe('1');
    expect(first.dataset.noon).toBe(String(Date.UTC(2026, 10, 1, 12)));
    expect(qa('.day').map((d) => d.dataset.date)).toEqual(Array.from({ length: 30 }, (_, i) => `2026-11-${String(i + 1).padStart(2, '0')}`));
    expect(new Set(qa('[data-best]').map((c) => c.dataset.date))).toEqual(best('B144', 80));
    host.querySelector('.day[data-date="2026-11-01"]').click();
    expect(q('center-button').textContent).toContain('Sun 01-11-2026');
    expect(q('chart-title').textContent).toBe(t('deg_chart_title', 'Sun 01-11-2026'));
  });
});

describe('deg screen: lifecycle', () => {
  test('timers pause while hidden and resume when visible', () => {
    let state = 'visible';
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => state);
    screen.mount(host, ctxOf(settings()));
    expect(vi.getTimerCount()).toBe(1);
    state = 'hidden';
    document.dispatchEvent(new Event('visibilitychange'));
    expect(vi.getTimerCount()).toBe(0);
    state = 'visible';
    document.dispatchEvent(new Event('visibilitychange'));
    expect(vi.getTimerCount()).toBe(1);
  });

  test('unmount stops timers and removes all listeners', () => {
    const remove = vi.spyOn(document, 'removeEventListener');
    screen.mount(host, ctxOf(settings()));
    openCalendar();
    screen.unmount();
    expect(remove).toHaveBeenCalledWith('visibilitychange', expect.any(Function));
    expect(remove).toHaveBeenCalledWith('keydown', expect.any(Function));
    expect(vi.getTimerCount()).toBe(0);
  });
});

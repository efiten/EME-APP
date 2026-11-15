// @vitest-environment jsdom
import { decode } from '../src/lib/horizon.js';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { createLiveCqScreen } from '../src/screens/livecq.js';
import { createLiveCqStore } from '../src/data.js';
import { BANDS, mergeSpots, parseN6nu, parseW3sz, selectSpots, sourceLabel, stationLatLon } from '../src/lib/livecq.js';
import { position } from '../src/lib/moon-calculator.js';
import { setLanguage, t } from '../src/i18n.js';
import { loadSettings, locatorLatLon } from '../src/settings.js';
import { callsTo, fails, ok, resource, stubFetch } from './fetch-stub.js';

const W3SZ = '/api/livecq/w3sz';
const N6NU = '/api/livecq/n6nu';
const NOW = Date.parse('2026-11-13T21:30:00Z');
const csvText = resource('livecq_sample.csv');
const jsonText = resource('n6nu_spots.json');
const ALL = new Set(BANDS.map((b) => b.id));

let host;
let screen;
let store;
const settings = (over = {}) => ({ locator: 'JO21SC', callsign: 'ON5GS', cqBands: new Set(ALL), language: 'en', ...over });
const mount = async (s = settings()) => {
  screen.mount(host, { settings: s, t, now: () => Date.now(), liveCq: store });
  await vi.advanceTimersByTimeAsync(0);
};
const q = (role) => host.querySelector(`[data-role="${role}"]`);
const cards = () => [...host.querySelectorAll('[data-role="spot"]')];
const expectedAt = (bands, now = NOW) => selectSpots(mergeSpots(parseW3sz(csvText), parseN6nu(jsonText)), bands, now, 60);

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
  setLanguage('en');
  const m = new Map();
  vi.stubGlobal('localStorage', { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => void m.set(k, String(v)) });
  stubFetch({ [W3SZ]: ok(csvText, { 'X-Fetched-At': String(NOW - 61_000) }), [N6NU]: ok(jsonText, { 'X-Fetched-At': String(NOW - 5_000) }) });
  store = createLiveCqStore();
  host = document.createElement('div');
  document.body.append(host);
  screen = createLiveCqScreen();
});
afterEach(() => {
  screen.unmount();
  host.remove();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('Live CQ screen', () => {
  test('lists the spots of both sources exactly as selectSpots with the saved bands and a 60 minute window', async () => {
    await mount();
    const expected = expectedAt(ALL);
    expect(expected.length).toBeGreaterThan(0);
    expect(cards().map((c) => c.querySelector('.call').textContent)).toEqual(expected.map((s) => s.callsign));
    expect(cards().map((c) => c.querySelector('.source').textContent)).toEqual(expected.map(sourceLabel));
    expect(q('summary').textContent).toBe(t('cq_summary', expected.length, 60, t('updated', '21:28')));
  });

  test('W3SZ spots are listed too, with their source', async () => {
    const at = Date.parse('2026-11-04T14:00:00Z');
    vi.setSystemTime(at);
    await mount(settings({ cqBands: new Set(['B144']) }));
    const expected = expectedAt(new Set(['B144']), at);
    expect(expected.map(sourceLabel)).toContain('W3SZ');
    expect(cards().map((c) => c.querySelector('.call').textContent)).toEqual(expected.map((s) => s.callsign));
  });

  test('a card shows locator, frequency, mode, dB, age, via and the moon at the station', async () => {
    await mount();
    const s = expectedAt(ALL)[0];
    const text = cards()[0].textContent;
    expect(text).toContain(s.grid || '----');
    expect(text).toContain(`${s.freqMhz.toFixed(3)} MHz`);
    expect(text).toContain(s.mode);
    expect(text).toContain(`${s.db} dB`);
    expect(text).toContain(t('via', s.spotter));
    const p = stationLatLon(s.grid);
    const m = p && position(p.latDeg, p.lonDeg, NOW);
    expect(text).toContain(!p ? t('moon_no_locator') : m.elevationDeg > 0 ? t('moon_up_el', m.elevationDeg.toFixed(0)) : t('moon_down'));
    expect(text).not.toMatch(/NaN|undefined/);
  });

  test('only the saved bands are listed; a chip click saves cqBands and updates the list', async () => {
    await mount(settings({ cqBands: new Set(['B144']) }));
    expect(cards().length).toBe(expectedAt(new Set(['B144'])).length);
    const chip = (id) => host.querySelector(`[data-band="${id}"]`);
    expect(chip('B144').getAttribute('aria-pressed')).toBe('true');
    expect(chip('B50').getAttribute('aria-pressed')).toBe('false');
    chip('B50').click();
    expect([...loadSettings().cqBands].sort()).toEqual(['B144', 'B50']);
    expect(cards().length).toBe(expectedAt(new Set(['B144', 'B50'])).length);
    chip('B144').click();
    chip('B50').click();
    expect([...loadSettings().cqBands]).toEqual([]);
    expect(host.textContent).toContain(t('choose_band'));
  });

  test('the All/None chip toggles every band', async () => {
    await mount(settings({ cqBands: new Set(['B144']) }));
    q('all').click();
    expect(loadSettings().cqBands.size).toBe(BANDS.length);
    expect(q('all').textContent).toBe(t('band_none'));
    q('all').click();
    expect(loadSettings().cqBands.size).toBe(0);
    expect(q('all').textContent).toBe(t('band_all'));
  });

  test('a network error keeps the previous list and shows the error text', async () => {
    const f = stubFetch({ [W3SZ]: ok(csvText), [N6NU]: ok(jsonText) });
    await mount();
    const before = cards().map((c) => c.querySelector('.call').textContent);
    expect(before.length).toBeGreaterThan(0);
    expect(q('error')).toBeNull();
    f.handlers[W3SZ] = fails('Failed to fetch');
    f.handlers[N6NU] = fails('Failed to fetch');
    vi.setSystemTime(NOW + 30_000);
    q('refresh').click();
    await vi.advanceTimersByTimeAsync(0);
    expect(q('error').textContent).toBe(t('livecq_error', 'Failed to fetch; Failed to fetch'));
    expect(cards().map((c) => c.querySelector('.call').textContent).filter((c) => before.includes(c)).length).toBeGreaterThan(0);
  });

  test('repeated refresh taps within 30 s send no second request', async () => {
    const f = stubFetch({ [W3SZ]: ok(csvText), [N6NU]: ok(jsonText) });
    await mount();
    expect(callsTo(f, W3SZ)).toBe(1);
    for (let i = 0; i < 3; i++) {
      q('refresh').click();
      await vi.advanceTimersByTimeAsync(5_000);
    }
    expect(callsTo(f, W3SZ)).toBe(1);
    await vi.advanceTimersByTimeAsync(20_000);
    q('refresh').click();
    await vi.advanceTimersByTimeAsync(0);
    expect(callsTo(f, W3SZ)).toBe(2);
    expect(callsTo(f, N6NU)).toBe(2);
  });

  test('shows the moon elevation at your QTH only with a valid locator', async () => {
    await mount();
    const me = locatorLatLon(settings());
    const el = position(me.latDeg, me.lonDeg, NOW).elevationDeg;
    expect(q('mymoon').textContent).toBe(t('moon_at_you', el.toFixed(1)));
    screen.unmount();
    await mount(settings({ locator: '' }));
    expect(q('mymoon')).toBeNull();
    expect(host.textContent).not.toMatch(/NaN/);
  });

  test('the moon at your QTH is coloured by the own horizon', async () => {
    vi.setSystemTime(Date.parse('2026-11-20T18:00:00Z')); // moon up at JO21SC
    await mount();
    expect(q('mymoon').classList.contains('up')).toBe(true); // no own horizon: green above 0 deg, as before
    screen.unmount();
    await mount(settings({ horizon: decode('1;0;P 0 360 90') }));
    expect(q('mymoon').classList.contains('partial')).toBe(true);
    screen.unmount();
    await mount(settings({ horizon: decode('1;0;B 0 360 90') }));
    expect(q('mymoon').classList.contains('down')).toBe(true);
  });

  test('unmount stops polling and a late response does not touch the DOM', async () => {
    const f = stubFetch({ [W3SZ]: ok(csvText), [N6NU]: ok(jsonText) });
    await mount();
    screen.unmount();
    host.replaceChildren();
    await vi.advanceTimersByTimeAsync(600_000);
    expect(callsTo(f, W3SZ)).toBe(1);
    expect(host.childElementCount).toBe(0);
  });

  test('mode and spotter text is inserted as text, never as markup; a callsign with markup is dropped by the parser', async () => {
    const lines = csvText.split(/\r?\n/);
    const evil = lines
      .map((l, i) => (i === 0 ? l : l.replace(',JT65B,', ',<img src=x onerror=alert(1)>,').replace(/,(W9IP),/, ',<b>BOLD</b>,')))
      .map((l, i) => (i === 1 ? l.replace(',KA1EME,', ',<i>X</i>,') : l))
      .join('\n');
    stubFetch({ [W3SZ]: ok(evil), [N6NU]: ok(jsonText) });
    vi.setSystemTime(Date.parse('2026-11-04T14:00:00Z'));
    await mount();
    expect(cards().length).toBeGreaterThan(0);
    expect(host.textContent).toContain('<img src=x onerror=alert(1)>');
    expect(host.textContent).toContain('<B>BOLD</B>');
    expect(host.textContent).not.toContain('<i>X</i>');
    expect(host.querySelector('img, b, i')).toBeNull();
  });

  test('no .empty element while the list has spots', async () => {
    await mount();
    expect(cards().length).toBeGreaterThan(0);
    expect(host.querySelector('.empty')).toBeNull();
    await mount(settings({ cqBands: new Set() }));
    expect(host.querySelector('.empty').textContent).toBe(t('choose_band'));
  });
});

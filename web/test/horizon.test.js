import { describe, expect, test } from 'vitest';
import { readExpected } from './expected.js';
import { marginForSettings, beamMarginDeg, chartSpans, chartTopDeg, currentOrNextWindow, decode, isDefaultHorizon, encode, halfBeamwidthDeg, moonTrack, moonWindows, parseAngle, trackSegments, usableWindows, defaultHorizon, VISIBILITY, visibility } from '../src/lib/horizon.js';
import { position } from '../src/lib/moon-calculator.js';

const DAY = 86_400_000;

describe('own horizon (same table as HorizonTest.kt)', () => {
  test('visibility', () => {
    const rows = readExpected('horizon_visibility');
    expect(rows.length).toBeGreaterThanOrEqual(25);
    for (const r of rows) {
      expect(visibility(decode(r.horizon), Number(r.az), Number(r.el), Number(r.margin)), JSON.stringify(r)).toBe(r.expected);
    }
  });

  test('stored text format', () => {
    for (const r of readExpected('horizon_codec')) {
      const h = decode(r.text);
      if (r.canonical === '') expect(h, r.text).toBeNull();
      else expect(encode(h), r.text).toBe(r.canonical);
    }
  });

  test('encode and decode keep everything', () => {
    const h = { minElDeg: 4.5, obstacles: [{ fromAzDeg: 350, toAzDeg: 10, maxElDeg: 15.25, kind: 'B' }, { fromAzDeg: 180, toAzDeg: 200, maxElDeg: 20, kind: 'P' }] };
    expect(encode(h)).toBe('1;4.5;B 350 10 15.25;P 180 200 20');
    expect(decode(encode(h))).toEqual(h);
    expect(encode(defaultHorizon())).toBe('1;0');
  });

  test('half beamwidth of a 6 m dish', () => {
    expect(halfBeamwidthDeg(1296, 6)).toBeCloseTo(1.35, 2);
    expect(halfBeamwidthDeg(432, 6)).toBeCloseTo(4.05, 2);
  });
});

describe('moon windows (same table as MoonWindowsTest.kt)', () => {
  const parse = (text) =>
    text.split(' ').filter((x) => x !== '').map((w) => {
      const [range, v] = w.split('/');
      const [a, b] = range.split('-').map((hm) => { const [h, m] = hm.split(':').map(Number); return h * 60 + m; });
      return { start: a, end: b, v };
    });

  test('windows to within 1 minute', () => {
    for (const r of readExpected('horizon_windows')) {
      const start = Date.parse(`${r.date}T00:00:00Z`);
      const f = r.kind === 'all' ? moonWindows : usableWindows;
      const got = f(decode(r.horizon), Number(r.lat), Number(r.lon), start, start + DAY, Number(r.margin));
      const want = parse(r.windows);
      expect(got.length, JSON.stringify(r)).toBe(want.length);
      want.forEach((w, i) => {
        expect(Math.abs(w.start - (got[i].startMillis - start) / 60000), JSON.stringify(r)).toBeLessThanOrEqual(1);
        expect(Math.abs(w.end - (got[i].endMillis - start) / 60000), JSON.stringify(r)).toBeLessThanOrEqual(1);
        expect(got[i].visibility[0]).toBe(w.v);
      });
    }
  });

  test('every minute of a window has its visibility, and the windows join up', () => {
    const h = decode('1;5;B 60 110 25;P 180 200 20');
    const start = Date.parse('2027-01-04T00:00:00Z');
    const all = moonWindows(h, 51.1, 4.3, start, start + DAY);
    expect(all[0].startMillis).toBe(start);
    expect(all.at(-1).endMillis).toBe(start + DAY);
    for (let i = 1; i < all.length; i++) expect(all[i].startMillis).toBe(all[i - 1].endMillis);
    for (const w of all) {
      for (let t = w.startMillis; t < w.endMillis; t += 60_000) {
        const p = position(51.1, 4.3, t);
        expect(visibility(h, p.azimuthDeg, p.elevationDeg)).toBe(w.visibility);
      }
    }
  });

  test('a moon behind the obstacle all day gives no window', () => {
    const start = Date.parse('2026-11-20T00:00:00Z');
    expect(usableWindows({ minElDeg: 89, obstacles: [] }, 51.1, 4.3, start, start + DAY)).toEqual([]);
    expect(VISIBILITY.BLOCKED).toBe('BLOCKED');
  });
});

describe('"My horizon" screen logic (same as HorizonChartTest.kt)', () => {
  test('angles typed in a field', () => {
    for (const r of readExpected('horizon_input')) {
      const got = parseAngle(r.text, Number(r.max));
      expect(got, `'${r.text}'`).toBe(r.expected === '' ? null : Number(r.expected));
    }
    expect(parseAngle('25,5', 90)).toBe(25.5);
    expect(parseAngle('25,5,1', 90)).toBeNull();
  });

  test('chart top is 40 unless something is higher', () => {
    expect(chartTopDeg(defaultHorizon())).toBe(40);
    expect(chartTopDeg(decode('1;5;B 60 110 25;P 180 200 20'))).toBe(40);
    expect(chartTopDeg(decode('1;0;B 60 110 38'))).toBe(50);
    expect(chartTopDeg(decode('1;0;B 60 110 90'))).toBe(90);
    expect(chartTopDeg(decode('1;5;B 60 110 25'), 55.3)).toBe(60);
    expect(chartTopDeg(defaultHorizon(), 88)).toBe(90);
  });

  test('obstacle across north is drawn in two pieces', () => {
    expect(chartSpans({ fromAzDeg: 60, toAzDeg: 110, maxElDeg: 25, kind: 'B' })).toEqual([[60, 110]]);
    expect(chartSpans({ fromAzDeg: 350, toAzDeg: 10, maxElDeg: 15, kind: 'B' })).toEqual([[350, 360], [0, 10]]);
    expect(chartSpans({ fromAzDeg: 0, toAzDeg: 360, maxElDeg: 5, kind: 'B' })).toEqual([[0, 360]]);
  });

  test('moon track in pieces', () => {
    const start = Date.parse('2026-11-20T00:00:00Z');
    const track = moonTrack(51.1, 4.3, start, start + DAY);
    expect(track.length).toBe(145);
    const segs = trackSegments(track);
    expect(segs.length).toBe(2);
    for (const p of segs.flat()) expect(p.elDeg).toBeGreaterThan(0);
    const p = (az) => ({ millis: 0, azDeg: az, elDeg: 10 });
    expect(trackSegments([p(350), p(355), p(2), p(8)]).length).toBe(2);
  });
});

describe('current or next window (same table as MoonWindowsTest.kt)', () => {
  test('as in the shared table', () => {
    for (const r of readExpected('horizon_next')) {
      const w = currentOrNextWindow(decode(r.horizon), 51.1, 4.3, Date.parse(r.now_utc), Number(r.margin));
      if (r.start_utc === '') expect(w, JSON.stringify(r)).toBeNull();
      else {
        expect(Math.abs(w.startMillis - Date.parse(r.start_utc)), JSON.stringify(r)).toBeLessThanOrEqual(61_000);
        expect(Math.abs(w.endMillis - Date.parse(r.end_utc)), JSON.stringify(r)).toBeLessThanOrEqual(61_000);
        expect(w.visibility).toBe(r.visibility);
      }
    }
  });

  test('margin of a dish', () => {
    expect(beamMarginDeg(6, 1296)).toBeCloseTo(1.349, 3);
    expect(beamMarginDeg(0, 1296)).toBe(0); // no dish: no margin
  });

  test('margin only with an own horizon, on the band of the dish (not the Deg tab)', () => {
    const bands = [{ id: 'B432', mhz: 432 }, { id: 'B1296', mhz: 1296 }];
    expect(marginForSettings({ horizon: defaultHorizon(), dishDiameterM: 6, dishBand: 'B1296' }, bands)).toBe(0);
    expect(marginForSettings({ horizon: { minElDeg: 5, obstacles: [] }, dishDiameterM: 6, dishBand: 'B1296', degBand: 'B432' }, bands)).toBeCloseTo(1.349, 3);
    expect(marginForSettings({ horizon: { minElDeg: 5, obstacles: [] }, dishDiameterM: 6, dishBand: 'B432' }, bands)).toBeCloseTo(4.048, 3);
  });

  test('default horizon is recognised', () => {
    expect(isDefaultHorizon(defaultHorizon())).toBe(true);
    expect(isDefaultHorizon(decode('1;0'))).toBe(true);
    expect(isDefaultHorizon(decode('1;5'))).toBe(false);
    expect(isDefaultHorizon(decode('1;0;P 180 200 20'))).toBe(false);
  });
});

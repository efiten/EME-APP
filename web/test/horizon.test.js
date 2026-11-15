import { describe, expect, test } from 'vitest';
import { readExpected } from './expected.js';
import { decode, encode, halfBeamwidthDeg, moonWindows, usableWindows, defaultHorizon, VISIBILITY, visibility } from '../src/lib/horizon.js';
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

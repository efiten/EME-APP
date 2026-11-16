import { describe, expect, test } from 'vitest';
import { readExpected } from './expected.js';
import { selectSpots } from '../src/lib/livecq.js';

// #105: no spots of stations where the moon is below the horizon (same table as LiveCqMoonDownTest.kt)
const spot = (callsign, grid, t, source = 'W3SZ') => ({
  utcMillis: t - 60_000, callsign, grid, freqMhz: 1296.065, band: 'B23CM', mode: 'Q65-60C', db: '-20',
  spotter: 'PA3DRK', spotterGrid: 'JO21', sources: new Set([source]), dt: '', app: '',
});
const bands = new Set(['B23CM']);

describe('Live CQ hides stations with the moon down', () => {
  test('only stations with the moon up or without locator', () => {
    const rows = readExpected('livecq_moon_down');
    const now = Date.parse(rows[0].now_utc);
    const shown = new Set(selectSpots(rows.map((r) => spot(r.callsign, r.grid, now)), bands, now, 60).map((s) => s.callsign));
    for (const r of rows) expect(shown.has(r.callsign), `${r.callsign} ${r.grid}`).toBe(r.shown === 'true');
  });

  test('the locator from an older spot of the same station decides', () => {
    const now = Date.parse('2026-11-20T18:00:00Z');
    const spots = [spot('W2AA', '', now, 'N6NU'), spot('W2AA', 'FN20', now - 10 * 60_000, 'W3SZ')];
    expect(selectSpots(spots, bands, now, 60)).toEqual([]);
  });

  test('the moon now counts, not at the time of the spot', () => {
    const spotTime = Date.parse('2026-11-21T02:30:00Z'); // JO21SC: moonset about 02:54 UTC
    const s = spot('ON4AA', 'JO21SC', spotTime + 60_000);
    expect(selectSpots([s], bands, spotTime + 5 * 60_000, 60).map((x) => x.callsign)).toEqual(['ON4AA']);
    expect(selectSpots([s], bands, spotTime + 50 * 60_000, 60)).toEqual([]);
  });
});

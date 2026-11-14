import { describe, expect, test } from 'vitest';
import {
  CONDITIONS,
  EME_BANDS,
  bestDaysOfMonth,
  computeDegradation,
  conditionOf,
  daily,
} from '../src/lib/degradation.js';
import { readExpected } from './expected.js';

const bandOf = (label) => EME_BANDS.find((b) => b.label === label);

function dailyFor(row, days) {
  return daily(row.date, days, Number(row.lat), Number(row.lon), bandOf(row.band), Number(row.tsys));
}

describe('degradation', () => {
  test('bands and conditions', () => {
    expect(EME_BANDS.map((b) => b.id)).toEqual(['B50', 'B144', 'B222', 'B432', 'B902', 'B1296']);
    expect(CONDITIONS).toEqual(['EXCELLENT', 'GOOD', 'FAIR', 'POOR', 'VERY_POOR']);
  });

  // Reference: MMMonVHF eme.php, observer 48N 11E, 12:00 UTC, 144 MHz, Tsys 80 K.
  test('matchesMmmonvhfToday', () => {
    const row = readExpected('degradation_daily').find((r) => r.condition !== '');
    const [, r] = dailyFor(row, 1)[0];
    expect(Math.abs(Number(row.deg_db) - r.degDb)).toBeLessThanOrEqual(Number(row.tol));
    expect(r.condition).toBe(row.condition);
  });

  test('closeToMmmonvhfServerList', () => {
    const rows = readExpected('degradation_daily').filter((r) => r.condition === '');
    const days = new Map(dailyFor(rows[0], rows.length));
    for (const row of rows) {
      expect(Math.abs(Number(row.deg_db) - days.get(row.date).degDb), row.date).toBeLessThanOrEqual(Number(row.tol));
    }
  });

  test('conditions', () => {
    expect(conditionOf(1.5)).toBe('EXCELLENT');
    expect(conditionOf(2.5)).toBe('GOOD');
    expect(conditionOf(4.0)).toBe('FAIR');
    expect(conditionOf(4.2)).toBe('POOR');
    expect(conditionOf(5.6)).toBe('VERY_POOR');
  });

  test('higherBandsHaveLessSkyNoise', () => {
    const t = Date.parse('2026-11-10T12:00:00Z');
    const d144 = computeDegradation(t, 51.1, 5.5, bandOf('144'), 80);
    const d1296 = computeDegradation(t, 51.1, 5.5, bandOf('1296'), 50);
    expect(d1296.tskyK).toBeLessThan(d144.tskyK / 50);
    expect(Math.abs(d144.rangeFactorDb - d1296.rangeFactorDb)).toBeLessThanOrEqual(1e-9);
    expect(d1296.degDb).toBeLessThanOrEqual(d144.degDb);
  });
});

describe('bestDaysOfMonth', () => {
  const lat = 51.15;
  const lon = 4.29;
  const B144 = bandOf('144');

  test('fourLowestDaysOfJanuary2030', () => {
    const best = bestDaysOfMonth(2030, 1, lat, lon, B144, 80);
    const all = daily('2030-01-01', 31, lat, lon, B144, 80);

    expect(best.size).toBe(4);
    expect([...best].every((d) => d.startsWith('2030-01-'))).toBe(true);
    const worstChosen = Math.max(...all.filter(([d]) => best.has(d)).map(([, r]) => r.degDb));
    expect(all.filter(([d]) => !best.has(d)).every(([, r]) => r.degDb >= worstChosen)).toBe(true);
    const tiedLeftOut = all.filter(([d, r]) => !best.has(d) && r.degDb === worstChosen).map(([d]) => d);
    const tiedChosen = all.filter(([d, r]) => best.has(d) && r.degDb === worstChosen).map(([d]) => d);
    expect(tiedLeftOut.every((left) => tiedChosen.every((d) => d < left))).toBe(true);
  });

  test('everyMonthLengthAndOtherCount', () => {
    for (const [y, m] of [[2028, 2], [2030, 2], [2030, 4], [2030, 12]]) {
      expect(bestDaysOfMonth(y, m, lat, lon, bandOf('1296'), 50).size).toBe(4);
    }
    expect(bestDaysOfMonth(2030, 1, lat, lon, B144, 80, 6).size).toBe(6);
  });
});

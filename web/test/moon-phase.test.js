import { describe, expect, test } from 'vitest';
import { DELTA_T_SECONDS } from '../src/lib/moon-calculator.js';
import { phaseAt } from '../src/lib/moon-phase.js';
import { readExpected } from './expected.js';

const at = (iso) => phaseAt(Date.parse(iso));

describe('moon phase', () => {
  test('illuminated fraction and age stay in range', () => {
    for (const iso of ['2026-11-09T12:00:00Z', '2026-11-17T12:00:00Z', '2026-11-24T12:00:00Z', '2026-12-01T12:00:00Z']) {
      const p = at(iso);
      expect(p.illuminated).toBeGreaterThanOrEqual(0);
      expect(p.illuminated).toBeLessThanOrEqual(1);
      expect(p.ageDeg).toBeGreaterThanOrEqual(0);
      expect(p.ageDeg).toBeLessThanOrEqual(360);
    }
  });

  test('uses the shared delta T of the moon calculator', () => {
    expect(DELTA_T_SECONDS).toBe(69.2);
  });

  test('reference rows (astropy)', () => {
    for (const row of readExpected('moon_phase')) {
      const m = at(row.utc);
      expect(Math.abs(Number(row.illuminated) - m.illuminated), row.utc).toBeLessThanOrEqual(Number(row.tol));
      expect(m.nameIndex, row.utc).toBe(Number(row.name_index));
    }
    expect(at('2026-11-17T12:00:00Z').waxing).toBe(true);
    expect(at('2026-12-01T12:00:00Z').waxing).toBe(false);
  });
});

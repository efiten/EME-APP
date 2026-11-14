import { describe, expect, test } from 'vitest';
import { isValid, normalize, toLatLon } from '../src/lib/maidenhead.js';
import { readExpected } from './expected.js';

describe('maidenhead', () => {
  test.each(readExpected('maidenhead'))('toLatLon $locator', (row) => {
    const p = toLatLon(row.locator);
    expect(p.latDeg).toBeCloseTo(Number(row.lat), 6);
    expect(p.lonDeg).toBeCloseTo(Number(row.lon), 6);
  });

  test('lowercase and spaces are accepted', () => {
    expect(isValid(' jo21sc ')).toBe(true);
    expect(normalize(' jo21SC ')).toBe('JO21sc');
    expect(normalize('jo21')).toBe('JO21');
  });

  test.each(['', 'J', 'JO2', 'JO21S', 'JO21SCX', 'SO21', 'JS21', 'JO2A', 'JO21YA', '1234', 'JO21 SC'])(
    'invalid locator %j is rejected',
    (s) => {
      expect(isValid(s)).toBe(false);
      expect(toLatLon(s)).toBeNull();
    },
  );
});

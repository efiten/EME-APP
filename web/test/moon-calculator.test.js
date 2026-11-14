import { describe, expect, test } from 'vitest';
import { toLatLon } from '../src/lib/maidenhead.js';
import {
  apparentSiderealTimeDeg,
  eclipticPosition,
  equatorialPosition,
  julianDay,
  position,
} from '../src/lib/moon-calculator.js';
import { readExpected } from './expected.js';

const millis = (iso) => Date.parse(iso);
const angleDiff = (a, b) => {
  const d = Math.abs(a - b) % 360;
  return d > 180 ? 360 - d : d;
};

describe('moon calculator', () => {
  test('julianDay of J2000', () => {
    expect(julianDay(millis('2000-01-01T12:00:00Z'))).toBeCloseTo(2451545.0, 9);
  });

  test('Meeus example 47.a ecliptic', () => {
    const e = eclipticPosition(2448724.5);
    expect(Math.abs(e.lonDeg - 133.162655)).toBeLessThan(1e-5);
    expect(Math.abs(e.latDeg - -3.229126)).toBeLessThan(1e-5);
    expect(Math.abs(e.distanceKm - 368409.7)).toBeLessThan(0.1);
  });

  test('Meeus example 47.a equatorial', () => {
    const q = equatorialPosition(2448724.5);
    expect(Math.abs(q.raDeg - 134.68847)).toBeLessThan(0.001);
    expect(Math.abs(q.decDeg - 13.768368)).toBeLessThan(0.001);
  });

  test('Meeus example 12.b sidereal time', () => {
    const jd = julianDay(millis('1987-04-10T19:21:00Z'));
    const expected = (8 + 34 / 60 + 56.853 / 3600) * 15;
    expect(Math.abs(apparentSiderealTimeDeg(jd) - expected)).toBeLessThanOrEqual(0.0005);
  });

  test.each(readExpected('moon_azel'))('az/el $locator $utc', (c) => {
    const tol = Number(c.tol_deg);
    const p = toLatLon(c.locator);
    const m = position(p.latDeg, p.lonDeg, millis(c.utc));
    expect(angleDiff(Number(c.az), m.azimuthDeg)).toBeLessThan(tol);
    expect(Math.abs(Number(c.el) - m.elevationDeg)).toBeLessThanOrEqual(tol);
  });

  test('aboveHorizonFlag', () => {
    const p = toLatLon('JO21SC');
    expect(position(p.latDeg, p.lonDeg, millis('2026-11-07T10:00:00Z')).isAboveHorizon).toBe(true);
    expect(position(p.latDeg, p.lonDeg, millis('2026-11-06T20:00:00Z')).isAboveHorizon).toBe(false);
  });
});

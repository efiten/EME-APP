import { afterEach, describe, expect, test } from 'vitest';
import { toLatLon } from '../src/lib/maidenhead.js';
import { position, quickElevationDeg, subLunarPoint } from '../src/lib/moon-calculator.js';
import { computeEvents } from '../src/lib/moon-events.js';
import { readExpected } from './expected.js';

const millis = (iso) => Date.parse(iso);

function assertTime(expectedIso, actual, toleranceSec) {
  expect(actual).not.toBeNull();
  const diff = Math.abs(actual - millis(expectedIso)) / 1000;
  expect(diff, `diff ${diff} s with ${expectedIso} (was ${new Date(actual).toISOString()})`).toBeLessThanOrEqual(
    toleranceSec,
  );
}

const given = (v) => v !== undefined && v !== '';

/** Reference: astropy 8, topocentric, centre of the moon at 0 deg, no refraction. */
function assertEventsMatch(row) {
  const p = toLatLon(row.locator);
  const ev = computeEvents(p.latDeg, p.lonDeg, millis(row.now_utc));
  expect(ev.isUp).toBe(row.is_up === 'true');
  if (given(row.next_rise_utc)) assertTime(row.next_rise_utc, ev.nextRise?.utcMillis ?? null, 60);
  if (given(row.next_set_utc)) assertTime(row.next_set_utc, ev.nextSet?.utcMillis ?? null, 60);
  if (given(row.culmination_utc)) assertTime(row.culmination_utc, ev.culmination?.utcMillis ?? null, 180);
  if (given(row.culmination_el)) expect(Math.abs(Number(row.culmination_el) - ev.culmination.elevationDeg)).toBeLessThanOrEqual(0.05);
  if (given(row.rise_az)) expect(Math.abs(Number(row.rise_az) - ev.nextRise.azimuthDeg)).toBeLessThanOrEqual(0.2);
  if (given(row.set_az)) expect(Math.abs(Number(row.set_az) - ev.nextSet.azimuthDeg)).toBeLessThanOrEqual(0.2);
}

describe('moon events', () => {
  const rows = readExpected('moon_events');

  test('moon down: next pass', () => {
    assertEventsMatch(rows.find((r) => r.is_up === 'false'));
  });

  test('moon up: shows next rise', () => {
    assertEventsMatch(rows.find((r) => r.is_up === 'true'));
  });

  describe('time zone independence', () => {
    const oldTz = process.env.TZ;
    afterEach(() => {
      if (oldTz === undefined) delete process.env.TZ;
      else process.env.TZ = oldTz;
    });

    test('same utcMillis under America/New_York', () => {
      const row = rows.find((r) => r.is_up === 'false');
      const p = toLatLon(row.locator);
      const now = millis(row.now_utc);
      const base = computeEvents(p.latDeg, p.lonDeg, now);
      process.env.TZ = 'America/New_York';
      expect(new Date(now).getHours()).not.toBe(new Date(now).getUTCHours());
      const ny = computeEvents(p.latDeg, p.lonDeg, now);
      expect(ny.nextRise.utcMillis).toBe(base.nextRise.utcMillis);
      expect(ny.nextSet.utcMillis).toBe(base.nextSet.utcMillis);
      expect(ny.culmination.utcMillis).toBe(base.culmination.utcMillis);
    });
  });

  test('subLunarPoint has the moon in zenith', () => {
    for (const iso of ['2026-11-07T10:00:00Z', '2026-01-01T00:00:00Z', '2025-06-15T18:30:00Z']) {
      const t = millis(iso);
      const sub = subLunarPoint(t);
      const el = position(sub.latDeg, sub.lonDeg, t).elevationDeg;
      expect(el, `EL at sub-lunar point ${iso}`).toBeGreaterThan(89.5);
    }
  });

  test('quickElevation matches full calculation', () => {
    const t = millis('2026-11-07T14:00:00Z');
    const sub = subLunarPoint(t);
    for (const [lat, lon] of [[51.1, 5.5], [40.5, -75.0], [-37.5, 145.0], [64.0, -20.0]]) {
      const full = position(lat, lon, t).elevationDeg;
      expect(Math.abs(full - quickElevationDeg(sub, lat, lon)), `lat ${lat} lon ${lon}`).toBeLessThanOrEqual(0.1);
    }
  });
});

import { afterEach, describe, expect, test } from 'vitest';
import { calendarDays, dayLabels, firstDayOfWeek } from '../src/lib/deg-calendar.js';
import { bestDaysOfMonth, EME_BANDS } from '../src/lib/degradation.js';
import { withTimeZone } from './tz.js';

/** 30 days with the chosen day at index 15 (half a month before and after), as DayLabelsTest.kt. */
function labels(center, today) {
  const c = Date.parse(`${center}T12:00:00Z`);
  const dates = Array.from({ length: 30 }, (_, i) => new Date(c + (i - 15) * 86_400_000).toISOString().slice(0, 10));
  return Object.fromEntries(dayLabels(dates, today, 15));
}

describe('dayLabels (DayLabelsTest.kt)', () => {
  test('centeredOnToday', () => {
    const l = labels('2026-11-10', '2026-11-10');
    expect(l[0]).toBe('26 oct');
    expect(l[6]).toBe('1 nov');
    expect(l[5]).toBeUndefined();
    expect(l[10]).toBe('5');
    expect(l[15]).toBe('today');
    expect(l[20]).toBe('15');
    expect(l[25]).toBe('20');
  });

  test('chosenDayInMiddleTodayAtLeft', () => {
    const l = labels('2026-11-24', '2026-11-10');
    expect(l[1]).toBe('today');
    expect(l[0]).toBeUndefined();
    expect(l[5]).toBe('14 nov');
    expect(l[10]).toBe('19');
    expect(l[15]).toBe('24 nov');
    expect(l[20]).toBeUndefined();
    expect(l[22]).toBe('1 dec');
    expect(l[25]).toBe('4');
  });

  test('todayOutsideWindow', () => {
    const l = labels('2027-03-15', '2026-11-10');
    expect(Object.values(l)).not.toContain('today');
    expect(l[15]).toBe('15 mar');
  });

  test('returns [index, label] pairs sorted by index', () => {
    const l = dayLabels(Array.from({ length: 30 }, (_, i) => `2026-11-${String(i + 1).padStart(2, '0')}`), '2026-11-16', 15);
    expect(l.map(([i]) => i)).toEqual([...l.map(([i]) => i)].sort((a, b) => a - b));
  });
});

describe('firstDayOfWeek (FirstDayOfWeekTest.kt), ISO 1 = Monday .. 7 = Sunday', () => {
  test('appLanguageWithoutCountryUsesThePhoneCountry', () => {
    expect(firstDayOfWeek('nl', 'BE')).toBe(1);
    expect(firstDayOfWeek('en', 'US')).toBe(7);
  });

  test('countryInTheLocaleWins', () => {
    expect(firstDayOfWeek('de-DE', 'US')).toBe(1);
    expect(firstDayOfWeek('ja-JP', 'BE')).toBe(7);
  });

  test('noCountryAtAllIsMonday', () => {
    expect(firstDayOfWeek('nl', '')).toBe(1);
  });

  test('Saturday and Friday countries', () => {
    expect(firstDayOfWeek('ar', 'EG')).toBe(6);
    expect(firstDayOfWeek('en', 'MV')).toBe(5);
  });
});

describe('calendarDays: UTC-noon dates (Review Focus 5)', () => {
  const noon = (y, m, d) => Date.UTC(y, m - 1, d, 12);

  test('November 2026 has 30 days from 2026-11-01 to 2026-11-30, with UTC noon timestamps', () => {
    const days = calendarDays(2026, 11);
    expect(days).toHaveLength(30);
    expect(days[0]).toMatchObject({ day: 1, iso: "2026-11-01", noonMs: noon(2026, 11, 1) });
    expect(days[29].iso).toBe('2026-11-30');
  });

  test('1 November 2026 is a Sunday, leap February has 29 days', () => {
    expect(calendarDays(2026, 11)[0].weekday).toBe(7);
    expect(calendarDays(2028, 2)).toHaveLength(29);
  });

  describe('TZ=America/New_York around the US DST end (1 November 2026)', () => {
    let restore = () => {};
    afterEach(() => restore());

    test('the helper really changes the offset and restores it', () => {
      const before = new Date(2026, 10, 1, 12).getTimezoneOffset();
      restore = withTimeZone('America/New_York');
      expect(new Date('2026-10-31T12:00:00Z').getTimezoneOffset()).toBe(240);
      expect(new Date('2026-11-01T12:00:00Z').getTimezoneOffset()).toBe(300);
      restore();
      expect(new Date(2026, 10, 1, 12).getTimezoneOffset()).toBe(before);
    });

    test('every day of the month maps to its own UTC noon, none skipped or doubled', () => {
      restore = withTimeZone('America/New_York');
      const days = calendarDays(2026, 11);
      expect(days[0]).toEqual({ day: 1, iso: '2026-11-01', noonMs: Date.UTC(2026, 10, 1, 12), weekday: 7 });
      expect(days.map((d) => d.iso)).toEqual(Array.from({ length: 30 }, (_, i) => `2026-11-${String(i + 1).padStart(2, '0')}`));
      days.forEach((d, i) => expect(d.noonMs).toBe(Date.UTC(2026, 10, 1 + i, 12)));
      const oct = calendarDays(2026, 10);
      expect(oct).toHaveLength(31);
      expect(oct[30].iso).toBe('2026-10-31');
    });

    test('best days of November equal bestDaysOfMonth in New York too', () => {
      const band = EME_BANDS[1];
      const expected = bestDaysOfMonth(2026, 11, 50.8, 4.4, band, band.defaultTsys, 4);
      restore = withTimeZone('America/New_York');
      expect(bestDaysOfMonth(2026, 11, 50.8, 4.4, band, band.defaultTsys, 4)).toEqual(expected);
    });
  });
});

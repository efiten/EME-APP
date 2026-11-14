// Port of dayLabels (DegradationScreen.kt) and firstDayOfWeek / the day grid (DegCalendar.kt).
// Dates are ISO strings (YYYY-MM-DD) of UTC noon days, as produced by daily() in degradation.js.

const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];
const dayOf = (iso) => Number(iso.slice(8, 10));
const withMonth = (iso) => `${dayOf(iso)} ${MONTHS[Number(iso.slice(5, 7)) - 1]}`;

/**
 * X axis labels (index -> text), deliberately not translated:
 *  the chosen day in the middle with its month ("24 nov") or "today", "today" at today's index, the 1st of a month with
 *  its month, every 5 days from the chosen day the day number; the first label on the left gets the month.
 *  A plain day number within 2 positions of a special label is dropped, so nothing overlaps.
 * Returns [index, label] sorted by index.
 */
export function dayLabels(isoDates, todayIso, centerIndex) {
  const special = new Map();
  isoDates.forEach((iso, i) => {
    if (i > 0 && dayOf(iso) === 1) special.set(i, withMonth(iso));
  });
  const todayIndex = isoDates.indexOf(todayIso);
  if (todayIndex >= 0) special.set(todayIndex, 'today');
  special.set(centerIndex, isoDates[centerIndex] === todayIso ? 'today' : withMonth(isoDates[centerIndex]));
  const labels = new Map(special);
  isoDates.forEach((iso, i) => {
    if ((i - centerIndex) % 5 === 0 && ![...special.keys()].some((k) => Math.abs(k - i) <= 2)) labels.set(i, String(dayOf(iso)));
  });
  // The first plain day number gets the month too, unless a label with a month already stands left of it
  for (const i of [...labels.keys()].sort((a, b) => a - b)) {
    if (special.has(i)) {
      if (labels.get(i) !== 'today') break;
      continue;
    }
    labels.set(i, withMonth(isoDates[i]));
    break;
  }
  return [...labels.entries()].sort((a, b) => a[0] - b[0]);
}

// Countries whose week does not start on Monday (CLDR week data as in Node's ICU; the Kotlin app gets it from java.time WeekFields).
// Table instead of Intl.Locale.weekInfo, which older Firefox and Safari lack.
const SUNDAY = 'AG AS BD BR BS BT BW BZ CA CO DM DO ET GT GU HK HN ID IL IN IS JM JP KE KH KR LA MH MM MO MT MX MZ NI NP PA PE PH PK PR PT PY SA SG SV TH TT TW UM US VE VI WS YE ZA ZW'.split(' ');
const SATURDAY = 'AF BH DJ DZ EG IQ IR JO KW LY OM QA SD SY'.split(' ');
const FRIDAY = ['MV'];

export const regionOf = (tag) => {
  try {
    return new Intl.Locale(tag).region ?? '';
  } catch {
    return '';
  }
};

/**
 * First day of the week as ISO number (1 Monday .. 7 Sunday). The app language has no country ("nl"), so the country comes
 * from the browser ([systemCountry], e.g. "BE" -> Monday, "US" -> Sunday). No country known: Monday.
 * [locale] is a language tag ("nl", "de-DE").
 */
export function firstDayOfWeek(locale, systemCountry) {
  const country = (regionOf(locale) || systemCountry || '').toUpperCase();
  if (!country) return 1;
  if (SUNDAY.includes(country)) return 7;
  if (SATURDAY.includes(country)) return 6;
  if (FRIDAY.includes(country)) return 5;
  return 1;
}

/**
 * The days of a month (month 1..12) as { day, iso, noonMs, weekday (ISO 1..7) }. Pure calendar arithmetic in UTC:
 * every day maps to its own UTC noon, whatever the browser time zone (no local midnight that shifts over a DST change).
 */
export function calendarDays(year, month) {
  const length = new Date(Date.UTC(year, month, 0)).getUTCDate();
  return Array.from({ length }, (_, i) => {
    const noonMs = Date.UTC(year, month - 1, i + 1, 12);
    const d = new Date(noonMs);
    return { day: i + 1, iso: d.toISOString().slice(0, 10), noonMs, weekday: d.getUTCDay() || 7 };
  });
}

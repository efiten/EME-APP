// Port of Maidenhead.kt: grid locator (4 or 6 characters) to latitude/longitude of the square centre.

const PATTERN_4 = /^[A-R]{2}[0-9]{2}$/;
const PATTERN_6 = /^[A-R]{2}[0-9]{2}[A-X]{2}$/;

/** First 2 letters upper case, last 2 lower case (6 characters); otherwise all upper case. */
export function normalize(input) {
  const s = input.trim();
  return s.length === 6 ? s.substring(0, 4).toUpperCase() + s.substring(4).toLowerCase() : s.toUpperCase();
}

export function isValid(input) {
  const s = input.trim().toUpperCase();
  return PATTERN_4.test(s) || PATTERN_6.test(s);
}

/** Returns null for an invalid locator. Longitude: east positive. */
export function toLatLon(input) {
  if (!isValid(input)) return null;
  const s = input.trim().toUpperCase();
  const A = 'A'.charCodeAt(0);
  const ZERO = '0'.charCodeAt(0);

  let lon = (s.charCodeAt(0) - A) * 20.0 - 180.0 + (s.charCodeAt(2) - ZERO) * 2.0;
  let lat = (s.charCodeAt(1) - A) * 10.0 - 90.0 + (s.charCodeAt(3) - ZERO) * 1.0;

  if (s.length === 6) {
    lon += (s.charCodeAt(4) - A) * (5.0 / 60.0) + 2.5 / 60.0;
    lat += (s.charCodeAt(5) - A) * (2.5 / 60.0) + 1.25 / 60.0;
  } else {
    lon += 1.0;
    lat += 0.5;
  }
  return { latDeg: lat, lonDeg: lon };
}

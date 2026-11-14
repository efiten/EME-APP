// Port of MoonEvents.kt: moonrise, culmination and moonset for an observer.
// Rise/set = the moment the CENTRE of the moon crosses elevation 0, no refraction.
import { position } from './moon-calculator.js';

const STEP_MS = 10 * 60_000;
const HOUR_MS = 3_600_000;
const SEARCH_AHEAD_MS = 48 * HOUR_MS;
const SEARCH_BACK_MS = 30 * HOUR_MS;

/** Refines a zero crossing between t0 and t1 to about a second. */
function bisect(t0, t1, rising, el) {
  let lo = t0;
  let hi = t1;
  while (hi - lo > 1_000) {
    const mid = Math.floor((lo + hi) / 2);
    const above = el(mid) > 0;
    if (above === rising) hi = mid;
    else lo = mid;
  }
  return Math.floor((lo + hi) / 2);
}

/** Time of the highest elevation between a and b. */
function maxBetween(a, b, el) {
  let best = a;
  let bestEl = el(a);
  for (let t = a; t <= b; t += STEP_MS) {
    const e = el(t);
    if (e > bestEl) {
      best = t;
      bestEl = e;
    }
  }
  let lo = Math.max(best - STEP_MS, a);
  let hi = Math.min(best + STEP_MS, b);
  const g = 0.6180339887;
  while (hi - lo > 1_000) {
    const m1 = hi - g * (hi - lo);
    const m2 = lo + g * (hi - lo);
    if (el(Math.trunc(m1)) < el(Math.trunc(m2))) lo = m1;
    else hi = m2;
  }
  return Math.trunc((lo + hi) / 2);
}

/** Returns {isUp, nextRise, culmination, nextSet}; each event is {utcMillis, azimuthDeg, elevationDeg} or null. */
export function computeEvents(latDeg, lonDeg, nowMs) {
  const el = (t) => position(latDeg, lonDeg, t).elevationDeg;
  const event = (t) => {
    const p = position(latDeg, lonDeg, t);
    return { utcMillis: t, azimuthDeg: p.azimuthDeg, elevationDeg: p.elevationDeg };
  };

  /** First time after `from` that EL crosses 0 (rising or falling). */
  const crossingAfter = (from, rising) => {
    let t0 = from;
    let e0 = el(t0);
    while (t0 < from + SEARCH_AHEAD_MS) {
      const t1 = t0 + STEP_MS;
      const e1 = el(t1);
      if (rising && e0 <= 0 && e1 > 0) return bisect(t0, t1, rising, el);
      if (!rising && e0 > 0 && e1 <= 0) return bisect(t0, t1, rising, el);
      t0 = t1;
      e0 = e1;
    }
    return null;
  };

  /** Last rise before `from`. */
  const riseBefore = (from) => {
    let t1 = from;
    let e1 = el(t1);
    while (t1 > from - SEARCH_BACK_MS) {
      const t0 = t1 - STEP_MS;
      const e0 = el(t0);
      if (e0 <= 0 && e1 > 0) return bisect(t0, t1, true, el);
      t1 = t0;
      e1 = e0;
    }
    return null;
  };

  const isUp = el(nowMs) > 0;
  const nextRise = crossingAfter(nowMs, true);
  const nextSet = crossingAfter(nowMs, false);

  // The pass whose culmination we show
  const passStart = isUp ? riseBefore(nowMs) : nextRise;
  const passEnd = isUp ? nextSet : nextRise !== null ? crossingAfter(nextRise, false) : null;
  const culmination = passStart !== null && passEnd !== null ? event(maxBetween(passStart, passEnd, el)) : null;

  return {
    isUp,
    nextRise: nextRise !== null ? event(nextRise) : null,
    culmination,
    nextSet: nextSet !== null ? event(nextSet) : null,
  };
}

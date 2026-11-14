// Port of MoonPhase.kt: Meeus ch. 25 (sun) and ch. 48 (illuminated fraction).
import { DELTA_T_SECONDS, cosD, eclipticPosition, julianDay, norm, sinD } from './moon-calculator.js';

const AU_KM = 149_597_870.7;

/** True ecliptic longitude of the sun (degrees) and distance (km), Meeus ch. 25 (low accuracy). */
export function sun(jde) {
  const t = (jde - 2_451_545.0) / 36_525.0;
  const l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t;
  const m = 357.52911 + 35999.05029 * t - 0.0001537 * t * t;
  const e = 0.016708634 - 0.000042037 * t;
  const c =
    (1.914602 - 0.004817 * t - 0.000014 * t * t) * sinD(m) +
    (0.019993 - 0.000101 * t) * sinD(2 * m) +
    0.000289 * sinD(3 * m);
  const lon = norm(l0 + c);
  const v = m + c;
  const r = (1.000001018 * (1 - e * e)) / (1 + e * cosD(v));
  return { lonDeg: lon, distanceKm: r * AU_KM };
}

/**
 * Phase at a time. illuminated 0..1, ageDeg 0..360 (0 new, 90 first quarter, 180 full, 270 last quarter),
 * waxing = ageDeg < 180, nameIndex 0 new, 1 waxing crescent, 2 first quarter, 3 waxing gibbous,
 * 4 full, 5 waning gibbous, 6 last quarter, 7 waning crescent.
 */
export function phaseAt(epochMillis) {
  const jde = julianDay(epochMillis) + DELTA_T_SECONDS / 86_400.0;
  const moon = eclipticPosition(jde);
  const s = sun(jde);
  const age = norm(moon.lonDeg - s.lonDeg);
  // Elongation and phase angle (Meeus 48.2 and 48.3)
  const cosPsi = cosD(moon.latDeg) * cosD(moon.lonDeg - s.lonDeg);
  const psi = Math.acos(Math.min(1, Math.max(-1, cosPsi)));
  const i = Math.atan2(s.distanceKm * Math.sin(psi), moon.distanceKm - s.distanceKm * Math.cos(psi));
  return {
    illuminated: (1 + Math.cos(i)) / 2,
    ageDeg: age,
    waxing: age < 180.0,
    nameIndex: Math.round(age / 45.0) % 8,
  };
}

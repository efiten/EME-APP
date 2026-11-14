import { position } from './moon-calculator.js';

/** EME bands (50 to 1296 MHz) with a default Tsys (K). Port of EmeBand in Degradation.kt. */
export const EME_BANDS = [
  { id: 'B50', label: '50', mhz: 50.0, defaultTsys: 150 },
  { id: 'B144', label: '144', mhz: 144.0, defaultTsys: 80 },
  { id: 'B222', label: '222', mhz: 222.0, defaultTsys: 70 },
  { id: 'B432', label: '432', mhz: 432.0, defaultTsys: 60 },
  { id: 'B902', label: '902', mhz: 903.0, defaultTsys: 50 },
  { id: 'B1296', label: '1296', mhz: 1296.0, defaultTsys: 50 },
];

export const CONDITIONS = ['EXCELLENT', 'GOOD', 'FAIR', 'POOR', 'VERY_POOR'];

/** Sky temperature at 144 MHz (K) per 0.1 h right ascension, 0.0 to 24.0 h. */
const TSKY_144 = [
      230, 230, 240, 250, 260, 265, 270, 275, 280, 285,
      290, 292, 294, 296, 298, 300, 302, 304, 306, 308,
      310, 320, 330, 340, 350, 360, 370, 380, 390, 390,
      390, 380, 370, 360, 350, 350, 350, 350, 350, 360,
      370, 375, 380, 385, 390, 395, 400, 405, 410, 415,
      420, 430, 445, 460, 480, 510, 540, 570, 600, 600,
      600, 580, 560, 540, 520, 500, 480, 460, 440, 420,
      400, 390, 380, 370, 360, 350, 340, 330, 320, 310,
      300, 280, 260, 240, 220, 210, 208, 206, 204, 202,
      200, 200, 200, 200, 200, 200, 205, 205, 205, 205,
      205, 205, 200, 200, 200, 202, 204, 208, 212, 216,
      220, 221, 222, 223, 224, 225, 226, 227, 228, 229,
      230, 245, 260, 275, 290, 305, 320, 320, 320, 315,
      310, 308, 306, 304, 302, 300, 300, 302, 305, 308,
      310, 320, 330, 340, 350, 360, 370, 380, 390, 400,
      410, 425, 440, 455, 470, 485, 490, 505, 520, 530,
      540, 580, 620, 660, 700, 780, 860, 940, 1020, 1100,
      1200, 1500, 1800, 2100, 2400, 2700, 3000, 3100, 3200, 3100,
      3000, 2950, 2900, 2650, 2400, 2150, 1900, 1650, 1400, 1150,
      900, 850, 800, 750, 700, 650, 600, 550, 500, 450,
      400, 380, 360, 340, 320, 320, 320, 320, 320, 320,
      320, 320, 320, 320, 320, 320, 316, 312, 308, 304,
      300, 296, 292, 288, 284, 280, 280, 280, 280, 285,
      285, 285, 280, 280, 275, 270, 260, 250, 240, 230,
      230,
];

const TSKY_MIN_144 = 200.0;
const CMB_K = 3.0;

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

export function tsky144(raHours) {
  // Like MMMonVHF: round to whole minutes, then to 0.1 h
  const minutes = Math.round(raHours * 60);
  const idx = clamp(Math.round((minutes / 60.0) * 10), 0, 240);
  return TSKY_144[idx];
}

const scale = (t144, mhz) => Math.max(CMB_K, t144 * Math.pow(144.0 / mhz, 2.6));

/** Extra path loss relative to the moon at perigee (dB), frequency independent. */
export function rangeFactorDb(distanceKm) {
  const radar = 10 * Math.log10((4 * distanceKm * distanceKm) / (1700.0 * 1700.0));
  const pathloss =
    32.45 + 20 * Math.log10(144.0) + 20 * Math.log10(distanceKm * 2) + radar - 10 * Math.log10(0.07);
  return pathloss - 256.34;
}

export function conditionOf(deg) {
  if (deg <= 1.5) return 'EXCELLENT';
  if (deg <= 2.5) return 'GOOD';
  if (deg <= 4.0) return 'FAIR';
  if (deg <= 5.5) return 'POOR';
  return 'VERY_POOR';
}

/** DEG [dB] = Tsky / (TskyMin + Tsys) + RangeFactor, as MMMonVHF. */
export function computeDegradation(epochMillis, latDeg, lonDeg, band, tsysK) {
  const moon = position(latDeg, lonDeg, epochMillis);
  const tsky = scale(tsky144(moon.raDeg / 15.0), band.mhz);
  const tskyMin = scale(TSKY_MIN_144, band.mhz);
  const rf = rangeFactorDb(moon.distanceKm);
  const degDb = Math.round((tsky / (tskyMin + tsysK) + rf) * 10) / 10.0;
  return {
    degDb,
    tskyK: tsky,
    tskyMinK: tskyMin,
    rangeFactorDb: rf,
    distanceKm: moon.distanceKm,
    condition: conditionOf(degDb),
    decDeg: moon.decDeg,
  };
}

/** One value per day at 12:00 UTC, from startIsoDate (YYYY-MM-DD), for `days` days. Returns [isoDate, Result][]. */
export function daily(startIsoDate, days, latDeg, lonDeg, band, tsysK) {
  const start = Date.parse(startIsoDate + 'T12:00:00Z');
  const out = [];
  for (let d = 0; d < days; d++) {
    const noon = start + d * 86_400_000;
    out.push([new Date(noon).toISOString().slice(0, 10), computeDegradation(noon, latDeg, lonDeg, band, tsysK)]);
  }
  return out;
}

/** The `count` days with the lowest degradation in the month; ties go to the earliest day (stable sort). */
export function bestDaysOfMonth(year, month1to12, latDeg, lonDeg, band, tsysK, count = 4) {
  const first = `${String(year).padStart(4, '0')}-${String(month1to12).padStart(2, '0')}-01`;
  const length = new Date(Date.UTC(year, month1to12, 0)).getUTCDate();
  const sorted = daily(first, length, latDeg, lonDeg, band, tsysK).sort((a, b) => a[1].degDb - b[1].degDb);
  return new Set(sorted.slice(0, count).map(([d]) => d));
}

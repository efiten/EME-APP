// Port of MoonCalculator.kt: Jean Meeus, "Astronomical Algorithms" (2nd ed.), chapters 47, 22, 12, 40.
// Accuracy better than ~0.01 degree. No atmospheric refraction (geometric elevation).

/** Difference between Terrestrial Time and UTC in seconds. Valid around 2020-2030. */
export const DELTA_T_SECONDS = 69.2;

// ---------- Time ----------

/** Julian day from milliseconds since 1970-01-01 UTC. */
export function julianDay(epochMillis) {
  return epochMillis / 86_400_000.0 + 2_440_587.5;
}

// ---------- Helpers ----------

export function norm(deg) {
  const r = deg % 360.0;
  return r < 0 ? r + 360.0 : r;
}

export const sinD = (d) => Math.sin((d * Math.PI) / 180.0);
export const cosD = (d) => Math.cos((d * Math.PI) / 180.0);
const tanD = (d) => Math.tan((d * Math.PI) / 180.0);
const asinD = (x) => (Math.asin(x) * 180.0) / Math.PI;
const atan2D = (y, x) => (Math.atan2(y, x) * 180.0) / Math.PI;

// ---------- Chapter 47 ----------

function eccentricityFactor(mCoef, e) {
  switch (Math.trunc(Math.abs(mCoef))) {
    case 1:
      return e;
    case 2:
      return e * e;
    default:
      return 1.0;
  }
}

/** Ecliptic coordinates for a Julian Ephemeris Day (TT): {lonDeg, latDeg, distanceKm}. */
export function eclipticPosition(jde) {
  const t = (jde - 2_451_545.0) / 36_525.0;
  const t2 = t * t;
  const t3 = t2 * t;
  const t4 = t3 * t;

  const lp = norm(218.3164477 + 481267.88123421 * t - 0.0015786 * t2 + t3 / 538841.0 - t4 / 65194000.0);
  const d = norm(297.8501921 + 445267.1114034 * t - 0.0018819 * t2 + t3 / 545868.0 - t4 / 113065000.0);
  const m = norm(357.5291092 + 35999.0502909 * t - 0.0001536 * t2 + t3 / 24490000.0);
  const mp = norm(134.9633964 + 477198.8675055 * t + 0.0087414 * t2 + t3 / 69699.0 - t4 / 14712000.0);
  const f = norm(93.272095 + 483202.0175233 * t - 0.0036539 * t2 - t3 / 3526000.0 + t4 / 863310000.0);
  const a1 = norm(119.75 + 131.849 * t);
  const a2 = norm(53.09 + 479264.29 * t);
  const a3 = norm(313.45 + 481266.484 * t);
  const e = 1.0 - 0.002516 * t - 0.0000074 * t2;

  let sumL = 0.0;
  let sumR = 0.0;
  for (const row of TABLE_LR) {
    const arg = row[0] * d + row[1] * m + row[2] * mp + row[3] * f;
    const eFactor = eccentricityFactor(row[1], e);
    sumL += row[4] * eFactor * sinD(arg);
    sumR += row[5] * eFactor * cosD(arg);
  }
  let sumB = 0.0;
  for (const row of TABLE_B) {
    const arg = row[0] * d + row[1] * m + row[2] * mp + row[3] * f;
    sumB += row[4] * eccentricityFactor(row[1], e) * sinD(arg);
  }

  sumL += 3958.0 * sinD(a1) + 1962.0 * sinD(lp - f) + 318.0 * sinD(a2);
  sumB +=
    -2235.0 * sinD(lp) +
    382.0 * sinD(a3) +
    175.0 * sinD(a1 - f) +
    175.0 * sinD(a1 + f) +
    127.0 * sinD(lp - mp) -
    115.0 * sinD(lp + mp);

  return {
    lonDeg: norm(lp + sumL / 1_000_000.0),
    latDeg: sumB / 1_000_000.0,
    distanceKm: 385_000.56 + sumR / 1000.0,
  };
}

// ---------- Nutation (chapter 22, short form) ----------

function nutation(jde) {
  const t = (jde - 2_451_545.0) / 36_525.0;
  const omega = norm(125.04452 - 1934.136261 * t);
  const lSun = norm(280.4665 + 36000.7698 * t);
  const lMoon = norm(218.3165 + 481267.8813 * t);
  const dPsiDeg =
    (-17.2 * sinD(omega) - 1.32 * sinD(2 * lSun) - 0.23 * sinD(2 * lMoon) + 0.21 * sinD(2 * omega)) / 3600.0;
  const dEpsDeg =
    (9.2 * cosD(omega) + 0.57 * cosD(2 * lSun) + 0.1 * cosD(2 * lMoon) - 0.09 * cosD(2 * omega)) / 3600.0;
  const eps0Deg =
    23.0 + 26.0 / 60.0 + 21.448 / 3600.0 + (-46.815 * t - 0.00059 * t * t + 0.001813 * t * t * t) / 3600.0;
  return { dPsiDeg, dEpsDeg, eps0Deg };
}

/** Apparent geocentric right ascension and declination (JDE in TT): {raDeg, decDeg, distanceKm}. */
export function equatorialPosition(jde) {
  const ecl = eclipticPosition(jde);
  const nut = nutation(jde);
  const lambda = ecl.lonDeg + nut.dPsiDeg;
  const eps = nut.eps0Deg + nut.dEpsDeg;
  const beta = ecl.latDeg;
  const ra = norm(atan2D(sinD(lambda) * cosD(eps) - tanD(beta) * sinD(eps), cosD(lambda)));
  const dec = asinD(sinD(beta) * cosD(eps) + cosD(beta) * sinD(eps) * sinD(lambda));
  return { raDeg: ra, decDeg: dec, distanceKm: ecl.distanceKm };
}

/** Apparent sidereal time at Greenwich (degrees), for JD in UT. */
export function apparentSiderealTimeDeg(jdUt) {
  const t = (jdUt - 2_451_545.0) / 36_525.0;
  const mean = 280.46061837 + 360.98564736629 * (jdUt - 2_451_545.0) + 0.000387933 * t * t - (t * t * t) / 38_710_000.0;
  const nut = nutation(jdUt);
  return norm(mean + nut.dPsiDeg * cosD(nut.eps0Deg + nut.dEpsDeg));
}

// ---------- Az/El for an observer ----------

/**
 * Topocentric azimuth/elevation of the moon. Latitude north positive, longitude EAST positive,
 * time in ms since 1970 UTC, height above sea level in metres.
 * Returns {azimuthDeg, elevationDeg, distanceKm, raDeg, decDeg, isAboveHorizon}.
 */
export function position(latDeg, lonDeg, epochMillis, heightM = 0.0) {
  const jdUt = julianDay(epochMillis);
  const jde = jdUt + DELTA_T_SECONDS / 86_400.0;
  const eq = equatorialPosition(jde);

  const lst = apparentSiderealTimeDeg(jdUt) + lonDeg;
  const h = norm(lst - eq.raDeg);

  const sinPi = 6378.14 / eq.distanceKm;
  const u = (Math.atan(0.99664719 * tanD(latDeg)) * 180.0) / Math.PI;
  const rhoSin = 0.99664719 * sinD(u) + (heightM / 6_378_140.0) * sinD(latDeg);
  const rhoCos = cosD(u) + (heightM / 6_378_140.0) * cosD(latDeg);

  const dAlpha = atan2D(-rhoCos * sinPi * sinD(h), cosD(eq.decDeg) - rhoCos * sinPi * cosD(h));
  const decTopo = atan2D(
    (sinD(eq.decDeg) - rhoSin * sinPi) * cosD(dAlpha),
    cosD(eq.decDeg) - rhoCos * sinPi * cosD(h),
  );
  const hTopo = h - dAlpha;

  const az = norm(atan2D(sinD(hTopo), cosD(hTopo) * sinD(latDeg) - tanD(decTopo) * cosD(latDeg)) + 180.0);
  const el = asinD(sinD(latDeg) * sinD(decTopo) + cosD(latDeg) * cosD(decTopo) * cosD(hTopo));

  const distTopo =
    eq.distanceKm *
    Math.sqrt(1.0 - 2.0 * sinPi * (rhoCos * cosD(eq.decDeg) * cosD(h) + rhoSin * sinD(eq.decDeg)) + sinPi * sinPi);
  return {
    azimuthDeg: az,
    elevationDeg: el,
    distanceKm: distTopo,
    raDeg: norm(eq.raDeg + dAlpha),
    decDeg: decTopo,
    isAboveHorizon: el > 0.0,
  };
}

/** Sub-lunar point (longitude east positive, -180..180): {latDeg, lonDeg, parallaxDeg}. */
export function subLunarPoint(epochMillis) {
  const jdUt = julianDay(epochMillis);
  const eq = equatorialPosition(jdUt + DELTA_T_SECONDS / 86_400.0);
  let lon = norm(eq.raDeg - apparentSiderealTimeDeg(jdUt));
  if (lon > 180.0) lon -= 360.0;
  return { latDeg: eq.decDeg, lonDeg: lon, parallaxDeg: asinD(6378.14 / eq.distanceKm) };
}

/** Quick approximation of the topocentric elevation from the sub-lunar point (world map, ~0.1 degree). */
export function quickElevationDeg(sub, latDeg, lonDeg) {
  const geo = asinD(sinD(latDeg) * sinD(sub.latDeg) + cosD(latDeg) * cosD(sub.latDeg) * cosD(lonDeg - sub.lonDeg));
  return geo - sub.parallaxDeg * cosD(geo);
}

// Table 47.A: D, M, M', F, coefficient Sigma l (1e-6 degree), coefficient Sigma r (1e-3 km)
const TABLE_LR = [
  [0.0, 0.0, 1.0, 0.0, 6288774.0, -20905355.0],
  [2.0, 0.0, -1.0, 0.0, 1274027.0, -3699111.0],
  [2.0, 0.0, 0.0, 0.0, 658314.0, -2955968.0],
  [0.0, 0.0, 2.0, 0.0, 213618.0, -569925.0],
  [0.0, 1.0, 0.0, 0.0, -185116.0, 48888.0],
  [0.0, 0.0, 0.0, 2.0, -114332.0, -3149.0],
  [2.0, 0.0, -2.0, 0.0, 58793.0, 246158.0],
  [2.0, -1.0, -1.0, 0.0, 57066.0, -152138.0],
  [2.0, 0.0, 1.0, 0.0, 53322.0, -170733.0],
  [2.0, -1.0, 0.0, 0.0, 45758.0, -204586.0],
  [0.0, 1.0, -1.0, 0.0, -40923.0, -129620.0],
  [1.0, 0.0, 0.0, 0.0, -34720.0, 108743.0],
  [0.0, 1.0, 1.0, 0.0, -30383.0, 104755.0],
  [2.0, 0.0, 0.0, -2.0, 15327.0, 10321.0],
  [0.0, 0.0, 1.0, 2.0, -12528.0, 0.0],
  [0.0, 0.0, 1.0, -2.0, 10980.0, 79661.0],
  [4.0, 0.0, -1.0, 0.0, 10675.0, -34782.0],
  [0.0, 0.0, 3.0, 0.0, 10034.0, -23210.0],
  [4.0, 0.0, -2.0, 0.0, 8548.0, -21636.0],
  [2.0, 1.0, -1.0, 0.0, -7888.0, 24208.0],
  [2.0, 1.0, 0.0, 0.0, -6766.0, 30824.0],
  [1.0, 0.0, -1.0, 0.0, -5163.0, -8379.0],
  [1.0, 1.0, 0.0, 0.0, 4987.0, -16675.0],
  [2.0, -1.0, 1.0, 0.0, 4036.0, -12831.0],
  [2.0, 0.0, 2.0, 0.0, 3994.0, -10445.0],
  [4.0, 0.0, 0.0, 0.0, 3861.0, -11650.0],
  [2.0, 0.0, -3.0, 0.0, 3665.0, 14403.0],
  [0.0, 1.0, -2.0, 0.0, -2689.0, -7003.0],
  [2.0, 0.0, -1.0, 2.0, -2602.0, 0.0],
  [2.0, -1.0, -2.0, 0.0, 2390.0, 10056.0],
  [1.0, 0.0, 1.0, 0.0, -2348.0, 6322.0],
  [2.0, -2.0, 0.0, 0.0, 2236.0, -9884.0],
  [0.0, 1.0, 2.0, 0.0, -2120.0, 5751.0],
  [0.0, 2.0, 0.0, 0.0, -2069.0, 0.0],
  [2.0, -2.0, -1.0, 0.0, 2048.0, -4950.0],
  [2.0, 0.0, 1.0, -2.0, -1773.0, 4130.0],
  [2.0, 0.0, 0.0, 2.0, -1595.0, 0.0],
  [4.0, -1.0, -1.0, 0.0, 1215.0, -3958.0],
  [0.0, 0.0, 2.0, 2.0, -1110.0, 0.0],
  [3.0, 0.0, -1.0, 0.0, -892.0, 3258.0],
  [2.0, 1.0, 1.0, 0.0, -810.0, 2616.0],
  [4.0, -1.0, -2.0, 0.0, 759.0, -1897.0],
  [0.0, 2.0, -1.0, 0.0, -713.0, -2117.0],
  [2.0, 2.0, -1.0, 0.0, -700.0, 2354.0],
  [2.0, 1.0, -2.0, 0.0, 691.0, 0.0],
  [2.0, -1.0, 0.0, -2.0, 596.0, 0.0],
  [4.0, 0.0, 1.0, 0.0, 549.0, -1423.0],
  [0.0, 0.0, 4.0, 0.0, 537.0, -1117.0],
  [4.0, -1.0, 0.0, 0.0, 520.0, -1571.0],
  [1.0, 0.0, -2.0, 0.0, -487.0, -1739.0],
  [2.0, 1.0, 0.0, -2.0, -399.0, 0.0],
  [0.0, 0.0, 2.0, -2.0, -381.0, -4421.0],
  [1.0, 1.0, 1.0, 0.0, 351.0, 0.0],
  [3.0, 0.0, -2.0, 0.0, -340.0, 0.0],
  [4.0, 0.0, -3.0, 0.0, 330.0, 0.0],
  [2.0, -1.0, 2.0, 0.0, 327.0, 0.0],
  [0.0, 2.0, 1.0, 0.0, -323.0, 1165.0],
  [1.0, 1.0, -1.0, 0.0, 299.0, 0.0],
  [2.0, 0.0, 3.0, 0.0, 294.0, 0.0],
  [2.0, 0.0, -1.0, -2.0, 0.0, 8752.0],
];

// Table 47.B: D, M, M', F, coefficient Sigma b (1e-6 degree)
const TABLE_B = [
  [0.0, 0.0, 0.0, 1.0, 5128122.0],
  [0.0, 0.0, 1.0, 1.0, 280602.0],
  [0.0, 0.0, 1.0, -1.0, 277693.0],
  [2.0, 0.0, 0.0, -1.0, 173237.0],
  [2.0, 0.0, -1.0, 1.0, 55413.0],
  [2.0, 0.0, -1.0, -1.0, 46271.0],
  [2.0, 0.0, 0.0, 1.0, 32573.0],
  [0.0, 0.0, 2.0, 1.0, 17198.0],
  [2.0, 0.0, 1.0, -1.0, 9266.0],
  [0.0, 0.0, 2.0, -1.0, 8822.0],
  [2.0, -1.0, 0.0, -1.0, 8216.0],
  [2.0, 0.0, -2.0, -1.0, 4324.0],
  [2.0, 0.0, 1.0, 1.0, 4200.0],
  [2.0, 1.0, 0.0, -1.0, -3359.0],
  [2.0, -1.0, -1.0, 1.0, 2463.0],
  [2.0, -1.0, 0.0, 1.0, 2211.0],
  [2.0, -1.0, -1.0, -1.0, 2065.0],
  [0.0, 1.0, -1.0, -1.0, -1870.0],
  [4.0, 0.0, -1.0, -1.0, 1828.0],
  [0.0, 1.0, 0.0, 1.0, -1794.0],
  [0.0, 0.0, 0.0, 3.0, -1749.0],
  [0.0, 1.0, -1.0, 1.0, -1565.0],
  [1.0, 0.0, 0.0, 1.0, -1491.0],
  [0.0, 1.0, 1.0, 1.0, -1475.0],
  [0.0, 1.0, 1.0, -1.0, -1410.0],
  [0.0, 1.0, 0.0, -1.0, -1344.0],
  [1.0, 0.0, 0.0, -1.0, -1335.0],
  [0.0, 0.0, 3.0, 1.0, 1107.0],
  [4.0, 0.0, 0.0, -1.0, 1021.0],
  [4.0, 0.0, -1.0, 1.0, 833.0],
  [0.0, 0.0, 1.0, -3.0, 777.0],
  [4.0, 0.0, -2.0, 1.0, 671.0],
  [2.0, 0.0, 0.0, -3.0, 607.0],
  [2.0, 0.0, 2.0, -1.0, 596.0],
  [2.0, -1.0, 1.0, -1.0, 491.0],
  [2.0, 0.0, -2.0, 1.0, -451.0],
  [0.0, 0.0, 3.0, -1.0, 439.0],
  [2.0, 0.0, 2.0, 1.0, 422.0],
  [2.0, 0.0, -3.0, -1.0, 421.0],
  [2.0, 1.0, -1.0, 1.0, -366.0],
  [2.0, 1.0, 0.0, 1.0, -351.0],
  [4.0, 0.0, 0.0, 1.0, 331.0],
  [2.0, -1.0, 1.0, 1.0, 315.0],
  [2.0, -2.0, 0.0, -1.0, 302.0],
  [0.0, 0.0, 1.0, 3.0, -283.0],
  [2.0, 1.0, 1.0, -1.0, -229.0],
  [1.0, 1.0, 0.0, -1.0, 223.0],
  [1.0, 1.0, 0.0, 1.0, 223.0],
  [0.0, 1.0, -2.0, -1.0, -220.0],
  [2.0, 1.0, -1.0, -1.0, -220.0],
  [1.0, 0.0, 1.0, 1.0, -185.0],
  [2.0, -1.0, -2.0, -1.0, 181.0],
  [0.0, 1.0, 2.0, 1.0, -177.0],
  [4.0, 0.0, -2.0, -1.0, 176.0],
  [4.0, -1.0, -1.0, -1.0, 166.0],
  [1.0, 0.0, 1.0, -1.0, -164.0],
  [4.0, 0.0, 1.0, -1.0, 132.0],
  [1.0, 0.0, -1.0, -1.0, -119.0],
  [4.0, -1.0, 0.0, -1.0, 115.0],
  [2.0, -2.0, 0.0, 1.0, 107.0],
];

package be.on5gs.eme.moon

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Maanpositie volgens Jean Meeus, "Astronomical Algorithms" (2e druk):
 *  - hoofdstuk 47: geocentrische lengte, breedte en afstand van de maan
 *  - hoofdstuk 22: nutatie (vereenvoudigd)
 *  - hoofdstuk 12: sterrentijd
 *  - hoofdstuk 40: topocentrische correctie (parallax; bij de maan bijna 1 graad!)
 *
 * Nauwkeurigheid: beter dan ~0,01 graad. Geen atmosferische refractie
 * (de "geometrische" elevatie), net als de meeste EME-programma's.
 */
object MoonCalculator {

    /**
     * Verschil tussen Terrestrische Tijd en UTC (ΔT), in seconden. Geldig rond 2020-2030.
     * `internal` zodat [be.on5gs.eme.moon.MoonPhase] dezelfde waarde gebruikt in plaats van
     * een eigen kopie (die dan niet mee zou opschuiven bij een update hier).
     */
    internal const val DELTA_T_SECONDS = 69.2

    data class Equatorial(
        val raDeg: Double,      // rechte klimming, graden
        val decDeg: Double,     // declinatie, graden
        val distanceKm: Double,
    )

    data class Ecliptic(
        val lonDeg: Double,     // geocentrische ecliptische lengte (zonder nutatie)
        val latDeg: Double,     // geocentrische ecliptische breedte
        val distanceKm: Double,
    )

    data class MoonPosition(
        val azimuthDeg: Double,     // 0 = noord, 90 = oost
        val elevationDeg: Double,   // boven de horizon als > 0
        val distanceKm: Double,     // afstand waarnemer - maan
        val raDeg: Double = 0.0,    // topocentrische rechte klimming (voor de hemeltemperatuur)
        val decDeg: Double = 0.0,   // topocentrische declinatie
    ) {
        val isAboveHorizon: Boolean get() = elevationDeg > 0.0
    }

    // ---------- Tijd ----------

    /** Juliaanse dag uit milliseconden sinds 1970-01-01 UTC. */
    fun julianDay(epochMillis: Long): Double = epochMillis / 86_400_000.0 + 2_440_587.5

    // ---------- Hoofdstuk 47 ----------

    /** Ecliptische coördinaten voor een gegeven Juliaanse Ephemeris Dag (TT). */
    fun eclipticPosition(jde: Double): Ecliptic {
        val t = (jde - 2_451_545.0) / 36_525.0
        val t2 = t * t; val t3 = t2 * t; val t4 = t3 * t

        val lp = norm(218.3164477 + 481267.88123421 * t - 0.0015786 * t2 + t3 / 538841.0 - t4 / 65194000.0)
        val d = norm(297.8501921 + 445267.1114034 * t - 0.0018819 * t2 + t3 / 545868.0 - t4 / 113065000.0)
        val m = norm(357.5291092 + 35999.0502909 * t - 0.0001536 * t2 + t3 / 24490000.0)
        val mp = norm(134.9633964 + 477198.8675055 * t + 0.0087414 * t2 + t3 / 69699.0 - t4 / 14712000.0)
        val f = norm(93.2720950 + 483202.0175233 * t - 0.0036539 * t2 - t3 / 3526000.0 + t4 / 863310000.0)
        val a1 = norm(119.75 + 131.849 * t)
        val a2 = norm(53.09 + 479264.290 * t)
        val a3 = norm(313.45 + 481266.484 * t)
        val e = 1.0 - 0.002516 * t - 0.0000074 * t2

        var sumL = 0.0
        var sumR = 0.0
        for (row in TABLE_LR) {
            val arg = row[0] * d + row[1] * m + row[2] * mp + row[3] * f
            val eFactor = eccentricityFactor(row[1], e)
            sumL += row[4] * eFactor * sinD(arg)
            sumR += row[5] * eFactor * cosD(arg)
        }
        var sumB = 0.0
        for (row in TABLE_B) {
            val arg = row[0] * d + row[1] * m + row[2] * mp + row[3] * f
            sumB += row[4] * eccentricityFactor(row[1], e) * sinD(arg)
        }

        sumL += 3958.0 * sinD(a1) + 1962.0 * sinD(lp - f) + 318.0 * sinD(a2)
        sumB += -2235.0 * sinD(lp) + 382.0 * sinD(a3) + 175.0 * sinD(a1 - f) +
            175.0 * sinD(a1 + f) + 127.0 * sinD(lp - mp) - 115.0 * sinD(lp + mp)

        return Ecliptic(
            lonDeg = norm(lp + sumL / 1_000_000.0),
            latDeg = sumB / 1_000_000.0,
            distanceKm = 385_000.56 + sumR / 1000.0,
        )
    }

    private fun eccentricityFactor(mCoef: Double, e: Double): Double = when (abs(mCoef).toInt()) {
        1 -> e
        2 -> e * e
        else -> 1.0
    }

    // ---------- Nutatie (hoofdstuk 22, verkorte vorm) ----------

    private data class Nutation(val dPsiDeg: Double, val dEpsDeg: Double, val eps0Deg: Double)

    private fun nutation(jde: Double): Nutation {
        val t = (jde - 2_451_545.0) / 36_525.0
        val omega = norm(125.04452 - 1934.136261 * t)
        val lSun = norm(280.4665 + 36000.7698 * t)
        val lMoon = norm(218.3165 + 481267.8813 * t)
        val dPsi = (-17.20 * sinD(omega) - 1.32 * sinD(2 * lSun) - 0.23 * sinD(2 * lMoon) + 0.21 * sinD(2 * omega)) / 3600.0
        val dEps = (9.20 * cosD(omega) + 0.57 * cosD(2 * lSun) + 0.10 * cosD(2 * lMoon) - 0.09 * cosD(2 * omega)) / 3600.0
        val eps0 = 23.0 + 26.0 / 60.0 + 21.448 / 3600.0 +
            (-46.8150 * t - 0.00059 * t * t + 0.001813 * t * t * t) / 3600.0
        return Nutation(dPsi, dEps, eps0)
    }

    /** Schijnbare geocentrische rechte klimming en declinatie (JDE in TT). */
    fun equatorialPosition(jde: Double): Equatorial {
        val ecl = eclipticPosition(jde)
        val nut = nutation(jde)
        val lambda = ecl.lonDeg + nut.dPsiDeg
        val eps = nut.eps0Deg + nut.dEpsDeg
        val beta = ecl.latDeg
        val ra = norm(atan2D(sinD(lambda) * cosD(eps) - tanD(beta) * sinD(eps), cosD(lambda)))
        val dec = asinD(sinD(beta) * cosD(eps) + cosD(beta) * sinD(eps) * sinD(lambda))
        return Equatorial(ra, dec, ecl.distanceKm)
    }

    /** Schijnbare sterrentijd in Greenwich (graden), voor JD in UT. */
    fun apparentSiderealTimeDeg(jdUt: Double): Double {
        val t = (jdUt - 2_451_545.0) / 36_525.0
        val mean = 280.46061837 + 360.98564736629 * (jdUt - 2_451_545.0) +
            0.000387933 * t * t - t * t * t / 38_710_000.0
        val nut = nutation(jdUt)
        return norm(mean + nut.dPsiDeg * cosD(nut.eps0Deg + nut.dEpsDeg))
    }

    // ---------- Az/El voor een waarnemer ----------

    /**
     * Topocentrische azimut/elevatie van de maan.
     * @param latDeg breedte, noord positief
     * @param lonDeg lengte, OOST positief (zoals Maidenhead-omzetting oplevert)
     * @param epochMillis UTC-tijd in milliseconden sinds 1970
     * @param heightM hoogte boven zeeniveau in meter
     */
    fun position(latDeg: Double, lonDeg: Double, epochMillis: Long, heightM: Double = 0.0): MoonPosition {
        val jdUt = julianDay(epochMillis)
        val jde = jdUt + DELTA_T_SECONDS / 86_400.0
        val eq = equatorialPosition(jde)

        // Lokale uurhoek (graden)
        val lst = apparentSiderealTimeDeg(jdUt) + lonDeg
        val h = norm(lst - eq.raDeg)

        // Topocentrische correctie (Meeus hfst. 40 en 11)
        val sinPi = 6378.14 / eq.distanceKm
        val u = Math.toDegrees(kotlin.math.atan(0.99664719 * tanD(latDeg)))
        val rhoSin = 0.99664719 * sinD(u) + heightM / 6_378_140.0 * sinD(latDeg)
        val rhoCos = cosD(u) + heightM / 6_378_140.0 * cosD(latDeg)

        val dAlpha = atan2D(-rhoCos * sinPi * sinD(h), cosD(eq.decDeg) - rhoCos * sinPi * cosD(h))
        val decTopo = atan2D(
            (sinD(eq.decDeg) - rhoSin * sinPi) * cosD(dAlpha),
            cosD(eq.decDeg) - rhoCos * sinPi * cosD(h),
        )
        val hTopo = h - dAlpha

        // Horizontale coördinaten; azimut vanaf het noorden, over het oosten
        val az = norm(atan2D(sinD(hTopo), cosD(hTopo) * sinD(latDeg) - tanD(decTopo) * cosD(latDeg)) + 180.0)
        val el = asinD(sinD(latDeg) * sinD(decTopo) + cosD(latDeg) * cosD(decTopo) * cosD(hTopo))

        // Afstand tot de waarnemer (benadering, voor info)
        val distTopo = eq.distanceKm * sqrt(
            1.0 - 2.0 * sinPi * (rhoCos * cosD(eq.decDeg) * cosD(h) + rhoSin * sinD(eq.decDeg)) + sinPi * sinPi,
        )
        return MoonPosition(az, el, distTopo, norm(eq.raDeg + dAlpha), decTopo)
    }

    /** Punt op aarde waar de maan in het zenit staat, plus de horizontale parallax. */
    data class SubLunarPoint(val latDeg: Double, val lonDeg: Double, val parallaxDeg: Double)

    /** Sub-lunair punt (lengte oost positief, -180..180) voor een UTC-tijd. */
    fun subLunarPoint(epochMillis: Long): SubLunarPoint {
        val jdUt = julianDay(epochMillis)
        val eq = equatorialPosition(jdUt + DELTA_T_SECONDS / 86_400.0)
        var lon = norm(eq.raDeg - apparentSiderealTimeDeg(jdUt))
        if (lon > 180.0) lon -= 360.0
        return SubLunarPoint(eq.decDeg, lon, asinD(6378.14 / eq.distanceKm))
    }

    /**
     * Snelle benadering van de topocentrische elevatie vanuit het sub-lunaire punt
     * (voor de wereldkaart; nauwkeurig tot ongeveer 0,1 graad).
     */
    fun quickElevationDeg(sub: SubLunarPoint, latDeg: Double, lonDeg: Double): Double {
        val geo = asinD(
            sinD(latDeg) * sinD(sub.latDeg) +
                cosD(latDeg) * cosD(sub.latDeg) * cosD(lonDeg - sub.lonDeg),
        )
        return geo - sub.parallaxDeg * cosD(geo)
    }

    // ---------- Hulpfuncties ----------

    /** `internal`: ook gebruikt door [be.on5gs.eme.moon.MoonPhase], zie [DELTA_T_SECONDS]. */
    internal fun norm(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }

    internal fun sinD(d: Double) = sin(d * PI / 180.0)
    internal fun cosD(d: Double) = cos(d * PI / 180.0)
    private fun tanD(d: Double) = tan(d * PI / 180.0)
    private fun asinD(x: Double) = asin(x) * 180.0 / PI
    private fun atan2D(y: Double, x: Double) = atan2(y, x) * 180.0 / PI

    // Tabel 47.A: D, M, M', F, coëfficiënt Σl (1e-6 graad), coëfficiënt Σr (1e-3 km)
    private val TABLE_LR: Array<DoubleArray> = arrayOf(
        doubleArrayOf(0.0, 0.0, 1.0, 0.0, 6288774.0, -20905355.0),
        doubleArrayOf(2.0, 0.0, -1.0, 0.0, 1274027.0, -3699111.0),
        doubleArrayOf(2.0, 0.0, 0.0, 0.0, 658314.0, -2955968.0),
        doubleArrayOf(0.0, 0.0, 2.0, 0.0, 213618.0, -569925.0),
        doubleArrayOf(0.0, 1.0, 0.0, 0.0, -185116.0, 48888.0),
        doubleArrayOf(0.0, 0.0, 0.0, 2.0, -114332.0, -3149.0),
        doubleArrayOf(2.0, 0.0, -2.0, 0.0, 58793.0, 246158.0),
        doubleArrayOf(2.0, -1.0, -1.0, 0.0, 57066.0, -152138.0),
        doubleArrayOf(2.0, 0.0, 1.0, 0.0, 53322.0, -170733.0),
        doubleArrayOf(2.0, -1.0, 0.0, 0.0, 45758.0, -204586.0),
        doubleArrayOf(0.0, 1.0, -1.0, 0.0, -40923.0, -129620.0),
        doubleArrayOf(1.0, 0.0, 0.0, 0.0, -34720.0, 108743.0),
        doubleArrayOf(0.0, 1.0, 1.0, 0.0, -30383.0, 104755.0),
        doubleArrayOf(2.0, 0.0, 0.0, -2.0, 15327.0, 10321.0),
        doubleArrayOf(0.0, 0.0, 1.0, 2.0, -12528.0, 0.0),
        doubleArrayOf(0.0, 0.0, 1.0, -2.0, 10980.0, 79661.0),
        doubleArrayOf(4.0, 0.0, -1.0, 0.0, 10675.0, -34782.0),
        doubleArrayOf(0.0, 0.0, 3.0, 0.0, 10034.0, -23210.0),
        doubleArrayOf(4.0, 0.0, -2.0, 0.0, 8548.0, -21636.0),
        doubleArrayOf(2.0, 1.0, -1.0, 0.0, -7888.0, 24208.0),
        doubleArrayOf(2.0, 1.0, 0.0, 0.0, -6766.0, 30824.0),
        doubleArrayOf(1.0, 0.0, -1.0, 0.0, -5163.0, -8379.0),
        doubleArrayOf(1.0, 1.0, 0.0, 0.0, 4987.0, -16675.0),
        doubleArrayOf(2.0, -1.0, 1.0, 0.0, 4036.0, -12831.0),
        doubleArrayOf(2.0, 0.0, 2.0, 0.0, 3994.0, -10445.0),
        doubleArrayOf(4.0, 0.0, 0.0, 0.0, 3861.0, -11650.0),
        doubleArrayOf(2.0, 0.0, -3.0, 0.0, 3665.0, 14403.0),
        doubleArrayOf(0.0, 1.0, -2.0, 0.0, -2689.0, -7003.0),
        doubleArrayOf(2.0, 0.0, -1.0, 2.0, -2602.0, 0.0),
        doubleArrayOf(2.0, -1.0, -2.0, 0.0, 2390.0, 10056.0),
        doubleArrayOf(1.0, 0.0, 1.0, 0.0, -2348.0, 6322.0),
        doubleArrayOf(2.0, -2.0, 0.0, 0.0, 2236.0, -9884.0),
        doubleArrayOf(0.0, 1.0, 2.0, 0.0, -2120.0, 5751.0),
        doubleArrayOf(0.0, 2.0, 0.0, 0.0, -2069.0, 0.0),
        doubleArrayOf(2.0, -2.0, -1.0, 0.0, 2048.0, -4950.0),
        doubleArrayOf(2.0, 0.0, 1.0, -2.0, -1773.0, 4130.0),
        doubleArrayOf(2.0, 0.0, 0.0, 2.0, -1595.0, 0.0),
        doubleArrayOf(4.0, -1.0, -1.0, 0.0, 1215.0, -3958.0),
        doubleArrayOf(0.0, 0.0, 2.0, 2.0, -1110.0, 0.0),
        doubleArrayOf(3.0, 0.0, -1.0, 0.0, -892.0, 3258.0),
        doubleArrayOf(2.0, 1.0, 1.0, 0.0, -810.0, 2616.0),
        doubleArrayOf(4.0, -1.0, -2.0, 0.0, 759.0, -1897.0),
        doubleArrayOf(0.0, 2.0, -1.0, 0.0, -713.0, -2117.0),
        doubleArrayOf(2.0, 2.0, -1.0, 0.0, -700.0, 2354.0),
        doubleArrayOf(2.0, 1.0, -2.0, 0.0, 691.0, 0.0),
        doubleArrayOf(2.0, -1.0, 0.0, -2.0, 596.0, 0.0),
        doubleArrayOf(4.0, 0.0, 1.0, 0.0, 549.0, -1423.0),
        doubleArrayOf(0.0, 0.0, 4.0, 0.0, 537.0, -1117.0),
        doubleArrayOf(4.0, -1.0, 0.0, 0.0, 520.0, -1571.0),
        doubleArrayOf(1.0, 0.0, -2.0, 0.0, -487.0, -1739.0),
        doubleArrayOf(2.0, 1.0, 0.0, -2.0, -399.0, 0.0),
        doubleArrayOf(0.0, 0.0, 2.0, -2.0, -381.0, -4421.0),
        doubleArrayOf(1.0, 1.0, 1.0, 0.0, 351.0, 0.0),
        doubleArrayOf(3.0, 0.0, -2.0, 0.0, -340.0, 0.0),
        doubleArrayOf(4.0, 0.0, -3.0, 0.0, 330.0, 0.0),
        doubleArrayOf(2.0, -1.0, 2.0, 0.0, 327.0, 0.0),
        doubleArrayOf(0.0, 2.0, 1.0, 0.0, -323.0, 1165.0),
        doubleArrayOf(1.0, 1.0, -1.0, 0.0, 299.0, 0.0),
        doubleArrayOf(2.0, 0.0, 3.0, 0.0, 294.0, 0.0),
        doubleArrayOf(2.0, 0.0, -1.0, -2.0, 0.0, 8752.0),
    )

    // Tabel 47.B: D, M, M', F, coëfficiënt Σb (1e-6 graad)
    private val TABLE_B: Array<DoubleArray> = arrayOf(
        doubleArrayOf(0.0, 0.0, 0.0, 1.0, 5128122.0),
        doubleArrayOf(0.0, 0.0, 1.0, 1.0, 280602.0),
        doubleArrayOf(0.0, 0.0, 1.0, -1.0, 277693.0),
        doubleArrayOf(2.0, 0.0, 0.0, -1.0, 173237.0),
        doubleArrayOf(2.0, 0.0, -1.0, 1.0, 55413.0),
        doubleArrayOf(2.0, 0.0, -1.0, -1.0, 46271.0),
        doubleArrayOf(2.0, 0.0, 0.0, 1.0, 32573.0),
        doubleArrayOf(0.0, 0.0, 2.0, 1.0, 17198.0),
        doubleArrayOf(2.0, 0.0, 1.0, -1.0, 9266.0),
        doubleArrayOf(0.0, 0.0, 2.0, -1.0, 8822.0),
        doubleArrayOf(2.0, -1.0, 0.0, -1.0, 8216.0),
        doubleArrayOf(2.0, 0.0, -2.0, -1.0, 4324.0),
        doubleArrayOf(2.0, 0.0, 1.0, 1.0, 4200.0),
        doubleArrayOf(2.0, 1.0, 0.0, -1.0, -3359.0),
        doubleArrayOf(2.0, -1.0, -1.0, 1.0, 2463.0),
        doubleArrayOf(2.0, -1.0, 0.0, 1.0, 2211.0),
        doubleArrayOf(2.0, -1.0, -1.0, -1.0, 2065.0),
        doubleArrayOf(0.0, 1.0, -1.0, -1.0, -1870.0),
        doubleArrayOf(4.0, 0.0, -1.0, -1.0, 1828.0),
        doubleArrayOf(0.0, 1.0, 0.0, 1.0, -1794.0),
        doubleArrayOf(0.0, 0.0, 0.0, 3.0, -1749.0),
        doubleArrayOf(0.0, 1.0, -1.0, 1.0, -1565.0),
        doubleArrayOf(1.0, 0.0, 0.0, 1.0, -1491.0),
        doubleArrayOf(0.0, 1.0, 1.0, 1.0, -1475.0),
        doubleArrayOf(0.0, 1.0, 1.0, -1.0, -1410.0),
        doubleArrayOf(0.0, 1.0, 0.0, -1.0, -1344.0),
        doubleArrayOf(1.0, 0.0, 0.0, -1.0, -1335.0),
        doubleArrayOf(0.0, 0.0, 3.0, 1.0, 1107.0),
        doubleArrayOf(4.0, 0.0, 0.0, -1.0, 1021.0),
        doubleArrayOf(4.0, 0.0, -1.0, 1.0, 833.0),
        doubleArrayOf(0.0, 0.0, 1.0, -3.0, 777.0),
        doubleArrayOf(4.0, 0.0, -2.0, 1.0, 671.0),
        doubleArrayOf(2.0, 0.0, 0.0, -3.0, 607.0),
        doubleArrayOf(2.0, 0.0, 2.0, -1.0, 596.0),
        doubleArrayOf(2.0, -1.0, 1.0, -1.0, 491.0),
        doubleArrayOf(2.0, 0.0, -2.0, 1.0, -451.0),
        doubleArrayOf(0.0, 0.0, 3.0, -1.0, 439.0),
        doubleArrayOf(2.0, 0.0, 2.0, 1.0, 422.0),
        doubleArrayOf(2.0, 0.0, -3.0, -1.0, 421.0),
        doubleArrayOf(2.0, 1.0, -1.0, 1.0, -366.0),
        doubleArrayOf(2.0, 1.0, 0.0, 1.0, -351.0),
        doubleArrayOf(4.0, 0.0, 0.0, 1.0, 331.0),
        doubleArrayOf(2.0, -1.0, 1.0, 1.0, 315.0),
        doubleArrayOf(2.0, -2.0, 0.0, -1.0, 302.0),
        doubleArrayOf(0.0, 0.0, 1.0, 3.0, -283.0),
        doubleArrayOf(2.0, 1.0, 1.0, -1.0, -229.0),
        doubleArrayOf(1.0, 1.0, 0.0, -1.0, 223.0),
        doubleArrayOf(1.0, 1.0, 0.0, 1.0, 223.0),
        doubleArrayOf(0.0, 1.0, -2.0, -1.0, -220.0),
        doubleArrayOf(2.0, 1.0, -1.0, -1.0, -220.0),
        doubleArrayOf(1.0, 0.0, 1.0, 1.0, -185.0),
        doubleArrayOf(2.0, -1.0, -2.0, -1.0, 181.0),
        doubleArrayOf(0.0, 1.0, 2.0, 1.0, -177.0),
        doubleArrayOf(4.0, 0.0, -2.0, -1.0, 176.0),
        doubleArrayOf(4.0, -1.0, -1.0, -1.0, 166.0),
        doubleArrayOf(1.0, 0.0, 1.0, -1.0, -164.0),
        doubleArrayOf(4.0, 0.0, 1.0, -1.0, 132.0),
        doubleArrayOf(1.0, 0.0, -1.0, -1.0, -119.0),
        doubleArrayOf(4.0, -1.0, 0.0, -1.0, 115.0),
        doubleArrayOf(2.0, -2.0, 0.0, 1.0, 107.0),
    )
}

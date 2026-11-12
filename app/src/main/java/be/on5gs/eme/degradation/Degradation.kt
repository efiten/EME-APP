package be.on5gs.eme.degradation

import be.on5gs.eme.moon.MoonCalculator
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * EME-banden voor de degradatie (50 t.e.m. 1296 MHz), met een standaard Tsys (K) die je kunt aanpassen.
 * Hogere banden zijn weggelaten: daar is de hemelruis zo laag dat de degradatie bijna alleen
 * nog van de maanafstand afhangt en dus altijd "goed" lijkt.
 */
enum class EmeBand(val label: String, val mhz: Double, val defaultTsys: Int) {
    B50("50", 50.0, 150),
    B144("144", 144.0, 80),
    B222("222", 222.0, 70),
    B432("432", 432.0, 60),
    B902("902", 903.0, 50),
    B1296("1296", 1296.0, 50),
}

/** Beoordeling zoals op MMMonVHF. */
enum class Condition { EXCELLENT, GOOD, FAIR, POOR, VERY_POOR }

/**
 * EME-degradatie zoals MMMonVHF (www.mmmonvhf.de/eme.php) ze berekent:
 *
 *   DEG [dB] = Tsky / (TskyMin + Tsys) + RangeFactor
 *
 *  - Tsky: hemeltemperatuur achter de maan bij 144 MHz, opgezocht met de rechte klimming van de
 *    maan (tabel van MMMonVHF, naar EME Handbook 2010 / WSJT 7.03-gegevens van K1JT).
 *    Voor andere banden geschaald met (144/f)^2.6, zoals WSJT-X doet; minimum 3 K (kosmische achtergrond).
 *  - TskyMin: laagste hemeltemperatuur (200 K bij 144 MHz, ook geschaald).
 *  - RangeFactor: extra padverlies t.o.v. de maan op zijn dichtst (perigeum).
 *
 * Opmerking: zoals op MMMonVHF wordt de verhouding Tsky/(TskyMin+Tsys) gewoon bij de dB opgeteld.
 * Zo zijn de getallen en de beoordeling (Excellent ... Very Poor) dezelfde als op die site.
 */
object Degradation {

    data class Result(
        val degDb: Double,
        val tskyK: Double,
        val tskyMinK: Double,
        val rangeFactorDb: Double,
        val distanceKm: Double,
        val condition: Condition,
        val decDeg: Double = 0.0,     // declinatie van de maan (topocentrisch)
    )

    /** Hemeltemperatuur bij 144 MHz (K) per 0,1 uur rechte klimming, 0.0 t.e.m. 24.0 uur. */
    private val TSKY_144 = intArrayOf(
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
    )

    private const val TSKY_MIN_144 = 200.0
    private const val CMB_K = 3.0

    fun tsky144(raHours: Double): Double {
        // Zoals MMMonVHF: afronden op hele minuten, dan op 0,1 uur
        val minutes = (raHours * 60).roundToInt()
        val idx = ((minutes / 60.0) * 10).roundToInt().coerceIn(0, 240)
        return TSKY_144[idx].toDouble()
    }

    private fun scale(t144: Double, mhz: Double) = maxOf(CMB_K, t144 * (144.0 / mhz).pow(2.6))

    /** Padverlies-verschil t.o.v. het perigeum (dB), zoals MMMonVHF (frequentie-onafhankelijk). */
    fun rangeFactorDb(distanceKm: Double): Double {
        val radar = 10 * log10(4 * distanceKm * distanceKm / (1700.0 * 1700.0))
        val pathloss = 32.45 + 20 * log10(144.0) + 20 * log10(distanceKm * 2) + radar - 10 * log10(0.07)
        return pathloss - 256.34
    }

    fun conditionOf(deg: Double): Condition = when {
        deg <= 1.5 -> Condition.EXCELLENT
        deg <= 2.5 -> Condition.GOOD
        deg <= 4.0 -> Condition.FAIR
        deg <= 5.5 -> Condition.POOR
        else -> Condition.VERY_POOR
    }

    fun compute(epochMillis: Long, latDeg: Double, lonDeg: Double, band: EmeBand, tsysK: Double): Result {
        val moon = MoonCalculator.position(latDeg, lonDeg, epochMillis)
        val tsky = scale(tsky144(moon.raDeg / 15.0), band.mhz)
        val tskyMin = scale(TSKY_MIN_144, band.mhz)
        val rf = rangeFactorDb(moon.distanceKm)
        val deg = Math.round((tsky / (tskyMin + tsysK) + rf) * 10) / 10.0
        return Result(deg, tsky, tskyMin, rf, moon.distanceKm, conditionOf(deg), moon.decDeg)
    }

    /** Eén waarde per dag om 12:00 UTC (zoals MMMonVHF), vanaf [startDate], [days] dagen. */
    fun daily(startDate: LocalDate, days: Int, latDeg: Double, lonDeg: Double, band: EmeBand, tsysK: Double): List<Pair<LocalDate, Result>> =
        (0 until days).map { d ->
            val date = startDate.plusDays(d.toLong())
            val noon = date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
            date to compute(noon, latDeg, lonDeg, band, tsysK)
        }
}

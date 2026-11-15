package be.on5gs.eme.horizon

import androidx.test.ext.junit.runners.AndroidJUnit4
import be.on5gs.eme.ExpectedCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Inlezen van horizon-survey (horizon.dl6zg.de); zelfde tabel als de webtests. org.json komt van Android. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HorizonSurveyTest {

    private fun file(name: String) = javaClass.classLoader!!.getResource("horizon_survey/$name")!!.readText()

    @Test
    fun surveysAsInTheSharedTable() {
        val rows = ExpectedCsv.read("horizon_import")
        assertEquals(8, rows.size)
        rows.forEach { r ->
            val got = HorizonSurvey.parse(file(r.getValue("file")))
            when (val err = r.getValue("error")) {
                "" -> {
                    got as HorizonSurvey.Result.Ok
                    assertEquals("${r["file"]}", r.getValue("points").toInt(), got.points)
                    assertEquals("${r["file"]}", r.getValue("horizon"), Horizon(0.0, got.obstacles).encode())
                }
                "TOO_FEW_POINTS" -> assertEquals("${r["file"]}", HorizonSurvey.Result.TooFewPoints, got)
                else -> assertEquals("${r["file"]}", HorizonSurvey.Result.NotASurvey, got)
            }
        }
    }

    /** JSON en CSV van dezelfde opmeting geven hetzelfde; het ijkpunt in de CSV telt niet mee. */
    @Test
    fun jsonAndCsvOfTheSameSurveyAgree() {
        val a = HorizonSurvey.parse(file("horizon_survey_example.json"))
        val b = HorizonSurvey.parse(file("horizon_survey_example.csv"))
        assertEquals(a, b)
    }

    /** Elke gemeten richting is na het inlezen geblokkeerd tot de gemeten hoogte (nooit te optimistisch). */
    @Test
    fun neverMoreOptimisticThanTheMeasurement() {
        val ok = HorizonSurvey.parse(file("horizon_survey_example.json")) as HorizonSurvey.Result.Ok
        val h = Horizon(0.0, ok.obstacles)
        HorizonSurvey.readPoints(file("horizon_survey_example.json"))!!.forEach { (az, el) ->
            if (el > 0.0) assertEquals("az $az", Visibility.BLOCKED, h.visibility(az, el - 0.01))
        }
        assertTrue(h.visibility(85.0, 25.0) == Visibility.CLEAR)
    }

    @Test
    fun brokenJsonIsNotASurvey() {
        assertEquals(HorizonSurvey.Result.NotASurvey, HorizonSurvey.parse("{ \"points\": [ { \"az\": 1"))
        assertEquals(HorizonSurvey.Result.NotASurvey, HorizonSurvey.parse(""))
        assertEquals(HorizonSurvey.Result.TooFewPoints, HorizonSurvey.parse("{\"points\":[]}"))
    }
}

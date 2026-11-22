package app.weathertrack.core

import app.weathertrack.core.history.DailyObs
import app.weathertrack.core.seasonal.Enso
import app.weathertrack.core.seasonal.EnsoPhase
import app.weathertrack.core.seasonal.Outlook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class OutlookTest {
    @Test
    fun oniParsesAndNeedsFiveSeasonsInARow() {
        val text = """
             SEAS  YR   TOTAL   ANOM
              DJF 2000  24.72  -0.40
              JFM 2000  25.00   0.60
              FMA 2000  26.00   0.70
              MAM 2000  27.00   0.80
              AMJ 2000  27.50   0.90
              MJJ 2000  27.60   1.00
              JJA 2000  27.00   0.20
              JAS 2000  26.80  -0.60
              ASO 2000  26.50  -0.70
        """.trimIndent()
        val oni = Enso.parseOni(text)
        assertEquals(9, oni.size)
        assertEquals(YearMonth.of(2000, 1), oni.first().month)
        assertEquals(EnsoPhase.EL_NINO, oni[1].phase)
        assertEquals(EnsoPhase.EL_NINO, oni[5].phase)
        assertEquals(EnsoPhase.NEUTRAL, oni[7].phase) // only 2 seasons: not an episode
        val now = Enso.now(oni)!!
        assertEquals(EnsoPhase.LA_NINA, now.phase)
        assertTrue(!now.official)
    }

    @Test
    fun ndjIsCentredOnDecember() {
        val oni = Enso.parseOni("NDJ 1997 28.0 2.40")
        assertEquals(YearMonth.of(1997, 12), oni.single().month)
    }

    @Test
    fun seasonalParsesMembersAndMonthStrings() {
        val body = """{"monthly":{"time":["2026-12-01","2027-01"],
            "temperature_2m_anomaly_member01":[1.0,null],"temperature_2m_anomaly_member02":[0.0,-0.5],
            "precipitation_mean":[120.0,50.0],"precipitation_anomaly":[20.0,-50.0]}}"""
        val m = Outlook.parseSeasonal(body)
        assertEquals(2, m.size)
        assertEquals(YearMonth.of(2026, 12), m[0].month)
        assertEquals(0.5, m[0].tempAnomaly!!, 1e-9)
        assertEquals(1.2, m[0].rainRatio!!, 1e-9)
        assertEquals(-0.5, m[1].tempAnomaly!!, 1e-9)
        assertEquals(0.5, m[1].rainRatio!!, 1e-9)
        assertTrue(Outlook.parseSeasonal("not json").isEmpty())
        assertTrue(Outlook.parseSeasonal("""{"error":true}""").isEmpty())
    }

    @Test
    fun buildFindsElNinoEffectAndModelMonths() {
        // 30 years: January is 2° warmer and 50% wetter in "El Niño" years (every 3rd year).
        val ninoYears = (1991..2020).filter { it % 3 == 0 }.toSet()
        val obs = mutableListOf<DailyObs>()
        var d = LocalDate.of(1991, 1, 1)
        while (d.isBefore(LocalDate.of(2026, 12, 1))) {
            val nino = d.monthValue == 1 && d.year in ninoYears
            val t = 15.0 + if (nino) 2.0 else 0.0
            obs += DailyObs(d, t + 5, t - 5, if (nino) 3.0 else 2.0)
            d = d.plusDays(1)
        }
        val oni = Enso.label((1991..2020).flatMap { y ->
            (1..12).map { m -> YearMonth.of(y, m) to if (y in ninoYears) 1.0 else 0.0 }
        })
        val first = YearMonth.of(2026, 12)
        val model = Outlook.parseSeasonal("""{"monthly":{"time":["2026-12-01"],"temperature_2m_anomaly":[0.4],
            "precipitation_mean":[90],"precipitation_anomaly":[-10]}}""")
        val v = Outlook.build(obs, first, southern = true, model = model, oni = oni)
        assertEquals(12, v.months.size)
        assertEquals(1, v.modelMonths)
        val dec = v.months[0]
        assertNotNull(dec.temp)
        assertEquals(dec.normalTemp!! + 0.4, dec.temp!!, 1e-9)
        assertEquals(dec.normalRain!! * 0.9, dec.rain!!, 1e-9)
        val jan = v.months[1]
        assertNull(jan.model)
        val e = jan.elNino!!
        assertEquals(10, e.years)
        assertTrue(e.tempShift!! > 1.0)
        assertTrue(e.rainRatio!! > 1.2)
        assertNull(jan.laNina)
    }
}

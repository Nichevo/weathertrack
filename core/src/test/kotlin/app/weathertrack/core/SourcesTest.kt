package app.weathertrack.core

import app.weathertrack.core.accuracy.ObsHour
import app.weathertrack.core.accuracy.RainReport
import app.weathertrack.core.sources.IssuedSourceHour
import app.weathertrack.core.sources.SourceHour
import app.weathertrack.core.sources.Sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesTest {
    @Test fun parsesMultiModelKeys() {
        val body = """{"hourly":{"time":[3600,7200],"temperature_2m_ecmwf_ifs025":[15.0,16.0],"precipitation_ecmwf_ifs025":[0.0,0.4],
            "temperature_2m_gfs_seamless":[14.0,null],"precipitation_gfs_seamless":[1.2,null],"precipitation_probability_gfs_seamless":[80,null]}}"""
        val rows = Sources.parseModels(body, Sources.MODELS.take(2))
        assertEquals(3, rows.size)
        val ec = rows.filter { it.source == "ecmwf_ifs025" }
        assertEquals(5.0, ec[0].rainChance!!, 0.01)
        assertEquals(70.0, ec[1].rainChance!!, 0.01)
        assertEquals(80.0, rows.first { it.source == "gfs_seamless" }.rainChance!!, 0.01)
    }

    @Test fun ensembleChanceIsShareOfWetMembers() {
        val body = """{"hourly":{"time":[3600],"precipitation":[0.0],"precipitation_member01":[0.3],"precipitation_member02":[0.0],"precipitation_member03":[1.0],
            "temperature_2m":[10.0],"temperature_2m_member01":[12.0]}}"""
        val h = Sources.parseEnsemble(body, Sources.ENSEMBLES[0]).single()
        assertEquals("ens_ecmwf_ifs025", h.source)
        assertEquals(50.0, h.rainChance!!, 0.01)
        assertEquals(11.0, h.temperature!!, 0.01)
    }

    @Test fun parsesMetno() {
        val body = """{"properties":{"timeseries":[{"time":"2026-11-22T05:00:00Z","data":{"instant":{"details":{"air_temperature":18.5}},
            "next_1_hours":{"details":{"precipitation_amount":0.6}}}},{"time":"2026-11-30T00:00:00Z","data":{"instant":{"details":{"air_temperature":10}}}}]}}"""
        val h = Sources.parseMetno(body).single()
        assertEquals(java.time.OffsetDateTime.parse("2026-11-22T06:00:00Z").toEpochSecond(), h.time)
        assertEquals(90.0, h.rainChance!!, 0.01)
    }

    @Test fun scoresWeightsAndBlend() {
        val hours = (1L..100L).map { it * 3600 }
        val obs = hours.map { ObsHour(it, 15.0, null, if (it % 7200 == 0L) 1.0 else 0.0, null) }
        val good = hours.map { IssuedSourceHour("good", it - 6 * 3600, it, 15.2, if (it % 7200 == 0L) 1.0 else 0.0, if (it % 7200 == 0L) 90.0 else 5.0) }
        val bad = hours.map { IssuedSourceHour("bad", it - 6 * 3600, it, 18.0, 1.0, 90.0) }
        val reports = listOf(RainReport(hours[10] - 1800, false, null, null))
        val scores = Sources.scores(good + bad, obs, reports)
        assertEquals("good", scores.first().source)
        assertTrue(scores.first().brier!! < scores.last().brier!!)
        assertEquals(1, scores.first { it.source == "good" }.reportsTotal)
        val w = Sources.weights(listOf("good", "bad"), scores).associateBy { it.source }
        assertTrue(w.getValue("good").rain > 0.7)
        assertTrue(w.getValue("good").temp > w.getValue("bad").temp)
        // No record yet: equal weights.
        val eq = Sources.weights(listOf("a", "b"), emptyList())
        assertEquals(0.5, eq[0].rain, 1e-9)
        val blend = Sources.blend(listOf(SourceHour("good", 3600 * 200, 10.0, 0.0, 10.0), SourceHour("bad", 3600 * 200, 20.0, 2.0, 90.0)),
            Sources.weights(listOf("good", "bad"), scores), 3600 * 199).single()
        assertTrue(blend.rainChance!! < 40)
        assertEquals(10.0, blend.chanceLow!!, 0.01)
        assertEquals(90.0, blend.chanceHigh!!, 0.01)
    }
}

class LowErrorsTest {
    @Test fun lowErrorIsActualMinusForecast() {
        val zone = java.time.ZoneId.of("UTC")
        val d = java.time.LocalDate.of(2026, 10, 1)
        val start = d.atStartOfDay(zone).toEpochSecond()
        val runs = (0 until 24).map { app.weathertrack.core.accuracy.RunHour(start + it * 3600L, 1, 10.0 + it % 5, 0.0) }
        val obs = (0 until 24).map { ObsHour(start + it * 3600L, 8.0 + it % 5, null, 0.0, null) }
        val e = app.weathertrack.core.accuracy.ForecastCheck.lowErrors(runs, obs, zone, d, leads = listOf(1)).single()
        assertEquals(-2.0, e.bias, 1e-9)
    }
}

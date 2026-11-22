package app.weathertrack.core

import app.weathertrack.core.tides.Linz
import app.weathertrack.core.tides.Tides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class LinzTest {
    private val csv = """
        070,Auckland,36°51'S,174°46'E
        Based on constituent set with reference date:,31-Dec-2014
        Local Std or Daylight Time,Tidal heights in metres.
        1,Th,1,2026,05:47,3.1,11:51,0.9,18:06,3.1,,
        2,Fr,1,2026,00:19,0.6,06:49,3.2,12:51,0.8,19:09,3.2
    """.trimIndent()

    @Test
    fun parsesHighsAndLowsInLocalTime() {
        val e = Linz.parse(csv)
        assertEquals(7, e.size)
        assertEquals(LocalDateTime.of(2026, 1, 1, 5, 47).atZone(Linz.ZONE).toEpochSecond(), e[0].time)
        assertEquals(listOf(true, false, true, false, true, false, true), e.map { it.high })
        assertEquals(0.6, e[3].height, 1e-9)
    }

    @Test
    fun curvePassesThroughTheTidesAndFeedsTheCard() {
        val e = Linz.parse(csv)
        val port = Linz.PORTS.first()
        val s = Linz.series(port, e, e.first().time, e.last().time)!!
        assertTrue(s.points.all { it.height in 0.59..3.21 })
        val mid = (e[1].time + e[2].time) / 2
        val now = Tides.now(s, mid)!!
        assertTrue(now.rising)
        assertEquals(e[2], now.next)
    }

    @Test
    fun nearestPortAndUrl() {
        assertEquals("Auckland", Linz.nearest(-36.90, 174.90)?.first?.name)
        assertNull(Linz.nearest(-33.0, 151.0))
        assertEquals("Onehunga", Linz.nearest(-36.93, 174.70)?.first?.name)
        assertEquals("https://static.charts.linz.govt.nz/tide-tables/maj-ports/csv/Port%20Taranaki%202026.csv",
            Linz.csvUrl(Linz.PORTS.first { it.name == "Port Taranaki" }, 2026))
    }
}

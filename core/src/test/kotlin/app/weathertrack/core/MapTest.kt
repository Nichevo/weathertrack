package app.weathertrack.core

import app.weathertrack.core.map.RainBand
import app.weathertrack.core.map.RainGrid
import app.weathertrack.core.map.RainViewer
import app.weathertrack.core.map.TileMath
import app.weathertrack.core.openmeteo.OpenMeteoParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapTest {
    @Test fun tileMathRoundTrips() {
        val z = 10
        val x = TileMath.x(174.76, z)
        val y = TileMath.y(-36.85, z)
        assertEquals(174.76, TileMath.lon(x, z), 1e-9)
        assertEquals(-36.85, TileMath.lat(y, z), 1e-9)
        // Auckland is in tile 1009, 624 at zoom 10.
        assertEquals(1009, (x / 256).toInt())
        assertEquals(624, (y / 256).toInt())
        val tiles = TileMath.tilesFor(x, y, z, 512.0, 512.0)
        assertTrue(tiles.size in 4..9)
        assertEquals(3, TileMath.wrapX(-1, 2))
    }

    @Test fun gridInterpolatesAndFindsThunder() {
        val b = RainGrid.points(-36.85, 174.76, 20.0, 3)
        assertEquals(9, b.lats.size)
        // Rain only at the north-west point: 1 mm per 15 min = 4 mm/h.
        val rain = (0 until 9).map { if (it == 0) listOf(1.0, 0.0) else listOf(0.0, 0.0) }
        val codes = (0 until 9).map { if (it == 4) listOf(95) else listOf(3) }
        val g = RainGrid(b.north, b.west, b.south, b.east, 3, 3, listOf(1000L, 1900L), rain, listOf(0L), codes)
        assertEquals(4.0, g.rateAt(b.north, b.west, 0)!!, 1e-9)
        assertEquals(1.0, g.rateAt((b.north + g.lat(1)) / 2, (b.west + g.lon(1)) / 2, 0)!!, 1e-9)
        assertNull(g.rateAt(b.north + 1, b.west, 0))
        assertEquals(0, g.timeIndex(1000))
        assertEquals(1, g.timeIndex(2700))
        assertNull(g.timeIndex(2800))
        assertEquals(listOf(g.lat(1) to g.lon(1)), g.thunderPoints(100))
        assertEquals(RainBand.HEAVY, RainBand.of(4.0))
        assertNull(RainBand.of(0.05))
    }

    @Test fun parsesMultiLocationGrid() {
        val b = RainGrid.points(0.0, 0.0, 10.0, 2)
        val one = """{"minutely_15":{"time":[0,900],"precipitation":[0.2,0.0]},"hourly":{"time":[0],"weather_code":[61]}}"""
        val g = OpenMeteoParser.parseRainGrid("[$one,$one,$one,$one]", b)
        assertEquals(listOf(0L, 900L), g.times)
        assertEquals(0.8, g.rateAt(0.0, 0.0, 0)!!, 1e-9)
    }

    @Test fun parsesRainViewer() {
        val body = """{"host":"https://tilecache.rainviewer.com","radar":{"past":[{"time":200,"path":"/v2/radar/200"},{"time":100,"path":"/v2/radar/100"}],"nowcast":[]}}"""
        val f = RainViewer.parse(body)
        assertEquals(listOf(100L, 200L), f.past.map { it.time })
        assertEquals("https://tilecache.rainviewer.com/v2/radar/200/512/7/1/2/2/1_0.png", RainViewer.tileUrl(f.host, f.past[1], 7, 1, 2))
    }
}

class RainOutlookTest {
    @Test fun describesRainNearAndOver() {
        val b = RainGrid.points(-36.85, 174.76, 20.0, 3)
        // Rain only at the west-middle point in step 0, everywhere in step 1.
        val rain = (0 until 9).map { if (it == 3) listOf(0.5, 0.5) else listOf(0.0, 0.5) }
        val g = RainGrid(b.north, b.west, b.south, b.east, 3, 3, listOf(0L, 900L), rain, listOf(0L), List(9) { listOf(3) })
        val dry = app.weathertrack.core.map.RainOutlooks.at(g, -36.85, 174.76, 10)!!
        assertEquals("Dry over you", dry.headline)
        assertEquals("Rain about 20 km to the west", dry.detail)
        val wet = app.weathertrack.core.map.RainOutlooks.at(g, -36.85, 174.76, 1000)!!
        assertEquals("Moderate rain over you", wet.headline)
        assertEquals(900L, app.weathertrack.core.map.RainOutlooks.firstRain(g, -36.85, 174.76, 10))
    }
}

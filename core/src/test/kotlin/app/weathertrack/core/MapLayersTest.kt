package app.weathertrack.core

import app.weathertrack.core.map.ColorScale
import app.weathertrack.core.map.Contours
import app.weathertrack.core.map.Gdacs
import app.weathertrack.core.map.Gibs
import app.weathertrack.core.map.LayerScales
import app.weathertrack.core.map.RainGrid
import app.weathertrack.core.openmeteo.OpenMeteoParser
import app.weathertrack.core.openmeteo.OpenMeteoUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLayersTest {
    private fun point(cloud: Double, speed: Double, dir: Double, p: Double = 1013.0) =
        """{"minutely_15":{"time":[0],"precipitation":[0]},"hourly":{"time":[0,3600],"weather_code":[3,3],""" +
            """"cloud_cover":[$cloud,0],"wind_speed_10m":[$speed,0],"wind_direction_10m":[$dir,0],"pressure_msl":[$p,$p]}}"""

    @Test fun parsesHourlyFieldsAndInterpolates() {
        val b = RainGrid.points(0.0, 0.0, 10.0, 2)
        // North-west and north-east blow from 350° and 10°: the average is from the north, not the south.
        val body = "[${point(100.0, 20.0, 350.0)},${point(0.0, 20.0, 10.0)},${point(100.0, 20.0, 350.0)},${point(0.0, 20.0, 10.0)}]"
        val g = OpenMeteoParser.parseRainGrid(body, b)
        assertEquals(setOf("cloud_cover", "wind_speed_10m", "wind_direction_10m", "pressure_msl"), g.fields.keys)
        assertEquals(50.0, g.fieldAt("cloud_cover", 0.0, 0.0, 0)!!, 1e-9)
        assertEquals(100.0, g.fieldAt("cloud_cover", b.north, b.west, 0)!!, 1e-9)
        assertNull(g.fieldAt("uv_index", 0.0, 0.0, 0))
        val w = g.windAt(0.0, 0.0, 0)!!
        assertEquals(20.0, w.speed, 1e-9)
        assertTrue(w.fromDeg < 0.5 || w.fromDeg > 359.5)
        assertEquals("N", w.compass)
        assertEquals(1, g.hourIndex(4000))
        assertNull(g.hourIndex(7300))
        assertEquals(1, g.nearestHour(9000))
    }

    @Test fun layerUrlsWrapTheDateLine() {
        assertEquals(-175.0, OpenMeteoUrls.wrapLon(185.0), 1e-9)
        assertEquals(170.0, OpenMeteoUrls.wrapLon(170.0), 1e-9)
        val u = OpenMeteoUrls.rainGrid(listOf(-40.0), listOf(185.0), layerHours = 25)
        assertTrue(u.contains("longitude=-175.0000"))
        assertTrue(u.contains("hourly=weather_code,cloud_cover,") && u.contains("forecast_hours=25"))
        assertFalse(OpenMeteoUrls.rainGrid(listOf(-40.0), listOf(170.0)).contains("cloud_cover"))
        assertTrue(OpenMeteoUrls.pressureGrid(listOf(-40.0), listOf(170.0)).contains("pressure_msl"))
    }

    @Test fun contoursFollowTheLevel() {
        // 3x3 with a peak in the middle: the 5 line is a closed ring of 4 pieces around it.
        val v = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 10.0, 0.0, 0.0, 0.0, 0.0)
        val segs = Contours.lines(v, 3, 3, 5.0)
        assertEquals(4, segs.size)
        assertTrue(segs.all { s -> listOf(s.x1, s.y1, s.x2, s.y2).all { it in 0.5..1.5 } })
        assertTrue(Contours.lines(v, 3, 3, 20.0).isEmpty())
    }

    @Test fun findsHighsAndLows() {
        val b = RainGrid.points(-40.0, 175.0, 300.0, 5)
        val p = (0 until 25).map { i ->
            val r = i / 5; val c = i % 5
            listOf(when { r == 1 && c == 1 -> 1030.0; r == 3 && c == 3 -> 990.0; else -> 1010.0 })
        }
        val g = RainGrid(b.north, b.west, b.south, b.east, 5, 5, emptyList(), emptyList(), listOf(0L), emptyList(), mapOf("pressure_msl" to p))
        val cs = Contours.centres(g, 0)
        assertEquals(2, cs.size)
        assertTrue(cs.single { it.high }.hPa == 1030.0)
        assertEquals(g.lat(3), cs.single { !it.high }.lat, 1e-9)
    }

    @Test fun colourScalesBlend() {
        val s = ColorScale(listOf(0.0 to 0x00000000, 10.0 to 0xFF0000FF.toInt()))
        assertEquals(0x7F00007F, s.colorAt(5.0))
        assertEquals(0xFF0000FF.toInt(), s.colorAt(99.0))
        assertEquals(0, LayerScales.VISIBILITY.colorAt(20000.0) ushr 24)
        assertTrue((LayerScales.VISIBILITY.colorAt(200.0) ushr 24) > 150)
    }

    @Test fun satelliteTimesAndUrls() {
        assertEquals("2026-11-23T18:00:00Z", Gibs.iso(1795456800))
        assertEquals(listOf(1795456800L, 1795456200L), Gibs.candidates(1795457399, 2))
        assertEquals(listOf(1795456200L, 1795456800L), Gibs.frames(1795456800, 2))
        assertEquals(
            "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/Himawari_AHI_Band13_Clean_Infrared/default/2026-11-23T18:00:00Z/GoogleMapsCompatible_Level6/5/19/31.png",
            Gibs.tileUrl(1795456800, 5, 31, 19),
        )
    }

    @Test fun parsesCyclones() {
        val events = """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[174.2,-28.0]},"properties":{"eventtype":"TC","eventid":1001400,"episodeid":7,
             "eventname":"TESTSTORM-27","alertlevel":"Orange","iscurrent":"true","severitydata":{"severity":157.4,"severityunit":"km/h"}}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[80.2,15.8]},"properties":{"eventid":1001346,"episodeid":10,
             "eventname":"OLD-26","alertlevel":"Red","iscurrent":"false"}}]}"""
        val e = Gdacs.parseEvents(events)
        assertEquals(2, e.size)
        val now = e.single { it.current }
        assertEquals("Teststorm", now.name)
        assertEquals(-28.0, now.lat, 1e-9)
        assertEquals(157.4, now.maxWindKmh!!, 1e-9)
        assertTrue(Gdacs.geometryUrl(now.id, now.episode).endsWith("eventid=1001400&episodeid=7"))

        val track = """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"LineString","coordinates":[[172.0,-25.0],[174.2,-28.0]]},"properties":{"Class":"Line_Line_0","polygonlabel":"TS","forecast":false}},
            {"type":"Feature","geometry":{"type":"LineString","coordinates":[[174.2,-28.0],[176.0,-31.0],[178.0,-34.0]]},"properties":{"Class":"Line_Line_1","polygonlabel":"HU","forecast":true}},
            {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[174.0,-28.0],[180.0,-34.0],[176.0,-35.0],[174.0,-28.0]]]},"properties":{"Class":"Poly_Cones"}},
            {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[172.0,-25.1],[172.1,-25.0],[172.0,-24.9],[171.9,-25.0]]]},"properties":{"Class":"Point_Polygon_Point_0"}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[174.2,-28.0]},"properties":{"Class":"Point_Centroid"}}]}"""
        val t = Gdacs.parseTrack(track)
        assertEquals(3, t.legs.size)
        assertEquals(2, t.legs.count { it.forecast })
        assertEquals("HU", t.legs.last().stage)
        assertEquals(-34.0, t.legs.last().lat2, 1e-9)
        assertEquals(4, t.cone.size)
        assertEquals(-28.0 to 174.0, t.cone.first())
        assertEquals(-25.0, t.points.single().first, 1e-9)
    }
}

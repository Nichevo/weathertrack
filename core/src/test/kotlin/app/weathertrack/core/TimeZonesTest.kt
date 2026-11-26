package app.weathertrack.core

import app.weathertrack.core.map.TimeZoneShapes
import app.weathertrack.core.map.ZoneClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class TimeZonesTest {
    private val shapes by lazy { File("../app/src/main/assets/timezones.bin").inputStream().use { TimeZoneShapes.read(it) } }

    @Test fun readsTheBundledBoundaries() {
        assertTrue(shapes.zones.size > 400)
        assertEquals("Pacific/Auckland", shapes.zoneAt(-36.85, 174.76)?.id)
        assertEquals("Pacific/Auckland", shapes.zoneAt(-41.29, 174.78)?.id)
        assertEquals("Pacific/Chatham", shapes.zoneAt(-43.95, -176.55)?.id)
        assertEquals("Europe/London", shapes.zoneAt(51.5, -0.12)?.id)
        assertEquals("Australia/Sydney", shapes.zoneAt(-33.87, 151.21)?.id)
        assertEquals("America/New_York", shapes.zoneAt(40.71, -74.0)?.id)
        // Longitude past 180 wraps round.
        assertEquals("Pacific/Auckland", shapes.zoneAt(-36.85, 174.76 - 360)?.id)
        // Open ocean falls in an Etc/GMT band.
        assertTrue(shapes.zoneAt(-30.0, -140.0)!!.id.startsWith("Etc/"))
        // Every zone name is one the phone's rules know.
        shapes.zones.forEach { assertNotNull(it.id, ZoneClock.zone(it.id)) }
    }

    @Test fun labelPointsSitInsideTheirZone() {
        val auckland = shapes.zones.first { it.id == "Pacific/Auckland" }
        assertTrue(auckland.contains(auckland.labelLat, auckland.labelLon))
    }

    @Test fun clockInfoAndLabels() {
        val summer = Instant.parse("2026-01-15T00:00:00Z")
        val winter = Instant.parse("2026-07-15T00:00:00Z")
        val s = ZoneClock.info("Pacific/Auckland", summer)!!
        assertEquals(13 * 3600, s.offsetSeconds)
        assertTrue(s.daylightSaving)
        assertEquals(12 * 3600, s.nextOffsetSeconds)
        assertFalse(ZoneClock.info("Pacific/Auckland", winter)!!.daylightSaving)
        assertEquals(null, ZoneClock.info("Asia/Tokyo", summer)!!.nextChange)

        assertEquals("UTC+13", ZoneClock.offsetLabel(13 * 3600))
        assertEquals("UTC+5:45", ZoneClock.offsetLabel(5 * 3600 + 45 * 60))
        assertEquals("UTC−3:30", ZoneClock.offsetLabel(-(3 * 3600 + 30 * 60)))
        assertEquals("UTC", ZoneClock.offsetLabel(0))
        assertEquals("4 h behind", ZoneClock.difference(9 * 3600, 13 * 3600))
        assertEquals("45 min ahead", ZoneClock.difference(13 * 3600 + 45 * 60, 13 * 3600))
        assertEquals("same time as you", ZoneClock.difference(3600, 3600))
        assertEquals("−4 h", ZoneClock.shortDifference(9 * 3600, 13 * 3600))
        assertEquals("+0:45", ZoneClock.shortDifference(13 * 3600 + 45 * 60, 13 * 3600))
        assertNotEquals(ZoneClock.color(12 * 3600), ZoneClock.color(13 * 3600))
    }
}

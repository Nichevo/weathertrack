package app.weathertrack.core

import app.weathertrack.core.alerts.AlertDecider
import app.weathertrack.core.alerts.TriggerKind.RAIN_SOON
import app.weathertrack.core.alerts.TriggerKind.TOO_COLD
import app.weathertrack.core.alerts.TriggerKind.TOO_HOT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class AlertDeciderTest {
    @Test fun notifiesOnStartOnly() {
        assertEquals(setOf(RAIN_SOON), AlertDecider.toNotify(setOf(RAIN_SOON), emptySet(), emptyMap(), 1000))
        assertEquals(emptySet<Any>(), AlertDecider.toNotify(setOf(RAIN_SOON), setOf(RAIN_SOON), mapOf(RAIN_SOON to 900L), 1000))
    }

    @Test fun renotifiesAfterThreeHoursStillActive() {
        val r = AlertDecider.toNotify(setOf(TOO_HOT, TOO_COLD), setOf(TOO_HOT, TOO_COLD),
            mapOf(TOO_HOT to 0L, TOO_COLD to 5000L), 3 * 3600L)
        assertEquals(setOf(TOO_HOT), r)
    }

    @Test fun quietHoursWrapMidnight() {
        val s = LocalTime.of(22, 0); val e = LocalTime.of(7, 0)
        assertTrue(AlertDecider.isQuiet(LocalTime.of(23, 30), s, e))
        assertTrue(AlertDecider.isQuiet(LocalTime.of(6, 59), s, e))
        assertFalse(AlertDecider.isQuiet(LocalTime.of(7, 0), s, e))
        assertFalse(AlertDecider.isQuiet(LocalTime.of(12, 0), s, e))
        assertTrue(AlertDecider.isQuiet(LocalTime.of(13, 0), LocalTime.of(12, 0), LocalTime.of(14, 0)))
        assertFalse(AlertDecider.isQuiet(LocalTime.of(13, 0), s, s))
    }
}

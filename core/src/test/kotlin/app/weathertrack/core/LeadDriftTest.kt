package app.weathertrack.core

import app.weathertrack.core.accuracy.ForecastCheck
import app.weathertrack.core.accuracy.IssuedHour
import app.weathertrack.core.accuracy.ObsHour
import app.weathertrack.core.comfort.Comfort
import app.weathertrack.core.comfort.Feeling
import app.weathertrack.core.jacket.JacketThresholds
import app.weathertrack.core.sources.Drift
import app.weathertrack.core.sources.ForecastChange
import app.weathertrack.core.sources.IssuedSourceHour
import app.weathertrack.core.sources.Lead
import app.weathertrack.core.sources.SourceHour
import app.weathertrack.core.sources.Sources
import app.weathertrack.core.sources.Variable
import app.weathertrack.core.walk.WalkIssueKind
import app.weathertrack.core.walk.WalkPlanner
import app.weathertrack.core.walk.WalkThresholds
import app.weathertrack.core.model.HourPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LeadDriftTest {
    private val h = 3600L
    private val zone = ZoneId.of("UTC")

    @Test
    fun leadGroups() {
        assertEquals(Lead.SOON, Lead.of(0))
        assertEquals(Lead.SOON, Lead.of(3))
        assertEquals(Lead.MORNING, Lead.of(4))
        assertEquals(Lead.HALF_DAY, Lead.of(12))
        assertEquals(Lead.DAY, Lead.of(24))
        assertEquals(Lead.NEXT_DAY, Lead.of(30))
        assertEquals(Lead.NEXT_DAY, Lead.of(99))
    }

    @Test
    fun leadWeightsFavourTheSourceThatIsRightAtThatLead() {
        // "a" is spot on 1-3 h out but 4° off a day out; "b" is the reverse.
        val obs = (0 until 200).map { ObsHour(1_000_000L + it * h, 15.0, 15.0, 0.0, 10.0) }
        val issued = obs.flatMap { o ->
            listOf(
                IssuedSourceHour("a", o.time - 2 * h, o.time, 15.0, 0.0, 5.0),
                IssuedSourceHour("b", o.time - 2 * h, o.time, 19.0, 0.0, 5.0),
                IssuedSourceHour("a", o.time - 20 * h, o.time, 19.0, 0.0, 5.0),
                IssuedSourceHour("b", o.time - 20 * h, o.time, 15.0, 0.0, 5.0),
            )
        }
        val pooled = Sources.weights(listOf("a", "b"), Sources.scores(issued, obs))
        val byLead = Sources.leadWeights(listOf("a", "b"), pooled, Sources.leadScores(issued, obs))
        val soonA = byLead.getValue(Lead.SOON).first { it.source == "a" }.temp
        val dayA = byLead.getValue(Lead.DAY).first { it.source == "a" }.temp
        assertTrue("a leads up close: $soonA", soonA > 0.8)
        assertTrue("b leads a day out: $dayA", dayA < 0.2)

        val now = 5_000_000L
        val latest = listOf(
            SourceHour("a", now + 2 * h, 10.0, 0.0, 5.0), SourceHour("b", now + 2 * h, 20.0, 0.0, 5.0),
            SourceHour("a", now + 20 * h, 10.0, 0.0, 5.0), SourceHour("b", now + 20 * h, 20.0, 0.0, 5.0),
        )
        val blend = Sources.blend(latest, pooled, now, byLead = byLead, now = now).associateBy { it.time }
        assertTrue(blend.getValue(now + 2 * h).temperature!! < 12)
        assertTrue(blend.getValue(now + 20 * h).temperature!! > 18)
    }

    @Test
    fun settledAtTheLeadFromWhichEveryRunWasRight() {
        // Each hour: runs 30 h..1 h ahead. Rain: wrong (dry) until 6 h out, right (wet) from then on.
        val hours = (0 until 10).map { 2_000_000L + it * 24 * h }
        val obs = hours.map { ObsHour(it, 15.0, 15.0, 1.0, 10.0, windSpeed = 10.0, cloudCover = 80.0) }
        val runs = hours.flatMap { t ->
            (1..30).map { lead -> IssuedSourceHour("a", t - lead * h, t, 15.0, if (lead <= 6) 1.0 else 0.0, null, 10.0, 80.0) }
        }
        val s = Drift.settled(runs, obs).associateBy { it.variable }
        assertEquals(6.0, s.getValue(Variable.RAIN).medianLeadH, 0.01)
        assertEquals(1.0, s.getValue(Variable.RAIN).by6, 0.01)
        assertEquals(0.0, s.getValue(Variable.RAIN).by12, 0.01)
        assertEquals(30.0, s.getValue(Variable.TEMP).medianLeadH, 0.01)
        assertEquals(1.0, s.getValue(Variable.TEMP).by24, 0.01)
    }

    @Test
    fun changesSinceTheMorningRun() {
        val now = 10 * 24 * h + 12 * h
        val morning = now - 5 * h
        val runs = (1..12).flatMap { k ->
            listOf(
                IssuedSourceHour("main", morning, now + k * h, 15.0, 0.0, 20.0),
                IssuedSourceHour("main", now - 600, now + k * h, if (k == 3) 20.0 else 15.0, 0.0, if (k in 3..4) 60.0 else 20.0),
            )
        }
        val c = Drift.changes(runs, now, since = morning + 60)
        assertEquals(2, c.size)
        val rain = c.first { it.kind == ForecastChange.Kind.RAIN }
        assertEquals(now + 3 * h, rain.time)
        assertEquals("Rain at 3 pm up from 20% to 60% since 7 am", rain.text(zone))
        assertTrue(c.any { it.kind == ForecastChange.Kind.TEMP })
        // Nothing to compare with: no run in force at the baseline.
        assertTrue(Drift.changes(runs, now, since = morning - 60).isEmpty())
    }

    @Test
    fun jacketByLead() {
        val day = LocalDate.of(2026, 3, 2)
        val start = day.atStartOfDay(zone).toEpochSecond()
        val obs = (0 until 24).map { ObsHour(start + it * h, 15.0, 15.0, if (it in 9..11) 2.0 else 0.0, 10.0) }
        // The 1 h run sees the 9 am rain; the 6 h run doesn't.
        fun run(at: Long, wet: Boolean) = (0 until 30).map {
            val t = at - at % h + it * h
            val hourOfDay = ((t - start) / h).toInt()
            IssuedHour(at, t, 15.0, 15.0, if (wet && hourOfDay in 9..11) 90.0 else 5.0, if (wet && hourOfDay in 9..11) 2.0 else 0.0, 10.0)
        }
        val issued = run(start + 8 * h, true) + run(start + 3 * h, false)
        val r = ForecastCheck.jacketByLead(issued, obs, zone, day, JacketThresholds(), leadsH = listOf(1, 6), startHours = listOf(9))
        assertEquals(1, r.getValue(1).right)
        assertEquals(1, r.getValue(6).wrong)
    }

    @Test
    fun comfortModelAndWarmWalks() {
        val now = 400 * 86400L
        fun f(kind: Feeling.Kind, feels: Double, daysAgo: Int = 1) = Feeling(now - daysAgo * 86400L, kind, feels, feels, null, null, null)
        val taps = listOf(f(Feeling.Kind.CHILLY, 10.0), f(Feeling.Kind.CHILLY, 12.0), f(Feeling.Kind.CHILLY, 13.0),
            f(Feeling.Kind.WARM, 23.0), f(Feeling.Kind.WARM, 24.0), f(Feeling.Kind.WARM, 26.0), f(Feeling.Kind.CHILLY, 2.0, daysAgo = 380))
        val m = Comfort.model(taps, now)
        assertEquals(12.0, m.chilly!!, 0.01)
        assertEquals(3, m.chillyTaps)
        assertEquals(24.0, m.warm!!, 0.01)
        // Overlapping points aren't trusted.
        assertNull(Comfort.model(taps.map { if (it.kind == Feeling.Kind.WARM) it.copy(feelsLike = 11.0) else it }, now).warm)

        val hot = HourPoint(0, 23.0, 24.5, 0.0, 0.0, 0, 5.0, 10.0, 200.0, 2.0, 80.0, 50.0, true, 80.0, 80.0, 0.0)
        assertTrue(WalkPlanner.rateHour(hot, WalkThresholds(petHotFeelsLike = 30.0, youWarmFeelsLike = 24.0)).issues.any { it.kind == WalkIssueKind.WARM })
        assertTrue(WalkPlanner.rateHour(hot, WalkThresholds(petHotFeelsLike = 30.0)).issues.none { it.kind == WalkIssueKind.WARM })
        assertNotNull(m)
    }
}

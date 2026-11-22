package app.weathertrack.core.alerts

import java.time.LocalTime

object AlertDecider {
    /**
     * Which of the [fired] triggers should notify now. A trigger notifies when it starts (it was
     * not active at the last check), or again once [renotifyAfterSeconds] have passed since its
     * last notification while it stays active.
     */
    fun toNotify(
        fired: Set<TriggerKind>,
        previouslyActive: Set<TriggerKind>,
        lastNotified: Map<TriggerKind, Long>,
        nowEpochSeconds: Long,
        renotifyAfterSeconds: Long = 3 * 3600L,
    ): Set<TriggerKind> = fired.filterTo(mutableSetOf()) { kind ->
        kind !in previouslyActive || nowEpochSeconds - (lastNotified[kind] ?: 0L) >= renotifyAfterSeconds
    }

    /** True when [time] falls in the quiet window, which may wrap past midnight (e.g. 22:00 to 07:00). */
    fun isQuiet(time: LocalTime, start: LocalTime, end: LocalTime): Boolean = when {
        start == end -> false
        start < end -> time >= start && time < end
        else -> time >= start || time < end
    }
}

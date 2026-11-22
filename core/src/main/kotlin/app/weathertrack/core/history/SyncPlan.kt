package app.weathertrack.core.history

import java.time.LocalDate

/** Works out which date ranges still need downloading, newest first, so recent months arrive first. */
object SyncPlan {
    /**
     * Every gap in [stored] between [first] and [end] (inclusive), split into chunks of at most
     * [chunkDays] days. The newest chunk comes first; any order of completion leaves no lasting hole,
     * because the next sync finds whatever is still missing.
     */
    fun chunks(stored: Set<LocalDate>, first: LocalDate, end: LocalDate, chunkDays: Int): List<Pair<LocalDate, LocalDate>> {
        val out = mutableListOf<Pair<LocalDate, LocalDate>>()
        var d = end
        var runEnd: LocalDate? = null
        var runStart: LocalDate? = null
        fun flush() {
            val e = runEnd ?: return
            val s = runStart!!
            var chunkEnd = e
            while (!chunkEnd.isBefore(s)) {
                val chunkStart = maxOf(s, chunkEnd.minusDays(chunkDays - 1L))
                out += chunkStart to chunkEnd
                chunkEnd = chunkStart.minusDays(1)
            }
            runEnd = null
            runStart = null
        }
        while (!d.isBefore(first)) {
            if (d in stored) flush() else {
                if (runEnd == null) runEnd = d
                runStart = d
            }
            d = d.minusDays(1)
        }
        flush()
        return out
    }
}

package app.weathertrack.core.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** One stored pull, enough to decide whether to keep it. */
data class PullRef(val id: Long, val placeId: Long?, val fetchedAtMs: Long)

/** How much detail to keep for forecasts older than [Storage.FULL_DETAIL_DAYS]. */
enum class KeepOlder(val key: String, val label: String, val bucketMs: Long?) {
    ALL("all", "Every forecast", null),
    HOURLY("hourly", "One per hour", 3_600_000L),
    THREE_HOURLY("3h", "One every 3 hours", 3 * 3_600_000L);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: HOURLY
    }
}

object Storage {
    /** Everything newer than this is kept exactly as fetched. */
    const val FULL_DETAIL_DAYS = 30L
    /** Raw responses older than this are stored gzipped (nothing is lost). */
    const val COMPRESS_AFTER_DAYS = 30L
    /** Failed responses (not HTTP 200) older than this are deleted; they hold no weather. */
    const val FAILED_KEEP_DAYS = 30L

    /**
     * Pulls to drop so that, before [cutoffMs], each place keeps only its first pull in each
     * [bucketMs] window. Pulls on or after the cutoff are always kept.
     */
    fun thin(pulls: List<PullRef>, cutoffMs: Long, bucketMs: Long): List<Long> =
        pulls.filter { it.fetchedAtMs < cutoffMs }
            .groupBy { it.placeId to Math.floorDiv(it.fetchedAtMs, bucketMs) }
            .values.flatMap { group -> group.sortedWith(compareBy({ it.fetchedAtMs }, { it.id })).drop(1).map { it.id } }

    fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }
}

package app.weathertrack.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object Format {
    private val hourFmt = DateTimeFormatter.ofPattern("h a", Locale.ENGLISH)
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH)

    fun zone(id: String?): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(ZoneId.systemDefault())

    fun hour(epochSeconds: Long, zone: ZoneId): String = hourFmt.format(Instant.ofEpochSecond(epochSeconds).atZone(zone))
    fun time(epochSeconds: Long, zone: ZoneId): String = timeFmt.format(Instant.ofEpochSecond(epochSeconds).atZone(zone))
    fun day(date: String): String = dayFmt.format(java.time.LocalDate.parse(date))

    fun temp(v: Double?): String = v?.let { "${it.roundToInt()}°" } ?: "–"
    fun pct(v: Double?): String = v?.let { "${it.roundToInt()}%" } ?: "–"
    fun kmh(v: Double?): String = v?.let { "${it.roundToInt()} km/h" } ?: "–"
    fun mm(v: Double?): String = v?.let { String.format(Locale.US, "%.1f mm", it) } ?: "–"
    fun uv(v: Double?): String = v?.let { String.format(Locale.US, "%.0f", it) } ?: "–"

    fun ago(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val mins = ((nowMs - epochMs) / 60_000).coerceAtLeast(0)
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 48 * 60 -> "${mins / 60} h ago"
            else -> "${mins / 1440} days ago"
        }
    }

    fun bytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", n / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024.0))
    }
}

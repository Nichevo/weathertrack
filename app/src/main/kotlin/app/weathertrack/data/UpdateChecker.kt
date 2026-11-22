package app.weathertrack.data

import android.content.Context
import app.weathertrack.BuildConfig
import app.weathertrack.core.about.LatestRelease
import app.weathertrack.core.about.NoteSection
import app.weathertrack.core.about.ReleaseNotes
import app.weathertrack.data.net.HttpFetcher

/**
 * What's new and Planned (from the CHANGELOG.md and ROADMAP.md bundled with the app), and whether
 * GitHub has a newer build than the one installed. The check runs at most every 6 hours.
 */
class UpdateChecker(private val context: Context, private val http: HttpFetcher) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    val versionName: String get() = BuildConfig.VERSION_NAME
    val build: Int get() = BuildConfig.VERSION_CODE

    fun changelog(): List<NoteSection> = asset("CHANGELOG.md")
    fun roadmap(): List<NoteSection> = asset("ROADMAP.md")

    private fun asset(name: String): List<NoteSection> =
        runCatching { context.assets.open(name).bufferedReader().use { ReleaseNotes.parse(it.readText()) } }.getOrDefault(emptyList())

    /** The latest release if it is newer than this build; null if not, or if GitHub can't be reached. */
    suspend fun newer(force: Boolean = false): LatestRelease? {
        val now = System.currentTimeMillis()
        val cached = prefs.getString("latest", null)
        val body = if (!force && cached != null && now - prefs.getLong("checked", 0) < CHECK_EVERY_MS) cached else {
            runCatching { http.get(LATEST_URL) }.getOrNull()?.takeIf { it.status == 200 }?.body?.also {
                prefs.edit().putString("latest", it).putLong("checked", now).apply()
            } ?: cached
        }
        val latest = body?.let { ReleaseNotes.parseLatest(it) } ?: return null
        return latest.takeIf { it.build > build }
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/Nichevo/weathertrack/releases/latest"
        private const val CHECK_EVERY_MS = 6 * 3600_000L
    }
}

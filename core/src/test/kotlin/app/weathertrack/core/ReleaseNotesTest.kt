package app.weathertrack.core

import app.weathertrack.core.about.ReleaseNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun parsesSectionsAndFindsTheInstalledOne() {
        val md = """
            # What's new
            Intro text is skipped.

            ## 1.0 · Clouds and wind
            - Cloud layers on Today
            - Wind in Forecast check,
              by lead time
            ## 0.1 · First builds
            - Everything before
        """.trimIndent()
        val s = ReleaseNotes.parse(md)
        assertEquals(2, s.size)
        assertEquals("1.0", s[0].version)
        assertEquals(listOf("Cloud layers on Today", "Wind in Forecast check, by lead time"), s[0].lines)
        assertEquals("1.0 · Clouds and wind", ReleaseNotes.current(s, "1.0.75")?.title)
        assertNull(ReleaseNotes.current(s, "2.0.80"))
    }

    @Test
    fun readsLatestRelease() {
        val body = """{"tag_name":"v1.0.81","name":"WeatherTrack 1.0.81","html_url":"https://example.com/r",
            "assets":[{"name":"weathertrack-1.0.81.apk","browser_download_url":"https://example.com/a.apk"}]}"""
        val r = ReleaseNotes.parseLatest(body)!!
        assertEquals(81, r.build)
        assertEquals("https://example.com/a.apk", r.apkUrl)
        assertEquals(59, ReleaseNotes.buildOf("v0.1.59"))
        assertNull(ReleaseNotes.parseLatest("{\"message\":\"Not Found\"}"))
    }
}

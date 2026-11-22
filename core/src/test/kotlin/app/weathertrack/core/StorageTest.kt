package app.weathertrack.core

import app.weathertrack.core.storage.KeepOlder
import app.weathertrack.core.storage.PullRef
import app.weathertrack.core.storage.Storage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageTest {
    @Test
    fun thinKeepsFirstPullPerHourPerPlaceBeforeCutoff() {
        val h = 3_600_000L
        val pulls = listOf(
            PullRef(1, 1, 0), PullRef(2, 1, 15 * 60_000), PullRef(3, 1, 45 * 60_000), // same hour: keep 1
            PullRef(4, 2, 10 * 60_000), // other place: kept
            PullRef(5, 1, h + 5), PullRef(6, 1, h + 10), // next hour: keep 5
            PullRef(7, 1, 10 * h), PullRef(8, 1, 10 * h + 60_000), // after cutoff: both kept
        )
        assertEquals(listOf(2L, 3L, 6L), Storage.thin(pulls, cutoffMs = 5 * h, bucketMs = KeepOlder.HOURLY.bucketMs!!).sorted())
        assertEquals(KeepOlder.HOURLY, KeepOlder.of(null))
        assertEquals(KeepOlder.ALL, KeepOlder.of("all"))
    }

    @Test
    fun gzipRoundTripsAndShrinksJson() {
        val json = (1..500).joinToString(",", "{\"hourly\":[", "]}") { "{\"t\":$it,\"temperature_2m\":15.2}" }
        val z = Storage.gzip(json)
        assertEquals(json, Storage.gunzip(z))
        assertTrue(z.size * 5 < json.length)
    }
}

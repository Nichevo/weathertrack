package app.weathertrack.data.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Calls to the Open-Meteo archive, one at a time (History and Home share it), waiting and retrying
 * when Open-Meteo says "too many requests" (HTTP 429). Archive calls are weighted by days and
 * variables, so a big download can pass the per-minute limit; it resets within a minute.
 */
class ArchiveClient(private val http: HttpFetcher) {
    private val lock = Mutex()

    class RateLimited(message: String) : IOException(message)

    /**
     * Returns the response (any status but 429). [onWait] gets the seconds left while waiting.
     * Throws [RateLimited] when the hourly or daily limit is hit, or the minute limit keeps hitting.
     */
    suspend fun get(url: String, onWait: (Int) -> Unit = {}): HttpResult = lock.withLock { fetch(url, onWait) }

    private suspend fun fetch(url: String, onWait: (Int) -> Unit): HttpResult {
        var attempt = 0
        while (true) {
            val result = http.get(url)
            if (result.status != 429) return result
            val body = result.body.lowercase()
            if ("hourly" in body || "daily" in body) {
                throw RateLimited("Open-Meteo's ${if ("hourly" in body) "hourly" else "daily"} download limit is used up. " +
                    "It carries on from where it stopped next time.")
            }
            attempt++
            if (attempt > MAX_RETRIES) throw RateLimited("Open-Meteo is busy. It carries on from where it stopped next time.")
            for (left in WAIT_SECONDS downTo 1) {
                onWait(left)
                delay(1000)
            }
            onWait(0)
        }
    }

    companion object {
        private const val WAIT_SECONDS = 65
        private const val MAX_RETRIES = 5
    }
}

package app.weathertrack.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class HttpResult(val url: String, val status: Int, val body: String, val fetchedAtMs: Long, val durationMs: Long)

class HttpFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun get(url: String): HttpResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val request = Request.Builder().url(url).header("User-Agent", "WeatherTrack-Android").build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val done = System.currentTimeMillis()
            HttpResult(url, response.code, body, done, done - started)
        }
    }
}

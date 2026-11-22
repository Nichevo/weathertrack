package app.weathertrack.backup

import android.content.Context
import android.util.Base64
import app.weathertrack.export.DataExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.util.concurrent.TimeUnit

data class BackupStatus(val connected: Boolean, val appKey: String, val lastAtMs: Long, val lastResult: String?)

/**
 * Backs the whole database up to the user's own Dropbox app (App folder access, PKCE, no secret).
 * The app key is typed into Settings and kept with the tokens in private app storage, never in the code.
 */
class DropboxBackup(private val context: Context, private val exporter: DataExporter) {
    private val prefs = context.getSharedPreferences("dropbox", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()

    fun status() = BackupStatus(
        connected = prefs.getString(REFRESH, null) != null,
        appKey = prefs.getString(KEY, "") ?: "",
        lastAtMs = prefs.getLong(LAST_AT, 0L),
        lastResult = prefs.getString(LAST_RESULT, null),
    )

    /** Step 1: the Dropbox page to open. Dropbox then shows a code to paste back into the app. */
    fun authUrl(appKey: String): String {
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val verifier = b64(bytes)
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        prefs.edit().putString(KEY, appKey.trim()).putString(VERIFIER, verifier).apply()
        return "https://www.dropbox.com/oauth2/authorize?client_id=${appKey.trim()}&response_type=code" +
            "&code_challenge=$challenge&code_challenge_method=S256&token_access_type=offline"
    }

    /** Step 2: swap the pasted code for a long-lived refresh token. */
    suspend fun finish(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val key = prefs.getString(KEY, null) ?: error("Enter the app key first")
            val verifier = prefs.getString(VERIFIER, null) ?: error("Tap Connect first")
            val body = FormBody.Builder()
                .add("code", code.trim()).add("grant_type", "authorization_code")
                .add("code_verifier", verifier).add("client_id", key).build()
            val o = post(TOKEN_URL, body, null)
            val refresh = o.str("refresh_token") ?: error("Dropbox gave no refresh token")
            prefs.edit().putString(REFRESH, refresh).remove(VERIFIER).apply()
        }
    }

    fun disconnect() {
        prefs.edit().remove(REFRESH).remove(VERIFIER).apply()
    }

    private fun accessToken(): String {
        val key = prefs.getString(KEY, null) ?: error("No app key")
        val refresh = prefs.getString(REFRESH, null) ?: error("Not connected to Dropbox")
        val body = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh).add("client_id", key).build()
        return post(TOKEN_URL, body, null).str("access_token") ?: error("Dropbox gave no access token")
    }

    /** Uploads a gzipped copy of the database and keeps the newest [KEEP] backups. */
    suspend fun backupNow(): Result<String> = withContext(Dispatchers.IO) {
        val result = runCatching {
            val token = accessToken()
            val file = File(context.cacheDir, "backup.db.gz")
            try {
                val size = exporter.snapshotGzip(file)
                val name = "weathertrack-${LocalDate.now()}.db.gz"
                upload(token, file, "/$name")
                prune(token)
                "Backed up $name (${size / 1024} KB)"
            } finally {
                file.delete()
            }
        }
        val edit = prefs.edit().putString(LAST_RESULT, result.getOrElse { "Failed: ${it.message}" })
        if (result.isSuccess) edit.putLong(LAST_AT, System.currentTimeMillis())
        edit.apply()
        result
    }

    private fun upload(token: String, file: File, path: String) {
        val octet = "application/octet-stream".toMediaType()
        val arg = buildJsonObject { put("path", path); put("mode", "overwrite"); put("mute", true) }.toString()
        if (file.length() <= SINGLE_MAX) {
            content("$CONTENT/files/upload", token, arg, file.readBytes().toRequestBody(octet))
            return
        }
        // Larger files go up in 8 MB pieces through an upload session.
        file.inputStream().use { input ->
            val buf = ByteArray(CHUNK)
            var offset = 0L
            var n = input.read(buf)
            val start = content("$CONTENT/files/upload_session/start", token, "{}", buf.copyOf(n).toRequestBody(octet))
            val session = start.str("session_id") ?: error("No upload session")
            offset += n
            while (true) {
                n = input.read(buf)
                if (n <= 0) break
                val cursor = """{"cursor":{"session_id":"$session","offset":$offset}}"""
                content("$CONTENT/files/upload_session/append_v2", token, cursor, buf.copyOf(n).toRequestBody(octet))
                offset += n
            }
            val finish = """{"cursor":{"session_id":"$session","offset":$offset},"commit":$arg}"""
            content("$CONTENT/files/upload_session/finish", token, finish, ByteArray(0).toRequestBody(octet))
        }
    }

    private fun prune(token: String) {
        val list = rpc("$API/files/list_folder", token, """{"path":""}""")
        val names = (list["entries"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.str("name") }
            .filter { it.startsWith("weathertrack-") && it.endsWith(".db.gz") }
            .sortedDescending()
        names.drop(KEEP).forEach { rpc("$API/files/delete_v2", token, buildJsonObject { put("path", "/$it") }.toString()) }
    }

    private fun rpc(url: String, token: String, body: String): JsonObject =
        post(url, body.toRequestBody("application/json".toMediaType()), token)

    private fun content(url: String, token: String, arg: String, body: RequestBody): JsonObject {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Dropbox-API-Arg", arg).post(body).build()
        return call(req)
    }

    private fun post(url: String, body: RequestBody, token: String?): JsonObject {
        val req = Request.Builder().url(url).post(body).apply { token?.let { header("Authorization", "Bearer $it") } }.build()
        return call(req)
    }

    private fun call(req: Request): JsonObject = client.newCall(req).execute().use { r ->
        val text = r.body?.string().orEmpty()
        if (!r.isSuccessful) error("Dropbox ${r.code}: ${text.take(200)}")
        if (text.isBlank() || text == "null") JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    companion object {
        private const val KEY = "app_key"
        private const val VERIFIER = "verifier"
        private const val REFRESH = "refresh_token"
        private const val LAST_AT = "last_at"
        private const val LAST_RESULT = "last_result"
        private const val TOKEN_URL = "https://api.dropboxapi.com/oauth2/token"
        private const val API = "https://api.dropboxapi.com/2"
        private const val CONTENT = "https://content.dropboxapi.com/2"
        private const val SINGLE_MAX = 8L * 1024 * 1024
        private const val CHUNK = 8 * 1024 * 1024
        const val KEEP = 8
    }
}

/** Weekly backup while it is on and Dropbox is connected. */
class BackupWorker(context: android.content.Context, params: androidx.work.WorkerParameters) :
    androidx.work.CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as app.weathertrack.WeatherApp).container
        if (!container.settings.current.backupWeekly || !container.backup.status().connected) return Result.success()
        return if (container.backup.backupNow().isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "dropbox-backup"

        fun schedule(context: android.content.Context, on: Boolean) {
            val wm = androidx.work.WorkManager.getInstance(context)
            if (!on) { wm.cancelUniqueWork(NAME); return }
            val req = androidx.work.PeriodicWorkRequestBuilder<BackupWorker>(7, java.util.concurrent.TimeUnit.DAYS)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true).build(),
                ).build()
            wm.enqueueUniquePeriodicWork(NAME, androidx.work.ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

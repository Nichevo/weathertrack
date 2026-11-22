package app.weathertrack.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.weathertrack.data.db.AppDatabase
import java.io.File
import java.util.zip.GZIPInputStream

data class BackupVerdict(val ok: Boolean, val version: Int, val counts: Map<String, Long>, val message: String)

/** Opens a gzipped database copy read-only and checks it would restore. */
object BackupCheck {
    /** Tables whose row counts are reported and compared. */
    private val TABLES = listOf("raw_pull", "forecast_hour", "observed_day", "observed_hour", "user_report", "feeling_report", "place")

    fun verify(context: Context, gz: File): BackupVerdict {
        val db = File(context.cacheDir, "restore-check.db")
        try {
            GZIPInputStream(gz.inputStream().buffered()).use { input -> db.outputStream().use { input.copyTo(it) } }
            val sql = SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY)
            sql.use { s ->
                val integrity = s.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else "?" }
                val version = s.rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                val counts = TABLES.associateWith { t ->
                    runCatching { s.rawQuery("SELECT COUNT(*) FROM `$t`", null).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } }.getOrDefault(-1L)
                }
                val problems = buildList {
                    if (integrity != "ok") add("integrity check said \"$integrity\"")
                    if (version < 1) add("no schema version")
                    if (version > AppDatabase.VERSION) add("made by a newer app (version $version)")
                    if ((counts["place"] ?: 0) <= 0) add("no places in it")
                }
                val summary = "schema v$version, ${counts["forecast_hour"] ?: 0} forecast hours, ${counts["raw_pull"] ?: 0} raw responses, " +
                    "${counts["observed_day"] ?: 0} history days"
                return BackupVerdict(problems.isEmpty(), version, counts, if (problems.isEmpty()) summary else problems.joinToString("; "))
            }
        } catch (e: Exception) {
            return BackupVerdict(false, 0, emptyMap(), e.message ?: "couldn't open it")
        } finally {
            db.delete()
            File(db.path + "-journal").delete()
        }
    }
}

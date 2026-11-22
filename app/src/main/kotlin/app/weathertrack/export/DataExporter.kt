package app.weathertrack.export

import android.content.Context
import android.database.Cursor
import android.net.Uri
import app.weathertrack.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStreamWriter

/** Exports stored data as CSV (one table per file) or as a copy of the whole SQLite database. */
class DataExporter(private val context: Context, private val db: AppDatabase) {

    enum class Table(val sqlName: String, val label: String) {
        FORECAST_HOUR("forecast_hour", "Hourly forecasts as issued"),
        FORECAST_DAY("forecast_day", "Daily forecasts as issued"),
        OBSERVED_DAY("observed_day", "Observed daily history"),
        CLIMATE_DAY("climate_day", "Daily climate for the Home view"),
        ENERGY_DAY("energy_day", "Imported power use"),
        OBSERVED_HOUR("observed_hour", "Actual hours (for Forecast check)"),
        PREVIOUS_RUN_HOUR("previous_run_hour", "Earlier model runs (for Forecast check)"),
        USER_REPORT("user_report", "Your raining / dry reports"),
        SOURCE_HOUR("source_hour", "Every forecast source, hour by hour as issued"),
        SOURCE_DAY("source_day", "Each forecast source's record, day by day"),
        FEELING_REPORT("feeling_report", "Your chilly, too warm and pollen reports, with the weather then"),
        RAW_PULL("raw_pull", "Raw API responses (JSON)"),
        PLACE("place", "Places"),
    }

    /** Writes [table] as CSV to [uri]. Returns the number of data rows written. */
    suspend fun exportCsv(table: Table, uri: Uri): Int = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Can't open file for writing")
        var rows = 0
        BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
            db.openHelper.readableDatabase.query("SELECT * FROM ${table.sqlName}").use { c ->
                w.write(c.columnNames.joinToString(",") { csv(it) })
                w.newLine()
                while (c.moveToNext()) {
                    for (i in 0 until c.columnCount) {
                        if (i > 0) w.write(",")
                        w.write(cell(c, i))
                    }
                    w.newLine()
                    rows++
                }
            }
        }
        rows
    }

    /** Copies the whole database file to [uri], after flushing the write-ahead log. */
    suspend fun exportSqlite(uri: Uri): Long = withContext(Dispatchers.IO) {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val file = context.getDatabasePath(AppDatabase.NAME)
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Can't open file for writing")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** Writes a gzipped copy of the whole database to [out] (for backups). Returns its size in bytes. */
    suspend fun snapshotGzip(out: java.io.File): Long = withContext(Dispatchers.IO) {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val file = context.getDatabasePath(AppDatabase.NAME)
        java.util.zip.GZIPOutputStream(out.outputStream().buffered()).use { z -> file.inputStream().use { it.copyTo(z) } }
        out.length()
    }

    private fun cell(c: Cursor, i: Int): String = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> ""
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i).toString()
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).toString()
        Cursor.FIELD_TYPE_BLOB -> ""
        else -> csv(c.getString(i))
    }

    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

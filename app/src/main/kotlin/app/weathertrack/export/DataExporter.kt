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

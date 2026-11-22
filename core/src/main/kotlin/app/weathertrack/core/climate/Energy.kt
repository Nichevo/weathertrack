package app.weathertrack.core.climate

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import kotlin.math.abs

/** Result of reading a power-usage CSV. */
data class EnergyImport(
    /** kWh per day. Rows for the same day (for example half-hourly readings) are added up. */
    val days: Map<LocalDate, Double>,
    val rowsRead: Int,
    val rowsSkipped: Int,
    val dateColumn: String,
    val valueColumn: String,
)

/**
 * How power use follows the weather: kWh per day = base + perHdd × HDD + perCdd × CDD,
 * fitted by least squares over days that have both usage and weather.
 */
data class EnergyFit(
    val days: Int,
    val baseKwhPerDay: Double,
    val kwhPerHdd: Double?,
    val kwhPerCdd: Double?,
    /** Share of the day-to-day variation in usage the weather explains, 0 to 1. */
    val r2: Double,
)

data class EnergyMonth(
    val month: YearMonth,
    val days: Int,
    val kwh: Double,
    val hdd: Double,
    val cdd: Double,
) {
    val kwhPerDay: Double get() = kwh / days
}

object EnergyCsv {
    private val formats: List<DateTimeFormatter> = listOf(
        "yyyy-MM-dd", "yyyy/MM/dd", "d/M/yyyy", "d/M/yy", "d-M-yyyy", "d.M.yyyy", "d MMM yyyy", "d-MMM-yyyy", "d-MMM-yy",
    ).map { DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH) }

    private val valueNames = listOf("kwh", "usage", "consumption", "energy", "units")
    private val number = Regex("""-?\d+(?:\.\d+)?""")

    /**
     * Dates may be ISO (2025-06-30) or day first, as NZ retailers write them (30/06/2025).
     * A time after the date is ignored. Returns null when no date column or number column is found.
     */
    fun parse(text: String): EnergyImport? {
        val lines = text.lineSequence().map { it.trim().removePrefix("﻿") }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null
        val delimiter = listOf(',', ';', '\t').maxBy { d -> lines.first().count { it == d } }
        val rows = lines.map { split(it, delimiter) }
        val width = rows.maxOf { it.size }
        val hasHeader = rows.first().none { parseDate(it) != null }
        val header = if (hasHeader) rows.first() else List(width) { "column ${it + 1}" }
        val data = if (hasHeader) rows.drop(1) else rows
        if (data.isEmpty()) return null
        val sample = data.take(50)

        fun share(col: Int, test: (String) -> Boolean) = sample.count { r -> r.getOrNull(col)?.let(test) == true }.toDouble() / sample.size
        val dateCol = (0 until width).maxBy { share(it) { c -> parseDate(c) != null } }
        if (share(dateCol) { parseDate(it) != null } < 0.5) return null
        val numericCols = (0 until width).filter { it != dateCol && share(it) { c -> parseNumber(c) != null } >= 0.5 }
        val valueCol = numericCols.firstOrNull { c -> valueNames.any { header.getOrNull(c)?.lowercase()?.contains(it) == true } }
            ?: numericCols.lastOrNull() ?: return null

        val days = sortedMapOf<LocalDate, Double>()
        var skipped = 0
        for (r in data) {
            val date = r.getOrNull(dateCol)?.let(::parseDate)
            val v = r.getOrNull(valueCol)?.let(::parseNumber)
            if (date == null || v == null) { skipped++; continue }
            days[date] = (days[date] ?: 0.0) + v
        }
        if (days.isEmpty()) return null
        return EnergyImport(days, data.size - skipped, skipped, header.getOrElse(dateCol) { "date" }, header.getOrElse(valueCol) { "value" })
    }

    fun parseDate(cell: String): LocalDate? {
        val s = cell.trim().trim('"').trim()
        if (s.isEmpty() || s.length > 40) return null
        val candidates = listOf(s, s.substringBefore('T'), s.substringBefore(' '), s.split(' ').take(3).joinToString(" "))
        for (c in candidates.distinct()) for (f in formats) {
            runCatching { return LocalDate.parse(c, f) }
        }
        return null
    }

    fun parseNumber(cell: String): Double? {
        val s = cell.trim().trim('"').trim()
        if (s.isEmpty() || parseDate(s) != null) return null
        val m = number.find(s.replace(",", "")) ?: return null
        // Only accept cells that are essentially a number, maybe with a unit such as "kWh".
        if (s.length - m.value.length > 6) return null
        return m.value.toDoubleOrNull()
    }

    private fun split(line: String, d: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == d && !quoted -> { out += cur.toString().trim(); cur.clear() }
                else -> cur.append(ch)
            }
            i++
        }
        out += cur.toString().trim()
        return out
    }
}

object EnergyAnalysis {
    private const val MIN_DAYS = 14
    private const val MIN_ACTIVE_DAYS = 5

    /** Fits usage against heating and cooling degree days. Null with fewer than 14 matching days. */
    fun fit(climate: List<ClimateDay>, kwh: Map<LocalDate, Double>, bases: DegreeDayBases): EnergyFit? {
        val pairs = climate.mapNotNull { d ->
            val mean = d.tempMean ?: return@mapNotNull null
            val k = kwh[d.date] ?: return@mapNotNull null
            Triple(HomeClimate.hdd(mean, bases), HomeClimate.cdd(mean, bases), k)
        }
        if (pairs.size < MIN_DAYS) return null
        val useH = pairs.count { it.first > 0 } >= MIN_ACTIVE_DAYS
        val useC = pairs.count { it.second > 0 } >= MIN_ACTIVE_DAYS
        val x = pairs.map { (h, c, _) -> listOfNotNull(1.0, h.takeIf { useH }, c.takeIf { useC }).toDoubleArray() }
        val y = pairs.map { it.third }
        val beta = leastSquares(x, y) ?: return null
        val predicted = x.map { row -> row.indices.sumOf { row[it] * beta[it] } }
        val meanY = y.average()
        val ssTot = y.sumOf { (it - meanY) * (it - meanY) }
        val ssRes = y.indices.sumOf { (y[it] - predicted[it]).let { r -> r * r } }
        var i = 1
        return EnergyFit(
            days = pairs.size,
            baseKwhPerDay = beta[0],
            kwhPerHdd = if (useH) beta[i++] else null,
            kwhPerCdd = if (useC) beta[i] else null,
            r2 = if (ssTot > 0) (1 - ssRes / ssTot).coerceIn(0.0, 1.0) else 0.0,
        )
    }

    /** Usage and degree days per calendar month, newest first. Only days with both are counted. */
    fun byMonth(climate: List<ClimateDay>, kwh: Map<LocalDate, Double>, bases: DegreeDayBases): List<EnergyMonth> =
        climate.filter { it.tempMean != null && kwh.containsKey(it.date) }
            .groupBy { YearMonth.from(it.date) }
            .toSortedMap(compareByDescending { it })
            .map { (m, days) ->
                EnergyMonth(
                    month = m, days = days.size,
                    kwh = days.sumOf { kwh.getValue(it.date) },
                    hdd = days.sumOf { HomeClimate.hdd(it.tempMean!!, bases) },
                    cdd = days.sumOf { HomeClimate.cdd(it.tempMean!!, bases) },
                )
            }

    /** Solves the normal equations (XᵀX)β = Xᵀy by Gaussian elimination. Null if singular. */
    private fun leastSquares(x: List<DoubleArray>, y: List<Double>): DoubleArray? {
        val k = x.first().size
        val a = Array(k) { r -> DoubleArray(k + 1) }
        for (n in x.indices) for (r in 0 until k) {
            for (c in 0 until k) a[r][c] += x[n][r] * x[n][c]
            a[r][k] += x[n][r] * y[n]
        }
        for (col in 0 until k) {
            val pivot = (col until k).maxBy { abs(a[it][col]) }
            if (abs(a[pivot][col]) < 1e-9) return null
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            for (r in 0 until k) if (r != col) {
                val f = a[r][col] / a[col][col]
                for (c in col..k) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(k) { a[it][k] / a[it][it] }
    }
}

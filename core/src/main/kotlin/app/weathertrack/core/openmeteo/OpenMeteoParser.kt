package app.weathertrack.core.openmeteo

import app.weathertrack.core.climate.ClimateDay
import app.weathertrack.core.model.CurrentConditions
import app.weathertrack.core.model.DayPoint
import app.weathertrack.core.model.Forecast
import app.weathertrack.core.model.GeoPlace
import app.weathertrack.core.model.HourPoint
import app.weathertrack.core.model.QuarterHourPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull

/** Parses raw Open-Meteo JSON (requested with timeformat=unixtime) into model objects. */
object OpenMeteoParser {
    private val json = Json { ignoreUnknownKeys = true }

    class ParseException(message: String) : Exception(message)

    fun parseForecast(body: String): Forecast {
        val root = json.parseToJsonElement(body).jsonObject
        root["error"]?.let { if ((it as? JsonPrimitive)?.contentOrNull == "true") throw ParseException(root.str("reason") ?: "API error") }

        val hourly = root.obj("hourly")?.let { h ->
            val times = h.longs("time")
            val t = h.doubles("temperature_2m")
            val at = h.doubles("apparent_temperature")
            val pp = h.doubles("precipitation_probability")
            val p = h.doubles("precipitation")
            val wc = h.doubles("weather_code")
            val ws = h.doubles("wind_speed_10m")
            val wg = h.doubles("wind_gusts_10m")
            val wd = h.doubles("wind_direction_10m")
            val uv = h.doubles("uv_index")
            val cc = h.doubles("cloud_cover")
            val rh = h.doubles("relative_humidity_2m")
            val day = h.doubles("is_day")
            val ccl = h.doubles("cloud_cover_low")
            val ccm = h.doubles("cloud_cover_mid")
            val cch = h.doubles("cloud_cover_high")
            val sw = h.doubles("shortwave_radiation")
            val dp = h.doubles("dew_point_2m")
            times.mapIndexedNotNull { i, time ->
                time ?: return@mapIndexedNotNull null
                HourPoint(
                    time = time,
                    temperature = t.at(i),
                    apparentTemperature = at.at(i),
                    precipitationProbability = pp.at(i),
                    precipitation = p.at(i),
                    weatherCode = wc.at(i)?.toInt(),
                    windSpeed = ws.at(i),
                    windGusts = wg.at(i),
                    windDirection = wd.at(i),
                    uvIndex = uv.at(i),
                    cloudCover = cc.at(i),
                    humidity = rh.at(i),
                    isDay = day.at(i)?.let { it >= 0.5 },
                    cloudCoverLow = ccl.at(i),
                    cloudCoverMid = ccm.at(i),
                    cloudCoverHigh = cch.at(i),
                    shortwaveRadiation = sw.at(i),
                    dewPoint = dp.at(i),
                )
            }
        } ?: emptyList()

        val quarter = root.obj("minutely_15")?.let { m ->
            val times = m.longs("time")
            val p = m.doubles("precipitation")
            times.mapIndexedNotNull { i, time -> time?.let { QuarterHourPoint(it, p.at(i)) } }
        } ?: emptyList()

        val daily = root.obj("daily")?.let { d ->
            val times = d.longs("time")
            val wc = d.doubles("weather_code")
            val tmax = d.doubles("temperature_2m_max")
            val tmin = d.doubles("temperature_2m_min")
            val ps = d.doubles("precipitation_sum")
            val ppm = d.doubles("precipitation_probability_max")
            val gm = d.doubles("wind_gusts_10m_max")
            val uvm = d.doubles("uv_index_max")
            val sr = d.longs("sunrise")
            val ss = d.longs("sunset")
            val et = d.doubles("et0_fao_evapotranspiration")
            val offset = root.num("utc_offset_seconds")?.toLong() ?: 0L
            times.mapIndexedNotNull { i, time ->
                time ?: return@mapIndexedNotNull null
                DayPoint(
                    date = java.time.Instant.ofEpochSecond(time + offset)
                        .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString(),
                    time = time,
                    weatherCode = wc.at(i)?.toInt(),
                    temperatureMax = tmax.at(i),
                    temperatureMin = tmin.at(i),
                    precipitationSum = ps.at(i),
                    precipitationProbabilityMax = ppm.at(i),
                    windGustsMax = gm.at(i),
                    uvIndexMax = uvm.at(i),
                    sunrise = sr.getOrNull(i),
                    sunset = ss.getOrNull(i),
                    et0 = et.at(i),
                )
            }
        } ?: emptyList()

        val current = root.obj("current")?.let { c ->
            val time = c.num("time")?.toLong() ?: return@let null
            CurrentConditions(
                time = time,
                temperature = c.num("temperature_2m"),
                apparentTemperature = c.num("apparent_temperature"),
                precipitation = c.num("precipitation"),
                weatherCode = c.num("weather_code")?.toInt(),
                windSpeed = c.num("wind_speed_10m"),
                windGusts = c.num("wind_gusts_10m"),
                isDay = c.num("is_day")?.let { it >= 0.5 },
                windDirection = c.num("wind_direction_10m"),
            )
        }

        return Forecast(
            latitude = root.num("latitude") ?: throw ParseException("Missing latitude"),
            longitude = root.num("longitude") ?: throw ParseException("Missing longitude"),
            timezone = root.str("timezone") ?: "UTC",
            utcOffsetSeconds = root.num("utc_offset_seconds")?.toInt() ?: 0,
            current = current,
            hourly = hourly,
            quarterHourly = quarter,
            daily = daily,
        )
    }

    /** One archive day, values as returned. [date] is ISO yyyy-MM-dd. */
    data class ArchiveDay(
        val date: String,
        val tempMax: Double?,
        val tempMin: Double?,
        val tempMean: Double?,
        val feelsMax: Double?,
        val feelsMin: Double?,
        val precipitation: Double?,
        val rain: Double?,
        val precipitationHours: Double?,
        val gustMax: Double?,
        val sunshineSeconds: Double?,
        val weatherCode: Int?,
    ) {
        val hasData: Boolean get() = tempMax != null || precipitation != null
    }

    /** Parses an archive response requested with ISO dates (no timeformat=unixtime). */
    fun parseArchiveDaily(body: String): List<ArchiveDay> {
        val root = json.parseToJsonElement(body).jsonObject
        if ((root["error"] as? JsonPrimitive)?.contentOrNull == "true") throw ParseException(root.str("reason") ?: "API error")
        val d = root.obj("daily") ?: return emptyList()
        val times = (d["time"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull } ?: return emptyList()
        val tmax = d.doubles("temperature_2m_max")
        val tmin = d.doubles("temperature_2m_min")
        val tmean = d.doubles("temperature_2m_mean")
        val fmax = d.doubles("apparent_temperature_max")
        val fmin = d.doubles("apparent_temperature_min")
        val p = d.doubles("precipitation_sum")
        val r = d.doubles("rain_sum")
        val ph = d.doubles("precipitation_hours")
        val g = d.doubles("wind_gusts_10m_max")
        val sun = d.doubles("sunshine_duration")
        val wc = d.doubles("weather_code")
        return times.mapIndexedNotNull { i, date ->
            date ?: return@mapIndexedNotNull null
            ArchiveDay(date, tmax.at(i), tmin.at(i), tmean.at(i), fmax.at(i), fmin.at(i), p.at(i), r.at(i),
                ph.at(i), g.at(i), sun.at(i), wc.at(i)?.toInt())
        }
    }

    /** Parses an archive response requested with [OpenMeteoUrls.archiveClimate]. Missing variables are null. */
    fun parseClimateDaily(body: String): List<ClimateDay> {
        val root = json.parseToJsonElement(body).jsonObject
        if ((root["error"] as? JsonPrimitive)?.contentOrNull == "true") throw ParseException(root.str("reason") ?: "API error")
        val d = root.obj("daily") ?: return emptyList()
        val times = (d["time"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull } ?: return emptyList()
        val mean = d.doubles("temperature_2m_mean")
        val tmax = d.doubles("temperature_2m_max")
        val tmin = d.doubles("temperature_2m_min")
        val rh = d.doubles("relative_humidity_2m_mean")
        val dew = d.doubles("dew_point_2m_mean")
        val solar = d.doubles("shortwave_radiation_sum")
        val sun = d.doubles("sunshine_duration")
        val cloud = d.doubles("cloud_cover_mean")
        return times.mapIndexedNotNull { i, date ->
            val day = date?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: return@mapIndexedNotNull null
            ClimateDay(day, mean.at(i), tmax.at(i), tmin.at(i), rh.at(i), dew.at(i), solar.at(i), sun.at(i), cloud.at(i))
        }
    }

    /** Parses a [OpenMeteoUrls.rainGrid] response into a grid with the given [bounds]. */
    fun parseRainGrid(body: String, bounds: app.weathertrack.core.map.RainGrid.Bounds): app.weathertrack.core.map.RainGrid {
        val root = json.parseToJsonElement(body)
        val items = when (root) {
            is JsonArray -> root.map { it.jsonObject }
            is JsonObject -> {
                if ((root["error"] as? JsonPrimitive)?.contentOrNull == "true") throw ParseException(root.str("reason") ?: "API error")
                listOf(root)
            }
            else -> throw ParseException("Unexpected rain grid response")
        }
        if (items.size != bounds.rows * bounds.cols) throw ParseException("Expected ${bounds.rows * bounds.cols} points, got ${items.size}")
        val first = items.first()
        val times = first.obj("minutely_15")?.longs("time")?.filterNotNull() ?: emptyList()
        val hourTimes = first.obj("hourly")?.longs("time")?.filterNotNull() ?: emptyList()
        return app.weathertrack.core.map.RainGrid(
            north = bounds.north, west = bounds.west, south = bounds.south, east = bounds.east,
            rows = bounds.rows, cols = bounds.cols,
            times = times,
            rainMm = items.map { it.obj("minutely_15")?.doubles("precipitation") ?: emptyList() },
            hourTimes = hourTimes,
            weatherCodes = items.map { o -> o.obj("hourly")?.doubles("weather_code")?.map { it?.toInt() } ?: emptyList() },
        )
    }

    /** Parses a [OpenMeteoUrls.previousRuns] response: one row per hour and lead. */
    fun parsePreviousRuns(body: String): List<app.weathertrack.core.accuracy.RunHour> {
        val root = json.parseToJsonElement(body).jsonObject
        if ((root["error"] as? JsonPrimitive)?.contentOrNull == "true") throw ParseException(root.str("reason") ?: "API error")
        val h = root.obj("hourly") ?: return emptyList()
        val times = h.longs("time")
        return OpenMeteoUrls.PREVIOUS_RUN_LEADS.flatMap { lead ->
            val suffix = if (lead == 0) "" else "_previous_day$lead"
            val t = h.doubles("temperature_2m$suffix")
            val p = h.doubles("precipitation$suffix")
            times.mapIndexedNotNull { i, time ->
                time ?: return@mapIndexedNotNull null
                if (t.at(i) == null && p.at(i) == null) return@mapIndexedNotNull null
                app.weathertrack.core.accuracy.RunHour(time, lead, t.at(i), p.at(i))
            }
        }
    }

    /** Parses a [OpenMeteoUrls.tides] response; null when the spot has no sea level data. */
    fun parseTides(body: String): app.weathertrack.core.tides.TideSeries? {
        val root = json.parseToJsonElement(body).jsonObject
        if ((root["error"] as? JsonPrimitive)?.contentOrNull == "true") throw ParseException(root.str("reason") ?: "API error")
        val h = root.obj("hourly") ?: return null
        val times = h.longs("time")
        val heights = h.doubles("sea_level_height_msl")
        val points = times.mapIndexedNotNull { i, t ->
            val v = heights.at(i)
            if (t == null || v == null) null else app.weathertrack.core.tides.TidePoint(t, v)
        }
        if (points.size < 6) return null
        return app.weathertrack.core.tides.TideSeries(points, root.num("latitude") ?: 0.0, root.num("longitude") ?: 0.0)
    }

    /** Parses a [OpenMeteoUrls.airQuality] response. */
    fun parseAir(body: String): List<app.weathertrack.core.air.AirHour> {
        val root = json.parseToJsonElement(body).jsonObject
        if ((root["error"] as? JsonPrimitive)?.contentOrNull == "true") throw ParseException(root.str("reason") ?: "API error")
        val h = root.obj("hourly") ?: return emptyList()
        val times = h.longs("time")
        val eu = h.doubles("european_aqi"); val us = h.doubles("us_aqi")
        val pm25 = h.doubles("pm2_5"); val pm10 = h.doubles("pm10"); val o3 = h.doubles("ozone"); val dust = h.doubles("dust")
        val pollen = app.weathertrack.core.air.Air.POLLEN.associateWith { h.doubles("${it}_pollen") }
        return times.mapIndexedNotNull { i, t ->
            t ?: return@mapIndexedNotNull null
            app.weathertrack.core.air.AirHour(
                t, eu.at(i), us.at(i), pm25.at(i), pm10.at(i), o3.at(i), dust.at(i),
                pollen.mapNotNull { (k, v) -> v.at(i)?.let { k to it } }.toMap(),
            )
        }
    }

    /** One day's high and rain from each model, keyed by model name. */
    data class ModelDay(val date: String, val highs: Map<String, Double>, val rain: Map<String, Double>)

    /** Parses a [OpenMeteoUrls.modelSpread] response. */
    fun parseModelSpread(body: String, models: List<String>): List<ModelDay> {
        val root = json.parseToJsonElement(body).jsonObject
        if ((root["error"] as? JsonPrimitive)?.contentOrNull == "true") throw ParseException(root.str("reason") ?: "API error")
        val d = root.obj("daily") ?: return emptyList()
        val dates = (d["time"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull } ?: return emptyList()
        val highs = models.associateWith { d.doubles("temperature_2m_max_$it") }
        val rain = models.associateWith { d.doubles("precipitation_sum_$it") }
        return dates.mapIndexedNotNull { i, date ->
            date ?: return@mapIndexedNotNull null
            ModelDay(
                date,
                highs.mapNotNull { (m, v) -> v.at(i)?.let { m to it } }.toMap(),
                rain.mapNotNull { (m, v) -> v.at(i)?.let { m to it } }.toMap(),
            )
        }
    }

    fun parseGeocoding(body: String): List<GeoPlace> {
        val root = json.parseToJsonElement(body).jsonObject
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            GeoPlace(
                name = o.str("name") ?: return@mapNotNull null,
                latitude = o.num("latitude") ?: return@mapNotNull null,
                longitude = o.num("longitude") ?: return@mapNotNull null,
                country = o.str("country"),
                countryCode = o.str("country_code"),
                admin1 = o.str("admin1"),
                timezone = o.str("timezone"),
            )
        }
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.doubles(key: String): List<Double?> =
        (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull } ?: emptyList()
    private fun JsonObject.longs(key: String): List<Long?> =
        (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.let { p -> p.longOrNull ?: p.doubleOrNull?.toLong() } } ?: emptyList()
    private fun List<Double?>.at(i: Int): Double? = getOrNull(i)
}

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

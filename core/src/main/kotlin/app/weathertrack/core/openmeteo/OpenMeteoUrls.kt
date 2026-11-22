package app.weathertrack.core.openmeteo

import java.util.Locale

/** Builds Open-Meteo request URLs. All times are requested as unix seconds. */
object OpenMeteoUrls {
    const val FORECAST_BASE = "https://api.open-meteo.com/v1/forecast"
    const val ARCHIVE_BASE = "https://archive-api.open-meteo.com/v1/archive"
    const val GEOCODING_BASE = "https://geocoding-api.open-meteo.com/v1/search"

    val HOURLY_VARS = listOf(
        "temperature_2m", "apparent_temperature", "precipitation_probability", "precipitation",
        "weather_code", "wind_speed_10m", "wind_gusts_10m", "wind_direction_10m", "uv_index",
        "cloud_cover", "relative_humidity_2m", "is_day",
        "cloud_cover_low", "cloud_cover_mid", "cloud_cover_high",
    )
    val DAILY_VARS = listOf(
        "weather_code", "temperature_2m_max", "temperature_2m_min", "precipitation_sum",
        "precipitation_probability_max", "wind_gusts_10m_max", "uv_index_max", "sunrise", "sunset",
    )
    val CURRENT_VARS = listOf(
        "temperature_2m", "apparent_temperature", "precipitation", "weather_code",
        "wind_speed_10m", "wind_gusts_10m", "is_day",
    )

    private fun coord(v: Double) = String.format(Locale.US, "%.4f", v)

    fun forecast(latitude: Double, longitude: Double, forecastDays: Int = 7): String =
        FORECAST_BASE +
            "?latitude=${coord(latitude)}&longitude=${coord(longitude)}" +
            "&current=${CURRENT_VARS.joinToString(",")}" +
            "&hourly=${HOURLY_VARS.joinToString(",")}" +
            "&minutely_15=precipitation&forecast_minutely_15=32" +
            "&daily=${DAILY_VARS.joinToString(",")}" +
            "&forecast_days=$forecastDays&past_hours=1" +
            "&timezone=auto&timeformat=unixtime&wind_speed_unit=kmh"

    val ARCHIVE_DAILY_VARS = listOf(
        "temperature_2m_max", "temperature_2m_min", "temperature_2m_mean", "apparent_temperature_max",
        "apparent_temperature_min", "precipitation_sum", "rain_sum", "precipitation_hours",
        "wind_gusts_10m_max", "sunshine_duration", "weather_code",
    )

    /** Daily observed history (reanalysis) between two ISO dates, inclusive. Dates are ISO strings. */
    fun archiveDaily(latitude: Double, longitude: Double, startDate: String, endDate: String): String =
        ARCHIVE_BASE +
            "?latitude=${coord(latitude)}&longitude=${coord(longitude)}" +
            "&start_date=$startDate&end_date=$endDate" +
            "&daily=${ARCHIVE_DAILY_VARS.joinToString(",")}" +
            "&timezone=auto&wind_speed_unit=kmh"

    /** Daily variables for the Home view (heating, cooling, sun and humidity). */
    val CLIMATE_DAILY_VARS = listOf(
        "temperature_2m_mean", "temperature_2m_max", "temperature_2m_min",
        "relative_humidity_2m_mean", "dew_point_2m_mean", "shortwave_radiation_sum",
        "sunshine_duration", "cloud_cover_mean",
    )

    /** Fallback if the archive rejects the newer daily means (humidity, dew point, cloud). */
    val CLIMATE_SAFE_DAILY_VARS = listOf(
        "temperature_2m_mean", "temperature_2m_max", "temperature_2m_min", "shortwave_radiation_sum", "sunshine_duration",
    )

    fun archiveClimate(
        latitude: Double, longitude: Double, startDate: String, endDate: String,
        vars: List<String> = CLIMATE_DAILY_VARS,
    ): String =
        ARCHIVE_BASE +
            "?latitude=${coord(latitude)}&longitude=${coord(longitude)}" +
            "&start_date=$startDate&end_date=$endDate" +
            "&daily=${vars.joinToString(",")}" +
            "&timezone=auto"

    fun geocode(query: String, count: Int = 10): String =
        GEOCODING_BASE + "?name=" + java.net.URLEncoder.encode(query, "UTF-8") +
            "&count=$count&language=en&format=json"
}

package app.weathertrack.core.openmeteo

import java.util.Locale

/** Builds Open-Meteo request URLs. All times are requested as unix seconds. */
object OpenMeteoUrls {
    const val FORECAST_BASE = "https://api.open-meteo.com/v1/forecast"
    const val GEOCODING_BASE = "https://geocoding-api.open-meteo.com/v1/search"

    val HOURLY_VARS = listOf(
        "temperature_2m", "apparent_temperature", "precipitation_probability", "precipitation",
        "weather_code", "wind_speed_10m", "wind_gusts_10m", "wind_direction_10m", "uv_index",
        "cloud_cover", "relative_humidity_2m", "is_day",
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

    fun geocode(query: String, count: Int = 10): String =
        GEOCODING_BASE + "?name=" + java.net.URLEncoder.encode(query, "UTF-8") +
            "&count=$count&language=en&format=json"
}

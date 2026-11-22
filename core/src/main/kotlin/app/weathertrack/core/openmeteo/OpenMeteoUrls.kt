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

    /**
     * 15-minute rain and hourly weather codes for every point of a grid, in one request
     * (Open-Meteo answers multi-location requests with a JSON array).
     */
    fun rainGrid(lats: List<Double>, lons: List<Double>, hours: Int = 6): String =
        FORECAST_BASE +
            "?latitude=${lats.joinToString(",") { coord(it) }}&longitude=${lons.joinToString(",") { coord(it) }}" +
            "&minutely_15=precipitation&forecast_minutely_15=${hours * 4}&past_minutely_15=2" +
            "&hourly=weather_code&forecast_hours=$hours&past_hours=1" +
            "&timezone=GMT&timeformat=unixtime"

    const val PREVIOUS_RUNS_BASE = "https://previous-runs-api.open-meteo.com/v1/forecast"
    /** Leads (days) fetched from Previous Runs for Forecast check. 0 is the latest run. */
    val PREVIOUS_RUN_LEADS = listOf(0, 1, 2, 3, 5, 7)

    /** Past hours (Open-Meteo's best estimate of what happened) for the last [pastDays] days. */
    fun pastHours(latitude: Double, longitude: Double, pastDays: Int): String =
        FORECAST_BASE +
            "?latitude=${coord(latitude)}&longitude=${coord(longitude)}" +
            "&hourly=temperature_2m,apparent_temperature,precipitation,weather_code,wind_gusts_10m,relative_humidity_2m,cloud_cover" +
            "&past_days=$pastDays&forecast_days=1&timezone=auto&timeformat=unixtime&wind_speed_unit=kmh"

    /** What earlier model runs forecast for the last [pastDays] days, at each lead in [PREVIOUS_RUN_LEADS]. */
    fun previousRuns(latitude: Double, longitude: Double, pastDays: Int): String {
        fun v(name: String) = PREVIOUS_RUN_LEADS.joinToString(",") { if (it == 0) name else "${name}_previous_day$it" }
        return PREVIOUS_RUNS_BASE +
            "?latitude=${coord(latitude)}&longitude=${coord(longitude)}" +
            "&hourly=${v("temperature_2m")},${v("precipitation")}" +
            "&past_days=$pastDays&forecast_days=1&timezone=auto&timeformat=unixtime"
    }

    const val MARINE_BASE = "https://marine-api.open-meteo.com/v1/marine"

    /** Sea level with tides (metres above mean sea level) from the nearest sea grid cell, yesterday to 7 days ahead. */
    fun tides(latitude: Double, longitude: Double): String =
        MARINE_BASE + "?latitude=${coord(latitude)}&longitude=${coord(longitude)}" +
            "&hourly=sea_level_height_msl&cell_selection=sea&past_days=1&forecast_days=7" +
            "&timezone=GMT&timeformat=unixtime"

    /** Weather models compared for "model spread". */
    val SPREAD_MODELS = listOf("ecmwf_ifs025", "gfs_seamless", "icon_seamless", "gem_seamless", "jma_seamless", "ukmo_seamless")
    val SPREAD_MODELS_SAFE = listOf("ecmwf_ifs025", "gfs_seamless", "icon_seamless")

    /** Daily high and rain from several models, to see how much they disagree. */
    fun modelSpread(latitude: Double, longitude: Double, models: List<String> = SPREAD_MODELS): String =
        FORECAST_BASE + "?latitude=${coord(latitude)}&longitude=${coord(longitude)}" +
            "&daily=temperature_2m_max,precipitation_sum&models=${models.joinToString(",")}" +
            "&forecast_days=7&timezone=auto"

    fun geocode(query: String, count: Int = 10): String =
        GEOCODING_BASE + "?name=" + java.net.URLEncoder.encode(query, "UTF-8") +
            "&count=$count&language=en&format=json"
}

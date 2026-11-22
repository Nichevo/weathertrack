package app.weathertrack.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Every HTTP response from a weather API, stored exactly as received.
 * This is the source of truth: parsed tables can always be rebuilt from it.
 */
@Entity(
    tableName = "raw_pull",
    indices = [Index("place_id"), Index("fetched_at"), Index("source", "endpoint")],
)
data class RawPullEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** API provider, e.g. "open-meteo". */
    val source: String,
    /** Which API on that provider, e.g. "forecast", "geocoding". */
    val endpoint: String,
    @androidx.room.ColumnInfo(name = "place_id") val placeId: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val url: String,
    /** When the request completed, epoch milliseconds UTC. */
    @androidx.room.ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @androidx.room.ColumnInfo(name = "http_status") val httpStatus: Int,
    @androidx.room.ColumnInfo(name = "duration_ms") val durationMs: Long,
    /** What triggered the pull: "app", "background", "manual". */
    val trigger: String,
    val body: String,
)

/** One hourly forecast value exactly as issued in a given pull. Key: (pull, valid time). */
@Entity(
    tableName = "forecast_hour",
    primaryKeys = ["pull_id", "valid_time"],
    indices = [Index("place_id", "valid_time"), Index("issued_at")],
)
data class ForecastHourEntity(
    @androidx.room.ColumnInfo(name = "pull_id") val pullId: Long,
    @androidx.room.ColumnInfo(name = "place_id") val placeId: Long,
    /** When the forecast was fetched, epoch seconds UTC. */
    @androidx.room.ColumnInfo(name = "issued_at") val issuedAt: Long,
    /** Start of the forecast hour, epoch seconds UTC. */
    @androidx.room.ColumnInfo(name = "valid_time") val validTime: Long,
    /** Hours between issue and valid time (negative for past hours). */
    @androidx.room.ColumnInfo(name = "lead_hours") val leadHours: Int,
    val temperature: Double?,
    @androidx.room.ColumnInfo(name = "apparent_temperature") val apparentTemperature: Double?,
    @androidx.room.ColumnInfo(name = "precipitation_probability") val precipitationProbability: Double?,
    val precipitation: Double?,
    @androidx.room.ColumnInfo(name = "weather_code") val weatherCode: Int?,
    @androidx.room.ColumnInfo(name = "wind_speed") val windSpeed: Double?,
    @androidx.room.ColumnInfo(name = "wind_gusts") val windGusts: Double?,
    @androidx.room.ColumnInfo(name = "wind_direction") val windDirection: Double?,
    @androidx.room.ColumnInfo(name = "uv_index") val uvIndex: Double?,
    @androidx.room.ColumnInfo(name = "cloud_cover") val cloudCover: Double?,
    val humidity: Double?,
    @androidx.room.ColumnInfo(name = "cloud_cover_low") val cloudCoverLow: Double? = null,
    @androidx.room.ColumnInfo(name = "cloud_cover_mid") val cloudCoverMid: Double? = null,
    @androidx.room.ColumnInfo(name = "cloud_cover_high") val cloudCoverHigh: Double? = null,
)

/** One daily forecast value exactly as issued in a given pull. */
@Entity(
    tableName = "forecast_day",
    primaryKeys = ["pull_id", "date"],
    indices = [Index("place_id", "date")],
)
data class ForecastDayEntity(
    @androidx.room.ColumnInfo(name = "pull_id") val pullId: Long,
    @androidx.room.ColumnInfo(name = "place_id") val placeId: Long,
    @androidx.room.ColumnInfo(name = "issued_at") val issuedAt: Long,
    /** Local date at the place, yyyy-MM-dd. */
    val date: String,
    @androidx.room.ColumnInfo(name = "lead_days") val leadDays: Int,
    @androidx.room.ColumnInfo(name = "weather_code") val weatherCode: Int?,
    @androidx.room.ColumnInfo(name = "temperature_max") val temperatureMax: Double?,
    @androidx.room.ColumnInfo(name = "temperature_min") val temperatureMin: Double?,
    @androidx.room.ColumnInfo(name = "precipitation_sum") val precipitationSum: Double?,
    @androidx.room.ColumnInfo(name = "precipitation_probability_max") val precipitationProbabilityMax: Double?,
    @androidx.room.ColumnInfo(name = "wind_gusts_max") val windGustsMax: Double?,
    @androidx.room.ColumnInfo(name = "uv_index_max") val uvIndexMax: Double?,
)

@Entity(tableName = "place")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val region: String?,
    val country: String?,
    @androidx.room.ColumnInfo(name = "country_code") val countryCode: String?,
    val latitude: Double,
    val longitude: Double,
    val timezone: String?,
    /** True for the single "My location" row, which is updated from the device location. */
    @androidx.room.ColumnInfo(name = "is_device_location") val isDeviceLocation: Boolean = false,
    @androidx.room.ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

/** One observed day from the Open-Meteo archive (reanalysis), as received. Key: (place, date). */
@Entity(
    tableName = "observed_day",
    primaryKeys = ["place_id", "date"],
    indices = [Index("pull_id")],
)
data class ObservedDayEntity(
    @androidx.room.ColumnInfo(name = "place_id") val placeId: Long,
    /** Local date at the place, yyyy-MM-dd. */
    val date: String,
    @androidx.room.ColumnInfo(name = "pull_id") val pullId: Long,
    @androidx.room.ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @androidx.room.ColumnInfo(name = "temperature_max") val temperatureMax: Double?,
    @androidx.room.ColumnInfo(name = "temperature_min") val temperatureMin: Double?,
    @androidx.room.ColumnInfo(name = "temperature_mean") val temperatureMean: Double?,
    @androidx.room.ColumnInfo(name = "apparent_temperature_max") val apparentTemperatureMax: Double?,
    @androidx.room.ColumnInfo(name = "apparent_temperature_min") val apparentTemperatureMin: Double?,
    @androidx.room.ColumnInfo(name = "precipitation_sum") val precipitationSum: Double?,
    @androidx.room.ColumnInfo(name = "rain_sum") val rainSum: Double?,
    @androidx.room.ColumnInfo(name = "precipitation_hours") val precipitationHours: Double?,
    @androidx.room.ColumnInfo(name = "wind_gusts_max") val windGustsMax: Double?,
    @androidx.room.ColumnInfo(name = "sunshine_duration") val sunshineDuration: Double?,
    @androidx.room.ColumnInfo(name = "weather_code") val weatherCode: Int?,
)

/** Daily weather for the Home view (degree days, sun, humidity), as received. Key: (place, date). */
@Entity(
    tableName = "climate_day",
    primaryKeys = ["place_id", "date"],
    indices = [Index("pull_id")],
)
data class ClimateDayEntity(
    @androidx.room.ColumnInfo(name = "place_id") val placeId: Long,
    /** Local date at the place, yyyy-MM-dd. */
    val date: String,
    @androidx.room.ColumnInfo(name = "pull_id") val pullId: Long,
    @androidx.room.ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @androidx.room.ColumnInfo(name = "temperature_mean") val temperatureMean: Double?,
    @androidx.room.ColumnInfo(name = "temperature_max") val temperatureMax: Double?,
    @androidx.room.ColumnInfo(name = "temperature_min") val temperatureMin: Double?,
    @androidx.room.ColumnInfo(name = "relative_humidity_mean") val relativeHumidityMean: Double?,
    @androidx.room.ColumnInfo(name = "dew_point_mean") val dewPointMean: Double?,
    /** MJ/m² for the day. */
    @androidx.room.ColumnInfo(name = "shortwave_radiation_sum") val shortwaveRadiationSum: Double?,
    /** Seconds. */
    @androidx.room.ColumnInfo(name = "sunshine_duration") val sunshineDuration: Double?,
    @androidx.room.ColumnInfo(name = "cloud_cover_mean") val cloudCoverMean: Double?,
)

/** Household power use per day, imported from a CSV. Not tied to a place. */
@Entity(tableName = "energy_day")
data class EnergyDayEntity(
    @PrimaryKey val date: String,
    val kwh: Double,
    /** Name of the file it came from. */
    val source: String?,
    @androidx.room.ColumnInfo(name = "imported_at") val importedAt: Long,
)

/** What happened in an hour at a place (Open-Meteo's best estimate, refreshed for recent days). */
@Entity(
    tableName = "observed_hour",
    primaryKeys = ["place_id", "valid_time"],
    indices = [Index("pull_id")],
)
data class ObservedHourEntity(
    @androidx.room.ColumnInfo(name = "place_id") val placeId: Long,
    @androidx.room.ColumnInfo(name = "valid_time") val validTime: Long,
    @androidx.room.ColumnInfo(name = "pull_id") val pullId: Long,
    @androidx.room.ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    val temperature: Double?,
    @androidx.room.ColumnInfo(name = "apparent_temperature") val apparentTemperature: Double?,
    val precipitation: Double?,
    @androidx.room.ColumnInfo(name = "weather_code") val weatherCode: Int?,
    @androidx.room.ColumnInfo(name = "wind_gusts") val windGusts: Double?,
    val humidity: Double?,
    @androidx.room.ColumnInfo(name = "cloud_cover") val cloudCover: Double?,
    @androidx.room.ColumnInfo(name = "wind_speed") val windSpeed: Double? = null,
    @androidx.room.ColumnInfo(name = "wind_direction") val windDirection: Double? = null,
    @androidx.room.ColumnInfo(name = "cloud_cover_low") val cloudCoverLow: Double? = null,
    @androidx.room.ColumnInfo(name = "cloud_cover_mid") val cloudCoverMid: Double? = null,
    @androidx.room.ColumnInfo(name = "shortwave_radiation") val shortwaveRadiation: Double? = null,
)

/** An hour as forecast lead_days earlier, from Open-Meteo Previous Runs (backfills Forecast check). */
@Entity(
    tableName = "previous_run_hour",
    primaryKeys = ["place_id", "valid_time", "lead_days"],
    indices = [Index("pull_id")],
)
data class PreviousRunHourEntity(
    @androidx.room.ColumnInfo(name = "place_id") val placeId: Long,
    @androidx.room.ColumnInfo(name = "valid_time") val validTime: Long,
    @androidx.room.ColumnInfo(name = "lead_days") val leadDays: Int,
    @androidx.room.ColumnInfo(name = "pull_id") val pullId: Long,
    val temperature: Double?,
    val precipitation: Double?,
    @androidx.room.ColumnInfo(name = "wind_speed") val windSpeed: Double? = null,
    @androidx.room.ColumnInfo(name = "wind_gusts") val windGusts: Double? = null,
    @androidx.room.ColumnInfo(name = "wind_direction") val windDirection: Double? = null,
    @androidx.room.ColumnInfo(name = "cloud_cover") val cloudCover: Double? = null,
    @androidx.room.ColumnInfo(name = "cloud_cover_low") val cloudCoverLow: Double? = null,
    @androidx.room.ColumnInfo(name = "cloud_cover_mid") val cloudCoverMid: Double? = null,
)

/** "It's raining" / "It's dry" from the user, with what the forecast and radar said at that moment. */
@Entity(
    tableName = "user_report",
    indices = [Index("place_id", "reported_at")],
)
data class UserReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "place_id") val placeId: Long,
    /** Epoch seconds UTC. */
    @androidx.room.ColumnInfo(name = "reported_at") val reportedAt: Long,
    val latitude: Double,
    val longitude: Double,
    val raining: Boolean,
    /** 15-minute forecast rain (mm) for that moment, from the stored forecast. */
    @androidx.room.ColumnInfo(name = "forecast_mm") val forecastMm: Double?,
    /** Hourly rain chance (%) for that hour, from the stored forecast. */
    @androidx.room.ColumnInfo(name = "forecast_chance") val forecastChance: Double?,
    /** Share of radar pixels over the spot showing rain (0..1). */
    @androidx.room.ColumnInfo(name = "radar_here") val radarHere: Double?,
    /** Time of the radar frame used, epoch seconds. */
    @androidx.room.ColumnInfo(name = "radar_time") val radarTime: Long?,
)

/** One hour from one forecast source (a weather model, an ensemble or another provider), as issued. */
@Entity(
    tableName = "source_hour",
    indices = [Index("place_id", "time")],
)
data class SourceHourEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "place_id") val placeId: Long,
    /** Source key, e.g. "gfs_seamless", "ens_ecmwf_ifs025", "metno", "main". */
    val source: String,
    /** When the app fetched it, epoch seconds. */
    @androidx.room.ColumnInfo(name = "issued_at") val issuedAt: Long,
    /** End of the hour, epoch seconds UTC. */
    val time: Long,
    val temperature: Double?,
    val precipitation: Double?,
    /** 0..100. For single-run models, derived from the amount. */
    @androidx.room.ColumnInfo(name = "rain_chance") val rainChance: Double?,
    /** km/h. */
    @androidx.room.ColumnInfo(name = "wind_speed") val windSpeed: Double? = null,
    /** 0..100. */
    @androidx.room.ColumnInfo(name = "cloud_cover") val cloudCover: Double? = null,
)

/** One forecast source's record for one local day at a place, kept after the hourly rows are cleared. */
@Entity(
    tableName = "source_day",
    primaryKeys = ["place_id", "date", "source"],
)
data class SourceDayEntity(
    @androidx.room.ColumnInfo(name = "place_id") val placeId: Long,
    /** Local date at the place, yyyy-MM-dd. */
    val date: String,
    val source: String,
    /** Hours checked against what happened. */
    val hours: Int,
    /** Sum of |forecast − actual| temperature, and of forecast − actual, over [tempN] hours. */
    @androidx.room.ColumnInfo(name = "temp_abs_sum") val tempAbsSum: Double,
    @androidx.room.ColumnInfo(name = "temp_err_sum") val tempErrSum: Double,
    @androidx.room.ColumnInfo(name = "temp_n") val tempN: Int,
    /** Hours where rain or no rain was called right, out of [rainN]. */
    @androidx.room.ColumnInfo(name = "rain_right") val rainRight: Int,
    @androidx.room.ColumnInfo(name = "rain_n") val rainN: Int,
)

/** "Feeling chilly", "Too warm" or "Pollen bothering me", with the weather at that moment. */
@Entity(
    tableName = "feeling_report",
    indices = [Index("place_id", "reported_at")],
)
data class FeelingReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "place_id") val placeId: Long,
    @androidx.room.ColumnInfo(name = "reported_at") val reportedAt: Long,
    /** "chilly", "warm" or "pollen". */
    val kind: String,
    val temperature: Double?,
    @androidx.room.ColumnInfo(name = "feels_like") val feelsLike: Double?,
    @androidx.room.ColumnInfo(name = "wind_speed") val windSpeed: Double?,
    @androidx.room.ColumnInfo(name = "wind_gusts") val windGusts: Double?,
    @androidx.room.ColumnInfo(name = "wind_direction") val windDirection: Double?,
    val humidity: Double?,
    @androidx.room.ColumnInfo(name = "cloud_cover") val cloudCover: Double?,
)

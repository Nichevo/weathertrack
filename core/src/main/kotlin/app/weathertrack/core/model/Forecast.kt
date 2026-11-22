package app.weathertrack.core.model

/** One hourly forecast step. Times are epoch seconds (UTC). Any value may be missing. */
data class HourPoint(
    val time: Long,
    val temperature: Double?,
    val apparentTemperature: Double?,
    val precipitationProbability: Double?,
    val precipitation: Double?,
    val weatherCode: Int?,
    val windSpeed: Double?,
    val windGusts: Double?,
    val windDirection: Double?,
    val uvIndex: Double?,
    val cloudCover: Double?,
    val humidity: Double?,
    val isDay: Boolean?,
    val cloudCoverLow: Double? = null,
    val cloudCoverMid: Double? = null,
    val cloudCoverHigh: Double? = null,
    /** Sunlight on a flat surface, W/m² (mean over the preceding hour). */
    val shortwaveRadiation: Double? = null,
    val dewPoint: Double? = null,
)

/** One 15-minute step, used for the short-term rain nowcast. */
data class QuarterHourPoint(
    val time: Long,
    val precipitation: Double?,
)

/** One day. [date] is ISO yyyy-MM-dd in the place's local time zone. */
data class DayPoint(
    val date: String,
    val time: Long,
    val weatherCode: Int?,
    val temperatureMax: Double?,
    val temperatureMin: Double?,
    val precipitationSum: Double?,
    val precipitationProbabilityMax: Double?,
    val windGustsMax: Double?,
    val uvIndexMax: Double?,
    val sunrise: Long?,
    val sunset: Long?,
    /** Reference evapotranspiration (how much water a lawn loses), mm. */
    val et0: Double? = null,
)

data class CurrentConditions(
    val time: Long,
    val temperature: Double?,
    val apparentTemperature: Double?,
    val precipitation: Double?,
    val weatherCode: Int?,
    val windSpeed: Double?,
    val windGusts: Double?,
    val isDay: Boolean?,
)

data class Forecast(
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val utcOffsetSeconds: Int,
    val current: CurrentConditions?,
    val hourly: List<HourPoint>,
    val quarterHourly: List<QuarterHourPoint>,
    val daily: List<DayPoint>,
)

/** A place found by the geocoding search. */
data class GeoPlace(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String?,
    val countryCode: String?,
    val admin1: String?,
    val timezone: String?,
)

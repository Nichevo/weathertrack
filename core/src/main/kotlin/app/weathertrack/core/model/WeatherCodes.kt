package app.weathertrack.core.model

/** Plain-English labels for WMO weather interpretation codes used by Open-Meteo. */
object WeatherCodes {
    fun describe(code: Int?): String = when (code) {
        null -> "Unknown"
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51 -> "Light drizzle"
        53 -> "Drizzle"
        55 -> "Heavy drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Violent showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Code $code"
    }

    fun isWet(code: Int?): Boolean = code != null && code >= 51

    fun isThunder(code: Int?): Boolean = code != null && code >= 95
}

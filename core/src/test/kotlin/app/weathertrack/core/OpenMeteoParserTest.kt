package app.weathertrack.core

import app.weathertrack.core.openmeteo.OpenMeteoParser
import app.weathertrack.core.openmeteo.OpenMeteoUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoParserTest {
    private val sample = """
    {"latitude":-36.85,"longitude":174.75,"generationtime_ms":0.5,"utc_offset_seconds":46800,
     "timezone":"Pacific/Auckland","timezone_abbreviation":"NZDT","elevation":20.0,
     "current_units":{"time":"unixtime"},
     "current":{"time":1795200000,"interval":900,"temperature_2m":17.2,"apparent_temperature":15.9,
       "precipitation":0.0,"weather_code":2,"wind_speed_10m":14.0,"wind_gusts_10m":31.0,"is_day":1},
     "hourly":{"time":[1795200000,1795203600],"temperature_2m":[17.2,null],
       "apparent_temperature":[15.9,15.0],"precipitation_probability":[10,55],"precipitation":[0.0,0.6],
       "weather_code":[2,61],"wind_speed_10m":[14.0,18.0],"wind_gusts_10m":[31.0,40.0],
       "wind_direction_10m":[220,230],"uv_index":[3.5,2.1],"cloud_cover":[40,90],
       "relative_humidity_2m":[70,85],"is_day":[1,0]},
     "minutely_15":{"time":[1795200000,1795200900],"precipitation":[0.0,0.2]},
     "daily":{"time":[1795172400],"weather_code":[61],"temperature_2m_max":[19.0],"temperature_2m_min":[12.5],
       "precipitation_sum":[3.2],"precipitation_probability_max":[80],"wind_gusts_10m_max":[52.0],
       "uv_index_max":[6.1],"sunrise":[1795176000],"sunset":[1795227000]}}
    """.trimIndent()

    @Test fun parsesAllSections() {
        val f = OpenMeteoParser.parseForecast(sample)
        assertEquals("Pacific/Auckland", f.timezone)
        assertEquals(46800, f.utcOffsetSeconds)
        assertEquals(2, f.hourly.size)
        assertNull(f.hourly[1].temperature)
        assertEquals(55.0, f.hourly[1].precipitationProbability!!, 0.0)
        assertEquals(61, f.hourly[1].weatherCode)
        assertEquals(false, f.hourly[1].isDay)
        assertEquals(2, f.quarterHourly.size)
        assertEquals(1, f.daily.size)
        assertEquals("2026-11-21", f.daily[0].date)
        assertEquals(17.2, f.current!!.temperature!!, 0.0)
    }

    @Test fun parsesGeocoding() {
        val body = """{"results":[{"id":1,"name":"Wellington","latitude":-41.28,"longitude":174.77,
            "country":"New Zealand","country_code":"NZ","admin1":"Wellington","timezone":"Pacific/Auckland"}]}"""
        val r = OpenMeteoParser.parseGeocoding(body)
        assertEquals(1, r.size)
        assertEquals("NZ", r[0].countryCode)
        assertTrue(OpenMeteoParser.parseGeocoding("{}").isEmpty())
    }

    @Test fun forecastUrlUsesUnixTime() {
        val url = OpenMeteoUrls.forecast(-36.8485, 174.7633)
        assertTrue(url.contains("latitude=-36.8485"))
        assertTrue(url.contains("timeformat=unixtime"))
        assertTrue(url.contains("minutely_15=precipitation"))
    }
}

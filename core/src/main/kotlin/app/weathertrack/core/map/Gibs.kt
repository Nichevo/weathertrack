package app.weathertrack.core.map

import java.time.Instant

/**
 * NASA GIBS satellite tiles: Himawari-9 infrared ("clean longwave", band 13), coloured where cloud
 * tops are cold. Free, no key. A new image every 10 minutes, usually 40 to 60 minutes behind.
 */
object Gibs {
    const val LAYER = "Himawari_AHI_Band13_Clean_Infrared"
    /** GIBS serves this layer up to zoom 6 (about 2 km pixels); the app enlarges beyond that. */
    const val MAX_ZOOM = 6
    /** Seconds between images. */
    const val STEP = 600L

    /** Tile for the image at [time] (epoch seconds), or the latest one when null. */
    fun tileUrl(time: Long?, z: Int, x: Int, y: Int): String =
        "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/$LAYER/default/${time?.let { iso(it) } ?: "default"}" +
            "/GoogleMapsCompatible_Level$MAX_ZOOM/$z/$y/$x.png"

    fun iso(time: Long): String = Instant.ofEpochSecond(time).toString()

    /** Image times to try, newest first, starting from the 10 minutes containing [now]. */
    fun candidates(now: Long, count: Int = 18): List<Long> {
        val last = now / STEP * STEP
        return (0 until count).map { last - it * STEP }
    }

    /** The [count] image times ending at [latest], oldest first. */
    fun frames(latest: Long, count: Int = 12): List<Long> = (count - 1 downTo 0).map { latest - it * STEP }
}

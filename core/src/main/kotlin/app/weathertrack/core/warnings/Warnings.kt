package app.weathertrack.core.warnings

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.OffsetDateTime
import javax.xml.parsers.DocumentBuilderFactory

/** One official warning or watch (CAP 1.2), for example from MetService. */
data class Warning(
    val id: String,
    val event: String,
    val headline: String,
    val severity: String,
    val colour: String?,
    val onset: Long?,
    val expires: Long?,
    val areaDesc: String,
    val description: String,
    val instruction: String,
    /** Each polygon is a list of (lat, lon) points. */
    val polygons: List<List<Pair<Double, Double>>>,
    val link: String?,
) {
    fun covers(lat: Double, lon: Double): Boolean = polygons.any { Cap.inside(it, lat, lon) }
    fun active(now: Long): Boolean = (expires ?: Long.MAX_VALUE) > now
    /** Red and orange warnings, or anything marked Severe or Extreme. */
    val serious: Boolean get() = colour?.lowercase() in setOf("red", "orange") || severity in setOf("Severe", "Extreme")
}

/** Reads CAP feeds: an RSS or Atom index of links, each pointing to one CAP alert. */
object Cap {
    data class FeedItem(val title: String, val link: String, val id: String)

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(InputSource(StringReader(xml)))

    private fun Element.children(name: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && (n.localName ?: n.nodeName) == name) out += n
        }
        return out
    }

    private fun Element.text(name: String): String? = children(name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun Element.all(name: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** RSS 2.0 items or Atom entries. */
    fun feed(xml: String): List<FeedItem> {
        val root = parse(xml).documentElement
        val rss = root.all("item").mapNotNull { e ->
            val link = e.text("link") ?: return@mapNotNull null
            FeedItem(e.text("title") ?: "", link, e.text("guid") ?: link)
        }
        val atom = root.all("entry").mapNotNull { e ->
            val link = e.children("link").firstOrNull()?.getAttribute("href")?.takeIf { it.isNotEmpty() } ?: e.text("id") ?: return@mapNotNull null
            FeedItem(e.text("title") ?: "", link, e.text("id") ?: link)
        }
        return rss + atom
    }

    private fun time(s: String?): Long? = s?.let { runCatching { OffsetDateTime.parse(it).toEpochSecond() }.getOrNull() }

    /** One CAP alert document; the first English info block is used. */
    fun alert(xml: String, link: String? = null): Warning? {
        val root = parse(xml).documentElement
        if ((root.localName ?: root.nodeName) != "alert") return null
        if (root.text("msgType") == "Cancel") return null
        val infos = root.children("info")
        val info = infos.firstOrNull { (it.text("language") ?: "en").startsWith("en") } ?: infos.firstOrNull() ?: return null
        val params = info.children("parameter").associate { (it.text("valueName") ?: "") to (it.text("value") ?: "") }
        val colour = params.entries.firstOrNull { it.key.contains("colour", true) || it.key.contains("color", true) }?.value
        val areas = info.children("area")
        val polygons = areas.flatMap { a -> a.children("polygon").mapNotNull { polygon(it.textContent) } }
        return Warning(
            id = root.text("identifier") ?: link ?: return null,
            event = info.text("event") ?: "Warning",
            headline = info.text("headline") ?: info.text("event") ?: "",
            severity = info.text("severity") ?: "Unknown",
            colour = colour?.takeIf { it.isNotBlank() },
            onset = time(info.text("onset") ?: info.text("effective")),
            expires = time(info.text("expires")),
            areaDesc = areas.mapNotNull { it.text("areaDesc") }.joinToString(", "),
            description = info.text("description") ?: "",
            instruction = info.text("instruction") ?: "",
            polygons = polygons,
            link = info.text("web") ?: link,
        )
    }

    /** CAP polygons are "lat,lon lat,lon ..." with the first point repeated at the end. */
    fun polygon(s: String): List<Pair<Double, Double>>? {
        val pts = s.trim().split(Regex("\\s+")).mapNotNull { p ->
            val (a, b) = p.split(",").takeIf { it.size >= 2 } ?: return@mapNotNull null
            val lat = a.toDoubleOrNull() ?: return@mapNotNull null
            val lon = b.toDoubleOrNull() ?: return@mapNotNull null
            lat to lon
        }
        return pts.takeIf { it.size >= 3 }
    }

    /** Ray casting point-in-polygon test. */
    fun inside(poly: List<Pair<Double, Double>>, lat: Double, lon: Double): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (yi, xi) = poly[i]
            val (yj, xj) = poly[j]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }
}

package app.weathertrack.core.about

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** One release (or planned release) from CHANGELOG.md / ROADMAP.md: a "## " heading and its "- " lines. */
data class NoteSection(val title: String, val lines: List<String>) {
    /** "1.0" from "1.0 · Clouds, wind and roadmap"; null when the heading doesn't start with a version. */
    val version: String? get() = Regex("^(\\d+(?:\\.\\d+)*)").find(title)?.value
}

/** The newest published build on GitHub. */
data class LatestRelease(val tag: String, val build: Int, val name: String, val pageUrl: String, val apkUrl: String?)

object ReleaseNotes {
    /** Splits simple Markdown into sections. Lines before the first "## " heading are ignored. */
    fun parse(markdown: String): List<NoteSection> {
        val out = mutableListOf<NoteSection>()
        var title: String? = null
        val lines = mutableListOf<String>()
        fun flush() { title?.let { out += NoteSection(it, lines.toList()) }; lines.clear() }
        for (raw in markdown.lineSequence()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("## ") -> { flush(); title = line.removePrefix("## ").trim() }
                title == null -> Unit
                line.trimStart().startsWith("- ") -> lines += line.trimStart().removePrefix("- ").trim()
                // A wrapped bullet continues the line above it.
                line.isNotBlank() && lines.isNotEmpty() && raw.startsWith("  ") -> lines[lines.lastIndex] = lines.last() + " " + line.trim()
                line.isNotBlank() -> lines += line.trim()
            }
        }
        flush()
        return out
    }

    /** The section for the installed version ("1.0.75" matches "1.0 · ..."), if any. */
    fun current(sections: List<NoteSection>, versionName: String): NoteSection? =
        sections.firstOrNull { s -> s.version?.let { v -> versionName == v || versionName.startsWith("$v.") } == true }

    /** The build number at the end of a tag like "v1.0.75" (CI's run number, which is the versionCode). */
    fun buildOf(tag: String): Int? = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull()

    private val json = Json { ignoreUnknownKeys = true }

    /** Reads GitHub's "latest release" response; null if it isn't one. */
    fun parseLatest(body: String): LatestRelease? {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val tag = str("tag_name") ?: return null
        val build = buildOf(tag) ?: return null
        val apk = (o["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull?.endsWith(".apk") == true }
            ?.let { (it["browser_download_url"] as? JsonPrimitive)?.contentOrNull }
        return LatestRelease(tag, build, str("name") ?: tag, str("html_url") ?: return null, apk)
    }
}

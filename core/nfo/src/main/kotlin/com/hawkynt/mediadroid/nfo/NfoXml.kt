package com.hawkynt.mediadroid.nfo

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Parses NFO XML text into a DOM. External entities are resolved to nothing, so a
 * hostile or offline DTD reference can never make the parser reach for the network.
 */
internal fun parseNfoXml(xml: String): Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    factory.setNamespaceAware(false)
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
    return builder.parse(InputSource(StringReader(xml)))
}

/** The document's root element, failing with a clear message when the file is empty. */
internal fun Document.rootElement(): Element {
    val root = documentElement ?: throw NfoFormatException("NFO has no root element.")
    return root
}

/** Text of the first direct child with [name], or null when absent or blank. */
internal fun Element.childText(name: String): String? {
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node is Element && node.tagName == name) {
            val text = node.textContent?.trim()
            if (!text.isNullOrEmpty()) return text
        }
    }
    return null
}

/** All direct children with [name], in document order. */
internal fun Element.childElements(name: String): List<Element> {
    val nodes = childNodes
    val result = ArrayList<Element>()
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node is Element && node.tagName == name) result.add(node)
    }
    return result
}

/** The first integer found in the text, or null — `2010-06-18` yields 2010. */
internal fun Element.childInt(name: String): Int? =
    childText(name)?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

/**
 * The `uniqueid` elements modern scrapers (jellyfin, tinyMediaManager) write, as
 * `type` to value pairs — `imdb` to `tt0460681`, `tmdb` to `1622`.
 */
internal fun Element.uniqueIds(): Map<String, String> {
    val result = HashMap<String, String>()
    for (element in childElements("uniqueid")) {
        val type = element.getAttribute("type").lowercase()
        val value = element.textContent?.trim()
        if (type.isNotEmpty() && !value.isNullOrEmpty()) result[type] = value
    }
    return result
}

/**
 * The rating on a 0–10 scale: the `<ratings>` entry marked `default="true"` (else the first), its
 * `<value>` scaled by its `max` (10 when absent), or the old flat `<rating>`. Zero means unrated.
 */
internal fun Element.rating(): Double? {
    val entries = childElements("ratings").flatMap { it.childElements("rating") }
    val entry = entries.firstOrNull { it.getAttribute("default") == "true" } ?: entries.firstOrNull()
    val value = if (entry != null) {
        val max = entry.getAttribute("max").toDoubleOrNull()?.takeIf { it > 0 } ?: 10.0
        entry.childText("value")?.toDoubleOrNull()?.let { it * 10.0 / max }
    } else {
        childText("rating")?.toDoubleOrNull()
    }
    return value?.takeIf { it > 0 }
}

/**
 * `<dateadded>` (`2019-01-02 10:04:20`, the date alone is enough) as epoch milliseconds. Kodi writes
 * local time; it is read as UTC here because it only ever orders items against each other.
 */
internal fun Element.dateAddedMs(): Long? {
    val text = childText("dateadded") ?: return null
    val match = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2})(?::(\d{2}))?)?""")
        .find(text) ?: return null
    val v = match.groupValues.map { it.toIntOrNull() ?: 0 }
    return runCatching {
        java.time.LocalDateTime.of(v[1], v[2], v[3], v[4], v[5], v[6])
            .toInstant(java.time.ZoneOffset.UTC)
            .toEpochMilli()
    }.getOrNull()
}

class NfoFormatException(message: String) : IllegalArgumentException(message)

/**
 * Languages of the `<audio>` or `<subtitle>` streams under `<fileinfo><streamdetails>`, as Kodi
 * writes them (3-letter codes): lower-cased, blanks dropped, each once in file order.
 */
internal fun Element.streamLanguages(streamKind: String): List<String> =
    childElements("fileinfo").flatMap { it.childElements("streamdetails") }
        .flatMap { it.childElements(streamKind) }
        .mapNotNull { it.childText("language")?.lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()

/**
 * One audio stream of `<streamdetails>`: Kodi's codec name (`ac3`, `truehd_atmos`, `dtshd_ma`, …),
 * channel count and language.
 */
data class AudioStream(val codec: String?, val channels: Int?, val language: String?)

/**
 * The technical side of a file as `<fileinfo><streamdetails>` records it: the first video stream's size,
 * codec and HDR type (`hdr10`, `hdr10plus`, `dolbyvision`, `hlg`) and every audio stream.
 */
data class StreamDetails(
    val videoWidth: Int?,
    val videoHeight: Int?,
    val videoCodec: String?,
    val hdrType: String?,
    val audio: List<AudioStream>,
)

/** The `<fileinfo><streamdetails>` of this NFO, or null when it has none. */
internal fun Element.streamDetails(): StreamDetails? {
    val details = childElements("fileinfo").flatMap { it.childElements("streamdetails") }.firstOrNull() ?: return null
    val video = details.childElements("video").firstOrNull()
    return StreamDetails(
        videoWidth = video?.childInt("width"),
        videoHeight = video?.childInt("height"),
        videoCodec = video?.childText("codec")?.lowercase(),
        hdrType = video?.childText("hdrtype")?.lowercase(),
        audio = details.childElements("audio").map {
            AudioStream(
                codec = it.childText("codec")?.lowercase(),
                channels = it.childInt("channels"),
                language = it.childText("language")?.lowercase(),
            )
        },
    )
}

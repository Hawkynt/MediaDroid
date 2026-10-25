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

class NfoFormatException(message: String) : IllegalArgumentException(message)

package com.hawkynt.mediadroyd.player

import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The face libass falls back to for Chinese, Japanese and Korean text where neither the file's fonts nor the default
 * one have it — on Android, which gives libass no font provider to ask. As the system's own fallback chain does: the
 * script picks the language (kana Japanese, Hangul Korean, Bopomofo Traditional Chinese; ideographs alone the device's
 * own language where it is one of them, else Simplified Chinese, the first CJK family Android lists), and the device's
 * font configuration (AOSP's `fonts.xml`, `font_fallback.xml` from Android 15) the face for that language.
 */
object ScriptFallback {
    /** The language text calls for — a BCP 47 tag of fonts.xml's (`ja`, `ko`, `zh-Hans`, `zh-Hant`) — and whether
     * the script itself says so ([certain]) rather than the device's language. */
    data class Pick(val language: String, val certain: Boolean)

    /** A face: a font [file] (under the system's font folder) and its [index] in a collection. */
    data class Face(val file: String, val index: Int)

    /** A fallback family of fonts.xml: its [languages] (`lang`, empty for none) and its regular sans [face]. */
    data class Family(val languages: List<String>, val face: Face)

    private val cjk = listOf("zh-Hans", "zh-Hant", "ja", "ko")

    /**
     * The CJK language [text] calls for on a device speaking [locale] (a BCP 47 tag), or null where it has no Chinese,
     * Japanese or Korean script. ASS override tags and Latin text count for nothing.
     */
    fun language(text: CharSequence, locale: String): Pick? {
        var han = false
        var bopomofo = false
        var kana = false
        var i = 0
        while (i < text.length) {
            val code = Character.codePointAt(text, i)
            i += Character.charCount(code)
            when (Character.UnicodeScript.of(code)) {
                Character.UnicodeScript.HANGUL -> return Pick("ko", true)
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana = true
                Character.UnicodeScript.BOPOMOFO -> bopomofo = true
                Character.UnicodeScript.HAN -> han = true
                else -> Unit
            }
        }
        return when {
            kana -> Pick("ja", true)
            bopomofo -> Pick("zh-Hant", true)
            han -> Pick(deviceLanguage(locale), false)
            else -> null
        }
    }

    /** The CJK language of a device speaking [locale] for ideographs alone: its own, else Simplified Chinese. */
    private fun deviceLanguage(locale: String): String {
        val parts = locale.replace('_', '-').split('-').map { it.lowercase() }
        return when (parts.first()) {
            "ja" -> "ja"
            "ko" -> "ko"
            "zh" -> if ("hant" in parts || parts.any { it in setOf("tw", "hk", "mo") }) "zh-Hant" else "zh-Hans"
            else -> "zh-Hans"
        }
    }

    /**
     * One subtitle track's choice as its text arrives ([see]): the language of its first CJK text, until the script
     * itself settles it (kana, Hangul, Bopomofo) — after that nothing moves it.
     */
    class Track(private val locale: String) {
        /** The language chosen so far; null while no CJK text was seen. */
        var language: String? = null
            private set
        private var settled = false

        /** The language to fall back to now that [text] arrived, where it differs from before; else null. */
        fun see(text: CharSequence): String? {
            if (settled) return null
            val pick = language(text, locale) ?: return null
            settled = pick.certain
            if (pick.language == language) return null
            language = pick.language
            return pick.language
        }
    }

    /**
     * The fallback families of a font configuration [xml] (fonts.xml's `familyset`), in its order: the unnamed
     * families, each with its regular (weight 400, upright) sans face — the serif ones (`fallbackFor`) passed over.
     * Empty where it does not read.
     */
    fun families(xml: String): List<Family> = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val root = factory.newDocumentBuilder().parse(xml.byteInputStream()).documentElement
        root.childElements("family").filter { !it.hasAttribute("name") }.mapNotNull { family ->
            val fonts = family.childElements("font").filter { !it.hasAttribute("fallbackFor") }
            val font = fonts.firstOrNull { it.weight == 400 && it.getAttribute("style") != "italic" }
                ?: fonts.firstOrNull()
                ?: return@mapNotNull null
            val file = font.childNodes.let { nodes ->
                (0 until nodes.length).map(nodes::item).filter { it.nodeType == org.w3c.dom.Node.TEXT_NODE }
                    .joinToString("") { it.nodeValue }.trim()
            }
            if (file.isEmpty()) return@mapNotNull null
            val languages = family.getAttribute("lang").split(' ', '\t', '\n', ',').filter { it.isNotBlank() }
            Family(languages, Face(file, font.getAttribute("index").toIntOrNull() ?: 0))
        }
    }.getOrDefault(emptyList())

    /**
     * The face for [language] among a device's [families], of those whose file [exists]: the family for the language,
     * else any other CJK family (ideographs at least), else one with no language, else the files Android has carried
     * by name — Noto Sans CJK with its faces in their order (JP, KR, SC, TC), Droid Sans Fallback. Null where the
     * device has none.
     */
    fun face(language: String, families: List<Family>, exists: (String) -> Boolean): Face? {
        val present = families.filter { exists(it.face.file) }
        return present.firstOrNull { family -> family.languages.any { serves(it, language) } }?.face
            ?: present.firstOrNull { family -> family.languages.any { tag -> cjk.any { serves(tag, it) } } }?.face
            ?: present.firstOrNull { it.languages.isEmpty() }?.face
            ?: carried(language).firstOrNull { exists(it.file) }
    }

    /** Whether fonts.xml's language [tag] is one for [language]. */
    private fun serves(tag: String, language: String): Boolean {
        val parts = tag.split('-').map { it.lowercase() }
        val script = parts.getOrNull(1)
        return when (language) {
            "ja" -> parts[0] == "ja" || script in setOf("jpan", "hira", "kana", "hrkt")
            "ko" -> parts[0] == "ko" || script in setOf("kore", "hang")
            "zh-Hant" -> (parts[0] == "zh" && (script == "hant" || script in setOf("tw", "hk", "mo"))) ||
                script in setOf("hant", "bopo")
            else -> (parts[0] == "zh" && script != "hant" && script !in setOf("tw", "hk", "mo", "bopo")) ||
                script == "hans" || script == "hani"
        }
    }

    /** The CJK faces Android has carried, for a device whose font configuration does not read. */
    private fun carried(language: String) = listOf(
        Face("NotoSansCJK-Regular.ttc", mapOf("ja" to 0, "ko" to 1, "zh-Hans" to 2, "zh-Hant" to 3)[language] ?: 2),
        Face("DroidSansFallbackFull.ttf", 0),
        Face("DroidSansFallback.ttf", 0),
    )

    private val Element.weight: Int get() = getAttribute("weight").toIntOrNull() ?: 400

    private fun Element.childElements(name: String): List<Element> =
        (0 until childNodes.length).map(childNodes::item).filterIsInstance<Element>().filter { it.tagName == name }
}

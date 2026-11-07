package com.hawkynt.mediadroid.library

import java.io.ByteArrayOutputStream

/** The `%XX` escapes of a location's parts (RFC 3986), decoded as Android's Uri.decode does it on every platform. */
object PercentDecoding {

    /** [text] with its `%XX` escapes decoded as UTF-8: a `+` stays a `+`, an escape that is not one stays as written. */
    fun decode(text: String): String {
        if ('%' !in text) return text
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val hex = if (text[i] == '%' && i + 2 <= text.lastIndex) text.substring(i + 1, i + 3) else null
            val byte = hex?.takeIf { h -> h.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' } }?.toInt(16)
            if (byte != null) {
                bytes.write(byte)
                i += 3
            } else {
                bytes.write(text[i].toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return bytes.toString(Charsets.UTF_8)
    }
}

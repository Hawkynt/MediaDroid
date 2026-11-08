package com.hawkynt.mediadroyd.player

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.extractor.mp3.Mp3InfoReplayGain

/**
 * The tags of an audio track as (key, value) for [ReplayGain.parse]: Vorbis comments (FLAC, Ogg, Opus), ID3 TXXX
 * frames by their description, iTunes freeform atoms (`----:com.apple.iTunes:replaygain_track_gain`) and string
 * `mdta` entries, and the ReplayGain field of an MP3's LAME header — that one first, so a tag written later wins.
 */
@OptIn(UnstableApi::class)
object ReplayGainTags {

    fun of(metadata: Metadata?): List<Pair<String, String>> {
        if (metadata == null) return emptyList()
        val header = mutableListOf<Pair<String, String>>()
        val tags = mutableListOf<Pair<String, String>>()
        for (i in 0 until metadata.length()) {
            when (val entry = metadata.get(i)) {
                is VorbisComment -> tags += entry.key to entry.value
                is TextInformationFrame -> entry.description?.let { tags += it to entry.value }
                is InternalFrame -> tags += "${entry.domain}:${entry.description}" to entry.text
                is MdtaMetadataEntry -> if (entry.typeIndicator == MdtaMetadataEntry.TYPE_INDICATOR_STRING) {
                    tags += entry.key to String(entry.value, Charsets.UTF_8)
                }
                is Mp3InfoReplayGain -> header += lameHeader(entry)
            }
        }
        return header + tags
    }

    private fun lameHeader(gain: Mp3InfoReplayGain): List<Pair<String, String>> = buildList {
        for (field in listOfNotNull(gain.field1, gain.field2)) {
            val kind = if (field.name == Mp3InfoReplayGain.GainField.NAME_RADIO) "TRACK" else "ALBUM"
            add("REPLAYGAIN_${kind}_GAIN" to field.gain.toString())
            if (gain.peak > 0f) add("REPLAYGAIN_${kind}_PEAK" to gain.peak.toString())
        }
    }
}

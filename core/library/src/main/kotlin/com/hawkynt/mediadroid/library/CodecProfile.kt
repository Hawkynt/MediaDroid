package com.hawkynt.mediadroid.library

/**
 * The profile and level a stream was encoded with, read from its RFC 6381 `codecs` string, as people
 * read it: `High@L4.1`, `Main 10@L5.1`, `HE-AAC`. Null for a codec string that carries none.
 *
 * The fields and numbers come from the specifications: H.264 `avc1.PPCCLL` (ITU-T H.264 Annex A
 * profile_idc and level_idc), HEVC `hvc1.P.F.TLL` (ISO/IEC 14496-15 Annex E, ITU-T H.265 Annex A),
 * AV1 `av01.P.LLT.DD` (AV1 Codec ISO Media File Format Binding, seq_level_idx), VP9 `vp09.PP.LL.DD`
 * (VP9 Codec ISO Media File Format Binding), AAC `mp4a.40.N` (ISO/IEC 14496-3 audio object types) and
 * Dolby Vision `dvh1.PP.LL` (Dolby Vision Streams within the ISO Base Media File Format).
 */
object CodecProfile {

    fun of(codecs: String?): String? {
        val parts = codecs?.trim()?.split('.') ?: return null
        return runCatching {
            when (parts[0]) {
                "avc1", "avc3" -> avc(parts)
                "hvc1", "hev1" -> hevc(parts)
                "av01" -> av1(parts)
                "vp09" -> vp9(parts)
                "mp4a" -> aac(parts)
                "dvh1", "dvhe", "dvav", "dva1", "dav1" -> "Profile ${parts[1].toInt()}@L${parts[2].toInt()}"
                else -> null
            }
        }.getOrNull()
    }

    private fun avc(parts: List<String>): String? {
        val hex = parts[1]
        if (hex.length != 6) return null
        val profile = hex.substring(0, 2).toInt(16)
        val constraints = hex.substring(2, 4).toInt(16)
        val level = hex.substring(4, 6).toInt(16)
        val name = when (profile) {
            // constraint_set1_flag on Baseline is Constrained Baseline.
            66 -> if (constraints and 0x40 != 0) "Constrained Baseline" else "Baseline"
            77 -> "Main"
            88 -> "Extended"
            100 -> "High"
            110 -> "High 10"
            122 -> "High 4:2:2"
            244 -> "High 4:4:4 Predictive"
            44 -> "CAVLC 4:4:4 Intra"
            118 -> "Multiview High"
            128 -> "Stereo High"
            else -> "Profile $profile"
        }
        return "$name@L${tenths(level)}"
    }

    private fun hevc(parts: List<String>): String {
        // general_profile_space is a leading A, B or C before the profile_idc.
        val profile = parts[1].trimStart('A', 'B', 'C').toInt()
        val tierAndLevel = parts[3]
        val level = tierAndLevel.substring(1).toInt()
        val name = when (profile) {
            1 -> "Main"
            2 -> "Main 10"
            3 -> "Main Still Picture"
            4 -> "Range Extensions"
            5 -> "High Throughput"
            9 -> "Screen Content"
            else -> "Profile $profile"
        }
        // general_level_idc is 30 times the level number.
        val shown = "$name@L${tenths(level / 3)}"
        return if (tierAndLevel[0] == 'H') "$shown, High tier" else shown
    }

    private fun av1(parts: List<String>): String {
        val name = when (val profile = parts[1].toInt()) {
            0 -> "Main"
            1 -> "High"
            2 -> "Professional"
            else -> "Profile $profile"
        }
        val index = parts[2].dropLast(1).toInt()
        val level = if (index and 3 == 0) "${2 + (index shr 2)}" else "${2 + (index shr 2)}.${index and 3}"
        return "$name@L$level, ${parts[3].toInt()}-bit"
    }

    private fun vp9(parts: List<String>): String =
        "Profile ${parts[1].toInt()}@L${tenths(parts[2].toInt())}, ${parts[3].toInt()}-bit"

    private fun aac(parts: List<String>): String? {
        if (parts[1] != "40") return null
        return when (parts[2].toInt()) {
            1 -> "Main"
            2 -> "LC"
            3 -> "SSR"
            4 -> "LTP"
            5 -> "HE-AAC"
            23 -> "LD"
            29 -> "HE-AAC v2"
            39 -> "ELD"
            else -> null
        }
    }

    /** A level written as ten times its number: 41 is 4.1, 30 is 3. */
    private fun tenths(value: Int): String = if (value % 10 == 0) "${value / 10}" else "${value / 10}.${value % 10}"
}

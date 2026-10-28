package com.hawkynt.mediadroid.nfo

import kotlinx.serialization.Serializable
/** A span inside a video, in milliseconds. */
@Serializable
data class TimeRange(val startMs: Long, val endMs: Long)

/** One line of an edit decision list: a span and what to do with it (see [EditDecisionList]). */
data class EdlEdit(val startMs: Long, val endMs: Long, val action: Int)

/** The intro and the credits an edit decision list marks, either possibly unknown. */
data class EdlSkips(val intro: TimeRange?, val credits: TimeRange?)

/**
 * The MPlayer edit decision list as Kodi reads it: `<video>.edl` beside the video, one edit per line,
 * `[start] [end] [action]`, times in seconds, `HH:MM:SS.sss` or `#frames`; lines starting `##` are
 * comments. Jellyfin's Intro Skipper writes its intros this way, Comskip its commercial breaks.
 * Rung 3: the normative description is the Kodi wiki page "Edit decision list"; the action numbers
 * are its constants, plus Intro Skipper's `4` for an intro.
 */
object EditDecisionList {

    const val CUT = 0
    const val MUTE = 1
    const val SCENE_MARKER = 2
    const val COMMERCIAL_BREAK = 3
    const val INTRO = 4

    /** Edits a player jumps over; mutes and scene markers only mark. */
    private val skipActions = setOf(CUT, COMMERCIAL_BREAK, INTRO)

    /** An intro starts within this much of the beginning; a skip that starts later is no intro. */
    const val INTRO_WINDOW_MS = 15 * 60_000L

    /** `Show - S01E02.mkv` → `Show - S01E02.edl`. */
    fun fileNameFor(videoFileName: String): String = videoFileName.substringBeforeLast('.') + ".edl"

    /**
     * Every edit in [text] whose times are in seconds or clock form. Frame numbers need the video's
     * frame rate, which the library does not read, so those edits are left out; so is anything malformed.
     * A scene marker is an edit that starts and ends at its (last) time.
     */
    fun parse(text: String): List<EdlEdit> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("##") }
        .mapNotNull { line ->
            val fields = line.split(Regex("\\s+"))
            when (fields.size) {
                3 -> {
                    val start = time(fields[0]) ?: return@mapNotNull null
                    val end = time(fields[1]) ?: return@mapNotNull null
                    val action = fields[2].toIntOrNull() ?: return@mapNotNull null
                    when {
                        end < start -> null
                        // "If start and end times are specified, the end time is used."
                        action == SCENE_MARKER -> EdlEdit(end, end, action)
                        else -> EdlEdit(start, end, action)
                    }
                }
                2 -> {
                    val at = time(fields[0]) ?: return@mapNotNull null
                    if (fields[1].toIntOrNull() == SCENE_MARKER) EdlEdit(at, at, SCENE_MARKER) else null
                }
                else -> null
            }
        }
        .toList()

    /**
     * The intro and credits among [edits]: the first skip that starts within [INTRO_WINDOW_MS] is the
     * intro, and the last skip after it (or the only one, when it starts later) is where the credits begin.
     */
    fun skips(edits: List<EdlEdit>): EdlSkips {
        val skips = edits.filter { it.action in skipActions && it.endMs > it.startMs }.sortedBy { it.startMs }
        val intro = skips.firstOrNull { it.startMs < INTRO_WINDOW_MS }
        val credits = skips.lastOrNull { it !== intro && (intro == null || it.startMs >= intro.endMs) }
            ?.takeIf { intro != null || it.startMs >= INTRO_WINDOW_MS }
        return EdlSkips(intro?.span(), credits?.span())
    }

    private fun EdlEdit.span() = TimeRange(startMs, endMs)

    /** Seconds (`420`, `5.3`) or clock time (`7:00`, `1:02:03.5`) in milliseconds; null for frames or junk. */
    private fun time(field: String): Long? {
        if (field.startsWith("#")) return null
        val parts = field.split(':')
        if (parts.size > 3) return null
        var seconds = 0.0
        for ((i, part) in parts.withIndex()) {
            val value = part.toDoubleOrNull() ?: return null
            if (value < 0 || (i < parts.lastIndex && part.contains('.'))) return null
            seconds = seconds * 60 + value
        }
        return Math.round(seconds * 1000)
    }
}

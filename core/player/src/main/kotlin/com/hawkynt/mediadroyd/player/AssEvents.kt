package com.hawkynt.mediadroyd.player

/**
 * ASS/SSA subtitles as the players hand them over, made into what libass ([AssTrack]) takes: which tracks are ASS, a
 * stream's header, its events with the time they show, a whole script file.
 */
object AssEvents {
    /** Media3's MIME type for ASS and SSA (MimeTypes.TEXT_SSA). */
    const val MIME_TYPE = "text/x-ssa"

    /** An event (`ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text`) shown for [durationMs]. */
    class Event(val durationMs: Long, val data: ByteArray)

    // Media3's MatroskaExtractor puts "Dialogue: 0:00:00:00,<duration>," before a block (its SSA_PREFIX, the end
    // written as "%01d:%02d:%02d:%02d" — hours, minutes, seconds, hundredths).
    private val matroskaPrefix = Regex("""^Dialogue: 0:00:00:00,(\d+):(\d{2}):(\d{2}):(\d{2}),""")

    /** Media3's MIME type for a subtitle file beside the video that no parser of its own reads (its own in codecs). */
    const val UNKNOWN_MIME_TYPE = "text/x-unknown"

    /** Whether a track of [mimeType] is ASS or SSA. */
    fun isAss(mimeType: String?): Boolean = mimeType == MIME_TYPE

    /**
     * Whether a Media3 track of [mimeType] and [codecs] is ASS or SSA: a stream as the file holds it, or a file beside
     * the video Media3 leaves unread (`text/x-unknown`, the file's own type in codecs), which libass reads whole.
     */
    fun isAss(mimeType: String?, codecs: String?): Boolean =
        isAss(mimeType) || (mimeType == UNKNOWN_MIME_TYPE && codecs == MIME_TYPE)

    /** Whether FFmpeg's codec [name] is ASS or SSA. */
    fun isAssCodec(name: String?): Boolean = name == "ass" || name == "ssa"

    /**
     * The event in a Matroska [sample] as Media3 hands it — its block after the prefix with the duration — or null
     * where it is no such sample (a whole file, which [isScript] tells).
     */
    fun fromMatroskaSample(sample: ByteArray): Event? {
        val head = String(sample, 0, minOf(sample.size, PREFIX_LOOK), Charsets.ISO_8859_1)
        val match = matroskaPrefix.find(head) ?: return null
        val (hours, minutes, seconds, hundredths) = match.destructured
        val durationMs = ((hours.toLong() * 60 + minutes.toLong()) * 60 + seconds.toLong()) * 1000 +
            hundredths.toLong() * 10
        return Event(durationMs, sample.copyOfRange(match.range.last + 1, sample.size))
    }

    /** A stream's header from Media3's initialization data: the codec private, after the events' format line. */
    fun header(initializationData: List<ByteArray>): ByteArray? = initializationData.getOrNull(1)

    /** Whether [data] is a whole script file: `[Script Info]` before any event, past a byte order mark and comments. */
    fun isScript(data: ByteArray): Boolean {
        val head = String(data, 0, minOf(data.size, SCRIPT_LOOK), Charsets.UTF_8).removePrefix("\uFEFF")
        return head.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith(";") } ==
            "[Script Info]"
    }

    private const val PREFIX_LOOK = 64
    private const val SCRIPT_LOOK = 4096
}

package com.hawkynt.mediadroyd.engine.desktop

/**
 * A moment of playback as the stage needs it (GlStage): the picture [shown], the one shown before it, the [next] two
 * decoded after it, and how far the clock is from [shown] to [next] ([phase], 0 to 1). The desktop decodes ahead, so
 * a temporal deinterlacer, smooth motion and Motion's search see the pictures to come without waiting for them. The
 * picture shown before [previous], [earlier], is PAL's dot-crawl comb's (DotCrawl); the ones shown before that,
 * [older] (newest first, up to [OLDER]), its comb per field for a capture bobbed to its fields.
 */
class Moment(
    val shown: MediaReader.Picture,
    val previous: MediaReader.Picture? = null,
    val next: MediaReader.Picture? = null,
    val after: MediaReader.Picture? = null,
    val phase: Float = 0f,
    val earlier: MediaReader.Picture? = null,
    val older: List<MediaReader.Picture> = emptyList(),
) {
    companion object {
        /** How many pictures shown before [earlier] a moment carries: back to the sixth before [shown]. */
        const val OLDER = 4

        /** How far [now] is from [shown]'s time to [next]'s, 0 to 1; 0 without a next picture. */
        fun phase(shown: MediaReader.Picture, next: MediaReader.Picture?, nowMs: Long): Float {
            if (next == null || next.ptsMs <= shown.ptsMs) return 0f
            return ((nowMs - shown.ptsMs).toFloat() / (next.ptsMs - shown.ptsMs)).coerceIn(0f, 1f)
        }
    }
}

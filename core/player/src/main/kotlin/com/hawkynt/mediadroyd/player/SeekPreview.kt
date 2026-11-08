package com.hawkynt.mediadroyd.player

import java.security.MessageDigest

/**
 * Seek-bar thumbnails: which picture shows for the moment under the finger, and how the pictures are
 * kept. The timeline is cut into steps of whole seconds, one picture per step taken from its middle, so
 * a finger sliding along asks for a few hundred pictures at most however long the film, and moving
 * within a step costs nothing. Pictures are kept per file (by path and modification time) and per
 * moment, in memory and in a small disk cache that lets go of the longest unused ones first.
 */
object SeekPreview {
    /** The most steps a film is cut into. */
    const val MOST_PICTURES = 300

    /** The shortest step: closer pictures of a short clip would mostly be the same keyframe. */
    const val SHORTEST_STEP_MS = 2_000L

    /** The longest step, for very long recordings. */
    const val LONGEST_STEP_MS = 60_000L

    /** The step when the length is not known (yet). */
    const val UNKNOWN_LENGTH_STEP_MS = 10_000L

    /** How many steps away a picture already kept may stand in while the right one loads. */
    const val STAND_IN_REACH = 3

    /** The step length for a file [durationMs] long: whole seconds, at most [MOST_PICTURES] across it. */
    fun stepMs(durationMs: Long): Long {
        if (durationMs <= 0) return UNKNOWN_LENGTH_STEP_MS
        val wholeSeconds = (durationMs + MOST_PICTURES * 1_000L - 1) / (MOST_PICTURES * 1_000L) * 1_000L
        return wholeSeconds.coerceIn(SHORTEST_STEP_MS, LONGEST_STEP_MS)
    }

    /** The step [positionMs] falls in; positions before or after the file fall on its first or last. */
    fun bucketOf(positionMs: Long, durationMs: Long): Int {
        val last = if (durationMs > 0) durationMs - 1 else Long.MAX_VALUE
        return (positionMs.coerceIn(0, last) / stepMs(durationMs)).toInt()
    }

    /** The moment the picture for [bucket] is taken: the middle of the step, inside the file. */
    fun frameTimeMs(bucket: Int, durationMs: Long): Long {
        val step = stepMs(durationMs)
        val middle = bucket * step + step / 2
        return if (durationMs > 1) middle.coerceIn(1, durationMs - 1) else middle
    }

    /**
     * The step among [kept] nearest to [bucket], within [STAND_IN_REACH], to show while the right picture
     * loads; between two equally near, the earlier. Null when none is near enough.
     */
    fun standIn(bucket: Int, kept: Collection<Int>): Int? =
        kept.filter { kotlin.math.abs(it - bucket) <= STAND_IN_REACH }
            .minWithOrNull(compareBy<Int> { kotlin.math.abs(it - bucket) }.thenBy { it })

    /** The cache folder for one file: a hash of its path and modification time, so a changed file starts afresh. */
    fun fileKey(path: String, modifiedMs: Long): String =
        MessageDigest.getInstance("SHA-1").digest("$path@$modifiedMs".toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** The file name a picture taken at [frameTimeMs] is kept under. */
    fun pictureName(frameTimeMs: Long): String = "$frameTimeMs.jpg"

    /** One picture on disk: its [name], size and when it was last used. */
    data class Kept(val name: String, val bytes: Long, val usedAt: Long)

    /** The pictures to delete, longest unused first, until what stays fits in [budgetBytes]. */
    fun toLetGo(kept: List<Kept>, budgetBytes: Long): List<String> {
        var total = kept.sumOf { it.bytes }
        val letGo = ArrayList<String>()
        for (entry in kept.sortedBy { it.usedAt }) {
            if (total <= budgetBytes) break
            letGo += entry.name
            total -= entry.bytes
        }
        return letGo
    }
}

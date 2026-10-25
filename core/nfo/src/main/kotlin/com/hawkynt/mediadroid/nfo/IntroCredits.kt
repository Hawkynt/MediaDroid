package com.hawkynt.mediadroid.nfo

/** A second-resolution span inside a video, as written by the sidecar extension below. */
data class TimeRange(val startSec: Int, val endSec: Int)

/**
 * Parses `<intro>start,end</intro>` and `<credits>start,end</credits>` spans in
 * seconds. This is our own sidecar extension, not a Kodi convention: Kodi's NFO
 * vocabulary has no intro or credits element, so files that carry these spans
 * come from tooling that adopted the same shape. Anything malformed yields
 * null — the player then falls back to chapters or skips nothing.
 */
fun parseTimeRange(text: String?): TimeRange? {
    val parts = text?.split(',')?.map { it.trim() } ?: return null
    if (parts.size != 2) return null
    val start = parts[0].toIntOrNull() ?: return null
    val end = parts[1].toIntOrNull() ?: return null
    return if (start in 0..end) TimeRange(start, end) else null
}

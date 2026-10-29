package com.hawkynt.mediadroid.player

/**
 * Times the per-frame processor work on 720 × 480 pictures, as an NTSC DVD gives them: Film's field
 * matching and decimation on 3:2 pulldown, with a cut. Run by `./gradlew :core:player:benchmark`;
 * the numbers are milliseconds per frame on this machine — a phone takes several times as long.
 */
fun main() {
    val frames = telecined(720, 480, 40)
    report("Film: FieldMatch + Decimate", frames.size) {
        val match = Telecine.FieldMatch()
        val decimate = Telecine.Decimate(mixed = true, duplicatePercent = 3.0)
        for (k in frames.indices) {
            val (_, woven) = match.add(frames[maxOf(k - 1, 0)], frames[k], frames[minOf(k + 1, frames.size - 1)])
            decimate.add(woven)
        }
    }
    val scenes = run {
        val match = Telecine.FieldMatch()
        frames.indices.count { k ->
            match.add(frames[maxOf(k - 1, 0)], frames[k], frames[minOf(k + 1, frames.size - 1)]).first.sceneChange
        }
    }
    println("(scene changes judged in ${frames.size} frames: $scenes)")
    val woven = run {
        val match = Telecine.FieldMatch()
        frames.indices.map { k ->
            match.add(frames[maxOf(k - 1, 0)], frames[k], frames[minOf(k + 1, frames.size - 1)]).second
        }
    }
    report("Film: FieldMatch alone", frames.size) {
        val match = Telecine.FieldMatch()
        for (k in frames.indices) match.add(frames[maxOf(k - 1, 0)], frames[k], frames[minOf(k + 1, frames.size - 1)])
    }
    report("Film: Decimate alone", frames.size) {
        val decimate = Telecine.Decimate(mixed = true, duplicatePercent = 3.0)
        for (frame in woven) decimate.add(frame)
    }
    report("Film: combed score (scene changes only)", 1) { Telecine.combedScore(frames[2]) }
}

/** Runs [work] (covering [count] frames) until warm, then reports the best of the timed rounds per frame. */
private fun report(name: String, count: Int, work: () -> Unit) {
    repeat(WARM_UP) { work() }
    val best = (1..ROUNDS).minOf {
        val start = System.nanoTime()
        work()
        System.nanoTime() - start
    }
    println("%-40s %7.3f ms per frame".format(name, best / 1e6 / count))
}

private const val WARM_UP = 5
private const val ROUNDS = 7

/** [count] frames of [width] × [height] of 3:2 pulldown, top field first: moving texture, a cut half-way. */
private fun telecined(width: Int, height: Int, count: Int): List<Deinterlacer.Plane> {
    fun film(t: Int) = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        val cut = t >= count * 2 / 5
        val v = if (!cut) {
            40 + ((x - 6 * t) * 7 + y * 3) % 23 + if ((x - 2 * y + 6 * t) % 48 < 16) 80 else 0
        } else {
            200 - ((x - 2 * t) * 5 + y * 11) % 37 - if ((x + y + 4 * t) % 64 < 20) 90 else 0
        }
        v.coerceIn(0, 255)
    }
    val pattern = arrayOf(intArrayOf(0, 0), intArrayOf(1, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 3))
    return (0 until count).map { k ->
        val group = k / 5
        val (top, bottom) = pattern[k % 5].let { film(group * 4 + it[0]) to film(group * 4 + it[1]) }
        val woven = IntArray(width * height) { i -> if ((i / width) % 2 == 0) top[i] else bottom[i] }
        Deinterlacer.Plane(width, height, woven)
    }
}

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

    // Motion searches a DVD at half size (360 × 240), above 1280 pixels wide at a quarter (480 × 270 for 1080p;
    // 960 × 540 is what half size would cost there).
    for ((width, height) in listOf(360 to 240, 480 to 270, 960 to 540)) {
        val pan = panning(width, height, 12)
        report("Motion: search at $width × $height", pan.size - 1) {
            val estimator = MotionInterpolation.Estimator(width, height)
            for (k in 1 until pan.size) {
                estimator.advance()
                estimator.estimate(pan[k - 1], pan[k])
            }
        }
    }
}

/** [count] frames of a textured picture panning 3 pixels right and 1 down a frame, with a box moving its own way. */
private fun panning(width: Int, height: Int, count: Int): List<Deinterlacer.Plane> = (0 until count).map { t ->
    Deinterlacer.Plane(
        width,
        height,
        IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val bx = width / 2 - 5 * t
            val by = height / 3 + 2 * t
            if (x in bx until bx + width / 6 && y in by until by + height / 6) {
                250 - ((x - bx) * 13 + (y - by) * 7) % 61
            } else {
                30 + (((x - 3 * t) * 37 + (y - t) * 91) % 97 * 2)
            }
        },
    )
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

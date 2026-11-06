package com.hawkynt.mediadroid.player

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The subtitles libass drew for a [frameWidth] × [frameHeight] picture, bent as [warp] bends that picture on screen
 * (a projector's mirror, flip and keystone, see [ScreenWarp.overlayMatrix]): each pixel of the box the bent picture
 * covers takes, bilinearly, what the subtitles show where the warp takes it back to — so on a rear-projection screen
 * they read the right way round and on a keystoned one they stay on the picture. Unchanged where the warp bends no
 * overlay.
 */
fun AssPicture.bent(warp: ScreenWarp, frameWidth: Int, frameHeight: Int): AssPicture {
    if (isEmpty || !warp.bendsOverlays || frameWidth <= 0 || frameHeight <= 0) return this
    val ahead = warp.overlayMatrix(frameWidth.toFloat(), frameHeight.toFloat()).map { it.toDouble() }
    val back = warp.overlayBackMatrix(frameWidth.toFloat(), frameHeight.toFloat()).map { it.toDouble() }
    fun map(m: List<Double>, x: Double, y: Double): Pair<Double, Double>? {
        val w = m[6] * x + m[7] * y + m[8]
        if (w <= 0.0) return null
        return (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
    }
    val corners = listOf(x to y, x + width to y, x + width to y + height, x to y + height)
        .mapNotNull { (cx, cy) -> map(ahead, cx.toDouble(), cy.toDouble()) }
    if (corners.size < 4) return this
    val left = floor(corners.minOf { it.first }).toInt().coerceIn(0, frameWidth)
    val top = floor(corners.minOf { it.second }).toInt().coerceIn(0, frameHeight)
    val right = ceil(corners.maxOf { it.first }).toInt().coerceIn(0, frameWidth)
    val bottom = ceil(corners.maxOf { it.second }).toInt().coerceIn(0, frameHeight)
    if (right <= left || bottom <= top) return AssPicture.EMPTY
    val w = right - left
    val h = bottom - top
    val out = IntArray(w * h)
    for (row in 0 until h) for (column in 0 until w) {
        val (sx, sy) = map(back, left + column + 0.5, top + row + 0.5) ?: continue
        out[row * w + column] = sample(sx - x - 0.5, sy - y - 0.5)
    }
    return AssPicture(left, top, w, h, out)
}

/** The colour at [fx], [fy] (in pixel centres of this box), bilinear over premultiplied colour; outside, nothing. */
private fun AssPicture.sample(fx: Double, fy: Double): Int {
    val x0 = floor(fx).toInt()
    val y0 = floor(fy).toInt()
    val ax = fx - x0
    val ay = fy - y0
    var a = 0.0
    var r = 0.0
    var g = 0.0
    var b = 0.0
    for ((dx, dy, weight) in listOf(
        Triple(0, 0, (1 - ax) * (1 - ay)), Triple(1, 0, ax * (1 - ay)),
        Triple(0, 1, (1 - ax) * ay), Triple(1, 1, ax * ay),
    )) {
        val px = x0 + dx
        val py = y0 + dy
        if (weight == 0.0 || px !in 0 until width || py !in 0 until height) continue
        val p = pixels[py * width + px]
        val pa = (p ushr 24) / 255.0 * weight
        a += pa
        r += ((p shr 16) and 0xFF) * pa
        g += ((p shr 8) and 0xFF) * pa
        b += (p and 0xFF) * pa
    }
    if (a <= 0.0) return 0
    fun channel(v: Double) = (v / a).toInt().coerceIn(0, 255)
    return ((a * 255.0 + 0.5).toInt().coerceIn(0, 255) shl 24) or (channel(r + a / 2) shl 16) or
        (channel(g + a / 2) shl 8) or channel(b + a / 2)
}

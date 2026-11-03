package com.hawkynt.mediadroid.player

/**
 * Neural upscaling (Real-ESRGAN's compact network, four times the picture's size): how a picture is cut for a
 * network that takes a fixed [TILE] × [TILE] input, and whether a device is fast enough to use it while playing.
 *
 * Tiles overlap by [OVERLAP] on every side: each fills only its core, the part at least that far from its edges,
 * so the seams between tiles come from inside a tile, where the network saw the pixels around. Reads outside the
 * picture repeat its edge.
 */
object NeuralUpscaling {
    /** The network's input side, in pixels. */
    const val TILE = 128

    /** The margin a tile reads around the core it fills. */
    const val OVERLAP = 8

    /** How much larger the network makes a picture. */
    const val SCALE = 4

    /** What one 576p picture may take: a frame at 25 frames a second. */
    const val BUDGET_MS = 40.0

    /** One tile: its input square at ([x], [y]) — partly outside the picture at its edges — and the core it fills. */
    data class Tile(val x: Int, val y: Int, val coreX: Int, val coreY: Int, val coreWidth: Int, val coreHeight: Int)

    /** The tiles for a [width] × [height] picture, row by row; their cores fill it exactly once. */
    fun tiles(width: Int, height: Int): List<Tile> {
        val step = TILE - 2 * OVERLAP
        val tiles = ArrayList<Tile>()
        for (coreY in 0 until height step step) for (coreX in 0 until width step step) {
            tiles += Tile(
                x = coreX - OVERLAP,
                y = coreY - OVERLAP,
                coreX = coreX,
                coreY = coreY,
                coreWidth = minOf(step, width - coreX),
                coreHeight = minOf(step, height - coreY),
            )
        }
        return tiles
    }

    /** Whether a device that upscales a 576p picture in [msFor576p] keeps up with playback. */
    fun fastEnough(msFor576p: Double): Boolean = msFor576p <= BUDGET_MS
}

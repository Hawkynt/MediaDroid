package com.hawkynt.mediadroid.engine

/**
 * The picture of what plays, as the decoder takes it: its size in pixels ([width], [height]), the shape of its pixels
 * ([pixelAspect], width over height, 1 for square), its frames a second ([frameRate], 0 where the file does not say),
 * how its brightness is coded ([transfer]) and the bits of each sample ([bitDepth]).
 */
data class VideoFormat(
    val width: Int,
    val height: Int,
    val pixelAspect: Float = 1f,
    val frameRate: Float = 0f,
    val transfer: Transfer = Transfer.SDR,
    val bitDepth: Int = 8,
) {
    /** Standard range, or one of HDR's two codings: SMPTE ST 2084 (PQ, HDR10) and HLG. */
    enum class Transfer { SDR, PQ, HLG }
}

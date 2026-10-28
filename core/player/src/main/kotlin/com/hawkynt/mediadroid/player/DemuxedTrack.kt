package com.hawkynt.mediadroid.player

/**
 * What Media3 is told about a stream FFmpeg demuxed. Codecs Android's own decoders take as FFmpeg
 * hands them over keep Media3's MIME type, so the hardware can decode them; everything else is named
 * for the bundled FFmpeg decoder — `audio/x-ffmpeg-wmav2`, `video/x-ffmpeg-rv40` — and carries the
 * stream's codec-parameter blob (see `tools/ffmpeg/mediadroid/jni/mediadroid_codecpar.h`).
 * MIME strings are Media3's `MimeTypes` constants.
 */
object DemuxedTrack {
    /** Stream kinds as `FfmpegDemuxer` reports them. */
    const val VIDEO = 0
    const val AUDIO = 1
    const val OTHER = 3

    const val AUDIO_PREFIX = "audio/x-ffmpeg-"
    const val VIDEO_PREFIX = "video/x-ffmpeg-"

    private const val HEADER_SIZE = 48
    private const val EXTRADATA_SIZE_OFFSET = 44

    /** The MIME type and initialization data a stream gets. */
    class Mapping(val mimeType: String, val initializationData: List<ByteArray>)

    /** Media3's own MIME types for the codecs that need nothing from the container. */
    private val plain = mapOf(
        "hevc" to "video/hevc",
        "mpeg2video" to "video/mpeg2",
        "vp8" to "video/x-vnd.on2.vp8",
        "vp9" to "video/x-vnd.on2.vp9",
        "mp3" to "audio/mpeg",
        "mp2" to "audio/mpeg-L2",
        "ac3" to "audio/ac3",
        "eac3" to "audio/eac3",
        "dts" to "audio/vnd.dts",
        "truehd" to "audio/true-hd",
    )

    /** The stream's own extradata out of its codec-parameter [blob]; empty when there is none. */
    fun extradataOf(blob: ByteArray): ByteArray {
        if (blob.size < HEADER_SIZE) return ByteArray(0)
        var size = 0
        for (i in 0 until 4) size = size or ((blob[EXTRADATA_SIZE_OFFSET + i].toInt() and 0xff) shl (8 * i))
        if (size <= 0 || size > blob.size - HEADER_SIZE) return ByteArray(0)
        return blob.copyOfRange(HEADER_SIZE, HEADER_SIZE + size)
    }

    /**
     * How a stream of [kind] with FFmpeg's [codecName] and codec-parameter [blob] reaches Media3;
     * [decoderName] is the bundled decoder for it, which FFmpeg finds by that name.
     */
    fun map(kind: Int, codecName: String?, blob: ByteArray, decoderName: String? = codecName): Mapping? {
        if (codecName == null || (kind != VIDEO && kind != AUDIO)) return null
        val extradata = extradataOf(blob)
        val native: Mapping? = when (codecName) {
            // Android's decoders want H.264 and HEVC in Annex B, as the raw-stream demuxers give it.
            "h264", "hevc" -> if (extradata.isEmpty() || isAnnexB(extradata)) {
                Mapping(if (codecName == "h264") "video/avc" else "video/hevc", emptyList())
            } else {
                null
            }
            "mpeg4" -> Mapping("video/mp4v-es", listOfNotNull(extradata.takeIf { it.isNotEmpty() }))
            // AAC without its AudioSpecificConfig cannot be configured on MediaCodec.
            "aac" -> extradata.takeIf { it.isNotEmpty() }?.let { Mapping("audio/mp4a-latm", listOf(it)) }
            else -> plain[codecName]?.let { Mapping(it, emptyList()) }
        }
        val prefix = if (kind == VIDEO) VIDEO_PREFIX else AUDIO_PREFIX
        return native ?: Mapping(prefix + (decoderName ?: codecName), listOf(blob))
    }

    private fun isAnnexB(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            (data[2] == 1.toByte() || (data[2] == 0.toByte() && data[3] == 1.toByte()))
}

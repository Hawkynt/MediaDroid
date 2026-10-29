package com.hawkynt.mediadroid.player

import com.hawkynt.mediadroid.player.PlaybackChain.Lane
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackChainTest {

    private val video = PlaybackChain.Video(
        input = 0,
        decoder = "c2.qti.hevc.decoder",
        width = 3840,
        height = 1606,
        pixelRatio = 1f,
        picture = PictureAdjustment.Neutral,
        fit = "Fit",
        zoom = 1f,
        screen = "this screen",
        screenWidth = 2400,
        screenHeight = 1080,
        refreshHz = 120f,
    )
    private val audio = PlaybackChain.Audio(
        input = 0,
        decoder = "c2.android.aac.decoder",
        inputSampleRate = 48_000,
        inputChannels = 6,
        sinkEncoding = "16-bit PCM",
        sinkSampleRate = 48_000,
        sinkChannels = 2,
        passthrough = false,
        offload = false,
        loudnessDb = 0f,
        nightMode = false,
        speed = 1f,
        delayMs = 0,
        spatial = false,
        device = "This device",
    )
    private val subtitles = PlaybackChain.Subtitles(
        input = 1,
        mimeType = "application/x-subrip",
        language = "German",
        delayMs = 0,
        screen = "this screen",
    )
    private val facts = PlaybackChain.Facts(
        inputs = listOf(
            PlaybackChain.Source("/storage/emulated/0/Movies/Heat (1995).mkv", "MatroskaExtractor"),
            PlaybackChain.Source("/storage/emulated/0/Movies/Heat (1995).de.srt", "SubripExtractor"),
        ),
        video = video,
        audio = audio,
        subtitles = subtitles,
        player = "Media3 ExoPlayer 1.11.1",
    )

    private fun PlaybackChain.Graph.lane(lane: Lane) = paths.single { it.lane == lane }

    private fun PlaybackChain.Graph.detail(lane: Lane, title: String) =
        lane(lane).nodes.single { it.title == title }.detail

    @Test
    fun `every file read is an input, with where it is read from and what splits it`() {
        val graph = PlaybackChain.of(facts)
        assertEquals(
            listOf(
                PlaybackChain.Input("Heat (1995).mkv", "Local file", "Media3 Matroska / WebM"),
                PlaybackChain.Input("Heat (1995).de.srt", "Local file", "Media3 Subrip"),
            ),
            graph.inputs,
        )
        assertEquals("Media3 ExoPlayer 1.11.1", graph.engine)
    }

    @Test
    fun `picture, sound and subtitles each run their own lane from their input to their output`() {
        val graph = PlaybackChain.of(facts)
        assertEquals(listOf(Lane.PICTURE, Lane.SOUND, Lane.SUBTITLES), graph.paths.map { it.lane })
        assertEquals(listOf(0, 0, 1), graph.paths.map { it.input })
        assertEquals(
            listOf("Decoder", "Conversion", "Pixels", "Filters", "Renderer"),
            graph.lane(Lane.PICTURE).nodes.map { it.title },
        )
        assertEquals(
            listOf("Decoder", "Processing", "Resampling", "Mixing", "Sink"),
            graph.lane(Lane.SOUND).nodes.map { it.title },
        )
        assertEquals(listOf("Parser", "Timing", "Renderer"), graph.lane(Lane.SUBTITLES).nodes.map { it.title })
        assertEquals(
            PlaybackChain.Node("Screen", "this screen · 2400 × 1080 · 120 Hz"),
            graph.lane(Lane.PICTURE).output,
        )
        assertEquals(PlaybackChain.Node("Output", "This device"), graph.lane(Lane.SOUND).output)
        assertEquals(
            PlaybackChain.Node("Screen", "over the picture on this screen"),
            graph.lane(Lane.SUBTITLES).output,
        )
    }

    @Test
    fun `the source names how the file is reached`() {
        fun from(location: String) =
            PlaybackChain.of(facts.copy(inputs = listOf(PlaybackChain.Source(location, null)))).inputs[0].readFrom
        assertEquals("Local file", from("/storage/emulated/0/Movies/Heat.mkv"))
        assertEquals("SMB share (smbj)", from("smb://nas/films/Heat.mkv"))
        assertEquals("NFS export (NFSv3)", from("nfs://nas/export/Heat.mkv"))
        assertEquals("SFTP (sshj)", from("sftp://nas/Heat.mkv"))
        assertEquals("FTPS (Commons Net)", from("ftps://nas/Heat.mkv"))
        assertEquals("WebDAV (OkHttp)", from("https://nas/dav/Heat.mkv"))
        assertEquals("Document folder (Android storage access)", from("content://com.android.docs/tree/x"))
    }

    @Test
    fun `the demuxer is Media3's own extractor or FFmpeg's demuxer by its format`() {
        fun demuxer(name: String?) =
            PlaybackChain.of(facts.copy(inputs = listOf(PlaybackChain.Source("/a.mkv", name)))).inputs[0].demuxer
        assertEquals("Media3 MP4", demuxer("FragmentedMp4Extractor"))
        assertEquals("Media3 MPEG-TS", demuxer("TsExtractor"))
        assertEquals("Media3 AVI", demuxer("AviExtractor"))
        assertEquals("Media3 Midi", demuxer("MidiExtractor"))
        assertEquals("FFmpeg (asf)", demuxer("ffmpeg:asf"))
        assertEquals("Media3 subtitle parser", demuxer("SubtitleExtractor"))
        assertEquals("not known yet", demuxer(null))
    }

    @Test
    fun `a hardware decoder writes straight to the surface, FFmpeg converts on the processor`() {
        val graph = PlaybackChain.of(facts)
        assertEquals("c2.qti.hevc.decoder (hardware)", graph.detail(Lane.PICTURE, "Decoder"))
        assertEquals(
            "none on the processor: the decoder draws onto the surface, the display turns it into RGB",
            graph.detail(Lane.PICTURE, "Conversion"),
        )
        assertEquals("Media3 MediaCodec video renderer", graph.detail(Lane.PICTURE, "Renderer"))

        val ffmpeg = PlaybackChain.of(facts.copy(video = video.copy(decoder = "ffmpeg-wmv3")))
        assertEquals("ffmpeg-wmv3 (software, FFmpeg)", ffmpeg.detail(Lane.PICTURE, "Decoder"))
        assertEquals(
            "to YV12 (YUV 4:2:0, 8-bit) by libyuv, the display turns it into RGB",
            ffmpeg.detail(Lane.PICTURE, "Conversion"),
        )
        assertEquals("FFmpeg video renderer", ffmpeg.detail(Lane.PICTURE, "Renderer"))
    }

    @Test
    fun `decoder and conversion name the pixel format at every step`() {
        val hdr = PlaybackChain.of(
            facts.copy(
                video = video.copy(
                    chroma = "4:2:0",
                    bitDepth = 10,
                    colorSpace = "BT.2020",
                    transfer = "PQ (HDR10)",
                    range = "limited range",
                ),
            ),
        )
        assertEquals("c2.qti.hevc.decoder (hardware) → YUV 4:2:0, 10-bit", hdr.detail(Lane.PICTURE, "Decoder"))
        assertEquals(
            "none on the processor: YUV 4:2:0, 10-bit stays on the surface, the display turns it into RGB",
            hdr.detail(Lane.PICTURE, "Conversion"),
        )
        assertEquals(
            "3840 × 1606, square · BT.2020, PQ (HDR10), limited range",
            hdr.detail(Lane.PICTURE, "Pixels"),
        )

        val dv = PlaybackChain.of(facts.copy(video = video.copy(decoder = "ffmpeg-dvvideo", decodedFormat = "yuv411p")))
        assertEquals(
            "ffmpeg-dvvideo (software, FFmpeg) → yuv411p (YUV 4:1:1, 8-bit)",
            dv.detail(Lane.PICTURE, "Decoder"),
        )
        assertEquals(
            "yuv411p → YV12 (YUV 4:2:0, 8-bit) by libyuv, the display turns it into RGB",
            dv.detail(Lane.PICTURE, "Conversion"),
        )
        val plain = PlaybackChain.of(facts.copy(video = video.copy(decoder = "ffmpeg-h264", decodedFormat = "yuv420p")))
        assertEquals(
            "yuv420p copied as YV12 (YUV 4:2:0, 8-bit), the display turns it into RGB",
            plain.detail(Lane.PICTURE, "Conversion"),
        )
        val deep = PlaybackChain.of(
            facts.copy(video = video.copy(decoder = "ffmpeg-hevc", decodedFormat = "yuv422p10le")),
        )
        assertEquals(
            "ffmpeg-hevc (software, FFmpeg) → yuv422p10le (YUV 4:2:2, 10-bit)",
            deep.detail(Lane.PICTURE, "Decoder"),
        )
    }

    @Test
    fun `timing says the frame rate, and how it is changed to fall evenly on the screen`() {
        val steady = PlaybackChain.of(facts.copy(video = video.copy(frameRate = 25f)))
        assertEquals("25 fps on 120 Hz", steady.detail(Lane.PICTURE, "Timing"))
        val even = PlaybackChain.of(
            facts.copy(video = video.copy(frameRate = 23.976f, speedFactor = 24f / 23.976f)),
        )
        assertEquals("23.976 fps played at 24 fps (+0.1 %) on 120 Hz", even.detail(Lane.PICTURE, "Timing"))
        val slower = PlaybackChain.of(
            facts.copy(video = video.copy(frameRate = 23.976f, speedFactor = 20f / 23.976f, refreshHz = 60f)),
        )
        assertEquals("23.976 fps played at 20 fps (−16.6 %) on 60 Hz", slower.detail(Lane.PICTURE, "Timing"))
    }

    @Test
    fun `decoder and processing name the sample format`() {
        val aac = PlaybackChain.of(facts.copy(audio = audio.copy(pcmFormat = "16-bit PCM")))
        assertEquals("c2.android.aac.decoder (software) → 16-bit PCM", aac.detail(Lane.SOUND, "Decoder"))
        assertEquals("none (16-bit PCM)", aac.detail(Lane.SOUND, "Processing"))

        val ffmpeg = PlaybackChain.of(
            facts.copy(
                audio = audio.copy(
                    decoder = "ffmpegLavc60-ac3",
                    decodedFormat = "fltp",
                    pcmFormat = "float PCM",
                    loudnessDb = 3f,
                ),
            ),
        )
        assertEquals(
            "ffmpegLavc60-ac3 (software, FFmpeg) · fltp (float, planar) → float PCM",
            ffmpeg.detail(Lane.SOUND, "Decoder"),
        )
        assertEquals("in float PCM: loudness +3 dB", ffmpeg.detail(Lane.SOUND, "Processing"))
    }

    @Test
    fun `pixels, filters and the screen say what happens to the picture`() {
        val graph = PlaybackChain.of(facts)
        assertEquals("3840 × 1606, square", graph.detail(Lane.PICTURE, "Pixels"))
        assertEquals("none · Fit", graph.detail(Lane.PICTURE, "Filters"))

        val dv = PlaybackChain.of(
            facts.copy(
                video = video.copy(
                    width = 720,
                    height = 576,
                    pixelRatio = 16f / 15f,
                    picture = PictureAdjustment(brightness = 0.1f, contrast = 1.2f),
                    fit = "Fill",
                    zoom = 1.5f,
                    screen = "LG TV",
                    screenWidth = 1920,
                    screenHeight = 1080,
                    refreshHz = 50f,
                ),
            ),
        )
        assertEquals("720 × 576, shown 768 × 576", dv.detail(Lane.PICTURE, "Pixels"))
        assertEquals("brightness +10 %, contrast 120 % · Fill, zoom 1.5×", dv.detail(Lane.PICTURE, "Filters"))
        assertEquals("LG TV · 1920 × 1080 · 50 Hz", dv.lane(Lane.PICTURE).output.detail)
    }

    @Test
    fun `the sound's way names every step that changes it`() {
        val plain = PlaybackChain.of(facts)
        assertEquals("c2.android.aac.decoder (software)", plain.detail(Lane.SOUND, "Decoder"))
        assertEquals("none", plain.detail(Lane.SOUND, "Processing"))
        assertEquals("none (48 kHz)", plain.detail(Lane.SOUND, "Resampling"))
        assertEquals("6 → 2 channels (downmix)", plain.detail(Lane.SOUND, "Mixing"))
        assertEquals("AudioTrack · 48 kHz · 2 channels · 16-bit PCM", plain.detail(Lane.SOUND, "Sink"))

        val busy = PlaybackChain.of(
            facts.copy(
                audio = audio.copy(
                    inputSampleRate = 44_100,
                    inputChannels = 2,
                    loudnessDb = 3f,
                    nightMode = true,
                    speed = 1.5f,
                    delayMs = 120,
                    spatial = true,
                    offload = true,
                ),
            ),
        )
        assertEquals(
            "loudness +3 dB, night mode, speed 1.5× (Sonic), delay 120 ms, spatial audio",
            busy.detail(Lane.SOUND, "Processing"),
        )
        assertEquals("44.1 kHz → 48 kHz", busy.detail(Lane.SOUND, "Resampling"))
        assertEquals("none (2 channels)", busy.detail(Lane.SOUND, "Mixing"))
        assertEquals(
            "AudioTrack · 48 kHz · 2 channels · 16-bit PCM · offloaded",
            busy.detail(Lane.SOUND, "Sink"),
        )
    }

    @Test
    fun `one channel is one channel`() {
        val mono = PlaybackChain.of(
            facts.copy(
                audio = audio.copy(
                    inputSampleRate = 44_100,
                    inputChannels = 1,
                    sinkSampleRate = 44_100,
                    sinkChannels = 1,
                ),
            ),
        )
        assertEquals("none (1 channel)", mono.detail(Lane.SOUND, "Mixing"))
        assertEquals("AudioTrack · 44.1 kHz · 1 channel · 16-bit PCM", mono.detail(Lane.SOUND, "Sink"))
        val up = PlaybackChain.of(facts.copy(audio = audio.copy(inputChannels = 1, sinkChannels = 2)))
        assertEquals("1 → 2 channels (upmix)", up.detail(Lane.SOUND, "Mixing"))
    }

    @Test
    fun `passthrough hands the encoded sound on untouched`() {
        val graph = PlaybackChain.of(
            facts.copy(audio = audio.copy(passthrough = true, sinkEncoding = "Dolby Digital", sinkChannels = 6)),
        )
        assertEquals("AudioTrack · passthrough of Dolby Digital", graph.detail(Lane.SOUND, "Sink"))
        assertEquals("none — passed through to the receiver", graph.detail(Lane.SOUND, "Mixing"))
    }

    @Test
    fun `subtitles name their format, their timing and where they are drawn`() {
        val graph = PlaybackChain.of(facts)
        assertEquals("SubRip · German", graph.detail(Lane.SUBTITLES, "Parser"))
        assertEquals("in sync", graph.detail(Lane.SUBTITLES, "Timing"))
        assertEquals("Media3 text renderer", graph.detail(Lane.SUBTITLES, "Renderer"))

        val late = PlaybackChain.of(
            facts.copy(subtitles = subtitles.copy(mimeType = "application/pgs", language = null, delayMs = -250)),
        )
        assertEquals("PGS (pictures)", late.detail(Lane.SUBTITLES, "Parser"))
        assertEquals("250 ms earlier", late.detail(Lane.SUBTITLES, "Timing"))
    }

    @Test
    fun `a lane without a track is left out`() {
        val graph = PlaybackChain.of(facts.copy(video = null, subtitles = null))
        assertEquals(listOf(Lane.SOUND), graph.paths.map { it.lane })
    }
}

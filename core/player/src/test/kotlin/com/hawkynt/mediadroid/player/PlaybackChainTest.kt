package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackChainTest {

    private val video = PlaybackChain.Video(
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
    private val facts = PlaybackChain.Facts(
        location = "/storage/emulated/0/Movies/Heat (1995).mkv",
        demuxer = "MatroskaExtractor",
        video = video,
        audio = audio,
        player = "Media3 ExoPlayer 1.11.1",
    )

    private fun List<PlaybackChain.Stage>.detail(section: String, step: String) =
        single { it.section == section && it.step == step }.detail

    @Test
    fun `the chain runs from the file to the screen and the speaker, in that order`() {
        val stages = PlaybackChain.of(facts)
        assertEquals(listOf("Source", "Video", "Audio", "Player"), stages.map { it.section }.distinct())
        assertEquals(
            listOf("Read from", "Demuxer"),
            stages.filter { it.section == "Source" }.map { it.step },
        )
        assertEquals(
            listOf("Decoder", "Conversion", "Pixels", "Filters", "Renderer", "Output"),
            stages.filter { it.section == "Video" }.map { it.step },
        )
        assertEquals(
            listOf("Decoder", "Processing", "Resampling", "Mixing", "Sink", "Output"),
            stages.filter { it.section == "Audio" }.map { it.step },
        )
    }

    @Test
    fun `the source names how the file is reached`() {
        assertEquals("Local file", PlaybackChain.of(facts).detail("Source", "Read from"))
        fun from(location: String) = PlaybackChain.of(facts.copy(location = location)).detail("Source", "Read from")
        assertEquals("SMB share (smbj)", from("smb://nas/films/Heat.mkv"))
        assertEquals("NFS export (NFSv3)", from("nfs://nas/export/Heat.mkv"))
        assertEquals("SFTP (sshj)", from("sftp://nas/Heat.mkv"))
        assertEquals("FTPS (Commons Net)", from("ftps://nas/Heat.mkv"))
        assertEquals("WebDAV (OkHttp)", from("https://nas/dav/Heat.mkv"))
        assertEquals("Document folder (Android storage access)", from("content://com.android.docs/tree/x"))
    }

    @Test
    fun `the demuxer is Media3's own extractor or FFmpeg's demuxer by its format`() {
        assertEquals("Media3 Matroska / WebM", PlaybackChain.of(facts).detail("Source", "Demuxer"))
        fun demuxer(name: String?) = PlaybackChain.of(facts.copy(demuxer = name)).detail("Source", "Demuxer")
        assertEquals("Media3 MP4", demuxer("FragmentedMp4Extractor"))
        assertEquals("Media3 MPEG-TS", demuxer("TsExtractor"))
        assertEquals("Media3 AVI", demuxer("AviExtractor"))
        assertEquals("Media3 Midi", demuxer("MidiExtractor"))
        assertEquals("FFmpeg (asf)", demuxer("ffmpeg:asf"))
        assertEquals("not known yet", demuxer(null))
    }

    @Test
    fun `a hardware decoder writes straight to the surface, FFmpeg converts on the processor`() {
        val stages = PlaybackChain.of(facts)
        assertEquals("c2.qti.hevc.decoder (hardware)", stages.detail("Video", "Decoder"))
        assertEquals("none — the decoder draws straight onto the surface", stages.detail("Video", "Conversion"))
        assertEquals("Media3 MediaCodec video renderer", stages.detail("Video", "Renderer"))

        val ffmpeg = PlaybackChain.of(facts.copy(video = video.copy(decoder = "ffmpeg-wmv3")))
        assertEquals("ffmpeg-wmv3 (software, FFmpeg)", ffmpeg.detail("Video", "Decoder"))
        assertEquals("YUV to the surface's format by libyuv", ffmpeg.detail("Video", "Conversion"))
        assertEquals("FFmpeg video renderer", ffmpeg.detail("Video", "Renderer"))
    }

    @Test
    fun `pixels, filters and the screen say what happens to the picture`() {
        val stages = PlaybackChain.of(facts)
        assertEquals("3840 × 1606, square", stages.detail("Video", "Pixels"))
        assertEquals("none · Fit", stages.detail("Video", "Filters"))
        assertEquals("this screen · 2400 × 1080 · 120 Hz", stages.detail("Video", "Output"))

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
        assertEquals("720 × 576, shown 768 × 576", dv.detail("Video", "Pixels"))
        assertEquals("brightness +10 %, contrast 120 % · Fill, zoom 1.5×", dv.detail("Video", "Filters"))
        assertEquals("LG TV · 1920 × 1080 · 50 Hz", dv.detail("Video", "Output"))
    }

    @Test
    fun `the sound's way names every step that changes it`() {
        val plain = PlaybackChain.of(facts)
        assertEquals("c2.android.aac.decoder (software)", plain.detail("Audio", "Decoder"))
        assertEquals("none", plain.detail("Audio", "Processing"))
        assertEquals("none (48 kHz)", plain.detail("Audio", "Resampling"))
        assertEquals("6 → 2 channels (downmix)", plain.detail("Audio", "Mixing"))
        assertEquals("AudioTrack · 48 kHz · 2 channels · 16-bit PCM", plain.detail("Audio", "Sink"))
        assertEquals("This device", plain.detail("Audio", "Output"))

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
            busy.detail("Audio", "Processing"),
        )
        assertEquals("44.1 kHz → 48 kHz", busy.detail("Audio", "Resampling"))
        assertEquals("none (2 channels)", busy.detail("Audio", "Mixing"))
        assertEquals("AudioTrack · 48 kHz · 2 channels · 16-bit PCM · offloaded", busy.detail("Audio", "Sink"))
    }

    @Test
    fun `one channel is one channel`() {
        val mono = PlaybackChain.of(
            facts.copy(
                audio = audio.copy(inputSampleRate = 44_100, inputChannels = 1, sinkSampleRate = 44_100, sinkChannels = 1),
            ),
        )
        assertEquals("none (1 channel)", mono.detail("Audio", "Mixing"))
        assertEquals("AudioTrack · 44.1 kHz · 1 channel · 16-bit PCM", mono.detail("Audio", "Sink"))
        val up = PlaybackChain.of(facts.copy(audio = audio.copy(inputChannels = 1, sinkChannels = 2)))
        assertEquals("1 → 2 channels (upmix)", up.detail("Audio", "Mixing"))
    }

    @Test
    fun `passthrough hands the encoded sound on untouched`() {
        val stages = PlaybackChain.of(
            facts.copy(audio = audio.copy(passthrough = true, sinkEncoding = "Dolby Digital", sinkChannels = 6)),
        )
        assertEquals("AudioTrack · passthrough of Dolby Digital", stages.detail("Audio", "Sink"))
        assertEquals("none — passed through to the receiver", stages.detail("Audio", "Mixing"))
    }

    @Test
    fun `a file without sound or picture leaves that part out`() {
        val stages = PlaybackChain.of(facts.copy(video = null))
        assertTrue(stages.none { it.section == "Video" })
        assertEquals("Media3 ExoPlayer 1.11.1", stages.detail("Player", "Engine"))
    }
}

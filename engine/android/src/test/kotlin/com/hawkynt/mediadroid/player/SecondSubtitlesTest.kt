package com.hawkynt.mediadroid.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.trackselection.TrackSelectorResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The second subtitle track's way to its renderer: Media3 picks the first track as ever, then the track wanted second
 * goes to the second set's renderer that handles it — never the track already playing, never a group the period does
 * not have — and its cues stand at the top. Under Robolectric (Media3's formats and cues), so in the screenshots' tier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@androidx.annotation.OptIn(UnstableApi::class)
class SecondSubtitlesTest {
    private class Renderer(private val handles: String) : RendererCapabilities {
        override fun getName() = "renderer for $handles"

        override fun getTrackType() = C.TRACK_TYPE_TEXT

        override fun supportsFormat(format: Format) = RendererCapabilities.create(
            if (format.sampleMimeType == handles) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE,
        )

        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
    }

    private fun group(id: String, mimeType: String) =
        TrackGroup(id, Format.Builder().setId(id).setSampleMimeType(mimeType).build())

    private val english = group("en", MimeTypes.APPLICATION_SUBRIP)
    private val german = group("de", MimeTypes.APPLICATION_SUBRIP)
    private val signs = group("signs", MimeTypes.TEXT_SSA)

    // The first set (libass, Media3's text renderer), then the second.
    private val firstAss = Renderer(MimeTypes.TEXT_SSA)
    private val firstText = Renderer(MimeTypes.APPLICATION_SUBRIP)
    private val secondAss = Renderer(MimeTypes.TEXT_SSA)
    private val secondText = Renderer(MimeTypes.APPLICATION_SUBRIP)
    private val renderers = arrayOf<RendererCapabilities>(firstAss, firstText, secondAss, secondText)
    private val groups = TrackGroupArray(english, german, signs)

    // Media3 playing the English track on the first text renderer.
    private val chosen = TrackSelectorResult(
        arrayOf(null, RendererConfiguration.DEFAULT, null, null),
        arrayOf(null, FixedTrackSelection(english, 0), null, null),
        null,
    )

    private fun target(group: TrackGroup) =
        SecondSubtitles.target(renderers, groups, group, 0, chosen) { it === secondAss || it === secondText }

    @Test
    fun `the track wanted second goes to the second renderer that handles it`() {
        assertEquals(3, target(german))
        assertEquals(2, target(signs))
    }

    @Test
    fun `not the track playing first, nor one the period does not have`() {
        assertNull(target(english))
        assertNull(target(group("fr", MimeTypes.APPLICATION_SUBRIP)))
    }

    @Test
    fun `cues shown second stand at the top unless they have a line of their own`() {
        val plain = Cue.Builder().setText("Hallo").build()
        val placed = Cue.Builder().setText("Sign").setLine(0.5f, Cue.LINE_TYPE_FRACTION).build()
        val (top, kept) = SecondSubtitles.atTop(listOf(plain, placed))
        assertEquals(Cue.LINE_TYPE_FRACTION, top.lineType)
        assertEquals(Cue.ANCHOR_TYPE_START, top.lineAnchor)
        assertEquals(true, top.line < 0.1f)
        assertEquals(placed, kept)
    }
}

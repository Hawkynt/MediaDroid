package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.player.LogoMask
import com.hawkynt.mediadroyd.player.PictureAdjustment
import com.hawkynt.mediadroyd.player.PictureCrop
import com.hawkynt.mediadroyd.player.ScreenRect
import com.hawkynt.mediadroyd.player.ScreenSetup
import com.hawkynt.mediadroyd.player.ScreenWarp
import com.hawkynt.mediadroyd.player.ToneMap
import java.io.File
import java.security.MessageDigest

/**
 * What the desktop stage draws, as a digest per case: every pass and its variants on the test clip, at its own size
 * and enlarged. Run before and after a change to the stage's code (`./gradlew :engine:desktop:stageDigests
 * -Pout=<file>`, on the same machine) and compare the two files: a change that only moves code draws the same
 * pixels. The cases whose result waits on a search on another thread (Motion, temporal denoising) are left out.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "stage-digests.txt")
    val clip = File(requireNotNull(Moment::class.java.getResource("/clip.mp4")).toURI()).path
    val pictures = MediaReader(clip).use { r ->
        r.keepPlanes = true
        generateSequence { r.read() }.filterIsInstance<MediaReader.Picture>().drop(8).take(5).toList()
    }
    val (before, shown, next, after) = pictures
    val beyond = pictures[4]
    fun digest(picture: MediaReader.Picture): String =
        MessageDigest.getInstance("SHA-256").digest(picture.rgba).joinToString("") { "%02x".format(it) }
    val w = shown.width
    val h = shown.height
    val hdr = MediaReader.Picture(1, 64, 48, ByteArray(64 * 48 * 8) { (it * 37 % 251).toByte() }, ToneMap.Transfer.PQ)
    val cases = buildList<Pair<String, PictureAdjustment>> {
        add("neutral" to PictureAdjustment.Neutral)
        for (u in PictureAdjustment.Upscaler.entries) {
            add("upscaler $u" to PictureAdjustment(upscaler = u))
            add("upscaler $u half" to PictureAdjustment(upscaler = u, upscalerStrength = 0.5f))
            add("upscaler $u adjusted" to PictureAdjustment(upscaler = u, contrast = 1.1f, sharpness = 0.4f))
        }
        for (d in PictureAdjustment.Deinterlace.entries) {
            add("deinterlace $d" to PictureAdjustment(deinterlace = d))
            add("deinterlace $d field rate" to PictureAdjustment(deinterlace = d, fieldRate = true))
        }
        for (c in PictureAdjustment.Chroma.entries) add("chroma $c" to PictureAdjustment(chroma = c))
        for (s in PictureAdjustment.Stereo.entries) add("stereo $s" to PictureAdjustment(stereo = s))
        for (i in PictureAdjustment.Interpolation.entries.filter { !it.isNeural && !it.isMotionCompensated }) {
            add("interpolation $i" to PictureAdjustment(interpolation = i))
        }
        add("deband" to PictureAdjustment(deband = 0.6f))
        add("denoise" to PictureAdjustment(noise = 0.5f))
        add("sharpen" to PictureAdjustment(sharpness = 0.7f, clarity = 0.3f))
        add("colour" to PictureAdjustment(brightness = 0.1f, gamma = 1.2f, contrast = 1.2f, saturation = 0.8f))
        add("deblock" to PictureAdjustment(deblock = 0.4f))
        add("deblock strong" to PictureAdjustment(deblock = 1f))
        add("dering" to PictureAdjustment(dering = 0.7f))
        add("dot crawl" to PictureAdjustment(dotCrawl = 0.5f))
        for (mode in LogoMask.Mode.entries) {
            add("logo $mode" to PictureAdjustment(logo = LogoMask(LogoMask.Corner.TOP_RIGHT, 0.25f, mode)))
        }
    }
    val sizes = listOf(w to h, w * 2 to h * 2, w * 5 / 2 to h * 7 / 3)
    val lines = ArrayList<String>()
    GlStage(Presentation.SYNC).use { stage ->
        for ((name, a) in cases) for ((sw, sh) in sizes) for (phase in listOf(0f, 0.25f, 0.75f)) {
            if (phase > 0f && a.interpolation == PictureAdjustment.Interpolation.OFF && !a.isFieldRate) continue
            val moment = Moment(shown, before, next, after, phase)
            val picture = stage.process(moment, a, sw, sh)
            lines += "$name ${sw}x$sh phase $phase ${digest(picture)}"
        }
        lines += "beyond ${digest(stage.process(Moment(next, shown, after, beyond), PictureAdjustment.Neutral, w, h))}"
        val crop = PictureCrop(4, 2, w - 10, h - 6)
        lines += "crop ${digest(stage.process(shown, PictureAdjustment.Neutral, w * 2, h * 2, crop))}"
        val setup = ScreenSetup(mirror = true, flip = true, keystoneVertical = 0.3f, keystoneHorizontal = -0.1f)
        val warp = ScreenWarp.of(setup, ScreenRect.centred(w, h, w * 2, h * 2), panorama = 1.3f)
        lines += "warp ${digest(stage.process(shown, PictureAdjustment.Neutral, w * 2, h * 2, warp = warp))}"
        for (u in listOf(PictureAdjustment.Upscaler.STANDARD, PictureAdjustment.Upscaler.FSR)) {
            lines += "hdr $u ${digest(stage.process(hdr, PictureAdjustment(upscaler = u), 160, 120))}"
        }
    }
    out.writeText(lines.joinToString("\n", postfix = "\n"))
    println("${lines.size} digests in $out")
}

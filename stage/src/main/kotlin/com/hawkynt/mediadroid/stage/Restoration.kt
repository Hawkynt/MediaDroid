package com.hawkynt.mediadroid.stage

import com.hawkynt.mediadroid.player.Cdef
import com.hawkynt.mediadroid.player.Deband
import com.hawkynt.mediadroid.player.Deblock
import com.hawkynt.mediadroid.player.LogoMask
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureCrop
import com.hawkynt.mediadroid.player.TemporalDenoise
import com.hawkynt.mediadroid.shaders.Shaders

/**
 * The restoration passes at the picture's own size — dot crawl, temporal denoising, deblocking and deringing, the
 * station logo, debanding, denoising and sharpening: which shader, which textures and which uniforms, the same on every
 * platform; which frames they read and draw into, and in what order, the platform stage's.
 */
class Restoration(private val gl: Gles, private val passes: Passes) {

    // Debanding's frame index, which seeds its random numbers: libplacebo's 8-bit counter.
    private var debandFrame = 0

    /**
     * Dot crawl and rainbows combed out of [shown] (DotCrawl) into [into]: NTSC's comb against [prev] and [next], PAL's
     * ([earlier], the picture two before, given) against those three; every texel read as it is.
     */
    fun dotCrawl(
        shown: DrawTarget,
        prev: DrawTarget,
        next: DrawTarget,
        earlier: DrawTarget?,
        into: DrawTarget,
        threshold: Float,
    ): DrawTarget {
        val sides = if (earlier != null) {
            listOf(earlier.texture to "uEarlier", prev.texture to "uPrev", next.texture to "uNext")
        } else {
            listOf(prev.texture to "uPrev", next.texture to "uNext")
        }
        val shader = if (earlier != null) Shaders.VIDEO_DOT_CRAWL_PAL else Shaders.VIDEO_DOT_CRAWL
        return passes.draw(shader, into, shown.texture, sides, nearest = true) {
            set("uTexel", 1f / shown.width, 1f / shown.height)
            set("uThreshold", threshold)
        }
    }

    /**
     * A side temporal denoising reads: its picture, and the motion vectors between it and the picture shown as a
     * texture of [gridWidth] × [gridHeight] blocks at [scale] — moved along them ([mode] 1), read as it is (0), or
     * not taken, behind a cut (2).
     */
    class Side(
        val picture: DrawTarget,
        val vectors: Int,
        val mode: Float,
        val gridWidth: Int = 0,
        val gridHeight: Int = 0,
        val scale: Int = 0,
        /** Whether the grid's uniforms are set (a side without vectors leaves them as they were). */
        val grid: Boolean = mode != 0f,
    )

    /**
     * [shown] temporally denoised (TemporalDenoise) into [into] against [prev] and [next], the overlapped blocks'
     * weights [obmc] beside them; every texel read as it is, the tables left read so afterwards.
     */
    fun temporalDenoise(
        shown: DrawTarget,
        prev: Side,
        next: Side,
        obmc: Int,
        params: TemporalDenoise.Params,
        into: DrawTarget,
    ): DrawTarget {
        val textures = listOf(
            prev.picture.texture to "uPrev",
            next.picture.texture to "uNext",
            prev.vectors to "uPrevVectors",
            next.vectors to "uNextVectors",
            obmc to "uObmc",
        )
        passes.draw(Shaders.VIDEO_TEMPORAL_DENOISE, into, shown.texture, textures, nearest = true) {
            set("uA", TemporalDenoise.threshold(params.a).toFloat())
            set("uB", TemporalDenoise.threshold(params.b).toFloat())
            set("uSize", shown.width.toFloat(), shown.height.toFloat())
            set("uPrevMode", prev.mode)
            set("uNextMode", next.mode)
            for ((side, name) in listOf(prev to "Prev", next to "Next")) {
                if (!side.grid) continue
                set("u${name}Grid", side.gridWidth.toFloat(), side.gridHeight.toFloat())
                set("u${name}Scale", side.scale.toFloat())
            }
        }
        // The pass leaves what it read nearest at linear; the tables are read texel by texel.
        for (table in listOf(prev.vectors, next.vectors, obmc)) {
            gl.bindTexture(Gles.TEXTURE_2D, table)
            gl.texParameteri(Gles.TEXTURE_2D, Gles.TEXTURE_MIN_FILTER, Gles.NEAREST)
            gl.texParameteri(Gles.TEXTURE_2D, Gles.TEXTURE_MAG_FILTER, Gles.NEAREST)
        }
        return into
    }

    /**
     * Deblocking's [step] (0 to 2) of [from] into [into] (Deblock), on the decoded picture's block grid starting at
     * [originX], [originY].
     */
    fun deblock(from: DrawTarget, into: DrawTarget, originX: Int, originY: Int, step: Int, d: Deblock.Params):
        DrawTarget = passes.draw(Shaders.VIDEO_DEBLOCK_ES3, into, from.texture) {
        setInt("uSize", from.width, from.height)
        setInt("uOrigin", originX, originY)
        setInt("uPass", step)
        setInt("uStrong", if (d.filter == Deblock.Filter.STRONG) 1 else 0)
        setInt(
            "uThresholds",
            Deblock.threshold(d.alpha),
            Deblock.threshold(d.beta),
            Deblock.threshold(d.gamma),
            Deblock.threshold(d.delta),
        )
    }

    /**
     * Deringing (Cdef) of [from] into [into]: each 8 × 8 block's direction and strength into [blocks] (one texel a
     * block, as many as fit from [originX], [originY]), then the filter along them.
     */
    fun dering(
        from: DrawTarget,
        blocks: DrawTarget,
        into: DrawTarget,
        originX: Int,
        originY: Int,
        d: Cdef.Params,
    ): DrawTarget {
        passes.draw(Shaders.VIDEO_CDEF_DIRECTION_ES3, blocks, from.texture) {
            setInt("uSize", from.width, from.height)
            setInt("uOrigin", originX, originY)
            setInt("uPrimary", d.primary)
        }
        return passes.draw(Shaders.VIDEO_CDEF_ES3, into, from.texture, listOf(blocks.texture to "uDirections")) {
            setInt("uSize", from.width, from.height)
            setInt("uOrigin", originX, originY)
            setInt("uBlocks", blocks.width, blocks.height)
            setInt("uSecondary", d.secondary)
            setInt("uDamping", d.damping)
        }
    }

    /** [logo]'s rectangle [rect] of [from] filled in from around it into [into] (LogoMask), each texel as it is. */
    fun logo(from: DrawTarget, into: DrawTarget, logo: LogoMask, rect: PictureCrop): DrawTarget =
        passes.draw(Shaders.VIDEO_LOGO_MASK, into, from.texture, nearest = true) {
            set("uSize", from.width.toFloat(), from.height.toFloat())
            set("uRect", rect.left.toFloat(), rect.top.toFloat(), rect.width.toFloat(), rect.height.toFloat())
            setInt("uMode", logo.mode.ordinal)
        }

    /**
     * [from] debanded into [into] (Deband, libplacebo's): GLSL ES 3.00's pass, which draws libplacebo's own random
     * numbers, where [es3]; else GLSL ES 1.00's. Each call is the next frame of its random numbers.
     */
    fun deband(from: DrawTarget, into: DrawTarget, d: Deband.Params, es3: Boolean = true): DrawTarget {
        debandFrame = (debandFrame + 1) and 0xFF
        return passes.draw(if (es3) Shaders.VIDEO_DEBAND_ES3 else Shaders.VIDEO_DEBAND, into, from.texture) {
            set("uSize", from.width.toFloat(), from.height.toFloat())
            // The picture's top row is where v is 1; libplacebo counts rows from the top.
            set("uRows", from.height.toFloat(), -1f)
            set("uFrame", debandFrame.toFloat())
            setInt("uIterations", d.iterations)
            set("uThreshold", d.threshold / 1000f)
            set("uRadius", d.radius)
            set("uDebandGrain", d.grain / 1000f)
        }
    }

    /** [from] denoised into [into] by the bilateral filter [k] (PictureAdjustment.denoiseKernel). */
    fun denoise(from: DrawTarget, into: DrawTarget, k: PictureAdjustment.Denoise): DrawTarget =
        passes.draw(Shaders.VIDEO_DENOISE, into, from.texture) {
            set("uTexel", 1f / from.width, 1f / from.height)
            set("uRadius", k.radius)
            set("uSigmaS2", 2f * k.radius * k.radius)
            set("uSigmaR2", 2f * k.similarity * k.similarity)
        }

    /**
     * [from] sharpened into [into] ([sharpness] and [clarity]: unsharp masking on the luma, small and large radius) —
     * at [into]'s size; [texel] the step its taps take, a texel of [from] by default.
     */
    fun sharpen(
        from: DrawTarget,
        into: DrawTarget,
        sharpness: Float,
        clarity: Float,
        texelX: Float = 1f / from.width,
        texelY: Float = 1f / from.height,
    ): DrawTarget = passes.draw(Shaders.VIDEO_SHARPEN, into, from.texture) {
        set("uTexel", texelX, texelY)
        set("uSharpness", sharpness)
        set("uClarity", clarity)
    }

    /** Builds the programs of the passes a chip without GLSL ES 3.00 can run, and with [es3] the others. */
    fun build(es3: Boolean) {
        val all = listOf(
            Shaders.VIDEO_DOT_CRAWL, Shaders.VIDEO_DOT_CRAWL_PAL, Shaders.VIDEO_TEMPORAL_DENOISE,
            Shaders.VIDEO_LOGO_MASK, Shaders.VIDEO_DENOISE, Shaders.VIDEO_SHARPEN,
        )
        all.forEach { passes.program(it) }
        passes.program(if (es3) Shaders.VIDEO_DEBAND_ES3 else Shaders.VIDEO_DEBAND)
    }
}

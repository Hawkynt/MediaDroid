package com.hawkynt.mediadroyd.stage

/**
 * A texture with its framebuffer, sized on demand ([ensure]): 8-bit RGBA, half floats for values outside 0..1 and
 * more than 8 bits (where [ensure] is asked for storage, immutable, as an image a compute pass writes must be), or
 * 16-bit values (desktop OpenGL only, for HDR's coded picture); read linearly, clamped at its edges.
 */
class StageFrame(
    private val gl: Gles,
    /**
     * The 8-bit frames' internal format: OpenGL ES 2's unsized RGBA (its only one) by default; desktop OpenGL's sized
     * RGBA8, which glCopyImageSubData holds to the textures it copies into.
     */
    private val bytes: Int = Gles.RGBA,
) : DrawTarget {
    override var texture = 0
        private set
    override var framebuffer = 0
        private set
    override var width = 0
        private set
    override var height = 0
        private set

    /** Whether the texture holds half floats rather than bytes. */
    var half = false
        private set

    /** Whether the texture holds 16-bit values. */
    var deep = false
        private set

    // Whether the texture is immutable storage.
    private var storage = false

    /**
     * This frame at [w] × [h] ([half]: in half floats, as immutable [storage] where asked; [deep]: in 16-bit values),
     * made anew where it was not; what it held is then gone.
     */
    fun ensure(w: Int, h: Int, half: Boolean = false, storage: Boolean = false, deep: Boolean = false): StageFrame {
        val stored = storage && half
        val deepened = deep && !half
        if (w == width && h == height && half == this.half && stored == this.storage && deepened == this.deep &&
            texture != 0
        ) {
            return this
        }
        release()
        texture = gl.genTexture()
        gl.bindTexture(Gles.TEXTURE_2D, texture)
        when {
            stored -> gl.texStorage2D(Gles.RGBA16F, w, h)
            half -> gl.texImage2D(Gles.RGBA16F, w, h, Gles.RGBA, Gles.HALF_FLOAT, null)
            deepened -> gl.texImage2D(Gles.RGBA16, w, h, Gles.RGBA, Gles.UNSIGNED_SHORT, null)
            else -> gl.texImage2D(bytes, w, h, Gles.RGBA, Gles.UNSIGNED_BYTE, null)
        }
        this.half = half
        this.storage = stored
        this.deep = deepened
        gl.texParameteri(Gles.TEXTURE_2D, Gles.TEXTURE_MIN_FILTER, Gles.LINEAR)
        gl.texParameteri(Gles.TEXTURE_2D, Gles.TEXTURE_MAG_FILTER, Gles.LINEAR)
        gl.texParameteri(Gles.TEXTURE_2D, Gles.TEXTURE_WRAP_S, Gles.CLAMP_TO_EDGE)
        gl.texParameteri(Gles.TEXTURE_2D, Gles.TEXTURE_WRAP_T, Gles.CLAMP_TO_EDGE)
        framebuffer = gl.genFramebuffer()
        gl.bindFramebuffer(framebuffer)
        gl.framebufferTexture2D(texture)
        width = w
        height = h
        return this
    }

    /**
     * Draws into this frame from here on, over the whole of it: the framebuffer bound and the viewport set to the
     * frame's own size together, so a pass never inherits the viewport of the one before.
     */
    fun bind() {
        gl.bindFramebuffer(framebuffer)
        gl.viewport(0, 0, width, height)
    }

    /** The texture and framebuffer deleted; [ensure] makes them anew. */
    fun release() {
        if (texture != 0) gl.deleteTexture(texture)
        if (framebuffer != 0) gl.deleteFramebuffer(framebuffer)
        texture = 0
        framebuffer = 0
        width = 0
        height = 0
    }
}

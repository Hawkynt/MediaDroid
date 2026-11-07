package com.hawkynt.mediadroid.stage

/**
 * A texture with its framebuffer, sized on demand ([ensure]): 8-bit RGBA, or half floats for values outside 0..1 and
 * more than 8 bits; read linearly, clamped at its edges.
 */
class StageFrame(private val gl: Gles) : DrawTarget {
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

    /** This frame at [w] × [h] ([half]: in half floats), made anew where it was not; what it held is then gone. */
    fun ensure(w: Int, h: Int, half: Boolean = false): StageFrame {
        if (w == width && h == height && half == this.half && texture != 0) return this
        release()
        texture = gl.genTexture()
        gl.bindTexture(Gles.TEXTURE_2D, texture)
        if (half) {
            gl.texImage2D(Gles.RGBA16F, w, h, Gles.RGBA, Gles.HALF_FLOAT, null)
        } else {
            gl.texImage2D(Gles.RGBA, w, h, Gles.RGBA, Gles.UNSIGNED_BYTE, null)
        }
        gl.texParameteri(Gles.TEXTURE_2D, Gles.TEXTURE_MIN_FILTER, Gles.LINEAR)
        gl.texParameteri(Gles.TEXTURE_2D, Gles.TEXTURE_MAG_FILTER, Gles.LINEAR)
        gl.texParameteri(Gles.TEXTURE_2D, Gles.TEXTURE_WRAP_S, Gles.CLAMP_TO_EDGE)
        gl.texParameteri(Gles.TEXTURE_2D, Gles.TEXTURE_WRAP_T, Gles.CLAMP_TO_EDGE)
        framebuffer = gl.genFramebuffer()
        gl.bindFramebuffer(framebuffer)
        gl.framebufferTexture2D(texture)
        this.half = half
        width = w
        height = h
        return this
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

package com.hawkynt.mediadroid.stage

import java.nio.ByteBuffer

/**
 * A Gles that draws nothing and writes down every call, a line each — for the passes' order and uniforms without a
 * graphics chip. Uniform locations are the names' indices in [names]; a shader whose source holds [failing] does not
 * compile.
 */
class RecordingGles(private val failing: String? = null) : Gles {
    val calls = ArrayList<String>()
    val names = ArrayList<String>()
    private val sources = HashMap<Int, String>()
    private var next = 1

    private fun log(call: String) {
        calls += call
    }

    private fun name(location: Int) = names.getOrElse(location) { "?" }

    override fun createShader(type: Int) = next++.also { log("createShader $type") }
    override fun shaderSource(shader: Int, source: String) {
        sources[shader] = source
    }
    override fun compileShader(shader: Int) = log("compileShader")
    override fun shaderCompiled(shader: Int) = failing == null || failing !in sources.getValue(shader)
    override fun shaderInfoLog(shader: Int) = "failed"
    override fun createProgram() = next++.also { log("createProgram $it") }
    override fun attachShader(program: Int, shader: Int) = Unit
    override fun bindAttribLocation(program: Int, index: Int, name: String) = log("attrib $index $name")
    override fun linkProgram(program: Int) = log("link $program")
    override fun programLinked(program: Int) = true
    override fun programInfoLog(program: Int) = ""
    override fun deleteProgram(program: Int) = log("deleteProgram $program")
    override fun useProgram(program: Int) = log("use $program")
    override fun uniformLocation(program: Int, name: String): Int {
        if (name !in names) names += name
        return names.indexOf(name)
    }
    override fun uniform1f(location: Int, x: Float) = log("${name(location)} = $x")
    override fun uniform2f(location: Int, x: Float, y: Float) = log("${name(location)} = $x, $y")
    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) = log("${name(location)} = $x, $y, $z")
    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) =
        log("${name(location)} = $x, $y, $z, $w")
    override fun uniform1i(location: Int, x: Int) = log("${name(location)} = $x")
    override fun uniform2i(location: Int, x: Int, y: Int) = log("${name(location)} = $x, $y")
    override fun uniform4i(location: Int, x: Int, y: Int, z: Int, w: Int) = log("${name(location)} = $x, $y, $z, $w")
    override fun uniformMatrix3fv(location: Int, matrix: FloatArray) = log("${name(location)} = matrix3")
    override fun uniformMatrix4fv(location: Int, matrix: FloatArray) = log("${name(location)} = matrix4")
    override fun activeTexture(unit: Int) = log("unit $unit")
    override fun bindTexture(target: Int, texture: Int) = log("texture $texture")
    override fun texParameteri(target: Int, name: Int, value: Int) = log("parameter $name $value")
    override fun genTexture() = next++.also { log("genTexture $it") }
    override fun deleteTexture(texture: Int) = log("deleteTexture $texture")
    override fun texImage2D(internalFormat: Int, width: Int, height: Int, format: Int, type: Int, pixels: ByteBuffer?) =
        log("texImage $width x $height ${pixels?.remaining()}")
    override fun texSubImage2D(width: Int, height: Int, format: Int, type: Int, pixels: ByteBuffer) =
        log("texSubImage $width x $height ${pixels.remaining()}")
    override fun pixelStorei(name: Int, value: Int) = log("pixelStore $name $value")
    override fun texImage3D(
        internalFormat: Int,
        width: Int,
        height: Int,
        depth: Int,
        format: Int,
        type: Int,
        pixels: ByteBuffer,
    ) = log("texImage3D $width x $height x $depth")
    override fun texStorage2D(internalFormat: Int, width: Int, height: Int) = log("texStorage $width x $height")
    override fun dispatchCompute(x: Int, y: Int, z: Int) = log("dispatch $x $y $z")
    override fun bindImageTexture(unit: Int, texture: Int, format: Int) = log("image $unit $texture")
    override fun memoryBarrier(bits: Int) = log("barrier $bits")
    override fun genBuffer() = next++.also { log("genBuffer $it") }
    override fun deleteBuffer(buffer: Int) = log("deleteBuffer $buffer")
    override fun bufferData(target: Int, buffer: Int, data: ByteBuffer) =
        log("bufferData $target $buffer ${data.remaining()}")
    override fun bindBufferBase(target: Int, index: Int, buffer: Int) = log("bindBufferBase $target $index $buffer")
    override fun uniformBlockBinding(program: Int, name: String, binding: Int) = log("blockBinding $name $binding")
    override fun genFramebuffer() = next++.also { log("genFramebuffer $it") }
    override fun deleteFramebuffer(framebuffer: Int) = log("deleteFramebuffer $framebuffer")
    override fun framebufferTexture2D(texture: Int) = log("attach $texture")

    /** What [readPixels] hands back: by default a picture whose every byte is [readValue]. */
    var readValue: Byte = 0
    override fun readPixels(width: Int, height: Int, into: ByteBuffer) {
        log("read $width x $height")
        for (i in 0 until width * height * 4) into.put(into.position() + i, readValue)
    }
    override fun bindFramebuffer(framebuffer: Int) = log("framebuffer $framebuffer")
    override fun viewport(x: Int, y: Int, width: Int, height: Int) = log("viewport $x $y $width $height")
    override fun drawQuad(program: Int) = log("draw $program")
}

/** A target that is only its numbers. */
class FakeTarget(
    override val texture: Int,
    override val width: Int,
    override val height: Int,
    override val framebuffer: Int = texture + 1000,
) : DrawTarget

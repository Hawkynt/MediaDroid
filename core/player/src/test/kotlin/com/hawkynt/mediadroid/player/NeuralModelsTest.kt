package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralModelsTest {
    @Test
    fun `DAIN comes from the app's model repository, its four networks each as structure and weights`() {
        val dain = NeuralModels.DAIN
        assertEquals("dain", dain.folder)
        assertEquals(
            listOf("depthnet", "flownet", "ctxnet", "interpolation").flatMap { listOf("$it.param", "$it.bin") },
            dain.files.map { it.name },
        )
        assertEquals(
            "https://huggingface.co/Hawkynt/mediadroid-models/resolve/main/dain/flownet.bin",
            dain.url(dain.files.first { it.name == "flownet.bin" }),
        )
        assertEquals(51_477_777L, dain.bytes)
    }

    @Test
    fun `every network on ncnn names its files, Real-ESRGAN four times the size from its compact video model`() {
        assertEquals(NeuralModels.REALESRGAN, NeuralModels.files(PictureAdjustment.Upscaler.NEURAL))
        assertEquals(4, NeuralEnlarging.scale(PictureAdjustment.Upscaler.NEURAL))
        assertEquals(3, NativeInterpolation.enlargerKind(PictureAdjustment.Upscaler.NEURAL))
        assertEquals(NeuralModels.RIFE, NeuralModels.files(PictureAdjustment.Interpolation.RIFE))
        assertEquals(NeuralModels.DAIN, NeuralModels.files(PictureAdjustment.Interpolation.DAIN))
        assertEquals(NeuralModels.WAIFU2X, NeuralModels.files(PictureAdjustment.Upscaler.WAIFU2X))
        assertEquals(NeuralModels.REALSR, NeuralModels.files(PictureAdjustment.Upscaler.REALSR))
        assertEquals(null, NeuralModels.files(PictureAdjustment.Upscaler.FSR))
    }

    @Test
    fun `a downloaded file counts only with its exact size and digest`() {
        val file = NeuralModels.File("a.bin", 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertTrue(file.matches("abc".toByteArray().inputStream()))
        assertFalse(file.matches("abd".toByteArray().inputStream()))
        assertFalse(file.matches("abcd".toByteArray().inputStream()))
    }

    @Test
    fun `XVFI comes from the repository as its seven convolution blocks`() {
        val xvfi = NeuralModels.XVFI
        assertEquals("xvfi", xvfi.folder)
        assertEquals(
            listOf("extract", "context", "bottom", "flow1", "flow2", "flow3", "refine")
                .flatMap { listOf("$it.param", "$it.bin") },
            xvfi.files.map { it.name },
        )
        assertEquals(11_185_958L, xvfi.bytes)
    }

    @Test
    fun `the neural smooth-motion choices are RIFE, DAIN, XVFI, FLAVR, CAIN and IFRNet, RIFE and IFRNet in the app`() {
        assertEquals(
            listOf(
                PictureAdjustment.Interpolation.RIFE,
                PictureAdjustment.Interpolation.DAIN,
                PictureAdjustment.Interpolation.XVFI,
                PictureAdjustment.Interpolation.FLAVR,
                PictureAdjustment.Interpolation.CAIN,
                PictureAdjustment.Interpolation.IFRNET,
            ),
            PictureAdjustment.Interpolation.entries.filter { it.isNeural },
        )
        assertEquals(null, NeuralModels.download(PictureAdjustment.Interpolation.RIFE))
        assertEquals(NeuralModels.DAIN, NeuralModels.download(PictureAdjustment.Interpolation.DAIN))
        assertEquals(NeuralModels.XVFI, NeuralModels.download(PictureAdjustment.Interpolation.XVFI))
        assertEquals(NeuralModels.FLAVR, NeuralModels.download(PictureAdjustment.Interpolation.FLAVR))
        assertEquals(NeuralModels.CAIN, NeuralModels.download(PictureAdjustment.Interpolation.CAIN))
        assertEquals(null, NeuralModels.download(PictureAdjustment.Interpolation.IFRNET))
    }

    @Test
    fun `RealSR is fetched, waifu2x and Real-CUGAN are carried`() {
        assertEquals(NeuralModels.REALSR, NeuralModels.download(PictureAdjustment.Upscaler.REALSR))
        assertEquals(null, NeuralModels.download(PictureAdjustment.Upscaler.WAIFU2X))
        assertEquals(listOf("x4.param", "x4.bin"), NeuralModels.REALSR.files.map { it.name })
        assertEquals(listOf("cain.param", "cain.bin"), NeuralModels.CAIN.files.map { it.name })
    }

    @Test
    fun `FLAVR comes from the repository as its 3D body and 2D head`() {
        assertEquals("flavr", NeuralModels.FLAVR.folder)
        assertEquals(
            listOf("body.param", "body.bin", "head.param", "head.bin"),
            NeuralModels.FLAVR.files.map { it.name },
        )
    }
}

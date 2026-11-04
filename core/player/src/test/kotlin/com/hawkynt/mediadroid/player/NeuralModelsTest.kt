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
    fun `a downloaded file counts only with its exact size and digest`() {
        val file = NeuralModels.File("a.bin", 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertTrue(file.matches("abc".toByteArray().inputStream()))
        assertFalse(file.matches("abd".toByteArray().inputStream()))
        assertFalse(file.matches("abcd".toByteArray().inputStream()))
    }

    @Test
    fun `the neural smooth-motion choices are RIFE and DAIN, only RIFE in the app itself`() {
        assertEquals(
            listOf(PictureAdjustment.Interpolation.RIFE, PictureAdjustment.Interpolation.DAIN),
            PictureAdjustment.Interpolation.entries.filter { it.isNeural },
        )
        assertEquals(null, NeuralModels.download(PictureAdjustment.Interpolation.RIFE))
        assertEquals(NeuralModels.DAIN, NeuralModels.download(PictureAdjustment.Interpolation.DAIN))
    }
}

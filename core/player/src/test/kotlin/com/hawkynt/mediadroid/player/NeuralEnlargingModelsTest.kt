package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralEnlargingModelsTest {
    private val waifu2x = PictureAdjustment.Upscaler.WAIFU2X
    private val cugan = PictureAdjustment.Upscaler.REALCUGAN

    @Test
    fun `waifu2x offers noise removal off and levels 0 to 3, level 1 carried as its default`() {
        val models = NeuralEnlarging.models(waifu2x)
        assertEquals(listOf("Noise off", "Noise 0", "Noise 1", "Noise 2", "Noise 3"), models.map { it.label })
        assertEquals(
            listOf(
                "scale2.0x_model", "noise0_scale2.0x_model", "noise1_scale2.0x_model", "noise2_scale2.0x_model",
                "noise3_scale2.0x_model",
            ),
            models.map { it.file },
        )
        assertTrue(models.all { it.scale == 2 })
        val default = models.single { it.isDefault }
        assertEquals("noise1_scale2.0x_model", default.file)
        assertEquals(NeuralModels.WAIFU2X, default.files)
        assertTrue(models.filter { !it.isDefault }.all { it.files !in NeuralModels.CARRIED })
        assertEquals(
            "https://raw.githubusercontent.com/nihui/waifu2x-ncnn-vulkan/64914665c45893135c9e50c1c296170a121b9f77/" +
                "models/models-cunet/noise3_scale2.0x_model.bin",
            models.last().files.let { it.url(it.files.last()) },
        )
    }

    @Test
    fun `Real-CUGAN offers its SE models at 2x, 3x and 4x and its pro models at 2x and 3x`() {
        val models = NeuralEnlarging.models(cugan)
        assertEquals(
            listOf(
                "2× conservative", "2× no denoise", "2× denoise 1", "2× denoise 2", "2× denoise 3",
                "3× conservative", "3× no denoise", "3× denoise 3",
                "4× conservative", "4× no denoise", "4× denoise 3",
                "Pro 2× conservative", "Pro 2× no denoise", "Pro 2× denoise 3",
                "Pro 3× conservative", "Pro 3× no denoise", "Pro 3× denoise 3",
            ),
            models.map { it.label },
        )
        assertEquals(listOf(2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 4, 2, 2, 2, 3, 3, 3), models.map { it.scale })
        assertEquals(NeuralModels.REALCUGAN, models.single { it.isDefault }.files)
        val pro3 = models.single { it.label == "Pro 3× denoise 3" }
        assertEquals("up3x-denoise3x", pro3.file)
        assertEquals(
            "https://raw.githubusercontent.com/nihui/realcugan-ncnn-vulkan/395302c5c70f1bff604c974e92e0a87e45c9f9ee/" +
                "models/models-pro/up3x-denoise3x.bin",
            pro3.files.url(pro3.files.files.last()),
        )
        assertEquals(models.size, models.map { it.files.folder }.toSet().size)
    }

    @Test
    fun `RealSR has one model, Real-ESRGAN on ncnn its video model by default, the screen's own scalers none`() {
        assertEquals(NeuralModels.REALSR, NeuralEnlarging.models(PictureAdjustment.Upscaler.REALSR).single().files)
        val realEsrgan = NeuralEnlarging.models(PictureAdjustment.Upscaler.NEURAL)
        assertEquals(NeuralModels.REALESRGAN, realEsrgan.single { it.isDefault }.files)
        assertEquals(emptyList<NeuralEnlarging.Model>(), NeuralEnlarging.models(PictureAdjustment.Upscaler.FSR))
    }

    @Test
    fun `a picture's choice is its upscaler's model, the default where none or an unknown one is named`() {
        val plain = NeuralEnlarging.choice(PictureAdjustment(upscaler = waifu2x))!!
        assertTrue(plain.model.isDefault)
        assertEquals("WAIFU2X", plain.key)
        assertEquals("waifu2x", plain.label)
        val gone = NeuralEnlarging.choice(PictureAdjustment(upscaler = waifu2x, upscalerModel = "noise9"))!!
        assertEquals(plain, gone)
        val strong = NeuralEnlarging.choice(
            PictureAdjustment(upscaler = cugan, upscalerModel = "pro-up3x-denoise3x", upscalerTta = true),
        )!!
        assertEquals(3, strong.scale)
        assertEquals("REALCUGAN:pro-up3x-denoise3x+tta", strong.key)
        assertEquals("Real-CUGAN Pro 3× denoise 3, eight ways", strong.label)
        assertEquals(null, NeuralEnlarging.choice(PictureAdjustment(upscaler = PictureAdjustment.Upscaler.FSR)))
    }

    @Test
    fun `a choice's key reads back as the same choice, an unknown one as none`() {
        for (upscaler in PictureAdjustment.Upscaler.entries) {
            for (model in NeuralEnlarging.models(upscaler)) for (tta in listOf(false, true)) {
                val choice = NeuralEnlarging.Choice(model, tta)
                assertEquals(choice, NeuralEnlarging.choice(choice.key))
            }
        }
        assertEquals(null, NeuralEnlarging.choice("WAIFU2X:noise9"))
        assertEquals(null, NeuralEnlarging.choice("NOTHING"))
        assertEquals(null, NeuralEnlarging.choice("FSR"))
    }

    @Test
    fun `a choice put on a picture is what the picture then asks for`() {
        val choice = NeuralEnlarging.choice("WAIFU2X:noise3+tta")!!
        val picture = choice.applied(PictureAdjustment(upscaler = PictureAdjustment.Upscaler.STANDARD))
        assertEquals(waifu2x, picture.upscaler)
        assertEquals("noise3", picture.upscalerModel)
        assertTrue(picture.upscalerTta)
        assertEquals(choice, NeuralEnlarging.choice(picture))
    }

    @Test
    fun `on the CPU the whole picture goes through at once`() {
        assertEquals(0, NeuralEnlarging.tile(NeuralEnlarging.choice("WAIFU2X")!!, 0, 1024, 576))
    }

    @Test
    fun `a GPU takes the whole picture where its memory holds it, else tiles as the ncnn ports size them`() {
        val waifu = NeuralEnlarging.choice("WAIFU2X")!!
        // waifu2x-ncnn-vulkan: 400 above 2600 MB, 200 above 740, 100 above 250, else 32.
        assertEquals(0, NeuralEnlarging.tile(waifu, 10_000, 1024, 576))
        assertEquals(400, NeuralEnlarging.tile(waifu, 3_000, 1024, 576))
        assertEquals(200, NeuralEnlarging.tile(waifu, 1_000, 1024, 576))
        assertEquals(100, NeuralEnlarging.tile(waifu, 300, 1024, 576))
        assertEquals(32, NeuralEnlarging.tile(waifu, 100, 1024, 576))
        assertEquals(0, NeuralEnlarging.tile(waifu, 3_000, 256, 256))
        // realcugan-ncnn-vulkan's table per scale: at 4x 300 above 980 MB, at 3x 200 above 950.
        val four = NeuralEnlarging.choice("REALCUGAN:se-up4x-conservative")!!
        val three = NeuralEnlarging.choice("REALCUGAN:se-up3x-conservative")!!
        assertEquals(300, NeuralEnlarging.tile(four, 1_000, 1024, 576))
        assertEquals(200, NeuralEnlarging.tile(three, 1_000, 1024, 576))
        // realsr-ncnn-vulkan: 200 above 1900 MB, 100 above 550, 64 above 190.
        assertEquals(64, NeuralEnlarging.tile(NeuralEnlarging.choice("REALSR")!!, 200, 1024, 576))
    }
}

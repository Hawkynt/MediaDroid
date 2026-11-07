package com.hawkynt.mediadroid.player

import android.content.Context
import com.hawkynt.mediadroid.data.ModelStore

/** DDColor on this device (NativeColorizer; see app/THIRD-PARTY-NOTICE.DDColor.txt), from its fetched files. */
object NeuralColorizer {
    /** DDColor on [unit] (the GPU or the CPU); null where that unit cannot run it or its files are not there. */
    fun open(context: Context, unit: NeuralUpscaler.Unit): NativeColorizer? {
        if (unit == NeuralUpscaler.Unit.NPU || !AndroidNeural.loaded) return null
        val gpu = unit == NeuralUpscaler.Unit.GPU
        if (gpu && !NativeInterpolation.hasGpu()) return null
        if (!ModelStore.ready(context, NeuralModels.DDCOLOR)) return null
        return NativeColorizer.open(ModelStore.folder(context, NeuralModels.DDCOLOR).absolutePath, gpu)
    }

    /**
     * The self-test of DDColor on [unit] (NeuralChecks.colorizer): whether the unit colours right and what one picture
     * takes there. Null where the unit cannot run it, or runs it wrong. The run is told to [progress].
     */
    fun selfTest(
        context: Context,
        unit: NeuralUpscaler.Unit,
        progress: NeuralChecks.Progress,
    ): NeuralSelfTest.Timing? = open(context, unit)?.use { net ->
        NeuralChecks.colorizer(net, unit.name, progress) { android.util.Log.i("NeuralColorizer", it) }
    }
}

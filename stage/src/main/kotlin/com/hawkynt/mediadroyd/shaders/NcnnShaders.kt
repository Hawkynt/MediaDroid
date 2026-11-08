package com.hawkynt.mediadroyd.shaders

/**
 * The compute shaders of glsl/ncnn by the name the native networks ask for them (native/src/shaders.h): the file name
 * without its extension. Handed to the native part once, before a network loads (NativeInterpolation.load).
 */
object NcnnShaders {
    val ALL: Map<String, String> = mapOf(
        "warp" to Shaders.NCNN_WARP,
        "dain-correlation" to Shaders.NCNN_DAIN_CORRELATION,
        "dain-flow-warp" to Shaders.NCNN_DAIN_FLOW_WARP,
        "dain-projection-zero" to Shaders.NCNN_DAIN_PROJECTION_ZERO,
        "dain-projection-project" to Shaders.NCNN_DAIN_PROJECTION_PROJECT,
        "dain-projection-average" to Shaders.NCNN_DAIN_PROJECTION_AVERAGE,
        "dain-projection-fill-hole" to Shaders.NCNN_DAIN_PROJECTION_FILL_HOLE,
        "dain-filter-interpolation" to Shaders.NCNN_DAIN_FILTER_INTERPOLATION,
        "dain-filter-interpolation-pack4" to Shaders.NCNN_DAIN_FILTER_INTERPOLATION_PACK4,
    )
}

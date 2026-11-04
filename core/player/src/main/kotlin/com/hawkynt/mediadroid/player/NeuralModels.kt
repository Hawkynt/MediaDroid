package com.hawkynt.mediadroid.player

import java.io.InputStream
import java.security.MessageDigest

/**
 * The neural networks too large to carry in the app: downloaded on demand from the app's model repository
 * ([REPOSITORY], huggingface.co/Hawkynt/mediadroid-models), each file kept only with its exact size and digest.
 */
object NeuralModels {
    /** Where the files are fetched from: one folder per network. */
    const val REPOSITORY = "https://huggingface.co/Hawkynt/mediadroid-models/resolve/main"

    /** One file of a network: its name, size and SHA-256 digest (hex). */
    data class File(val name: String, val bytes: Long, val sha256: String) {
        /** Whether [content] is this file: the exact size and digest. */
        fun matches(content: InputStream): Boolean {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1 shl 16)
            var read = 0L
            content.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    read += n
                }
            }
            return read == bytes && digest.digest().joinToString("") { "%02x".format(it) } == sha256
        }
    }

    /** A network to download: its [folder] in the repository and its [files]. */
    data class Download(val folder: String, val files: List<File>) {
        /** Where [file] is fetched from. */
        fun url(file: File): String = "$REPOSITORY/$folder/${file.name}"

        /** All of it, in bytes. */
        val bytes: Long get() = files.sumOf { it.bytes }
    }

    /** DAIN, as nihui's dain-ncnn-vulkan converted it (MIT; models/best), copied unchanged. */
    val DAIN = Download(
        "dain",
        listOf(
            File("depthnet.param", 23_625, "e5b2824a8efd13fcc8875456df47f723682f3b38ebac897c7ad06c4450cd15c0"),
            File("depthnet.bin", 10_728_564, "4d063853f50ecb7835e9c28c04087f9256541a276478925d5b062e4ab8e07355"),
            File("flownet.param", 17_852, "ce1e6c4e86e21de63e6e94ddf37d8bc1f01ae1a3d7a983b5513df338ccc54d91"),
            File("flownet.bin", 22_092_552, "f6d5f47e84d0b25d9fff06c5acb382bef8281097188fd3a240501e754e95e2cb"),
            File("ctxnet.param", 1_254, "85fc9a1fbf2d398b02edceaae2081ff1716edb44afd980c4928dacb44856f929"),
            File("ctxnet.bin", 313_748, "957faa2e2cd475648166f056f65963136d24e4ccfcc665e367d7868b2f56d3e4"),
            File("interpolation.param", 9_066, "9475d3368d092e8074189c44baa11fd5e357bf930188797fb2b4b899333cfdc1"),
            File("interpolation.bin", 18_291_116, "485ca7ffb0eb86e9468e9841356c0af5c55070dde1901a3628ce98baf5d28b6d"),
        ),
    )

    /**
     * XVFI, the Vimeo-trained XVFI-Net (research and education only, used with its authors' permission), as
     * tools/xvfi/export.py writes its convolution blocks out for ncnn.
     */
    val XVFI = Download(
        "xvfi",
        listOf(
            File("extract.param", 975, "eaf251d3619671ba0d99ce0ff838b44d978498f6c33ad3d0c0e4a8f82dfd2787"),
            File("extract.bin", 447_644, "adb5a7ecc41098826125ee796482f2156c737b2a066f23a4a6b932c57a3728e4"),
            File("context.param", 148, "a8ac821a0d05a12d35197c0282fdba9aab4a1f67857c4e9ad1fd4e94ccdf780c"),
            File("context.bin", 73_988, "daa636956605a852ec9d2fc6650d108ae2ecc3cdaef7ea0c6c7462e92378d1d2"),
            File("bottom.param", 678, "f6982dde65e8664b26cdf377aef45908c5050c3ec1a0863b9811d26709f5a74d"),
            File("bottom.bin", 2_319_404, "1ca55e7e0bfa87e9967f6a89526d922f2a84b8abdb7226c5a070baec371a386c"),
            File("flow1.param", 148, "b355568bc5efe83f80c6cd4390d2c862aa07b6825e8118650b42fac11456c888"),
            File("flow1.bin", 147_716, "d70c51f5d72685bf77fe5a0d5562926a401810801cf34c8039d37aca49e8ac1a"),
            File("flow2.param", 678, "6e9c20c06c340a92c30402fb6bf14d30f73b1fc6f690f1f3f507dbb53b1f258b"),
            File("flow2.bin", 2_335_788, "1cb8e027b68bf8585575ede3f7e23c3c142586a18ac768e1cbd71663bde37cac"),
            File("flow3.param", 772, "06a700dcdd7d35a43ef4f3f10eb247a3efba43fb22ecaeed5a808b1f38c4d220"),
            File("flow3.bin", 2_088_488, "d2876d4e581f0658cc4ac6e84a60a396a692d7007aa87585bef0287376b3e77a"),
            File("refine.param", 1_167, "82fb6b8f2e03adfe966117cf4fc5e4da3c03733d2aa5cca584656ba61b02cc35"),
            File("refine.bin", 3_768_364, "65814b891c5d4eb17c807ba0b894e41d88e9e8567ef322a6d74b56fef9cbfb53"),
        ),
    )

    /** What [network] needs downloaded; null for a network the app carries itself. */
    fun download(network: PictureAdjustment.Interpolation): Download? = when (network) {
        PictureAdjustment.Interpolation.DAIN -> DAIN
        PictureAdjustment.Interpolation.XVFI -> XVFI
        else -> null
    }
}

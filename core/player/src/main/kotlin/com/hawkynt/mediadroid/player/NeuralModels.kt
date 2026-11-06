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

    /**
     * FLAVR, the 2x model (Apache-2.0), as tools/flavr/export.py writes it out for ncnn: its 3D part as 2D layers
     * over the four frames, and its 2D head.
     */
    val FLAVR = Download(
        "flavr",
        listOf(
            File("body.param", 33_394, "dc2da53ed365ef18d7f1ca72e572aa6bff81a2d2c4b2ac439c995bfb5f467ef6"),
            File("body.bin", 84_077_848, "3c09b1938d55530e65aaec54f4c54022c38d52a2340b1bc40b8fa751f2b56350"),
            File("head.param", 304, "c5aa1dc47fbc382d97d58eef6d0de22995c49afb63ebaee98408861d1bf3bddb"),
            File("head.bin", 51_604, "1737f4ddf5a086eab6562192fdfcfec4cf09de8cddb9e8596c76c6a71a31e76c"),
        ),
    )

    /** CAIN, as nihui's cain-ncnn-vulkan converted it (MIT; models/cain), copied unchanged. */
    val CAIN = Download(
        "cain",
        listOf(
            File("cain.param", 60_912, "bb652c63ce444c010f47c0767964880453315bd4a5b9b172fc25667899c23986"),
            File("cain.bin", 85_635_100, "4fcb9e35dd1bab1ef4b42cd04b299030e11213a14d1067c9c7ec3896dd6c3536"),
        ),
    )

    /** RealSR's DF2K_JPEG model, as nihui's realsr-ncnn-vulkan converted it (MIT; models-DF2K_JPEG), unchanged. */
    val REALSR = Download(
        "realsr",
        listOf(
            File("x4.param", 115_993, "a7f31e75518ad279c179202bf77da9eca3141124a67e5261d97235849e6cc323"),
            File("x4.bin", 33_424_520, "e862e88c3f346a3b8506d839633f5e2a16461bb8bfb3e211152f40d807a4f51c"),
        ),
    )

    /** RIFE 4.25 lite as rife-ncnn-vulkan runs it (MIT): carried in the app (app/src/main/assets/models). */
    val RIFE = Download(
        "rife-4.25-lite",
        listOf(
            File("flownet.param", 35_830, "e55b76f197b89637dc824ce89e64878df419214d3cf6a10aea0283c61b93a908"),
            File("flownet.bin", 11_276_252, "350a15e464bea5ad378e06c0fb43996e90a0d35653d5a6ef6bc980d832538fb7"),
        ),
    )

    /** IFRNet's S model as ifrnet-ncnn-vulkan runs it (MIT): carried in the app. */
    val IFRNET = Download(
        "ifrnet-s",
        listOf(
            File("ifrnet.param", 13_490, "fc684bb14ca0135adb7016125d5ecfcdd18f30922fd9937a64346804c909d6ae"),
            File("ifrnet.bin", 5_921_992, "7c3fe0a5eb7e544bc8c615ee47d4bafcbc077f98d75eda217c9657979adb1e14"),
        ),
    )

    /** waifu2x's CUnet as waifu2x-ncnn-vulkan runs it (MIT): carried in the app. */
    val WAIFU2X = Download(
        "waifu2x-cunet",
        listOf(
            File(
                "noise1_scale2.0x_model.param", 7_249,
                "ccc3b2f1c9fad829ecdda40cac8dc853fcddf14917170efba888b256fbf9074a",
            ),
            File(
                "noise1_scale2.0x_model.bin", 2_776_400,
                "862c0e40f3df956ff57bd4d168c85dea5d375b614077fdddd3ac8cfdb9a686da",
            ),
        ),
    )

    /** Real-CUGAN's SE model as realcugan-ncnn-vulkan runs it (MIT): carried in the app. */
    val REALCUGAN = Download(
        "realcugan-se",
        listOf(
            File("up2x-conservative.param", 4_761, "4b3b5c5710ad1d00503c8243417aa75f13fe497723e8b4952e8b1abfc08f9b84"),
            File(
                "up2x-conservative.bin", 2_573_648,
                "91c72c136e7ff8556323d4449c5ceeff22d9829bf8463a01137cadb8d59b84a0",
            ),
        ),
    )

    /**
     * The networks the app carries itself, by folder: on Android its assets, on the desktop resources unpacked into
     * the app-data folder on first use, every file checked against its digest there (see ModelFolder.unpack).
     */
    val CARRIED = listOf(RIFE, IFRNET, WAIFU2X, REALCUGAN)

    /** The files [network] runs from: carried or downloaded; null for one that is no network. */
    fun files(network: PictureAdjustment.Interpolation): Download? = when (network) {
        PictureAdjustment.Interpolation.RIFE -> RIFE
        PictureAdjustment.Interpolation.IFRNET -> IFRNET
        else -> download(network)
    }

    /** The files [upscaler] runs from: carried or downloaded; null for one that is no network on ncnn. */
    fun files(upscaler: PictureAdjustment.Upscaler): Download? = when (upscaler) {
        PictureAdjustment.Upscaler.WAIFU2X -> WAIFU2X
        PictureAdjustment.Upscaler.REALCUGAN -> REALCUGAN
        else -> download(upscaler)
    }

    /** What [upscaler] needs downloaded; null for one the app carries itself. */
    fun download(upscaler: PictureAdjustment.Upscaler): Download? =
        if (upscaler == PictureAdjustment.Upscaler.REALSR) REALSR else null

    /** What [network] needs downloaded; null for a network the app carries itself. */
    fun download(network: PictureAdjustment.Interpolation): Download? = when (network) {
        PictureAdjustment.Interpolation.DAIN -> DAIN
        PictureAdjustment.Interpolation.XVFI -> XVFI
        PictureAdjustment.Interpolation.FLAVR -> FLAVR
        PictureAdjustment.Interpolation.CAIN -> CAIN
        else -> null
    }
}

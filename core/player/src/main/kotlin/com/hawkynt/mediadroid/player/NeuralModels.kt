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

    /** What [network] needs downloaded; null for a network the app carries itself. */
    fun download(network: PictureAdjustment.Interpolation): Download? = when (network) {
        PictureAdjustment.Interpolation.DAIN -> DAIN
        else -> null
    }
}

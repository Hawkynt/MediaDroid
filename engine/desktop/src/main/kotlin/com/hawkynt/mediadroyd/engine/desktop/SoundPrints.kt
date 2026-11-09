package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.player.IntroCredits
import com.hawkynt.mediadroyd.player.NativeFingerprint
import com.hawkynt.mediadroyd.library.FsFile
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A video's sound fingerprinted on the desktop for finding its intro and credits (PlayerPlatform.soundPrint): FFmpeg
 * decodes it (MediaReader, its pictures left alone) as 16-bit stereo at MediaReader.SAMPLE_RATE and Chromaprint
 * fingerprints it (NativeFingerprint, inside the native library).
 */
object SoundPrints {
    /**
     * The video at [path] ([from] a network source where it lives on one): its first IntroCredits.OPENING_MS, or where
     * [closing] its last IntroCredits.CLOSING_MS; null where it cannot be read or the native library is missing.
     */
    fun of(path: String, from: FsFile?, closing: Boolean): IntroCredits.SoundPrint? {
        if (!NativeNetworks.loaded) return null
        return runCatching {
            MediaReader(path, from, decoders = emptyList(), pictures = false).use { reader ->
                val startMs = if (closing) (reader.durationMs - IntroCredits.CLOSING_MS).coerceAtLeast(0) else 0
                val endMs = startMs + if (closing) IntroCredits.CLOSING_MS else IntroCredits.OPENING_MS
                if (startMs > 0) reader.seek(startMs)
                val pcm = ByteArrayOutputStream()
                // Where the sound handed out begins: after a seek, the first piece may start a little before startMs.
                var firstMs = -1L
                while (true) {
                    val item = reader.read() ?: break
                    val sound = item as? MediaReader.Sound ?: continue
                    if (sound.ptsMs >= endMs) break
                    if (firstMs < 0) firstMs = sound.ptsMs
                    pcm.write(sound.pcm)
                }
                val bytes = pcm.toByteArray()
                if (bytes.isEmpty()) return null
                val samples = ShortArray(bytes.size / 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                NativeFingerprint.fingerprint(samples, MediaReader.SAMPLE_RATE, 2)
                    ?.let { IntroCredits.SoundPrint(it, firstMs) }
            }
        }.getOrNull()
    }
}

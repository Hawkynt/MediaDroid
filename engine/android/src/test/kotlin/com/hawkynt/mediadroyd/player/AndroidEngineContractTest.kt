package com.hawkynt.mediadroyd.player

import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import com.hawkynt.mediadroyd.engine.MediaRequest
import com.hawkynt.mediadroyd.engine.PlaybackEngine
import com.hawkynt.mediadroyd.engine.PlaybackEngineContract
import com.hawkynt.mediadroyd.engine.PlaybackTuning
import com.hawkynt.mediadroyd.engine.VideoOutput
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Android engine passes the contract every engine passes: the real ExoPlayer as the app builds it, under
 * Robolectric with its pass-through codecs (Media3's own tests' ShadowMediaCodecConfig), playing contract/clip.mp4 —
 * three seconds of H.264 and two AAC tracks, made with FFmpeg's test source and sine generator. Time is Robolectric's:
 * [pass] moves the main looper's clock on, and the player's thread with it. Robolectric's sandbox takes seconds to
 * start, so this runs with the screenshots' tier (-Pscreenshots).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidEngineContractTest : PlaybackEngineContract() {

    @get:Rule
    val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private val clip: String by lazy {
        // Copied out of the test resources: Media3 reads a file by its path.
        val file = File.createTempFile("contract", ".mp4").apply { deleteOnExit() }
        javaClass.getResourceAsStream("/contract/clip.mp4")!!.use { input -> file.outputStream().use(input::copyTo) }
        file.path
    }

    override fun newEngine(): PlaybackEngine = AndroidPlaybackEngine(
        RuntimeEnvironment.getApplication(),
        AndroidPlaybackEngine.Options(),
        PlaybackTuning.Mutable(),
        files = { null },
    )

    override val media: MediaRequest get() = MediaRequest(clip)

    override fun output(): VideoOutput = AndroidPlaybackEngine.SurfaceOutput(Surface(SurfaceTexture(1)))

    override fun pass(engine: PlaybackEngine, ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
        Thread.sleep(1)
    }
}

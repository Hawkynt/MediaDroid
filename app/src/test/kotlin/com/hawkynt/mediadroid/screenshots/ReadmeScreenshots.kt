package com.hawkynt.mediadroid.screenshots

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.github.takahirom.roborazzi.captureRoboImage
import com.hawkynt.mediadroid.data.ResumeInfo
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.ui.MediaDroidApp
import com.hawkynt.mediadroid.ui.theme.MediaDroidTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The README's screenshots, taken from the real app running on the generated [DemoLibrary]: the browse
 * wall, a show's details, the filters, the Files browser and the player on an episode of a binge queue. Run with
 * `./gradlew :app:recordRoborazziDebug -Pscreenshots`; the files land in `screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class ReadmeScreenshots {

    @get:Rule
    val compose = createComposeRule()

    private val out = File(System.getProperty("user.dir"), "../screenshots").canonicalFile
    private lateinit var store: StateStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "demo-library")
        root.deleteRecursively()
        DemoLibrary.write(root)
        // Pictures load on the test thread, so a capture never catches them half-way.
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).coroutineContext(Dispatchers.Unconfined).build())

        store = StateStore(context)
        store.data.rootPath = root.absolutePath
        store.data.sidebarExpanded = false
        // Something to continue: one film half-way, one episode just begun.
        val movie = File(root, "Movies/Deep Blue Silence (2014)/Deep Blue Silence (2014).mkv").absolutePath
        val episode = File(root, "TV Shows/Starlight Bakery (2020)/Season 01/S01E04 - Flour Power.mkv").absolutePath
        store.data.resume[movie] = ResumeInfo(positionMs = 3_100_000, durationMs = 6_200_000)
        store.data.resume[episode] = ResumeInfo(positionMs = 300_000, durationMs = 1_500_000)
        store.save()

        compose.setContent { MediaDroidTheme { MediaDroidApp(store, demo = true) } }
        waitFor(hasText("More info"))
    }

    private fun waitFor(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        try {
            compose.waitUntil(timeoutMillis = 20_000) {
                compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            compose.onRoot(useUnmergedTree = true).printToLog("SCREEN")
            System.err.println(compose.onRoot(useUnmergedTree = true).printToString(Int.MAX_VALUE))
            throw e
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage(File(out, name).path)

    private fun tap(matcher: androidx.compose.ui.test.SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true)[0].performClick()

    @Test
    fun browseWall() = capture("main.png")

    @Test
    fun showDetails() {
        tap(hasContentDescription("TV Shows"))
        // The hero shows what is being watched, Starlight Bakery; More info opens it.
        waitFor(hasText("All TV Shows"))
        tap(hasText("More info"))
        waitFor(hasText("Play next"))
        tap(hasText("Season 1"))
        waitFor(hasText("Grand Opening"))
        capture("detail.png")
    }

    @Test
    fun filters() {
        tap(hasContentDescription("Filters"))
        compose.waitForIdle()
        capture("filters.png")
    }

    @Test
    fun files() {
        // Storage as it is: a season folder with its real file names, NFO titles, thumbs and marks.
        tap(hasContentDescription("Files"))
        listOf("Internal storage", "TV Shows", "Starlight Bakery (2020)", "Season 01").forEach {
            waitFor(hasText(it))
            tap(hasText(it))
        }
        waitFor(hasText("Flour Power"))
        capture("files.png")
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun player() {
        // A show's binge queue, picked up where it was left: Starlight Bakery, episode 4.
        tap(hasContentDescription("TV Shows"))
        waitFor(hasText("Starlight Bakery"))
        tap(hasText("More info"))
        waitFor(hasContentDescription("Back"))
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Play next"))
        tap(hasText("Play next"))
        compose.waitForIdle()
        // A tap on the picture brings the controls up: title, timeline, previous and next episode.
        compose.onRoot().performClick()
        waitFor(hasContentDescription("Playback and picture"))
        capture("player.png")
    }
}

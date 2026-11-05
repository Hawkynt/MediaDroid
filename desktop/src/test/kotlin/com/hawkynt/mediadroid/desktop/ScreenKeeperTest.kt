package com.hawkynt.mediadroid.desktop

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ScreenKeeperTest {

    private fun locks(): String =
        ProcessBuilder("systemd-inhibit", "--list", "--no-pager").redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText()

    private fun waitFor(condition: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < until) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun `on Linux, while something plays the system holds an idle lock in MediaDroid's name, and lets go after`() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux") && File("/usr/bin/systemd-inhibit").canExecute())
        assumeTrue(runCatching { locks() }.getOrNull()?.contains("WHO") == true)
        // A machine without a user session (a CI runner) may grant a lock and still list none: the test then has
        // nothing to look at. A probe lock, held a moment, shows which kind of machine this is.
        val probe = runCatching {
            ProcessBuilder("systemd-inhibit", "--what=idle", "--who=MediaDroid-probe", "--why=probe", "sleep", "5")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull()
        val listed = probe != null && waitFor { locks().contains("MediaDroid-probe") }
        probe?.descendants()?.forEach { it.destroy() }
        probe?.destroy()
        probe?.waitFor()
        assumeTrue(listed)
        waitFor { !locks().contains("MediaDroid-probe") }
        ScreenKeeper.forThisSystem().use { keeper ->
            keeper.keepOn(true)
            assertTrue(waitFor { locks().lines().any { it.startsWith("MediaDroid ") } })
            keeper.keepOn(false)
            assertTrue(waitFor { !locks().lines().any { it.startsWith("MediaDroid ") } })
        }
    }
}

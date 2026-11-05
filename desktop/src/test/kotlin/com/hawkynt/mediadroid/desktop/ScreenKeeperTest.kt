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
        // A machine without a user session (a CI runner) lists locks but grants none.
        val granted = runCatching {
            ProcessBuilder("systemd-inhibit", "--what=idle", "--who=probe", "--why=probe", "true")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0
        }.getOrDefault(false)
        assumeTrue(granted)
        ScreenKeeper.forThisSystem().use { keeper ->
            keeper.keepOn(true)
            assertTrue(waitFor { locks().contains("MediaDroid") })
            keeper.keepOn(false)
            assertTrue(waitFor { !locks().contains("MediaDroid") })
        }
    }
}

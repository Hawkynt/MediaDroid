package com.hawkynt.mediadroyd.engine.desktop

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class FfmpegNativesTest {

    /**
     * In a JVM of its own, since a JVM loads FFmpeg's natives once: a thread whose interrupt flag is set (the playback
     * engine's reader, stopped while it starts) still loads them, and keeps its flag. Without [FfmpegNatives] JavaCPP's
     * file lock on its cache gives up with FileLockInterruptionException, and FFmpeg stays unusable in that JVM.
     */
    @Test
    fun `an interrupted thread still loads FFmpeg, and keeps its interrupt`() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), "com.hawkynt.mediadroyd.engine.desktop.InterruptedLoadKt",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "the child JVM did not finish" }
        assertEquals(output, "loaded, still interrupted", output.lines().last { it.isNotBlank() })
    }
}

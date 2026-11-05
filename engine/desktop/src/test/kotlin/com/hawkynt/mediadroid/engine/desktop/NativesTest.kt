package com.hawkynt.mediadroid.engine.desktop

import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.junit.Assert.assertTrue
import org.junit.Test

class NativesTest {
    @Test
    fun `FFmpeg's natives load, the build the Gradle property asks for`() {
        val version = avformat.avformat_version()
        assertTrue("libavformat $version", version > 0)
        val license = avutil.avutil_license().string
        val wanted = System.getProperty("mediadroid.ffmpegLicence", "LGPL")
        // "GPL version 3 or later" for the full build, "LGPL version 3 or later" for the published one.
        assertTrue("licence: $license, wanted $wanted", license.startsWith(wanted))
    }
}

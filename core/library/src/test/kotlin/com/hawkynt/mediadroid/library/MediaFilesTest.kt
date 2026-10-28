package com.hawkynt.mediadroid.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFilesTest {

    @Test
    fun everyContainerMedia3ReadsCountsAsVideo() {
        listOf("a.mp4", "a.MKV", "a.webm", "a.avi", "a.ts", "a.m2ts", "a.mpg", "a.vob", "a.ogv", "a.flv", "a.mov")
            .forEach { assertTrue(it, MediaFiles.isVideo(it)) }
    }

    @Test
    fun camcorderAndDvdRecorderFilesAreMpegProgramStreamsAndPlay() {
        listOf("MOV001.MOD", "MOV001.TOD", "VR_MOVIE.VRO", "clip.ogx").forEach { assertTrue(it, MediaFiles.isVideo(it)) }
    }

    @Test
    fun containersTheBundledFfmpegDemuxesCountAsVideoToo() {
        listOf(
            "a.wmv", "a.asf", "a.wtv", "a.dvr-ms", "a.rm", "a.rmvb", "a.dv", "a.dif", "a.nut", "a.mxf",
            "a.nuv", "a.m2v", "a.264", "a.h264", "a.hevc", "a.h265", "a.y4m", "a.ivf",
        ).forEach { assertTrue(it, MediaFiles.isVideo(it)) }
    }

    @Test
    fun audioSubtitlesSidecarsAndPostScriptAreNoVideos() {
        listOf("a.ps", "a.mka", "a.wma", "a.ra", "a.srt", "a.nfo", "a.jpg", "noext")
            .forEach { assertFalse(it, MediaFiles.isVideo(it)) }
    }
}

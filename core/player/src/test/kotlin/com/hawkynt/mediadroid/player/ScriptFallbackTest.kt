package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScriptFallbackTest {
    @Test
    fun `text in Latin script calls for no fallback`() {
        assertNull(ScriptFallback.language("Hello, world! Ça va? Привет", "en-US"))
        assertNull(ScriptFallback.language("{\\an8\\fs40}Sign — «quoted»", "ja-JP"))
    }

    @Test
    fun `kana is Japanese and Hangul Korean whatever the device speaks`() {
        assertEquals(ScriptFallback.Pick("ja", true), ScriptFallback.language("日本語の字幕", "en-US"))
        assertEquals(ScriptFallback.Pick("ja", true), ScriptFallback.language("ｶﾀｶﾅ", "zh-CN"))
        assertEquals(ScriptFallback.Pick("ko", true), ScriptFallback.language("한국어 자막", "ja-JP"))
        assertEquals(ScriptFallback.Pick("zh-Hant", true), ScriptFallback.language("ㄅㄆㄇ", "en-US"))
    }

    @Test
    fun `Chinese characters alone follow the device's language, else Simplified Chinese`() {
        assertEquals(ScriptFallback.Pick("zh-Hans", false), ScriptFallback.language("中文字幕", "en-US"))
        assertEquals(ScriptFallback.Pick("ja", false), ScriptFallback.language("中文字幕", "ja-JP"))
        assertEquals(ScriptFallback.Pick("ko", false), ScriptFallback.language("中文字幕", "ko"))
        assertEquals(ScriptFallback.Pick("zh-Hant", false), ScriptFallback.language("中文字幕", "zh-TW"))
        assertEquals(ScriptFallback.Pick("zh-Hant", false), ScriptFallback.language("中文字幕", "zh-Hant-HK"))
        assertEquals(ScriptFallback.Pick("zh-Hans", false), ScriptFallback.language("中文字幕", "zh-CN"))
        // An ideograph outside the Basic Multilingual Plane counts as well.
        assertEquals(ScriptFallback.Pick("zh-Hans", false), ScriptFallback.language("𠀋", "de-DE"))
    }

    @Test
    fun `a track settles on its language as its lines arrive`() {
        val track = ScriptFallback.Track("en-US")
        assertNull(track.see("Opening"))
        assertEquals("zh-Hans", track.see("日本"))
        assertNull(track.see("日本"))
        // The first kana settles it: Japanese, and nothing moves it after.
        assertEquals("ja", track.see("ですか"))
        assertNull(track.see("中文"))
        assertNull(track.see("한국어"))
    }

    @Test
    fun `the device's fallback families are read from fonts xml`() {
        val families = ScriptFallback.families(FONTS_XML)
        assertEquals(
            listOf(
                ScriptFallback.Family(listOf("und-Arab"), ScriptFallback.Face("NotoNaskhArabic-Regular.ttf", 0)),
                ScriptFallback.Family(listOf("zh-Hans"), ScriptFallback.Face("NotoSansCJK-Regular.ttc", 2)),
                ScriptFallback.Family(listOf("zh-Hant", "zh-Bopo"), ScriptFallback.Face("NotoSansCJK-Regular.ttc", 3)),
                ScriptFallback.Family(listOf("ja"), ScriptFallback.Face("NotoSansCJK-Regular.ttc", 0)),
                ScriptFallback.Family(listOf("ko"), ScriptFallback.Face("NotoSansCJK-Regular.ttc", 1)),
            ),
            families,
        )
        assertEquals(emptyList<ScriptFallback.Family>(), ScriptFallback.families("not xml <"))
    }

    @Test
    fun `each language takes its own face where the device has it`() {
        val families = ScriptFallback.families(FONTS_XML)
        val all: (String) -> Boolean = { true }
        assertEquals(ScriptFallback.Face("NotoSansCJK-Regular.ttc", 0), ScriptFallback.face("ja", families, all))
        assertEquals(ScriptFallback.Face("NotoSansCJK-Regular.ttc", 1), ScriptFallback.face("ko", families, all))
        assertEquals(ScriptFallback.Face("NotoSansCJK-Regular.ttc", 2), ScriptFallback.face("zh-Hans", families, all))
        assertEquals(ScriptFallback.Face("NotoSansCJK-Regular.ttc", 3), ScriptFallback.face("zh-Hant", families, all))
    }

    @Test
    fun `a device with separate files per language, or only Droid's fallback, is served too`() {
        val split = ScriptFallback.families(
            """
            <familyset>
                <family lang="zh-Hans"><font weight="400" style="normal">NotoSansSC-Regular.otf</font></family>
                <family lang="ja"><font weight="400" style="normal">NotoSansJP-Regular.otf</font></family>
                <family><font weight="400" style="normal">DroidSansFallback.ttf</font></family>
            </familyset>
            """.trimIndent(),
        )
        val all: (String) -> Boolean = { true }
        assertEquals(ScriptFallback.Face("NotoSansJP-Regular.otf", 0), ScriptFallback.face("ja", split, all))
        // No Korean face: another CJK one draws the ideographs at least.
        assertEquals(ScriptFallback.Face("NotoSansSC-Regular.otf", 0), ScriptFallback.face("ko", split, all))
        // No fonts.xml at all: the files Android has carried by name, those that are there.
        assertEquals(
            ScriptFallback.Face("NotoSansCJK-Regular.ttc", 1),
            ScriptFallback.face("ko", emptyList()) { it == "NotoSansCJK-Regular.ttc" },
        )
        assertEquals(
            ScriptFallback.Face("DroidSansFallback.ttf", 0),
            ScriptFallback.face("ja", emptyList()) { it == "DroidSansFallback.ttf" },
        )
        assertNull(ScriptFallback.face("ja", emptyList()) { false })
        // A file fonts.xml names but the device does not have is passed over.
        assertEquals(
            ScriptFallback.Face("NotoSansSC-Regular.otf", 0),
            ScriptFallback.face("ja", split) { it != "NotoSansJP-Regular.otf" },
        )
    }

    private companion object {
        // The shape of AOSP's frameworks/base/data/fonts/fonts.xml (Android 8 to 14), cut down.
        val FONTS_XML = """
            <?xml version="1.0" encoding="utf-8"?>
            <familyset version="23">
                <family name="sans-serif">
                    <font weight="400" style="normal">Roboto-Regular.ttf
                      <axis tag="wght" stylevalue="400" />
                    </font>
                </family>
                <alias name="arial" to="sans-serif" />
                <family lang="und-Arab" variant="elegant">
                    <font weight="400" style="normal" postScriptName="NotoNaskhArabic">NotoNaskhArabic-Regular.ttf</font>
                </family>
                <family lang="zh-Hans">
                    <font weight="400" style="normal" index="2" postScriptName="NotoSansCJKjp-Regular">
                        NotoSansCJK-Regular.ttc
                    </font>
                    <font weight="400" style="normal" index="2" fallbackFor="serif"
                          postScriptName="NotoSerifCJKjp-Regular">NotoSerifCJK-Regular.ttc</font>
                </family>
                <family lang="zh-Hant zh-Bopo">
                    <font weight="400" style="normal" index="3" fallbackFor="serif">NotoSerifCJK-Regular.ttc</font>
                    <font weight="400" style="normal" index="3">NotoSansCJK-Regular.ttc</font>
                </family>
                <family lang="ja">
                    <font weight="400" style="normal" index="0">NotoSansCJK-Regular.ttc</font>
                </family>
                <family lang="ko">
                    <font weight="700" style="normal" index="1">NotoSansCJK-Bold.ttc</font>
                    <font weight="400" style="normal" index="1">NotoSansCJK-Regular.ttc</font>
                </family>
            </familyset>
        """.trimIndent()
    }
}

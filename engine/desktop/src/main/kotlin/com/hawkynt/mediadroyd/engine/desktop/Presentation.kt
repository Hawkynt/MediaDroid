package com.hawkynt.mediadroyd.engine.desktop

/**
 * How the stage hands its finished picture to the window (README "Platform support"; the measurements behind the choice
 * are `./gradlew :desktop:presentationBench`'s). Each but [NATIVE] reads the picture back into memory, where the
 * window's subtitles, histogram and snapshot take it from.
 */
enum class Presentation(val key: String) {
    /** glReadPixels straight into memory: the CPU waits for the graphics card to finish and copy. */
    SYNC("sync"),

    /** Into a pixel-buffer object, fenced, waited for at once and mapped. */
    PBO("pbo"),

    /**
     * Into one of two pixel-buffer objects while the one before is mapped: the picture handed out is the one the call
     * before drew, so nobody waits for the graphics card — one picture late, the newest collected by [GlStage.latest].
     */
    PBO_LAGGED("pbo-lagged"),

    /**
     * Not read back: the stage shows the picture in a window of its own inside the player's (PictureWindow), libass's
     * subtitles drawn over it there and the controls in a see-through window over both; read back only when a snapshot
     * or the histogram asks (GlStage.readBackShown). Where that window cannot be had — no compositor for the
     * see-through window, neither X11 nor Win32 to put it into — the player reads back as [PBO_LAGGED].
     */
    NATIVE("native"),
    ;

    /** How a picture is read back where one has to be: [NATIVE] as [PBO_LAGGED]. */
    val readBack: Presentation get() = if (this == NATIVE) PBO_LAGGED else this

    companion object {
        /**
         * What the desktop presents by: the stage's own window, the one candidate within budget at 4K (README "Platform
         * support", #287, #378).
         */
        val DEFAULT = NATIVE

        /**
         * The presentation the system property `mediadroyd.presentation` (or `MEDIADROYD_PRESENTATION`) names —
         * `pbo-lagged` keeps the picture in Compose's own window, `sync` falls back to the plain read-back where a
         * driver mishandles pixel-buffer objects —, else [DEFAULT].
         */
        fun chosen(): Presentation {
            val asked = System.getProperty("mediadroyd.presentation") ?: System.getenv("MEDIADROYD_PRESENTATION")
            return entries.firstOrNull { it.key == asked } ?: DEFAULT
        }
    }
}

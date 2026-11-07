package com.hawkynt.mediadroid.engine.desktop

/**
 * How the stage hands its finished picture to the window (README "Platform support"; the measurements behind the choice
 * are `./gradlew :desktop:presentationBench`'s). Each reads the picture back into memory, where the window's subtitles,
 * histogram and snapshot take it from.
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
    ;

    companion object {
        /**
         * What the desktop presents by: the one candidate within 4 ms a picture at 1080p that Compose Desktop's
         * public API allows (README "Platform support", #287).
         */
        val DEFAULT = PBO_LAGGED

        /**
         * The presentation the system property `mediadroid.presentation` (or `MEDIADROID_PRESENTATION`) names — `sync`
         * falls back to the plain read-back where a driver mishandles pixel-buffer objects —, else [DEFAULT].
         */
        fun chosen(): Presentation {
            val asked = System.getProperty("mediadroid.presentation") ?: System.getenv("MEDIADROID_PRESENTATION")
            return entries.firstOrNull { it.key == asked } ?: DEFAULT
        }
    }
}

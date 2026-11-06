package com.hawkynt.mediadroid.player

import kotlin.math.roundToInt

/**
 * Where libass lays a track out ([AssTrack.render]): the frame it draws — the part of the screen the picture shows, at
 * [x], [y] of the view, [width] × [height] — and how far the whole video reaches past it on each side (the margins,
 * never above 0). Where the screen shows all of the video the margins are 0 and libass draws as on the whole video.
 * Where it shows a part — Fill crops it to the screen, the black bars are cropped (AutoCrop) — signs placed with
 * `\pos` stay where they belong on the whole video, while lines without a place of their own are kept inside the frame,
 * as mpv keeps them with sub-use-margins (libass's use_margins).
 */
data class AssFrame(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val marginLeft: Int = 0,
    val marginTop: Int = 0,
    val marginRight: Int = 0,
    val marginBottom: Int = 0,
) {
    /** Whether the frame shows only part of the video. */
    val partial: Boolean get() = marginLeft != 0 || marginTop != 0 || marginRight != 0 || marginBottom != 0

    companion object {
        /**
         * For a view whose shown part runs from [visibleLeft], [visibleTop] to [visibleRight], [visibleBottom] and in
         * which the whole video — the black bars a crop cut away included — lies from [videoLeft], [videoTop] to
         * [videoRight], [videoBottom] (beyond the view where it is larger): the frame is where the two meet. Null
         * where they do not.
         */
        fun of(
            visibleLeft: Int,
            visibleTop: Int,
            visibleRight: Int,
            visibleBottom: Int,
            videoLeft: Float,
            videoTop: Float,
            videoRight: Float,
            videoBottom: Float,
        ): AssFrame? {
            val left = maxOf(visibleLeft, videoLeft.roundToInt())
            val top = maxOf(visibleTop, videoTop.roundToInt())
            val right = minOf(visibleRight, videoRight.roundToInt())
            val bottom = minOf(visibleBottom, videoBottom.roundToInt())
            if (right <= left || bottom <= top) return null
            return AssFrame(
                left, top, right - left, bottom - top,
                marginLeft = minOf(0, videoLeft.roundToInt() - left),
                marginTop = minOf(0, videoTop.roundToInt() - top),
                marginRight = minOf(0, right - videoRight.roundToInt()),
                marginBottom = minOf(0, bottom - videoBottom.roundToInt()),
            )
        }

        /**
         * For a picture of [width] × [height] that shows [crop] of a [videoWidth] × [videoHeight] video (null for all
         * of it), all of the picture on the screen.
         */
        fun cropped(width: Int, height: Int, crop: PictureCrop?, videoWidth: Int, videoHeight: Int): AssFrame =
            shown(width, height, 0, 0, width, height, crop, videoWidth, videoHeight) ?: AssFrame(0, 0, width, height)

        /**
         * For a view of [width] × [height] that shows [crop] of a [videoWidth] × [videoHeight] picture (null for all
         * of it) and of which the part from [visibleLeft], [visibleTop] to [visibleRight], [visibleBottom] is on the
         * screen. The picture fills the view, or — drawn on part of it (a projector's keystone draws on the whole
         * screen) — the [pictureWidth] × [pictureHeight] at [pictureX], [pictureY]. Null where nothing of it shows.
         */
        fun shown(
            width: Int,
            height: Int,
            visibleLeft: Int,
            visibleTop: Int,
            visibleRight: Int,
            visibleBottom: Int,
            crop: PictureCrop?,
            videoWidth: Int,
            videoHeight: Int,
            pictureX: Int = 0,
            pictureY: Int = 0,
            pictureWidth: Int = width,
            pictureHeight: Int = height,
        ): AssFrame? {
            // What shows is the picture's place, as far as it is on the screen.
            val left = maxOf(visibleLeft, pictureX)
            val top = maxOf(visibleTop, pictureY)
            val right = minOf(visibleRight, pictureX + pictureWidth)
            val bottom = minOf(visibleBottom, pictureY + pictureHeight)
            return whole(
                left, top, right, bottom, crop, videoWidth, videoHeight, pictureX, pictureY, pictureWidth, pictureHeight,
            )
        }

        // The frame [left], [top] … shows, around it the whole video of which the picture at [pictureX] … is [crop].
        private fun whole(
            visibleLeft: Int,
            visibleTop: Int,
            visibleRight: Int,
            visibleBottom: Int,
            crop: PictureCrop?,
            videoWidth: Int,
            videoHeight: Int,
            pictureX: Int,
            pictureY: Int,
            pictureWidth: Int,
            pictureHeight: Int,
        ): AssFrame? {
            if (crop == null || videoWidth <= 0 || videoHeight <= 0 || crop.width <= 0 || crop.height <= 0) {
                return of(
                    visibleLeft, visibleTop, visibleRight, visibleBottom,
                    pictureX.toFloat(), pictureY.toFloat(),
                    (pictureX + pictureWidth).toFloat(), (pictureY + pictureHeight).toFloat(),
                )
            }
            val scaleX = pictureWidth.toFloat() / crop.width
            val scaleY = pictureHeight.toFloat() / crop.height
            val left = pictureX - crop.left * scaleX
            val top = pictureY - crop.top * scaleY
            return of(
                visibleLeft, visibleTop, visibleRight, visibleBottom,
                left, top, left + videoWidth * scaleX, top + videoHeight * scaleY,
            )
        }
    }
}

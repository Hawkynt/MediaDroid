package com.hawkynt.mediadroid.ui

import com.hawkynt.mediadroid.player.PictureAdjustment

/** How trying a choice came out: taken, or refused with why, the choice before kept (see PictureTrial). */
typealias PictureVerdict = com.hawkynt.mediadroid.player.PictureVerdict

/**
 * Tries a picture before it is taken, telling what it is doing meanwhile: each platform's check behind every choice
 * of deinterlacing, upscaling and smooth motion (Android's pictureCheck).
 */
typealias PictureTry = suspend (picture: PictureAdjustment, say: (String) -> Unit) -> PictureVerdict

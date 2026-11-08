package com.hawkynt.mediadroyd.engine

/**
 * Where an engine's pictures go ([PlaybackEngine.videoOutput]): a platform's own kinds, each engine naming the ones
 * it takes — on Android a view's surface or the picture stage's input, on the desktop the window's stage, which takes
 * the picture due at each refresh. Handing an engine an output it does not take is a mistake it refuses
 * (IllegalArgumentException); none ([PlaybackEngine.videoOutput] null) leaves the sound playing on its own.
 */
interface VideoOutput

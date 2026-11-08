package com.hawkynt.mediadroyd.engine

/**
 * Where an engine is with what it has open, apart from whether it plays ([PlaybackEngine.isPlaying]): nothing open
 * ([IDLE]: before the first [PlaybackEngine.open] and after [PlaybackEngine.close]), loading or taking a seek in
 * ([BUFFERING]), able to play from where it stands ([READY], playing or paused) or played to its end ([ENDED]).
 * Media3's four states, which every engine can tell.
 */
enum class PlaybackState { IDLE, BUFFERING, READY, ENDED }

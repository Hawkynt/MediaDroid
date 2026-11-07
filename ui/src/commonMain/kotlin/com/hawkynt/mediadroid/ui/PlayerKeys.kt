package com.hawkynt.mediadroid.ui

import androidx.compose.ui.input.key.Key

/** What a key asks of the player. */
enum class PlayerCommand {
    PLAY_PAUSE,
    SEEK_FORWARD,
    SEEK_BACK,
    FULL_SCREEN,
    NEXT,
    PANEL,
    NEXT_SUBTITLES,
    SNAPSHOT,
    FRAME_BACK,
    FRAME_FORWARD,
    CHAPTER_NEXT,
    CHAPTER_BACK,
    REPEAT,
    SLOWER,
    FASTER,
    LEAVE,
}

/**
 * The player's keyboard, mpv's where mpv has one, the same on every platform: Space or K play and pause, ← and → seek,
 * F full screen, N the next video, P the panel, S the next subtitles, C a snapshot, , and . (and a remote's step keys)
 * a frame back and on, Page Up and Down a chapter on and back, L marks the part to repeat, [ and ] slower and faster,
 * Esc leaves. Each player does what it can of these; a key it has no use for it leaves to the screen.
 */
object PlayerKeys {
    /** What [key] asks, or null for a key the player leaves alone. */
    fun commandOf(key: Key): PlayerCommand? = when (key) {
        Key.Spacebar, Key.K -> PlayerCommand.PLAY_PAUSE
        Key.DirectionRight -> PlayerCommand.SEEK_FORWARD
        Key.DirectionLeft -> PlayerCommand.SEEK_BACK
        Key.F -> PlayerCommand.FULL_SCREEN
        Key.N -> PlayerCommand.NEXT
        Key.P -> PlayerCommand.PANEL
        Key.S -> PlayerCommand.NEXT_SUBTITLES
        Key.C -> PlayerCommand.SNAPSHOT
        Key.Comma, Key.MediaStepBackward -> PlayerCommand.FRAME_BACK
        Key.Period, Key.MediaStepForward -> PlayerCommand.FRAME_FORWARD
        Key.PageUp -> PlayerCommand.CHAPTER_NEXT
        Key.PageDown -> PlayerCommand.CHAPTER_BACK
        Key.L -> PlayerCommand.REPEAT
        Key.LeftBracket -> PlayerCommand.SLOWER
        Key.RightBracket -> PlayerCommand.FASTER
        Key.Escape -> PlayerCommand.LEAVE
        else -> null
    }
}

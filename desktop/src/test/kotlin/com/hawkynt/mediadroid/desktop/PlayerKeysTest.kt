package com.hawkynt.mediadroid.desktop

import androidx.compose.ui.input.key.Key
import com.hawkynt.mediadroid.ui.PlayerCommand
import com.hawkynt.mediadroid.ui.PlayerKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerKeysTest {

    @Test
    fun `the player's keyboard is mpv's, the same on every platform`() {
        val expected = mapOf(
            Key.Spacebar to PlayerCommand.PLAY_PAUSE,
            Key.K to PlayerCommand.PLAY_PAUSE,
            Key.DirectionRight to PlayerCommand.SEEK_FORWARD,
            Key.DirectionLeft to PlayerCommand.SEEK_BACK,
            Key.F to PlayerCommand.FULL_SCREEN,
            Key.N to PlayerCommand.NEXT,
            Key.P to PlayerCommand.PANEL,
            Key.S to PlayerCommand.NEXT_SUBTITLES,
            Key.C to PlayerCommand.SNAPSHOT,
            Key.Comma to PlayerCommand.FRAME_BACK,
            Key.Period to PlayerCommand.FRAME_FORWARD,
            Key.MediaStepBackward to PlayerCommand.FRAME_BACK,
            Key.MediaStepForward to PlayerCommand.FRAME_FORWARD,
            Key.PageUp to PlayerCommand.CHAPTER_NEXT,
            Key.PageDown to PlayerCommand.CHAPTER_BACK,
            Key.L to PlayerCommand.REPEAT,
            Key.LeftBracket to PlayerCommand.SLOWER,
            Key.RightBracket to PlayerCommand.FASTER,
            Key.Escape to PlayerCommand.LEAVE,
        )
        expected.forEach { (key, command) -> assertEquals(key.toString(), command, PlayerKeys.commandOf(key)) }
    }

    @Test
    fun `other keys are left to the screen`() {
        assertNull(PlayerKeys.commandOf(Key.A))
        assertNull(PlayerKeys.commandOf(Key.Enter))
    }
}

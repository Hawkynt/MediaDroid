package com.hawkynt.mediadroid.ui

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * The player as the controls see it. The ExoPlayer itself only ever holds the one file playing, so
 * on its own it reports no next or previous item and Media3 greys those buttons out. This wrapper
 * answers for our queue instead: Next and Previous stay live and are handed to [onNext] and
 * [onPrevious], which move through the episodes or the parts of a movie.
 */
@OptIn(UnstableApi::class)
class QueuePlayer(
    player: Player,
    private val hasNext: () -> Boolean,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
) : ForwardingPlayer(player) {

    private val queueCommands = listOf(
        COMMAND_SEEK_TO_NEXT,
        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        COMMAND_SEEK_TO_PREVIOUS,
        COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )

    override fun getAvailableCommands(): Player.Commands {
        val builder = super.getAvailableCommands().buildUpon()
            .add(COMMAND_SEEK_TO_PREVIOUS)
            .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        return if (hasNext()) {
            builder.add(COMMAND_SEEK_TO_NEXT).add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()
        } else {
            builder.remove(COMMAND_SEEK_TO_NEXT).remove(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()
        }
    }

    override fun isCommandAvailable(command: Int): Boolean =
        if (command in queueCommands) getAvailableCommands().contains(command) else super.isCommandAvailable(command)

    override fun hasNextMediaItem(): Boolean = hasNext()

    override fun hasPreviousMediaItem(): Boolean = true

    override fun seekToNext() = onNext()

    override fun seekToNextMediaItem() = onNext()

    override fun seekToPrevious() = onPrevious()

    override fun seekToPreviousMediaItem() = onPrevious()
}

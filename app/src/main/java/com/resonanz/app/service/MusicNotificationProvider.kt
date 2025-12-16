package com.resonanz.app.service

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList
import com.resonanz.app.R

@UnstableApi
class MusicNotificationProvider(
    private val context: Context,
    private val musicService: MusicService
) : DefaultMediaNotificationProvider(context) {

    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        showPauseButton: Boolean
    ): ImmutableList<CommandButton> {
        val standardButtons = super.getMediaButtons(session, playerCommands, mediaButtonPreferences, showPauseButton)
        val finalButtons = mutableListOf<CommandButton>()

        // Shuffle button
        val shuffleOn = session.player.shuffleModeEnabled
        val shuffleCommandAction = if (shuffleOn) CUSTOM_COMMAND_SHUFFLE_OFF else CUSTOM_COMMAND_SHUFFLE_ON
        val shuffleIcon = if (shuffleOn) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle
        val shuffleButton = CommandButton.Builder()
            .setDisplayName("Shuffle")
            .setIconResId(shuffleIcon)
            .setSessionCommand(SessionCommand(shuffleCommandAction, Bundle.EMPTY))
            .build()

        // Repeat button
        val repeatIcon = when (session.player.repeatMode) {
            Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
            Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat_on
            else -> R.drawable.ic_repeat
        }
        val repeatButton = CommandButton.Builder()
            .setDisplayName("Repeat")
            .setIconResId(repeatIcon)
            .setSessionCommand(SessionCommand(CUSTOM_COMMAND_CYCLE_REPEAT_MODE, Bundle.EMPTY))
            .build()

        // Order: Shuffle, Previous, Play/Pause, Next, Repeat
        finalButtons.add(shuffleButton)
        finalButtons.addAll(standardButtons)
        finalButtons.add(repeatButton)

        return ImmutableList.copyOf(finalButtons)
    }

    companion object {
        const val CUSTOM_COMMAND_SHUFFLE_ON = "com.resonanz.app.SHUFFLE_ON"
        const val CUSTOM_COMMAND_SHUFFLE_OFF = "com.resonanz.app.SHUFFLE_OFF"
        const val CUSTOM_COMMAND_CYCLE_REPEAT_MODE = "com.resonanz.app.CYCLE_REPEAT"
    }
}

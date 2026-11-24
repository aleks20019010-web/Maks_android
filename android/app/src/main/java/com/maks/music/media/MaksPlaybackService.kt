package com.maks.music.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.maks.music.MainActivity
import com.maks.music.MaksApplication
import com.maks.music.R

@OptIn(UnstableApi::class)
class MaksPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_DISLIKE, Bundle.EMPTY))
                .add(SessionCommand(CMD_LIKE, Bundle.EMPTY))
                .build()

            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val app = application as MaksApplication
            when (customCommand.customAction) {
                CMD_DISLIKE -> {
                    app.musicController.dislikeCurrent()
                }
                CMD_LIKE -> {
                    val liked = app.musicController.toggleLikeCurrent()
                    updateCustomLayout(liked)
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as MaksApplication
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Оборачиваем ExoPlayer, чтобы кнопки Next/Prev в шторке и на экране блокировки
        // всегда были активны и переключали очередь LocalMusicController
        val sessionPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun hasNextMediaItem(): Boolean = true
            override fun hasPreviousMediaItem(): Boolean = true

            override fun seekToNext() {
                app.musicController.next(isUserSkip = true)
            }

            override fun seekToNextMediaItem() {
                app.musicController.next(isUserSkip = true)
            }

            override fun seekToPrevious() {
                app.musicController.prev()
            }

            override fun seekToPreviousMediaItem() {
                app.musicController.prev()
            }
        }

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        app.musicController.bindPlayer(exoPlayer)
        app.musicController.onLikeStateChanged = { isLiked ->
            updateCustomLayout(isLiked)
        }

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setCallback(sessionCallback)
            .setSessionActivity(sessionActivityIntent)
            .setCustomLayout(buildCustomButtons(isLiked = app.musicController.uiState.value.isCurrentLiked))
            .build()
    }

    private fun updateCustomLayout(isLiked: Boolean) {
        mediaSession?.setCustomLayout(buildCustomButtons(isLiked))
    }

    private fun buildCustomButtons(isLiked: Boolean): ImmutableList<CommandButton> {
        val dislikeButton = CommandButton.Builder()
            .setDisplayName("Не нравится")
            .setIconResId(R.drawable.ic_dislike)
            .setSessionCommand(SessionCommand(CMD_DISLIKE, Bundle.EMPTY))
            .setEnabled(true)
            .build()

        val likeButton = CommandButton.Builder()
            .setDisplayName(if (isLiked) "В любимых" else "Нравится")
            .setIconResId(if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            .setSessionCommand(SessionCommand(CMD_LIKE, Bundle.EMPTY))
            .setEnabled(true)
            .build()

        return ImmutableList.of(dislikeButton, likeButton)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        val app = application as MaksApplication
        app.musicController.onLikeStateChanged = null
        app.musicController.unbindPlayer()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val CMD_DISLIKE = "com.maks.music.action.DISLIKE"
        const val CMD_LIKE = "com.maks.music.action.LIKE"
    }
}
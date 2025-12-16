package com.resonanz.app.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.resonanz.app.MainActivity
import com.resonanz.app.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@UnstableApi
class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    companion object {
        const val NOTIFICATION_ID = 101
        const val ACTION_PLAY_PAUSE = "com.resonanz.app.PLAY_PAUSE"
        const val ACTION_NEXT = "com.resonanz.app.NEXT"
        const val ACTION_PREVIOUS = "com.resonanz.app.PREVIOUS"
        
        var instance: MusicService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
            addListener(playerListener)
        }

        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val defaultResult = super.onConnect(session, controller)
                val customCommands = listOf(
                    MusicNotificationProvider.CUSTOM_COMMAND_SHUFFLE_ON,
                    MusicNotificationProvider.CUSTOM_COMMAND_SHUFFLE_OFF,
                    MusicNotificationProvider.CUSTOM_COMMAND_CYCLE_REPEAT_MODE
                ).map { SessionCommand(it, Bundle.EMPTY) }

                val sessionCommandsBuilder = SessionCommands.Builder()
                // Add all default session commands
                defaultResult.availableSessionCommands.commands.forEach { 
                    sessionCommandsBuilder.add(it) 
                }
                // Add custom commands
                customCommands.forEach { sessionCommandsBuilder.add(it) }

                return MediaSession.ConnectionResult.accept(
                    sessionCommandsBuilder.build(),
                    defaultResult.availablePlayerCommands
                )
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    MusicNotificationProvider.CUSTOM_COMMAND_SHUFFLE_ON -> {
                        session.player.shuffleModeEnabled = true
                        onUpdateNotification(session)
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_SHUFFLE_OFF -> {
                        session.player.shuffleModeEnabled = false
                        onUpdateNotification(session)
                    }
                    MusicNotificationProvider.CUSTOM_COMMAND_CYCLE_REPEAT_MODE -> {
                        val currentMode = session.player.repeatMode
                        val newMode = when (currentMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                            else -> Player.REPEAT_MODE_OFF
                        }
                        session.player.repeatMode = newMode
                        onUpdateNotification(session)
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(getOpenAppPendingIntent())
            .setCallback(callback)
            .build()

        setMediaNotificationProvider(MusicNotificationProvider(this, this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_PLAY_PAUSE -> player.playWhenReady = !player.playWhenReady
                ACTION_NEXT -> player.seekToNext()
                ACTION_PREVIOUS -> player.seekToPrevious()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            mediaSession?.let { onUpdateNotification(it) }
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            item?.let { updateCurrentSongFromMediaItem(it) }
            mediaSession?.let { onUpdateNotification(it) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffleEnabled.value = shuffleModeEnabled
            mediaSession?.let { onUpdateNotification(it) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode
            mediaSession?.let { onUpdateNotification(it) }
        }

        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.e("MusicService", "Player error: ${error.message}")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                _duration.value = player.duration.coerceAtLeast(0L)
            }
        }
    }

    private fun updateCurrentSongFromMediaItem(item: MediaItem) {
        val extras = item.mediaMetadata.extras
        _currentSong.value = Song(
            id = item.mediaId,
            title = item.mediaMetadata.title?.toString() ?: "Unknown",
            artist = item.mediaMetadata.artist?.toString() ?: "Unknown Artist",
            artistId = extras?.getLong("artistId") ?: 0L,
            album = item.mediaMetadata.albumTitle?.toString() ?: "Unknown Album",
            albumId = extras?.getLong("albumId") ?: 0L,
            path = extras?.getString("path") ?: "",
            contentUriString = item.localConfiguration?.uri?.toString() ?: "",
            albumArtUriString = item.mediaMetadata.artworkUri?.toString(),
            duration = extras?.getLong("duration") ?: 0L,
            genre = extras?.getString("genre"),
            mimeType = extras?.getString("mimeType"),
            bitrate = extras?.getInt("bitrate"),
            sampleRate = extras?.getInt("sampleRate")
        )
        _duration.value = player.duration.coerceAtLeast(0L)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        instance = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun getOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("ACTION_SHOW_PLAYER", true)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // Public API
    fun playSong(song: Song, queue: List<Song> = listOf(song), queueName: String = "Now Playing") {
        _queue.value = queue
        
        val mediaItems = queue.map { it.toMediaItem() }
        player.setMediaItems(mediaItems, queue.indexOf(song).coerceAtLeast(0), 0L)
        player.prepare()
        player.play()
    }

    fun playPause() {
        player.playWhenReady = !player.playWhenReady
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    fun nextSong() {
        player.seekToNext()
    }

    fun previousSong() {
        player.seekToPrevious()
    }

    fun toggleShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    fun toggleRepeat() {
        val newMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = newMode
    }

    fun getCurrentPosition(): Long = player.currentPosition

    fun getPlayer(): Player = player

    private fun Song.toMediaItem(): MediaItem {
        val extras = Bundle().apply {
            putLong("artistId", artistId)
            putLong("albumId", albumId)
            putString("path", path)
            putLong("duration", duration)
            putString("genre", genre)
            putString("mimeType", mimeType)
            bitrate?.let { putInt("bitrate", it) }
            sampleRate?.let { putInt("sampleRate", it) }
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(albumArtUriString?.let { android.net.Uri.parse(it) })
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(contentUriString)
            .setMediaMetadata(metadata)
            .build()
    }
}

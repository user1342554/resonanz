package com.resonanz.app.viewmodel

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.resonanz.app.RecentlyPlayedManager
import com.resonanz.app.model.PlayerSheetState
import com.resonanz.app.model.Song
import com.resonanz.app.model.StablePlayerState
import com.resonanz.app.service.MusicService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerViewModel : ViewModel() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _stablePlayerState = MutableStateFlow(StablePlayerState())
    val stablePlayerState: StateFlow<StablePlayerState> = _stablePlayerState.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _sheetState = MutableStateFlow(PlayerSheetState.COLLAPSED)
    val sheetState: StateFlow<PlayerSheetState> = _sheetState.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _queueSourceName = MutableStateFlow("Now Playing")
    val queueSourceName: StateFlow<String> = _queueSourceName.asStateFlow()

    // Device sync state
    private val _activeDevice = MutableStateFlow("phone")
    val activeDevice: StateFlow<String> = _activeDevice.asStateFlow()
    
    // When web is active, phone playback is paused but we track "virtual" playing state
    private var wasPlayingBeforeWebTransfer = false

    // Callback for web sync
    var onStateChanged: ((String?, Boolean, Long, Long, List<String>, Boolean, Int) -> Unit)? = null

    // Recently played history
    private var recentlyPlayedManager: RecentlyPlayedManager? = null
    private val _recentSongIds = MutableStateFlow<List<String>>(emptyList())
    val recentSongIds: StateFlow<List<String>> = _recentSongIds.asStateFlow()

    private var isConnected = false

    fun connect(context: Context) {
        if (isConnected) return
        
        // Initialize recently played manager
        if (recentlyPlayedManager == null) {
            recentlyPlayedManager = RecentlyPlayedManager(context)
            _recentSongIds.value = recentlyPlayedManager?.getRecentSongIds() ?: emptyList()
        }
        
        val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            controller?.addListener(playerListener)
            isConnected = true
            startPositionUpdates()
            updateStateFromPlayer()
        }, MoreExecutors.directExecutor())
    }

    fun disconnect() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        isConnected = false
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _stablePlayerState.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onMediaItemTransition(
            mediaItem: androidx.media3.common.MediaItem?,
            reason: Int
        ) {
            updateStateFromPlayer()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _stablePlayerState.update { it.copy(isShuffleEnabled = shuffleModeEnabled) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _stablePlayerState.update { it.copy(repeatMode = repeatMode) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                _stablePlayerState.update { 
                    it.copy(totalDuration = controller?.duration?.coerceAtLeast(0L) ?: 0L) 
                }
            }
        }
    }

    private fun updateStateFromPlayer() {
        val player = controller ?: return
        val currentItem = player.currentMediaItem
        
        val song = if (currentItem != null) {
            val extras = currentItem.mediaMetadata.extras
            Song(
                id = currentItem.mediaId,
                title = currentItem.mediaMetadata.title?.toString() ?: "Unknown",
                artist = currentItem.mediaMetadata.artist?.toString() ?: "Unknown Artist",
                artistId = extras?.getLong("artistId") ?: 0L,
                album = currentItem.mediaMetadata.albumTitle?.toString() ?: "Unknown Album",
                albumId = extras?.getLong("albumId") ?: 0L,
                path = extras?.getString("path") ?: "",
                contentUriString = currentItem.localConfiguration?.uri?.toString() ?: "",
                albumArtUriString = currentItem.mediaMetadata.artworkUri?.toString(),
                duration = extras?.getLong("duration") ?: 0L,
                genre = extras?.getString("genre"),
                mimeType = extras?.getString("mimeType"),
                bitrate = extras?.getInt("bitrate"),
                sampleRate = extras?.getInt("sampleRate")
            )
        } else null

        _stablePlayerState.update {
            it.copy(
                currentSong = song,
                isPlaying = player.isPlaying,
                totalDuration = player.duration.coerceAtLeast(0L),
                isShuffleEnabled = player.shuffleModeEnabled,
                repeatMode = player.repeatMode
            )
        }
    }

    private fun startPositionUpdates() {
        viewModelScope.launch {
            while (isActive && isConnected) {
                controller?.let { player ->
                    _currentPosition.value = player.currentPosition
                    
                    // Update web server with current state
                    val state = _stablePlayerState.value
                    onStateChanged?.invoke(
                        state.currentSong?.id,
                        state.isPlaying,
                        player.currentPosition,
                        state.totalDuration,
                        _queue.value.map { it.id },
                        state.isShuffleEnabled,
                        state.repeatMode
                    )
                }
                delay(200)
            }
        }
    }

    fun playSong(song: Song, queue: List<Song> = listOf(song), queueName: String = "Now Playing") {
        _queue.value = queue
        _queueSourceName.value = queueName
        MusicService.instance?.playSong(song, queue, queueName)
        
        // Record in recently played history
        recentlyPlayedManager?.recordPlay(song.id)
        _recentSongIds.value = recentlyPlayedManager?.getRecentSongIds() ?: emptyList()
    }

    fun playPause() {
        controller?.let {
            it.playWhenReady = !it.playWhenReady
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun nextSong() {
        controller?.seekToNext()
    }

    fun previousSong() {
        controller?.seekToPrevious()
    }

    fun toggleShuffle() {
        controller?.let {
            it.shuffleModeEnabled = !it.shuffleModeEnabled
        }
    }

    fun toggleRepeat() {
        controller?.let {
            val newMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            it.repeatMode = newMode
        }
    }
    
    // ==================== DEVICE TRANSFER HANDLING ====================
    
    /**
     * Called when playback should transfer to web.
     * Pauses local playback but keeps state for web to continue.
     */
    fun transferToWeb() {
        controller?.let {
            wasPlayingBeforeWebTransfer = it.isPlaying
            if (it.isPlaying) {
                it.pause()
            }
        }
        _activeDevice.value = "web"
        // Keep updating state so web can track position
    }
    
    /**
     * Called when playback should transfer back to phone.
     * Resumes playback from the provided position.
     */
    fun transferToPhone(positionMs: Long) {
        _activeDevice.value = "phone"
        controller?.let {
            // Seek to the position and resume if it was playing
            it.seekTo(positionMs)
            if (wasPlayingBeforeWebTransfer || _stablePlayerState.value.isPlaying) {
                it.play()
            }
        }
        wasPlayingBeforeWebTransfer = false
    }
    
    /**
     * Update position from web when web is the active device.
     * This keeps our state in sync with web playback.
     */
    fun updatePositionFromWeb(positionMs: Long) {
        if (_activeDevice.value != "phone") {
            _currentPosition.value = positionMs
        }
    }
    
    /**
     * Check if phone is the active playback device.
     */
    fun isPhoneActive(): Boolean = _activeDevice.value == "phone"

    fun expandPlayer() {
        _sheetState.value = PlayerSheetState.EXPANDED
    }

    fun collapsePlayer() {
        _sheetState.value = PlayerSheetState.COLLAPSED
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}

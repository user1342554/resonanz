package com.resonanz.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.resonanz.app.Playlist
import com.resonanz.app.PlaylistManager
import com.resonanz.app.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaylistUiState(
    val playlists: List<Playlist> = emptyList(),
    val currentPlaylistDetails: Playlist? = null,
    val currentPlaylistSongs: List<Song> = emptyList(),
    val isLoading: Boolean = false
)

class PlaylistViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistUiState())
    val uiState: StateFlow<PlaylistUiState> = _uiState.asStateFlow()

    private var playlistManager: PlaylistManager? = null
    private var allSongs: List<Song> = emptyList()

    fun initialize(playlistManager: PlaylistManager, songs: List<Song>) {
        this.playlistManager = playlistManager
        this.allSongs = songs
        loadPlaylists()
    }

    fun updateSongs(songs: List<Song>) {
        this.allSongs = songs
        // Update current playlist songs if we're viewing one
        _uiState.value.currentPlaylistDetails?.let { playlist ->
            val updatedSongs = playlist.songs.mapNotNull { songId ->
                allSongs.find { it.id == songId }
            }
            _uiState.update { it.copy(currentPlaylistSongs = updatedSongs) }
        }
    }

    fun loadPlaylists() {
        val playlists = playlistManager?.getAll() ?: emptyList()
        _uiState.update { it.copy(playlists = playlists) }
    }

    fun loadPlaylistDetails(playlistId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            val playlist = playlistManager?.get(playlistId)
            if (playlist != null) {
                val songs = playlist.songs.mapNotNull { songId ->
                    allSongs.find { it.id == songId }
                }
                _uiState.update {
                    it.copy(
                        currentPlaylistDetails = playlist,
                        currentPlaylistSongs = songs,
                        isLoading = false
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun clearPlaylistDetails() {
        _uiState.update {
            it.copy(
                currentPlaylistDetails = null,
                currentPlaylistSongs = emptyList()
            )
        }
    }

    fun createPlaylist(name: String): Playlist? {
        val playlist = playlistManager?.create(name)
        loadPlaylists()
        return playlist
    }

    fun deletePlaylist(playlistId: String) {
        playlistManager?.delete(playlistId)
        loadPlaylists()
        // Clear details if we deleted the current playlist
        if (_uiState.value.currentPlaylistDetails?.id == playlistId) {
            clearPlaylistDetails()
        }
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        playlistManager?.rename(playlistId, newName)
        loadPlaylists()
        // Update current playlist details if it's the one we renamed
        if (_uiState.value.currentPlaylistDetails?.id == playlistId) {
            _uiState.update {
                it.copy(
                    currentPlaylistDetails = it.currentPlaylistDetails?.copy(name = newName)
                )
            }
        }
    }

    fun addSongToPlaylist(playlistId: String, songId: String) {
        playlistManager?.addSong(playlistId, songId)
        loadPlaylists()
        
        // SOFORT den UI-State aktualisieren wenn wir diese Playlist anschauen!
        if (_uiState.value.currentPlaylistDetails?.id == playlistId) {
            val song = allSongs.find { it.id == songId }
            if (song != null && song.id !in _uiState.value.currentPlaylistSongs.map { it.id }) {
                _uiState.update {
                    val updatedPlaylist = playlistManager?.get(playlistId)
                    it.copy(
                        currentPlaylistDetails = updatedPlaylist,
                        currentPlaylistSongs = it.currentPlaylistSongs + song
                    )
                }
            }
        }
    }

    fun removeSongFromPlaylist(playlistId: String, songId: String) {
        playlistManager?.removeSong(playlistId, songId)
        loadPlaylists()
        
        // SOFORT den UI-State aktualisieren wenn wir diese Playlist anschauen!
        if (_uiState.value.currentPlaylistDetails?.id == playlistId) {
            _uiState.update {
                val updatedPlaylist = playlistManager?.get(playlistId)
                it.copy(
                    currentPlaylistDetails = updatedPlaylist,
                    currentPlaylistSongs = it.currentPlaylistSongs.filterNot { s -> s.id == songId }
                )
            }
        }
    }

    fun reorderSongsInPlaylist(playlistId: String, newOrder: List<String>) {
        playlistManager?.reorderSongs(playlistId, newOrder)
        loadPlaylists()
        
        // Update current playlist songs order
        if (_uiState.value.currentPlaylistDetails?.id == playlistId) {
            val reorderedSongs = newOrder.mapNotNull { songId ->
                allSongs.find { it.id == songId }
            }
            _uiState.update {
                val updatedPlaylist = playlistManager?.get(playlistId)
                it.copy(
                    currentPlaylistDetails = updatedPlaylist,
                    currentPlaylistSongs = reorderedSongs
                )
            }
        }
    }

    fun getPlaylistManager(): PlaylistManager? = playlistManager
}


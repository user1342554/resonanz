package com.resonanz.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class PlaylistImportState(
    val isImporting: Boolean = false,
    val playlistName: String = "",
    val totalSongs: Int = 0,
    val downloadedSongs: Int = 0,
    val currentSongName: String = "",
    val error: String? = null,
    val completed: Boolean = false
)

class PlaylistShareManager(private val context: Context) {
    
    private val _importState = MutableStateFlow(PlaylistImportState())
    val importState: StateFlow<PlaylistImportState> = _importState
    
    private val storageDir: File by lazy {
        File(context.filesDir, "music").also { it.mkdirs() }
    }
    
    /**
     * Parse a share URL to extract the base URL and playlist ID
     */
    fun parseShareUrl(url: String): Pair<String, String>? {
        // URL format: http://192.168.x.x:8080/share/playlist/{id}
        val regex = Regex("""(https?://[^/]+)/share/playlist/([^/]+)""")
        val match = regex.find(url) ?: return null
        val baseUrl = match.groupValues[1]
        val playlistId = match.groupValues[2]
        return baseUrl to playlistId
    }
    
    /**
     * Import a playlist from another device
     */
    suspend fun importPlaylist(shareUrl: String, playlistManager: PlaylistManager): Boolean {
        val (baseUrl, playlistId) = parseShareUrl(shareUrl) ?: run {
            _importState.value = PlaylistImportState(error = "Ungültige Share-URL")
            return false
        }
        
        return withContext(Dispatchers.IO) {
            try {
                _importState.value = PlaylistImportState(isImporting = true)
                
                // 1. Fetch playlist metadata and songs
                val songsUrl = "$baseUrl/share/playlist/$playlistId/songs"
                val connection = URL(songsUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 30000
                
                if (connection.responseCode != 200) {
                    _importState.value = PlaylistImportState(error = "Playlist nicht gefunden")
                    return@withContext false
                }
                
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                
                val playlistName = json.getString("playlistName")
                val songsArray = json.getJSONArray("songs")
                val totalSongs = songsArray.length()
                
                _importState.value = PlaylistImportState(
                    isImporting = true,
                    playlistName = playlistName,
                    totalSongs = totalSongs
                )
                
                // 2. Download each song
                val importedSongIds = mutableListOf<String>()
                
                for (i in 0 until totalSongs) {
                    val song = songsArray.getJSONObject(i)
                    val songId = song.getString("id")
                    val songTitle = song.getString("title")
                    val songArtist = song.optString("artist", "Unknown")
                    
                    _importState.value = _importState.value.copy(
                        currentSongName = songTitle,
                        downloadedSongs = i
                    )
                    
                    // Download the song
                    val downloadUrl = "$baseUrl/share/playlist/$playlistId/song/$songId"
                    val success = downloadSong(downloadUrl, songTitle, songArtist)
                    
                    if (success) {
                        // Use the filename as the song ID for the new playlist
                        val fileName = sanitizeFileName("$songTitle - $songArtist") + ".mp3"
                        importedSongIds.add(fileName)
                    }
                }
                
                // 3. Create the playlist with imported songs
                val newPlaylistName = "$playlistName (importiert)"
                playlistManager.importPlaylist(newPlaylistName, importedSongIds)
                
                _importState.value = PlaylistImportState(
                    isImporting = false,
                    playlistName = newPlaylistName,
                    totalSongs = totalSongs,
                    downloadedSongs = totalSongs,
                    completed = true
                )
                
                true
            } catch (e: Exception) {
                Log.e("PlaylistShareManager", "Import error", e)
                _importState.value = PlaylistImportState(error = "Import fehlgeschlagen: ${e.message}")
                false
            }
        }
    }
    
    private fun downloadSong(url: String, title: String, artist: String): Boolean {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 60000
            
            if (connection.responseCode != 200) {
                Log.w("PlaylistShareManager", "Failed to download $title: ${connection.responseCode}")
                return false
            }
            
            val fileName = sanitizeFileName("$title - $artist") + ".mp3"
            val file = File(storageDir, fileName)
            
            // Skip if file already exists
            if (file.exists()) {
                Log.d("PlaylistShareManager", "Song already exists: $fileName")
                return true
            }
            
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            Log.d("PlaylistShareManager", "Downloaded: $fileName")
            true
        } catch (e: Exception) {
            Log.e("PlaylistShareManager", "Download error for $title", e)
            false
        }
    }
    
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(100) // Limit length
    }
    
    fun resetState() {
        _importState.value = PlaylistImportState()
    }
}


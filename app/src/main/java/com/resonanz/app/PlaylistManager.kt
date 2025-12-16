package com.resonanz.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Playlist(
    val id: String,
    val name: String,
    val songs: List<String> // immutable list - wichtig für Compose!
)

class PlaylistManager(context: Context) {
    
    private val playlistFile = File(context.filesDir, "playlists.json")
    private var playlists = listOf<Playlist>() // immutable list
    var onChanged: (() -> Unit)? = null
    
    init {
        load()
    }
    
    private fun load() {
        if (playlistFile.exists()) {
            try {
                val json = JSONArray(playlistFile.readText())
                val loadedPlaylists = mutableListOf<Playlist>()
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    val songs = mutableListOf<String>()
                    val songsArray = obj.getJSONArray("songs")
                    for (j in 0 until songsArray.length()) {
                        songs.add(songsArray.getString(j))
                    }
                    loadedPlaylists.add(Playlist(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        songs = songs.toList() // immutable copy
                    ))
                }
                playlists = loadedPlaylists.toList()
            } catch (e: Exception) {
                e.printStackTrace()
                playlists = emptyList()
            }
        } else {
            playlists = emptyList()
        }
    }
    
    private fun save() {
        val json = JSONArray()
        for (playlist in playlists) {
            val obj = JSONObject()
            obj.put("id", playlist.id)
            obj.put("name", playlist.name)
            obj.put("songs", JSONArray(playlist.songs))
            json.put(obj)
        }
        playlistFile.writeText(json.toString())
        onChanged?.invoke()
    }
    
    fun getAll(): List<Playlist> = playlists // bereits immutable
    
    fun get(id: String): Playlist? = playlists.find { it.id == id }
    
    fun create(name: String): Playlist {
        val playlist = Playlist(
            id = UUID.randomUUID().toString(),
            name = name,
            songs = emptyList() // immutable empty list
        )
        playlists = playlists + playlist // neue Liste!
        save()
        return playlist
    }
    
    fun rename(id: String, newName: String): Boolean {
        val index = playlists.indexOfFirst { it.id == id }
        if (index == -1) return false
        
        // Neue Playlist mit neuem Namen erstellen
        playlists = playlists.map { playlist ->
            if (playlist.id == id) {
                playlist.copy(name = newName) // neue Instanz!
            } else {
                playlist
            }
        }
        save()
        return true
    }
    
    fun delete(id: String): Boolean {
        val oldSize = playlists.size
        playlists = playlists.filterNot { it.id == id } // neue Liste!
        val removed = playlists.size < oldSize
        if (removed) save()
        return removed
    }
    
    fun addSong(playlistId: String, songId: String): Boolean {
        val playlist = get(playlistId) ?: return false
        if (playlist.songs.contains(songId)) return true // bereits vorhanden
        
        // Neue Playlist mit neuer Song-Liste erstellen
        playlists = playlists.map { p ->
            if (p.id == playlistId) {
                p.copy(songs = p.songs + songId) // neue Liste!
            } else {
                p
            }
        }
        save()
        return true
    }
    
    fun removeSong(playlistId: String, songId: String): Boolean {
        val playlist = get(playlistId) ?: return false
        if (!playlist.songs.contains(songId)) return false
        
        // Neue Playlist mit neuer Song-Liste erstellen
        playlists = playlists.map { p ->
            if (p.id == playlistId) {
                p.copy(songs = p.songs.filterNot { it == songId }) // neue Liste!
            } else {
                p
            }
        }
        save()
        return true
    }
    
    fun reorderSongs(playlistId: String, newOrder: List<String>): Boolean {
        val playlist = get(playlistId) ?: return false
        
        // Neue Playlist mit neuer Song-Reihenfolge erstellen
        playlists = playlists.map { p ->
            if (p.id == playlistId) {
                p.copy(songs = newOrder.toList()) // neue Liste!
            } else {
                p
            }
        }
        save()
        return true
    }
    
    fun toJson(): String {
        val json = JSONArray()
        for (playlist in playlists) {
            val obj = JSONObject()
            obj.put("id", playlist.id)
            obj.put("name", playlist.name)
            obj.put("songs", JSONArray(playlist.songs))
            json.put(obj)
        }
        return json.toString()
    }
    
    /**
     * Export a playlist for sharing - includes full metadata
     */
    fun exportForShare(playlistId: String): JSONObject? {
        val playlist = get(playlistId) ?: return null
        return JSONObject().apply {
            put("id", playlist.id)
            put("name", playlist.name)
            put("songs", JSONArray(playlist.songs))
            put("exportedAt", System.currentTimeMillis())
        }
    }
    
    /**
     * Import a shared playlist
     */
    fun importPlaylist(name: String, songIds: List<String>): Playlist {
        val playlist = Playlist(
            id = UUID.randomUUID().toString(),
            name = name,
            songs = songIds.toList() // immutable copy
        )
        playlists = playlists + playlist // neue Liste!
        save()
        return playlist
    }
}

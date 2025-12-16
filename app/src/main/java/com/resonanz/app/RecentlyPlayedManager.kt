package com.resonanz.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages the recently played songs history using SharedPreferences.
 * Stores song IDs with timestamps, sorted by most recent first.
 */
class RecentlyPlayedManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("recently_played", Context.MODE_PRIVATE)
    private val maxHistorySize = 100 // Keep last 100 played songs
    
    /**
     * Record that a song was played.
     * Adds to top of history or moves existing entry to top.
     */
    fun recordPlay(songId: String) {
        val history = getHistory().toMutableList()
        
        // Remove existing entry if present
        history.removeAll { it.songId == songId }
        
        // Add new entry at the beginning
        history.add(0, PlayRecord(songId, System.currentTimeMillis()))
        
        // Trim to max size
        val trimmed = history.take(maxHistorySize)
        
        saveHistory(trimmed)
    }
    
    /**
     * Get the play history, sorted by most recent first.
     */
    fun getHistory(): List<PlayRecord> {
        val json = prefs.getString("history", null) ?: return emptyList()
        
        return try {
            val array = JSONArray(json)
            val history = mutableListOf<PlayRecord>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                history.add(PlayRecord(
                    songId = obj.getString("songId"),
                    timestamp = obj.getLong("timestamp")
                ))
            }
            history.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get ordered list of song IDs by recency (most recent first).
     */
    fun getRecentSongIds(): List<String> {
        return getHistory().map { it.songId }
    }
    
    private fun saveHistory(history: List<PlayRecord>) {
        val array = JSONArray()
        history.forEach { record ->
            array.put(JSONObject().apply {
                put("songId", record.songId)
                put("timestamp", record.timestamp)
            })
        }
        prefs.edit().putString("history", array.toString()).apply()
    }
    
    data class PlayRecord(
        val songId: String,
        val timestamp: Long
    )
}


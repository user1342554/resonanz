package com.resonanz.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Kotlin bridge for the Python Spotify downloader
 * Handles initialization, progress callbacks, and FFmpeg integration
 */
class SpotifyDownloader(private val context: Context) {
    
    companion object {
        private const val TAG = "SpotifyDownloader"
        
        @Volatile
        private var instance: SpotifyDownloader? = null
        
        fun getInstance(context: Context): SpotifyDownloader {
            return instance ?: synchronized(this) {
                instance ?: SpotifyDownloader(context.applicationContext).also { instance = it }
            }
        }
    }
    
    // Python module reference
    private var pythonModule: PyObject? = null
    
    // Download state
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState
    
    // Progress listeners (for SSE broadcasting)
    private val progressListeners = CopyOnWriteArrayList<(String) -> Unit>()
    
    // Main thread handler for callbacks
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Coroutine scope for download operations
    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Current download job
    private var currentJob: Job? = null
    
    /**
     * Initialize Python and load the spotify_downloader module
     */
    fun initialize(): Boolean {
        return try {
            // Start Python if not already running
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            
            val python = Python.getInstance()
            pythonModule = python.getModule("spotify_downloader")
            
            // Set up progress callback
            setupProgressCallback()
            
            // Test connection
            val testResult = pythonModule?.callAttr("test_connection")?.toString()
            Log.d(TAG, "Python module initialized: $testResult")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Python", e)
            _downloadState.value = DownloadState.Error("Failed to initialize: ${e.message}")
            false
        }
    }
    
    /**
     * Set up the progress callback from Python to Kotlin
     * Note: Chaquopy callbacks work via PyObject proxy
     */
    private fun setupProgressCallback() {
        // Progress updates are handled via the listener pattern in downloadPlaylistWithProgress
        // The Python code sends updates which we receive via polling or direct callback
    }
    
    /**
     * Handle progress updates from Python
     */
    private fun handleProgressUpdate(progressJson: String) {
        try {
            val json = JSONObject(progressJson)
            val eventType = json.optString("type", "unknown")
            
            // Update state based on event type
            when (eventType) {
                "playlist_start" -> {
                    val total = json.optInt("total", 0)
                    _downloadState.value = DownloadState.Downloading(
                        currentTrack = 0,
                        totalTracks = total,
                        currentTrackName = "",
                        currentArtist = "",
                        status = "Starting..."
                    )
                }
                "track_start", "track_progress" -> {
                    val current = _downloadState.value
                    if (current is DownloadState.Downloading) {
                        _downloadState.value = current.copy(
                            currentTrack = json.optInt("index", current.currentTrack),
                            currentTrackName = json.optString("track", current.currentTrackName),
                            currentArtist = json.optString("artist", current.currentArtist),
                            status = json.optString("status", current.status),
                            percent = json.optString("percent", "")
                        )
                    }
                }
                "track_complete" -> {
                    val current = _downloadState.value
                    if (current is DownloadState.Downloading) {
                        _downloadState.value = current.copy(
                            completedTracks = current.completedTracks + 1
                        )
                    }
                }
                "track_failed" -> {
                    val current = _downloadState.value
                    if (current is DownloadState.Downloading) {
                        _downloadState.value = current.copy(
                            failedTracks = current.failedTracks + 1
                        )
                    }
                }
                "playlist_complete" -> {
                    val downloaded = json.optInt("downloaded", 0)
                    val failed = json.optInt("failed", 0)
                    _downloadState.value = DownloadState.Completed(
                        downloadedCount = downloaded,
                        failedCount = failed
                    )
                }
                "cancelled" -> {
                    _downloadState.value = DownloadState.Cancelled
                }
                "error" -> {
                    _downloadState.value = DownloadState.Error(json.optString("message", "Unknown error"))
                }
            }
            
            // Notify all listeners (for SSE)
            progressListeners.forEach { listener ->
                mainHandler.post { listener(progressJson) }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing progress: $progressJson", e)
        }
    }
    
    /**
     * Add a progress listener (for SSE connections)
     */
    fun addProgressListener(listener: (String) -> Unit) {
        progressListeners.add(listener)
    }
    
    /**
     * Remove a progress listener
     */
    fun removeProgressListener(listener: (String) -> Unit) {
        progressListeners.remove(listener)
    }
    
    /**
     * Start downloading a playlist from CSV content
     */
    fun downloadPlaylist(
        csvContent: String,
        outputDir: File,
        skipInstrumentals: Boolean = false,
        onComplete: (DownloadResult) -> Unit
    ) {
        // Cancel any existing download
        cancelDownload()
        
        currentJob = downloadScope.launch {
            try {
                _downloadState.value = DownloadState.Initializing
                
                // Ensure Python is initialized
                if (pythonModule == null && !initialize()) {
                    withContext(Dispatchers.Main) {
                        onComplete(DownloadResult.Error("Failed to initialize Python"))
                    }
                    return@launch
                }
                
                // Ensure output directory exists
                if (!outputDir.exists()) {
                    outputDir.mkdirs()
                }
                
                _downloadState.value = DownloadState.Downloading(
                    currentTrack = 0,
                    totalTracks = 0,
                    currentTrackName = "",
                    currentArtist = "",
                    status = "Parsing CSV..."
                )
                
                // Create a callback wrapper for progress updates
                val progressCallback = Python.getInstance().getModule("builtins")
                    .callAttr("type", "ProgressCallback", Python.getInstance().getModule("builtins").get("object"), 
                        mapOf<String, Any>()
                    )
                
                // Call Python download function
                val result = pythonModule?.callAttr(
                    "download_playlist",
                    csvContent,
                    outputDir.absolutePath,
                    skipInstrumentals
                )
                
                // Parse result
                val resultMap = result?.asMap()
                val success = resultMap?.get(Python.getInstance().getModule("builtins").callAttr("str", "success"))?.toBoolean() ?: false
                val downloaded = resultMap?.get(Python.getInstance().getModule("builtins").callAttr("str", "downloaded"))?.toInt() ?: 0
                val failed = resultMap?.get(Python.getInstance().getModule("builtins").callAttr("str", "failed"))?.toInt() ?: 0
                
                withContext(Dispatchers.Main) {
                    if (success) {
                        _downloadState.value = DownloadState.Completed(downloaded, failed)
                        onComplete(DownloadResult.Success(downloaded, failed))
                    } else {
                        _downloadState.value = DownloadState.Error("Download failed")
                        onComplete(DownloadResult.Error("No tracks were downloaded"))
                    }
                }
                
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) {
                    _downloadState.value = DownloadState.Cancelled
                    onComplete(DownloadResult.Cancelled)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    _downloadState.value = DownloadState.Error(e.message ?: "Unknown error")
                    onComplete(DownloadResult.Error(e.message ?: "Unknown error"))
                }
            }
        }
    }
    
    /**
     * Alternative download method that processes progress in real-time
     * Uses a polling approach since Chaquopy callbacks can be tricky
     */
    fun downloadPlaylistWithProgress(
        csvContent: String,
        outputDir: File,
        skipInstrumentals: Boolean = false,
        onProgress: (String) -> Unit,
        onComplete: (DownloadResult) -> Unit
    ) {
        cancelDownload()
        
        // Add progress listener
        val progressListener: (String) -> Unit = { json -> onProgress(json) }
        addProgressListener(progressListener)
        
        currentJob = downloadScope.launch {
            try {
                _downloadState.value = DownloadState.Initializing
                
                if (pythonModule == null && !initialize()) {
                    withContext(Dispatchers.Main) {
                        removeProgressListener(progressListener)
                        onComplete(DownloadResult.Error("Failed to initialize Python"))
                    }
                    return@launch
                }
                
                if (!outputDir.exists()) {
                    outputDir.mkdirs()
                }
                
                Log.d(TAG, "Parsing CSV with Python...")
                Log.d(TAG, "CSV content length: ${csvContent.length}")
                Log.d(TAG, "CSV first 500 chars: ${csvContent.take(500)}")
                
                _downloadState.value = DownloadState.Downloading(
                    currentTrack = 0,
                    totalTracks = 0,
                    currentTrackName = "CSV wird gelesen...",
                    currentArtist = "",
                    status = "parsing"
                )
                
                // Parse CSV to get track list
                val trackList = try {
                    pythonModule?.callAttr("get_tracks_from_csv", csvContent)
                } catch (e: Exception) {
                    Log.e(TAG, "CSV parse error: ${e.message}", e)
                    throw Exception("CSV konnte nicht gelesen werden: ${e.message}")
                }
                
                Log.d(TAG, "Python returned track list: $trackList")
                
                val tracks = trackList?.asList() ?: emptyList()
                val totalTracks = tracks.size
                Log.d(TAG, "Found $totalTracks tracks in CSV")
                
                if (totalTracks == 0) {
                    throw Exception("Keine Songs in der CSV gefunden")
                }
                
                var downloaded = 0
                var failed = 0
                val downloadedFilePaths = mutableListOf<String>()
                
                // Download each track one by one
                for ((index, trackObj) in tracks.withIndex()) {
                    if (!isActive) break // Check for cancellation
                    
                    val trackMap = trackObj.asMap()
                    val trackName = getStringFromPyDict(trackMap, "track_name")
                    val artistName = getStringFromPyDict(trackMap, "artist_name")
                    val albumName = getStringFromPyDict(trackMap, "album_name")
                    
                    // Skip instrumentals if requested
                    if (skipInstrumentals && (trackName.lowercase().contains("instrumental") || 
                                               trackName.lowercase().contains("karaoke"))) {
                        Log.d(TAG, "Skipping instrumental: $trackName")
                        continue
                    }
                    
                    Log.d(TAG, "Downloading ${index + 1}/$totalTracks: $artistName - $trackName")
                    
                    _downloadState.value = DownloadState.Downloading(
                        currentTrack = index + 1,
                        totalTracks = totalTracks,
                        currentTrackName = trackName,
                        currentArtist = artistName,
                        status = "downloading",
                        completedTracks = downloaded,
                        failedTracks = failed
                    )
                    
                    // Download single track
                    val result = try {
                        pythonModule?.callAttr(
                            "download_single_track",
                            trackName,
                            artistName,
                            albumName,
                            outputDir.absolutePath,
                            index + 1
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Download error for $trackName: ${e.message}")
                        null
                    }
                    
                    val success = getBoolFromPyDict(result, "success")
                    if (success) {
                        downloaded++
                        // Get the file path from result
                        val filePath = getStringFromPyDict(result?.asMap(), "path")
                        if (filePath.isNotEmpty()) {
                            downloadedFilePaths.add(filePath)
                        }
                        Log.d(TAG, "Successfully downloaded: $trackName -> $filePath")
                    } else {
                        failed++
                        val error = getStringFromPyDict(result?.asMap(), "error")
                        Log.e(TAG, "Failed to download $trackName: $error")
                    }
                    
                    // Update state after each track
                    _downloadState.value = DownloadState.Downloading(
                        currentTrack = index + 1,
                        totalTracks = totalTracks,
                        currentTrackName = trackName,
                        currentArtist = artistName,
                        status = if (success) "complete" else "failed",
                        completedTracks = downloaded,
                        failedTracks = failed
                    )
                }
                
                Log.d(TAG, "Download complete: $downloaded successful, $failed failed, files: $downloadedFilePaths")
                
                withContext(Dispatchers.Main) {
                    removeProgressListener(progressListener)
                    if (downloaded > 0) {
                        _downloadState.value = DownloadState.Completed(downloaded, failed)
                        onComplete(DownloadResult.Success(downloaded, failed, downloadedFilePaths))
                    } else {
                        _downloadState.value = DownloadState.Error("Keine Songs heruntergeladen")
                        onComplete(DownloadResult.Error("Keine Songs konnten heruntergeladen werden"))
                    }
                }
                
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) {
                    removeProgressListener(progressListener)
                    _downloadState.value = DownloadState.Cancelled
                    onComplete(DownloadResult.Cancelled)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    removeProgressListener(progressListener)
                    _downloadState.value = DownloadState.Error(e.message ?: "Unknown error")
                    onComplete(DownloadResult.Error(e.message ?: "Unknown error"))
                }
            }
        }
    }
    
    private fun getIntFromPyDict(pyObj: PyObject?, key: String): Int {
        return try {
            pyObj?.asMap()?.entries?.find { 
                it.key.toString() == key 
            }?.value?.toInt() ?: 0
        } catch (e: Exception) {
            0
        }
    }
    
    private fun getBoolFromPyDict(pyObj: PyObject?, key: String): Boolean {
        return try {
            pyObj?.asMap()?.entries?.find { 
                it.key.toString() == key 
            }?.value?.toBoolean() ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    private fun getStringFromPyDict(pyMap: Map<PyObject, PyObject>?, key: String): String {
        return try {
            pyMap?.entries?.find { 
                it.key.toString() == key 
            }?.value?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * Cancel the current download
     */
    fun cancelDownload() {
        Log.d(TAG, "cancelDownload() called")
        currentJob?.cancel()
        currentJob = null
        
        try {
            pythonModule?.callAttr("cancel_download")
            Log.d(TAG, "Python cancel_download called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling download", e)
        }
        
        _downloadState.value = DownloadState.Cancelled
        Log.d(TAG, "State set to Cancelled")
    }
    
    /**
     * Reset state to idle
     */
    fun reset() {
        _downloadState.value = DownloadState.Idle
    }
    
    /**
     * Clean up resources
     */
    fun shutdown() {
        cancelDownload()
        downloadScope.cancel()
        progressListeners.clear()
    }
    
    /**
     * Test Python environment
     */
    fun testPython(): String {
        if (pythonModule == null && !initialize()) {
            return "Failed to initialize Python"
        }
        
        return try {
            val result = pythonModule?.callAttr("test_connection")?.toString()
            "Python OK: $result"
        } catch (e: Exception) {
            "Python error: ${e.message}"
        }
    }
}

/**
 * Download state sealed class
 */
sealed class DownloadState {
    object Idle : DownloadState()
    object Initializing : DownloadState()
    data class Downloading(
        val currentTrack: Int,
        val totalTracks: Int,
        val currentTrackName: String,
        val currentArtist: String,
        val status: String,
        val percent: String = "",
        val completedTracks: Int = 0,
        val failedTracks: Int = 0
    ) : DownloadState()
    data class Completed(
        val downloadedCount: Int,
        val failedCount: Int
    ) : DownloadState()
    object Cancelled : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * Download result sealed class
 */
sealed class DownloadResult {
    data class Success(
        val downloadedCount: Int, 
        val failedCount: Int,
        val downloadedFiles: List<String> = emptyList()
    ) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
    object Cancelled : DownloadResult()
}


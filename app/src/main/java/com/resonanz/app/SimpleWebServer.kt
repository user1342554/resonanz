package com.resonanz.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.resonanz.app.model.Song
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class SimpleWebServer(
    port: Int, 
    private val storageDir: File, 
    private val deviceId: String,
    private val context: Context
) : NanoHTTPD(port) {

    var onFileChanged: (() -> Unit)? = null
    var onPlaylistChanged: (() -> Unit)? = null
    private var lastModified: Long = 0
    
    val playlistManager = PlaylistManager(context)
    
    // Spotify Downloader (lazy initialization)
    private val spotifyDownloader by lazy { SpotifyDownloader.getInstance(context) }
    
    // All songs (MediaStore + uploaded)
    @Volatile
    private var allSongs: List<Song> = emptyList()
    
    fun setSongs(songs: List<Song>) {
        allSongs = songs
        updateLastModified()
    }
    
    // ==================== DEVICE MANAGEMENT ====================
    
    data class DeviceInfo(
        val id: String,           // "phone" or "web:{uuid}"
        val name: String,         // "Phone" or "Browser"
        val type: String,         // "phone" or "web"
        var lastSeenMs: Long,
        var isActive: Boolean = false
    )
    
    // Connected devices with heartbeat tracking
    private val connectedDevices = ConcurrentHashMap<String, DeviceInfo>()
    
    // Active device - only this device can advance position/auto-next
    @Volatile
    private var activeDevice: String = "phone"
    
    // Device heartbeat timeout (15 seconds)
    private val DEVICE_TIMEOUT_MS = 15_000L
    
    // Main thread handler for player commands (Media3 thread safety)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // ==================== PLAYER STATE WITH TIMESTAMPS ====================
    
    data class PlayerState(
        var currentSongId: String? = null,
        var isPlaying: Boolean = false,
        var currentPosition: Long = 0,
        var positionUpdatedAtMs: Long = System.currentTimeMillis(),
        var totalDuration: Long = 0,
        var queue: List<String> = emptyList(),
        var shuffleEnabled: Boolean = false,
        var repeatMode: Int = 0,
        var playbackSpeed: Float = 1.0f
    )
    
    @Volatile
    private var playerState = PlayerState()
    
    // Monotonic state revision counter for conflict detection
    @Volatile
    private var stateRevision: Long = 0
    
    // SSE connections for real-time push
    private val sseConnections = CopyOnWriteArrayList<SSEConnection>()
    
    data class SSEConnection(
        val deviceId: String,
        val outputStream: PipedOutputStream,
        var lastEventId: Long = 0
    )
    
    // Callback for player control from web (now routes through main thread)
    var onPlayerCommand: ((String, Any?) -> Unit)? = null
    
    private fun invokePlayerCommand(command: String, data: Any?) {
        mainHandler.post {
            onPlayerCommand?.invoke(command, data)
        }
    }
    
    fun updatePlayerState(
        songId: String?,
        isPlaying: Boolean,
        position: Long,
        duration: Long,
        queue: List<String>,
        shuffle: Boolean,
        repeat: Int
    ) {
        val now = System.currentTimeMillis()
        val stateChanged = playerState.currentSongId != songId ||
                          playerState.isPlaying != isPlaying ||
                          playerState.shuffleEnabled != shuffle ||
                          playerState.repeatMode != repeat ||
                          playerState.queue != queue
        
        playerState = PlayerState(
            currentSongId = songId,
            isPlaying = isPlaying,
            currentPosition = position,
            positionUpdatedAtMs = now,
            totalDuration = duration,
            queue = queue,
            shuffleEnabled = shuffle,
            repeatMode = repeat
        )
        
        // Update phone device heartbeat
        connectedDevices["phone"]?.lastSeenMs = now
        
        // Increment revision only on significant state changes
        if (stateChanged) {
            stateRevision++
        }
        
        // Push state to all SSE connections
        broadcastState()
    }
    
    // Clean up stale devices
    private fun cleanupDevices() {
        val now = System.currentTimeMillis()
        val staleDevices = connectedDevices.filter { (id, device) ->
            id != "phone" && now - device.lastSeenMs > DEVICE_TIMEOUT_MS
        }
        staleDevices.forEach { (id, _) ->
            connectedDevices.remove(id)
            // If active device went stale, transfer back to phone
            if (activeDevice == id) {
                activeDevice = "phone"
                connectedDevices["phone"]?.isActive = true
                stateRevision++
                broadcastState()
            }
        }
    }
    
    // Register or update a web device
    private fun registerDevice(deviceId: String): DeviceInfo {
        cleanupDevices()
        val now = System.currentTimeMillis()
        return connectedDevices.getOrPut(deviceId) {
            DeviceInfo(
                id = deviceId,
                name = "Browser",
                type = "web",
                lastSeenMs = now,
                isActive = false
            )
        }.also {
            it.lastSeenMs = now
        }
    }
    
    // Get available devices
    private fun getAvailableDevices(): List<DeviceInfo> {
        cleanupDevices()
        return connectedDevices.values.toList()
    }
    
    // ==================== SSE BROADCASTING ====================
    
    private fun broadcastState() {
        if (sseConnections.isEmpty()) return
        
        val stateJson = buildStateJson()
        val eventData = buildSSEEvent(stateJson)
        
        val deadConnections = mutableListOf<SSEConnection>()
        
        for (conn in sseConnections) {
            try {
                conn.outputStream.write(eventData.toByteArray())
                conn.outputStream.flush()
                conn.lastEventId = stateRevision
            } catch (e: Exception) {
                deadConnections.add(conn)
            }
        }
        
        // Clean up dead connections
        sseConnections.removeAll(deadConnections)
    }
    
    private fun buildSSEEvent(data: String): String {
        return "id: $stateRevision\nretry: 3000\ndata: $data\n\n"
    }
    
    private fun buildStateJson(): String {
        val now = System.currentTimeMillis()
        val positionAgeMs = now - playerState.positionUpdatedAtMs
        
        val song = if (playerState.currentSongId != null) {
            allSongs.find { it.id == playerState.currentSongId }
        } else null
        
        return JSONObject().apply {
            put("trackId", playerState.currentSongId ?: JSONObject.NULL)
            put("isPlaying", playerState.isPlaying)
            put("positionMs", playerState.currentPosition)
            put("positionAgeMs", positionAgeMs)
            put("serverNowMs", now)
            put("totalDurationMs", playerState.totalDuration)
            put("playbackSpeed", playerState.playbackSpeed)
            put("stateRevision", stateRevision)
            put("activeDevice", activeDevice)
            put("shuffleEnabled", playerState.shuffleEnabled)
            put("repeatMode", playerState.repeatMode)
            
            if (song != null) {
                put("currentSong", JSONObject().apply {
                    put("id", song.id)
                    put("title", song.title)
                    put("artist", song.artist)
                    put("album", song.album)
                    put("duration", song.duration)
                    // Use /albumart/ endpoint for web (works for all song types)
                    put("albumArt", "/albumart/${java.net.URLEncoder.encode(song.id, "UTF-8")}")
                })
            } else {
                put("currentSong", JSONObject.NULL)
            }
            
            put("queue", JSONArray(playerState.queue.mapNotNull { songId ->
                allSongs.find { it.id == songId }?.let { s ->
                    JSONObject().apply {
                        put("id", s.id)
                        put("title", s.title)
                        put("artist", s.artist)
                    }
                }
            }))
        }.toString()
    }
    
    // Session management
    private val pendingSessions = ConcurrentHashMap<String, Long>()
    private val verifiedSessions = ConcurrentHashMap<String, Long>()
    
    private val prefs by lazy { context.getSharedPreferences("resonanz_sessions", Context.MODE_PRIVATE) }
    
    companion object {
        private const val SESSION_TIMEOUT = 30L * 24 * 60 * 60 * 1000L
    }

    init {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        updateLastModified()
        loadSessions()
        playlistManager.onChanged = { updateLastModified() }
        
        // Register phone as always-connected device
        connectedDevices["phone"] = DeviceInfo(
            id = "phone",
            name = "Phone",
            type = "phone",
            lastSeenMs = System.currentTimeMillis(),
            isActive = true
        )
    }
    
    private fun loadSessions() {
        val saved = prefs.getStringSet("verified_sessions", emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        for (entry in saved) {
            val parts = entry.split("|")
            if (parts.size == 2) {
                val token = parts[0]
                val timestamp = parts[1].toLongOrNull() ?: continue
                if (now - timestamp < SESSION_TIMEOUT) {
                    verifiedSessions[token] = timestamp
                }
            }
        }
    }
    
    private fun saveSessions() {
        val toSave = verifiedSessions.map { "${it.key}|${it.value}" }.toSet()
        prefs.edit().putStringSet("verified_sessions", toSave).apply()
    }

    private fun updateLastModified() {
        lastModified = System.currentTimeMillis()
    }
    
    fun notifyFilesChanged() {
        updateLastModified()
    }
    
    fun verifySession(token: String): Boolean {
        if (pendingSessions.containsKey(token)) {
            pendingSessions.remove(token)
            verifiedSessions[token] = System.currentTimeMillis()
            saveSessions()
            return true
        }
        return false
    }
    
    private fun cleanupSessions() {
        val now = System.currentTimeMillis()
        pendingSessions.entries.removeIf { now - it.value > 5 * 60 * 1000L }
        val hadExpired = verifiedSessions.entries.removeIf { now - it.value > SESSION_TIMEOUT }
        if (hadExpired) saveSessions()
    }
    
    private fun isSessionVerified(session: IHTTPSession): Boolean {
        val cookies = session.cookies
        val token = cookies?.read("session_token")
        return token != null && verifiedSessions.containsKey(token)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        
        cleanupSessions()

        return when {
            // Public endpoints
            method == Method.GET && uri == "/" -> serveLoginOrMain(session)
            method == Method.GET && uri == "/login" -> serveLoginPage(session)
            method == Method.GET && uri == "/check-session" -> checkSession(session)
            method == Method.POST && uri.startsWith("/verify/") -> handleVerify(uri.removePrefix("/verify/"))
            
            // Protected endpoints
            method == Method.GET && uri == "/app" -> serveMainPage(session)
            method == Method.GET && uri == "/files" -> protectedRoute(session) { serveFileList() }
            method == Method.GET && uri == "/check" -> protectedRoute(session) { serveCheckUpdate() }
            method == Method.GET && uri.startsWith("/download/") -> protectedRoute(session) { serveFile(session, uri.removePrefix("/download/")) }
            method == Method.GET && uri.startsWith("/albumart/") -> protectedRoute(session) { serveEmbeddedAlbumArt(uri.removePrefix("/albumart/")) }
            method == Method.GET && uri.startsWith("/save/") -> protectedRoute(session) { serveSaveFile(uri.removePrefix("/save/")) }
            method == Method.POST && uri == "/upload" -> protectedRoute(session) { handleUpload(session) }
            method == Method.POST && uri.startsWith("/delete/") -> protectedRoute(session) { handleDelete(uri.removePrefix("/delete/")) }
            
            // Playlist endpoints
            method == Method.GET && uri == "/playlists" -> protectedRoute(session) { servePlaylists() }
            method == Method.POST && uri == "/playlists/create" -> protectedRoute(session) { handleCreatePlaylist(session) }
            method == Method.POST && uri.startsWith("/playlists/rename/") -> protectedRoute(session) { handleRenamePlaylist(session, uri.removePrefix("/playlists/rename/")) }
            method == Method.POST && uri.startsWith("/playlists/delete/") -> protectedRoute(session) { handleDeletePlaylist(uri.removePrefix("/playlists/delete/")) }
            method == Method.POST && uri.startsWith("/playlists/add/") -> protectedRoute(session) { handleAddToPlaylist(session, uri.removePrefix("/playlists/add/")) }
            method == Method.POST && uri.startsWith("/playlists/remove/") -> protectedRoute(session) { handleRemoveFromPlaylist(session, uri.removePrefix("/playlists/remove/")) }
            method == Method.POST && uri.startsWith("/playlists/reorder/") -> protectedRoute(session) { handleReorderPlaylist(session, uri.removePrefix("/playlists/reorder/")) }
            
            // Player endpoints for web sync
            method == Method.GET && uri == "/player/state" -> protectedRoute(session) { servePlayerState() }
            method == Method.POST && uri == "/player/play" -> protectedRoute(session) { handlePlayerPlay(session) }
            method == Method.POST && uri == "/player/pause" -> protectedRoute(session) { handlePlayerPause() }
            method == Method.POST && uri == "/player/next" -> protectedRoute(session) { handlePlayerNext() }
            method == Method.POST && uri == "/player/prev" -> protectedRoute(session) { handlePlayerPrev() }
            method == Method.POST && uri == "/player/seek" -> protectedRoute(session) { handlePlayerSeek(session) }
            method == Method.POST && uri == "/player/shuffle" -> protectedRoute(session) { handlePlayerShuffle() }
            method == Method.POST && uri == "/player/repeat" -> protectedRoute(session) { handlePlayerRepeat() }
            
            // Device sync endpoints
            method == Method.GET && uri == "/player/devices" -> protectedRoute(session) { serveDevices(session) }
            method == Method.POST && uri == "/player/transfer" -> protectedRoute(session) { handleTransfer(session) }
            method == Method.GET && uri == "/player/events" -> protectedRoute(session) { serveSSE(session) }
            method == Method.POST && uri == "/player/heartbeat" -> protectedRoute(session) { handleHeartbeat(session) }
            method == Method.POST && uri == "/player/position" -> protectedRoute(session) { handlePositionUpdate(session) }
            
            // Spotify import endpoints
            method == Method.POST && uri == "/spotify/import" -> protectedRoute(session) { handleSpotifyImport(session) }
            method == Method.GET && uri == "/spotify/progress" -> protectedRoute(session) { serveSpotifyProgress(session) }
            method == Method.POST && uri == "/spotify/cancel" -> protectedRoute(session) { handleSpotifyCancel() }
            method == Method.GET && uri == "/spotify/status" -> protectedRoute(session) { serveSpotifyStatus() }
            method == Method.GET && uri == "/spotify/test" -> protectedRoute(session) { testSpotifyPython() }
            method == Method.POST && uri == "/spotify/testcsv" -> protectedRoute(session) { testCsvParsing(session) }
            
            // Playlist sharing endpoints (PUBLIC - for other devices)
            method == Method.GET && uri.matches(Regex("/share/playlist/[^/]+")) -> {
                val playlistId = uri.removePrefix("/share/playlist/")
                serveSharedPlaylist(playlistId)
            }
            method == Method.GET && uri.matches(Regex("/share/playlist/[^/]+/songs")) -> {
                val playlistId = uri.removePrefix("/share/playlist/").removeSuffix("/songs")
                serveSharedPlaylistSongs(playlistId)
            }
            method == Method.GET && uri.matches(Regex("/share/playlist/[^/]+/song/.*")) -> {
                val parts = uri.removePrefix("/share/playlist/").split("/song/")
                if (parts.size == 2) serveSharedSong(parts[0], parts[1]) 
                else newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
            
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }
    
    private fun protectedRoute(session: IHTTPSession, handler: () -> Response): Response {
        return if (isSessionVerified(session)) handler() else unauthorized()
    }
    
    private fun unauthorized(): Response {
        return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", """{"error":"unauthorized"}""")
    }
    
    private fun serveLoginOrMain(session: IHTTPSession): Response {
        return if (isSessionVerified(session)) serveMainPage(session) else serveLoginPage(session)
    }
    
    private fun checkSession(session: IHTTPSession): Response {
        val verified = isSessionVerified(session)
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"verified":$verified}""")
    }
    
    private fun handleVerify(token: String): Response {
        return if (verifySession(token)) {
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
        } else {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"success":false}""")
        }
    }
    
    // Playlist handlers
    private fun servePlaylists(): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", playlistManager.toJson())
    }
    
    private fun handleCreatePlaylist(session: IHTTPSession): Response {
        val params = mutableMapOf<String, String>()
        session.parseBody(params)
        val name = session.parameters["name"]?.firstOrNull() ?: "Neue Playlist"
        val playlist = playlistManager.create(name)
        android.util.Log.d("SimpleWebServer", "Playlist created: ${playlist.name}, callback is ${if (onPlaylistChanged != null) "SET" else "NULL"}")
        onPlaylistChanged?.invoke()
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"id":"${playlist.id}","name":"${playlist.name}"}""")
    }
    
    private fun handleRenamePlaylist(session: IHTTPSession, id: String): Response {
        val params = mutableMapOf<String, String>()
        session.parseBody(params)
        val name = session.parameters["name"]?.firstOrNull() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"name required"}""")
        return if (playlistManager.rename(id, name)) {
            onPlaylistChanged?.invoke()
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"not found"}""")
        }
    }
    
    private fun handleDeletePlaylist(id: String): Response {
        return if (playlistManager.delete(id)) {
            onPlaylistChanged?.invoke()
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"not found"}""")
        }
    }
    
    private fun handleAddToPlaylist(session: IHTTPSession, id: String): Response {
        val params = mutableMapOf<String, String>()
        session.parseBody(params)
        val song = session.parameters["song"]?.firstOrNull() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"song required"}""")
        return if (playlistManager.addSong(id, song)) {
            onPlaylistChanged?.invoke()
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"not found"}""")
        }
    }
    
    private fun handleRemoveFromPlaylist(session: IHTTPSession, id: String): Response {
        val params = mutableMapOf<String, String>()
        session.parseBody(params)
        val song = session.parameters["song"]?.firstOrNull() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"song required"}""")
        return if (playlistManager.removeSong(id, song)) {
            onPlaylistChanged?.invoke()
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"not found"}""")
        }
    }
    
    private fun handleReorderPlaylist(session: IHTTPSession, id: String): Response {
        try {
            val params = mutableMapOf<String, String>()
            session.parseBody(params)
            val songsParam = session.parameters["songs"]?.firstOrNull() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"songs required"}""")
            val songsArray = JSONArray(songsParam)
            val songs = mutableListOf<String>()
            for (i in 0 until songsArray.length()) {
                songs.add(songsArray.getString(i))
            }
            return if (playlistManager.reorderSongs(id, songs)) {
                onPlaylistChanged?.invoke()
                newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"not found"}""")
            }
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${e.message}"}""")
        }
    }
    
    // ==================== SPOTIFY IMPORT ENDPOINTS ====================
    
    private fun handleSpotifyImport(session: IHTTPSession): Response {
        try {
            val params = mutableMapOf<String, String>()
            session.parseBody(params)
            
            Log.d("SpotifyImport", "Parameters: ${session.parameters.keys}")
            Log.d("SpotifyImport", "Params map: ${params.keys}")
            
            // Get CSV content from uploaded file or text field
            var csvContent = session.parameters["csv"]?.firstOrNull()
            Log.d("SpotifyImport", "CSV from parameters: ${csvContent?.take(200) ?: "null"}")
            
            if (csvContent == null) {
                csvContent = params["postData"]
                Log.d("SpotifyImport", "CSV from postData: ${csvContent?.take(200) ?: "null"}")
            }
            
            if (csvContent == null) {
                // Try to get from files
                val files = mutableMapOf<String, String>()
                try {
                    session.parseBody(files)
                    Log.d("SpotifyImport", "Files: ${files.keys}")
                } catch (e: Exception) {
                    Log.e("SpotifyImport", "Error parsing files", e)
                }
            }
            
            if (csvContent == null) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, 
                    "application/json", 
                    """{"error":"No CSV content provided"}"""
                )
            }
            
            Log.d("SpotifyImport", "CSV content length: ${csvContent.length}")
            
            // Get options
            val skipInstrumentals = session.parameters["skipInstrumentals"]?.firstOrNull()?.toBoolean() ?: false
            val targetPlaylistId = session.parameters["targetPlaylist"]?.firstOrNull()
            
            Log.d("SpotifyImport", "Target playlist ID: $targetPlaylistId")
            
            // Start download in background
            thread {
                spotifyDownloader.downloadPlaylistWithProgress(
                    csvContent = csvContent,
                    outputDir = storageDir,
                    skipInstrumentals = skipInstrumentals,
                    onProgress = { /* Progress tracked via downloadState */ },
                    onComplete = { result ->
                        // Notify file change to refresh song list
                        onFileChanged?.invoke()
                        
                        // Add downloaded songs to target playlist if specified
                        if (!targetPlaylistId.isNullOrEmpty() && result is DownloadResult.Success) {
                            addDownloadedSongsToPlaylist(result.downloadedFiles, targetPlaylistId)
                        }
                    }
                )
            }
            
            return newFixedLengthResponse(
                Response.Status.OK, 
                "application/json", 
                """{"success":true,"message":"Download started"}"""
            )
            
        } catch (e: Exception) {
            Log.e("SimpleWebServer", "Spotify import error", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, 
                "application/json", 
                """{"error":"${e.message?.replace("\"", "'")}"}"""
            )
        }
    }
    
    private fun serveSpotifyProgress(session: IHTTPSession): Response {
        // Return current status as JSON (polling endpoint)
        return serveSpotifyStatus()
    }
    
    private fun addDownloadedSongsToPlaylist(downloadedFiles: List<String>, playlistId: String) {
        // Wait a moment for file system to update
        Thread.sleep(500)
        
        // Refresh song list to get new IDs
        onFileChanged?.invoke()
        
        // Give it a moment to process
        Thread.sleep(500)
        
        var addedCount = 0
        downloadedFiles.forEach { filePath ->
            // Find the song by matching path
            val song = allSongs.find { it.path == filePath }
            if (song != null) {
                if (playlistManager.addSong(playlistId, song.id)) {
                    addedCount++
                    Log.d("SpotifyImport", "Added song to playlist: ${song.title}")
                }
            } else {
                // Try matching by filename
                val fileName = java.io.File(filePath).name
                val songByName = allSongs.find { java.io.File(it.path).name == fileName }
                if (songByName != null && playlistManager.addSong(playlistId, songByName.id)) {
                    addedCount++
                    Log.d("SpotifyImport", "Added song to playlist (by name): ${songByName.title}")
                } else {
                    Log.w("SpotifyImport", "Could not find song for: $filePath")
                }
            }
        }
        Log.d("SpotifyImport", "Added $addedCount songs to playlist $playlistId")
    }
    
    private fun handleSpotifyCancel(): Response {
        spotifyDownloader.cancelDownload()
        return newFixedLengthResponse(
            Response.Status.OK, 
            "application/json", 
            """{"success":true,"message":"Download cancelled"}"""
        )
    }
    
    private fun serveSpotifyStatus(): Response {
        val state = spotifyDownloader.downloadState.value
        val json = when (state) {
            is DownloadState.Idle -> """{"status":"idle"}"""
            is DownloadState.Initializing -> """{"status":"initializing"}"""
            is DownloadState.Downloading -> """{"status":"downloading","track":${state.currentTrack},"total":${state.totalTracks},"name":"${state.currentTrackName}","artist":"${state.currentArtist}","completed":${state.completedTracks},"failed":${state.failedTracks}}"""
            is DownloadState.Completed -> """{"status":"completed","downloaded":${state.downloadedCount},"failed":${state.failedCount}}"""
            is DownloadState.Cancelled -> """{"status":"cancelled"}"""
            is DownloadState.Error -> """{"status":"error","message":"${state.message.replace("\"", "'")}"}"""
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }
    
    private fun testSpotifyPython(): Response {
        return try {
            val result = spotifyDownloader.testPython()
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true,"message":"$result"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":false,"error":"${e.message?.replace("\"", "'")}"}""")
        }
    }
    
    private fun testCsvParsing(session: IHTTPSession): Response {
        return try {
            val params = mutableMapOf<String, String>()
            session.parseBody(params)
            
            val csvContent = session.parameters["csv"]?.firstOrNull()
                ?: params["postData"]
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"No CSV"}""")
            
            // Return preview of CSV content
            val preview = csvContent.take(1000).replace("\"", "'").replace("\n", "\\n")
            val lines = csvContent.lines().take(5)
            val headers = lines.firstOrNull() ?: "none"
            
            newFixedLengthResponse(Response.Status.OK, "application/json", 
                """{"length":${csvContent.length},"lines":${csvContent.lines().size},"headers":"${headers.replace("\"", "'")}","preview":"$preview"}""")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"error":"${e.message?.replace("\"", "'")}"}""")
        }
    }
    
    
    // Player sync endpoints
    private fun servePlayerState(): Response {
        // Use the shared buildStateJson for consistency
        return newFixedLengthResponse(Response.Status.OK, "application/json", buildStateJson())
    }
    
    private fun handlePlayerPlay(session: IHTTPSession): Response {
        try {
            val params = mutableMapOf<String, String>()
            session.parseBody(params)
            val songId = session.parameters["songId"]?.firstOrNull()
            invokePlayerCommand("play", songId)
            return newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${e.message}"}""")
        }
    }
    
    private fun handlePlayerPause(): Response {
        invokePlayerCommand("pause", null)
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
    }
    
    private fun handlePlayerNext(): Response {
        invokePlayerCommand("next", null)
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
    }
    
    private fun handlePlayerPrev(): Response {
        invokePlayerCommand("prev", null)
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
    }
    
    private fun handlePlayerSeek(session: IHTTPSession): Response {
        try {
            val params = mutableMapOf<String, String>()
            session.parseBody(params)
            val position = session.parameters["position"]?.firstOrNull()?.toLongOrNull() ?: 0L
            invokePlayerCommand("seek", position)
            return newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${e.message}"}""")
        }
    }
    
    private fun handlePlayerShuffle(): Response {
        invokePlayerCommand("shuffle", null)
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
    }
    
    private fun handlePlayerRepeat(): Response {
        invokePlayerCommand("repeat", null)
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
    }
    
    // ==================== DEVICE SYNC ENDPOINTS ====================
    
    private fun serveDevices(session: IHTTPSession): Response {
        // Register/update this device from query param
        val deviceId = session.parameters["deviceId"]?.firstOrNull()
        if (deviceId != null) {
            registerDevice(deviceId)
        }
        
        val devices = getAvailableDevices()
        val json = JSONObject().apply {
            put("activeDevice", activeDevice)
            put("devices", JSONArray(devices.map { device ->
                JSONObject().apply {
                    put("id", device.id)
                    put("name", device.name)
                    put("type", device.type)
                    put("isActive", device.id == activeDevice)
                }
            }))
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }
    
    private fun handleTransfer(session: IHTTPSession): Response {
        try {
            val params = mutableMapOf<String, String>()
            session.parseBody(params)
            
            val targetDevice = session.parameters["device"]?.firstOrNull()
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"device required"}""")
            
            val ifRevision = session.parameters["ifRevision"]?.firstOrNull()?.toLongOrNull()
            
            // Register the target device if it's a web device (in case SSE/heartbeat hasn't registered it yet)
            if (targetDevice != "phone" && targetDevice.startsWith("web:")) {
                registerDevice(targetDevice)
            }
            
            // Conflict detection (skip if no revision provided)
            if (ifRevision != null && ifRevision != stateRevision && ifRevision != 0L) {
                return newFixedLengthResponse(Response.Status.CONFLICT, "application/json", 
                    """{"conflict":true,"currentRevision":$stateRevision}"""
                )
            }
            
            // Validate target device exists (phone always exists)
            if (targetDevice != "phone" && !connectedDevices.containsKey(targetDevice)) {
                // Try to register it
                registerDevice(targetDevice)
            }
            
            val previousDevice = activeDevice
            val now = System.currentTimeMillis()
            val positionAgeMs = now - playerState.positionUpdatedAtMs
            
            // Update active device
            connectedDevices.values.forEach { it.isActive = false }
            connectedDevices[targetDevice]?.isActive = true
            activeDevice = targetDevice
            stateRevision++
            
            // If transferring TO phone, tell phone to resume
            if (targetDevice == "phone" && previousDevice != "phone") {
                // Calculate effective position accounting for time since last update
                val effectivePosition = if (playerState.isPlaying) {
                    playerState.currentPosition + positionAgeMs
                } else {
                    playerState.currentPosition
                }
                invokePlayerCommand("transferToPhone", effectivePosition)
            }
            // If transferring FROM phone to web, tell phone to pause
            else if (targetDevice != "phone" && previousDevice == "phone") {
                invokePlayerCommand("transferToWeb", null)
            }
            
            broadcastState()
            
            // Return current state for the new active device
            val stateJson = buildStateJson()
            val response = """{"success":true,"activeDevice":"$activeDevice","stateRevision":$stateRevision,"state":$stateJson}"""
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", response)
        } catch (e: Exception) {
            Log.e("SimpleWebServer", "Transfer error", e)
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${e.message}"}""")
        }
    }
    
    private fun serveSSE(session: IHTTPSession): Response {
        val deviceId = session.parameters["deviceId"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "deviceId required")
        
        // Register device
        registerDevice(deviceId)
        
        // Get Last-Event-ID for reconnection
        val lastEventId = session.headers["last-event-id"]?.toLongOrNull() ?: 0
        
        try {
            val pipedOut = PipedOutputStream()
            val pipedIn = PipedInputStream(pipedOut, 8192)
            
            val connection = SSEConnection(deviceId, pipedOut, lastEventId)
            sseConnections.add(connection)
            
            // Start background thread to handle keepalives and cleanup
            thread(start = true, isDaemon = true, name = "SSE-$deviceId") {
                try {
                    // Send initial state immediately
                    val initialState = buildStateJson()
                    val initialEvent = buildSSEEvent(initialState)
                    pipedOut.write(initialEvent.toByteArray())
                    pipedOut.flush()
                    
                    // Send keepalive every 15 seconds
                    while (sseConnections.contains(connection)) {
                        Thread.sleep(15000)
                        try {
                            pipedOut.write(": keepalive\n\n".toByteArray())
                            pipedOut.flush()
                            // Update device heartbeat
                            connectedDevices[deviceId]?.lastSeenMs = System.currentTimeMillis()
                        } catch (e: Exception) {
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.d("SimpleWebServer", "SSE connection closed for $deviceId")
                } finally {
                    sseConnections.remove(connection)
                    try { pipedOut.close() } catch (e: Exception) {}
                }
            }
            
            val response = newChunkedResponse(Response.Status.OK, "text/event-stream", pipedIn)
            response.addHeader("Cache-Control", "no-cache")
            response.addHeader("Connection", "keep-alive")
            response.addHeader("X-Accel-Buffering", "no") // Disable nginx buffering
            return response
            
        } catch (e: Exception) {
            Log.e("SimpleWebServer", "SSE setup error", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
        }
    }
    
    private fun handleHeartbeat(session: IHTTPSession): Response {
        try {
            val params = mutableMapOf<String, String>()
            session.parseBody(params)
            
            val deviceId = session.parameters["deviceId"]?.firstOrNull()
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"deviceId required"}""")
            
            registerDevice(deviceId)
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", 
                """{"success":true,"activeDevice":"$activeDevice"}""")
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${e.message}"}""")
        }
    }
    
    private fun handlePositionUpdate(session: IHTTPSession): Response {
        try {
            val params = mutableMapOf<String, String>()
            session.parseBody(params)
            
            val deviceId = session.parameters["deviceId"]?.firstOrNull()
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"deviceId required"}""")
            
            val position = session.parameters["position"]?.firstOrNull()?.toLongOrNull()
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"position required"}""")
            
            val revision = session.parameters["revision"]?.firstOrNull()?.toLongOrNull()
            
            // Only accept position updates from the active device
            if (deviceId != activeDevice) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", 
                    """{"error":"not active device","activeDevice":"$activeDevice"}""")
            }
            
            // Conflict check
            if (revision != null && revision != stateRevision) {
                return newFixedLengthResponse(Response.Status.CONFLICT, "application/json",
                    """{"conflict":true,"currentRevision":$stateRevision}""")
            }
            
            // Update position
            val now = System.currentTimeMillis()
            playerState = playerState.copy(
                currentPosition = position,
                positionUpdatedAtMs = now
            )
            
            // Update device heartbeat
            connectedDevices[deviceId]?.lastSeenMs = now
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", 
                """{"success":true,"revision":$stateRevision}""")
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun serveLoginPage(session: IHTTPSession): Response {
        val token = UUID.randomUUID().toString()
        pendingSessions[token] = System.currentTimeMillis()
        
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Resonanz - Anmelden</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: system-ui, -apple-system, sans-serif;
                        background: #1a1a1a;
                        color: #fff;
                        min-height: 100vh;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        padding: 20px;
                    }
                    h1 { margin-bottom: 10px; }
                    .subtitle { color: #888; margin-bottom: 30px; }
                    .qr-container {
                        background: #fff;
                        padding: 20px;
                        border-radius: 8px;
                        margin-bottom: 20px;
                    }
                    .qr-container svg { display: block; }
                    .instructions {
                        text-align: center;
                        color: #888;
                        max-width: 300px;
                        line-height: 1.5;
                    }
                    .status {
                        margin-top: 20px;
                        padding: 10px 20px;
                        background: #333;
                        border-radius: 4px;
                    }
                </style>
            </head>
            <body>
                <h1>Resonanz</h1>
                <p class="subtitle">Mit Handy verbinden</p>
                <div class="qr-container" id="qrcode"></div>
                <p class="instructions">Scanne diesen QR-Code mit der Resonanz App auf deinem Handy</p>
                <div class="status" id="status">Warte auf Verbindung...</div>
                <script>
                    const token = '$token';
                    const script = document.createElement('script');
                    script.src = 'https://cdn.jsdelivr.net/npm/qrcode-generator@1.4.4/qrcode.min.js';
                    script.onload = function() {
                        const qr = qrcode(0, 'M');
                        qr.addData('resonanz:' + token);
                        qr.make();
                        document.getElementById('qrcode').innerHTML = qr.createSvgTag(6, 0);
                    };
                    document.head.appendChild(script);
                    
                    async function checkVerification() {
                        try {
                            const response = await fetch('/check-session');
                            const data = await response.json();
                            if (data.verified) {
                                document.getElementById('status').textContent = 'Verbunden!';
                                document.getElementById('status').style.background = '#2a5a2a';
                                setTimeout(() => window.location.href = '/app', 500);
                            }
                        } catch (e) {}
                    }
                    document.cookie = 'session_token=' + token + '; path=/; max-age=2592000';
                    setInterval(checkVerification, 1000);
                </script>
            </body>
            </html>
        """.trimIndent()

        val response = newFixedLengthResponse(Response.Status.OK, "text/html", html)
        response.addHeader("Set-Cookie", "session_token=$token; Path=/; Max-Age=2592000")
        return response
    }

    private fun serveCheckUpdate(): Response {
        val json = """{"lastModified":$lastModified}"""
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun serveMainPage(session: IHTTPSession): Response {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Resonanz</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: system-ui, -apple-system, sans-serif;
                        background: #1a1a1a;
                        color: #fff;
                        min-height: 100vh;
                    }
                    .container { display: flex; height: 100vh; }
                    .sidebar {
                        width: 250px;
                        background: #111;
                        padding: 20px;
                        overflow-y: auto;
                        border-right: 1px solid #333;
                    }
                    .main { flex: 1; padding: 20px; overflow-y: auto; }
                    
                    h1 { font-size: 1.5rem; margin-bottom: 20px; }
                    h2 { font-size: 1rem; color: #888; margin: 20px 0 10px; font-weight: 500; }
                    
                    .btn {
                        background: #333;
                        color: #fff;
                        border: 1px solid #555;
                        padding: 8px 16px;
                        cursor: pointer;
                        font-size: 0.9rem;
                        text-decoration: none;
                        display: inline-block;
                        text-align: center;
                    }
                    .btn:hover { background: #444; }
                    .btn-small { padding: 4px 10px; font-size: 0.8rem; }
                    a.btn { line-height: 1.4; }
                    .btn-danger { background: #600; border-color: #800; }
                    .btn-danger:hover { background: #800; }
                    .btn-download { background: #1a5a1a; border-color: #2a7a2a; }
                    .btn-download:hover { background: #2a7a2a; }
                    
                    .playlist-item {
                        padding: 10px;
                        cursor: pointer;
                        border-radius: 4px;
                        margin-bottom: 4px;
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                    }
                    .playlist-item:hover { background: #222; }
                    .playlist-item.active { background: #333; }
                    .playlist-name { flex: 1; }
                    
                    .upload-area {
                        border: 2px dashed #444;
                        padding: 20px;
                        text-align: center;
                        margin-bottom: 20px;
                        background: #222;
                    }
                    .upload-area.dragover { border-color: #888; background: #2a2a2a; }
                    input[type="file"] { display: none; }
                    
                    .file-list { background: #222; }
                    .file-item {
                        display: flex;
                        flex-direction: column;
                        padding: 12px;
                        border-bottom: 1px solid #333;
                    }
                    .file-item:last-child { border-bottom: none; }
                    .file-header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 8px;
                    }
                    .file-name { font-weight: 500; word-break: break-all; flex: 1; }
                    .file-size { color: #888; margin-left: 10px; font-size: 0.85rem; }
                    .file-actions { display: flex; gap: 8px; margin-top: 8px; }
                    
                    /* Three-dot menu styles */
                    .song-menu-container { position: relative; }
                    .song-menu-btn {
                        background: none;
                        border: none;
                        color: #888;
                        font-size: 1.5rem;
                        cursor: pointer;
                        padding: 4px 8px;
                        line-height: 1;
                        border-radius: 4px;
                        transition: background 0.2s, color 0.2s;
                    }
                    .song-menu-btn:hover { background: #333; color: #fff; }
                    .song-menu {
                        position: absolute;
                        right: 0;
                        top: 100%;
                        background: #2a2a2a;
                        border: 1px solid #444;
                        border-radius: 8px;
                        min-width: 160px;
                        box-shadow: 0 4px 12px rgba(0,0,0,0.4);
                        z-index: 1000;
                        display: none;
                        overflow: hidden;
                    }
                    .song-menu.active { display: block; }
                    .song-menu-item {
                        padding: 12px 16px;
                        cursor: pointer;
                        display: flex;
                        align-items: center;
                        gap: 10px;
                        transition: background 0.2s;
                        color: #ddd;
                        font-size: 0.9rem;
                    }
                    .song-menu-item:hover { background: #383838; }
                    .song-menu-item.danger { color: #f66; }
                    .song-menu-item.danger:hover { background: #4a2a2a; }
                    .song-menu-divider { height: 1px; background: #444; margin: 4px 0; }
                    
                    audio { width: 100%; height: 36px; }
                    
                    .empty { padding: 30px; text-align: center; color: #666; }
                    .status { margin-top: 10px; color: #888; display: none; }
                    
                    .playlist-song {
                        padding: 10px;
                        background: #2a2a2a;
                        margin-bottom: 4px;
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        cursor: move;
                    }
                    .playlist-song.dragging { opacity: 0.5; }
                    .playlist-song.drag-over { border-top: 2px solid #888; }
                    
                    .add-to-playlist {
                        position: relative;
                    }
                    .playlist-dropdown {
                        position: absolute;
                        top: 100%;
                        left: 0;
                        background: #333;
                        border: 1px solid #555;
                        min-width: 150px;
                        z-index: 100;
                        display: none;
                    }
                    .playlist-dropdown.show { display: block; }
                    .playlist-dropdown-item {
                        padding: 8px 12px;
                        cursor: pointer;
                    }
                    .playlist-dropdown-item:hover { background: #444; }
                    
                    /* Player Bar */
                    .player-bar {
                        position: fixed;
                        bottom: 0;
                        left: 0;
                        right: 0;
                        height: 90px;
                        background: linear-gradient(180deg, #1a1a1a 0%, #111 100%);
                        border-top: 1px solid #333;
                        display: flex;
                        align-items: center;
                        padding: 0 20px;
                        z-index: 1000;
                    }
                    .player-bar.hidden { display: none; }
                    .player-info {
                        display: flex;
                        align-items: center;
                        width: 250px;
                        min-width: 180px;
                    }
                    .player-cover {
                        width: 56px;
                        height: 56px;
                        background: #333;
                        border-radius: 4px;
                        margin-right: 12px;
                        object-fit: cover;
                    }
                    .player-text { overflow: hidden; }
                    .player-title {
                        font-weight: 500;
                        white-space: nowrap;
                        overflow: hidden;
                        text-overflow: ellipsis;
                    }
                    .player-artist {
                        color: #888;
                        font-size: 0.85rem;
                        white-space: nowrap;
                        overflow: hidden;
                        text-overflow: ellipsis;
                    }
                    .player-controls {
                        flex: 1;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        gap: 8px;
                    }
                    .player-buttons {
                        display: flex;
                        align-items: center;
                        gap: 16px;
                    }
                    .player-btn {
                        background: none;
                        border: none;
                        color: #fff;
                        cursor: pointer;
                        padding: 8px;
                        font-size: 1.2rem;
                        opacity: 0.7;
                        transition: opacity 0.2s;
                    }
                    .player-btn:hover { opacity: 1; }
                    .player-btn.active { color: #1db954; opacity: 1; }
                    .player-btn-main {
                        width: 40px;
                        height: 40px;
                        background: #fff;
                        border-radius: 50%;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        color: #000;
                        opacity: 1;
                    }
                    .player-btn-main:hover { transform: scale(1.05); }
                    .player-progress {
                        display: flex;
                        align-items: center;
                        gap: 10px;
                        width: 100%;
                        max-width: 600px;
                    }
                    .player-time {
                        font-size: 0.75rem;
                        color: #888;
                        min-width: 40px;
                    }
                    .player-slider {
                        flex: 1;
                        height: 4px;
                        background: #444;
                        border-radius: 2px;
                        cursor: pointer;
                        position: relative;
                    }
                    .player-slider-fill {
                        height: 100%;
                        background: #fff;
                        border-radius: 2px;
                        width: 0%;
                        transition: width 0.1s;
                    }
                    .player-slider:hover .player-slider-fill { background: #1db954; }
                    .player-extra {
                        width: 250px;
                        display: flex;
                        justify-content: flex-end;
                        gap: 12px;
                        align-items: center;
                    }
                    .main { padding-bottom: 110px; }
                    
                    /* Device Picker */
                    .device-picker { position: relative; }
                    .device-dropdown {
                        position: absolute;
                        bottom: 100%;
                        right: 0;
                        background: #282828;
                        border-radius: 8px;
                        min-width: 200px;
                        box-shadow: 0 4px 12px rgba(0,0,0,0.5);
                        display: none;
                        margin-bottom: 10px;
                    }
                    .device-dropdown.show { display: block; }
                    .device-dropdown-header {
                        padding: 12px 16px;
                        font-size: 0.8rem;
                        color: #888;
                        text-transform: uppercase;
                        letter-spacing: 0.5px;
                        border-bottom: 1px solid #333;
                    }
                    .device-item {
                        padding: 12px 16px;
                        cursor: pointer;
                        display: flex;
                        align-items: center;
                        gap: 12px;
                    }
                    .device-item:hover { background: #333; }
                    .device-item.active { color: #1db954; }
                    .device-item-icon { font-size: 1.2rem; }
                    .device-item-name { flex: 1; }
                    .device-item-active { color: #1db954; font-size: 0.8rem; }
                    
                    /* Device indicator */
                    .device-indicator {
                        position: fixed;
                        bottom: 100px;
                        left: 50%;
                        transform: translateX(-50%);
                        background: #1db954;
                        color: #000;
                        padding: 8px 16px;
                        border-radius: 20px;
                        font-size: 0.85rem;
                        font-weight: 500;
                        z-index: 1001;
                    }
                    .device-indicator.hidden { display: none; }
                    
                    /* Spotify Import Styles */
                    .spotify-import-area {
                        border: 2px dashed #1db954;
                        padding: 30px;
                        text-align: center;
                        margin-bottom: 20px;
                        background: linear-gradient(135deg, #1a1a1a 0%, #1a2a1a 100%);
                        border-radius: 8px;
                    }
                    .spotify-import-area.dragover {
                        border-color: #1ed760;
                        background: linear-gradient(135deg, #1a2a1a 0%, #1a3a1a 100%);
                    }
                    .spotify-logo {
                        font-size: 2rem;
                        margin-bottom: 10px;
                    }
                    .spotify-import-area h3 {
                        color: #1db954;
                        margin-bottom: 10px;
                    }
                    .btn-spotify {
                        background: #1db954;
                        border-color: #1ed760;
                        color: #000;
                        font-weight: 500;
                    }
                    .btn-spotify:hover { background: #1ed760; }
                    .spotify-options {
                        display: flex;
                        gap: 20px;
                        justify-content: center;
                        margin: 15px 0;
                        flex-wrap: wrap;
                    }
                    .spotify-options label {
                        display: flex;
                        align-items: center;
                        gap: 6px;
                        color: #888;
                        cursor: pointer;
                    }
                    .spotify-options input[type="checkbox"] {
                        accent-color: #1db954;
                    }
                    .spotify-progress {
                        margin-top: 20px;
                        padding: 20px;
                        background: #222;
                        border-radius: 8px;
                        display: none;
                    }
                    .spotify-progress.active { display: block; }
                    .spotify-progress-header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 15px;
                    }
                    .spotify-progress-bar {
                        height: 6px;
                        background: #333;
                        border-radius: 3px;
                        overflow: hidden;
                        margin-bottom: 15px;
                    }
                    .spotify-progress-fill {
                        height: 100%;
                        background: linear-gradient(90deg, #1db954 0%, #1ed760 100%);
                        width: 0%;
                        transition: width 0.3s ease;
                    }
                    .spotify-track-info {
                        color: #888;
                        font-size: 0.9rem;
                    }
                    .spotify-track-name {
                        color: #fff;
                        font-weight: 500;
                    }
                    .spotify-stats {
                        display: flex;
                        gap: 20px;
                        margin-top: 10px;
                    }
                    .spotify-stat {
                        display: flex;
                        align-items: center;
                        gap: 6px;
                    }
                    .spotify-stat.success { color: #1db954; }
                    .spotify-stat.error { color: #e74c3c; }
                    .spotify-log {
                        max-height: 200px;
                        overflow-y: auto;
                        background: #1a1a1a;
                        padding: 10px;
                        border-radius: 4px;
                        margin-top: 15px;
                        font-size: 0.8rem;
                        font-family: monospace;
                    }
                    .spotify-log-item { padding: 4px 0; border-bottom: 1px solid #333; }
                    .spotify-log-item.success { color: #1db954; }
                    .spotify-log-item.error { color: #e74c3c; }
                    .spotify-log-item.info { color: #888; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="sidebar">
                        <h1>Resonanz</h1>
                        
                        <div style="margin-bottom: 10px;">
                            <button class="btn" onclick="showAllSongs()" style="width: 100%;">Alle Songs</button>
                        </div>
                        
                        <h2>Playlists</h2>
                        <div id="playlistList"></div>
                        <button class="btn" onclick="createPlaylist()" style="width: 100%; margin-top: 10px;">+ Neue Playlist</button>
                        
                        <h2 style="margin-top: 30px;">Import</h2>
                        <button class="btn btn-spotify" onclick="showSpotifyImport()" style="width: 100%;">🎵 Spotify Import</button>
                    </div>
                    
                    <div class="main">
                        <div id="allSongsView">
                            <div class="upload-area" id="dropZone">
                                <p style="margin-bottom: 10px; color: #888;">Dateien hierher ziehen oder</p>
                                <input type="file" id="fileInput" accept="audio/*" multiple>
                                <button class="btn" onclick="document.getElementById('fileInput').click()">Dateien auswaehlen</button>
                                <div class="status" id="status"></div>
                            </div>
                            
                            <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 10px;">
                                <h2 style="margin: 0; flex: 1;">Alle Songs</h2>
                                <button class="btn btn-small btn-download" onclick="downloadAllSongs()">⬇ Alle herunterladen</button>
                            </div>
                            <div class="file-list" id="fileList"></div>
                        </div>
                        
                        <div id="playlistView" style="display: none;">
                            <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 20px;">
                                <h2 id="playlistTitle" style="margin: 0; flex: 1;"></h2>
                                <button class="btn btn-small btn-download" onclick="downloadPlaylist()">⬇ Alle herunterladen</button>
                                <button class="btn btn-small" onclick="renameCurrentPlaylist()">Umbenennen</button>
                                <button class="btn btn-small btn-danger" onclick="deleteCurrentPlaylist()">Loeschen</button>
                            </div>
                            <div id="playlistSongs"></div>
                        </div>
                        
                        <!-- Spotify Import View -->
                        <div id="spotifyView" style="display: none;">
                            <h2 style="margin-bottom: 20px;">🎵 Spotify Import</h2>
                            
                            <div class="spotify-import-area" id="spotifyDropZone">
                                <div class="spotify-logo">🎧</div>
                                <h3>Spotify Playlist importieren</h3>
                                <p style="color: #888; margin-bottom: 15px;">
                                    Exportiere deine Playlist von <a href="https://exportify.net" target="_blank" style="color: #1db954;">Exportify</a> 
                                    oder <a href="https://www.tunemymusic.com" target="_blank" style="color: #1db954;">TuneMyMusic</a> als CSV
                                </p>
                                <input type="file" id="spotifyCsvInput" accept=".csv">
                                <button class="btn btn-spotify" onclick="document.getElementById('spotifyCsvInput').click()">CSV Datei auswaehlen</button>
                                <p style="color: #666; font-size: 0.8rem; margin-top: 10px;">oder CSV hierher ziehen</p>
                                
                                <div class="spotify-options">
                                    <label>
                                        <input type="checkbox" id="spotifySkipInstrumentals">
                                        Instrumentals ueberspringen
                                    </label>
                                </div>
                                
                                <!-- Playlist selector -->
                                <div style="margin-top: 15px; padding: 15px; background: #222; border-radius: 8px;">
                                    <label style="color: #1db954; font-weight: 500; display: block; margin-bottom: 10px;">📁 Zu Playlist hinzufuegen:</label>
                                    <select id="spotifyTargetPlaylist" style="width: 100%; padding: 10px; background: #333; border: 1px solid #444; border-radius: 6px; color: #fff; font-size: 0.9rem;">
                                        <option value="">-- Keine Playlist (nur downloaden) --</option>
                                    </select>
                                    <button class="btn btn-small" onclick="createPlaylistForSpotify()" style="margin-top: 8px;">+ Neue Playlist erstellen</button>
                                </div>
                                
                                <p style="color: #666; font-size: 0.75rem; margin-top: 10px;">Downloads als M4A/AAC (beste Qualitaet, nativ auf Android)</p>
                                <button class="btn btn-small" onclick="testSpotifyPython()" style="margin-top: 10px;">🔧 Python testen</button>
                                <button class="btn btn-small" onclick="testCsvHeaders()" style="margin-top: 10px; margin-left: 5px;">📋 CSV testen</button>
                                <div id="spotifyTestResult" style="margin-top: 10px; font-size: 0.8rem; color: #888; white-space: pre-wrap; word-break: break-all;"></div>
                            </div>
                            
                            <div class="spotify-progress" id="spotifyProgress">
                                <div class="spotify-progress-header">
                                    <span id="spotifyProgressTitle">Download laeuft...</span>
                                    <button class="btn btn-small btn-danger" onclick="cancelSpotifyImport()">Abbrechen</button>
                                </div>
                                <div class="spotify-progress-bar">
                                    <div class="spotify-progress-fill" id="spotifyProgressFill"></div>
                                </div>
                                <div class="spotify-track-info">
                                    <span id="spotifyTrackCount">0 / 0</span> - 
                                    <span class="spotify-track-name" id="spotifyCurrentTrack">Initialisiere...</span>
                                </div>
                                <div class="spotify-stats">
                                    <div class="spotify-stat success">✓ <span id="spotifySuccessCount">0</span> erfolgreich</div>
                                    <div class="spotify-stat error">✗ <span id="spotifyErrorCount">0</span> fehlgeschlagen</div>
                                </div>
                                <div class="spotify-log" id="spotifyLog"></div>
                            </div>
                        </div>
                    </div>
                </div>
                
                <!-- Hidden audio element for browser playback -->
                <audio id="webAudio" style="display: none;" preload="none"></audio>
                
                <!-- Player Bar -->
                <div class="player-bar hidden" id="playerBar">
                    <div class="player-info">
                        <img class="player-cover" id="playerCover" src="" alt="">
                        <div class="player-text">
                            <div class="player-title" id="playerTitle">-</div>
                            <div class="player-artist" id="playerArtist">-</div>
                        </div>
                    </div>
                    <div class="player-controls">
                        <div class="player-buttons">
                            <button class="player-btn" id="btnShuffle" onclick="toggleShuffle()" title="Shuffle">🔀</button>
                            <button class="player-btn" onclick="playerPrev()" title="Zurueck">⏮</button>
                            <button class="player-btn player-btn-main" id="btnPlayPause" onclick="togglePlayPause()" title="Play/Pause">▶</button>
                            <button class="player-btn" onclick="playerNext()" title="Weiter">⏭</button>
                            <button class="player-btn" id="btnRepeat" onclick="toggleRepeat()" title="Repeat">🔁</button>
                        </div>
                        <div class="player-progress">
                            <span class="player-time" id="playerTime">0:00</span>
                            <div class="player-slider" id="playerSlider" onclick="seekTo(event)">
                                <div class="player-slider-fill" id="playerProgress"></div>
                            </div>
                            <span class="player-time" id="playerDuration">0:00</span>
                        </div>
                    </div>
                    <div class="player-extra">
                        <button class="player-btn" onclick="showQueue()" title="Queue">📋</button>
                        <div class="device-picker">
                            <button class="player-btn" id="btnDevice" onclick="toggleDevicePicker()" title="Geraete">🔊</button>
                            <div class="device-dropdown" id="deviceDropdown">
                                <div class="device-dropdown-header">Wiedergabe auf</div>
                                <div id="deviceList"></div>
                            </div>
                        </div>
                    </div>
                </div>
                
                <!-- Device indicator -->
                <div class="device-indicator hidden" id="deviceIndicator">
                    <span id="deviceIndicatorText">Wiedergabe auf Phone</span>
                </div>
                
                <script>
                    let files = [];
                    let playlists = [];
                    let currentPlaylist = null;
                    let lastModified = 0;
                    
                    const dropZone = document.getElementById('dropZone');
                    const fileInput = document.getElementById('fileInput');
                    const status = document.getElementById('status');
                    
                    dropZone.addEventListener('dragover', (e) => { e.preventDefault(); dropZone.classList.add('dragover'); });
                    dropZone.addEventListener('dragleave', () => dropZone.classList.remove('dragover'));
                    dropZone.addEventListener('drop', (e) => {
                        e.preventDefault();
                        dropZone.classList.remove('dragover');
                        handleFiles(e.dataTransfer.files);
                    });
                    fileInput.addEventListener('change', () => handleFiles(fileInput.files));
                    
                    async function handleFiles(fileList) {
                        for (let file of fileList) {
                            status.style.display = 'block';
                            status.textContent = 'Upload: ' + file.name;
                            const formData = new FormData();
                            formData.append('file', file);
                            try {
                                const response = await fetch('/upload', { method: 'POST', body: formData });
                                if (response.status === 401) { window.location.href = '/login'; return; }
                                status.textContent = response.ok ? 'Fertig: ' + file.name : 'Fehler';
                            } catch (err) {
                                status.textContent = 'Fehler: ' + err.message;
                            }
                        }
                        loadFiles();
                    }
                    
                    async function loadFiles() {
                        try {
                            const response = await fetch('/files');
                            if (response.status === 401) { window.location.href = '/login'; return; }
                            files = await response.json();
                            renderFiles();
                        } catch (err) {}
                    }
                    
                    async function loadPlaylists() {
                        try {
                            const response = await fetch('/playlists');
                            if (response.status === 401) { window.location.href = '/login'; return; }
                            playlists = await response.json();
                            renderPlaylists();
                        } catch (err) {}
                    }
                    
                    function renderFiles() {
                        const fileList = document.getElementById('fileList');
                        if (files.length === 0) {
                            fileList.innerHTML = '<div class="empty">Keine Songs gefunden</div>';
                            return;
                        }
                        fileList.innerHTML = files.map(song => `
                            <div class="file-item" data-song-id="${'$'}{song.id}">
                                <div class="file-header">
                                    <span class="file-name">${'$'}{song.name}</span>
                                    <div style="display: flex; align-items: center; gap: 8px;">
                                        <span class="file-size">${'$'}{formatDuration(song.duration)}</span>
                                        <div class="song-menu-container">
                                            <button class="song-menu-btn" onclick="toggleSongMenu(event, '${'$'}{song.id}')">⋮</button>
                                            <div class="song-menu" id="song-menu-${'$'}{song.id}">
                                                <div class="song-menu-item" onclick="playSong('${'$'}{song.id}')">▶ Play</div>
                                                <div class="song-menu-item" onclick="addToQueue('${'$'}{song.id}')">➕ Add to Queue</div>
                                                <div class="song-menu-divider"></div>
                                                <div class="song-menu-item" onclick="showAddToPlaylist('${'$'}{song.id}')">📁 Add to Playlist</div>
                                                <a class="song-menu-item" href="/save/${'$'}{song.id}" download style="text-decoration: none;">⬇ Download</a>
                                                <div class="song-menu-divider"></div>
                                                <div class="song-menu-item danger" onclick="confirmDeleteSong('${'$'}{song.id}', '${'$'}{song.name.replace(/'/g, "\\'")}')">🗑 Delete from Device</div>
                                            </div>
                                        </div>
                                    </div>
                                </div>
                                <div style="color: #888; font-size: 0.85rem; margin-bottom: 8px;">${'$'}{song.artist} - ${'$'}{song.album}</div>
                                <audio controls preload="none" src="/download/${'$'}{song.id}"></audio>
                            </div>
                        `).join('');
                    }
                    
                    function formatDuration(ms) {
                        const sec = Math.floor(ms / 1000);
                        const min = Math.floor(sec / 60);
                        const s = sec % 60;
                        return min + ':' + (s < 10 ? '0' : '') + s;
                    }
                    
                    function renderPlaylists() {
                        const list = document.getElementById('playlistList');
                        if (playlists.length === 0) {
                            list.innerHTML = '<div style="color: #666; padding: 10px;">Keine Playlists</div>';
                        } else {
                            list.innerHTML = playlists.map(p => `
                                <div class="playlist-item ${'$'}{currentPlaylist && currentPlaylist.id === p.id ? 'active' : ''}" onclick="showPlaylist('${'$'}{p.id}')">
                                    <span class="playlist-name">${'$'}{p.name}</span>
                                    <span style="color: #666; font-size: 0.8rem;">${'$'}{p.songs.length}</span>
                                </div>
                            `).join('');
                        }
                        // Also update Spotify import playlist selector
                        updateSpotifyPlaylistSelector();
                    }
                    
                    function togglePlaylistDropdown(btn, songId) {
                        document.querySelectorAll('.playlist-dropdown').forEach(d => d.classList.remove('show'));
                        const dropdown = btn.nextElementSibling;
                        if (playlists.length === 0) {
                            dropdown.innerHTML = '<div class="playlist-dropdown-item" onclick="createPlaylist()">Playlist erstellen</div>';
                        } else {
                            dropdown.innerHTML = playlists.map(p => 
                                `<div class="playlist-dropdown-item" onclick="addToPlaylist('${'$'}{p.id}', '${'$'}{songId}')">${'$'}{p.name}</div>`
                            ).join('');
                        }
                        dropdown.classList.toggle('show');
                    }
                    
                    document.addEventListener('click', (e) => {
                        if (!e.target.closest('.add-to-playlist')) {
                            document.querySelectorAll('.playlist-dropdown').forEach(d => d.classList.remove('show'));
                        }
                    });
                    
                    async function createPlaylist() {
                        const name = prompt('Name der Playlist:');
                        if (!name) return;
                        const formData = new FormData();
                        formData.append('name', name);
                        await fetch('/playlists/create', { method: 'POST', body: formData });
                        loadPlaylists();
                    }
                    
                    async function addToPlaylist(playlistId, songId) {
                        const formData = new FormData();
                        formData.append('song', songId);
                        await fetch('/playlists/add/' + playlistId, { method: 'POST', body: formData });
                        loadPlaylists();
                        if (currentPlaylist && currentPlaylist.id === playlistId) {
                            showPlaylist(playlistId);
                        }
                    }
                    
                    async function removeFromPlaylist(playlistId, songId) {
                        const formData = new FormData();
                        formData.append('song', songId);
                        await fetch('/playlists/remove/' + playlistId, { method: 'POST', body: formData });
                        loadPlaylists();
                        showPlaylist(playlistId);
                    }
                    
                    function showAllSongs() {
                        currentPlaylist = null;
                        document.getElementById('allSongsView').style.display = 'block';
                        document.getElementById('playlistView').style.display = 'none';
                        document.getElementById('spotifyView').style.display = 'none';
                        renderPlaylists();
                    }
                    
                    function showPlaylist(id) {
                        currentPlaylist = playlists.find(p => p.id === id);
                        if (!currentPlaylist) return;
                        
                        document.getElementById('allSongsView').style.display = 'none';
                        document.getElementById('playlistView').style.display = 'block';
                        document.getElementById('spotifyView').style.display = 'none';
                        document.getElementById('playlistTitle').textContent = currentPlaylist.name;
                        
                        const songsDiv = document.getElementById('playlistSongs');
                        if (currentPlaylist.songs.length === 0) {
                            songsDiv.innerHTML = '<div class="empty">Keine Songs in dieser Playlist</div>';
                        } else {
                            songsDiv.innerHTML = currentPlaylist.songs.map((songId, idx) => {
                                const song = files.find(f => String(f.id) === String(songId));
                                return `
                                    <div class="playlist-song" draggable="true" data-song="${'$'}{songId}" data-index="${'$'}{idx}">
                                        <div style="flex: 1;">
                                            <div>${'$'}{song ? song.name : 'Unbekannt'}</div>
                                            ${'$'}{song ? `<div style="color: #888; font-size: 0.85rem;">${'$'}{song.artist} - ${'$'}{song.album}</div>` : ''}
                                            ${'$'}{song ? `<audio controls preload="none" src="/download/${'$'}{songId}" style="width: 100%; margin-top: 8px;"></audio>` : '<div style="color: #f66; font-size: 0.8rem;">Song nicht gefunden</div>'}
                                        </div>
                                        <div style="display: flex; flex-direction: column; gap: 4px; margin-left: 8px;">
                                            ${'$'}{song ? `<a href="/save/${'$'}{songId}" class="btn btn-small btn-download" download>⬇</a>` : ''}
                                            <button class="btn btn-small btn-danger" onclick="removeFromPlaylist('${'$'}{currentPlaylist.id}', '${'$'}{songId}')">X</button>
                                        </div>
                                    </div>
                                `;
                            }).join('');
                            initDragDrop();
                        }
                        renderPlaylists();
                    }
                    
                    function initDragDrop() {
                        const songs = document.querySelectorAll('.playlist-song');
                        songs.forEach(song => {
                            song.addEventListener('dragstart', (e) => {
                                e.target.classList.add('dragging');
                                e.dataTransfer.setData('text/plain', e.target.dataset.index);
                            });
                            song.addEventListener('dragend', (e) => {
                                e.target.classList.remove('dragging');
                                document.querySelectorAll('.playlist-song').forEach(s => s.classList.remove('drag-over'));
                            });
                            song.addEventListener('dragover', (e) => {
                                e.preventDefault();
                                const dragging = document.querySelector('.dragging');
                                if (dragging !== song) {
                                    song.classList.add('drag-over');
                                }
                            });
                            song.addEventListener('dragleave', () => song.classList.remove('drag-over'));
                            song.addEventListener('drop', async (e) => {
                                e.preventDefault();
                                song.classList.remove('drag-over');
                                const fromIdx = parseInt(e.dataTransfer.getData('text/plain'));
                                const toIdx = parseInt(song.dataset.index);
                                if (fromIdx !== toIdx) {
                                    const newOrder = [...currentPlaylist.songs];
                                    const [moved] = newOrder.splice(fromIdx, 1);
                                    newOrder.splice(toIdx, 0, moved);
                                    const formData = new FormData();
                                    formData.append('songs', JSON.stringify(newOrder));
                                    await fetch('/playlists/reorder/' + currentPlaylist.id, { method: 'POST', body: formData });
                                    loadPlaylists().then(() => showPlaylist(currentPlaylist.id));
                                }
                            });
                        });
                    }
                    
                    async function renameCurrentPlaylist() {
                        if (!currentPlaylist) return;
                        const name = prompt('Neuer Name:', currentPlaylist.name);
                        if (!name) return;
                        const formData = new FormData();
                        formData.append('name', name);
                        await fetch('/playlists/rename/' + currentPlaylist.id, { method: 'POST', body: formData });
                        loadPlaylists().then(() => showPlaylist(currentPlaylist.id));
                    }
                    
                    async function deleteCurrentPlaylist() {
                        if (!currentPlaylist) return;
                        if (!confirm('Playlist loeschen?')) return;
                        await fetch('/playlists/delete/' + currentPlaylist.id, { method: 'POST' });
                        showAllSongs();
                        loadPlaylists();
                    }
                    
                    async function downloadPlaylist() {
                        if (!currentPlaylist || currentPlaylist.songs.length === 0) {
                            alert('Keine Songs in der Playlist');
                            return;
                        }
                        
                        const songsToDownload = currentPlaylist.songs.filter(songId => 
                            files.some(f => String(f.id) === String(songId))
                        );
                        
                        if (songsToDownload.length === 0) {
                            alert('Keine Songs zum Herunterladen gefunden');
                            return;
                        }
                        
                        if (!confirm('Möchtest du ' + songsToDownload.length + ' Songs herunterladen?')) return;
                        
                        // Download songs one by one with a small delay
                        for (let i = 0; i < songsToDownload.length; i++) {
                            const songId = songsToDownload[i];
                            const link = document.createElement('a');
                            link.href = '/save/' + songId;
                            link.download = '';
                            link.style.display = 'none';
                            document.body.appendChild(link);
                            link.click();
                            document.body.removeChild(link);
                            
                            // Small delay between downloads to prevent browser blocking
                            if (i < songsToDownload.length - 1) {
                                await new Promise(resolve => setTimeout(resolve, 500));
                            }
                        }
                    }
                    
                    async function downloadAllSongs() {
                        if (files.length === 0) {
                            alert('Keine Songs vorhanden');
                            return;
                        }
                        
                        if (!confirm('Möchtest du alle ' + files.length + ' Songs herunterladen?')) return;
                        
                        // Download songs one by one with a small delay
                        for (let i = 0; i < files.length; i++) {
                            const song = files[i];
                            const link = document.createElement('a');
                            link.href = '/save/' + song.id;
                            link.download = '';
                            link.style.display = 'none';
                            document.body.appendChild(link);
                            link.click();
                            document.body.removeChild(link);
                            
                            // Small delay between downloads to prevent browser blocking
                            if (i < files.length - 1) {
                                await new Promise(resolve => setTimeout(resolve, 500));
                            }
                        }
                    }
                    
                    async function deleteFile(name) {
                        if (!confirm('Wirklich loeschen?')) return;
                        await fetch('/delete/' + encodeURIComponent(name), { method: 'POST' });
                        loadFiles();
                        loadPlaylists();
                    }
                    
                    // Three-dot menu functions
                    let activeMenu = null;
                    
                    function toggleSongMenu(event, songId) {
                        event.stopPropagation();
                        const menu = document.getElementById('song-menu-' + songId);
                        
                        // Close other menus
                        document.querySelectorAll('.song-menu.active').forEach(m => {
                            if (m !== menu) m.classList.remove('active');
                        });
                        
                        menu.classList.toggle('active');
                        activeMenu = menu.classList.contains('active') ? menu : null;
                    }
                    
                    // Close menu when clicking outside
                    document.addEventListener('click', () => {
                        if (activeMenu) {
                            activeMenu.classList.remove('active');
                            activeMenu = null;
                        }
                    });
                    
                    async function playSong(songId) {
                        closeAllMenus();
                        await fetch('/player/play?songId=' + encodeURIComponent(songId), { method: 'POST' });
                    }
                    
                    async function addToQueue(songId) {
                        closeAllMenus();
                        // Add to queue - this would need a queue endpoint
                        alert('Song zur Warteschlange hinzugefuegt');
                    }
                    
                    function showAddToPlaylist(songId) {
                        closeAllMenus();
                        // Find the dropdown for this song and populate it
                        const dropdown = document.getElementById('dropdown-' + songId);
                        if (dropdown) {
                            togglePlaylistDropdown(null, songId);
                        } else {
                            // Create a modal for adding to playlist
                            showPlaylistModal(songId);
                        }
                    }
                    
                    function showPlaylistModal(songId) {
                        let modal = document.getElementById('playlist-modal');
                        if (!modal) {
                            modal = document.createElement('div');
                            modal.id = 'playlist-modal';
                            modal.style.cssText = 'position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.7); display: flex; align-items: center; justify-content: center; z-index: 2000;';
                            modal.innerHTML = `
                                <div style="background: #2a2a2a; border-radius: 12px; padding: 20px; min-width: 280px; max-width: 90%;">
                                    <h3 style="margin: 0 0 16px 0;">Zu Playlist hinzufuegen</h3>
                                    <div id="playlist-modal-list" style="max-height: 300px; overflow-y: auto;"></div>
                                    <button class="btn" style="margin-top: 16px; width: 100%;" onclick="closePlaylistModal()">Abbrechen</button>
                                </div>
                            `;
                            document.body.appendChild(modal);
                        }
                        
                        const listDiv = document.getElementById('playlist-modal-list');
                        if (playlists.length === 0) {
                            listDiv.innerHTML = '<div style="color: #888; padding: 20px; text-align: center;">Keine Playlists vorhanden</div>';
                        } else {
                            listDiv.innerHTML = playlists.map(p => `
                                <div style="padding: 12px; background: #333; margin-bottom: 8px; border-radius: 6px; cursor: pointer;" 
                                     onclick="addSongToPlaylistAndClose('${'$'}{p.id}', '${'$'}{songId}')"
                                     onmouseover="this.style.background='#444'" onmouseout="this.style.background='#333'">
                                    ${'$'}{p.name} <span style="color: #888; font-size: 0.85rem;">(${'$'}{p.songs.length} Songs)</span>
                                </div>
                            `).join('');
                        }
                        
                        modal.style.display = 'flex';
                        modal.dataset.songId = songId;
                    }
                    
                    function closePlaylistModal() {
                        const modal = document.getElementById('playlist-modal');
                        if (modal) modal.style.display = 'none';
                    }
                    
                    async function addSongToPlaylistAndClose(playlistId, songId) {
                        await addToPlaylist(playlistId, songId);
                        closePlaylistModal();
                    }
                    
                    async function confirmDeleteSong(songId, songName) {
                        closeAllMenus();
                        if (!confirm('Song "' + songName + '" wirklich vom Geraet loeschen?')) return;
                        
                        try {
                            const response = await fetch('/delete/' + encodeURIComponent(songId), { method: 'POST' });
                            if (response.ok) {
                                loadFiles();
                                loadPlaylists();
                            } else {
                                alert('Fehler beim Loeschen');
                            }
                        } catch (e) {
                            alert('Fehler: ' + e.message);
                        }
                    }
                    
                    function closeAllMenus() {
                        document.querySelectorAll('.song-menu.active').forEach(m => m.classList.remove('active'));
                        activeMenu = null;
                    }
                    
                    function formatSize(bytes) {
                        if (bytes < 1024) return bytes + ' B';
                        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
                        return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
                    }
                    
                    async function checkForUpdates() {
                        try {
                            const response = await fetch('/check');
                            if (response.status === 401) { window.location.href = '/login'; return; }
                            const data = await response.json();
                            if (data.lastModified !== lastModified) {
                                lastModified = data.lastModified;
                                loadFiles();
                                loadPlaylists().then(() => {
                                    if (currentPlaylist) {
                                        const updated = playlists.find(p => p.id === currentPlaylist.id);
                                        if (updated) showPlaylist(updated.id);
                                    }
                                });
                            }
                        } catch (err) {}
                    }
                    
                    loadFiles();
                    loadPlaylists();
                    setInterval(checkForUpdates, 1000);
                    
                    // ==================== DEVICE & PLAYER SYNC ====================
                    
                    // Device ID (persisted across sessions)
                    let deviceId = localStorage.getItem('resonanz_device_id');
                    if (!deviceId) {
                        deviceId = 'web:' + Math.random().toString(36).substr(2, 9);
                        localStorage.setItem('resonanz_device_id', deviceId);
                    }
                    
                    // Player state
                    let playerState = { isPlaying: false, currentSong: null, activeDevice: 'phone' };
                    const DRIFT_THRESHOLD_MS = 300;
                    
                    // Audio element for browser playback
                    const webAudio = document.getElementById('webAudio');
                    let currentLoadedSongId = null;
                    let isWebActive = false;
                    
                    // SSE Connection
                    let eventSource = null;
                    
                    function connectSSE() {
                        if (eventSource) {
                            eventSource.close();
                        }
                        
                        eventSource = new EventSource('/player/events?deviceId=' + encodeURIComponent(deviceId));
                        
                        eventSource.onmessage = (e) => {
                            try {
                                const state = JSON.parse(e.data);
                                handleStateUpdate(state);
                            } catch (err) {
                                console.error('SSE parse error:', err);
                            }
                        };
                        
                        eventSource.onerror = () => {
                            console.log('SSE disconnected, reconnecting...');
                        };
                    }
                    
                    function handleStateUpdate(state) {
                        if (!state) {
                            console.error('handleStateUpdate: state is null/undefined');
                            return;
                        }
                        
                        const wasActive = isWebActive;
                        isWebActive = state.activeDevice === deviceId;
                        
                        console.log('State update:', {
                            activeDevice: state.activeDevice,
                            myDeviceId: deviceId,
                            isWebActive: isWebActive,
                            wasActive: wasActive,
                            hasSong: !!state.currentSong
                        });
                        
                        playerState = state;
                        renderPlayer(state);
                        updateDeviceIndicator(state);
                        
                        // If we just became active, start playback
                        if (isWebActive && !wasActive && state.currentSong) {
                            console.log('Becoming active, loading song...');
                            loadAndPlaySong(state);
                        }
                        // If we're active, sync position
                        else if (isWebActive && state.currentSong) {
                            syncAudioPosition(state);
                        }
                        // If we lost active status, pause
                        else if (!isWebActive && wasActive) {
                            console.log('Lost active status, pausing...');
                            webAudio.pause();
                        }
                        
                        // Update Media Session
                        if (state.currentSong) {
                            updateMediaSession(state.currentSong);
                        }
                    }
                    
                    function loadAndPlaySong(state) {
                        if (!state.currentSong) {
                            console.log('loadAndPlaySong: no current song');
                            return;
                        }
                        
                        const songId = state.currentSong.id;
                        console.log('loadAndPlaySong:', songId, 'current:', currentLoadedSongId);
                        
                        if (currentLoadedSongId !== songId) {
                            currentLoadedSongId = songId;
                            const audioUrl = '/download/' + encodeURIComponent(songId);
                            console.log('Loading audio from:', audioUrl);
                            webAudio.src = audioUrl;
                            webAudio.load();
                        }
                        
                        webAudio.onloadedmetadata = () => {
                            console.log('Audio metadata loaded, duration:', webAudio.duration);
                            // Calculate effective position (clock-skew safe)
                            const positionMs = state.positionMs || 0;
                            const positionAgeMs = state.positionAgeMs || 0;
                            const playbackSpeed = state.playbackSpeed || 1;
                            const effectivePos = positionMs + (positionAgeMs * playbackSpeed);
                            console.log('Seeking to:', effectivePos, 'ms');
                            webAudio.currentTime = effectivePos / 1000;
                            
                            if (state.isPlaying) {
                                console.log('Starting playback...');
                                webAudio.play().catch(e => console.log('Autoplay blocked:', e));
                            }
                        };
                        
                        webAudio.onerror = (e) => {
                            console.error('Audio load error:', webAudio.error);
                        };
                    }
                    
                    function syncAudioPosition(state) {
                        if (!webAudio.duration || isNaN(webAudio.duration)) return;
                        
                        // Clock-skew safe: use positionAgeMs
                        const effectivePos = state.positionMs + (state.positionAgeMs * state.playbackSpeed);
                        const currentPosMs = webAudio.currentTime * 1000;
                        const drift = Math.abs(currentPosMs - effectivePos);
                        
                        if (drift > DRIFT_THRESHOLD_MS) {
                            webAudio.currentTime = effectivePos / 1000;
                        }
                        
                        // Sync play/pause state
                        if (state.isPlaying && webAudio.paused) {
                            webAudio.play().catch(e => {});
                        } else if (!state.isPlaying && !webAudio.paused) {
                            webAudio.pause();
                        }
                    }
                    
                    // Send position updates when we're playing
                    webAudio.addEventListener('timeupdate', () => {
                        if (isWebActive) {
                            const formData = new FormData();
                            formData.append('deviceId', deviceId);
                            formData.append('position', Math.floor(webAudio.currentTime * 1000));
                            formData.append('revision', playerState.stateRevision);
                            fetch('/player/position', { method: 'POST', body: formData }).catch(() => {});
                        }
                    });
                    
                    // Handle track end
                    webAudio.addEventListener('ended', () => {
                        if (isWebActive) {
                            playerNext();
                        }
                    });
                    
                    function renderPlayer(state) {
                        const bar = document.getElementById('playerBar');
                        if (!state || !state.currentSong) {
                            bar.classList.add('hidden');
                            return;
                        }
                        bar.classList.remove('hidden');
                        
                        const cover = document.getElementById('playerCover');
                        const title = document.getElementById('playerTitle');
                        const artist = document.getElementById('playerArtist');
                        const btnPlay = document.getElementById('btnPlayPause');
                        const btnShuffle = document.getElementById('btnShuffle');
                        const btnRepeat = document.getElementById('btnRepeat');
                        const progress = document.getElementById('playerProgress');
                        const timeEl = document.getElementById('playerTime');
                        const durationEl = document.getElementById('playerDuration');
                        
                        cover.src = state.currentSong.albumArt || '';
                        title.textContent = state.currentSong.title || '-';
                        artist.textContent = state.currentSong.artist || '-';
                        btnPlay.textContent = state.isPlaying ? '⏸' : '▶';
                        btnShuffle.classList.toggle('active', state.shuffleEnabled || false);
                        btnRepeat.classList.toggle('active', (state.repeatMode || 0) > 0);
                        btnRepeat.textContent = state.repeatMode === 2 ? '🔂' : '🔁';
                        
                        // Calculate display position (with fallbacks)
                        const positionMs = state.positionMs || 0;
                        const positionAgeMs = state.positionAgeMs || 0;
                        const totalDurationMs = state.totalDurationMs || 0;
                        
                        let displayPos;
                        if (isWebActive && webAudio.currentTime) {
                            displayPos = webAudio.currentTime * 1000;
                        } else {
                            displayPos = positionMs + positionAgeMs;
                        }
                        
                        const percent = totalDurationMs > 0 ? (displayPos / totalDurationMs) * 100 : 0;
                        progress.style.width = Math.min(percent, 100) + '%';
                        timeEl.textContent = formatDuration(displayPos);
                        durationEl.textContent = formatDuration(totalDurationMs);
                    }
                    
                    function updateDeviceIndicator(state) {
                        const indicator = document.getElementById('deviceIndicator');
                        const text = document.getElementById('deviceIndicatorText');
                        
                        if (state.activeDevice === 'phone') {
                            indicator.classList.add('hidden');
                        } else if (state.activeDevice === deviceId) {
                            text.textContent = 'Wiedergabe auf diesem Browser';
                            indicator.classList.remove('hidden');
                        } else {
                            text.textContent = 'Wiedergabe auf anderem Geraet';
                            indicator.classList.remove('hidden');
                        }
                    }
                    
                    // ==================== DEVICE PICKER ====================
                    
                    function toggleDevicePicker() {
                        const dropdown = document.getElementById('deviceDropdown');
                        dropdown.classList.toggle('show');
                        if (dropdown.classList.contains('show')) {
                            loadDevices();
                        }
                    }
                    
                    async function loadDevices() {
                        try {
                            const response = await fetch('/player/devices?deviceId=' + encodeURIComponent(deviceId));
                            const data = await response.json();
                            console.log('Devices:', data);
                            renderDeviceList(data.devices || [], data.activeDevice);
                        } catch (err) {
                            console.error('Failed to load devices:', err);
                        }
                    }
                    
                    function renderDeviceList(devices, currentActiveDevice) {
                        const list = document.getElementById('deviceList');
                        
                        // Always show phone and this browser
                        let html = '';
                        
                        // Phone device
                        const phoneActive = currentActiveDevice === 'phone';
                        html += `
                            <div class="device-item ${'$'}{phoneActive ? 'active' : ''}" onclick="transferTo('phone')">
                                <span class="device-item-icon">📱</span>
                                <span class="device-item-name">Phone</span>
                                ${'$'}{phoneActive ? '<span class="device-item-active">▶</span>' : ''}
                            </div>
                        `;
                        
                        // This browser
                        const browserActive = currentActiveDevice === deviceId;
                        html += `
                            <div class="device-item ${'$'}{browserActive ? 'active' : ''}" onclick="transferTo('${'$'}{deviceId}')">
                                <span class="device-item-icon">💻</span>
                                <span class="device-item-name">Dieser Browser</span>
                                ${'$'}{browserActive ? '<span class="device-item-active">▶</span>' : ''}
                            </div>
                        `;
                        
                        list.innerHTML = html;
                    }
                    
                    async function transferTo(targetDevice) {
                        const formData = new FormData();
                        formData.append('device', targetDevice);
                        formData.append('ifRevision', playerState.stateRevision || 0);
                        
                        try {
                            const response = await fetch('/player/transfer', { method: 'POST', body: formData });
                            const data = await response.json();
                            console.log('Transfer response:', data);
                            
                            if (data.success) {
                                // Close picker
                                document.getElementById('deviceDropdown').classList.remove('show');
                                
                                // If transferring to this browser, start playback
                                if (targetDevice === deviceId && data.state) {
                                    console.log('Transferring to this browser, state:', data.state);
                                    handleStateUpdate(data.state);
                                } else {
                                    // Just update the state
                                    if (data.state) {
                                        handleStateUpdate(data.state);
                                    }
                                }
                            } else if (data.error) {
                                console.error('Transfer error:', data.error);
                                alert('Transfer fehlgeschlagen: ' + data.error);
                            }
                        } catch (err) {
                            console.error('Transfer failed:', err);
                            alert('Transfer fehlgeschlagen');
                        }
                    }
                    
                    // Close device picker when clicking outside
                    document.addEventListener('click', (e) => {
                        if (!e.target.closest('.device-picker')) {
                            document.getElementById('deviceDropdown').classList.remove('show');
                        }
                    });
                    
                    // ==================== PLAYER CONTROLS ====================
                    
                    async function togglePlayPause() {
                        if (isWebActive) {
                            if (webAudio.paused) {
                                webAudio.play().catch(e => {});
                            } else {
                                webAudio.pause();
                            }
                        }
                        
                        if (playerState.isPlaying) {
                            await fetch('/player/pause', { method: 'POST' });
                        } else {
                            await fetch('/player/play', { method: 'POST' });
                        }
                    }
                    
                    async function playerNext() {
                        if (isWebActive) {
                            webAudio.pause();
                            currentLoadedSongId = null;
                        }
                        await fetch('/player/next', { method: 'POST' });
                    }
                    
                    async function playerPrev() {
                        if (isWebActive) {
                            webAudio.pause();
                            currentLoadedSongId = null;
                        }
                        await fetch('/player/prev', { method: 'POST' });
                    }
                    
                    async function toggleShuffle() {
                        await fetch('/player/shuffle', { method: 'POST' });
                    }
                    
                    async function toggleRepeat() {
                        await fetch('/player/repeat', { method: 'POST' });
                    }
                    
                    async function seekTo(event) {
                        const slider = document.getElementById('playerSlider');
                        const rect = slider.getBoundingClientRect();
                        const percent = (event.clientX - rect.left) / rect.width;
                        const position = Math.floor(percent * playerState.totalDurationMs);
                        
                        if (isWebActive) {
                            webAudio.currentTime = position / 1000;
                        }
                        
                        const formData = new FormData();
                        formData.append('position', position);
                        await fetch('/player/seek', { method: 'POST', body: formData });
                    }
                    
                    async function playSong(songId) {
                        const formData = new FormData();
                        formData.append('songId', songId);
                        await fetch('/player/play', { method: 'POST', body: formData });
                    }
                    
                    function showQueue() {
                        console.log('Queue data:', playerState.queue);
                        if (!playerState.queue || playerState.queue.length === 0) {
                            alert('Queue ist leer');
                            return;
                        }
                        try {
                            const queueList = playerState.queue.map((s, i) => {
                                const title = s.title || s.name || 'Unknown';
                                const artist = s.artist || 'Unknown';
                                return (i + 1) + '. ' + title + ' - ' + artist;
                            }).join('\\n');
                            alert('Queue:\\n' + queueList);
                        } catch (e) {
                            console.error('Queue display error:', e);
                            alert('Queue konnte nicht angezeigt werden');
                        }
                    }
                    
                    // ==================== MEDIA SESSION API ====================
                    
                    function updateMediaSession(song) {
                        if (!('mediaSession' in navigator)) return;
                        
                        navigator.mediaSession.metadata = new MediaMetadata({
                            title: song.title || 'Unknown',
                            artist: song.artist || 'Unknown Artist',
                            album: song.album || 'Unknown Album',
                            artwork: song.albumArt ? [{ src: song.albumArt }] : []
                        });
                        
                        navigator.mediaSession.setActionHandler('play', togglePlayPause);
                        navigator.mediaSession.setActionHandler('pause', togglePlayPause);
                        navigator.mediaSession.setActionHandler('previoustrack', playerPrev);
                        navigator.mediaSession.setActionHandler('nexttrack', playerNext);
                        navigator.mediaSession.setActionHandler('seekto', (details) => {
                            if (details.seekTime) {
                                if (isWebActive) {
                                    webAudio.currentTime = details.seekTime;
                                }
                                const formData = new FormData();
                                formData.append('position', Math.floor(details.seekTime * 1000));
                                fetch('/player/seek', { method: 'POST', body: formData });
                            }
                        });
                    }
                    
                    // Update position state for OS media controls
                    setInterval(() => {
                        if (isWebActive && webAudio.duration && !isNaN(webAudio.duration) && 'mediaSession' in navigator) {
                            navigator.mediaSession.setPositionState({
                                duration: webAudio.duration,
                                playbackRate: webAudio.playbackRate,
                                position: webAudio.currentTime
                            });
                        }
                    }, 1000);
                    
                    // ==================== HEARTBEAT ====================
                    
                    setInterval(() => {
                        const formData = new FormData();
                        formData.append('deviceId', deviceId);
                        fetch('/player/heartbeat', { method: 'POST', body: formData }).catch(() => {});
                    }, 5000);
                    
                    // ==================== INIT ====================
                    
                    // Try SSE first
                    connectSSE();
                    
                    // Polling as reliable fallback (SSE can be flaky with NanoHTTPD)
                    async function updatePlayerState() {
                        try {
                            const response = await fetch('/player/state');
                            if (!response.ok) return;
                            const state = await response.json();
                            handleStateUpdate(state);
                        } catch (err) {}
                    }
                    
                    // Poll every 500ms for reliable updates
                    setInterval(updatePlayerState, 500);
                    
                    // Initial state load
                    updatePlayerState();
                    
                    // ==================== SPOTIFY IMPORT ====================
                    
                    let spotifyPollingInterval = null;
                    let spotifyImporting = false;
                    
                    // Setup Spotify CSV drop zone
                    const spotifyDropZone = document.getElementById('spotifyDropZone');
                    const spotifyCsvInput = document.getElementById('spotifyCsvInput');
                    
                    if (spotifyDropZone) {
                        spotifyDropZone.addEventListener('dragover', (e) => {
                            e.preventDefault();
                            spotifyDropZone.classList.add('dragover');
                        });
                        spotifyDropZone.addEventListener('dragleave', () => {
                            spotifyDropZone.classList.remove('dragover');
                        });
                        spotifyDropZone.addEventListener('drop', (e) => {
                            e.preventDefault();
                            spotifyDropZone.classList.remove('dragover');
                            if (e.dataTransfer.files.length > 0) {
                                handleSpotifyCsv(e.dataTransfer.files[0]);
                            }
                        });
                    }
                    
                    if (spotifyCsvInput) {
                        spotifyCsvInput.addEventListener('change', () => {
                            if (spotifyCsvInput.files.length > 0) {
                                handleSpotifyCsv(spotifyCsvInput.files[0]);
                            }
                        });
                    }
                    
                    function showSpotifyImport() {
                        document.getElementById('allSongsView').style.display = 'none';
                        document.getElementById('playlistView').style.display = 'none';
                        document.getElementById('spotifyView').style.display = 'block';
                        currentPlaylist = null;
                        
                        // Check current status
                        checkSpotifyStatus();
                    }
                    
                    async function checkSpotifyStatus() {
                        try {
                            const response = await fetch('/spotify/status');
                            const data = await response.json();
                            if (data.status === 'downloading' || data.status === 'initializing') {
                                spotifyImporting = true;
                                document.getElementById('spotifyProgress').classList.add('active');
                                startSpotifyPolling();
                                updateSpotifyUI(data);
                            }
                        } catch (e) {}
                    }
                    
                    async function handleSpotifyCsv(file) {
                        if (!file.name.endsWith('.csv')) {
                            alert('Bitte eine CSV Datei auswaehlen');
                            return;
                        }
                        
                        const csvContent = await file.text();
                        startSpotifyImport(csvContent);
                    }
                    
                    async function startSpotifyImport(csvContent) {
                        if (spotifyImporting) {
                            alert('Ein Import laeuft bereits');
                            return;
                        }
                        
                        spotifyImporting = true;
                        
                        // Show progress UI
                        const progressDiv = document.getElementById('spotifyProgress');
                        progressDiv.classList.add('active');
                        
                        // Reset UI
                        document.getElementById('spotifyProgressFill').style.width = '0%';
                        document.getElementById('spotifyTrackCount').textContent = '0 / 0';
                        document.getElementById('spotifyCurrentTrack').textContent = 'Initialisiere Python...';
                        document.getElementById('spotifySuccessCount').textContent = '0';
                        document.getElementById('spotifyErrorCount').textContent = '0';
                        document.getElementById('spotifyLog').innerHTML = '';
                        document.getElementById('spotifyProgressTitle').textContent = 'Download laeuft...';
                        
                        // Get options
                        const skipInstrumentals = document.getElementById('spotifySkipInstrumentals').checked;
                        const targetPlaylist = document.getElementById('spotifyTargetPlaylist').value;
                        
                        // Start polling for progress
                        startSpotifyPolling();
                        
                        // Start import
                        try {
                            const formData = new FormData();
                            formData.append('csv', csvContent);
                            formData.append('skipInstrumentals', skipInstrumentals);
                            if (targetPlaylist) {
                                formData.append('targetPlaylist', targetPlaylist);
                            }
                            
                            const response = await fetch('/spotify/import', { method: 'POST', body: formData });
                            if (!response.ok) {
                                const error = await response.json();
                                throw new Error(error.error || 'Import fehlgeschlagen');
                            }
                            addSpotifyLog('Import gestartet...', 'info');
                            if (targetPlaylist) {
                                const playlistName = document.getElementById('spotifyTargetPlaylist').selectedOptions[0]?.text || targetPlaylist;
                                addSpotifyLog('Songs werden zu "' + playlistName + '" hinzugefuegt', 'info');
                            }
                        } catch (e) {
                            addSpotifyLog('Fehler: ' + e.message, 'error');
                            spotifyImporting = false;
                            stopSpotifyPolling();
                        }
                    }
                    
                    // Update playlist selector when playlists change
                    function updateSpotifyPlaylistSelector() {
                        const select = document.getElementById('spotifyTargetPlaylist');
                        if (!select) return;
                        
                        const currentValue = select.value;
                        select.innerHTML = '<option value="">-- Keine Playlist (nur downloaden) --</option>';
                        
                        playlists.forEach(p => {
                            const option = document.createElement('option');
                            option.value = p.id;
                            option.textContent = p.name + ' (' + p.songs.length + ' Songs)';
                            select.appendChild(option);
                        });
                        
                        // Restore previous selection if still valid
                        if (currentValue && playlists.some(p => p.id === currentValue)) {
                            select.value = currentValue;
                        }
                    }
                    
                    async function createPlaylistForSpotify() {
                        const name = prompt('Name der neuen Playlist:');
                        if (!name || !name.trim()) return;
                        
                        try {
                            const response = await fetch('/playlists/create', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                                body: 'name=' + encodeURIComponent(name.trim())
                            });
                            
                            if (response.ok) {
                                const data = await response.json();
                                await loadPlaylists();
                                // Select the new playlist
                                document.getElementById('spotifyTargetPlaylist').value = data.id;
                            }
                        } catch (e) {
                            alert('Fehler beim Erstellen: ' + e.message);
                        }
                    }
                    
                    function startSpotifyPolling() {
                        if (spotifyPollingInterval) return;
                        spotifyPollingInterval = setInterval(pollSpotifyStatus, 1000);
                    }
                    
                    function stopSpotifyPolling() {
                        if (spotifyPollingInterval) {
                            clearInterval(spotifyPollingInterval);
                            spotifyPollingInterval = null;
                        }
                    }
                    
                    async function pollSpotifyStatus() {
                        try {
                            const response = await fetch('/spotify/status');
                            const data = await response.json();
                            updateSpotifyUI(data);
                        } catch (e) {
                            console.error('Polling error:', e);
                        }
                    }
                    
                    let lastSpotifyStatus = null;
                    
                    function updateSpotifyUI(data) {
                        const statusChanged = JSON.stringify(data) !== JSON.stringify(lastSpotifyStatus);
                        lastSpotifyStatus = data;
                        
                        switch (data.status) {
                            case 'idle':
                                if (spotifyImporting) {
                                    // Was importing, now idle - check if completed
                                }
                                break;
                                
                            case 'initializing':
                                document.getElementById('spotifyCurrentTrack').textContent = 'Initialisiere Python...';
                                break;
                                
                            case 'downloading':
                                document.getElementById('spotifyTrackCount').textContent = 
                                    (data.track || 0) + ' / ' + (data.total || 0);
                                document.getElementById('spotifyCurrentTrack').textContent = 
                                    (data.artist || '') + ' - ' + (data.name || 'Verarbeite...');
                                document.getElementById('spotifySuccessCount').textContent = data.completed || 0;
                                document.getElementById('spotifyErrorCount').textContent = data.failed || 0;
                                
                                const percent = data.total > 0 ? ((data.track || 0) / data.total) * 100 : 0;
                                document.getElementById('spotifyProgressFill').style.width = percent + '%';
                                break;
                                
                            case 'completed':
                                spotifyImporting = false;
                                stopSpotifyPolling();
                                document.getElementById('spotifyProgressTitle').textContent = 'Download abgeschlossen!';
                                document.getElementById('spotifyProgressFill').style.width = '100%';
                                document.getElementById('spotifySuccessCount').textContent = data.downloaded || 0;
                                document.getElementById('spotifyErrorCount').textContent = data.failed || 0;
                                if (statusChanged) {
                                    addSpotifyLog('Fertig! ' + (data.downloaded || 0) + ' heruntergeladen, ' + (data.failed || 0) + ' fehlgeschlagen', 'info');
                                    loadFiles();
                                }
                                break;
                                
                            case 'cancelled':
                                spotifyImporting = false;
                                stopSpotifyPolling();
                                document.getElementById('spotifyProgressTitle').textContent = 'Abgebrochen';
                                if (statusChanged) {
                                    addSpotifyLog('Download abgebrochen', 'error');
                                }
                                break;
                                
                            case 'error':
                                spotifyImporting = false;
                                stopSpotifyPolling();
                                document.getElementById('spotifyProgressTitle').textContent = 'Fehler';
                                if (statusChanged) {
                                    addSpotifyLog('Fehler: ' + (data.message || 'Unbekannter Fehler'), 'error');
                                }
                                break;
                        }
                    }
                    
                    function addSpotifyLog(message, type) {
                        const log = document.getElementById('spotifyLog');
                        const item = document.createElement('div');
                        item.className = 'spotify-log-item ' + type;
                        item.textContent = message;
                        log.appendChild(item);
                        log.scrollTop = log.scrollHeight;
                    }
                    
                    async function cancelSpotifyImport() {
                        try {
                            await fetch('/spotify/cancel', { method: 'POST' });
                            stopSpotifyPolling();
                        } catch (e) {
                            console.error('Cancel error:', e);
                        }
                    }
                    
                    async function testSpotifyPython() {
                        const resultDiv = document.getElementById('spotifyTestResult');
                        resultDiv.textContent = 'Teste Python...';
                        resultDiv.style.color = '#888';
                        try {
                            const response = await fetch('/spotify/test');
                            const data = await response.json();
                            if (data.success) {
                                resultDiv.textContent = '✓ ' + data.message;
                                resultDiv.style.color = '#1db954';
                            } else {
                                resultDiv.textContent = '✗ ' + data.error;
                                resultDiv.style.color = '#e74c3c';
                            }
                        } catch (e) {
                            resultDiv.textContent = '✗ Fehler: ' + e.message;
                            resultDiv.style.color = '#e74c3c';
                        }
                    }
                    
                    async function testCsvHeaders() {
                        const resultDiv = document.getElementById('spotifyTestResult');
                        const fileInput = document.getElementById('spotifyCsvInput');
                        
                        if (!fileInput.files || fileInput.files.length === 0) {
                            resultDiv.textContent = 'Bitte zuerst eine CSV Datei auswaehlen';
                            resultDiv.style.color = '#e74c3c';
                            return;
                        }
                        
                        resultDiv.textContent = 'Teste CSV...';
                        resultDiv.style.color = '#888';
                        
                        try {
                            const csvContent = await fileInput.files[0].text();
                            resultDiv.textContent = 'CSV gelesen, Laenge: ' + csvContent.length + '\\nErste 500 Zeichen:\\n' + csvContent.substring(0, 500);
                            resultDiv.style.color = '#1db954';
                            
                            // Also test server-side
                            const formData = new FormData();
                            formData.append('csv', csvContent);
                            const response = await fetch('/spotify/testcsv', { method: 'POST', body: formData });
                            const data = await response.json();
                            resultDiv.textContent += '\\n\\nServer-Antwort:\\nHeaders: ' + data.headers + '\\nZeilen: ' + data.lines;
                        } catch (e) {
                            resultDiv.textContent = '✗ Fehler: ' + e.message;
                            resultDiv.style.color = '#e74c3c';
                        }
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun serveFileList(): Response {
        val jsonArray = JSONArray()
        
        for (song in allSongs) {
            val obj = JSONObject()
            obj.put("id", song.id)
            obj.put("name", song.title)
            obj.put("artist", song.artist)
            obj.put("album", song.album)
            obj.put("duration", song.duration)
            obj.put("path", song.path)
            // For web UI, use the /albumart/ endpoint (works for both MediaStore and downloaded songs)
            obj.put("albumArt", "/albumart/${java.net.URLEncoder.encode(song.id, "UTF-8")}")
            jsonArray.put(obj)
        }
        
        return newFixedLengthResponse(Response.Status.OK, "application/json", jsonArray.toString())
    }

    private fun serveEmbeddedAlbumArt(identifier: String): Response {
        val decoded = java.net.URLDecoder.decode(identifier, "UTF-8")
        
        // Find song by ID
        val song = allSongs.find { it.id == decoded }
        
        // First, try to get album art from MediaStore (for system-indexed songs)
        if (song?.albumArtUriString != null && song.albumArtUriString.startsWith("content://")) {
            try {
                val albumArtUri = android.net.Uri.parse(song.albumArtUriString)
                val inputStream = context.contentResolver.openInputStream(albumArtUri)
                if (inputStream != null) {
                    val bytes = inputStream.readBytes()
                    inputStream.close()
                    if (bytes.isNotEmpty()) {
                        val byteStream = java.io.ByteArrayInputStream(bytes)
                        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", byteStream, bytes.size.toLong())
                    }
                }
            } catch (e: Exception) {
                Log.d("SimpleWebServer", "No MediaStore album art for ${song.title}: ${e.message}")
            }
        }
        
        // Try to get embedded album art from the audio file
        val file: File = when {
            song != null -> File(song.path)
            File(decoded).exists() -> File(decoded)
            File(storageDir, decoded).exists() -> File(storageDir, decoded)
            else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
        
        if (file.exists()) {
            try {
                val mmr = android.media.MediaMetadataRetriever()
                mmr.setDataSource(file.absolutePath)
                
                val embeddedPicture = mmr.embeddedPicture
                mmr.release()
                
                if (embeddedPicture != null) {
                    // Detect image type from magic bytes
                    val mimeType = when {
                        embeddedPicture.size >= 3 && 
                            embeddedPicture[0] == 0xFF.toByte() && 
                            embeddedPicture[1] == 0xD8.toByte() && 
                            embeddedPicture[2] == 0xFF.toByte() -> "image/jpeg"
                        embeddedPicture.size >= 8 && 
                            embeddedPicture[0] == 0x89.toByte() && 
                            embeddedPicture[1] == 0x50.toByte() -> "image/png"
                        embeddedPicture.size >= 4 && 
                            String(embeddedPicture.take(4).toByteArray()) == "RIFF" -> "image/webp"
                        else -> "image/jpeg" // Default fallback
                    }
                    
                    val inputStream = java.io.ByteArrayInputStream(embeddedPicture)
                    return newFixedLengthResponse(Response.Status.OK, mimeType, inputStream, embeddedPicture.size.toLong())
                }
            } catch (e: Exception) {
                Log.w("SimpleWebServer", "Could not extract album art from ${file.name}: ${e.message}")
            }
        }
        
        // Return 404 if no album art found
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No album art found")
    }

    private fun serveFile(session: IHTTPSession, identifier: String): Response {
        val decoded = java.net.URLDecoder.decode(identifier, "UTF-8")
        
        // Find file by song ID or by path
        val file: File = when {
            // Try to find by song ID (id is now String)
            allSongs.any { it.id == decoded } -> {
                allSongs.find { it.id == decoded }?.let { File(it.path) }
                    ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Song not found")
            }
            // Try direct path (for MediaStore songs)
            File(decoded).exists() -> File(decoded)
            // Try in storageDir (for uploaded files)
            File(storageDir, decoded).exists() -> File(storageDir, decoded)
            else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
        
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
        
        val mimeType = when {
            file.name.endsWith(".mp3", true) -> "audio/mpeg"
            file.name.endsWith(".wav", true) -> "audio/wav"
            file.name.endsWith(".ogg", true) -> "audio/ogg"
            file.name.endsWith(".flac", true) -> "audio/flac"
            file.name.endsWith(".m4a", true) -> "audio/mp4"
            else -> "application/octet-stream"
        }

        val fileLength = file.length()
        val rangeHeader = session.headers["range"]

        // Handle range request
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                val rangeSpec = rangeHeader.removePrefix("bytes=")
                val parts = rangeSpec.split("-")
                val start = parts[0].toLongOrNull() ?: 0L
                val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
                    minOf(parts[1].toLong(), fileLength - 1)
                } else {
                    fileLength - 1
                }
                
                if (start >= fileLength || start > end) {
                    val response = newFixedLengthResponse(
                        Response.Status.RANGE_NOT_SATISFIABLE, 
                        "text/plain", 
                        "Range not satisfiable"
                    )
                    response.addHeader("Content-Range", "bytes */$fileLength")
                    return response
                }

                val contentLength = end - start + 1
                val fis = FileInputStream(file)
                fis.skip(start)
                
                val response = newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT, 
                    mimeType, 
                    fis,
                    contentLength
                )
                response.addHeader("Content-Range", "bytes $start-$end/$fileLength")
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("Content-Length", contentLength.toString())
                return response
            } catch (e: Exception) {
                Log.e("SimpleWebServer", "Range request error", e)
            }
        }

        // Full file - stream it
        val response = newFixedLengthResponse(
            Response.Status.OK, 
            mimeType, 
            FileInputStream(file),
            fileLength
        )
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", fileLength.toString())
        return response
    }

    /**
     * Serve file as download (with Content-Disposition: attachment)
     */
    private fun serveSaveFile(identifier: String): Response {
        val decoded = java.net.URLDecoder.decode(identifier, "UTF-8")
        
        // Find song and file
        val song = allSongs.find { it.id == decoded }
        val file: File = when {
            song != null -> File(song.path)
            File(decoded).exists() -> File(decoded)
            File(storageDir, decoded).exists() -> File(storageDir, decoded)
            else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
        
        if (!file.exists()) {
            // Try content URI for MediaStore songs
            if (song != null) {
                try {
                    val contentUri = android.net.Uri.parse(song.contentUriString)
                    val inputStream = context.contentResolver.openInputStream(contentUri)
                    if (inputStream != null) {
                        val mimeType = song.mimeType ?: "audio/mpeg"
                        val fileName = sanitizeFileName("${song.artist} - ${song.title}.${getExtension(file.name, mimeType)}")
                        val response = newChunkedResponse(Response.Status.OK, mimeType, inputStream)
                        response.addHeader("Content-Disposition", "attachment; filename=\"$fileName\"")
                        return response
                    }
                } catch (e: Exception) {
                    Log.e("SimpleWebServer", "Error streaming from content URI for save", e)
                }
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
        
        val mimeType = when {
            file.name.endsWith(".mp3", true) -> "audio/mpeg"
            file.name.endsWith(".wav", true) -> "audio/wav"
            file.name.endsWith(".ogg", true) -> "audio/ogg"
            file.name.endsWith(".flac", true) -> "audio/flac"
            file.name.endsWith(".m4a", true) -> "audio/mp4"
            else -> "application/octet-stream"
        }
        
        val fileLength = file.length()
        val fileName = if (song != null) {
            sanitizeFileName("${song.artist} - ${song.title}.${file.extension}")
        } else {
            file.name
        }
        
        val response = newFixedLengthResponse(
            Response.Status.OK,
            mimeType,
            FileInputStream(file),
            fileLength
        )
        response.addHeader("Content-Disposition", "attachment; filename=\"$fileName\"")
        response.addHeader("Content-Length", fileLength.toString())
        return response
    }
    
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
    
    private fun getExtension(fileName: String, mimeType: String): String {
        val ext = fileName.substringAfterLast('.', "")
        if (ext.isNotEmpty()) return ext
        return when (mimeType) {
            "audio/mpeg" -> "mp3"
            "audio/wav" -> "wav"
            "audio/ogg" -> "ogg"
            "audio/flac" -> "flac"
            "audio/mp4" -> "m4a"
            else -> "mp3"
        }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val tempFilePath = files["file"]
            if (tempFilePath != null) {
                val tempFile = File(tempFilePath)
                val params = session.parameters
                val originalName = params["file"]?.firstOrNull() ?: "uploaded_file"
                val destFile = File(storageDir, originalName)
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
                Log.d("SimpleWebServer", "File saved: ${destFile.absolutePath}")
                updateLastModified()
                onFileChanged?.invoke()
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
            }
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No file received")
        } catch (e: Exception) {
            Log.e("SimpleWebServer", "Upload error", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
        }
    }

    private fun handleDelete(identifier: String): Response {
        val decoded = java.net.URLDecoder.decode(identifier, "UTF-8")
        
        // First, try to find song by ID
        val song = allSongs.find { it.id == decoded }
        val file: File = when {
            song != null -> File(song.path)
            File(storageDir, decoded).exists() -> File(storageDir, decoded)
            File(decoded).exists() -> File(decoded)
            else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
        
        // Only allow deletion of files in storageDir for security
        if (!file.absolutePath.startsWith(storageDir.absolutePath)) {
            Log.w("SimpleWebServer", "Attempted to delete file outside storage dir: ${file.absolutePath}")
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Cannot delete system files")
        }
        
        return if (file.exists() && file.delete()) {
            Log.d("SimpleWebServer", "File deleted: ${file.absolutePath}")
            
            // Also delete cached album art if it exists
            val cacheDir = File(context.cacheDir, "albumart")
            val artHash = file.absolutePath.hashCode().toString()
            File(cacheDir, "$artHash.jpg").delete()
            
            updateLastModified()
            onFileChanged?.invoke()
            newFixedLengthResponse(Response.Status.OK, "text/plain", "Deleted")
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found or could not be deleted")
        }
    }
    
    // ==================== PLAYLIST SHARING ====================
    
    /**
     * Serve playlist metadata for sharing
     */
    private fun serveSharedPlaylist(playlistId: String): Response {
        val decodedId = java.net.URLDecoder.decode(playlistId, "UTF-8")
        val playlistData = playlistManager.exportForShare(decodedId)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"Playlist not found"}""")
        
        return newFixedLengthResponse(Response.Status.OK, "application/json", playlistData.toString())
    }
    
    /**
     * Serve list of songs in a shared playlist with full metadata
     */
    private fun serveSharedPlaylistSongs(playlistId: String): Response {
        val decodedId = java.net.URLDecoder.decode(playlistId, "UTF-8")
        val playlist = playlistManager.get(decodedId)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"Playlist not found"}""")
        
        val songsArray = JSONArray()
        for (songId in playlist.songs) {
            val song = allSongs.find { it.id == songId }
            if (song != null) {
                songsArray.put(JSONObject().apply {
                    put("id", song.id)
                    put("title", song.title)
                    put("artist", song.artist)
                    put("album", song.album)
                    put("duration", song.duration)
                    put("path", song.path)
                })
            }
        }
        
        val response = JSONObject().apply {
            put("playlistId", decodedId)
            put("playlistName", playlist.name)
            put("songCount", songsArray.length())
            put("songs", songsArray)
        }
        
        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
    }
    
    /**
     * Serve a single song file from a shared playlist
     */
    private fun serveSharedSong(playlistId: String, songId: String): Response {
        val decodedPlaylistId = java.net.URLDecoder.decode(playlistId, "UTF-8")
        val decodedSongId = java.net.URLDecoder.decode(songId, "UTF-8")
        
        // Verify the song is in the playlist
        val playlist = playlistManager.get(decodedPlaylistId)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Playlist not found")
        
        if (!playlist.songs.contains(decodedSongId)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Song not in playlist")
        }
        
        // Find the song
        val song = allSongs.find { it.id == decodedSongId }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Song not found")
        
        // Get the file
        val file = File(song.path)
        if (!file.exists()) {
            // Try content URI for MediaStore songs
            try {
                val contentUri = android.net.Uri.parse(song.contentUriString)
                val inputStream = context.contentResolver.openInputStream(contentUri)
                if (inputStream != null) {
                    val mimeType = song.mimeType ?: "audio/mpeg"
                    return newChunkedResponse(Response.Status.OK, mimeType, inputStream)
                }
            } catch (e: Exception) {
                Log.e("SimpleWebServer", "Error streaming from content URI", e)
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not accessible")
        }
        
        val mimeType = song.mimeType ?: "audio/mpeg"
        val fileLength = file.length()
        
        val response = newFixedLengthResponse(
            Response.Status.OK,
            mimeType,
            FileInputStream(file),
            fileLength
        )
        response.addHeader("Content-Disposition", "attachment; filename=\"${song.title}.${file.extension}\"")
        response.addHeader("Content-Length", fileLength.toString())
        return response
    }
    
    /**
     * Generate a share token for a playlist
     */
    fun generatePlaylistShareUrl(playlistId: String, localIp: String): String {
        return "http://$localIp:8080/share/playlist/$playlistId"
    }
}

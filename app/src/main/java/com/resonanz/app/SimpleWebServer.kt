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
            
            // Player sync endpoints
            method == Method.GET && uri == "/player/events" -> protectedRoute(session) { serveSSE(session) }
            
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
        val name = session.parameters["name"]?.firstOrNull() ?: "New Playlist"
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
            broadcastState()
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
    
    private fun serveSSE(session: IHTTPSession): Response {
        val deviceId = session.parameters["deviceId"]?.firstOrNull() ?: "web"
        
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

    private fun serveLoginPage(session: IHTTPSession): Response {
        val token = UUID.randomUUID().toString()
        pendingSessions[token] = System.currentTimeMillis()
        
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Resonanz - Connect</title>
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet">
                <style>
                    :root {
                        --bg-primary: #0a0a0a;
                        --bg-secondary: #141414;
                        --bg-tertiary: #1a1a1a;
                        --bg-elevated: #242424;
                        --accent: #1db954;
                        --accent-hover: #1ed760;
                        --accent-glow: rgba(29, 185, 84, 0.4);
                        --text-primary: #ffffff;
                        --text-secondary: rgba(255,255,255,0.7);
                        --text-tertiary: rgba(255,255,255,0.5);
                        --border-subtle: rgba(255,255,255,0.08);
                        --border-visible: rgba(255,255,255,0.15);
                    }
                    
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    
                    body {
                        font-family: 'Inter', system-ui, -apple-system, BlinkMacSystemFont, sans-serif;
                        background: var(--bg-primary);
                        color: var(--text-primary);
                        min-height: 100vh;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        padding: 24px;
                        position: relative;
                        overflow: hidden;
                    }
                    
                    /* Animated background gradient */
                    body::before {
                        content: '';
                        position: absolute;
                        top: -50%;
                        left: -50%;
                        width: 200%;
                        height: 200%;
                        background: radial-gradient(circle at 30% 20%, rgba(29, 185, 84, 0.08) 0%, transparent 50%),
                                    radial-gradient(circle at 70% 80%, rgba(29, 185, 84, 0.05) 0%, transparent 50%);
                        animation: gradientMove 20s ease-in-out infinite;
                        z-index: 0;
                    }
                    
                    @keyframes gradientMove {
                        0%, 100% { transform: translate(0, 0) rotate(0deg); }
                        33% { transform: translate(2%, 2%) rotate(1deg); }
                        66% { transform: translate(-1%, 1%) rotate(-1deg); }
                    }
                    
                    @keyframes fadeInUp {
                        from { opacity: 0; transform: translateY(20px); }
                        to { opacity: 1; transform: translateY(0); }
                    }
                    
                    @keyframes pulse {
                        0%, 100% { opacity: 1; }
                        50% { opacity: 0.5; }
                    }
                    
                    @keyframes glow {
                        0%, 100% { box-shadow: 0 0 20px var(--accent-glow), 0 0 40px rgba(29, 185, 84, 0.2); }
                        50% { box-shadow: 0 0 30px var(--accent-glow), 0 0 60px rgba(29, 185, 84, 0.3); }
                    }
                    
                    @keyframes spin {
                        from { transform: rotate(0deg); }
                        to { transform: rotate(360deg); }
                    }
                    
                    .login-card {
                        position: relative;
                        z-index: 1;
                        background: rgba(20, 20, 20, 0.8);
                        backdrop-filter: blur(40px);
                        -webkit-backdrop-filter: blur(40px);
                        border: 1px solid var(--border-subtle);
                        border-radius: 28px;
                        padding: 48px;
                        text-align: center;
                        max-width: 420px;
                        width: 100%;
                        animation: fadeInUp 0.6s ease-out;
                    }
                    
                    .logo {
                        font-size: 2.5rem;
                        font-weight: 700;
                        letter-spacing: -0.03em;
                        margin-bottom: 8px;
                        background: linear-gradient(135deg, var(--text-primary) 0%, var(--text-secondary) 100%);
                        -webkit-background-clip: text;
                        -webkit-text-fill-color: transparent;
                        background-clip: text;
                    }
                    
                    .subtitle {
                        color: var(--text-tertiary);
                        font-size: 1rem;
                        font-weight: 400;
                        margin-bottom: 36px;
                    }
                    
                    .qr-wrapper {
                        position: relative;
                        display: inline-block;
                        margin-bottom: 32px;
                    }
                    
                    .qr-container {
                        background: #ffffff;
                        padding: 20px;
                        border-radius: 20px;
                        position: relative;
                        animation: glow 3s ease-in-out infinite;
                    }
                    
                    .qr-container svg {
                        display: block;
                        border-radius: 8px;
                    }
                    
                    .qr-corner {
                        position: absolute;
                        width: 24px;
                        height: 24px;
                        border: 3px solid var(--accent);
                    }
                    .qr-corner.tl { top: -4px; left: -4px; border-right: none; border-bottom: none; border-radius: 8px 0 0 0; }
                    .qr-corner.tr { top: -4px; right: -4px; border-left: none; border-bottom: none; border-radius: 0 8px 0 0; }
                    .qr-corner.bl { bottom: -4px; left: -4px; border-right: none; border-top: none; border-radius: 0 0 0 8px; }
                    .qr-corner.br { bottom: -4px; right: -4px; border-left: none; border-top: none; border-radius: 0 0 8px 0; }
                    
                    .instructions {
                        color: var(--text-secondary);
                        font-size: 0.9375rem;
                        line-height: 1.6;
                        margin-bottom: 24px;
                    }
                    
                    .instructions strong {
                        color: var(--accent);
                        font-weight: 600;
                    }
                    
                    .status {
                        display: inline-flex;
                        align-items: center;
                        gap: 10px;
                        padding: 12px 24px;
                        background: var(--bg-elevated);
                        border: 1px solid var(--border-subtle);
                        border-radius: 100px;
                        font-size: 0.875rem;
                        font-weight: 500;
                        color: var(--text-secondary);
                        transition: all 0.3s ease;
                    }
                    
                    .status.connected {
                        background: rgba(29, 185, 84, 0.15);
                        border-color: var(--accent);
                        color: var(--accent);
                    }
                    
                    .status-dot {
                        width: 8px;
                        height: 8px;
                        border-radius: 50%;
                        background: var(--text-tertiary);
                        animation: pulse 2s ease-in-out infinite;
                    }
                    
                    .status.connected .status-dot {
                        background: var(--accent);
                        animation: none;
                    }
                    
                    .spinner {
                        width: 16px;
                        height: 16px;
                        border: 2px solid var(--border-visible);
                        border-top-color: var(--accent);
                        border-radius: 50%;
                        animation: spin 0.8s linear infinite;
                    }
                    
                    .footer {
                        position: absolute;
                        bottom: 24px;
                        left: 0;
                        right: 0;
                        text-align: center;
                        color: var(--text-tertiary);
                        font-size: 0.75rem;
                        z-index: 1;
                    }
                </style>
            </head>
            <body>
                <div class="login-card">
                    <h1 class="logo">Resonanz</h1>
                    <p class="subtitle">Connect with your phone</p>
                    
                    <div class="qr-wrapper">
                        <div class="qr-container" id="qrcode">
                            <div class="spinner" style="width: 140px; height: 140px; border-width: 3px;"></div>
                        </div>
                        <div class="qr-corner tl"></div>
                        <div class="qr-corner tr"></div>
                        <div class="qr-corner bl"></div>
                        <div class="qr-corner br"></div>
                    </div>
                    
                    <p class="instructions">
                        Open the <strong>Resonanz</strong> app on your phone<br>
                        and scan this QR code to connect
                    </p>
                    
                    <div class="status" id="status">
                        <span class="status-dot"></span>
                        <span id="statusText">Waiting for connection...</span>
                    </div>
                </div>
                
                <div class="footer">Resonanz Music Player</div>
                
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
                                const statusEl = document.getElementById('status');
                                const statusText = document.getElementById('statusText');
                                statusEl.classList.add('connected');
                                statusText.textContent = 'Connected!';
                                setTimeout(() => window.location.href = '/app', 800);
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
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet">
                <style>
                    :root {
                        --bg-primary: #0a0a0a;
                        --bg-secondary: #111111;
                        --bg-tertiary: #181818;
                        --bg-elevated: #242424;
                        --bg-hover: #2a2a2a;
                        --accent: #1db954;
                        --accent-hover: #1ed760;
                        --accent-glow: rgba(29, 185, 84, 0.4);
                        --accent-subtle: rgba(29, 185, 84, 0.1);
                        --text-primary: #ffffff;
                        --text-secondary: rgba(255,255,255,0.7);
                        --text-tertiary: rgba(255,255,255,0.5);
                        --text-muted: rgba(255,255,255,0.35);
                        --border-subtle: rgba(255,255,255,0.06);
                        --border-visible: rgba(255,255,255,0.12);
                        --shadow-lg: 0 25px 50px -12px rgba(0,0,0,0.5);
                        --shadow-xl: 0 35px 60px -15px rgba(0,0,0,0.6);
                        --radius-sm: 6px;
                        --radius-md: 10px;
                        --radius-lg: 14px;
                        --radius-xl: 20px;
                        --sidebar-width: 280px;
                        --player-height: 90px;
                    }
                    
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    
                    body {
                        font-family: 'Inter', system-ui, -apple-system, BlinkMacSystemFont, sans-serif;
                        background: var(--bg-primary);
                        color: var(--text-primary);
                        min-height: 100vh;
                        -webkit-font-smoothing: antialiased;
                        -moz-osx-font-smoothing: grayscale;
                    }
                    
                    /* Scrollbar styling */
                    ::-webkit-scrollbar { width: 8px; height: 8px; }
                    ::-webkit-scrollbar-track { background: transparent; }
                    ::-webkit-scrollbar-thumb { background: var(--border-visible); border-radius: 4px; }
                    ::-webkit-scrollbar-thumb:hover { background: var(--text-tertiary); }
                    
                    /* Animations */
                    @keyframes fadeIn { 
                        from { opacity: 0; transform: translateY(8px); } 
                        to { opacity: 1; transform: translateY(0); }
                    }
                    @keyframes fadeInScale { 
                        from { opacity: 0; transform: scale(0.95); } 
                        to { opacity: 1; transform: scale(1); }
                    }
                    @keyframes slideUp { 
                        from { opacity: 0; transform: translateY(100%); } 
                        to { opacity: 1; transform: translateY(0); }
                    }
                    @keyframes slideDown {
                        from { opacity: 1; transform: translateY(0); }
                        to { opacity: 0; transform: translateY(100%); }
                    }
                    @keyframes pulse { 
                        0%, 100% { opacity: 1; } 
                        50% { opacity: 0.5; }
                    }
                    @keyframes spin {
                        from { transform: rotate(0deg); }
                        to { transform: rotate(360deg); }
                    }
                    @keyframes shimmer {
                        0% { background-position: -200% 0; }
                        100% { background-position: 200% 0; }
                    }
                    
                    .view-enter { animation: fadeIn 0.25s ease-out; }
                    
                    /* Layout */
                    .container { 
                        display: flex; 
                        height: 100vh;
                        overflow: hidden;
                    }
                    
                    /* Sidebar */
                    .sidebar {
                        width: var(--sidebar-width);
                        background: rgba(17, 17, 17, 0.95);
                        backdrop-filter: blur(20px);
                        -webkit-backdrop-filter: blur(20px);
                        padding: 24px 16px;
                        overflow-y: auto;
                        border-right: 1px solid var(--border-subtle);
                        display: flex;
                        flex-direction: column;
                        flex-shrink: 0;
                    }
                    
                    .main { 
                        flex: 1; 
                        padding: 24px 32px; 
                        padding-bottom: calc(var(--player-height) + 32px);
                        overflow-y: auto;
                        overflow-x: hidden;
                    }
                    
                    /* Typography */
                    h1 { 
                        font-size: 1.75rem; 
                        font-weight: 700; 
                        letter-spacing: -0.02em;
                        margin-bottom: 8px;
                    }
                    h2 { 
                        font-size: 0.75rem; 
                        color: var(--text-tertiary); 
                        text-transform: uppercase;
                        letter-spacing: 0.08em;
                        font-weight: 600;
                        margin: 28px 0 12px; 
                    }
                    
                    /* Buttons */
                    .btn {
                        background: var(--bg-elevated);
                        color: var(--text-primary);
                        border: 1px solid var(--border-visible);
                        padding: 10px 20px;
                        cursor: pointer;
                        font-size: 0.875rem;
                        font-weight: 500;
                        font-family: inherit;
                        text-decoration: none;
                        display: inline-flex;
                        align-items: center;
                        justify-content: center;
                        gap: 8px;
                        border-radius: var(--radius-lg);
                        transition: all 0.2s ease;
                    }
                    .btn:hover { 
                        background: var(--bg-hover); 
                        border-color: var(--border-visible);
                        transform: translateY(-1px);
                    }
                    .btn:active { transform: translateY(0); }
                    .btn-small { 
                        padding: 6px 12px; 
                        font-size: 0.8125rem;
                        border-radius: var(--radius-md);
                    }
                    a.btn { line-height: 1; }
                    .btn-primary {
                        background: var(--accent);
                        border-color: var(--accent);
                        color: #000;
                        font-weight: 600;
                    }
                    .btn-primary:hover {
                        background: var(--accent-hover);
                        border-color: var(--accent-hover);
                    }
                    .btn-danger { 
                        background: rgba(239, 68, 68, 0.1); 
                        border-color: rgba(239, 68, 68, 0.3);
                        color: #ef4444;
                    }
                    .btn-danger:hover { 
                        background: rgba(239, 68, 68, 0.2);
                        border-color: rgba(239, 68, 68, 0.4);
                    }
                    .btn-download { 
                        background: var(--accent-subtle);
                        border-color: rgba(29, 185, 84, 0.3);
                        color: var(--accent);
                    }
                    .btn-download:hover { 
                        background: rgba(29, 185, 84, 0.2);
                        border-color: rgba(29, 185, 84, 0.4);
                    }
                    .btn-ghost {
                        background: transparent;
                        border-color: transparent;
                    }
                    .btn-ghost:hover {
                        background: var(--bg-tertiary);
                    }
                    .btn-icon {
                        width: 40px;
                        height: 40px;
                        padding: 0;
                        border-radius: 50%;
                    }
                    
                    /* Sidebar Logo */
                    .sidebar-logo {
                        font-size: 1.5rem;
                        font-weight: 700;
                        letter-spacing: -0.03em;
                        padding: 0 8px;
                        margin-bottom: 24px;
                        background: linear-gradient(135deg, var(--text-primary) 0%, var(--accent) 100%);
                        -webkit-background-clip: text;
                        -webkit-text-fill-color: transparent;
                        background-clip: text;
                    }
                    
                    /* Nav Items */
                    .nav-item {
                        display: flex;
                        align-items: center;
                        gap: 12px;
                        padding: 12px 14px;
                        cursor: pointer;
                        border-radius: var(--radius-md);
                        margin-bottom: 4px;
                        font-size: 0.9375rem;
                        font-weight: 500;
                        color: var(--text-secondary);
                        transition: all 0.15s ease;
                        position: relative;
                    }
                    .nav-item:hover { 
                        background: var(--bg-tertiary);
                        color: var(--text-primary);
                    }
                    .nav-item.active { 
                        background: var(--bg-tertiary);
                        color: var(--text-primary);
                    }
                    .nav-item.active::before {
                        content: '';
                        position: absolute;
                        left: 0;
                        top: 50%;
                        transform: translateY(-50%);
                        width: 3px;
                        height: 20px;
                        background: var(--accent);
                        border-radius: 0 2px 2px 0;
                    }
                    .nav-icon {
                        width: 20px;
                        height: 20px;
                        opacity: 0.8;
                        flex-shrink: 0;
                    }
                    .nav-item:hover .nav-icon,
                    .nav-item.active .nav-icon {
                        opacity: 1;
                    }
                    
                    /* Playlist Items */
                    .playlist-item {
                        display: flex;
                        align-items: center;
                        gap: 12px;
                        padding: 10px 14px;
                        cursor: pointer;
                        border-radius: var(--radius-md);
                        margin-bottom: 2px;
                        font-size: 0.875rem;
                        color: var(--text-secondary);
                        transition: all 0.15s ease;
                    }
                    .playlist-item:hover { 
                        background: var(--bg-tertiary);
                        color: var(--text-primary);
                    }
                    .playlist-item.active { 
                        background: var(--bg-tertiary);
                        color: var(--text-primary);
                    }
                    .playlist-icon {
                        width: 40px;
                        height: 40px;
                        background: linear-gradient(135deg, var(--bg-elevated) 0%, var(--bg-tertiary) 100%);
                        border-radius: var(--radius-sm);
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        flex-shrink: 0;
                        color: var(--text-tertiary);
                    }
                    .playlist-item:hover .playlist-icon,
                    .playlist-item.active .playlist-icon {
                        color: var(--text-secondary);
                    }
                    .playlist-info { flex: 1; min-width: 0; }
                    .playlist-name { 
                        font-weight: 500;
                        white-space: nowrap;
                        overflow: hidden;
                        text-overflow: ellipsis;
                    }
                    .playlist-count {
                        font-size: 0.75rem;
                        color: var(--text-muted);
                        margin-top: 2px;
                    }
                    
                    /* Upload Area */
                    .upload-area {
                        border: 2px dashed var(--border-visible);
                        padding: 32px;
                        text-align: center;
                        margin-bottom: 32px;
                        background: var(--bg-secondary);
                        border-radius: var(--radius-xl);
                        transition: all 0.2s ease;
                    }
                    .upload-area:hover {
                        border-color: var(--text-tertiary);
                        background: var(--bg-tertiary);
                    }
                    .upload-area.dragover { 
                        border-color: var(--accent);
                        background: var(--accent-subtle);
                    }
                    .upload-icon {
                        width: 48px;
                        height: 48px;
                        margin: 0 auto 16px;
                        opacity: 0.5;
                    }
                    input[type="file"] { display: none; }
                    
                    /* Page Header */
                    .page-header {
                        display: flex;
                        align-items: center;
                        justify-content: space-between;
                        margin-bottom: 24px;
                        gap: 16px;
                    }
                    .page-title {
                        font-size: 2rem;
                        font-weight: 700;
                        letter-spacing: -0.02em;
                    }
                    .page-actions {
                        display: flex;
                        gap: 12px;
                        align-items: center;
                    }
                    
                    /* View Toggle */
                    .view-toggle {
                        display: flex;
                        background: var(--bg-secondary);
                        border-radius: var(--radius-md);
                        padding: 4px;
                        gap: 4px;
                    }
                    .view-toggle-btn {
                        padding: 8px 12px;
                        border: none;
                        background: transparent;
                        color: var(--text-tertiary);
                        cursor: pointer;
                        border-radius: var(--radius-sm);
                        transition: all 0.15s ease;
                        font-size: 1rem;
                    }
                    .view-toggle-btn:hover { color: var(--text-secondary); }
                    .view-toggle-btn.active {
                        background: var(--bg-elevated);
                        color: var(--text-primary);
                    }
                    .view-toggle-btn svg {
                        display: block;
                    }
                    
                    /* Song Grid */
                    .song-grid {
                        display: grid;
                        grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
                        gap: 24px;
                    }
                    .song-card {
                        background: var(--bg-secondary);
                        border-radius: var(--radius-lg);
                        padding: 16px;
                        cursor: pointer;
                        transition: all 0.2s ease;
                        position: relative;
                    }
                    .song-card:hover {
                        background: var(--bg-tertiary);
                        transform: translateY(-4px);
                    }
                    .song-card-art {
                        width: 100%;
                        aspect-ratio: 1;
                        background: var(--bg-tertiary);
                        border-radius: var(--radius-md);
                        margin-bottom: 14px;
                        position: relative;
                        overflow: hidden;
                    }
                    .song-card-art img {
                        width: 100%;
                        height: 100%;
                        object-fit: cover;
                    }
                    .song-card-play {
                        position: absolute;
                        bottom: 8px;
                        right: 8px;
                        width: 48px;
                        height: 48px;
                        background: var(--accent);
                        border-radius: 50%;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        color: #000;
                        font-size: 1.25rem;
                        opacity: 0;
                        transform: translateY(8px);
                        transition: all 0.2s ease;
                        box-shadow: 0 8px 24px rgba(0,0,0,0.4);
                        border: none;
                        cursor: pointer;
                    }
                    .song-card:hover .song-card-play {
                        opacity: 1;
                        transform: translateY(0);
                    }
                    .song-card-play:hover {
                        transform: scale(1.05);
                        background: var(--accent-hover);
                    }
                    .song-card-title {
                        font-weight: 600;
                        font-size: 0.9375rem;
                        margin-bottom: 4px;
                        white-space: nowrap;
                        overflow: hidden;
                        text-overflow: ellipsis;
                    }
                    .song-card-artist {
                        color: var(--text-tertiary);
                        font-size: 0.8125rem;
                        white-space: nowrap;
                        overflow: hidden;
                        text-overflow: ellipsis;
                    }
                    .song-card-menu {
                        position: absolute;
                        top: 8px;
                        right: 8px;
                        opacity: 0;
                        transition: opacity 0.15s ease;
                    }
                    .song-card:hover .song-card-menu { opacity: 1; }
                    
                    /* Song List View */
                    .song-list { display: none; }
                    .song-list.active { display: block; }
                    .song-grid.hidden { display: none; }
                    
                    .file-list { 
                        background: var(--bg-secondary);
                        border-radius: var(--radius-lg);
                        overflow: hidden;
                    }
                    .file-item {
                        display: flex;
                        align-items: center;
                        gap: 16px;
                        padding: 12px 16px;
                        border-bottom: 1px solid var(--border-subtle);
                        transition: background 0.15s ease;
                        cursor: pointer;
                    }
                    .file-item:last-child { border-bottom: none; }
                    .file-item:hover { background: var(--bg-tertiary); }
                    .file-item-art {
                        width: 48px;
                        height: 48px;
                        background: var(--bg-tertiary);
                        border-radius: var(--radius-sm);
                        flex-shrink: 0;
                        overflow: hidden;
                    }
                    .file-item-art img {
                        width: 100%;
                        height: 100%;
                        object-fit: cover;
                    }
                    .file-item-info { flex: 1; min-width: 0; }
                    .file-name { 
                        font-weight: 500; 
                        font-size: 0.9375rem;
                        white-space: nowrap;
                        overflow: hidden;
                        text-overflow: ellipsis;
                        margin-bottom: 2px;
                    }
                    .file-meta {
                        color: var(--text-tertiary);
                        font-size: 0.8125rem;
                        white-space: nowrap;
                        overflow: hidden;
                        text-overflow: ellipsis;
                    }
                    .file-duration {
                        color: var(--text-tertiary);
                        font-size: 0.8125rem;
                        font-variant-numeric: tabular-nums;
                        margin-right: 8px;
                    }
                    
                    /* Context Menu */
                    .song-menu-container { position: relative; }
                    .song-menu-btn {
                        background: transparent;
                        border: none;
                        color: var(--text-tertiary);
                        font-size: 1.25rem;
                        cursor: pointer;
                        padding: 8px;
                        line-height: 1;
                        border-radius: var(--radius-md);
                        transition: all 0.15s ease;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                    }
                    .song-menu-btn:hover { 
                        background: var(--bg-hover);
                        color: var(--text-primary);
                    }
                    .song-menu {
                        position: absolute;
                        right: 0;
                        top: calc(100% + 4px);
                        background: rgba(36, 36, 36, 0.98);
                        backdrop-filter: blur(20px);
                        -webkit-backdrop-filter: blur(20px);
                        border: 1px solid var(--border-visible);
                        border-radius: var(--radius-lg);
                        min-width: 200px;
                        box-shadow: var(--shadow-xl);
                        z-index: 1000;
                        display: none;
                        overflow: hidden;
                        animation: fadeInScale 0.15s ease-out;
                    }
                    .song-menu.active { display: block; }
                    .song-menu-item {
                        padding: 12px 16px;
                        cursor: pointer;
                        display: flex;
                        align-items: center;
                        gap: 12px;
                        transition: background 0.1s ease;
                        color: var(--text-secondary);
                        font-size: 0.875rem;
                        font-weight: 500;
                    }
                    .song-menu-item:hover { 
                        background: var(--bg-hover);
                        color: var(--text-primary);
                    }
                    .song-menu-item.danger { color: #ef4444; }
                    .song-menu-item.danger:hover { 
                        background: rgba(239, 68, 68, 0.1);
                        color: #f87171;
                    }
                    .song-menu-divider { 
                        height: 1px; 
                        background: var(--border-subtle); 
                        margin: 6px 0; 
                    }
                    
                    audio { display: none; }
                    
                    .empty { 
                        padding: 60px 30px; 
                        text-align: center; 
                        color: var(--text-muted);
                        font-size: 0.9375rem;
                    }
                    .empty-icon {
                        font-size: 3rem;
                        margin-bottom: 16px;
                        opacity: 0.5;
                    }
                    .status { 
                        margin-top: 12px; 
                        color: var(--text-tertiary); 
                        display: none;
                        font-size: 0.875rem;
                    }
                    
                    /* Playlist Songs */
                    .playlist-song {
                        display: flex;
                        align-items: center;
                        gap: 16px;
                        padding: 12px 16px;
                        background: var(--bg-secondary);
                        margin-bottom: 2px;
                        border-radius: var(--radius-md);
                        cursor: grab;
                        transition: all 0.15s ease;
                    }
                    .playlist-song:hover { background: var(--bg-tertiary); }
                    .playlist-song.dragging { 
                        opacity: 0.5;
                        cursor: grabbing;
                    }
                    .playlist-song.drag-over { 
                        border-top: 2px solid var(--accent);
                        margin-top: -2px;
                    }
                    .playlist-song-drag {
                        color: var(--text-muted);
                        cursor: grab;
                        padding: 4px;
                    }
                    .playlist-song-art {
                        width: 48px;
                        height: 48px;
                        background: var(--bg-tertiary);
                        border-radius: var(--radius-sm);
                        flex-shrink: 0;
                        overflow: hidden;
                    }
                    .playlist-song-art img {
                        width: 100%;
                        height: 100%;
                        object-fit: cover;
                    }
                    .playlist-song-info { flex: 1; min-width: 0; }
                    .playlist-song-title {
                        font-weight: 500;
                        font-size: 0.9375rem;
                        margin-bottom: 2px;
                        white-space: nowrap;
                        overflow: hidden;
                        text-overflow: ellipsis;
                    }
                    .playlist-song-artist {
                        color: var(--text-tertiary);
                        font-size: 0.8125rem;
                        white-space: nowrap;
                        overflow: hidden;
                        text-overflow: ellipsis;
                    }
                    .playlist-song-actions {
                        display: flex;
                        gap: 8px;
                        opacity: 0;
                        transition: opacity 0.15s ease;
                    }
                    .playlist-song:hover .playlist-song-actions { opacity: 1; }
                    
                    .add-to-playlist { position: relative; }
                    .playlist-dropdown {
                        position: absolute;
                        top: calc(100% + 4px);
                        left: 0;
                        background: rgba(36, 36, 36, 0.98);
                        backdrop-filter: blur(20px);
                        -webkit-backdrop-filter: blur(20px);
                        border: 1px solid var(--border-visible);
                        border-radius: var(--radius-lg);
                        min-width: 180px;
                        z-index: 100;
                        display: none;
                        box-shadow: var(--shadow-lg);
                        animation: fadeInScale 0.15s ease-out;
                    }
                    .playlist-dropdown.show { display: block; }
                    .playlist-dropdown-item {
                        padding: 12px 16px;
                        cursor: pointer;
                        font-size: 0.875rem;
                        color: var(--text-secondary);
                        transition: all 0.1s ease;
                    }
                    .playlist-dropdown-item:hover { 
                        background: var(--bg-hover);
                        color: var(--text-primary);
                    }
                    
                    /* Mini Player Bar */
                    .player-bar {
                        position: fixed;
                        bottom: 0;
                        left: 0;
                        right: 0;
                        height: var(--player-height);
                        background: rgba(18, 18, 18, 0.95);
                        backdrop-filter: blur(30px);
                        -webkit-backdrop-filter: blur(30px);
                        border-top: 1px solid var(--border-subtle);
                        display: flex;
                        align-items: center;
                        padding: 0 24px;
                        z-index: 1000;
                        animation: slideUp 0.3s ease-out;
                    }
                    .player-bar.hidden { 
                        display: none;
                    }
                    .player-info {
                        display: flex;
                        align-items: center;
                        width: 280px;
                        min-width: 200px;
                        gap: 14px;
                        cursor: pointer;
                    }
                    .player-info:hover .player-title { color: var(--text-primary); }
                    .player-cover {
                        width: 56px;
                        height: 56px;
                        background: var(--bg-tertiary);
                        border-radius: var(--radius-md);
                        object-fit: cover;
                        box-shadow: 0 4px 12px rgba(0,0,0,0.3);
                        transition: transform 0.2s ease;
                    }
                    .player-info:hover .player-cover { transform: scale(1.03); }
                    .player-text { overflow: hidden; }
                    .player-title {
                        font-weight: 600;
                        font-size: 0.9375rem;
                        white-space: nowrap;
                        overflow: hidden;
                        text-overflow: ellipsis;
                        margin-bottom: 2px;
                        transition: color 0.15s ease;
                    }
                    .player-artist {
                        color: var(--text-tertiary);
                        font-size: 0.8125rem;
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
                        max-width: 700px;
                        margin: 0 auto;
                    }
                    .player-buttons {
                        display: flex;
                        align-items: center;
                        gap: 20px;
                    }
                    .player-btn {
                        background: none;
                        border: none;
                        color: var(--text-secondary);
                        cursor: pointer;
                        padding: 10px;
                        font-size: 1.125rem;
                        border-radius: var(--radius-md);
                        transition: all 0.15s ease;
                    }
                    .player-btn:hover { 
                        color: var(--text-primary);
                        background: var(--bg-hover);
                    }
                    .player-btn.active { color: var(--accent); }
                    .player-btn svg {
                        display: block;
                    }
                    .player-btn-main {
                        width: 44px;
                        height: 44px;
                        background: var(--text-primary);
                        border-radius: 50%;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        color: var(--bg-primary);
                        font-size: 1.25rem;
                        transition: all 0.15s ease;
                        box-shadow: 0 4px 12px rgba(0,0,0,0.3);
                    }
                    .player-btn-main:hover { 
                        transform: scale(1.06);
                        box-shadow: 0 6px 20px rgba(0,0,0,0.4);
                    }
                    .player-progress {
                        display: flex;
                        align-items: center;
                        gap: 12px;
                        width: 100%;
                    }
                    .player-time {
                        font-size: 0.75rem;
                        color: var(--text-muted);
                        min-width: 44px;
                        font-variant-numeric: tabular-nums;
                    }
                    .player-slider {
                        flex: 1;
                        height: 4px;
                        background: var(--bg-hover);
                        border-radius: 2px;
                        cursor: pointer;
                        position: relative;
                        transition: height 0.1s ease;
                    }
                    .player-slider:hover { height: 6px; }
                    .player-slider-fill {
                        height: 100%;
                        background: var(--text-primary);
                        border-radius: 2px;
                        width: 0%;
                        transition: width 0.1s linear, background 0.15s ease;
                        position: relative;
                    }
                    .player-slider:hover .player-slider-fill { background: var(--accent); }
                    .player-slider-fill::after {
                        content: '';
                        position: absolute;
                        right: -6px;
                        top: 50%;
                        transform: translateY(-50%) scale(0);
                        width: 12px;
                        height: 12px;
                        background: var(--text-primary);
                        border-radius: 50%;
                        transition: transform 0.1s ease;
                    }
                    .player-slider:hover .player-slider-fill::after { transform: translateY(-50%) scale(1); }
                    .player-extra {
                        width: 280px;
                        display: flex;
                        justify-content: flex-end;
                        gap: 8px;
                        align-items: center;
                    }
                    
                    /* Full Screen Player */
                    .full-player {
                        position: fixed;
                        top: 0;
                        left: 0;
                        right: 0;
                        bottom: 0;
                        background: var(--bg-primary);
                        z-index: 2000;
                        display: none;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        padding: 40px;
                    }
                    .full-player.active {
                        display: flex;
                        animation: fadeIn 0.3s ease-out;
                    }
                    .full-player-bg {
                        position: absolute;
                        top: 0;
                        left: 0;
                        right: 0;
                        bottom: 0;
                        background-size: cover;
                        background-position: center;
                        filter: blur(100px) saturate(1.5);
                        opacity: 0.3;
                        transform: scale(1.2);
                    }
                    .full-player-content {
                        position: relative;
                        z-index: 1;
                        max-width: 500px;
                        width: 100%;
                        text-align: center;
                    }
                    .full-player-close {
                        position: absolute;
                        top: 24px;
                        left: 24px;
                        z-index: 2;
                    }
                    .full-player-art {
                        width: 100%;
                        max-width: 400px;
                        aspect-ratio: 1;
                        margin: 0 auto 40px;
                        border-radius: var(--radius-xl);
                        overflow: hidden;
                        box-shadow: var(--shadow-xl);
                    }
                    .full-player-art img {
                        width: 100%;
                        height: 100%;
                        object-fit: cover;
                    }
                    .full-player-title {
                        font-size: 1.75rem;
                        font-weight: 700;
                        margin-bottom: 8px;
                        letter-spacing: -0.02em;
                    }
                    .full-player-artist {
                        font-size: 1.125rem;
                        color: var(--text-secondary);
                        margin-bottom: 32px;
                    }
                    .full-player-progress {
                        margin-bottom: 24px;
                    }
                    .full-player-slider {
                        height: 6px;
                        background: rgba(255,255,255,0.2);
                        border-radius: 3px;
                        cursor: pointer;
                        margin-bottom: 12px;
                    }
                    .full-player-slider-fill {
                        height: 100%;
                        background: var(--text-primary);
                        border-radius: 3px;
                        transition: width 0.1s linear;
                    }
                    .full-player-times {
                        display: flex;
                        justify-content: space-between;
                        font-size: 0.8125rem;
                        color: var(--text-tertiary);
                        font-variant-numeric: tabular-nums;
                    }
                    .full-player-controls {
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        gap: 24px;
                    }
                    .full-player-btn {
                        background: none;
                        border: none;
                        color: var(--text-secondary);
                        font-size: 1.5rem;
                        cursor: pointer;
                        padding: 12px;
                        border-radius: var(--radius-md);
                        transition: all 0.15s ease;
                    }
                    .full-player-btn:hover {
                        color: var(--text-primary);
                        background: rgba(255,255,255,0.1);
                    }
                    .full-player-btn.active { color: var(--accent); }
                    .full-player-btn svg {
                        display: block;
                    }
                    .full-player-btn-main {
                        width: 72px;
                        height: 72px;
                        background: var(--text-primary);
                        border-radius: 50%;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        color: var(--bg-primary);
                        font-size: 2rem;
                        transition: all 0.15s ease;
                    }
                    .full-player-btn-main:hover {
                        transform: scale(1.05);
                    }
                    
                    /* Device Picker */
                    .device-picker { position: relative; }
                    .device-dropdown {
                        position: absolute;
                        bottom: calc(100% + 8px);
                        right: 0;
                        background: rgba(36, 36, 36, 0.98);
                        backdrop-filter: blur(20px);
                        -webkit-backdrop-filter: blur(20px);
                        border: 1px solid var(--border-visible);
                        border-radius: var(--radius-lg);
                        min-width: 240px;
                        box-shadow: var(--shadow-xl);
                        display: none;
                        overflow: hidden;
                        animation: fadeInScale 0.15s ease-out;
                    }
                    .device-dropdown.show { display: block; }
                    .device-dropdown-header {
                        padding: 14px 16px;
                        font-size: 0.75rem;
                        color: var(--text-tertiary);
                        text-transform: uppercase;
                        letter-spacing: 0.08em;
                        font-weight: 600;
                        border-bottom: 1px solid var(--border-subtle);
                    }
                    .device-item {
                        padding: 14px 16px;
                        cursor: pointer;
                        display: flex;
                        align-items: center;
                        gap: 14px;
                        transition: background 0.1s ease;
                    }
                    .device-item:hover { background: var(--bg-hover); }
                    .device-item.active { color: var(--accent); }
                    .device-item-icon { 
                        font-size: 1.25rem;
                        opacity: 0.8;
                    }
                    .device-item-name { 
                        flex: 1;
                        font-size: 0.9375rem;
                        font-weight: 500;
                    }
                    .device-item-active { 
                        color: var(--accent);
                        font-size: 0.75rem;
                        font-weight: 600;
                    }
                    
                    /* Device indicator */
                    .device-indicator {
                        position: fixed;
                        bottom: calc(var(--player-height) + 16px);
                        left: 50%;
                        transform: translateX(-50%);
                        background: var(--accent);
                        color: var(--bg-primary);
                        padding: 10px 20px;
                        border-radius: 100px;
                        font-size: 0.875rem;
                        font-weight: 600;
                        z-index: 1001;
                        box-shadow: var(--shadow-lg);
                    }
                    .device-indicator.hidden { display: none; }
                    
                    /* Spotify Import Styles */
                    .spotify-import-area {
                        border: 2px dashed rgba(29, 185, 84, 0.4);
                        padding: 48px 32px;
                        text-align: center;
                        margin-bottom: 32px;
                        background: linear-gradient(135deg, var(--bg-secondary) 0%, rgba(29, 185, 84, 0.05) 100%);
                        border-radius: var(--radius-xl);
                        transition: all 0.2s ease;
                    }
                    .spotify-import-area:hover {
                        border-color: rgba(29, 185, 84, 0.6);
                    }
                    .spotify-import-area.dragover {
                        border-color: var(--accent);
                        background: linear-gradient(135deg, rgba(29, 185, 84, 0.1) 0%, rgba(29, 185, 84, 0.15) 100%);
                    }
                    .spotify-logo {
                        font-size: 3rem;
                        margin-bottom: 16px;
                    }
                    .spotify-import-area h3 {
                        color: var(--accent);
                        margin-bottom: 12px;
                        font-size: 1.25rem;
                        font-weight: 600;
                    }
                    .spotify-import-area p {
                        color: var(--text-tertiary);
                        font-size: 0.9375rem;
                        line-height: 1.6;
                    }
                    .spotify-import-area a {
                        color: var(--accent);
                        text-decoration: none;
                        font-weight: 500;
                    }
                    .spotify-import-area a:hover {
                        text-decoration: underline;
                    }
                    .btn-spotify {
                        background: var(--accent);
                        border-color: var(--accent);
                        color: var(--bg-primary);
                        font-weight: 600;
                        padding: 12px 28px;
                        font-size: 0.9375rem;
                    }
                    .btn-spotify:hover { 
                        background: var(--accent-hover);
                        border-color: var(--accent-hover);
                        transform: translateY(-2px);
                    }
                    .spotify-options {
                        display: flex;
                        gap: 24px;
                        justify-content: center;
                        margin: 20px 0;
                        flex-wrap: wrap;
                    }
                    .spotify-options label {
                        display: flex;
                        align-items: center;
                        gap: 8px;
                        color: var(--text-secondary);
                        cursor: pointer;
                        font-size: 0.875rem;
                        padding: 8px 12px;
                        background: var(--bg-tertiary);
                        border-radius: var(--radius-md);
                        transition: all 0.15s ease;
                    }
                    .spotify-options label:hover {
                        background: var(--bg-elevated);
                    }
                    .spotify-options input[type="checkbox"] {
                        accent-color: var(--accent);
                        width: 16px;
                        height: 16px;
                    }
                    .spotify-progress {
                        margin-top: 24px;
                        padding: 24px;
                        background: var(--bg-secondary);
                        border-radius: var(--radius-lg);
                        border: 1px solid var(--border-subtle);
                        display: none;
                    }
                    .spotify-progress.active { 
                        display: block;
                        animation: fadeIn 0.2s ease-out;
                    }
                    .spotify-progress-header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 20px;
                    }
                    .spotify-progress-header span {
                        font-weight: 600;
                        font-size: 1rem;
                    }
                    .spotify-progress-bar {
                        height: 8px;
                        background: var(--bg-tertiary);
                        border-radius: 4px;
                        overflow: hidden;
                        margin-bottom: 16px;
                    }
                    .spotify-progress-fill {
                        height: 100%;
                        background: linear-gradient(90deg, var(--accent) 0%, var(--accent-hover) 100%);
                        width: 0%;
                        transition: width 0.3s ease;
                        border-radius: 4px;
                    }
                    .spotify-track-info {
                        color: var(--text-tertiary);
                        font-size: 0.875rem;
                    }
                    .spotify-track-name {
                        color: var(--text-primary);
                        font-weight: 600;
                    }
                    .spotify-stats {
                        display: flex;
                        gap: 24px;
                        margin-top: 16px;
                    }
                    .spotify-stat {
                        display: flex;
                        align-items: center;
                        gap: 8px;
                        font-size: 0.875rem;
                        font-weight: 500;
                    }
                    .spotify-stat.success { color: var(--accent); }
                    .spotify-stat.error { color: #ef4444; }
                    .spotify-log {
                        max-height: 200px;
                        overflow-y: auto;
                        background: var(--bg-primary);
                        padding: 16px;
                        border-radius: var(--radius-md);
                        margin-top: 16px;
                        font-size: 0.8125rem;
                        font-family: 'SF Mono', Monaco, Consolas, monospace;
                        border: 1px solid var(--border-subtle);
                    }
                    .spotify-log-item { 
                        padding: 8px 0; 
                        border-bottom: 1px solid var(--border-subtle);
                    }
                    .spotify-log-item:last-child { border-bottom: none; }
                    .spotify-log-item.success { color: var(--accent); }
                    .spotify-log-item.error { color: #ef4444; }
                    .spotify-log-item.info { color: var(--text-tertiary); }
                    
                    /* Playlist Hero Header */
                    .playlist-hero {
                        display: flex;
                        gap: 32px;
                        margin-bottom: 32px;
                        padding: 32px;
                        background: linear-gradient(135deg, var(--bg-secondary) 0%, var(--bg-tertiary) 100%);
                        border-radius: var(--radius-xl);
                    }
                    .playlist-hero-art {
                        width: 200px;
                        height: 200px;
                        background: var(--bg-tertiary);
                        border-radius: var(--radius-lg);
                        display: grid;
                        grid-template-columns: 1fr 1fr;
                        grid-template-rows: 1fr 1fr;
                        overflow: hidden;
                        box-shadow: var(--shadow-lg);
                        flex-shrink: 0;
                    }
                    .playlist-hero-art img {
                        width: 100%;
                        height: 100%;
                        object-fit: cover;
                    }
                    .playlist-hero-art .single-art {
                        grid-column: 1 / -1;
                        grid-row: 1 / -1;
                    }
                    .playlist-hero-info {
                        display: flex;
                        flex-direction: column;
                        justify-content: flex-end;
                    }
                    .playlist-hero-label {
                        font-size: 0.75rem;
                        text-transform: uppercase;
                        letter-spacing: 0.08em;
                        color: var(--text-tertiary);
                        font-weight: 600;
                        margin-bottom: 8px;
                    }
                    .playlist-hero-title {
                        font-size: 3rem;
                        font-weight: 700;
                        letter-spacing: -0.03em;
                        margin-bottom: 16px;
                        line-height: 1.1;
                    }
                    .playlist-hero-meta {
                        color: var(--text-secondary);
                        font-size: 0.9375rem;
                    }
                    .playlist-hero-actions {
                        display: flex;
                        gap: 12px;
                        margin-top: 24px;
                    }
                    
                    /* Modal */
                    .modal-overlay {
                        position: fixed;
                        top: 0;
                        left: 0;
                        right: 0;
                        bottom: 0;
                        background: rgba(0, 0, 0, 0.75);
                        backdrop-filter: blur(4px);
                        -webkit-backdrop-filter: blur(4px);
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        z-index: 3000;
                        animation: fadeIn 0.2s ease-out;
                    }
                    .modal-content {
                        background: var(--bg-secondary);
                        border: 1px solid var(--border-visible);
                        border-radius: var(--radius-xl);
                        padding: 28px;
                        min-width: 320px;
                        max-width: 90%;
                        max-height: 80vh;
                        overflow-y: auto;
                        animation: fadeInScale 0.2s ease-out;
                    }
                    .modal-header {
                        display: flex;
                        align-items: center;
                        justify-content: space-between;
                        margin-bottom: 20px;
                    }
                    .modal-title {
                        font-size: 1.25rem;
                        font-weight: 600;
                    }
                    .modal-close {
                        background: none;
                        border: none;
                        color: var(--text-tertiary);
                        font-size: 1.5rem;
                        cursor: pointer;
                        padding: 4px;
                        border-radius: var(--radius-sm);
                        transition: all 0.15s ease;
                    }
                    .modal-close:hover {
                        color: var(--text-primary);
                        background: var(--bg-tertiary);
                    }
                    .modal-list {
                        max-height: 320px;
                        overflow-y: auto;
                    }
                    .modal-list-item {
                        display: flex;
                        align-items: center;
                        gap: 14px;
                        padding: 14px;
                        border-radius: var(--radius-md);
                        cursor: pointer;
                        transition: background 0.1s ease;
                    }
                    .modal-list-item:hover {
                        background: var(--bg-tertiary);
                    }
                    .modal-list-item-icon {
                        width: 44px;
                        height: 44px;
                        background: var(--bg-elevated);
                        border-radius: var(--radius-sm);
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        color: var(--text-tertiary);
                        flex-shrink: 0;
                    }
                    .modal-list-item-text {
                        flex: 1;
                    }
                    .modal-list-item-title {
                        font-weight: 500;
                        margin-bottom: 2px;
                    }
                    .modal-list-item-subtitle {
                        font-size: 0.8125rem;
                        color: var(--text-tertiary);
                    }
                    
                    /* Form Elements */
                    select {
                        font-family: inherit;
                        background: var(--bg-tertiary);
                        border: 1px solid var(--border-visible);
                        border-radius: var(--radius-md);
                        color: var(--text-primary);
                        padding: 12px 16px;
                        font-size: 0.9375rem;
                        width: 100%;
                        cursor: pointer;
                        transition: all 0.15s ease;
                        appearance: none;
                        background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 12 12'%3E%3Cpath fill='%23888' d='M6 8L1 3h10z'/%3E%3C/svg%3E");
                        background-repeat: no-repeat;
                        background-position: right 12px center;
                        padding-right: 36px;
                    }
                    select:hover {
                        border-color: var(--text-tertiary);
                    }
                    select:focus {
                        outline: none;
                        border-color: var(--accent);
                    }
                    
                    /* Responsive */
                    @media (max-width: 900px) {
                        .sidebar { width: 240px; }
                        .song-grid { grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 16px; }
                        .playlist-hero { flex-direction: column; align-items: center; text-align: center; }
                        .playlist-hero-art { width: 180px; height: 180px; }
                        .playlist-hero-title { font-size: 2rem; }
                        .playlist-hero-info { align-items: center; }
                    }
                    @media (max-width: 640px) {
                        .sidebar { display: none; }
                        .main { padding: 16px; }
                        .song-grid { grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 12px; }
                        .player-bar { padding: 0 16px; }
                        .player-extra { display: none; }
                        .player-info { width: auto; flex: 1; }
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="sidebar">
                        <div class="sidebar-logo">Resonanz</div>
                        
                        <div class="nav-item active" onclick="showAllSongs()" id="navAllSongs">
                            <svg class="nav-icon" width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/></svg>
                            <span>All Songs</span>
                        </div>
                        
                        <h2>Library</h2>
                        <div id="playlistList"></div>
                        <button class="btn btn-ghost" onclick="createPlaylist()" style="width: 100%; justify-content: flex-start; margin-top: 8px;">
                            <span>＋</span>
                            <span>New Playlist</span>
                        </button>
                        
                        <h2>Import</h2>
                        <div class="nav-item" onclick="showSpotifyImport()" id="navSpotify">
                            <svg class="nav-icon" width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm4.64 14.36c-.19.19-.45.3-.71.3-.13 0-.26-.03-.38-.08-2.08-1.04-4.63-1.22-7.66-.67-.56.11-1.09-.25-1.2-.81-.11-.56.25-1.09.81-1.2 3.47-.59 6.45-.33 8.93.92.51.26.71.89.45 1.4l-.24.14zM17.7 13c-.3 0-.58-.11-.8-.32-2.25-1.68-5.68-2.01-8.37-1.1-.55.18-1.14-.11-1.32-.66-.18-.55.11-1.14.66-1.32 3.25-1.09 7.29-.73 10.08 1.34.46.34.56 1 .22 1.46-.19.26-.49.4-.8.4l.33.2zm.31-2.59c-.27 0-.54-.1-.75-.29-2.73-2.04-7.08-2.17-9.63-1.19-.51.19-1.08-.07-1.27-.58-.19-.51.07-1.08.58-1.27 2.92-1.12 7.79-.9 10.87 1.38.46.34.55.98.21 1.44-.2.27-.51.42-.84.42l.83.09z"/></svg>
                            <span>Spotify Import</span>
                        </div>
                    </div>
                    
                    <div class="main">
                        <!-- All Songs View -->
                        <div id="allSongsView" class="view-enter">
                            <div class="upload-area" id="dropZone">
                                <div class="upload-icon">📁</div>
                                <p style="margin-bottom: 16px; color: var(--text-secondary);">Drop audio files here to upload</p>
                                <input type="file" id="fileInput" accept="audio/*" multiple>
                                <button class="btn" onclick="document.getElementById('fileInput').click()">Choose Files</button>
                                <div class="status" id="status"></div>
                            </div>
                            
                            <div class="page-header">
                                <h1 class="page-title">All Songs</h1>
                                <div class="page-actions">
                                    <div class="view-toggle">
                                        <button class="view-toggle-btn active" id="viewGridBtn" onclick="setView('grid')" title="Grid view">
                                            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M3 3h8v8H3V3zm0 10h8v8H3v-8zm10-10h8v8h-8V3zm0 10h8v8h-8v-8z"/></svg>
                                        </button>
                                        <button class="view-toggle-btn" id="viewListBtn" onclick="setView('list')" title="List view">
                                            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z"/></svg>
                                        </button>
                                    </div>
                                    <button class="btn btn-download btn-small" onclick="downloadAllSongs()">
                                        <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/></svg>
                                        <span>Download All</span>
                                    </button>
                                </div>
                            </div>
                            
                            <div class="song-grid" id="songGrid"></div>
                            <div class="song-list file-list" id="fileList"></div>
                        </div>
                        
                        <!-- Playlist View -->
                        <div id="playlistView" style="display: none;">
                            <div class="playlist-hero" id="playlistHero">
                                <div class="playlist-hero-art" id="playlistArt">
                                    <div class="single-art" style="background: var(--bg-elevated); display: flex; align-items: center; justify-content: center; font-size: 4rem;">🎵</div>
                                </div>
                                <div class="playlist-hero-info">
                                    <div class="playlist-hero-label">Playlist</div>
                                    <h1 class="playlist-hero-title" id="playlistTitle"></h1>
                                    <div class="playlist-hero-meta" id="playlistMeta"></div>
                                    <div class="playlist-hero-actions">
                                        <button class="btn btn-primary" onclick="playPlaylist()">
                                            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
                                            Play
                                        </button>
                                        <button class="btn btn-download" onclick="downloadPlaylist()">
                                            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/></svg>
                                            Download
                                        </button>
                                        <button class="btn" onclick="renameCurrentPlaylist()">Rename</button>
                                        <button class="btn btn-danger" onclick="deleteCurrentPlaylist()">Delete</button>
                                    </div>
                                </div>
                            </div>
                            <div id="playlistSongs"></div>
                        </div>
                        
                        <!-- Spotify Import View -->
                        <div id="spotifyView" style="display: none;" class="view-enter">
                            <div class="page-header">
                                <h1 class="page-title">Spotify Import</h1>
                            </div>
                            
                            <div class="spotify-import-area" id="spotifyDropZone">
                                <div class="spotify-logo">🎧</div>
                                <h3>Import Your Spotify Playlist</h3>
                                <p style="margin-bottom: 20px;">
                                    Export your playlist from <a href="https://exportify.net" target="_blank">Exportify</a> 
                                    or <a href="https://www.tunemymusic.com" target="_blank">TuneMyMusic</a> as CSV
                                </p>
                                <input type="file" id="spotifyCsvInput" accept=".csv">
                                <button class="btn btn-spotify" onclick="document.getElementById('spotifyCsvInput').click()">Select CSV File</button>
                                <p style="font-size: 0.8125rem; margin-top: 12px;">or drag and drop here</p>
                                
                                <div class="spotify-options">
                                    <label>
                                        <input type="checkbox" id="spotifySkipInstrumentals">
                                        Skip instrumentals
                                    </label>
                                </div>
                                
                                <div style="margin-top: 24px; padding: 20px; background: var(--bg-tertiary); border-radius: var(--radius-lg);">
                                    <label style="color: var(--accent); font-weight: 600; display: block; margin-bottom: 12px;">📁 Add to playlist:</label>
                                    <select id="spotifyTargetPlaylist">
                                        <option value="">-- Download only (no playlist) --</option>
                                    </select>
                                    <button class="btn btn-small" onclick="createPlaylistForSpotify()" style="margin-top: 12px; width: 100%;">+ Create New Playlist</button>
                                </div>
                                
                                <p style="color: #666; font-size: 0.75rem; margin-top: 10px;">Downloads as M4A/AAC (best quality, native on Android)</p>
                                <button class="btn btn-small" onclick="testSpotifyPython()" style="margin-top: 10px;">🔧 Test Python</button>
                                <button class="btn btn-small" onclick="testCsvHeaders()" style="margin-top: 10px; margin-left: 5px;">📋 Test CSV</button>
                                <div id="spotifyTestResult" style="margin-top: 10px; font-size: 0.8rem; color: #888; white-space: pre-wrap; word-break: break-all;"></div>
                            </div>
                            
                            <div class="spotify-progress" id="spotifyProgress">
                                <div class="spotify-progress-header">
                                    <span id="spotifyProgressTitle">Download in progress...</span>
                                    <button class="btn btn-small btn-danger" onclick="cancelSpotifyImport()">Cancel</button>
                                </div>
                                <div class="spotify-progress-bar">
                                    <div class="spotify-progress-fill" id="spotifyProgressFill"></div>
                                </div>
                                <div class="spotify-track-info">
                                    <span id="spotifyTrackCount">0 / 0</span> - 
                                    <span class="spotify-track-name" id="spotifyCurrentTrack">Initializing...</span>
                                </div>
                                <div class="spotify-stats">
                                    <div class="spotify-stat success">✓ <span id="spotifySuccessCount">0</span> successful</div>
                                    <div class="spotify-stat error">✗ <span id="spotifyErrorCount">0</span> failed</div>
                                </div>
                                <div class="spotify-log" id="spotifyLog"></div>
                            </div>
                        </div>
                    </div>
                </div>
                
                <!-- Mini Player Bar -->
                <div class="player-bar hidden" id="playerBar">
                    <div class="player-info" onclick="openFullPlayer()">
                        <img class="player-cover" id="playerCover" src="" alt="">
                        <div class="player-text">
                            <div class="player-title" id="playerTitle">-</div>
                            <div class="player-artist" id="playerArtist">-</div>
                        </div>
                    </div>
                    <div class="player-controls">
                        <div class="player-buttons">
                            <button class="player-btn" id="btnShuffle" onclick="toggleShuffle()" title="Shuffle">
                                <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z"/></svg>
                            </button>
                            <button class="player-btn" onclick="playerPrev()" title="Previous">
                                <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M6 6h2v12H6zm3.5 6l8.5 6V6z"/></svg>
                            </button>
                            <button class="player-btn player-btn-main" id="btnPlayPause" onclick="togglePlayPause()" title="Play/Pause">
                                <svg class="icon-play" width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
                                <svg class="icon-pause" width="20" height="20" viewBox="0 0 24 24" fill="currentColor" style="display:none"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>
                            </button>
                            <button class="player-btn" onclick="playerNext()" title="Next">
                                <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg>
                            </button>
                            <button class="player-btn" id="btnRepeat" onclick="toggleRepeat()" title="Repeat">
                                <svg class="icon-repeat" width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z"/></svg>
                                <svg class="icon-repeat-one" width="20" height="20" viewBox="0 0 24 24" fill="currentColor" style="display:none"><path d="M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-2V9h-1l-2 1v1h1.5v4H13z"/></svg>
                            </button>
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
                        <button class="player-btn" onclick="openFullPlayer()" title="Expand">
                            <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"/></svg>
                        </button>
                    </div>
                </div>
                
                <!-- Full Screen Player -->
                <div class="full-player" id="fullPlayer">
                    <div class="full-player-bg" id="fullPlayerBg"></div>
                    <button class="btn btn-ghost full-player-close" onclick="closeFullPlayer()">
                        <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>
                    </button>
                    <div class="full-player-content">
                        <div class="full-player-art">
                            <img id="fullPlayerCover" src="" alt="">
                        </div>
                        <h2 class="full-player-title" id="fullPlayerTitle">-</h2>
                        <p class="full-player-artist" id="fullPlayerArtist">-</p>
                        <div class="full-player-progress">
                            <div class="full-player-slider" id="fullPlayerSlider" onclick="seekToFull(event)">
                                <div class="full-player-slider-fill" id="fullPlayerProgress"></div>
                            </div>
                            <div class="full-player-times">
                                <span id="fullPlayerTime">0:00</span>
                                <span id="fullPlayerDuration">0:00</span>
                            </div>
                        </div>
                        <div class="full-player-controls">
                            <button class="full-player-btn" id="fullBtnShuffle" onclick="toggleShuffle()" title="Shuffle">
                                <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z"/></svg>
                            </button>
                            <button class="full-player-btn" onclick="playerPrev()" title="Previous">
                                <svg width="28" height="28" viewBox="0 0 24 24" fill="currentColor"><path d="M6 6h2v12H6zm3.5 6l8.5 6V6z"/></svg>
                            </button>
                            <button class="full-player-btn full-player-btn-main" id="fullBtnPlayPause" onclick="togglePlayPause()" title="Play/Pause">
                                <svg class="icon-play" width="32" height="32" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
                                <svg class="icon-pause" width="32" height="32" viewBox="0 0 24 24" fill="currentColor" style="display:none"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>
                            </button>
                            <button class="full-player-btn" onclick="playerNext()" title="Next">
                                <svg width="28" height="28" viewBox="0 0 24 24" fill="currentColor"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg>
                            </button>
                            <button class="full-player-btn" id="fullBtnRepeat" onclick="toggleRepeat()" title="Repeat">
                                <svg class="icon-repeat" width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z"/></svg>
                                <svg class="icon-repeat-one" width="24" height="24" viewBox="0 0 24 24" fill="currentColor" style="display:none"><path d="M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-2V9h-1l-2 1v1h1.5v4H13z"/></svg>
                            </button>
                        </div>
                    </div>
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
                                status.textContent = response.ok ? 'Done: ' + file.name : 'Error';
                            } catch (err) {
                                status.textContent = 'Error: ' + err.message;
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
                    
                    // View state
                    let currentView = 'grid';
                    
                    function setView(view) {
                        currentView = view;
                        const gridBtn = document.getElementById('viewGridBtn');
                        const listBtn = document.getElementById('viewListBtn');
                        const songGrid = document.getElementById('songGrid');
                        const fileList = document.getElementById('fileList');
                        
                        if (view === 'grid') {
                            gridBtn.classList.add('active');
                            listBtn.classList.remove('active');
                            songGrid.classList.remove('hidden');
                            fileList.classList.remove('active');
                        } else {
                            listBtn.classList.add('active');
                            gridBtn.classList.remove('active');
                            songGrid.classList.add('hidden');
                            fileList.classList.add('active');
                        }
                    }
                    
                    function renderFiles() {
                        const songGrid = document.getElementById('songGrid');
                        const fileList = document.getElementById('fileList');
                        
                        if (files.length === 0) {
                            songGrid.innerHTML = '<div class="empty"><div class="empty-icon">🎵</div>No songs yet<br><span style="font-size: 0.875rem; margin-top: 8px; display: block;">Upload some music to get started</span></div>';
                            fileList.innerHTML = '';
                            return;
                        }
                        
                        // Render Grid View
                        songGrid.innerHTML = files.map(song => `
                            <div class="song-card" data-song-id="${'$'}{song.id}" ondblclick="playSong('${'$'}{song.id}')">
                                <div class="song-card-art">
                                    <img src="${'$'}{song.albumArt || ''}" alt="" onerror="this.style.display='none'">
                                    <button class="song-card-play" onclick="event.stopPropagation(); playSong('${'$'}{song.id}')">
                                        <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
                                    </button>
                                </div>
                                <div class="song-card-menu">
                                    <button class="song-menu-btn" onclick="event.stopPropagation(); toggleSongMenu(event, '${'$'}{song.id}')">⋯</button>
                                    <div class="song-menu" id="song-menu-grid-${'$'}{song.id}">
                                        <div class="song-menu-item" onclick="playSong('${'$'}{song.id}')">▶ Play</div>
                                        <div class="song-menu-item" onclick="showAddToPlaylist('${'$'}{song.id}')">📁 Add to Playlist</div>
                                        <a class="song-menu-item" href="/save/${'$'}{song.id}" download style="text-decoration: none;">⬇ Download</a>
                                        <div class="song-menu-divider"></div>
                                        <div class="song-menu-item danger" onclick="confirmDeleteSong('${'$'}{song.id}', '${'$'}{song.name.replace(/'/g, "\\'")}')">🗑 Delete</div>
                                    </div>
                                </div>
                                <div class="song-card-title">${'$'}{song.name}</div>
                                <div class="song-card-artist">${'$'}{song.artist}</div>
                            </div>
                        `).join('');
                        
                        // Render List View
                        fileList.innerHTML = files.map(song => `
                            <div class="file-item" data-song-id="${'$'}{song.id}" ondblclick="playSong('${'$'}{song.id}')">
                                <div class="file-item-art">
                                    <img src="${'$'}{song.albumArt || ''}" alt="" onerror="this.parentElement.innerHTML='<div style=\\'width:100%;height:100%;display:flex;align-items:center;justify-content:center;font-size:1.25rem;\\'>🎵</div>'">
                                </div>
                                <div class="file-item-info">
                                    <div class="file-name">${'$'}{song.name}</div>
                                    <div class="file-meta">${'$'}{song.artist} • ${'$'}{song.album}</div>
                                </div>
                                <span class="file-duration">${'$'}{formatDuration(song.duration)}</span>
                                <div class="song-menu-container">
                                    <button class="song-menu-btn" onclick="event.stopPropagation(); toggleSongMenu(event, '${'$'}{song.id}-list')">⋯</button>
                                    <div class="song-menu" id="song-menu-${'$'}{song.id}-list">
                                        <div class="song-menu-item" onclick="playSong('${'$'}{song.id}')">▶ Play</div>
                                        <div class="song-menu-item" onclick="showAddToPlaylist('${'$'}{song.id}')">📁 Add to Playlist</div>
                                        <a class="song-menu-item" href="/save/${'$'}{song.id}" download style="text-decoration: none;">⬇ Download</a>
                                        <div class="song-menu-divider"></div>
                                        <div class="song-menu-item danger" onclick="confirmDeleteSong('${'$'}{song.id}', '${'$'}{song.name.replace(/'/g, "\\'")}')">🗑 Delete</div>
                                    </div>
                                </div>
                            </div>
                        `).join('');
                        
                        setView(currentView);
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
                            list.innerHTML = '<div style="color: var(--text-muted); padding: 12px 14px; font-size: 0.875rem;">No playlists yet</div>';
                        } else {
                            list.innerHTML = playlists.map(p => `
                                <div class="playlist-item ${'$'}{currentPlaylist && currentPlaylist.id === p.id ? 'active' : ''}" onclick="showPlaylist('${'$'}{p.id}')">
                                    <div class="playlist-icon">
                                        <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zM17 6v8.18c-.31-.11-.65-.18-1-.18-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z"/></svg>
                                    </div>
                                    <div class="playlist-info">
                                        <div class="playlist-name">${'$'}{p.name}</div>
                                        <div class="playlist-count">${'$'}{p.songs.length} song${'$'}{p.songs.length !== 1 ? 's' : ''}</div>
                                    </div>
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
                            dropdown.innerHTML = '<div class="playlist-dropdown-item" onclick="createPlaylist()">Create playlist</div>';
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
                        const name = prompt('Playlist name:');
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
                    
                    function updateNavActive(activeId) {
                        document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));
                        document.querySelectorAll('.playlist-item').forEach(el => el.classList.remove('active'));
                        const activeEl = document.getElementById(activeId);
                        if (activeEl) activeEl.classList.add('active');
                    }
                    
                    function showAllSongs() {
                        currentPlaylist = null;
                        document.getElementById('allSongsView').style.display = 'block';
                        document.getElementById('allSongsView').classList.add('view-enter');
                        document.getElementById('playlistView').style.display = 'none';
                        document.getElementById('spotifyView').style.display = 'none';
                        updateNavActive('navAllSongs');
                        renderPlaylists();
                    }
                    
                    function showPlaylist(id) {
                        currentPlaylist = playlists.find(p => p.id === id);
                        if (!currentPlaylist) return;
                        
                        document.getElementById('allSongsView').style.display = 'none';
                        document.getElementById('playlistView').style.display = 'block';
                        document.getElementById('playlistView').classList.add('view-enter');
                        document.getElementById('spotifyView').style.display = 'none';
                        document.getElementById('playlistTitle').textContent = currentPlaylist.name;
                        
                        // Update playlist meta
                        const songCount = currentPlaylist.songs.length;
                        const totalDuration = currentPlaylist.songs.reduce((acc, songId) => {
                            const song = files.find(f => String(f.id) === String(songId));
                            return acc + (song ? song.duration : 0);
                        }, 0);
                        document.getElementById('playlistMeta').textContent = songCount + ' song' + (songCount !== 1 ? 's' : '') + ' • ' + formatDuration(totalDuration);
                        
                        // Update playlist art collage
                        const artDiv = document.getElementById('playlistArt');
                        const artSongs = currentPlaylist.songs.slice(0, 4).map(songId => files.find(f => String(f.id) === String(songId))).filter(s => s && s.albumArt);
                        if (artSongs.length >= 4) {
                            artDiv.innerHTML = artSongs.slice(0, 4).map(s => '<img src="' + s.albumArt + '" alt="">').join('');
                        } else if (artSongs.length > 0) {
                            artDiv.innerHTML = '<img class="single-art" src="' + artSongs[0].albumArt + '" alt="">';
                        } else {
                            artDiv.innerHTML = '<div class="single-art" style="background: var(--bg-elevated); display: flex; align-items: center; justify-content: center; font-size: 4rem;">🎵</div>';
                        }
                        
                        updateNavActive(null);
                        
                        const songsDiv = document.getElementById('playlistSongs');
                        if (currentPlaylist.songs.length === 0) {
                            songsDiv.innerHTML = '<div class="empty"><div class="empty-icon">📝</div>This playlist is empty<br><span style="font-size: 0.875rem; margin-top: 8px; display: block;">Add songs from your library</span></div>';
                        } else {
                            songsDiv.innerHTML = currentPlaylist.songs.map((songId, idx) => {
                                const song = files.find(f => String(f.id) === String(songId));
                                return `
                                    <div class="playlist-song" draggable="true" data-song="${'$'}{songId}" data-index="${'$'}{idx}" ondblclick="playSong('${'$'}{songId}')">
                                        <div class="playlist-song-drag">☰</div>
                                        <div class="playlist-song-art">
                                            ${'$'}{song && song.albumArt ? `<img src="${'$'}{song.albumArt}" alt="">` : '<div style="width:100%;height:100%;display:flex;align-items:center;justify-content:center;font-size:1.25rem;">🎵</div>'}
                                        </div>
                                        <div class="playlist-song-info">
                                            <div class="playlist-song-title">${'$'}{song ? song.name : 'Unknown song'}</div>
                                            <div class="playlist-song-artist">${'$'}{song ? song.artist : 'Not found in library'}</div>
                                        </div>
                                        <span class="file-duration">${'$'}{song ? formatDuration(song.duration) : '--:--'}</span>
                                        <div class="playlist-song-actions">
                                            ${'$'}{song ? `<a href="/save/${'$'}{songId}" class="btn btn-small btn-download" download>⬇</a>` : ''}
                                            <button class="btn btn-small btn-danger" onclick="event.stopPropagation(); removeFromPlaylist('${'$'}{currentPlaylist.id}', '${'$'}{songId}')">✕</button>
                                        </div>
                                    </div>
                                `;
                            }).join('');
                            initDragDrop();
                        }
                        renderPlaylists();
                    }
                    
                    function playPlaylist() {
                        if (!currentPlaylist || currentPlaylist.songs.length === 0) return;
                        playSong(currentPlaylist.songs[0]);
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
                        const name = prompt('New name:', currentPlaylist.name);
                        if (!name) return;
                        const formData = new FormData();
                        formData.append('name', name);
                        await fetch('/playlists/rename/' + currentPlaylist.id, { method: 'POST', body: formData });
                        loadPlaylists().then(() => showPlaylist(currentPlaylist.id));
                    }
                    
                    async function deleteCurrentPlaylist() {
                        if (!currentPlaylist) return;
                        if (!confirm('Delete playlist?')) return;
                        await fetch('/playlists/delete/' + currentPlaylist.id, { method: 'POST' });
                        showAllSongs();
                        loadPlaylists();
                    }
                    
                    async function downloadPlaylist() {
                        if (!currentPlaylist || currentPlaylist.songs.length === 0) {
                            alert('No songs in this playlist');
                            return;
                        }
                        
                        const songsToDownload = currentPlaylist.songs.filter(songId => 
                            files.some(f => String(f.id) === String(songId))
                        );
                        
                        if (songsToDownload.length === 0) {
                            alert('No songs found to download');
                            return;
                        }
                        
                        if (!confirm('Do you want to download ' + songsToDownload.length + ' songs?')) return;
                        
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
                            alert('No songs available');
                            return;
                        }
                        
                        if (!confirm('Do you want to download all ' + files.length + ' songs?')) return;
                        
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
                        if (!confirm('Really delete?')) return;
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
                            modal.className = 'modal-overlay';
                            modal.onclick = (e) => { if (e.target === modal) closePlaylistModal(); };
                            modal.innerHTML = `
                                <div class="modal-content">
                                    <div class="modal-header">
                                        <h3 class="modal-title">Add to Playlist</h3>
                                        <button class="modal-close" onclick="closePlaylistModal()">✕</button>
                                    </div>
                                    <div class="modal-list" id="playlist-modal-list"></div>
                                </div>
                            `;
                            document.body.appendChild(modal);
                        }
                        
                        const listDiv = document.getElementById('playlist-modal-list');
                        if (playlists.length === 0) {
                            listDiv.innerHTML = '<div class="empty" style="padding: 40px 20px;"><div class="empty-icon">📝</div>No playlists yet<br><span style="font-size: 0.875rem; margin-top: 8px; display: block;">Create one first</span></div>';
                        } else {
                            listDiv.innerHTML = playlists.map(p => `
                                <div class="modal-list-item" onclick="addSongToPlaylistAndClose('${'$'}{p.id}', '${'$'}{songId}')">
                                    <div class="modal-list-item-icon">
                                        <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zM17 6v8.18c-.31-.11-.65-.18-1-.18-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z"/></svg>
                                    </div>
                                    <div class="modal-list-item-text">
                                        <div class="modal-list-item-title">${'$'}{p.name}</div>
                                        <div class="modal-list-item-subtitle">${'$'}{p.songs.length} song${'$'}{p.songs.length !== 1 ? 's' : ''}</div>
                                    </div>
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
                                alert('Error deleting');
                            }
                        } catch (e) {
                            alert('Error: ' + e.message);
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
                    
                    // ==================== PLAYER SYNC (Remote Control for Phone) ====================
                    
                    // Player state
                    let playerState = { isPlaying: false, currentSong: null };
                    
                    // SSE Connection for real-time updates
                    let eventSource = null;
                    
                    function connectSSE() {
                        if (eventSource) {
                            eventSource.close();
                        }
                        
                        eventSource = new EventSource('/player/events?deviceId=web');
                        
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
                        if (!state) return;
                        playerState = state;
                        renderPlayer(state);
                    }
                    
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
                        
                        // Toggle play/pause icons
                        const playIcon = btnPlay.querySelector('.icon-play');
                        const pauseIcon = btnPlay.querySelector('.icon-pause');
                        if (playIcon && pauseIcon) {
                            playIcon.style.display = state.isPlaying ? 'none' : 'block';
                            pauseIcon.style.display = state.isPlaying ? 'block' : 'none';
                        }
                        
                        btnShuffle.classList.toggle('active', state.shuffleEnabled || false);
                        btnRepeat.classList.toggle('active', (state.repeatMode || 0) > 0);
                        
                        // Toggle repeat icons
                        const repeatIcon = btnRepeat.querySelector('.icon-repeat');
                        const repeatOneIcon = btnRepeat.querySelector('.icon-repeat-one');
                        if (repeatIcon && repeatOneIcon) {
                            repeatIcon.style.display = state.repeatMode === 2 ? 'none' : 'block';
                            repeatOneIcon.style.display = state.repeatMode === 2 ? 'block' : 'none';
                        }
                        
                        // Calculate display position
                        const positionMs = state.positionMs || 0;
                        const positionAgeMs = state.positionAgeMs || 0;
                        const totalDurationMs = state.totalDurationMs || 0;
                        const displayPos = positionMs + positionAgeMs;
                        
                        const percent = totalDurationMs > 0 ? (displayPos / totalDurationMs) * 100 : 0;
                        progress.style.width = Math.min(percent, 100) + '%';
                        timeEl.textContent = formatDuration(displayPos);
                        durationEl.textContent = formatDuration(totalDurationMs);
                        
                        // Also update full player if open
                        if (document.getElementById('fullPlayer').classList.contains('active')) {
                            updateFullPlayer();
                        }
                    }
                    
                    // ==================== PLAYER CONTROLS (Remote for Phone) ====================
                    
                    async function togglePlayPause() {
                        if (playerState.isPlaying) {
                            await fetch('/player/pause', { method: 'POST' });
                        } else {
                            await fetch('/player/play', { method: 'POST' });
                        }
                    }
                    
                    async function playerNext() {
                        await fetch('/player/next', { method: 'POST' });
                    }
                    
                    async function playerPrev() {
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
                        
                        const formData = new FormData();
                        formData.append('position', position);
                        await fetch('/player/seek', { method: 'POST', body: formData });
                    }
                    
                    async function playSong(songId) {
                        closeAllMenus();
                        
                        // Tell phone to play this song
                        const formData = new FormData();
                        formData.append('songId', songId);
                        await fetch('/player/play', { method: 'POST', body: formData });
                    }
                    
                    // ==================== INIT ====================
                    
                    // Try SSE first
                    connectSSE();
                    
                    // Polling as reliable fallback
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
                        document.getElementById('spotifyView').classList.add('view-enter');
                        currentPlaylist = null;
                        updateNavActive('navSpotify');
                        
                        // Check current status
                        checkSpotifyStatus();
                    }
                    
                    // ==================== FULL SCREEN PLAYER ====================
                    
                    function openFullPlayer() {
                        if (!playerState.currentSong) return;
                        const fullPlayer = document.getElementById('fullPlayer');
                        fullPlayer.classList.add('active');
                        document.body.style.overflow = 'hidden';
                        updateFullPlayer();
                    }
                    
                    function closeFullPlayer() {
                        const fullPlayer = document.getElementById('fullPlayer');
                        fullPlayer.classList.remove('active');
                        document.body.style.overflow = '';
                    }
                    
                    function updateFullPlayer() {
                        if (!playerState || !playerState.currentSong) return;
                        
                        const cover = document.getElementById('fullPlayerCover');
                        const bg = document.getElementById('fullPlayerBg');
                        const title = document.getElementById('fullPlayerTitle');
                        const artist = document.getElementById('fullPlayerArtist');
                        const btnPlay = document.getElementById('fullBtnPlayPause');
                        const btnShuffle = document.getElementById('fullBtnShuffle');
                        const btnRepeat = document.getElementById('fullBtnRepeat');
                        const progress = document.getElementById('fullPlayerProgress');
                        const timeEl = document.getElementById('fullPlayerTime');
                        const durationEl = document.getElementById('fullPlayerDuration');
                        
                        const albumArt = playerState.currentSong.albumArt || '';
                        cover.src = albumArt;
                        bg.style.backgroundImage = albumArt ? 'url(' + albumArt + ')' : 'none';
                        title.textContent = playerState.currentSong.title || '-';
                        artist.textContent = playerState.currentSong.artist || '-';
                        
                        // Toggle play/pause icons
                        const playIcon = btnPlay.querySelector('.icon-play');
                        const pauseIcon = btnPlay.querySelector('.icon-pause');
                        if (playIcon && pauseIcon) {
                            playIcon.style.display = playerState.isPlaying ? 'none' : 'block';
                            pauseIcon.style.display = playerState.isPlaying ? 'block' : 'none';
                        }
                        
                        btnShuffle.classList.toggle('active', playerState.shuffleEnabled || false);
                        btnRepeat.classList.toggle('active', (playerState.repeatMode || 0) > 0);
                        
                        // Toggle repeat icons
                        const repeatIcon = btnRepeat.querySelector('.icon-repeat');
                        const repeatOneIcon = btnRepeat.querySelector('.icon-repeat-one');
                        if (repeatIcon && repeatOneIcon) {
                            repeatIcon.style.display = playerState.repeatMode === 2 ? 'none' : 'block';
                            repeatOneIcon.style.display = playerState.repeatMode === 2 ? 'block' : 'none';
                        }
                        
                        const positionMs = playerState.positionMs || 0;
                        const positionAgeMs = playerState.positionAgeMs || 0;
                        const totalDurationMs = playerState.totalDurationMs || 0;
                        const displayPos = positionMs + positionAgeMs;
                        
                        const percent = totalDurationMs > 0 ? (displayPos / totalDurationMs) * 100 : 0;
                        progress.style.width = Math.min(percent, 100) + '%';
                        timeEl.textContent = formatDuration(displayPos);
                        durationEl.textContent = formatDuration(totalDurationMs);
                    }
                    
                    function seekToFull(event) {
                        const slider = document.getElementById('fullPlayerSlider');
                        const rect = slider.getBoundingClientRect();
                        const percent = (event.clientX - rect.left) / rect.width;
                        const position = Math.floor(percent * playerState.totalDurationMs);
                        
                        const formData = new FormData();
                        formData.append('position', position);
                        fetch('/player/seek', { method: 'POST', body: formData });
                    }
                    
                    // Close full player on escape key
                    document.addEventListener('keydown', (e) => {
                        if (e.key === 'Escape') {
                            closeFullPlayer();
                        }
                    });
                    
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
                            alert('Please select a CSV file');
                            return;
                        }
                        
                        const csvContent = await file.text();
                        startSpotifyImport(csvContent);
                    }
                    
                    async function startSpotifyImport(csvContent) {
                        if (spotifyImporting) {
                            alert('An import is already in progress');
                            return;
                        }
                        
                        spotifyImporting = true;
                        
                        // Show progress UI
                        const progressDiv = document.getElementById('spotifyProgress');
                        progressDiv.classList.add('active');
                        
                        // Reset UI
                        document.getElementById('spotifyProgressFill').style.width = '0%';
                        document.getElementById('spotifyTrackCount').textContent = '0 / 0';
                        document.getElementById('spotifyCurrentTrack').textContent = 'Initializing Python...';
                        document.getElementById('spotifySuccessCount').textContent = '0';
                        document.getElementById('spotifyErrorCount').textContent = '0';
                        document.getElementById('spotifyLog').innerHTML = '';
                        document.getElementById('spotifyProgressTitle').textContent = 'Download in progress...';
                        
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
                                throw new Error(error.error || 'Import failed');
                            }
                            addSpotifyLog('Import gestartet...', 'info');
                            if (targetPlaylist) {
                                const playlistName = document.getElementById('spotifyTargetPlaylist').selectedOptions[0]?.text || targetPlaylist;
                                addSpotifyLog('Songs will be added to "' + playlistName + '"', 'info');
                            }
                        } catch (e) {
                            addSpotifyLog('Error: ' + e.message, 'error');
                            spotifyImporting = false;
                            stopSpotifyPolling();
                        }
                    }
                    
                    // Update playlist selector when playlists change
                    function updateSpotifyPlaylistSelector() {
                        const select = document.getElementById('spotifyTargetPlaylist');
                        if (!select) return;
                        
                        const currentValue = select.value;
                        select.innerHTML = '<option value="">-- No playlist (download only) --</option>';
                        
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
                        const name = prompt('New playlist name:');
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
                            alert('Error creating: ' + e.message);
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
                                document.getElementById('spotifyCurrentTrack').textContent = 'Initializing Python...';
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
                                    addSpotifyLog('Done! ' + (data.downloaded || 0) + ' downloaded, ' + (data.failed || 0) + ' failed', 'info');
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
                                document.getElementById('spotifyProgressTitle').textContent = 'Error';
                                if (statusChanged) {
                                    addSpotifyLog('Error: ' + (data.message || 'Unknown error'), 'error');
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
                            resultDiv.textContent = '✗ Error: ' + e.message;
                            resultDiv.style.color = '#e74c3c';
                        }
                    }
                    
                    async function testCsvHeaders() {
                        const resultDiv = document.getElementById('spotifyTestResult');
                        const fileInput = document.getElementById('spotifyCsvInput');
                        
                        if (!fileInput.files || fileInput.files.length === 0) {
                            resultDiv.textContent = 'Please select a CSV file first';
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
                            resultDiv.textContent = '✗ Error: ' + e.message;
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
                // CORS headers for audio playback
                response.addHeader("Access-Control-Allow-Origin", "*")
                response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
                response.addHeader("Access-Control-Allow-Headers", "Range")
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
        // CORS headers for audio playback
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Range")
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

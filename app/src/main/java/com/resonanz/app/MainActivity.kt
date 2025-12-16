package com.resonanz.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.resonanz.app.model.PlayerSheetState
import com.resonanz.app.model.Song
import com.resonanz.app.service.MusicService
import com.resonanz.app.ui.components.FullPlayer
import com.resonanz.app.ui.components.MiniPlayer
import com.resonanz.app.ui.components.PlaylistImportDialog
import com.resonanz.app.ui.components.PlaylistShareDialog
import com.resonanz.app.ui.screens.HomeScreen
import com.resonanz.app.ui.screens.LibraryScreen
import com.resonanz.app.ui.screens.SyncScreen
import com.resonanz.app.ui.theme.ResonanzTheme
import com.resonanz.app.viewmodel.PlayerViewModel
import com.resonanz.app.viewmodel.PlaylistViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {

    // State
    private val _songs = mutableStateOf<List<Song>>(emptyList())
    private val _playlists = mutableStateOf<List<Playlist>>(emptyList())
    private val _serverRunning = mutableStateOf(false)
    private val _ipAddress = mutableStateOf("")
    
    // Playlist Sharing
    private val playlistShareManager by lazy { PlaylistShareManager(this) }
    private val _sharePlaylistState = mutableStateOf<Playlist?>(null)
    
    // Reference to PlayerViewModel for device transfer commands
    private var playerViewModelRef: PlayerViewModel? = null
    
    // Reference to PlaylistViewModel for web sync
    private var playlistViewModelRef: com.resonanz.app.viewmodel.PlaylistViewModel? = null

    // Server
    private var webServer: SimpleWebServer? = null
    
    // Music Service
    private var musicService: MusicService? = null
    private var serviceBound = false

    private val deviceId: String by lazy {
        val prefs = getSharedPreferences("resonanz", MODE_PRIVATE)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private val storageDir: File by lazy {
        File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Resonanz").also {
            if (!it.exists()) it.mkdirs()
        }
    }

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val qrType = data?.getStringExtra(QrScannerActivity.EXTRA_QR_TYPE)
            val qrData = data?.getStringExtra(QrScannerActivity.EXTRA_QR_DATA)
            
            when (qrType) {
                QrScannerActivity.QR_TYPE_PLAYLIST -> {
                    qrData?.let { url ->
                        importPlaylistFromUrl(url)
                    }
                }
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted */ }
    
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadSongsFromMediaStore()
            registerMediaStoreObserver()
        }
    }
    
    private var mediaStoreObserver: ContentObserver? = null
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // Service connected
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermission()
        requestAudioPermission()
        startMusicService()
        startServer()

        setContent {
            ResonanzTheme {
                MainContent()
            }
        }
    }
    
    private fun startMusicService() {
        val intent = Intent(this, MusicService::class.java)
        startService(intent)
    }
    
    private fun requestAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        
        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                loadSongsFromMediaStore()
                registerMediaStoreObserver()
            }
            else -> {
                audioPermissionLauncher.launch(permission)
            }
        }
    }

    @Composable
    private fun MainContent() {
        val playerViewModel: PlayerViewModel = viewModel()
        val context = LocalContext.current
        
        DisposableEffect(Unit) {
            playerViewModel.connect(context)
            
            // Store reference for web command handling
            playerViewModelRef = playerViewModel
            
            // Connect player state to web server
            playerViewModel.onStateChanged = { songId, isPlaying, position, duration, queue, shuffle, repeat ->
                webServer?.updatePlayerState(songId, isPlaying, position, duration, queue, shuffle, repeat)
            }
            
            onDispose {
                playerViewModel.onStateChanged = null
                playerViewModelRef = null
                playerViewModel.disconnect()
            }
        }
        
        var selectedTab by remember { mutableIntStateOf(0) }
        
        // PlaylistViewModel for reactive playlist updates
        val playlistViewModel: PlaylistViewModel = viewModel()
        
        // Store reference for web sync
        DisposableEffect(playlistViewModel) {
            playlistViewModelRef = playlistViewModel
            onDispose { playlistViewModelRef = null }
        }

        // Observe state reactively with delegation
        val songs by _songs
        val serverRunning by _serverRunning
        val ipAddress by _ipAddress
        
        // Initialize PlaylistViewModel when webServer is available
        DisposableEffect(webServer, songs) {
            webServer?.playlistManager?.let { manager ->
                playlistViewModel.initialize(manager, songs)
            }
            onDispose { }
        }
        
        // Get playlists from ViewModel (reactive!)
        val playlistUiState by playlistViewModel.uiState.collectAsState()
        val playlists = playlistUiState.playlists
        
        // Playlist sharing state
        var sharePlaylist by remember { _sharePlaylistState }
        val importState by playlistShareManager.importState.collectAsState()
        
        val stableState by playerViewModel.stablePlayerState.collectAsState()
        val currentPosition by playerViewModel.currentPosition.collectAsState()
        val sheetState by playerViewModel.sheetState.collectAsState()
        
        val currentSong = stableState.currentSong
        val isPlaying = stableState.isPlaying
        val totalDuration = stableState.totalDuration
        val isShuffleEnabled = stableState.isShuffleEnabled
        val repeatMode = stableState.repeatMode
        
        val showFullPlayer = sheetState == PlayerSheetState.EXPANDED
        
        BackHandler(enabled = showFullPlayer) {
            playerViewModel.collapsePlayer()
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = {
                    Column {
                        // Mini Player
                        AnimatedVisibility(
                            visible = currentSong != null && !showFullPlayer,
                            enter = slideInVertically(
                                initialOffsetY = { it },
                                animationSpec = tween(250)
                            ) + fadeIn(animationSpec = tween(250)),
                            exit = slideOutVertically(
                                targetOffsetY = { it },
                                animationSpec = tween(250)
                            ) + fadeOut(animationSpec = tween(250))
                        ) {
                            currentSong?.let { song ->
                                MiniPlayer(
                                    song = song,
                                    isPlaying = isPlaying,
                                    onPlayPause = { playerViewModel.playPause() },
                                    onNext = { playerViewModel.nextSong() },
                                    onClick = { playerViewModel.expandPlayer() },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }

                        // Bottom Navigation
                        NavigationBar {
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                                label = { Text("Home") },
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 }
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Sync, contentDescription = "Sync") },
                                label = { Text("Sync") },
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 }
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Library") },
                                label = { Text("Library") },
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 }
                            )
                        }
                    }
                }
            ) { paddingValues ->
                Box(modifier = Modifier.padding(paddingValues)) {
                    when (selectedTab) {
                        0 -> {
                            val recentSongIds by playerViewModel.recentSongIds.collectAsState()
                            HomeScreen(
                                songs = songs,
                                playlists = playlists,
                                recentSongIds = recentSongIds,
                                onSongClick = { song ->
                                    playerViewModel.playSong(song, songs, "All Songs")
                                },
                                onAddToPlaylist = { song, playlist ->
                                    playlistViewModel.addSongToPlaylist(playlist.id, song.id)
                                },
                                onCreatePlaylist = { name ->
                                    playlistViewModel.createPlaylist(name)
                                },
                                onDeleteSong = { song ->
                                    deleteSong(song)
                                },
                                onDeleteSongs = { songsToDelete ->
                                    deleteSongs(songsToDelete)
                                }
                            )
                        }
                        1 -> SyncScreen(
                            serverRunning = serverRunning,
                            ipAddress = ipAddress,
                            onToggleServer = { enabled ->
                                if (enabled) startServer() else stopServer()
                            },
                            onScanQr = {
                                scannerLauncher.launch(Intent(this@MainActivity, QrScannerActivity::class.java))
                            }
                        )
                        2 -> LibraryScreen(
                            songs = songs,
                            playlistViewModel = playlistViewModel,
                            onSongClick = { song ->
                                playerViewModel.playSong(song, songs, "Library")
                            },
                            onPlayPlaylist = { playlist, playlistSongs ->
                                if (playlistSongs.isNotEmpty()) {
                                    playerViewModel.playSong(playlistSongs.first(), playlistSongs, playlist.name)
                                }
                            },
                            onShufflePlaylist = { playlist, playlistSongs ->
                                if (playlistSongs.isNotEmpty()) {
                                    playerViewModel.playSong(playlistSongs.random(), playlistSongs, playlist.name)
                                    playerViewModel.toggleShuffle()
                                }
                            },
                            onSharePlaylist = { playlist ->
                                if (!serverRunning) {
                                    startServer()
                                }
                                sharePlaylist = playlist
                            }
                        )
                    }
                }
            }

            // Full Player Overlay
            AnimatedVisibility(
                visible = showFullPlayer && currentSong != null,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300)),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            ) {
                FullPlayer(
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    currentPositionProvider = { playerViewModel.currentPosition.value },
                    totalDuration = totalDuration,
                    isShuffleEnabled = isShuffleEnabled,
                    repeatMode = repeatMode,
                    onPlayPause = { playerViewModel.playPause() },
                    onNext = { playerViewModel.nextSong() },
                    onPrevious = { playerViewModel.previousSong() },
                    onSeek = { playerViewModel.seekTo(it) },
                    onShuffleToggle = { playerViewModel.toggleShuffle() },
                    onRepeatToggle = { playerViewModel.toggleRepeat() },
                    onCollapse = { playerViewModel.collapsePlayer() }
                )
            }
            
            // Playlist Share Dialog
            sharePlaylist?.let { playlist ->
                val shareUrl = webServer?.generatePlaylistShareUrl(playlist.id, ipAddress) ?: ""
                PlaylistShareDialog(
                    playlistName = playlist.name,
                    shareUrl = shareUrl,
                    onDismiss = { sharePlaylist = null }
                )
            }
            
            // Playlist Import Dialog
            if (importState.isImporting || importState.completed || importState.error != null) {
                PlaylistImportDialog(
                    state = importState,
                    onDismiss = {
                        playlistShareManager.resetState()
                        playlistViewModel.loadPlaylists()
                        loadSongsFromMediaStore()
                    }
                )
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun loadSongsFromMediaStore() {
        Thread {
            val songList = mutableListOf<Song>()
            val addedPaths = mutableSetOf<String>()
            
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ARTIST_ID,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.MIME_TYPE
            )
            
            val selection: String? = null
            val selectionArgs: Array<String>? = null
            val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"
            
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val artistIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val mimeTypeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val path = cursor.getString(dataCol)
                    val albumId = cursor.getLong(albumIdCol)
                    val contentUri = android.content.ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                    )
                    val albumArtUri = android.content.ContentUris.withAppendedId(
                        android.net.Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    )
                    
                    val song = Song(
                        id = id.toString(),
                        title = cursor.getString(titleCol) ?: "Unknown",
                        artist = cursor.getString(artistCol) ?: "Unknown Artist",
                        artistId = cursor.getLong(artistIdCol),
                        album = cursor.getString(albumCol) ?: "Unknown Album",
                        albumId = albumId,
                        path = path,
                        contentUriString = contentUri.toString(),
                        albumArtUriString = albumArtUri.toString(),
                        duration = cursor.getLong(durationCol),
                        mimeType = cursor.getString(mimeTypeCol),
                        bitrate = null,
                        sampleRate = null
                    )
                    songList.add(song)
                    addedPaths.add(path)
                }
            }
            
            // Create album art cache directory
            val albumArtCacheDir = File(cacheDir, "albumart")
            if (!albumArtCacheDir.exists()) {
                albumArtCacheDir.mkdirs()
            }
            
            // Also add uploaded songs from storageDir (with metadata extraction)
            storageDir.listFiles()?.filter {
                it.extension.lowercase() in listOf("mp3", "m4a", "wav", "ogg", "flac", "opus", "aac", "wma", "aiff", "alac", "ape", "weba") &&
                it.absolutePath !in addedPaths
            }?.forEach { file ->
                // Extract metadata from file using MediaMetadataRetriever
                var title = file.nameWithoutExtension
                var artist = "Unknown Artist"
                var album = "Downloaded"
                var duration = 0L
                var mimeType: String? = null
                var albumArtUri: String? = null
                
                try {
                    val mmr = android.media.MediaMetadataRetriever()
                    mmr.setDataSource(file.absolutePath)
                    
                    mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.let {
                        if (it.isNotBlank()) title = it
                    }
                    mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let {
                        if (it.isNotBlank()) artist = it
                    }
                    mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let {
                        if (it.isNotBlank()) album = it
                    }
                    mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.let {
                        duration = it.toLongOrNull() ?: 0L
                    }
                    mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let {
                        mimeType = it
                    }
                    
                    // Extract and cache embedded album art
                    val embeddedPicture = mmr.embeddedPicture
                    if (embeddedPicture != null) {
                        val artFileName = "${file.absolutePath.hashCode()}.jpg"
                        val artFile = File(albumArtCacheDir, artFileName)
                        try {
                            artFile.writeBytes(embeddedPicture)
                            albumArtUri = android.net.Uri.fromFile(artFile).toString()
                        } catch (e: Exception) {
                            android.util.Log.w("MainActivity", "Could not cache album art for ${file.name}: ${e.message}")
                        }
                    }
                    
                    mmr.release()
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "Could not extract metadata from ${file.name}: ${e.message}")
                }
                
                val songId = file.absolutePath.hashCode().toString()
                songList.add(
                    Song(
                        id = songId,
                        title = title,
                        artist = artist,
                        artistId = 0L,
                        album = album,
                        albumId = 0L,
                        path = file.absolutePath,
                        contentUriString = android.net.Uri.fromFile(file).toString(),
                        albumArtUriString = albumArtUri,
                        duration = duration,
                        mimeType = mimeType,
                        bitrate = null,
                        sampleRate = null
                    )
                )
            }
            
            val sortedList = songList.sortedBy { it.title.lowercase() }
            
            runOnUiThread {
                _songs.value = sortedList
                webServer?.setSongs(sortedList)
            }
        }.start()
    }
    
    private fun registerMediaStoreObserver() {
        if (mediaStoreObserver != null) return
        
        mediaStoreObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                loadSongsFromMediaStore()
            }
        }
        
        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaStoreObserver!!
        )
    }

    private fun loadPlaylists() {
        _playlists.value = webServer?.playlistManager?.getAll() ?: emptyList()
    }

    private fun startServer() {
        try {
            webServer = SimpleWebServer(8080, storageDir, deviceId, applicationContext)
            webServer?.onFileChanged = {
                runOnUiThread {
                    loadSongsFromMediaStore()
                    loadPlaylists()
                }
            }
            webServer?.onPlaylistChanged = {
                android.util.Log.d("MainActivity", "onPlaylistChanged callback triggered! viewModelRef is ${if (playlistViewModelRef != null) "SET" else "NULL"}")
                runOnUiThread {
                    android.util.Log.d("MainActivity", "Calling playlistViewModel.loadPlaylists()")
                    playlistViewModelRef?.loadPlaylists()
                    loadPlaylists() // Also update old state for backup
                }
            }
            webServer?.setSongs(_songs.value)
            
            // Setup player command handler for web sync
            webServer?.onPlayerCommand = { command, data ->
                runOnUiThread {
                    when (command) {
                        "play" -> {
                            val songId = data as? String
                            if (songId != null) {
                                val song = _songs.value.find { it.id == songId }
                                if (song != null) {
                                    MusicService.instance?.playSong(song, _songs.value, "All Songs")
                                }
                            } else {
                                MusicService.instance?.playPause()
                            }
                        }
                        "pause" -> MusicService.instance?.playPause()
                        "next" -> MusicService.instance?.nextSong()
                        "prev" -> MusicService.instance?.previousSong()
                        "seek" -> {
                            val position = data as? Long ?: 0L
                            MusicService.instance?.seekTo(position)
                        }
                        "shuffle" -> MusicService.instance?.toggleShuffle()
                        "repeat" -> MusicService.instance?.toggleRepeat()
                        
                        // Device transfer commands
                        "transferToWeb" -> {
                            playerViewModelRef?.transferToWeb()
                        }
                        "transferToPhone" -> {
                            val position = data as? Long ?: 0L
                            playerViewModelRef?.transferToPhone(position)
                        }
                    }
                }
            }
            
            webServer?.start()
            _serverRunning.value = true
            _ipAddress.value = "http://${getLocalIpAddress()}:8080"
            loadPlaylists()
        } catch (e: Exception) {
            _serverRunning.value = false
        }
    }

    private fun stopServer() {
        webServer?.stop()
        webServer = null
        _serverRunning.value = false
        _ipAddress.value = ""
    }
    
    private fun deleteSong(song: com.resonanz.app.model.Song) {
        // Only allow deletion of local files in storageDir
        val file = java.io.File(song.path)
        if (file.exists() && file.absolutePath.startsWith(storageDir.absolutePath)) {
            if (file.delete()) {
                // Also delete cached album art
                val cacheDir = java.io.File(cacheDir, "albumart")
                val artHash = file.absolutePath.hashCode().toString()
                java.io.File(cacheDir, "$artHash.jpg").delete()
                
                // Refresh song list
                loadSongsFromMediaStore()
            }
        }
    }
    
    private fun deleteSongs(songs: List<com.resonanz.app.model.Song>) {
        var deletedCount = 0
        songs.forEach { song ->
            val file = java.io.File(song.path)
            if (file.exists() && file.absolutePath.startsWith(storageDir.absolutePath)) {
                if (file.delete()) {
                    // Also delete cached album art
                    val artCacheDir = java.io.File(cacheDir, "albumart")
                    val artHash = file.absolutePath.hashCode().toString()
                    java.io.File(artCacheDir, "$artHash.jpg").delete()
                    deletedCount++
                }
            }
        }
        if (deletedCount > 0) {
            // Refresh song list only once after all deletions
            loadSongsFromMediaStore()
        }
    }
    
    private fun importPlaylistFromUrl(url: String) {
        CoroutineScope(Dispatchers.Main).launch {
            webServer?.playlistManager?.let { playlistManager ->
                playlistShareManager.importPlaylist(url, playlistManager)
            }
        }
    }

    private fun getLocalIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ipAddress = wifiManager.connectionInfo.ipAddress
        return Formatter.formatIpAddress(ipAddress)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaStoreObserver?.let {
            contentResolver.unregisterContentObserver(it)
        }
        stopServer()
    }
}

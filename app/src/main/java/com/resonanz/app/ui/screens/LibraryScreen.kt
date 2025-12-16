package com.resonanz.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonanz.app.Playlist
import com.resonanz.app.model.Song
import com.resonanz.app.ui.components.PlaylistArtCollage
import com.resonanz.app.ui.components.SmartImage
import com.resonanz.app.viewmodel.PlaylistViewModel

data class Album(val name: String, val artist: String, val songs: List<Song>, val artUri: String?)
data class Artist(val name: String, val songs: List<Song>)

enum class LibraryTab { SONGS, ALBUMS, ARTISTS, PLAYLISTS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    songs: List<Song>,
    playlistViewModel: PlaylistViewModel,
    onSongClick: (Song) -> Unit,
    onPlayPlaylist: (Playlist, List<Song>) -> Unit,
    onShufflePlaylist: (Playlist, List<Song>) -> Unit,
    onSharePlaylist: (Playlist) -> Unit,
    modifier: Modifier = Modifier
) {
    // Collect state from ViewModel - this is reactive!
    val uiState by playlistViewModel.uiState.collectAsState()
    val playlists = uiState.playlists
    
    var selectedTab by remember { mutableStateOf(LibraryTab.PLAYLISTS) }
    var showDetailType by remember { mutableStateOf<String?>(null) } // "album", "artist", "playlist"
    var showDetailId by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showAddSongsSheet by remember { mutableStateOf(false) }
    
    val albums = remember(songs) {
        songs.groupBy { it.album }.map { (name, songList) ->
            Album(name, songList.firstOrNull()?.artist ?: "", songList, songList.firstOrNull()?.albumArtUriString)
        }.sortedBy { it.name.lowercase() }
    }
    
    val artists = remember(songs) {
        songs.groupBy { it.artist }.map { (name, songList) ->
            Artist(name, songList)
        }.sortedBy { it.name.lowercase() }
    }

    // Load playlist details when showDetailId changes
    LaunchedEffect(showDetailId) {
        if (showDetailType == "playlist" && showDetailId != null) {
            playlistViewModel.loadPlaylistDetails(showDetailId!!)
        } else {
            playlistViewModel.clearPlaylistDetails()
        }
    }
    
    // Get current playlist and songs from ViewModel (reactive!)
    val currentPlaylist = uiState.currentPlaylistDetails
    val playlistSongsFromViewModel = uiState.currentPlaylistSongs
    
    // If showing playlist detail, use the full screen component
    if (showDetailType == "playlist" && currentPlaylist != null) {
        PlaylistDetailScreen(
            playlist = currentPlaylist,
            allSongs = songs,
            onBackClick = { 
                showDetailType = null
                showDetailId = null 
            },
            onPlayAll = { songsToPlay -> onPlayPlaylist(currentPlaylist, songsToPlay) },
            onShuffleAll = { songsToPlay -> onShufflePlaylist(currentPlaylist, songsToPlay) },
            onSongClick = onSongClick,
            onAddSongs = { showAddSongsSheet = true },
            onRemoveSong = { songId -> 
                playlistViewModel.removeSongFromPlaylist(currentPlaylist.id, songId)
            },
            onRenamePlaylist = { newName -> 
                playlistViewModel.renamePlaylist(currentPlaylist.id, newName)
            },
            onDeletePlaylist = { 
                playlistViewModel.deletePlaylist(currentPlaylist.id)
                showDetailType = null
                showDetailId = null
            },
            onReorderSongs = { newOrder -> 
                playlistViewModel.reorderSongsInPlaylist(currentPlaylist.id, newOrder)
            }
        )
        
        if (showAddSongsSheet) {
            AddSongsToPlaylistSheet(
                allSongs = songs,
                currentSongIds = currentPlaylist.songs,
                onDismiss = { showAddSongsSheet = false },
                onConfirm = { selectedIds ->
                    // Add new songs
                    selectedIds.forEach { songId ->
                        if (songId !in currentPlaylist.songs) {
                            playlistViewModel.addSongToPlaylist(currentPlaylist.id, songId)
                        }
                    }
                    // Remove deselected songs
                    currentPlaylist.songs.forEach { songId ->
                        if (songId !in selectedIds) {
                            playlistViewModel.removeSongFromPlaylist(currentPlaylist.id, songId)
                        }
                    }
                    showAddSongsSheet = false
                }
            )
        }
        return
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Get current album/artist for detail view
        val currentAlbum = if (showDetailType == "album") {
            albums.find { it.name == showDetailId }
        } else null
        
        val currentArtist = if (showDetailType == "artist") {
            artists.find { it.name == showDetailId }
        } else null
        
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showDetailType != null) {
                IconButton(onClick = { 
                    showDetailType = null
                    showDetailId = null 
                }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            }
            
            Text(
                text = when {
                    currentAlbum != null -> currentAlbum.name
                    currentArtist != null -> currentArtist.name
                    else -> "Library"
                },
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            
            if (showDetailType == null && selectedTab == LibraryTab.PLAYLISTS) {
                IconButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Playlist")
                }
            }
        }
        
        // Tabs
        if (showDetailType == null) {
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                modifier = Modifier.padding(horizontal = 16.dp),
                containerColor = MaterialTheme.colorScheme.background
            ) {
                LibraryTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = {
                            Text(
                                text = when (tab) {
                                    LibraryTab.SONGS -> "Songs"
                                    LibraryTab.ALBUMS -> "Alben"
                                    LibraryTab.ARTISTS -> "Artists"
                                    LibraryTab.PLAYLISTS -> "Playlists"
                                }
                            )
                        }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Content
        when {
            currentAlbum != null -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    items(currentAlbum.songs) { song ->
                        SongItem(
                            song = song,
                            onClick = { onSongClick(song) }
                        )
                    }
                }
            }
            currentArtist != null -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    items(currentArtist.songs) { song ->
                        SongItem(
                            song = song,
                            onClick = { onSongClick(song) }
                        )
                    }
                }
            }
            else -> {
                when (selectedTab) {
                    LibraryTab.SONGS -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                        ) {
                            items(songs) { song ->
                                SongItem(
                                    song = song,
                                    onClick = { onSongClick(song) }
                                )
                            }
                        }
                    }
                    LibraryTab.ALBUMS -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                        ) {
                            items(albums) { album ->
                                AlbumItem(
                                    album = album,
                                    onClick = { 
                                        showDetailType = "album"
                                        showDetailId = album.name
                                    }
                                )
                            }
                        }
                    }
                    LibraryTab.ARTISTS -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                        ) {
                            items(artists) { artist ->
                                ArtistItem(
                                    artist = artist,
                                    onClick = { 
                                        showDetailType = "artist"
                                        showDetailId = artist.name
                                    }
                                )
                            }
                        }
                    }
                    LibraryTab.PLAYLISTS -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(playlists, key = { it.id }) { playlist ->
                                val playlistSongs = remember(playlist.songs, songs) {
                                    songs.filter { it.id in playlist.songs }
                                }
                                PlaylistItem(
                                    playlist = playlist,
                                    playlistSongs = playlistSongs,
                                    onClick = { 
                                        showDetailType = "playlist"
                                        showDetailId = playlist.id
                                    },
                                    onPlay = { onPlayPlaylist(playlist, playlistSongs) },
                                    onDelete = { playlistViewModel.deletePlaylist(playlist.id) },
                                    onShare = { onSharePlaylist(playlist) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Create Playlist Dialog
    if (showCreateDialog) {
        var playlistName by remember { mutableStateOf("") }
        
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Neue Playlist") },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (playlistName.isNotBlank()) {
                            playlistViewModel.createPlaylist(playlistName)
                            showCreateDialog = false
                        }
                    }
                ) {
                    Text("Erstellen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

@Composable
fun AlbumItem(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.background
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmartImage(
                model = album.artUri,
                contentDescription = "Album Art",
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp)),
                shape = RoundedCornerShape(8.dp),
                contentScale = ContentScale.Crop
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.name,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${album.artist} • ${album.songs.size} Songs",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
fun ArtistItem(
    artist: Artist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.background
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artist.name,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${artist.songs.size} Songs",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
fun PlaylistItem(
    playlist: Playlist,
    playlistSongs: List<Song>,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Playlist Art Collage
            PlaylistArtCollage(
                songs = playlistSongs.take(4),
                modifier = Modifier.size(64.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${playlist.songs.size} Songs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Play button
            if (playlistSongs.isNotEmpty()) {
                FilledIconButton(
                    onClick = onPlay,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Abspielen"
                    )
                }
            }
            
            // More menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Mehr",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Teilen") },
                        leadingIcon = { Icon(Icons.Default.Share, null) },
                        onClick = {
                            showMenu = false
                            onShare()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Löschen") },
                        leadingIcon = { 
                            Icon(
                                Icons.Default.Delete, 
                                null,
                                tint = MaterialTheme.colorScheme.error
                            ) 
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

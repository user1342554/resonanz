package com.resonanz.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
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
import com.resonanz.app.ui.components.SmartImage
import com.resonanz.app.utils.formatDuration

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    songs: List<Song>,
    playlists: List<Playlist>,
    recentSongIds: List<String>,
    onSongClick: (Song) -> Unit,
    onAddToPlaylist: (Song, Playlist) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onDeleteSong: ((Song) -> Unit)? = null,
    onDeleteSongs: ((List<Song>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showAddToPlaylistDialog by remember { mutableStateOf<Song?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    
    // Multi-select state
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedSongIds by remember { mutableStateOf(setOf<String>()) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }
    
    // Sort songs: Top 10 recently played first, then alphabetically
    val sortedSongs = remember(songs, recentSongIds) {
        val top10RecentIds = recentSongIds.take(10).toSet()
        val recentIndexMap = recentSongIds.take(10).withIndex().associate { it.value to it.index }
        songs.sortedWith(compareBy(
            // Top 10 recently played songs first (by recency), others after
            { recentIndexMap[it.id] ?: Int.MAX_VALUE },
            // Then alphabetically by title for non-recent songs
            { if (it.id !in top10RecentIds) it.title.lowercase() else "" }
        ))
    }
    
    val filteredSongs = remember(sortedSongs, searchQuery) {
        if (searchQuery.isEmpty()) sortedSongs
        else sortedSongs.filter { 
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true) ||
            it.album.contains(searchQuery, ignoreCase = true)
        }
    }
    
    // Exit selection mode when no songs are selected
    LaunchedEffect(selectedSongIds) {
        if (selectedSongIds.isEmpty() && isSelectionMode) {
            // Keep selection mode active even with no selections
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // Header with selection mode actions
            if (isSelectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { 
                            isSelectionMode = false
                            selectedSongIds = emptySet()
                        }) {
                            Icon(Icons.Default.Close, "Abbrechen")
                        }
                        Text(
                            text = "${selectedSongIds.size} ausgewählt",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Row {
                        // Select All button
                        IconButton(onClick = {
                            selectedSongIds = if (selectedSongIds.size == filteredSongs.size) {
                                emptySet()
                            } else {
                                filteredSongs.map { it.id }.toSet()
                            }
                        }) {
                            Icon(
                                Icons.Default.SelectAll, 
                                "Alle auswählen",
                                tint = if (selectedSongIds.size == filteredSongs.size) 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "Home",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Suchen...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search"
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "${filteredSongs.size} Songs",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Song List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = if (isSelectionMode && selectedSongIds.isNotEmpty()) 80.dp else 0.dp)
            ) {
                items(filteredSongs, key = { it.id }) { song ->
                    val isSelected = song.id in selectedSongIds
                    
                    SongItemWithMenu(
                        song = song,
                        isSelectionMode = isSelectionMode,
                        isSelected = isSelected,
                        onClick = { 
                            if (isSelectionMode) {
                                selectedSongIds = if (isSelected) {
                                    selectedSongIds - song.id
                                } else {
                                    selectedSongIds + song.id
                                }
                            } else {
                                onSongClick(song)
                            }
                        },
                        onLongClick = {
                            if (!isSelectionMode) {
                                isSelectionMode = true
                                selectedSongIds = setOf(song.id)
                            }
                        },
                        onAddToPlaylist = { showAddToPlaylistDialog = song },
                        onDelete = onDeleteSong?.let { { it(song) } }
                    )
                }
            }
        }
        
        // Floating action bar for selection mode
        AnimatedVisibility(
            visible = isSelectionMode && selectedSongIds.isNotEmpty(),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showBulkDeleteConfirm = true }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${selectedSongIds.size} Songs löschen",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
    
    // Bulk delete confirmation dialog
    if (showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text("${selectedSongIds.size} Songs löschen?") },
            text = { Text("Möchtest du die ausgewählten Songs wirklich löschen? Diese Aktion kann nicht rückgängig gemacht werden.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBulkDeleteConfirm = false
                        val songsToDelete = songs.filter { it.id in selectedSongIds }
                        onDeleteSongs?.invoke(songsToDelete) ?: songsToDelete.forEach { song ->
                            onDeleteSong?.invoke(song)
                        }
                        selectedSongIds = emptySet()
                        isSelectionMode = false
                    }
                ) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
    
    // Add to Playlist Sheet
    showAddToPlaylistDialog?.let { song ->
        com.resonanz.app.ui.components.AddToPlaylistSheet(
            song = song,
            allSongs = songs,
            playlists = playlists,
            onDismiss = { showAddToPlaylistDialog = null },
            onSave = { selectedPlaylistIds ->
                selectedPlaylistIds.forEach { playlistId ->
                    playlists.find { it.id == playlistId }?.let { playlist ->
                        onAddToPlaylist(song, playlist)
                    }
                }
            },
            onCreatePlaylist = onCreatePlaylist
        )
    }
}

@Composable
fun SongItem(
    song: Song,
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
            // Album Art
            SmartImage(
                model = song.albumArtUriString,
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
                    text = song.title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${song.artist} • ${formatDuration(song.duration)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongItemWithMenu(
    song: Song,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDelete: (() -> Unit)? = null,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        color = if (isSelected) 
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else 
            MaterialTheme.colorScheme.background
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection checkbox or Album Art
            if (isSelectionMode) {
                Box(
                    modifier = Modifier.size(52.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (isSelected) "Ausgewählt" else "Nicht ausgewählt",
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else {
                // Album Art
                SmartImage(
                    model = song.albumArtUriString,
                    contentDescription = "Album Art",
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    shape = RoundedCornerShape(8.dp),
                    contentScale = ContentScale.Crop
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${song.artist} • ${formatDuration(song.duration)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // Menu Button (hidden in selection mode)
            if (!isSelectionMode) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Mehr",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Zur Playlist hinzufügen") },
                            leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) },
                            onClick = {
                                showMenu = false
                                onAddToPlaylist()
                            }
                        )
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text("Löschen", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { 
                                    Icon(
                                        Icons.Default.Delete, 
                                        null,
                                        tint = MaterialTheme.colorScheme.error
                                    ) 
                                },
                                onClick = {
                                    showMenu = false
                                    showDeleteConfirm = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Song löschen?") },
            text = { Text("Möchtest du \"${song.title}\" wirklich löschen? Diese Aktion kann nicht rückgängig gemacht werden.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete?.invoke()
                    }
                ) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

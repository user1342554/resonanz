package com.resonanz.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Reorder
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import com.resonanz.app.Playlist
import com.resonanz.app.model.Song
import com.resonanz.app.ui.components.SmartImage
import com.resonanz.app.utils.formatDuration
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlist: Playlist,
    allSongs: List<Song>,
    onBackClick: () -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onShuffleAll: (List<Song>) -> Unit,
    onSongClick: (Song) -> Unit,
    onAddSongs: () -> Unit,
    onRemoveSong: (String) -> Unit,
    onRenamePlaylist: (String) -> Unit,
    onDeletePlaylist: () -> Unit,
    onReorderSongs: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    // Keep songs in playlist order - use playlist.id + songs size as key for proper invalidation
    val playlistSongs = remember(playlist.id, playlist.songs) {
        playlist.songs.mapNotNull { songId -> allSongs.find { it.id == songId } }
    }
    
    val totalDuration = remember(playlist.id, playlist.songs) {
        playlistSongs.sumOf { it.duration }
    }
    
    var isRemoveModeEnabled by remember { mutableStateOf(false) }
    var isReorderModeEnabled by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    // Local copy for reordering with drag & drop - key includes playlist.songs for proper invalidation
    var localSongOrder by remember(playlist.id, playlist.songs) { mutableStateOf(playlist.songs) }
    val localPlaylistSongs = remember(playlist.id, localSongOrder) {
        localSongOrder.mapNotNull { songId -> allSongs.find { it.id == songId } }
    }
    
    // Track drag state for saving
    var lastMovedFrom by remember { mutableStateOf<Int?>(null) }
    var lastMovedTo by remember { mutableStateOf<Int?>(null) }
    
    val listState = rememberLazyListState()
    val view = LocalView.current
    
    val reorderableState = rememberReorderableLazyListState(
        lazyListState = listState,
        onMove = { from, to ->
            localSongOrder = localSongOrder.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            if (lastMovedFrom == null) {
                lastMovedFrom = from.index
            }
            lastMovedTo = to.index
        }
    )
    
    // Save reorder when dragging stops
    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging && lastMovedFrom != null && lastMovedTo != null) {
            if (localSongOrder != playlist.songs) {
                onReorderSongs(localSongOrder)
            }
            lastMovedFrom = null
            lastMovedTo = null
        }
    }
    
    // When exiting reorder mode, save the order
    fun saveReorder() {
        if (localSongOrder != playlist.songs) {
            onReorderSongs(localSongOrder)
        }
    }
    
    Scaffold(
        topBar = {
            Column {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = onBackClick,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${playlistSongs.size} Songs • ${formatDuration(totalDuration)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    // Edit button
                    FilledTonalIconButton(
                        onClick = { showRenameDialog = true },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Icon(Icons.Default.Edit, "Bearbeiten")
                    }
                    
                    // Delete button
                    FilledTonalIconButton(
                        onClick = { showDeleteDialog = true },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Icon(Icons.Default.Delete, "Löschen")
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Play & Shuffle Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { if (playlistSongs.isNotEmpty()) onPlayAll(playlistSongs) },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    enabled = playlistSongs.isNotEmpty(),
                    shape = RoundedCornerShape(
                        topStart = 28.dp,
                        topEnd = 12.dp,
                        bottomStart = 28.dp,
                        bottomEnd = 12.dp
                    )
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Play it", style = MaterialTheme.typography.titleMedium)
                }
                
                FilledTonalButton(
                    onClick = { if (playlistSongs.isNotEmpty()) onShuffleAll(playlistSongs) },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    enabled = playlistSongs.isNotEmpty(),
                    shape = RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 28.dp,
                        bottomStart = 12.dp,
                        bottomEnd = 28.dp
                    )
                ) {
                    Icon(Icons.Rounded.Shuffle, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Shuffle", style = MaterialTheme.typography.titleMedium)
                }
            }
            
            // Action Buttons: Remove, Reorder
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val removeCornerRadius by animateDpAsState(
                    targetValue = if (isRemoveModeEnabled) 24.dp else 12.dp,
                    label = "removeCornerRadius"
                )
                val removeButtonColor by animateColorAsState(
                    targetValue = if (isRemoveModeEnabled) 
                        MaterialTheme.colorScheme.tertiary 
                    else 
                        MaterialTheme.colorScheme.surfaceVariant,
                    label = "removeButtonColor"
                )
                val removeIconColor by animateColorAsState(
                    targetValue = if (isRemoveModeEnabled) 
                        MaterialTheme.colorScheme.onTertiary 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "removeIconColor"
                )
                
                Button(
                    onClick = { 
                        isRemoveModeEnabled = !isRemoveModeEnabled
                        if (isRemoveModeEnabled) {
                            if (isReorderModeEnabled) saveReorder()
                            isReorderModeEnabled = false
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .animateContentSize(),
                    shape = RoundedCornerShape(removeCornerRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = removeButtonColor,
                        contentColor = removeIconColor
                    )
                ) {
                    Icon(
                        Icons.Default.RemoveCircleOutline, 
                        null, 
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Remove", style = MaterialTheme.typography.labelLarge)
                }
                
                val reorderCornerRadius by animateDpAsState(
                    targetValue = if (isReorderModeEnabled) 24.dp else 12.dp,
                    label = "reorderCornerRadius"
                )
                val reorderButtonColor by animateColorAsState(
                    targetValue = if (isReorderModeEnabled) 
                        MaterialTheme.colorScheme.tertiary 
                    else 
                        MaterialTheme.colorScheme.surfaceVariant,
                    label = "reorderButtonColor"
                )
                val reorderIconColor by animateColorAsState(
                    targetValue = if (isReorderModeEnabled) 
                        MaterialTheme.colorScheme.onTertiary 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "reorderIconColor"
                )
                
                Button(
                    onClick = { 
                        if (isReorderModeEnabled) {
                            saveReorder()
                        }
                        isReorderModeEnabled = !isReorderModeEnabled
                        if (isReorderModeEnabled) isRemoveModeEnabled = false
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .animateContentSize(),
                    shape = RoundedCornerShape(reorderCornerRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = reorderButtonColor,
                        contentColor = reorderIconColor
                    )
                ) {
                    Icon(Icons.Rounded.Reorder, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (isReorderModeEnabled) "Done" else "Reorder", 
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Song List or Empty State
            if (playlistSongs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.MusicOff,
                            null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "This playlist is empty.",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Tap on 'Add Songs' to begin.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val songsToShow = if (isReorderModeEnabled) localPlaylistSongs else playlistSongs
                
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentPadding = PaddingValues(
                        top = 12.dp,
                        bottom = 120.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(songsToShow, key = { _, song -> song.id }) { index, song ->
                        ReorderableItem(
                            state = reorderableState,
                            key = song.id,
                            enabled = isReorderModeEnabled
                        ) { isDragging ->
                            val elevation by animateFloatAsState(
                                targetValue = if (isDragging) 8f else 0f,
                                label = "elevation"
                            )
                            
                            PlaylistSongItem(
                                song = song,
                                onClick = { if (!isReorderModeEnabled) onSongClick(song) },
                                onRemoveClick = { onRemoveSong(song.id) },
                                showRemoveButton = isRemoveModeEnabled,
                                showDragHandle = isReorderModeEnabled,
                                isDragging = isDragging,
                                dragModifier = Modifier.draggableHandle(
                                    onDragStarted = {
                                        ViewCompat.performHapticFeedback(
                                            view,
                                            HapticFeedbackConstantsCompat.GESTURE_START
                                        )
                                    },
                                    onDragStopped = {
                                        ViewCompat.performHapticFeedback(
                                            view,
                                            HapticFeedbackConstantsCompat.GESTURE_END
                                        )
                                    }
                                ),
                                modifier = Modifier.graphicsLayer {
                                    shadowElevation = elevation
                                }
                            )
                        }
                    }
                }
            }
            
            // Add Songs FAB
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                ExtendedFloatingActionButton(
                    onClick = onAddSongs,
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Songs")
                }
            }
        }
    }
    
    // Rename Dialog
    if (showRenameDialog) {
        RenamePlaylistDialog(
            currentName = playlist.name,
            onDismiss = { showRenameDialog = false },
            onRename = { newName ->
                onRenamePlaylist(newName)
                showRenameDialog = false
            }
        )
    }
    
    // Delete Confirmation Dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Playlist löschen?") },
            text = { Text("Bist du sicher, dass du \"${playlist.name}\" löschen möchtest?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePlaylist()
                        showDeleteDialog = false
                    }
                ) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

@Composable
private fun PlaylistSongItem(
    song: Song,
    onClick: () -> Unit,
    onRemoveClick: () -> Unit,
    showRemoveButton: Boolean,
    showDragHandle: Boolean = false,
    isDragging: Boolean = false,
    dragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isDragging) 
            MaterialTheme.colorScheme.primaryContainer 
        else 
            MaterialTheme.colorScheme.surface,
        label = "bgColor"
    )
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clickable(enabled = !showDragHandle, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drag handle on the left when in reorder mode
            if (showDragHandle) {
                Box(
                    modifier = dragModifier
                        .padding(end = 8.dp)
                ) {
                    Icon(
                        Icons.Default.DragIndicator,
                        contentDescription = "Drag to reorder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            SmartImage(
                model = song.albumArtUriString,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(8.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${song.artist} • ${formatDuration(song.duration)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            if (showRemoveButton) {
                IconButton(onClick = onRemoveClick) {
                    Icon(
                        Icons.Default.RemoveCircleOutline,
                        "Entfernen",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenamePlaylistDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playlist umbenennen") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Neuer Name") },
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { if (newName.isNotBlank()) onRename(newName) },
                enabled = newName.isNotBlank() && newName != currentName
            ) {
                Text("Umbenennen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSongsToPlaylistSheet(
    allSongs: List<Song>,
    currentSongIds: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    
    val selectedSongIds = remember {
        mutableStateMapOf<String, Boolean>().apply {
            currentSongIds.forEach { put(it, true) }
        }
    }
    
    val filteredSongs = remember(searchQuery, allSongs) {
        if (searchQuery.isBlank()) allSongs
        else allSongs.filter {
            it.title.contains(searchQuery, true) || 
            it.artist.contains(searchQuery, true)
        }
    }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Text(
                text = "Songs hinzufügen",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            
            // Search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Songs suchen...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = CircleShape,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, null)
                        }
                    }
                }
            )
            
            // Song list
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                items(filteredSongs, key = { it.id }) { song ->
                    val isSelected = selectedSongIds[song.id] ?: false
                    
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedSongIds[song.id] = !isSelected
                            },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { selectedSongIds[song.id] = it }
                            )
                            
                            SmartImage(
                                model = song.albumArtUriString,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                shape = CircleShape
                            )
                            
                            Spacer(modifier = Modifier.width(12.dp))
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = song.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            
            // Confirm FAB
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        onConfirm(selectedSongIds.filter { it.value }.keys.toList())
                    },
                    shape = CircleShape
                ) {
                    Icon(Icons.Rounded.Check, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Hinzufügen")
                }
            }
        }
    }
}


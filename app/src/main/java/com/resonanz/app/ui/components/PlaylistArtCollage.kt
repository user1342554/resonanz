package com.resonanz.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.resonanz.app.model.Song

@Composable
fun PlaylistArtCollage(
    songs: List<Song>,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    
    if (songs.isEmpty()) {
        Box(
            modifier = modifier
                .aspectRatio(1f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "Playlist",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    } else {
        Surface(
            modifier = modifier.aspectRatio(1f),
            shape = shape,
            color = Color.Transparent
        ) {
            when (songs.size) {
                1 -> {
                    SmartImage(
                        model = songs[0].albumArtUriString,
                        contentDescription = songs[0].title,
                        contentScale = ContentScale.Crop,
                        shape = shape,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                2 -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        SmartImage(
                            model = songs[0].albumArtUriString,
                            contentDescription = songs[0].title,
                            contentScale = ContentScale.Crop,
                            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                        )
                        SmartImage(
                            model = songs[1].albumArtUriString,
                            contentDescription = songs[1].title,
                            contentScale = ContentScale.Crop,
                            shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                        )
                    }
                }
                3 -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        SmartImage(
                            model = songs[0].albumArtUriString,
                            contentDescription = songs[0].title,
                            contentScale = ContentScale.Crop,
                            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                        )
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            SmartImage(
                                model = songs[1].albumArtUriString,
                                contentDescription = songs[1].title,
                                contentScale = ContentScale.Crop,
                                shape = RoundedCornerShape(bottomStart = 12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                            )
                            SmartImage(
                                model = songs[2].albumArtUriString,
                                contentDescription = songs[2].title,
                                contentScale = ContentScale.Crop,
                                shape = RoundedCornerShape(bottomEnd = 12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                            )
                        }
                    }
                }
                else -> {
                    // 4+ songs - 2x2 grid
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            SmartImage(
                                model = songs[0].albumArtUriString,
                                contentDescription = songs[0].title,
                                contentScale = ContentScale.Crop,
                                shape = RoundedCornerShape(topStart = 12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                            )
                            SmartImage(
                                model = songs[1].albumArtUriString,
                                contentDescription = songs[1].title,
                                contentScale = ContentScale.Crop,
                                shape = RoundedCornerShape(topEnd = 12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                            )
                        }
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            SmartImage(
                                model = songs[2].albumArtUriString,
                                contentDescription = songs[2].title,
                                contentScale = ContentScale.Crop,
                                shape = RoundedCornerShape(bottomStart = 12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                            )
                            SmartImage(
                                model = songs[3].albumArtUriString,
                                contentDescription = songs[3].title,
                                contentScale = ContentScale.Crop,
                                shape = RoundedCornerShape(bottomEnd = 12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}


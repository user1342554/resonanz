package com.resonanz.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.resonanz.app.R
import com.resonanz.app.model.Song

@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val bodyTapInteractionSource = remember { MutableInteractionSource() }
    val corners = 20.dp
    val albumCorners = 10.dp
    val shape = RoundedCornerShape(corners)
    val albumShape = RoundedCornerShape(albumCorners)

    Surface(
        modifier = modifier,
        shape = shape,
        tonalElevation = 10.dp,
        color = colors.primaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 78.dp)
                .clickable(
                    indication = null,
                    interactionSource = bodyTapInteractionSource
                ) {
                    onClick()
                }
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SmartImage(
                model = song.albumArtUriString,
                shape = albumShape,
                contentDescription = "Album art",
                modifier = Modifier
                    .size(56.dp)
                    .clip(albumShape),
                contentScale = ContentScale.Crop
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                AutoScrollingText(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onPrimaryContainer
                    ),
                    gradientEdgeColor = colors.primaryContainer
                )
                AutoScrollingText(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = colors.onPrimaryContainer.copy(alpha = 0.7f)
                    ),
                    gradientEdgeColor = colors.primaryContainer
                )
            }

            FilledIconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPlayPause()
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = colors.onPrimaryContainer,
                    contentColor = colors.primaryContainer
                ),
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    painter = if (isPlaying) painterResource(R.drawable.ic_pause) else painterResource(R.drawable.ic_play),
                    contentDescription = if (isPlaying) "Pause" else "Play",
                )
            }

            FilledTonalIconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onNext()
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = colors.onPrimaryContainer.copy(alpha = 0.12f),
                    contentColor = colors.onPrimaryContainer
                ),
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_next),
                    contentDescription = "Next",
                )
            }
        }
    }
}

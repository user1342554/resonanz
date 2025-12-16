package com.resonanz.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.resonanz.app.R
import com.resonanz.app.model.Song
import com.resonanz.app.utils.formatDuration
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayer(
    currentSong: Song?,
    isPlaying: Boolean,
    currentPositionProvider: () -> Long,
    totalDuration: Long,
    isShuffleEnabled: Boolean,
    repeatMode: Int,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCollapse: () -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = currentSong ?: return
    val colors = MaterialTheme.colorScheme

    val stableControlAnimationSpec = remember {
        tween<Float>(durationMillis = 240, easing = FastOutSlowInEasing)
    }

    val controlOtherButtonsColor = colors.primary.copy(alpha = 0.15f)
    val controlPlayPauseColor = colors.primary
    val controlTintPlayPauseIcon = colors.onPrimary
    val controlTintOtherIcons = colors.primary

    var sliderDragValue by remember { mutableStateOf<Float?>(null) }
    var pendingSeekValue by remember { mutableStateOf<Float?>(null) }
    val interactionSource = remember { MutableInteractionSource() }

    // Use smooth progress for fluid seeking - reduces recompositions
    val (smoothProgress, smoothPosition) = rememberSmoothProgress(
        isPlayingProvider = { isPlaying },
        currentPositionProvider = currentPositionProvider,
        totalDuration = totalDuration,
        sampleWhilePlayingMs = 200L,
        sampleWhilePausedMs = 500L
    )

    // Clear pending seek when player reaches the target position
    LaunchedEffect(smoothProgress, pendingSeekValue) {
        pendingSeekValue?.let { pending ->
            if (kotlin.math.abs(smoothProgress - pending) < 0.02f) {
                pendingSeekValue = null
            }
        }
    }

    // Priority: drag > pending seek > smooth progress
    val effectiveProgress = sliderDragValue ?: pendingSeekValue ?: smoothProgress
    val effectivePosition = when {
        sliderDragValue != null -> (sliderDragValue!! * totalDuration).roundToLong()
        pendingSeekValue != null -> (pendingSeekValue!! * totalDuration).roundToLong()
        else -> smoothPosition
    }

    Scaffold(
        containerColor = colors.primaryContainer,
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colors.onPrimaryContainer,
                    actionIconContentColor = colors.onPrimaryContainer,
                    navigationIconContentColor = colors.onPrimaryContainer
                ),
                title = {
                    Text(
                        modifier = Modifier.padding(start = 18.dp),
                        text = "Now Playing",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .width(56.dp)
                            .height(42.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(colors.onPrimary)
                                .clickable(onClick = onCollapse),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_keyboard_arrow_down),
                                contentDescription = "Collapse",
                                tint = colors.primary
                            )
                        }
                    }
                },
                actions = {
                    Row(
                        modifier = Modifier.padding(end = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(height = 42.dp, width = 50.dp)
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 6.dp,
                                        topEnd = 50.dp,
                                        bottomStart = 6.dp,
                                        bottomEnd = 50.dp
                                    )
                                )
                                .background(colors.onPrimary)
                                .clickable { /* Queue */ },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_queue_music),
                                contentDescription = "Queue",
                                tint = colors.primary
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceAround
        ) {
            // Album Cover
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(vertical = 8.dp)
            ) {
                SmartImage(
                    model = song.albumArtUriString,
                    contentDescription = "Album art",
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            // Song Info
            Column(
                horizontalAlignment = Alignment.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
            ) {
                AutoScrollingTextOnDemand(
                    text = song.title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = colors.onPrimaryContainer
                    ),
                    gradientEdgeColor = colors.primaryContainer,
                    expansionFraction = 1f
                )
                Spacer(modifier = Modifier.height(4.dp))
                AutoScrollingTextOnDemand(
                    text = song.artist,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = colors.onPrimaryContainer.copy(alpha = 0.8f)
                    ),
                    gradientEdgeColor = colors.primaryContainer,
                    expansionFraction = 1f
                )
            }

            // Progress Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 0.dp)
                    .heightIn(min = 70.dp)
            ) {
                WavyMusicSlider(
                    value = effectiveProgress,
                    onValueChange = { newValue -> sliderDragValue = newValue },
                    onValueChangeFinished = {
                        sliderDragValue?.let { finalValue ->
                            // Keep showing the seek position until player catches up
                            pendingSeekValue = finalValue
                            onSeek((finalValue * totalDuration).roundToLong())
                        }
                        sliderDragValue = null
                    },
                    interactionSource = interactionSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    trackHeight = 6.dp,
                    thumbRadius = 8.dp,
                    activeTrackColor = colors.primary,
                    inactiveTrackColor = colors.primary.copy(alpha = 0.2f),
                    thumbColor = colors.primary,
                    waveLength = 30.dp,
                    isPlaying = isPlaying,
                    isWaveEligible = true
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        formatDuration(effectivePosition),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = colors.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        formatDuration(totalDuration),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = colors.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            // Controls
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedPlaybackControls(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    isPlayingProvider = { isPlaying },
                    onPrevious = onPrevious,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    height = 80.dp,
                    pressAnimationSpec = stableControlAnimationSpec,
                    releaseDelay = 220L,
                    colorOtherButtons = controlOtherButtonsColor,
                    colorPlayPause = controlPlayPauseColor,
                    tintPlayPauseIcon = controlTintPlayPauseIcon,
                    tintOtherIcons = controlTintOtherIcons
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom Toggle Row
                BottomToggleRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 58.dp, max = 78.dp)
                        .padding(horizontal = 26.dp, vertical = 0.dp)
                        .padding(bottom = 6.dp),
                    isShuffleEnabled = isShuffleEnabled,
                    repeatMode = repeatMode,
                    onShuffleToggle = onShuffleToggle,
                    onRepeatToggle = onRepeatToggle
                )
            }
        }
    }
}

@Composable
private fun BottomToggleRow(
    modifier: Modifier,
    isShuffleEnabled: Boolean,
    repeatMode: Int,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val rowCorners = 60.dp
    val inactiveBg = colors.primary.copy(alpha = 0.08f)

    Box(
        modifier = modifier.background(
            color = colors.onPrimary,
            shape = RoundedCornerShape(rowCorners)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .clip(RoundedCornerShape(rowCorners))
                .background(Color.Transparent),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val commonModifier = Modifier.weight(1f)

            ToggleSegmentButton(
                modifier = commonModifier,
                active = isShuffleEnabled,
                activeColor = colors.primary,
                activeCornerRadius = rowCorners,
                activeContentColor = colors.onPrimary,
                inactiveColor = inactiveBg,
                onClick = onShuffleToggle,
                iconId = R.drawable.ic_shuffle,
                contentDesc = "Shuffle"
            )
            
            val repeatActive = repeatMode != Player.REPEAT_MODE_OFF
            val repeatIcon = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
                Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat_on
                else -> R.drawable.ic_repeat
            }
            ToggleSegmentButton(
                modifier = commonModifier,
                active = repeatActive,
                activeColor = colors.secondary,
                activeCornerRadius = rowCorners,
                activeContentColor = colors.onSecondary,
                inactiveColor = inactiveBg,
                onClick = onRepeatToggle,
                iconId = repeatIcon,
                contentDesc = "Repeat"
            )
        }
    }
}

@Composable
fun ToggleSegmentButton(
    modifier: Modifier,
    active: Boolean,
    activeColor: Color,
    inactiveColor: Color = Color.Gray,
    activeContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    activeCornerRadius: androidx.compose.ui.unit.Dp = 8.dp,
    onClick: () -> Unit,
    iconId: Int,
    contentDesc: String
) {
    val bgColor by animateColorAsState(
        targetValue = if (active) activeColor else inactiveColor,
        animationSpec = tween(durationMillis = 250),
        label = ""
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (active) activeCornerRadius else 8.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = ""
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(cornerRadius))
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconId),
            contentDescription = contentDesc,
            tint = if (active) activeContentColor else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
    }
}

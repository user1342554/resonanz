package com.resonanz.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Soft, muted color palette for music player
val Background = Color(0xFF0D0D0D)
val Surface = Color(0xFF161616)
val SurfaceVariant = Color(0xFF1F1F1F)

// Warm coral/salmon accent - softer than bright purple
val Primary = Color(0xFFE8A87C)
val PrimaryContainer = Color(0xFF1A1A1A)
val OnPrimaryContainer = Color(0xFFF5E6DC)

// Muted teal for secondary actions
val Secondary = Color(0xFF7FBFB3)
val SecondaryContainer = Color(0xFF1C2625)

// Text colors
val OnBackground = Color(0xFFF2F2F2)
val OnSurface = Color(0xFFEAEAEA)
val OnSurfaceVariant = Color(0xFF9A9A9A)

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    secondaryContainer = SecondaryContainer,
    onSecondary = Color(0xFF1A1A1A),
    background = Background,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    onPrimary = Color(0xFF1A1A1A),
    onBackground = OnBackground,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant
)

@Composable
fun ResonanzTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}


package com.jum3a.livetranslate.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Mint = Color(0xFF3DDC97)

// Always dark: easy on the eyes (and on others nearby) in a mosque or lecture hall.
private val Scheme = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00281A),
    primaryContainer = Color(0xFF0F5137),
    onPrimaryContainer = Color(0xFFB7F5D6),
    secondary = Color(0xFF9CCFB8),
    onSecondary = Color(0xFF06372A),
    secondaryContainer = Color(0xFF1F3B31),
    onSecondaryContainer = Color(0xFFCBEBDD),
    tertiary = Color(0xFFF2C46D),
    onTertiary = Color(0xFF402D00),
    background = Color(0xFF0B110F),
    onBackground = Color(0xFFE3ECE7),
    surface = Color(0xFF0B110F),
    onSurface = Color(0xFFE3ECE7),
    surfaceVariant = Color(0xFF1B2622),
    onSurfaceVariant = Color(0xFFA9B8B1),
    surfaceContainerLowest = Color(0xFF070C0A),
    surfaceContainerLow = Color(0xFF101815),
    surfaceContainer = Color(0xFF141D19),
    surfaceContainerHigh = Color(0xFF19231F),
    surfaceContainerHighest = Color(0xFF1F2A25),
    outline = Color(0xFF5E6E67),
    outlineVariant = Color(0xFF2E3B36),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun Jum3aTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

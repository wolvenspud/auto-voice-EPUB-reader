package com.autovice.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = AccentAmber,
    onPrimary = InkBlack,
    primaryContainer = SurfaceVariantLight,
    onPrimaryContainer = InkBlack,
    background = PaperWhite,
    onBackground = InkBlack,
    surface = SurfaceLight,
    onSurface = InkBlack,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = InkBlack,
)

private val DarkColorScheme = darkColorScheme(
    primary = AccentAmberNight,
    onPrimary = PaperDark,
    primaryContainer = SurfaceVariantDark,
    onPrimaryContainer = TextDark,
    background = PaperDark,
    onBackground = TextDark,
    surface = SurfaceDark,
    onSurface = TextDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextDark,
)

@Composable
fun AutoVoiceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

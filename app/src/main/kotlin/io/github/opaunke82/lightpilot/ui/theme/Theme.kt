// Derived from KarooFirefly (https://github.com/derstrassi/karoofirefly),
// Copyright (c) 2026 derstrassi, MIT License. Adapted for LightPilot.

package io.github.opaunke82.lightpilot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    background = Color.White,
    surface = Color.White,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = largerTypography(Typography(), FONT_SCALE),
        content = content,
    )
}

/** Larger text on the Karoo (readable on the bike, with gloves). */
private const val FONT_SCALE = 1.15f

private fun TextStyle.scaled(f: Float) = copy(
    fontSize = fontSize * f,
    lineHeight = if (lineHeight.isSp) lineHeight * f else lineHeight,
)

private fun largerTypography(t: Typography, f: Float) = t.copy(
    displayLarge = t.displayLarge.scaled(f),
    displayMedium = t.displayMedium.scaled(f),
    displaySmall = t.displaySmall.scaled(f),
    headlineLarge = t.headlineLarge.scaled(f),
    headlineMedium = t.headlineMedium.scaled(f),
    headlineSmall = t.headlineSmall.scaled(f),
    titleLarge = t.titleLarge.scaled(f),
    titleMedium = t.titleMedium.scaled(f),
    titleSmall = t.titleSmall.scaled(f),
    bodyLarge = t.bodyLarge.scaled(f),
    bodyMedium = t.bodyMedium.scaled(f),
    bodySmall = t.bodySmall.scaled(f),
    labelLarge = t.labelLarge.scaled(f),
    labelMedium = t.labelMedium.scaled(f),
    labelSmall = t.labelSmall.scaled(f),
)

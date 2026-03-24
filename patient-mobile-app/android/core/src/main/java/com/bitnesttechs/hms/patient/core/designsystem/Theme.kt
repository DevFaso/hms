package com.bitnesttechs.hms.patient.core.designsystem

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = HmsPrimary,
    onPrimary = Color.White,
    primaryContainer = HmsPrimaryLight,
    secondary = HmsAccent,
    onSecondary = Color.White,
    background = HmsBackground,
    surface = HmsSurface,
    onBackground = HmsTextPrimary,
    onSurface = HmsTextPrimary,
    error = HmsError,
    onError = Color.White,
    outline = HmsBorder,
    surfaceVariant = HmsDivider,
)

@Composable
fun HmsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = HmsTypography,
        content = content
    )
}

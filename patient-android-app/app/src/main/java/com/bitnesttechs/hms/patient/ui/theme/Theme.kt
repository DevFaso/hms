package com.bitnesttechs.hms.patient.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = BrandLightBlue,
    onPrimaryContainer = BrandDarkBlue,
    secondary = BrandDarkBlue,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    background = androidx.compose.ui.graphics.Color.White,
    surface = SurfaceGrey,
    onBackground = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
    onSurface = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
    error = ErrorRed
)

private val DarkColorScheme = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = androidx.compose.ui.graphics.Color.Black,
    primaryContainer = BrandDarkBlue,
    onPrimaryContainer = BrandLightBlue,
    secondary = BrandDarkBlueDark,
    onSecondary = androidx.compose.ui.graphics.Color.Black,
    background = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
    surface = androidx.compose.ui.graphics.Color(0xFF2C2C2E),
    onBackground = androidx.compose.ui.graphics.Color.White,
    onSurface = androidx.compose.ui.graphics.Color.White,
    error = ErrorRed
)

@Composable
fun MediHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MediHubTypography,
        content = content
    )
}

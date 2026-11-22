package com.example.vescurus.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = GoldPrimary,
    secondary = GoldSecondary,
    tertiary = SkyHighlight,
    background = Slate950,
    surface = Slate900,
    onPrimary = Slate950,
    onSecondary = Slate950,
    onTertiary = Slate950,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun VESCURUSTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = Slate950.toArgb()
                window.navigationBarColor = androidx.compose.ui.graphics.Color.Black.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}

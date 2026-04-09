package com.tyust.course.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = SystemBlue,
    onPrimary = SurfaceWhite,
    primaryContainer = SystemBlueDark,
    onPrimaryContainer = SurfaceWhite,
    secondary = Neutral300,
    onSecondary = Neutral900,
    background = BackgroundDark,
    onBackground = Neutral50,
    surface = SurfaceDark,
    onSurface = Neutral50,
    surfaceVariant = Neutral700,
    onSurfaceVariant = Neutral300,
    error = SemanticDanger
)

private val LightColorScheme = lightColorScheme(
    primary = SystemBlue,
    onPrimary = SurfaceWhite,
    primaryContainer = SystemBlueLight,
    onPrimaryContainer = SystemBlueDark,
    secondary = Neutral500,
    onSecondary = SurfaceWhite,
    background = Neutral50,
    onBackground = Neutral900,
    surface = SurfaceWhite,
    onSurface = Neutral900,
    surfaceVariant = Neutral100,
    onSurfaceVariant = Neutral500,
    error = SemanticDanger
)

@Composable
fun CourseSelectorTheme(
    darkTheme: Boolean = false,  // Always use light theme
    dynamicColor: Boolean = false,  // Disable dynamic colors for consistent look
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 系统工具风：状态栏融于背景，不再强制涂成醒目的品牌色
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

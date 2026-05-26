package com.tyust.course.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val DarkColorScheme = darkColorScheme(
    primary = BrandPrimary,
    onPrimary = SurfaceWhite,
    primaryContainer = BrandPrimaryStrong,
    onPrimaryContainer = SurfaceWhite,
    secondary = BrandSecondary,
    onSecondary = SurfaceWhite,
    secondaryContainer = Neutral700,
    onSecondaryContainer = SurfaceWhite,
    tertiary = BlockBlue,
    background = BackgroundDark,
    onBackground = Neutral50,
    surface = SurfaceDark,
    onSurface = Neutral50,
    surfaceVariant = Neutral700,
    onSurfaceVariant = Neutral300,
    outline = Neutral500,
    outlineVariant = Neutral700,
    error = SemanticDanger,
    onError = SurfaceWhite,
    errorContainer = SemanticDangerContainer,
    onErrorContainer = Neutral900,
    surfaceTint = BrandPrimary
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4D70FF),
    onPrimary = SurfaceWhite,
    primaryContainer = Color(0xFFDAE2FF),
    onPrimaryContainer = NeuOnSurface,
    secondary = BrandSecondary,
    onSecondary = SurfaceWhite,
    secondaryContainer = NeuInsetBackground,
    onSecondaryContainer = NeuOnSurface,
    tertiary = BlockBlue,
    onTertiary = SurfaceWhite,
    background = NeuBackground,
    onBackground = NeuOnSurface,
    surface = NeuSurface,
    onSurface = NeuOnSurface,
    surfaceVariant = NeuInsetBackground,
    onSurfaceVariant = NeuOnSurfaceVariant,
    outline = NeuDarkShadow,
    outlineVariant = NeuDivider,
    error = SemanticDanger,
    onError = SurfaceWhite,
    errorContainer = SemanticDangerContainer,
    onErrorContainer = NeuOnSurface,
    surfaceTint = Color(0xFF4D70FF)
)

@Composable
fun CourseSelectorTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    primaryOverride: Color? = null,
    content: @Composable () -> Unit
) {
    val resolvedDarkTheme = false && darkTheme && isSystemInDarkTheme()
    val baseColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (resolvedDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        resolvedDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val colorScheme = if (primaryOverride != null && !resolvedDarkTheme) {
        baseColorScheme.copy(
            primary = primaryOverride,
            surfaceTint = primaryOverride,
            primaryContainer = primaryOverride.copy(alpha = 0.15f)
        )
    } else {
        baseColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !resolvedDarkTheme
                isAppearanceLightNavigationBars = !resolvedDarkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}

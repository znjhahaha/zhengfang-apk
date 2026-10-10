package com.tyust.course.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.tyust.course.ui.system.GlassPressIndication
import com.tyust.course.ui.system.LocalWallpaperAppearanceColors
import com.tyust.course.ui.system.ProvideWallpaperAppearance
import com.tyust.course.ui.system.rememberWallpaperRegionAppearance
import com.tyust.course.manager.AppearanceSettingsManager
import com.tyust.course.manager.AppThemeCoordinator
import com.tyust.course.manager.resolveDarkTheme
import com.tyust.course.manager.WallpaperRegion

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

internal val DarkColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF88BFFF),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF002F55),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF183F68),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFD6E9FF),
    secondary = Neutral300,
    onSecondary = Neutral900,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF293343),
    onSecondaryContainer = Neutral50,
    tertiary = androidx.compose.ui.graphics.Color(0xFFB8C4FF),
    onTertiary = androidx.compose.ui.graphics.Color(0xFF202A5E),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF303B71),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFE0E5FF),
    background = BackgroundDark,
    onBackground = Neutral50,
    surface = SurfaceDark,
    onSurface = Neutral50,
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF29313D),
    surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF0B0E13),
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF171B22),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFF1D222B),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF252B35),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF303744),
    onSurfaceVariant = Neutral300,
    outline = Neutral500,
    outlineVariant = Neutral700,
    error = androidx.compose.ui.graphics.Color(0xFFFFB4AB),
    onError = androidx.compose.ui.graphics.Color(0xFF690005),
    errorContainer = androidx.compose.ui.graphics.Color(0xFF742A27),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFFFFDAD6),
    surfaceTint = BrandPrimary
)

internal val LightColorScheme = lightColorScheme(
    primary = BrandPrimaryStrong,
    onPrimary = SurfaceWhite,
    primaryContainer = BrandPrimaryContainer,
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
    // Unset containers fall back to Material's lavender defaults (#ECE6F0),
    // which tinted every solid secondary button. Keep them neutral iOS fills.
    surfaceContainerLowest = SurfaceWhite,
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF6F6F8),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFFF0F0F3),
    surfaceContainerHigh = IOSFillLight,
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFE2E2E7),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF51545A),
    outline = NeuDarkShadow,
    outlineVariant = NeuDivider,
    error = SemanticDanger,
    onError = SurfaceWhite,
    errorContainer = SemanticDangerContainer,
    onErrorContainer = NeuOnSurface,
    surfaceTint = BrandPrimary
)

@Composable
fun CourseSelectorTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // 自定义壁纸只影响局部玻璃与内容颜色，不能反向切换整套 Material 主题。
    val systemDark = AppThemeCoordinator.systemNight
    val resolvedDarkTheme = darkTheme ?: resolveDarkTheme(AppearanceSettingsManager.themeMode, systemDark)
    val baseColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (resolvedDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        resolvedDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val colorScheme = baseColorScheme

    val view = LocalView.current
    val rootWallpaperColors = rememberWallpaperRegionAppearance(darkTheme = resolvedDarkTheme)
    val metrics = view.resources.displayMetrics
    val viewportWidth = view.width.takeIf { it > 0 } ?: metrics.widthPixels
    val viewportHeight = view.height.takeIf { it > 0 } ?: metrics.heightPixels
    val toneMap = rememberAppWallpaperToneMap(resolvedDarkTheme)
    val bars = androidx.compose.runtime.remember { AppSystemBarState() }
    val statusSurface = bars.statusSurface
    val wallpaperStyle = AppearanceSettingsManager.style
    val statusBarUsesDarkIcons = toneMap.usesDarkBarIcons(
        viewportWidth,
        viewportHeight,
        WallpaperRegion(0, 0, viewportWidth, (viewportHeight * 0.10f).toInt()),
        wallpaperStyle.imageBlur,
        wallpaperStyle.imageDim,
        statusSurface.toArgb(), statusSurface.alpha
    )
    val navigationBarUsesDarkIcons = toneMap.usesDarkBarIcons(
        viewportWidth,
        viewportHeight,
        WallpaperRegion(0, (viewportHeight * 0.90f).toInt(), viewportWidth, viewportHeight),
        wallpaperStyle.imageBlur,
        wallpaperStyle.imageDim
    )
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            // edge-to-edge：内容延伸到系统栏后方，玻璃顶栏/底栏自行处理 insets
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = statusBarUsesDarkIcons
                isAppearanceLightNavigationBars = navigationBarUsesDarkIcons
            }
        }
    }

    CompositionLocalProvider(
        LocalAppAppearance provides AppAppearance(resolvedDarkTheme),
        LocalSystemBarState provides bars,
        LocalIndication provides GlassPressIndication,
        LocalWallpaperAppearanceColors provides rootWallpaperColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = { ProvideWallpaperAppearance(rootWallpaperColors, content) }
        )
    }
}

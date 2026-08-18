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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.tyust.course.ui.system.GlassPressIndication
import com.tyust.course.ui.system.LocalWallpaperAppearanceColors
import com.tyust.course.ui.system.rememberWallpaperRegionAppearance
import com.tyust.course.manager.AppearanceSettingsManager
import com.tyust.course.manager.WallpaperRegion

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
    primary = BrandPrimary,
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
    onSurfaceVariant = NeuOnSurfaceVariant,
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
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // 自定义壁纸只影响局部玻璃与内容颜色，不能反向切换整套 Material 主题。
    val resolvedDarkTheme = darkTheme || isSystemInDarkTheme()
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
    val rootWallpaperColors = rememberWallpaperRegionAppearance()
    val metrics = view.resources.displayMetrics
    val viewportWidth = view.width.takeIf { it > 0 } ?: metrics.widthPixels
    val viewportHeight = view.height.takeIf { it > 0 } ?: metrics.heightPixels
    val toneMap = AppearanceSettingsManager.toneMap
    val wallpaperStyle = AppearanceSettingsManager.style
    val statusBarUsesDarkIcons = toneMap.resolve(
        viewportWidth,
        viewportHeight,
        WallpaperRegion(0, 0, viewportWidth, (viewportHeight * 0.10f).toInt()),
        wallpaperStyle.imageBlur,
        wallpaperStyle.imageDim
    ).usesDarkForeground
    val navigationBarUsesDarkIcons = toneMap.resolve(
        viewportWidth,
        viewportHeight,
        WallpaperRegion(0, (viewportHeight * 0.90f).toInt(), viewportWidth, viewportHeight),
        wallpaperStyle.imageBlur,
        wallpaperStyle.imageDim
    ).usesDarkForeground
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
        LocalIndication provides GlassPressIndication,
        LocalWallpaperAppearanceColors provides rootWallpaperColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content
        )
    }
}

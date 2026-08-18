package com.tyust.course.ui.system

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import com.tyust.course.manager.AppearanceSettingsManager
import com.tyust.course.manager.WallpaperRegion
import kotlin.math.abs
import kotlin.math.roundToInt

@Immutable
data class WallpaperAppearanceColors(
    val surface: Color,
    val solidSurface: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val border: Color,
    val usesDarkForeground: Boolean
) {
    companion object {
        val Light = WallpaperAppearanceColors(
            surface = Color.White.copy(alpha = 0.24f),
            solidSurface = Color(0xFFE9E9EE),
            onSurface = Color(0xFF1C1C1E),
            onSurfaceVariant = Color(0xFF51545A),
            border = Color(0xFF1C1C1E).copy(alpha = 0.16f),
            usesDarkForeground = true
        )
    }
}

val LocalWallpaperAppearanceColors = staticCompositionLocalOf { WallpaperAppearanceColors.Light }

@Composable
fun ProvideWallpaperAppearance(
    colors: WallpaperAppearanceColors,
    content: @Composable () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme = scheme.copy(
            surface = colors.solidSurface,
            onSurface = colors.onSurface,
            surfaceVariant = colors.surface,
            onSurfaceVariant = colors.onSurfaceVariant,
            outline = colors.border,
            outlineVariant = colors.border.copy(alpha = 0.55f)
        ),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalWallpaperAppearanceColors provides colors,
            androidx.compose.material3.LocalContentColor provides colors.onSurface,
            content = content
        )
    }
}

@Stable
class WallpaperRegionState internal constructor() {
    internal var region by mutableStateOf<WallpaperRegion?>(null)
        private set

    internal fun update(bounds: Rect) {
        if (!bounds.isFinite || bounds.width <= 0f || bounds.height <= 0f) return
        val next = WallpaperRegion(
            left = bounds.left.roundToInt(),
            top = bounds.top.roundToInt(),
            right = bounds.right.roundToInt(),
            bottom = bounds.bottom.roundToInt()
        )
        val current = region
        if (current == null ||
            abs(current.left - next.left) >= RegionPositionHysteresisPx ||
            abs(current.top - next.top) >= RegionPositionHysteresisPx ||
            abs(current.right - next.right) >= RegionSizeHysteresisPx ||
            abs(current.bottom - next.bottom) >= RegionSizeHysteresisPx
        ) {
            region = next
        }
    }
}

@Composable
fun rememberWallpaperRegionState(): WallpaperRegionState = remember { WallpaperRegionState() }

fun Modifier.wallpaperRegion(state: WallpaperRegionState): Modifier =
    onGloballyPositioned { coordinates -> state.update(coordinates.boundsInWindow()) }

@Composable
fun rememberWallpaperRegionAppearance(
    state: WallpaperRegionState? = null
): WallpaperAppearanceColors {
    val view = LocalView.current
    val metrics = view.resources.displayMetrics
    val viewportWidth = view.width.takeIf { it > 0 } ?: metrics.widthPixels
    val viewportHeight = view.height.takeIf { it > 0 } ?: metrics.heightPixels
    val toneMap = AppearanceSettingsManager.toneMap
    val style = AppearanceSettingsManager.style
    val region = state?.region ?: WallpaperRegion(0, 0, viewportWidth, viewportHeight)
    val resolved = remember(
        toneMap,
        viewportWidth,
        viewportHeight,
        region,
        style.imageBlur,
        style.imageDim
    ) {
        toneMap.resolve(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            region = region,
            blur = style.imageBlur,
            dim = style.imageDim
        )
    }

    val surfaceTarget = Color(resolved.surfaceArgb).copy(alpha = resolved.surfaceAlpha)
    val foregroundTarget = Color(resolved.foregroundArgb)
    val variantTarget = foregroundTarget.copy(alpha = 0.68f)
    val borderTarget = Color(resolved.borderArgb).copy(alpha = if (resolved.isMixed) 0.24f else 0.16f)
    val solidTarget = if (resolved.usesDarkForeground) Color(0xFFE9E9EE) else Color(0xFF2C2C2E)
    val animation = tween<Color>(durationMillis = 150)
    val surface by animateColorAsState(surfaceTarget, animation, label = "wallpaperSurface")
    val solidSurface by animateColorAsState(solidTarget, animation, label = "wallpaperSolidSurface")
    val foreground by animateColorAsState(foregroundTarget, animation, label = "wallpaperForeground")
    val variant by animateColorAsState(variantTarget, animation, label = "wallpaperForegroundVariant")
    val border by animateColorAsState(borderTarget, animation, label = "wallpaperBorder")
    return WallpaperAppearanceColors(
        surface = surface,
        solidSurface = solidSurface,
        onSurface = foreground,
        onSurfaceVariant = variant,
        border = border,
        usesDarkForeground = resolved.usesDarkForeground
    )
}

private const val RegionPositionHysteresisPx = 24
private const val RegionSizeHysteresisPx = 4

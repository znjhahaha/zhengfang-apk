package com.tyust.course.ui.system

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 为交互控件提供独立的灰阶采样源。源层与页面壁纸是兄弟关系，
 * 不记录页面内容，也不会形成控件采样自身的循环。
 */
@Composable
fun NeutralGlassBackdropProvider(
    modifier: Modifier = Modifier,
    enabled: Boolean = isBackdropSupported(),
    content: @Composable () -> Unit
) {
    val neutralBackdrop = if (enabled) rememberLayerBackdrop() else null
    val isLightTheme = !isSystemInDarkTheme()

    Box(modifier = modifier) {
        if (neutralBackdrop != null) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0f)
                    .layerBackdrop(neutralBackdrop)
            ) {
                val base = if (isLightTheme) Color(0xFFE4E6E9) else Color(0xFF24262A)
                val highlight = if (isLightTheme) Color(0xFFF9FAFB) else Color(0xFF494C52)
                val shade = if (isLightTheme) Color(0xFFC4C7CC) else Color(0xFF111317)

                drawRect(base)
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(highlight, base, shade),
                        start = androidx.compose.ui.geometry.Offset.Zero,
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height)
                    )
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.White.copy(alpha = if (isLightTheme) 0.30f else 0.08f),
                            0.46f to Color.Transparent,
                            1f to Color.Black.copy(alpha = if (isLightTheme) 0.08f else 0.18f)
                        )
                    )
                )

            }
        }

        CompositionLocalProvider(LocalNeutralGlassBackdrop provides neutralBackdrop) {
            content()
        }
    }
}
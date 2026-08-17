package com.tyust.course.ui.system.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.tyust.course.manager.rec709Luminance
import com.tyust.course.ui.system.GlassRecipe
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min

/**
 * 顶栏区域的动态亮度采样（[GlassRecipe.LuminanceSampleSize]/[GlassRecipe.LuminanceSampleHz]
 * 首次接上消费者；参考库内 AdaptiveLuminanceGlassContent 的模式）。
 *
 * 对若干 [LayerBackdrop] 的 GraphicsLayer 栅格化成 [GlassRecipe.LuminanceSampleSize]
 * 见方的缩略图，只统计**顶部带**（前 40% 行——顶栏标题真正压着的那一段）的 Rec.709
 * 平均亮度，取多来源中的**最小值**（最暗者说了算：任一来源深，文字就该转浅）。
 *
 * 内容滚过顶栏时亮度随之变化，标题颜色经滞回阈值翻转（见 SystemTopBar），
 * 因此"背景滑动决定字体颜色"是逐位置的，而不是整张壁纸一个值。
 * 3Hz × 5×5 的开销可忽略。
 */
@Composable
fun rememberAdaptiveLuminance(
    sources: List<LayerBackdrop>,
    active: Boolean = true
): State<Float> {
    val luminance = remember { mutableFloatStateOf(0.5f) }
    if (!active || sources.isEmpty()) return luminance
    LaunchedEffect(sources) {
        val n = GlassRecipe.LuminanceSampleSize
        val bandRows = max(1, n * 2 / 5)
        val period = (1000L / GlassRecipe.LuminanceSampleHz).coerceAtLeast(100L)
        val buffer = IntArray(n * n)
        while (isActive) {
            var darkest = 1f
            for (source in sources) {
                val sampled = runCatching { sampleTopBand(source, n, bandRows, buffer) }
                sampled.getOrNull()?.let { darkest = min(darkest, it) }
            }
            if (darkest < 1f) luminance.floatValue = darkest
            delay(period)
        }
    }
    return luminance
}

private suspend fun sampleTopBand(
    source: LayerBackdrop,
    n: Int,
    bandRows: Int,
    buffer: IntArray
): Float {
    val image: ImageBitmap = source.graphicsLayer.toImageBitmap()
    val w = image.width.coerceAtLeast(1)
    val h = image.height.coerceAtLeast(1)
    // 网格采样：n 列 × bandRows 行均匀分布在整幅/顶部带上，
    // 每点单像素读取——不做整图缩放，也就没有额外的位图分配
    val xStride = (w - 1).toFloat() / (n - 1).coerceAtLeast(1)
    val yStride = (h - 1).toFloat() / (bandRows - 1).coerceAtLeast(1)
    var sum = 0f
    var count = 0
    for (row in 0 until bandRows) {
        val y = (row * yStride).toInt().coerceIn(0, h - 1)
        for (col in 0 until n) {
            val x = (col * xStride).toInt().coerceIn(0, w - 1)
            val idx = row * n + col
            image.readPixels(
                buffer,
                startX = x, startY = y,
                width = 1, height = 1,
                bufferOffset = idx
            )
            sum += rec709Luminance(buffer[idx])
            count++
        }
    }
    return if (count > 0) sum / count else 1f
}

/**
 * 顶栏文字颜色的自适应成品：亮度经【滞回】翻转（<0.45 转浅字、>0.55 转回深字、
 * 中间维持现状——防止边界抖动），400ms 颜色过渡。null（未采样）时回落 onSurface。
 */
@Composable
fun rememberAdaptiveContentColor(
    sources: List<LayerBackdrop>,
    active: Boolean = true
): Color {
    val luminance by rememberAdaptiveLuminance(sources = sources, active = active)
    var prefersLight by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(luminance) {
        prefersLight = when {
            luminance < 0.45f -> true
            luminance > 0.55f -> false
            else -> prefersLight
        }
    }
    val color by animateColorAsState(
        targetValue = if (prefersLight == true) Color(0xFFF2F3F8)
        else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(durationMillis = 420),
        label = "adaptiveContentColor"
    )
    return color
}

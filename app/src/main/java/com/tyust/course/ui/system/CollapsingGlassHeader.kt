package com.tyust.course.ui.system

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.tyust.course.ui.system.glass.glassRim
import com.tyust.course.ui.system.glass.resolvePhysicalLens

/**
 * 「上划收拢成一条悬浮玻璃」这套顶栏的两枚玻璃原件。
 *
 * 课表页先落地了这套做法（`ScheduleScreen.WeekHeaderCompact`），成绩页要复用，
 * 于是把玻璃部分搬到这里；页面各自负责自己的几何与前景排布——那部分本来就该不一样，
 * 硬做成一个"通用折叠顶栏"只会长出一堆互相打架的参数。
 *
 * 两条铁律（都是踩过的坑，改动前先读）：
 * 1. 玻璃层必须是前景内容的【兄弟节点】。`layerBackdrop` 捕获所在节点的整棵子树，
 *    挂在包含按钮的父节点上，按钮就会采样一个含有自己的图层 → RenderThread 死循环
 *    → native SIGSEGV。
 * 2. `vibrancy()` 必须待在强度 guard 里面。它会提亮采样到的那块背景，展开态（强度 0）
 *    若照旧执行，顶栏后面就会留下一张比周围亮一档的"幽灵卡片"。
 */

/**
 * 状态栏细磨砂。唯一职责是让内容滚过时时钟仍可读，
 * 所以直角、不加 lens（minCornerRadius = 0 会让 canUseLiquidLens 静默退化）。
 */
@Composable
internal fun StatusBarFrost(
    height: Dp,
    collapse: Float,
    backdrop: Backdrop
) {
    val isLightTheme = !isSystemInDarkTheme()
    val tint = if (isLightTheme) {
        Color.White.copy(alpha = 0.46f * collapse)
    } else {
        Color(0xFF1E2024).copy(alpha = 0.52f * collapse)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(0.dp) },
                effects = {
                    // vibrancy 必须也进 guard，见文件头注释第 2 条
                    if (collapse > 0.01f) {
                        vibrancy()
                        val radius = GlassRecipe.TopBarBlurDp.dp.toPx() * collapse
                        if (radius > 0.01f) blur(radius)
                    }
                },
                onDrawSurface = { drawRect(tint) }
            )
    )
}

/**
 * 悬浮玻璃药片。
 *
 * `cornerRadius` 传【折叠态条高的一半】，于是折叠态正好是一枚胶囊——iOS 26 的浮动
 * 工具栏就是这个形状。它同时决定折射行程：`canUseLiquidLens` 要求
 * refractionHeight ≤ minCornerRadius，所以圆角越大折射越给得开。
 * 全宽直角条（minCornerRadius = 0）会让 lens 静默退化，玻璃感只剩磨砂。
 *
 * @param strength 显形强度。调用方刻意让它比 collapse 晚起步：顶栏还很高的时候这块
 *                 玻璃是一张大圆角卡片，提前显形会让人先看到"卡"再看到"条"。
 */
@Composable
internal fun HeaderGlassSlab(
    strength: Float,
    backdrop: Backdrop,
    cornerRadius: Dp,
    modifier: Modifier = Modifier
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accessibility = rememberGlassAccessibilityMode()
    val material = remember(accessibility) {
        GlassMaterials.resolve(GlassMaterialRole.Navigation, accessibility)
            .copy(refractionHeightDp = 16f, refractionAmountDp = 22f)
    }
    val slabShape = RoundedCornerShape(cornerRadius)
    // 白雾压薄一档：玻璃感要来自边缘光与折射，白雾一厚就是一张白卡片
    val surface = if (isLightTheme) {
        Color.White.copy(alpha = 0.22f * strength)
    } else {
        Color(0xFF1E2024).copy(alpha = 0.28f * strength)
    }
    val sheen = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = (if (isLightTheme) 0.24f else 0.12f) * strength),
            Color.Transparent
        )
    )

    Box(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { slabShape },
                effects = {
                    // vibrancy 必须也进 guard，见文件头注释第 2 条
                    if (strength > 0.01f) {
                        vibrancy()
                        val params = resolvePhysicalLens(
                            density = this,
                            material = material,
                            shape = slabShape,
                            minCornerRadiusPx = cornerRadius.toPx(),
                            minDimensionPx = size.minDimension,
                            interactionProgress = 0f,
                            enableBlur = true,
                            allowChromaticAberration = false,
                            pressScalesRefraction = false
                        )
                        val radius = params.blurPx * strength
                        if (radius > 0.01f) blur(radius)
                        if (params.useLens) {
                            lens(
                                refractionHeight = params.refractionHeightPx * strength,
                                refractionAmount = params.refractionAmountPx * strength,
                                chromaticAberration = false
                            )
                        }
                    }
                },
                highlight = { Highlight.Default.copy(alpha = 0.22f * strength) },
                shadow = {
                    Shadow(
                        radius = 14.dp,
                        color = Color.Black.copy(
                            alpha = (if (isLightTheme) 0.12f else 0.24f) * strength
                        )
                    )
                },
                // 缺了内阴影，玻璃就只是一层贴纸，没有壁厚
                innerShadow = {
                    InnerShadow(
                        radius = 8.dp,
                        offset = DpOffset(0.dp, 2.dp),
                        color = Color.Black.copy(
                            alpha = (if (isLightTheme) 0.07f else 0.16f) * strength
                        )
                    )
                },
                onDrawSurface = {
                    drawRect(surface)
                    // 上半镜面：厚度感来自这一道高光，而不是更厚的白雾
                    drawRect(
                        brush = sheen,
                        size = Size(size.width, size.height * 0.55f)
                    )
                }
            )
            // 边缘光不依赖 AGSL，是 API 31/32 上唯一稳定成立的玻璃特征
            .glassRim(
                shape = slabShape,
                intensity = strength,
                isLightTheme = isLightTheme
            )
    )
}

internal fun lerpDp(start: Dp, stop: Dp, fraction: Float): Dp =
    start + (stop - start) * fraction.coerceIn(0f, 1f)

internal fun lerpSp(startSp: Float, stopSp: Float, fraction: Float) =
    (startSp + (stopSp - startSp) * fraction.coerceIn(0f, 1f)).sp

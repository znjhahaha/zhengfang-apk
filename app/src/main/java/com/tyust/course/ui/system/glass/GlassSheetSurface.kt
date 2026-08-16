package com.tyust.course.ui.system.glass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.tyust.course.ui.system.GlassMaterialRole
import com.tyust.course.ui.system.GlassMaterials
import com.tyust.course.ui.system.rememberGlassAccessibilityMode

/**
 * Modal 角色的整块玻璃面板（登录卡、引导卡这类"一整张纸"）。
 *
 * 这套配方原先内联在 `LoginScreen` 里，引导页要用同一件东西，于是搬到这里。
 * 参数与那一版逐个对齐，所以登录页换成调用它之后视觉不变。
 *
 * 与 [glassChip] 的分工：chip 不采样背景（纯边缘光 + 淡表面），适合放在
 * **已经被 layerBackdrop 捕获的内容层里**的小控件；sheet 会真的模糊 + 折射背景，
 * 所以它必须是捕获层的**兄弟节点**，否则自采样会把 RenderThread 拖死。
 *
 * @param backdrop 采样源。为 null 时返回原 Modifier，调用方自己给不透明回退。
 */
@Composable
fun Modifier.glassSheet(
    backdrop: Backdrop?,
    cornerRadius: Dp = 28.dp
): Modifier {
    if (backdrop == null) return this
    val accessibility = rememberGlassAccessibilityMode()
    val shape = RoundedCornerShape(cornerRadius)
    val material = GlassMaterials.resolve(
        role = GlassMaterialRole.Modal,
        accessibility = accessibility
    )
    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = material.surfaceAlpha)
    val borderAlpha = material.borderAlpha

    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            val params = resolvePhysicalLens(
                density = this,
                material = material,
                shape = shape,
                minCornerRadiusPx = cornerRadius.toPx(),
                minDimensionPx = size.minDimension,
                interactionProgress = 0f,
                enableBlur = true,
                allowChromaticAberration = false
            )
            vibrancy()
            if (params.blurPx > 0f) blur(params.blurPx)
            if (params.useLens) {
                lens(
                    refractionHeight = params.refractionHeightPx,
                    refractionAmount = params.refractionAmountPx,
                    chromaticAberration = params.chromaticAberration
                )
            }
        },
        onDrawSurface = {
            drawRect(surfaceColor)
            drawRoundRect(
                color = Color.White.copy(alpha = borderAlpha),
                cornerRadius = CornerRadius(cornerRadius.toPx()),
                style = Stroke(width = 0.5f.dp.toPx())
            )
        }
    )
}

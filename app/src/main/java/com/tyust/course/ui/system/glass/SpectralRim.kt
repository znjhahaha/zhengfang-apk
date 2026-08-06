package com.tyust.course.ui.system.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.GlassMaterialSpec
import com.tyust.course.ui.system.isLensSupported

private fun Outline.asPath(): Path = when (this) {
    is Outline.Rectangle -> Path().apply { addRect(rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Generic -> path
}

/**
 * API 33+ 真 lens 已自带 Fresnel/色散，这里不再画任何彩色描边。
 * 仅在无 lens 能力时补极淡白色边缘与镜面高光，避免“固定彩虹模板”露馅。
 */
fun Modifier.spectralRim(
    shape: Shape,
    material: GlassMaterialSpec,
    interactionProgress: () -> Float,
    velocityX: () -> Float,
    pointerPosition: () -> Offset = { Offset.Unspecified }
): Modifier {
    // 真物理透镜路径：彩色只能来自 backdrop 折射，禁止模板 rim
    if (isLensSupported()) return this

    return drawWithCache {
        val outlinePath = shape.createOutline(size, LayoutDirection.Ltr, this).asPath()

        onDrawWithContent {
            drawContent()

            val progress = interactionProgress().coerceIn(0f, 1f)
            if (progress <= 0.001f) return@onDrawWithContent

            val specularAlpha = material.optics.specularAlpha * progress * 0.55f
            if (specularAlpha <= 0.001f) return@onDrawWithContent

            val pointer = pointerPosition().let { candidate ->
                if (candidate.x.isFinite() && candidate.y.isFinite()) {
                    Offset(
                        x = candidate.x.coerceIn(0f, size.width),
                        y = candidate.y.coerceIn(0f, size.height)
                    )
                } else {
                    center
                }
            }

            clipPath(outlinePath) {
                drawPath(
                    path = outlinePath,
                    color = Color.White.copy(alpha = specularAlpha * 0.45f),
                    style = Stroke(width = 1.6.dp.toPx() * 2f),
                    blendMode = BlendMode.SrcOver
                )
                drawPath(
                    path = outlinePath,
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = specularAlpha * 0.35f),
                            Color.Transparent
                        ),
                        center = pointer,
                        radius = size.maxDimension.coerceAtLeast(1f)
                    ),
                    style = Stroke(width = 3.dp.toPx()),
                    blendMode = BlendMode.Plus
                )
            }
        }
    }
}
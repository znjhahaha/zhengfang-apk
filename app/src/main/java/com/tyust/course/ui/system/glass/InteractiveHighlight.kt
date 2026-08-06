package com.tyust.course.ui.system.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.util.fastCoerceIn
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.isRuntimeShaderSupported
import com.tyust.course.ui.system.GlassRuntimeGuard
import kotlinx.coroutines.CoroutineScope

class InteractiveHighlight(
    animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset }
) {
    private val optics = InteractiveOptics(animationScope)

    val pressProgress: Float
        get() = optics.pressProgress

    val velocityX: Float
        get() = optics.velocityX

    val pointerPosition: Offset
        get() = optics.pointerPosition

    private val shader = if (
        isRuntimeShaderSupported() &&
        GlassRuntimeGuard.isDynamicOpticsEnabled()
    ) {
        runCatching {
            RuntimeShader(
                """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.16, dist);
    return color * intensity;
}
"""
            )
        }.onFailure(GlassRuntimeGuard::disableDynamicOpticsForSession).getOrNull()
    } else {
        null
    }

    val modifier: Modifier = Modifier.drawWithContent {
        drawContent()

        val progress = optics.pressProgress.coerceIn(0f, 1f)
        if (progress <= 0.001f) return@drawWithContent

        val rawPosition = optics.pointerPosition
        val mappedPosition = if (rawPosition.x.isFinite() && rawPosition.y.isFinite()) {
            position(size, rawPosition)
        } else {
            center
        }
        val lightPosition = Offset(
            mappedPosition.x.fastCoerceIn(0f, size.width),
            mappedPosition.y.fastCoerceIn(0f, size.height)
        )

        if (shader != null) {
            shader.setFloatUniform("size", size.width, size.height)
            shader.setColorUniform("color", Color.White.copy(alpha = 0.20f * progress))
            shader.setFloatUniform("radius", size.maxDimension * 0.88f)
            shader.setFloatUniform("position", lightPosition.x, lightPosition.y)
            drawRect(
                brush = ShaderBrush(shader.asComposeShader()),
                blendMode = BlendMode.Plus
            )
        } else {
            drawCircle(
                color = Color.White.copy(alpha = 0.12f * progress),
                radius = size.maxDimension * 0.62f,
                center = lightPosition,
                blendMode = BlendMode.Plus
            )
        }
    }

    val gestureModifier: Modifier = optics.gestureModifier
}
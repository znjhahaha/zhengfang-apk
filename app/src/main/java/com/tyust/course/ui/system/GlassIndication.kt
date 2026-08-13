package com.tyust.course.ui.system

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.launch

/**
 * iOS 式按压反馈：整面中性灰淡入淡出，替代 Material 的白色圆形涟漪。
 * 通过 Theme 层的 LocalIndication 全局生效，覆盖所有默认 clickable 与 Material 按钮。
 */
object GlassPressIndication : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        GlassPressNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = "GlassPressIndication".hashCode()
}

private class GlassPressNode(
    private val interactionSource: InteractionSource
) : Modifier.Node(), DrawModifierNode {

    private val pressAlpha = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press ->
                        launch { pressAlpha.animateTo(1f, tween(durationMillis = 80)) }

                    is PressInteraction.Release,
                    is PressInteraction.Cancel ->
                        launch { pressAlpha.animateTo(0f, tween(durationMillis = 240)) }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = pressAlpha.value
        if (alpha > 0.001f) {
            // iOS systemFill 中性灰：浅色背景压暗、深色背景提亮
            drawRect(color = PressOverlayColor.copy(alpha = PressOverlayAlpha * alpha))
        }
    }

    private companion object {
        val PressOverlayColor = Color(0xFF787880)
        const val PressOverlayAlpha = 0.16f
    }
}

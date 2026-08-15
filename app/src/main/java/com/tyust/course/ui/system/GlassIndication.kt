package com.tyust.course.ui.system

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
 * iOS 式按压反馈：整体轻微回缩 + 中性灰罩，替代 Material 的白色圆形涟漪。
 * 通过 Theme 层的 LocalIndication 全局生效，覆盖所有默认 clickable 与 Material 按钮，
 * 因此按压手感只在这一个文件里定义。
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
                        // 松手是弹回而不是线性褪色，手感才不发木
                        launch {
                            pressAlpha.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = 0.72f,
                                    stiffness = 380f
                                )
                            )
                        }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val progress = pressAlpha.value
        if (progress > 0.001f) {
            // iOS systemFill 中性灰：浅色背景压暗、深色背景提亮。
            // 整体回缩不在这里做：本节点位于 clickable 处，其之前绘制的背景不受影响，
            // 在这里 scale 只会缩到内容而缩不动底色。缩放由按钮最外层的 graphicsLayer 负责。
            drawRect(color = PressOverlayColor.copy(alpha = PressOverlayAlpha * progress))
        }
    }

    private companion object {
        val PressOverlayColor = Color(0xFF787880)
        const val PressOverlayAlpha = 0.16f
    }
}

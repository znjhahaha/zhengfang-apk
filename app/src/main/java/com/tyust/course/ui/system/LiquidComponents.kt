package com.tyust.course.ui.system

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.tyust.course.ui.system.glass.InteractiveHighlight
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

enum class LiquidButtonStyle {
    Transparent,
    Surface,
    Tinted
}

@Composable
fun LiquidButton(
    onClick: () -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    isInteractive: Boolean = true,
    style: LiquidButtonStyle = LiquidButtonStyle.Surface,
    shape: androidx.compose.ui.graphics.Shape = Capsule(),
    cornerRadius: androidx.compose.ui.unit.Dp = 24.dp,
    tint: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit
) {
    val useGlass = backdrop != null && isBackdropSupported()

    if (useGlass && backdrop != null) {
        val animationScope = rememberCoroutineScope()
        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(animationScope = animationScope)
        }

        Row(
            modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        vibrancy()
                        blur(2f.dp.toPx())
                        val refractionHeight = 12f.dp.toPx()
                        val refractionAmount = 24f.dp.toPx()
                        if (
                            canUseLiquidLens(
                                shape = shape,
                                refractionHeightPx = refractionHeight,
                                refractionAmountPx = refractionAmount,
                                minCornerRadiusPx = cornerRadius.toPx(),
                                minDimensionPx = size.minDimension
                            )
                        ) {
                            lens(refractionHeight, refractionAmount)
                        }
                    },
                    shadow = { Shadow(alpha = 0.25f) },
                    layerBlock = if (isInteractive) {
                        {
                            val progress = interactiveHighlight.pressProgress
                            val scale = lerp(1f, 1f + 4f.dp.toPx() / size.height, progress)

                            val maxOffset = size.minDimension
                            val initialDerivative = 0.05f
                            val offset = interactiveHighlight.offset
                            translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                            translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

                            val maxDragScale = 4f.dp.toPx() / size.height
                            val offsetAngle = atan2(offset.y, offset.x)
                            scaleX =
                                scale +
                                    maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                                    (size.width / size.height).fastCoerceAtMost(1f)
                            scaleY =
                                scale +
                                    maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                                    (size.height / size.width).fastCoerceAtMost(1f)
                        }
                    } else {
                        null
                    },
                    onDrawSurface = {
                        when (style) {
                            LiquidButtonStyle.Tinted -> {
                                val activeTint = if (tint.isSpecified) tint else Color(0xFF3B82F6)
                                drawRect(activeTint, blendMode = BlendMode.Hue)
                                drawRect(activeTint.copy(alpha = 0.75f))
                            }
                            LiquidButtonStyle.Surface -> {
                                drawRect(Color.White.copy(alpha = 0.3f))
                            }
                            LiquidButtonStyle.Transparent -> {}
                        }
                    }
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onClick
                )
                .then(
                    if (isInteractive) {
                        Modifier
                            .then(interactiveHighlight.modifier)
                            .then(interactiveHighlight.gestureModifier)
                    } else {
                        Modifier
                    }
                )
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    } else {
        Row(
            modifier
                .defaultMinSize(minHeight = 48.dp)
                .neumorphicShadow(cornerRadius = cornerRadius, elevation = 4.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
fun AnimatedIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    enabled: Boolean = true,
    buttonSize: Dp = 36.dp,
    iconSize: Dp = 18.dp,
    tint: Color = LocalContentColor.current
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.82f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 400f),
        label = "iconBtnScale"
    )

    Box(
        modifier = modifier
            .size(buttonSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = if (enabled) tint else tint.copy(alpha = 0.38f)
        )
    }
}

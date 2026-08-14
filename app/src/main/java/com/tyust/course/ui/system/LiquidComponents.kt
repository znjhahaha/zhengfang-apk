package com.tyust.course.ui.system

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.tyust.course.ui.system.glass.DampedDragAnimation
import com.tyust.course.ui.system.glass.InteractiveHighlight
import com.tyust.course.ui.system.glass.chromaticFringe
import com.tyust.course.ui.system.glass.motionIntensityFromVelocity
import com.tyust.course.ui.system.glass.resolvePhysicalLens
import com.tyust.course.ui.theme.MotionSpring
import kotlinx.coroutines.flow.collectLatest

enum class LiquidButtonStyle {
    Transparent,
    Surface,
    Tinted,

    /** 实色填充（不透明），用于玻璃弹窗等容器之上，避免玻璃叠玻璃发糊。 */
    SolidSurface,
    SolidTinted
}

@Composable
fun LiquidButton(
    onClick: () -> Unit,
    backdrop: Backdrop? = LocalNeutralGlassBackdrop.current,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isInteractive: Boolean = true,
    style: LiquidButtonStyle = LiquidButtonStyle.Surface,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(percent = 50),
    cornerRadius: Dp = 24.dp,
    tint: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    minHeight: Dp = 48.dp,
    horizontalPadding: Dp = 16.dp,
    content: @Composable RowScope.() -> Unit
) {
    val isSolid = style == LiquidButtonStyle.SolidSurface || style == LiquidButtonStyle.SolidTinted
    val glassBackdrop = backdrop?.takeIf { isBackdropSupported() && !isSolid }
    val isLightTheme = !isSystemInDarkTheme()
    val activeTint = if (tint.isSpecified) tint else MaterialTheme.colorScheme.primary
    val activeContentColor = when {
        contentColor.isSpecified -> contentColor
        style == LiquidButtonStyle.Tinted || style == LiquidButtonStyle.SolidTinted -> Color.White
        style == LiquidButtonStyle.SolidSurface -> MaterialTheme.colorScheme.onSurface
        else -> LocalContentColor.current
    }
    val resolvedContentColor = if (enabled) {
        activeContentColor
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
    }
    val disabledSurfaceColor = MaterialTheme.colorScheme.surfaceVariant.copy(
        alpha = GlassRecipe.ActionDisabledSurfaceAlpha
    )
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val accessibility = rememberGlassAccessibilityMode()
    val controlMaterial = GlassMaterials.resolve(
        role = GlassMaterialRole.Control,
        accessibility = accessibility,
        interactionProgress = if (isPressed) 1f else 0f
    )
    val buttonScale by animateFloatAsState(
        targetValue = if (enabled && isPressed && !accessibility.reduceMotion) {
            GlassRecipe.ActionPressedScale
        } else {
            1f
        },
        animationSpec = MotionSpring.liquidTap(),
        label = "liquidButtonScale"
    )

    val contentRow: @Composable (Modifier) -> Unit = { baseModifier ->
        Row(
            baseModifier
                .graphicsLayer {
                    scaleX = buttonScale
                    scaleY = buttonScale
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick
                )
                .defaultMinSize(minHeight = minHeight)
                .padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompositionLocalProvider(LocalContentColor provides resolvedContentColor) {
                content()
            }
        }
    }

    if (glassBackdrop != null) {
        val animationScope = rememberCoroutineScope()
        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(animationScope = animationScope)
        }
        val glassModifier = modifier
            .drawBackdrop(
                backdrop = glassBackdrop,
                shape = { shape },
                effects = {
                    val params = resolvePhysicalLens(
                        density = this,
                        material = controlMaterial,
                        shape = shape,
                        minCornerRadiusPx = cornerRadius.toPx(),
                        minDimensionPx = size.minDimension,
                        interactionProgress = if (isPressed) 1f else 0f,
                        motionIntensity = interactiveHighlight.pressProgress * 0.35f,
                        enableBlur = true,
                        allowChromaticAberration = isInteractive && enabled
                    )
                    vibrancy()
                    if (params.blurPx > 0f) blur(params.blurPx)
                    if (params.useLens) {
                        lens(
                            refractionHeight = params.refractionHeightPx,
                            refractionAmount = params.refractionAmountPx,
                            chromaticAberration = params.chromaticAberration
                        )
                    } else if (params.fringePx > 0f) {
                        // API 31/32：RGB 分离色散近似，交互时浮现
                        chromaticFringe(params.fringePx)
                    }
                },
                highlight = {
                    Highlight.Default.copy(
                        width = Highlight.Default.width / 1.25f,
                        blurRadius = Highlight.Default.blurRadius / 1.4f,
                        alpha = if (enabled) 0.18f + interactiveHighlight.pressProgress * 0.18f else 0.08f
                    )
                },
                shadow = {
                    Shadow(
                        alpha = if (enabled) {
                            controlMaterial.shadowAlpha +
                                interactiveHighlight.pressProgress * 0.04f
                        } else {
                            0.05f
                        }
                    )
                },
                onDrawSurface = {
                    when {
                        !enabled -> drawRect(disabledSurfaceColor)
                        style == LiquidButtonStyle.Tinted -> drawRect(
                            activeTint.copy(alpha = GlassRecipe.ActionTintAlpha)
                        )
                        style == LiquidButtonStyle.Surface -> drawRect(
                            if (isLightTheme) {
                                Color.White.copy(alpha = 0.36f)
                            } else {
                                Color.White.copy(alpha = 0.12f)
                            }
                        )
                        else -> Unit
                    }
                    if (enabled) {
                        drawRect(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = if (isLightTheme) 0.16f else 0.10f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = if (isLightTheme) 0.04f else 0.10f)
                                )
                            )
                        )
                    }
                }
            )
            .then(
                if (isInteractive && enabled) {
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                }
            )
        contentRow(glassModifier)
    } else {
        val fallbackColor = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f)
            style == LiquidButtonStyle.SolidTinted -> activeTint
            // iOS gray-fill：不透明浅灰/深灰，弹窗白底上干净清晰
            style == LiquidButtonStyle.SolidSurface ->
                if (isLightTheme) Color(0xFFEFEFF4) else Color(0xFF3A3A3C)
            style == LiquidButtonStyle.Tinted -> activeTint.copy(alpha = GlassRecipe.ActionTintAlpha)
            style == LiquidButtonStyle.Surface -> MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
            else -> Color.Transparent
        }
        val fallbackModifier = modifier
            .clip(shape)
            .background(fallbackColor)
            .then(
                if (isSolid) {
                    Modifier
                } else {
                    Modifier.border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f),
                        shape = shape
                    )
                }
            )
        contentRow(fallbackModifier)
    }
}

@Composable
fun LiquidSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop? = LocalNeutralGlassBackdrop.current,
    checkedColor: Color = Color.Unspecified
) {
    val isLightTheme = !isSystemInDarkTheme()
    val activeCheckedColor = when {
        checkedColor.isSpecified -> checkedColor
        isLightTheme -> Color(0xFF34C759)
        else -> Color(0xFF30D158)
    }
    val inactiveTrackColor = if (isLightTheme) {
        Color(0xFF787878).copy(alpha = 0.20f)
    } else {
        Color(0xFF787880).copy(alpha = 0.36f)
    }
    val glassBackdrop = backdrop?.takeIf { isBackdropSupported() }
    val accessibility = rememberGlassAccessibilityMode()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { 20.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    val latestChecked by androidx.compose.runtime.rememberUpdatedState(checked)
    val latestOnCheckedChange by androidx.compose.runtime.rememberUpdatedState(onCheckedChange)
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    val dragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                if (!enabled) return@DampedDragAnimation
                fraction = if (didDrag) {
                    didDrag = false
                    if (targetValue >= 0.5f) 1f else 0f
                } else {
                    if (latestChecked) 0f else 1f
                }
                latestOnCheckedChange(fraction == 1f)
            },
            onDrag = { _, dragAmount ->
                if (!enabled) return@DampedDragAnimation
                if (!didDrag) didDrag = dragAmount.x != 0f
                val delta = dragAmount.x / dragWidth
                fraction = if (isLtr) {
                    (fraction + delta).fastCoerceIn(0f, 1f)
                } else {
                    (fraction - delta).fastCoerceIn(0f, 1f)
                }
            }
        )
    }

    LaunchedEffect(dragAnimation) {
        snapshotFlow { fraction }
            .collectLatest(dragAnimation::updateValue)
    }
    LaunchedEffect(checked) {
        val target = if (checked) 1f else 0f
        if (target != fraction) {
            fraction = target
            dragAnimation.animateToValue(target)
        }
    }

    // 中性采样源只携带控件所需的灰阶光学信息，轨道色由 thumb 表面色兜底。
    val thumbBackdrop = glassBackdrop

    Box(
        modifier = modifier
            .width(64.dp)
            .height(48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
            .semantics {
                role = Role.Switch
                stateDescription = if (checked) "已开启" else "已关闭"
                toggleableState = androidx.compose.ui.state.ToggleableState(checked)
                onClick {
                    if (enabled) {
                        latestOnCheckedChange(!latestChecked)
                        true
                    } else {
                        false
                    }
                }
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .clip(Capsule())
                .drawBehind {
                    drawRect(
                        lerp(
                            inactiveTrackColor,
                            activeCheckedColor,
                            dragAnimation.value
                        )
                    )
                }
                .size(64.dp, 28.dp)
        )

        val thumbModifier = Modifier
            .graphicsLayer {
                val padding = 2.dp.toPx()
                translationX = if (isLtr) {
                    lerp(padding, padding + dragWidth, dragAnimation.value)
                } else {
                    lerp(-padding, -(padding + dragWidth), dragAnimation.value)
                }
            }
            .then(if (enabled) dragAnimation.modifier else Modifier)

        if (thumbBackdrop != null) {
            Box(
                modifier = thumbModifier
                    .drawBackdrop(
                        backdrop = thumbBackdrop,
                        shape = { Capsule() },
                        effects = {
                            val progress = dragAnimation.pressProgress
                            val material = GlassMaterials.resolve(
                                role = GlassMaterialRole.Interactive,
                                accessibility = accessibility,
                                interactionProgress = progress
                            )
                            val motion = motionIntensityFromVelocity(
                                velocityX = dragAnimation.velocity * dragWidth,
                                fullEffectVelocity = material.optics.velocityForFullEffect
                            )
                            val params = resolvePhysicalLens(
                                density = this,
                                material = material,
                                shape = Capsule(),
                                minCornerRadiusPx = size.minDimension / 2f,
                                minDimensionPx = size.minDimension,
                                interactionProgress = progress,
                                motionIntensity = motion,
                                // 静止时保留轻 blur，按压后转物理透镜
                                enableBlur = true,
                                allowChromaticAberration = true
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
                        highlight = {
                            val progress = dragAnimation.pressProgress
                            Highlight.Ambient.copy(
                                width = Highlight.Ambient.width / 1.5f,
                                blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                                alpha = progress
                            )
                        },
                        shadow = {
                            Shadow(
                                radius = 4.dp,
                                color = Color.Black.copy(alpha = 0.05f)
                            )
                        },
                        layerBlock = {
                            if (accessibility.reduceMotion) {
                                scaleX = 1f
                                scaleY = 1f
                            } else {
                                scaleX = dragAnimation.scaleX
                                scaleY = dragAnimation.scaleY
                                val velocity = dragAnimation.velocity / 50f
                                scaleX /= 1f -
                                    (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f -
                                    (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            }
                        },
                        onDrawSurface = {
                            val progress = dragAnimation.pressProgress
                            val material = GlassMaterials.resolve(
                                role = GlassMaterialRole.Interactive,
                                accessibility = accessibility,
                                interactionProgress = progress
                            )
                            // 静止也保持可透视，不再用 0.94 白块盖住折射
                            drawRect(
                                Color.White.copy(
                                    alpha = lerp(0.34f, material.surfaceAlpha, progress)
                                )
                            )
                        }
                    )
                    .size(40.dp, 24.dp)
            )
        } else {
            Box(
                modifier = thumbModifier
                    .graphicsLayer {
                        if (accessibility.reduceMotion) {
                            scaleX = 1f
                            scaleY = 1f
                        } else {
                            scaleX = dragAnimation.scaleX
                            scaleY = dragAnimation.scaleY
                        }
                    }
                    .clip(Capsule())
                    .background(Color.White)
                    .size(40.dp, 24.dp)
            )
        }
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
    val accessibility = rememberGlassAccessibilityMode()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled && !accessibility.reduceMotion) 0.82f else 1f,
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

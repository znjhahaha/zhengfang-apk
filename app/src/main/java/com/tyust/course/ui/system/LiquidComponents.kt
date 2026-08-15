package com.tyust.course.ui.system

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.tyust.course.ui.system.glass.DampedDragAnimation
import com.tyust.course.ui.system.glass.InteractiveHighlight
import com.tyust.course.ui.system.glass.chromaticFringe
import com.tyust.course.ui.system.glass.glassChip
import com.tyust.course.ui.system.glass.rememberPressMotion
import com.tyust.course.ui.system.glass.resolvePhysicalLens
import com.tyust.course.ui.theme.IOSDisabledFillDark
import com.tyust.course.ui.theme.IOSDisabledFillLight
import com.tyust.course.ui.theme.IOSFillDark
import com.tyust.course.ui.theme.IOSFillLight
import com.tyust.course.ui.theme.MotionSpring
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

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
    backdrop: Backdrop? = LocalControlBackdrop.current,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isInteractive: Boolean = true,
    style: LiquidButtonStyle = LiquidButtonStyle.Surface,
    shape: androidx.compose.ui.graphics.Shape = Capsule(),
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
    // iOS filled button 的按压：压扁 + 回弹过冲。玻璃分支不用它（那边由 layerBlock 做光学形变）。
    val press = rememberPressMotion(
        pressed = isPressed,
        enabled = enabled,
        depth = 0.06f,
        reduceMotion = accessibility.reduceMotion
    )
    val hasRealLens = isRuntimeShaderTrulySupported()
    // 玻璃分支的按压由 layerBlock 的光学形变表达；实色分支交给全局 iOS 按压（回缩 + 灰罩）。
    // 玻璃分支不能用 indication：drawBackdrop 不裁剪内容，灰罩会溢出成方块。
    val contentRow: @Composable (Modifier, Indication?) -> Unit = { baseModifier, pressIndication ->
        Row(
            baseModifier
                .clickable(
                    interactionSource = interactionSource,
                    indication = pressIndication,
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
        val allowInteraction = isInteractive && enabled && !accessibility.reduceMotion
        val glassModifier = modifier
            .drawBackdrop(
                backdrop = glassBackdrop,
                shape = { shape },
                // 官方 LiquidButton 的光学管线：只有 vibrancy → blur → lens，
                // 表面亮暗完全由 lens 对真实环境的折射产生。
                effects = {
                    vibrancy()
                    if (hasRealLens) {
                        blur(2.dp.toPx())
                        lens(12.dp.toPx(), 24.dp.toPx())
                    } else {
                        blur(6.dp.toPx())
                        val press = interactiveHighlight.pressProgress
                        if (press > 0f) chromaticFringe(1.2.dp.toPx() * press)
                    }
                },
                layerBlock = if (allowInteraction) {
                    {
                        // 官方 LiquidButton 同款拖拽形变：整体随按压放大，
                        // 再按拖拽角度做各向异性拉伸，位移由相对按压起点的 offset 驱动。
                        val progress = interactiveHighlight.pressProgress
                        val scale = lerp(1f, 1f + 4.dp.toPx() / size.height, progress)

                        val maxOffset = size.minDimension
                        val initialDerivative = 0.05f
                        val offset = interactiveHighlight.offset
                        translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                        translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)

                        val maxDragScale = 4.dp.toPx() / size.height
                        val offsetAngle = atan2(offset.y, offset.x)
                        scaleX = scale +
                            maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                            (size.width / size.height).fastCoerceAtMost(1f)
                        scaleY = scale +
                            maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                            (size.height / size.width).fastCoerceAtMost(1f)
                    }
                } else {
                    null
                },
                onDrawSurface = {
                    when {
                        !enabled -> drawRect(disabledSurfaceColor)
                        // 不用 BlendMode.Hue：那样只贡献色相，明度会沿用身后折射的环境亮度，
                        // 在壁纸上形成一条随位置游走的伪高光。这里直接实色覆盖。
                        style == LiquidButtonStyle.Tinted ->
                            drawRect(activeTint.copy(alpha = GlassRecipe.ActionTintAlpha))
                        style == LiquidButtonStyle.Surface -> drawRect(
                            if (isLightTheme) {
                                Color.White.copy(alpha = 0.36f)
                            } else {
                                Color.White.copy(alpha = 0.12f)
                            }
                        )
                        else -> Unit
                    }
                }
            )
            .then(
                if (isInteractive && enabled) {
                    interactiveHighlight.gestureModifier
                } else {
                    Modifier
                }
            )
        contentRow(glassModifier, null)
    } else {
        // iOS 实色路径：填充完全不透明，不参与折射，因此不会出现随环境游走的高光。
        val fallbackColor = when {
            !enabled -> if (isLightTheme) IOSDisabledFillLight else IOSDisabledFillDark
            style == LiquidButtonStyle.SolidTinted -> activeTint
            style == LiquidButtonStyle.SolidSurface -> if (isLightTheme) IOSFillLight else IOSFillDark
            style == LiquidButtonStyle.Tinted -> activeTint.copy(alpha = GlassRecipe.ActionTintAlpha)
            style == LiquidButtonStyle.Surface -> MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
            else -> Color.Transparent
        }
        val fallbackModifier = modifier
            // 回缩必须在 clip/background 之前：这样底色随内容一起缩，
            // 而不是只缩到文字。灰罩由 clickable 处的 GlassPressIndication 叠加。
            .graphicsLayer {
                scaleX = press.scaleX
                scaleY = press.scaleY
            }
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
        contentRow(fallbackModifier, LocalIndication.current)
    }
}

@Composable
fun LiquidSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop? = LocalControlBackdrop.current,
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
    // 禁用态靠"实色降对比"表达，不靠整体降透明度：后者会让轨道色透进 thumb，
    // 把绿轨道与白旋钮糊成一块灰绿。
    val disabledTrackColor = if (isLightTheme) {
        Color(0xFFE3E5E9)
    } else {
        Color(0xFF3A3C41)
    }
    val thumbColor = when {
        enabled -> Color.White
        isLightTheme -> Color(0xFFF2F2F4)
        else -> Color(0xFFB8BABE)
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
                updateValue(fraction)
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
                updateValue(fraction)
            }
        )
    }

    // value 只允许一条驱动路径：手势内走 updateValue（直接操纵），
    // 外部 checked 变化走 animateToValue（带按压释放）。两者若并发对同一个
    // Animatable 发起 animateTo，先发起的会被抢占并连锁取消释放动画。
    LaunchedEffect(checked) {
        val target = if (checked) 1f else 0f
        if (target != fraction) {
            fraction = target
            dragAnimation.animateToValue(target)
        }
    }

    val trackBackdrop = rememberLayerBackdrop()
    // 轨道近场与官方 LiquidSlider 滑块同曲线：静止时轨道层纵向塔缩为 0，
    // 滑块是不被轨道颜色污染的纯白实体；按压时轨道层展开到实尺寸，
    // 配合 lens 在滑块内输出折射与色散。
    val scaledTrackBackdrop = rememberBackdrop(trackBackdrop) { drawTrackBackdrop ->
        val progress = dragAnimation.pressProgress
        val scaleX = lerp(2f / 3f, 0.75f, progress)
        val scaleY = lerp(0f, 0.75f, progress)
        scale(scaleX, scaleY) {
            drawTrackBackdrop()
        }
    }
    val thumbBackdrop = if (glassBackdrop != null) {
        rememberCombinedBackdrop(glassBackdrop, scaledTrackBackdrop)
    } else {
        null
    }

    Box(
        modifier = modifier
            .width(64.dp)
            .height(48.dp)
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
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind {
                    drawRect(
                        if (enabled) {
                            lerp(
                                inactiveTrackColor,
                                activeCheckedColor,
                                dragAnimation.value
                            )
                        } else {
                            disabledTrackColor
                        }
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
                            blur(8.dp.toPx() * (1f - progress))
                            lens(
                                refractionHeight = 5.dp.toPx() * progress,
                                refractionAmount = 10.dp.toPx() * progress,
                                chromaticAberration = true
                            )
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
                        innerShadow = {
                            val progress = dragAnimation.pressProgress
                            InnerShadow(
                                radius = 4.dp * progress,
                                alpha = progress
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
                            // 官方同款：静止为不透明纯白旋钮（轨道色不穿透），
                            // 按压时白色退去，露出轨道折射与色散。
                            drawRect(
                                thumbColor.copy(
                                    alpha = androidx.compose.ui.util.lerp(1f, 0f, progress)
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
                    .background(thumbColor)
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
    buttonSize: Dp = 38.dp,
    iconSize: Dp = 18.dp,
    tint: Color = LocalContentColor.current,
    /** 顶栏与工具栏的图标按钮默认成为玻璃芯片，不再是裸图标。 */
    chip: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val accessibility = rememberGlassAccessibilityMode()
    // 图标芯片压得比文字按钮深一点，小控件需要更大幅度才看得出来
    val press = rememberPressMotion(
        pressed = isPressed,
        enabled = enabled,
        depth = if (chip) 0.10f else 0.14f,
        reduceMotion = accessibility.reduceMotion
    )

    Box(
        modifier = modifier
            .size(buttonSize)
            .graphicsLayer {
                scaleX = press.scaleX
                scaleY = press.scaleY
            }
            .then(
                if (chip) {
                    // 禁用时容器保留、只压暗：容器直接消失会让人以为这里没有按钮
                    Modifier.glassChip(
                        shape = CircleShape,
                        dimmed = !enabled,
                        pressProgress = { press.progress }
                    )
                } else {
                    Modifier
                }
            )
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

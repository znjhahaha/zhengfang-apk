package com.tyust.course.ui.system

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
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
import com.tyust.course.ui.system.glass.chromaticFringe
import com.tyust.course.ui.system.glass.motionIntensityFromVelocity
import com.tyust.course.ui.system.glass.resolvePhysicalLens
import com.tyust.course.ui.theme.MotionEasing
import com.tyust.course.ui.theme.MotionSpring
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 选择器选项只描述数据，不持有展开状态或页面回调。
 */
data class LiquidPickerOption(
    val label: String,
    val enabled: Boolean = true
)

@Composable
fun LiquidSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop? = LocalControlBackdrop.current,
    height: Dp = 52.dp
) {
    if (options.isEmpty()) return

    val optionCount = options.size
    val clampedSelectedIndex = selectedIndex.coerceIn(0, optionCount - 1)
    val glassBackdrop = backdrop?.takeIf { isBackdropSupported() }
    val useGlass = glassBackdrop != null
    // 真 lens（API33+）折射色散；API31/32 固定 blur 毛玻璃
    val hasRealLens = isRuntimeLensEnabled()
    val isLightTheme = !rememberGlassDarkTheme()
    val trackShape = RoundedCornerShape(percent = 50)
    val indicatorShape = RoundedCornerShape(percent = 50)
    // 隐藏内容层与环境层合成为选中透镜的采样源：折射要看得见，
    // 采样源里必须有高对比边缘（文字），只折射平滑壁纸等于没有折射。
    val segmentsBackdrop = rememberLayerBackdrop()
    val animationScope = rememberCoroutineScope()
    val accessibility = rememberGlassAccessibilityMode()
    val trackMaterial = GlassMaterials.resolve(
        role = GlassMaterialRole.Navigation,
        accessibility = accessibility,
        interactionProgress = 0f
    )
    val indicatorMaterial = GlassMaterials.resolve(
        role = GlassMaterialRole.Interactive,
        accessibility = accessibility,
        interactionProgress = 0f
    )
    val pressedScale = if (accessibility.reduceMotion) {
        1f
    } else {
        GlassRecipe.SegIndicatorPressedScale
    }
    val latestSelectedIndex by rememberUpdatedState(clampedSelectedIndex)
    val latestOnSelect by rememberUpdatedState(onSelect)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                clip = false
            }
    ) {
        val density = LocalDensity.current
        val compact = height <= 36.dp
        val horizontalPadding = if (compact) 3.dp else 5.dp
        val verticalPadding = if (compact) 3.dp else 5.dp
        val horizontalPaddingPx = with(density) { horizontalPadding.toPx() }
        val segmentWidthPx =
            ((constraints.maxWidth - horizontalPaddingPx * 2f) / optionCount)
                .coerceAtLeast(1f)
        val segmentWidth = with(density) { segmentWidthPx.toDp() }
        val indicatorWidth = segmentWidth
        // key 里【不放 segmentWidthPx】：这个动画的状态是"段序号"空间的
        // （valueRange = 0..count-1，速度也按序号算），宽度只在手势里做 px→序号换算，
        // 那处 pointerInput 自己带 key。写进来的后果是顶栏折叠时宽度每帧在变，
        // 于是动画对象每帧重建一次、滑块状态被反复丢掉。
        val dragAnimation = remember(animationScope, optionCount) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = clampedSelectedIndex.toFloat(),
                valueRange = 0f..(optionCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = pressedScale,
                directManipulationSpec = MotionSpring.liquidFollow(),
                settleAnimationSpec = MotionSpring.segmentedSettle(),
                releaseScaleAnimationSpec = MotionSpring.segmentedRelease(),
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> }
            )
        }

        val requestSelection: (Int) -> Unit = { requestedIndex ->
            if (enabled) {
                val targetIndex = requestedIndex.coerceIn(0, optionCount - 1)
                latestOnSelect(targetIndex)
                dragAnimation.animateToValue(targetIndex.toFloat())
                animationScope.launch {
                    withFrameNanos { }
                    val acceptedIndex = latestSelectedIndex.coerceIn(0, optionCount - 1)
                    if (acceptedIndex != targetIndex) {
                        dragAnimation.animateToValue(acceptedIndex.toFloat())
                    }
                }
            }
        }

        LaunchedEffect(clampedSelectedIndex, dragAnimation) {
            if (abs(dragAnimation.targetValue - clampedSelectedIndex) > 0.001f) {
                dragAnimation.animateToValue(clampedSelectedIndex.toFloat())
            }
        }

        val trackBackgroundColor = if (hasRealLens) {
            if (isLightTheme) {
                Color.White.copy(alpha = GlassRecipe.SegTrackSurfaceAlphaLight)
            } else {
                Color.Black.copy(alpha = GlassRecipe.SegTrackSurfaceAlphaDark)
            }
        } else {
            if (isLightTheme) {
                Color(GlassRecipe.NavLegacyTrackSurfaceLight)
                    .copy(alpha = GlassRecipe.NavLegacyTrackSurfaceAlpha)
            } else {
                Color.Black.copy(alpha = GlassRecipe.NavLegacyTrackSurfaceAlpha)
            }
        }
        val trackBorderColor = MaterialTheme.colorScheme.onSurface.copy(
            alpha = when {
                compact && useGlass -> 0.07f
                useGlass -> 0.10f
                else -> 0.14f
            }
        )
        val indicatorHeight = (height - verticalPadding * 2).coerceAtLeast(1.dp)
        val indicatorBackdrop = if (glassBackdrop != null) {
            rememberCombinedBackdrop(glassBackdrop, segmentsBackdrop)
        } else {
            null
        }

        // 可见标签层：作为轨道 Box 的内容绘制，随轨道 layerBlock 一起放大，
        // 手势与语义也归属该内容层（与 CapsuleNavigationBar 结构一致）。
        val segmentLabels: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)
                .pointerInput(enabled, optionCount, segmentWidthPx) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startValue = dragAnimation.value
                        val touchSlop = viewConfiguration.touchSlop
                        val tappedIndex =
                            ((down.position.x - horizontalPaddingPx) / segmentWidthPx)
                                .toInt()
                                .coerceIn(0, optionCount - 1)
                        var totalDragX = 0f
                        var dragging = false
                        var completed = false
                        var pointerId = down.id

                        dragAnimation.press()

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                    ?: break
                                if (change.changedToUpIgnoreConsumed()) {
                                    completed = true
                                    if (dragging) change.consume()
                                    break
                                }

                                val dragAmount = change.positionChange()
                                if (dragAmount != Offset.Zero) {
                                    totalDragX += dragAmount.x
                                    if (!dragging && abs(totalDragX) > touchSlop) {
                                        dragging = true
                                    }
                                    if (dragging) {
                                        change.consume()
                                        dragAnimation.updateValue(
                                            (startValue + totalDragX / segmentWidthPx)
                                                .coerceIn(0f, (optionCount - 1).toFloat())
                                        )
                                    }
                                }
                                pointerId = change.id
                            }
                        } finally {
                            when {
                                !completed -> dragAnimation.release()
                                dragging -> requestSelection(dragAnimation.targetValue.roundToInt())
                                else -> requestSelection(tappedIndex)
                            }
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEachIndexed { index, label ->
                val selectionAmount =
                    (1f - abs(dragAnimation.value - index.toFloat())).coerceIn(0f, 1f)
                val textColor = if (enabled) {
                    // 分段栏嵌在页面里，只靠字重差提示太弱：选中直接走主色，
                    // 未选中压到低对比，两端拉开后"当前在哪一段"一眼可见。
                    lerpColor(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
                        MaterialTheme.colorScheme.primary,
                        selectionAmount
                    )
                } else {
                    // 禁用只降内容对比，轨道与滑块保持实色，避免整块糊成半透明灰
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(indicatorShape)
                        .semantics(mergeDescendants = true) {
                            selected = index == clampedSelectedIndex
                            role = Role.Tab
                            onClick {
                                requestSelection(index)
                                true
                            }
                            if (!enabled) disabled()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = if (compact) 6.dp else 10.dp),
                        style = if (compact) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.labelLarge
                        },
                        fontWeight = if (selectionAmount >= 0.55f) {
                            FontWeight.ExtraBold
                        } else {
                            FontWeight.SemiBold
                        },
                        color = textColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = if (compact) TextOverflow.Clip else TextOverflow.Ellipsis
                    )
                }
            }
        }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (glassBackdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = glassBackdrop,
                            shape = { Capsule() },
                            effects = {
                                vibrancy()
                                if (hasRealLens) {
                                    val params = resolvePhysicalLens(
                                        density = this,
                                        material = trackMaterial,
                                        shape = Capsule(),
                                        minCornerRadiusPx = size.minDimension / 2f,
                                        minDimensionPx = size.minDimension,
                                        interactionProgress = 0f,
                                        enableBlur = true,
                                        allowChromaticAberration = false,
                                        pressScalesRefraction = false
                                    )
                                    if (params.blurPx > 0f) blur(params.blurPx)
                                    else blur(8.dp.toPx())
                                    if (params.useLens) {
                                        lens(
                                            refractionHeight = params.refractionHeightPx,
                                            refractionAmount = params.refractionAmountPx,
                                            chromaticAberration = false
                                        )
                                    }
                                } else {
                                    blur(GlassRecipe.NavLegacyTrackBlurDp.dp.toPx())
                                }
                            },
                            highlight = {
                                if (hasRealLens) Highlight.Default.copy(alpha = 0.16f) else null
                            },
                            shadow = { if (hasRealLens) Shadow(alpha = 0.08f) else null },
                            layerBlock = {
                                val progress = dragAnimation.pressProgress
                                val maxGain = 16.dp.toPx()
                                val pressScale = lerp(1f, 1f + maxGain / size.width, progress)
                                val indicatorBoost = (
                                    (dragAnimation.scaleX + dragAnimation.scaleY) / 2f - 1f
                                    ).coerceIn(0f, 0.4f)
                                val scale = pressScale * (1f + indicatorBoost * 0.18f)
                                scaleX = scale
                                scaleY = scale
                            },
                            onDrawSurface = { drawRect(trackBackgroundColor) }
                        )
                    } else {
                        Modifier
                            .clip(trackShape)
                            .background(trackBackgroundColor)
                            .border(0.75.dp, trackBorderColor, trackShape)
                    }
                )
        ) {
            // 可见标签与手势收进轨道内容层：轨道 layerBlock 放大时文字同步放大。
            // 它位于滑块之下，因此选中项是"透过玻璃看到的"，折射才有意义。
            segmentLabels()
        }

        val indicatorBaseModifier = Modifier
            .width(indicatorWidth)
            .height(indicatorHeight)
            .align(Alignment.CenterStart)
            .graphicsLayer {
                translationX = horizontalPaddingPx + dragAnimation.value * segmentWidthPx
                clip = false
            }

        if (glassBackdrop != null && indicatorBackdrop != null) {
            // 专供滑块折射采样的隐藏层：染成主色后，透镜里浮出的就是饱和蓝字，
            // 滑块表面因此可以做到几乎透明，不必靠白色填充去制造存在感。
            Row(
                modifier = Modifier
                    .clearAndSetSemantics { }
                    .alpha(0f)
                    .layerBackdrop(segmentsBackdrop)
                    .align(Alignment.CenterStart)
                    .height(indicatorHeight)
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .graphicsLayer(
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                options.forEach { label ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(
                                horizontal = if (compact) 6.dp else 10.dp
                            ),
                            style = if (compact) {
                                MaterialTheme.typography.labelMedium
                            } else {
                                MaterialTheme.typography.labelLarge
                            },
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = if (compact) TextOverflow.Clip else TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Box(
                modifier = indicatorBaseModifier.drawBackdrop(
                    backdrop = indicatorBackdrop,
                    shape = { Capsule() },
                    effects = {
                        val press = dragAnimation.pressProgress
                        vibrancy()
                        if (hasRealLens) {
                            val motion = motionIntensityFromVelocity(
                                velocityX = dragAnimation.velocity * segmentWidthPx,
                                fullEffectVelocity = indicatorMaterial.optics.velocityForFullEffect
                            )
                            val params = resolvePhysicalLens(
                                density = this,
                                material = indicatorMaterial,
                                shape = Capsule(),
                                minCornerRadiusPx = size.minDimension / 2f,
                                minDimensionPx = size.minDimension,
                                interactionProgress = press,
                                motionIntensity = motion,
                                enableBlur = false,
                                allowChromaticAberration = true,
                                chromaticAberrationAtRest = true,
                                pressScalesRefraction = true,
                                refractionFloor = if (compact) 0.30f else 0.42f
                            )
                            if (params.useLens) {
                                lens(
                                    refractionHeight = params.refractionHeightPx,
                                    refractionAmount = params.refractionAmountPx,
                                    chromaticAberration = params.chromaticAberration
                                )
                            }
                        } else {
                            lens(
                                refractionHeight = 10.dp.toPx() * (0.42f + press * 0.58f),
                                refractionAmount = 14.dp.toPx() * (0.42f + press * 0.58f),
                                chromaticAberration = true
                            )
                            val legacyMotion = motionIntensityFromVelocity(
                                velocityX = dragAnimation.velocity * segmentWidthPx,
                                fullEffectVelocity = indicatorMaterial.optics.velocityForFullEffect
                            )
                            chromaticFringe(
                                (0.55f + press * 1.8f + legacyMotion * 1.2f)
                                    .coerceIn(0f, 2.2f).dp.toPx()
                            )
                        }
                    },
                    highlight = {
                        val progress = dragAnimation.pressProgress
                        val scaleComp = (
                            (dragAnimation.scaleX + dragAnimation.scaleY) / 2f
                            ).coerceAtLeast(1f)
                        if (hasRealLens) {
                            Highlight.Default.copy(
                                width = Highlight.Default.width / scaleComp,
                                blurRadius = Highlight.Default.blurRadius / scaleComp,
                                alpha = GlassRecipe.SegSelectedRimAlpha + progress * 0.22f
                            )
                        } else {
                            Highlight.Default.copy(
                                width = Highlight.Default.width / scaleComp,
                                alpha = GlassRecipe.SegSelectedRimAlpha + progress * 0.22f
                            )
                        }
                    },
                    shadow = {
                        val progress = dragAnimation.pressProgress
                        if (hasRealLens) {
                            // 静止就带明显投影：选中感来自"浮起"，不是给滑块上色
                            Shadow(alpha = GlassRecipe.SegSelectedShadowAlpha + progress * 0.12f)
                        } else {
                            Shadow(alpha = GlassRecipe.SegSelectedShadowAlpha + progress * 0.30f)
                        }
                    },
                    innerShadow = {
                        val progress = dragAnimation.pressProgress
                        InnerShadow(
                            radius = 4.dp * progress,
                            alpha = progress * 0.5f
                        )
                    },
                    layerBlock = {
                        scaleX = dragAnimation.scaleX
                        scaleY = dragAnimation.scaleY
                        val velocity = dragAnimation.velocity / 10f
                        val maxStretch = GlassRecipe.SegIndicatorMaxVelocityStretch
                        scaleX /= 1f - (velocity * 0.45f)
                            .coerceIn(-maxStretch, maxStretch)
                        scaleY *= 1f - (velocity * 0.15f)
                            .coerceIn(-maxStretch, maxStretch)
                    },
                    onDrawBackdrop = { drawBackdrop -> drawBackdrop() },
                    onDrawSurface = {
                        val press = dragAnimation.pressProgress
                        if (hasRealLens) {
                            val solidColor = if (isLightTheme) {
                                Color(GlassRecipe.NavSelectedSolidColorLight)
                            } else {
                                Color(GlassRecipe.NavSelectedSolidColorDark)
                            }
                            val restAlpha = if (isLightTheme) {
                                GlassRecipe.SegSelectedSurfaceAlphaLight
                            } else {
                                GlassRecipe.SegSelectedSurfaceAlphaDark
                            }
                            val fillAlpha = lerp(
                                restAlpha,
                                GlassRecipe.NavSelectedGlassAlpha,
                                press
                            )
                            if (fillAlpha > 0f) {
                                drawRect(solidColor.copy(alpha = fillAlpha))
                            }
                        } else {
                            drawRect(Color.Black.copy(0.1f), alpha = 1f - press)
                            drawRect(Color.Black.copy(alpha = 0.03f * press))
                        }
                    }
                )
            )
        } else {
            Box(
                modifier = indicatorBaseModifier
                    .shadow(1.dp, indicatorShape, clip = false)
                    .clip(indicatorShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            )
        }

    }
}

@Composable
fun LiquidPicker(
    options: List<LiquidPickerOption>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "请选择",
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    backdrop: Backdrop? = LocalControlBackdrop.current
) {
    var expanded by remember { mutableStateOf(false) }
    val validSelectedIndex = selectedIndex?.takeIf { it in options.indices }
    val selectedLabel = validSelectedIndex?.let { options[it].label }
    val hasAvailableOption = options.any { it.enabled }
    val canOpen = enabled && (hasAvailableOption || onAction != null)
    val accessibility = rememberGlassAccessibilityMode()
    val fieldMaterial = GlassMaterials.resolve(
        role = GlassMaterialRole.Control,
        accessibility = accessibility
    )
    val glassBackdrop = backdrop?.takeIf { isBackdropSupported() }
    val shape = RoundedCornerShape(18.dp)
    val density = LocalDensity.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(canOpen) {
        if (!canOpen) expanded = false
    }

    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (accessibility.reduceMotion) {
            tween(durationMillis = 0)
        } else if (expanded) {
            MotionSpring.liquidMenu()
        } else {
            tween(durationMillis = 140, easing = MotionEasing.Accelerate)
        },
        label = "liquidPickerArrow"
    )
    val fieldScale by animateFloatAsState(
        targetValue = if (
            !accessibility.reduceMotion &&
            (isPressed || expanded) &&
            canOpen
        ) {
            0.985f
        } else {
            1f
        },
        animationSpec = MotionSpring.liquidTap(),
        label = "liquidPickerScale"
    )
    val borderColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.outlineVariant.copy(
            alpha = if (expanded) {
                (fieldMaterial.borderAlpha + 0.08f).coerceAtMost(0.72f)
            } else {
                fieldMaterial.borderAlpha
            }
        ),
        label = "liquidPickerBorder"
    )
    // 禁用只降内容对比，玻璃表面保持原样，避免整块半透明糊灰
    val primaryContentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val secondaryContentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val anchorWidth = with(density) { constraints.maxWidth.toDp() }
        val fallbackModifier = Modifier
            .shadow(if (expanded) 5.dp else 2.dp, shape, clip = false)
            .clip(shape)
            .background(
                MaterialTheme.colorScheme.surface.copy(
                    alpha = if (accessibility.highContrast) 1f else 0.94f
                )
            )
            .border(1.dp, borderColor, shape)
        val glassModifier = if (glassBackdrop != null) {
            Modifier.drawBackdrop(
                backdrop = glassBackdrop,
                shape = { shape },
                effects = {
                    val params = resolvePhysicalLens(
                        density = this,
                        material = fieldMaterial,
                        shape = shape,
                        minCornerRadiusPx = 18.dp.toPx(),
                        minDimensionPx = size.minDimension,
                        interactionProgress = if (expanded) 0.35f else 0f,
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
                shadow = {
                    Shadow(
                        alpha = fieldMaterial.shadowAlpha + if (expanded) 0.06f else 0f
                    )
                },
                onDrawSurface = {
                    drawRect(
                        Color.White.copy(
                            alpha = fieldMaterial.surfaceAlpha + if (expanded) 0.08f else 0f
                        )
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = borderColor.alpha),
                        cornerRadius = CornerRadius(18.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                    )
                }
            )
        } else {
            fallbackModifier
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(glassModifier)
                .graphicsLayer {
                    scaleX = fieldScale
                    scaleY = fieldScale
                }
                .heightIn(min = 56.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = canOpen,
                    role = Role.Button,
                    onClick = { expanded = !expanded }
                )
                .semantics {
                    contentDescription = label ?: placeholder
                    stateDescription = selectedLabel ?: placeholder
                    if (!canOpen) disabled()
                }
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = secondaryContentColor
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (!label.isNullOrBlank()) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryContentColor,
                        maxLines = 1
                    )
                }
                Text(
                    text = selectedLabel ?: placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selectedLabel != null) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selectedLabel != null) {
                        primaryContentColor
                    } else {
                        secondaryContentColor.copy(alpha = secondaryContentColor.alpha * 0.72f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起选项" else "展开选项",
                modifier = Modifier
                    .size(21.dp)
                    .rotate(arrowRotation),
                tint = secondaryContentColor
            )
        }

        LiquidAnchoredOptionMenu(
            expanded = expanded,
            options = options,
            selectedIndex = validSelectedIndex,
            anchorWidth = anchorWidth,
            onSelect = onSelect,
            onDismiss = { expanded = false },
            backdrop = backdrop,
            actionLabel = actionLabel,
            onAction = onAction
        )
    }
}

/**
 * 从锚点长出来的玻璃选项菜单。
 *
 * 原先埋在 [LiquidPicker] 里。成绩页那一行紧凑学期行也要"从字段上长出来"的同一种展开，
 * 于是抽出来两处共用——照抄一遍的代价不是行数，而是两份出场时序迟早会分叉。
 *
 * 两个不能动的细节：
 * 1. `Popup` 必须【先挂载、下一帧再跑 menuProgress】。同一帧挂载并从 0 开始动画，
 *    第一帧拿不到测量结果，读起来是"先闪一下再展开"。
 * 2. 下方放不下就翻到锚点上方（[AnchoredPickerPositionProvider]），并据此把
 *    `transformOrigin` 换到底边——否则向上弹出的菜单会从远离锚点的那头长出来。
 *
 * @param expanded 由调用方持有。菜单只负责在它变 false 之后把退场动画跑完再卸载自己。
 * @param anchorWidth 锚点宽度，菜单以它为最小宽度（调用方用 `BoxWithConstraints` 量）。
 */
@Composable
internal fun LiquidAnchoredOptionMenu(
    expanded: Boolean,
    options: List<LiquidPickerOption>,
    selectedIndex: Int?,
    anchorWidth: Dp,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    backdrop: Backdrop? = LocalControlBackdrop.current,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val accessibility = rememberGlassAccessibilityMode()
    // 选中行的底色要与字段表面同源，所以这里也解析一次 Control 档
    val fieldMaterial = GlassMaterials.resolve(
        role = GlassMaterialRole.Control,
        accessibility = accessibility
    )
    val menuMaterial = GlassMaterials.resolve(
        role = GlassMaterialRole.Modal,
        accessibility = accessibility
    )
    val glassBackdrop = backdrop?.takeIf { isBackdropSupported() }
    val menuShape = RoundedCornerShape(20.dp)
    val menuSurfaceColor = MaterialTheme.colorScheme.surface.copy(
        alpha = menuMaterial.surfaceAlpha
    )
    val validSelectedIndex = selectedIndex?.takeIf { it in options.indices }
    val density = LocalDensity.current
    val overlayBottomInset = LocalAppOverlayBottomInset.current
    val gapPx = with(density) { 8.dp.roundToPx() }
    val overlayBottomInsetPx = with(density) { overlayBottomInset.roundToPx() }
    val popupPositionProvider = remember(gapPx, overlayBottomInsetPx) {
        AnchoredPickerPositionProvider(
            gapPx = gapPx,
            bottomInsetPx = overlayBottomInsetPx
        )
    }
    var popupVisible by remember { mutableStateOf(false) }
    val menuProgress = remember { Animatable(0f) }

    LaunchedEffect(expanded, accessibility.reduceMotion) {
        if (accessibility.reduceMotion) {
            popupVisible = expanded
            menuProgress.snapTo(if (expanded) 1f else 0f)
        } else if (expanded) {
            popupVisible = true
            withFrameNanos { }
            menuProgress.animateTo(1f, MotionSpring.liquidMenu())
        } else if (popupVisible) {
            menuProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 140,
                    easing = MotionEasing.Accelerate
                )
            )
            popupVisible = false
        }
    }

    if (popupVisible) {
        Popup(
            popupPositionProvider = popupPositionProvider,
            onDismissRequest = onDismiss,
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            val menuFallbackModifier = Modifier
                .shadow(12.dp, menuShape, clip = false)
                .clip(menuShape)
                .background(
                    MaterialTheme.colorScheme.surface.copy(
                        alpha = if (accessibility.highContrast) 1f else 0.98f
                    )
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(
                        alpha = menuMaterial.borderAlpha
                    ),
                    menuShape
                )
            val menuGlassModifier = if (glassBackdrop != null) {
                Modifier.drawBackdrop(
                    backdrop = glassBackdrop,
                    shape = { menuShape },
                    effects = {
                        val params = resolvePhysicalLens(
                            density = this,
                            material = menuMaterial,
                            shape = menuShape,
                            minCornerRadiusPx = 20.dp.toPx(),
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
                    shadow = { Shadow(alpha = menuMaterial.shadowAlpha) },
                    onDrawSurface = {
                        drawRect(menuSurfaceColor)
                        drawRoundRect(
                            color = Color.White.copy(
                                alpha = menuMaterial.borderAlpha
                            ),
                            cornerRadius = CornerRadius(20.dp.toPx()),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                        )
                    }
                )
            } else {
                menuFallbackModifier
            }

            Column(
                modifier = Modifier
                    // 【必须是 width，不能是 widthIn】菜单里每一行都是 fillMaxWidth，
                    // 所以 widthIn(min = anchorWidth, max = 420.dp) 会让它们撑到 max 那一档，
                    // 菜单于是比字段宽出一截；再被 AnchoredPickerPositionProvider 的
                    // `x.coerceIn(0, windowWidth - popupWidth)` 夹到 0，就成了一条贴着
                    // 屏幕左右边的白板（登录页学校选择器上肉眼可见）。
                    // 锚点同宽本来就是这个菜单的设计意图——它是"从字段下缘长出来的那一段"。
                    .width(anchorWidth)
                    .then(menuGlassModifier)
                    .graphicsLayer {
                        val progress = menuProgress.value.coerceIn(0f, 1f)
                        val opensAbove = popupPositionProvider.opensAbove
                        alpha = progress
                        scaleX = lerp(0.97f, 1f, progress)
                        scaleY = lerp(0.96f, 1f, progress)
                        transformOrigin = TransformOrigin(
                            pivotFractionX = 0.5f,
                            pivotFractionY = if (opensAbove) 1f else 0f
                        )
                        translationY = (1f - progress) *
                            if (opensAbove) 6.dp.toPx() else -6.dp.toPx()
                    }
                    .padding(6.dp)
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                options.forEachIndexed { index, option ->
                    val isSelected = index == validSelectedIndex
                    val itemInteractionSource = remember(index) { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isSelected) {
                                    Color.White.copy(
                                        alpha = (fieldMaterial.surfaceAlpha * 0.6f)
                                            .coerceIn(0.12f, 0.28f)
                                    )
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable(
                                interactionSource = itemInteractionSource,
                                indication = null,
                                enabled = option.enabled,
                                role = Role.RadioButton,
                                onClick = {
                                    onDismiss()
                                    onSelect(index)
                                }
                            )
                            .semantics { selected = isSelected }
                            .graphicsLayer { alpha = if (option.enabled) 1f else 0.38f }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(22.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        Text(
                            text = option.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (!actionLabel.isNullOrBlank() && onAction != null) {
                    if (options.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                .height(1.dp)
                                .background(
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)
                                )
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = {
                                    onDismiss()
                                    onAction()
                                }
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(32.dp))
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

private class AnchoredPickerPositionProvider(
    private val gapPx: Int,
    private val bottomInsetPx: Int
) : PopupPositionProvider {
    var opensAbove: Boolean = false
        private set

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = anchorBounds.left.coerceIn(0, maxX)
        val safeBottom = (windowSize.height - bottomInsetPx).coerceAtLeast(0)
        val belowY = anchorBounds.bottom + gapPx
        val aboveY = anchorBounds.top - gapPx - popupContentSize.height
        val fitsBelow = belowY + popupContentSize.height <= safeBottom
        val fitsAbove = aboveY >= 0
        opensAbove = !fitsBelow && fitsAbove
        val maxY = (safeBottom - popupContentSize.height).coerceAtLeast(0)
        val y = when {
            opensAbove -> aboveY
            fitsBelow -> belowY
            else -> belowY.coerceIn(0, maxY)
        }
        return IntOffset(x, y.coerceIn(0, maxY))
    }
}

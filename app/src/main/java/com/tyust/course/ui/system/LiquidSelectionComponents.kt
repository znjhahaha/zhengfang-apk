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
import com.kyant.backdrop.shadow.Shadow
import com.tyust.course.ui.system.glass.DampedDragAnimation
import com.tyust.course.ui.system.glass.InteractiveHighlight
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
    backdrop: Backdrop? = LocalAppBackdrop.current,
    height: Dp = 52.dp
) {
    if (options.isEmpty()) return

    val optionCount = options.size
    val clampedSelectedIndex = selectedIndex.coerceIn(0, optionCount - 1)
    val glassBackdrop = backdrop?.takeIf { isBackdropSupported() }
    val useGlass = glassBackdrop != null
    // 真 lens（API33+）折射色散；API31/32 固定 blur 毛玻璃
    val hasRealLens = isRuntimeShaderTrulySupported()
    val isLightTheme = !isSystemInDarkTheme()
    val trackShape = RoundedCornerShape(percent = 50)
    val indicatorShape = RoundedCornerShape(percent = 50)
    val trackBackdrop = if (useGlass) rememberLayerBackdrop() else null
    val indicatorBackdrop = if (glassBackdrop != null && trackBackdrop != null) {
        rememberCombinedBackdrop(glassBackdrop, trackBackdrop)
    } else {
        null
    }
    val animationScope = rememberCoroutineScope()
    val accessibility = rememberGlassAccessibilityMode()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val latestSelectedIndex by rememberUpdatedState(clampedSelectedIndex)
    val latestOnSelect by rememberUpdatedState(onSelect)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.46f
                clip = false
            }
    ) {
        val density = LocalDensity.current
        val horizontalPadding = 5.dp
        val verticalPadding = 5.dp
        val horizontalPaddingPx = with(density) { horizontalPadding.toPx() }
        val verticalPaddingPx = with(density) { verticalPadding.toPx() }
        val segmentWidthPx =
            ((constraints.maxWidth - horizontalPaddingPx * 2f) / optionCount)
                .coerceAtLeast(1f)
        val segmentWidth = with(density) { segmentWidthPx.toDp() }
        val indicatorMinDimensionPx = minOf(
            segmentWidthPx,
            (constraints.maxHeight - verticalPaddingPx * 2f).coerceAtLeast(1f)
        )
        val dragAnimation = remember(animationScope, optionCount, segmentWidthPx) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = clampedSelectedIndex.toFloat(),
                valueRange = 0f..(optionCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = if (accessibility.reduceMotion) {
                    1f
                } else {
                    GlassRecipe.SegIndicatorPressedScale
                },
                directManipulationSpec = MotionSpring.liquidFollow(),
                settleAnimationSpec = MotionSpring.liquidSettle(),
                releaseScaleAnimationSpec = MotionSpring.liquidSelectionRelease(),
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

        // 轨道半透底：API32 对齐 cba2a09 轨；与底栏一致
        val trackBackgroundColor = if (hasRealLens) {
            if (isLightTheme) Color.White.copy(alpha = if (useGlass) 0.16f else 0.28f)
            else Color.Black.copy(alpha = if (useGlass) 0.18f else 0.28f)
        } else {
            if (isLightTheme) {
                Color(GlassRecipe.NavLegacyTrackSurfaceLight)
                    .copy(alpha = GlassRecipe.NavLegacyTrackSurfaceAlpha)
            } else {
                Color.Black.copy(alpha = GlassRecipe.NavLegacyTrackSurfaceAlpha)
            }
        }
        val trackBorderColor = MaterialTheme.colorScheme.onSurface.copy(
            alpha = if (useGlass) 0.10f else 0.14f
        )
        val compact = height <= 36.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(trackShape)
                .background(trackBackgroundColor)
                .drawBehind {
                    drawRoundRect(
                        color = trackBorderColor,
                        cornerRadius = CornerRadius(size.height / 2f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.75.dp.toPx())
                    )
                }
        )

        if (trackBackdrop != null) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(trackBackdrop)
                    .clip(trackShape)
                    .background(trackBackgroundColor)
                    .drawBehind {
                        drawRoundRect(
                            color = trackBorderColor,
                            cornerRadius = CornerRadius(size.height / 2f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 0.75.dp.toPx()
                            )
                        )
                    }
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
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
                                MaterialTheme.typography.labelSmall
                            } else {
                                MaterialTheme.typography.labelLarge
                            },
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = if (compact) TextOverflow.Clip else TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        val indicatorHeight = (height - verticalPadding * 2).coerceAtLeast(1.dp)
        val indicatorBaseModifier = Modifier
            .width(segmentWidth)
            .height(indicatorHeight)
            .align(Alignment.CenterStart)
            .graphicsLayer {
                translationX = horizontalPaddingPx + dragAnimation.value * segmentWidthPx
                clip = false
                if (!useGlass && !accessibility.reduceMotion) {
                    val velocity = dragAnimation.velocity / 6f
                    scaleX = dragAnimation.scaleX /
                        (1f - (velocity * 0.75f).coerceIn(-0.22f, 0.22f))
                    scaleY = dragAnimation.scaleY *
                        (1f - (velocity * 0.25f).coerceIn(-0.22f, 0.22f))
                }
            }

        if (indicatorBackdrop != null) {
            Box(
                modifier = indicatorBaseModifier.drawBackdrop(
                    backdrop = indicatorBackdrop,
                    shape = { indicatorShape },
                    effects = {
                        val press = dragAnimation.pressProgress
                        vibrancy()
                        if (hasRealLens) {
                            val activeMaterial = GlassMaterials.resolve(
                                role = GlassMaterialRole.Interactive,
                                accessibility = accessibility,
                                interactionProgress = press
                            )
                            val motion = motionIntensityFromVelocity(
                                velocityX = dragAnimation.velocity * segmentWidthPx,
                                fullEffectVelocity = activeMaterial.optics.velocityForFullEffect
                            )
                            val params = resolvePhysicalLens(
                                density = this,
                                material = activeMaterial,
                                shape = indicatorShape,
                                minCornerRadiusPx = indicatorMinDimensionPx / 2f,
                                minDimensionPx = indicatorMinDimensionPx,
                                interactionProgress = press,
                                motionIntensity = motion,
                                enableBlur = false,
                                allowChromaticAberration = true,
                                pressScalesRefraction = true,
                                refractionFloor = 0.55f
                            )
                            if (params.useLens) {
                                lens(
                                    refractionHeight = params.refractionHeightPx,
                                    refractionAmount = params.refractionAmountPx,
                                    chromaticAberration = params.chromaticAberration
                                )
                            }
                        } else {
                            // API31/32：固定 blur 毛玻璃滑块，对齐底栏
                            blur(GlassRecipe.NavLegacyIndicatorBlurDp.dp.toPx())
                        }
                    },
                    layerBlock = {
                        if (accessibility.reduceMotion) {
                            scaleX = 1f
                            scaleY = 1f
                        } else {
                            // 与底栏同一 velocity 公式
                            val velocity = dragAnimation.velocity / 6f
                            scaleX = dragAnimation.scaleX /
                                (1f - (velocity * 0.75f).coerceIn(-0.22f, 0.22f))
                            scaleY = dragAnimation.scaleY *
                                (1f - (velocity * 0.25f).coerceIn(-0.22f, 0.22f))
                        }
                    },
                    shadow = {
                        val shadowAlpha = lerp(
                            GlassRecipe.SegIndicatorShadowAlpha,
                            GlassRecipe.SegIndicatorPressedShadowAlpha,
                            dragAnimation.pressProgress
                        )
                        Shadow(alpha = shadowAlpha)
                    },
                    onDrawSurface = {
                        val press = dragAnimation.pressProgress
                        if (hasRealLens) {
                            val solidColor = if (isLightTheme) {
                                Color(GlassRecipe.NavSelectedSolidColorLight)
                            } else {
                                Color(GlassRecipe.NavSelectedSolidColorDark)
                            }
                            val fillAlpha = lerp(
                                GlassRecipe.NavSelectedSolidAlpha,
                                GlassRecipe.NavSelectedGlassAlpha,
                                press
                            )
                            if (fillAlpha > 0f) {
                                drawRect(solidColor.copy(alpha = fillAlpha))
                            }
                        } else {
                            // API32 cba2a09：Black×0.1
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
                }
                .then(
                    if (enabled) interactiveHighlight.gestureModifier else Modifier
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEachIndexed { index, label ->
                val selectionAmount =
                    (1f - abs(dragAnimation.value - index.toFloat())).coerceIn(0f, 1f)
                val textColor = lerpColor(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                    MaterialTheme.colorScheme.onSurface,
                    selectionAmount
                )
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
                            MaterialTheme.typography.labelSmall
                        } else {
                            MaterialTheme.typography.labelLarge
                        },
                        fontWeight = if (selectionAmount >= 0.55f) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Medium
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
    backdrop: Backdrop? = LocalAppBackdrop.current
) {
    var expanded by remember { mutableStateOf(false) }
    var popupVisible by remember { mutableStateOf(false) }
    val menuProgress = remember { Animatable(0f) }
    val validSelectedIndex = selectedIndex?.takeIf { it in options.indices }
    val selectedLabel = validSelectedIndex?.let { options[it].label }
    val hasAvailableOption = options.any { it.enabled }
    val canOpen = enabled && (hasAvailableOption || onAction != null)
    val accessibility = rememberGlassAccessibilityMode()
    val fieldMaterial = GlassMaterials.resolve(
        role = GlassMaterialRole.Control,
        accessibility = accessibility
    )
    val menuMaterial = GlassMaterials.resolve(
        role = GlassMaterialRole.Modal,
        accessibility = accessibility
    )
    val glassBackdrop = backdrop?.takeIf { isBackdropSupported() }
    val shape = RoundedCornerShape(18.dp)
    val menuShape = RoundedCornerShape(20.dp)
    val density = LocalDensity.current
    val overlayBottomInset = LocalAppOverlayBottomInset.current
    val gapPx = with(density) { 8.dp.roundToPx() }
    val overlayBottomInsetPx = with(density) { overlayBottomInset.roundToPx() }
    val menuSurfaceColor = MaterialTheme.colorScheme.surface.copy(
        alpha = menuMaterial.surfaceAlpha
    )
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

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

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val anchorWidth = with(density) { constraints.maxWidth.toDp() }
        val popupPositionProvider = remember(gapPx, overlayBottomInsetPx) {
            AnchoredPickerPositionProvider(
                gapPx = gapPx,
                bottomInsetPx = overlayBottomInsetPx
            )
        }
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
                    alpha = if (enabled) 1f else 0.52f
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Text(
                    text = selectedLabel ?: placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selectedLabel != null) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selectedLabel != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (popupVisible) {
            Popup(
                popupPositionProvider = popupPositionProvider,
                onDismissRequest = { expanded = false },
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
                        .widthIn(min = anchorWidth, max = 420.dp)
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
                                        expanded = false
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
                                        expanded = false
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
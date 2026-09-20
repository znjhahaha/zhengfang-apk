package com.tyust.course.ui.system

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.scale

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import com.tyust.course.ui.system.glass.GlassLensTransform
import com.tyust.course.ui.system.glass.DampedDragAnimation
import com.tyust.course.ui.system.glass.GlassLensAnchor
import com.tyust.course.ui.system.glass.LocalGlassLensAnchor
import com.tyust.course.ui.system.glass.LiquidPickerLayerPolicy
import com.tyust.course.ui.system.glass.LiquidPickerMotionPhysics
import com.tyust.course.ui.system.glass.RoundedRectMergeGeometry
import com.tyust.course.ui.system.glass.chromaticFringe
import com.tyust.course.ui.system.glass.glassChip
import com.tyust.course.ui.system.glass.glassLens
import com.tyust.course.ui.system.glass.glassLensAnchor
import com.tyust.course.ui.system.glass.glassLensOpticsFrom
import com.tyust.course.ui.system.glass.glassRim
import com.tyust.course.ui.system.glass.rememberGlassLensAnchor
import com.tyust.course.ui.system.glass.rememberGlassLensRegion
import com.tyust.course.ui.system.glass.GlassLensContentSnapshot
import com.tyust.course.ui.system.glass.LocalPageGlassFreshness
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

private val PickerHeaderHeight = 50.dp
private val PickerItemHeight = 40.dp
private val PickerItemGap = 3.dp
private val PickerBodyVerticalPadding = 6.dp
private val PickerBodyHorizontalPadding = 10.dp
private val PickerMaxBodyHeight = 240.dp
private val PickerCornerRadius = 20.dp
private val PickerExpandedGap = 12.dp
private val PickerOpeningOverlap = 16.dp
private val PickerClosingOverlap = 24.dp
private const val PickerItemStaggerSeconds = 0.018f
private val PickerItemEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
private const val PickerCircleBezier = 0.5522848f

/** Adds a clockwise rounded-rectangle contour without allocating an intermediate geometry. */
private fun Path.addPickerRoundedContour(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radius: Float
) {
    val width = (right - left).coerceAtLeast(0f)
    val height = (bottom - top).coerceAtLeast(0f)
    if (width <= 0.5f || height <= 0.5f) return

    val r = radius.coerceIn(0f, minOf(width, height) / 2f)
    val c = r * PickerCircleBezier
    moveTo(left + r, top)
    lineTo(right - r, top)
    cubicTo(right - r + c, top, right, top + r - c, right, top + r)
    lineTo(right, bottom - r)
    cubicTo(right, bottom - r + c, right - r + c, bottom, right - r, bottom)
    lineTo(left + r, bottom)
    cubicTo(left + r - c, bottom, left, bottom - r + c, left, bottom - r)
    lineTo(left, top + r)
    cubicTo(left, top + r - c, left + r - c, top, left + r, top)
    close()
}

/** Smooths sampled implicit-surface stations without inventing the underlying neck geometry. */
private fun Path.addPickerImplicitContour(points: List<Offset>) {
    if (points.size < 3) return
    fun midpoint(first: Offset, second: Offset) = Offset(
        x = (first.x + second.x) / 2f,
        y = (first.y + second.y) / 2f
    )

    val firstMidpoint = midpoint(points.last(), points.first())
    moveTo(firstMidpoint.x, firstMidpoint.y)
    points.forEachIndexed { index, point ->
        val next = points[(index + 1) % points.size]
        val nextMidpoint = midpoint(point, next)
        quadraticTo(point.x, point.y, nextMidpoint.x, nextMidpoint.y)
    }
    close()
}

private fun smoothStep(value: Float): Float {
    val x = value.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

@Stable
private class PickerMotionState(initialPosition: Float) {
    var isSettled by mutableStateOf(true)
        private set
    var travelPosition by mutableFloatStateOf(initialPosition)
        private set
    var travelVelocity by mutableFloatStateOf(0f)
        private set
    var extentPosition by mutableFloatStateOf(initialPosition)
        private set
    var extentVelocity by mutableFloatStateOf(0f)
        private set
    var phaseTimeSeconds by mutableFloatStateOf(0f)
        private set

    suspend fun animateTo(
        expanded: Boolean,
        reducedMotion: Boolean
    ) {
        isSettled = false
        phaseTimeSeconds = 0f
        val target = if (expanded) 1f else 0f
        if (reducedMotion) {
            snapTo(target)
            return
        }
        var state = LiquidPickerMotionPhysics.State(
            travel = LiquidPickerMotionPhysics.Axis(travelPosition, travelVelocity),
            extent = LiquidPickerMotionPhysics.Axis(extentPosition, extentVelocity)
        )
        if (LiquidPickerMotionPhysics.isAtRest(state, expanded)) {
            snapTo(target)
            return
        }

        var previousFrame = withFrameNanos { it }
        while (true) {
            val frame = withFrameNanos { it }
            val deltaSeconds = ((frame - previousFrame) / 1_000_000_000f)
                .coerceIn(0f, 0.1f)
            previousFrame = frame
            phaseTimeSeconds += deltaSeconds
            state = LiquidPickerMotionPhysics.step(
                state = state,
                expanded = expanded,
                deltaSeconds = deltaSeconds
            )
            travelPosition = state.travel.position
            travelVelocity = state.travel.velocity
            extentPosition = state.extent.position
            extentVelocity = state.extent.velocity

            if (LiquidPickerMotionPhysics.isAtRest(state, expanded)) {
                snapTo(target)
                return
            }
        }
    }

    private fun snapTo(target: Float) {
        travelPosition = target
        travelVelocity = 0f
        extentPosition = target
        extentVelocity = 0f
        isSettled = true
    }
}

/**
 * Real rounded-surface lens used under the picker union contour.
 *
 * The AGSL lens only accepts rounded-rectangular shapes, while the temporary SDF bridge is a
 * generic path. Header and body therefore refract the real wallpaper independently; a very thin
 * unified contour above them supplies the transient liquid neck without covering the refraction.
 */
@Composable
private fun PickerLensLayer(
    modifier: Modifier,
    backdrop: Backdrop,
    shape: Shape,
    cornerRadius: Dp,
    motionVelocity: Float,
    pressProgress: Float,
    enabled: Boolean,
    forceBlurFallback: Boolean = false
) {
    val accessibility = rememberGlassAccessibilityMode()
    val isLightTheme = LocalWallpaperAppearanceColors.current.usesDarkForeground
    val hasRealLens = isRuntimeLensEnabled() && !forceBlurFallback
    // API 31/32：自家 ES 2.0 折射。用 App 全局区域 —— 头部/主体采样的
    // `glassBackdrop` 就是 `LocalControlBackdrop`，在 App 根上等于那层壁纸，
    // 与全局底图同源。全屏且静态，展开动画改高度也不会触发重拍。
    //
    // `forceBlurFallback` 那一层要排除：它是 GenericShape 的合并轮廓，
    // 33+ 上也不折射（走 blur），而且自家着色器只有圆角矩形 SDF。
    val lensAnchor = if (forceBlurFallback) null else LocalGlassLensAnchor.current
    val hasLensLook = hasRealLens || lensAnchor != null
    val lensDensity = LocalDensity.current
    val resolvedMaterial = GlassMaterials.resolve(
        role = GlassMaterialRole.Interactive,
        accessibility = accessibility,
        interactionProgress = pressProgress
    )
    val material = if (hasLensLook) {
        resolvedMaterial
    } else {
        // 真的没有折射时（API ≤ 30）才用受控的 blur 顶替。
        // 判据是 hasLensLook：31/32 现在有折射了，再补 blur 就是把它糊掉，
        // 而且底图是无 blur 的原 backdrop，屏幕上多出来的模糊会让折射内容
        // 与周围对不上。
        resolvedMaterial.copy(blurDp = GlassRecipe.PickerBlurDp)
    }
    val motionIntensity = (abs(motionVelocity) / 9f).coerceIn(0f, 1f)
    val enabledScale = if (enabled) 1f else GlassRecipe.ChipDisabledSurfaceScale
    val surfaceAlpha = material.surfaceAlpha * enabledScale *
        if (isLightTheme) 1f else 0.72f

    Box(
        modifier = modifier
            // 必须在 drawBackdrop **上游**：它下面那层会把背景原样再画一遍。
            .glassLens(
                lensAnchor,
                optics = { w, h ->
                    glassLensOpticsFrom(
                        material = material,
                        density = lensDensity,
                        // 与下面 resolvePhysicalLens 的 minCornerRadiusPx 同一条算式
                        cornerRadiusPx = with(lensDensity) {
                            minOf(cornerRadius.toPx(), minOf(w, h) / 2f)
                                .coerceAtLeast(0.5f)
                        },
                        minDimensionPx = minOf(w, h).coerceAtLeast(1f),
                        interactionProgress = pressProgress,
                        motionIntensity = motionIntensity,
                        // 与下面同值：静止就是透明玻璃，按压/速度只是加强
                        pressScalesRefraction = false,
                        chromaticAberrationAtRest = false
                    )
                }
            )
            .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            // Each persistent lens needs its own optical edge. Only the temporary generic
            // bridge omits it; that surface already has the SDF rim below.
            highlight = { if (forceBlurFallback) null else Highlight.Default },
            shadow = { if (forceBlurFallback) null else Shadow(alpha = if (isLightTheme) 0.14f else 0.22f) },
            innerShadow = null,
            effects = {
                val effectiveCornerRadius = minOf(
                    cornerRadius.toPx(),
                    size.minDimension / 2f
                ).coerceAtLeast(0.5f)
                val params = resolvePhysicalLens(
                    scope = this,
                    material = material,
                    minCornerRadiusPx = effectiveCornerRadius,
                    minDimensionPx = size.minDimension.coerceAtLeast(1f),
                    interactionProgress = pressProgress,
                    motionIntensity = motionIntensity,
                    enableBlur = !hasLensLook,
                    allowChromaticAberration = !accessibility.reduceMotion,
                    chromaticAberrationAtRest = false,
                    // The selector is transparent glass even at rest; press/motion only intensify it.
                    pressScalesRefraction = false
                )
                vibrancy()
                if (params.blurPx > 0f) blur(params.blurPx)
                if (params.useLens) {
                    lens(
                        refractionHeight = params.refractionHeightPx,
                        refractionAmount = params.refractionAmountPx,
                        chromaticAberration = params.chromaticAberration
                    )
                } else if (params.fringePx > 0f && lensAnchor == null) {
                    // 只有真的没有折射时才用假色散近似（API ≤ 30）。
                    // 31/32 上折射与七波长色散已由上面的 glassLens 画完。
                    chromaticFringe(params.fringePx)
                }
            },
            onDrawBackdrop = { drawBackdrop ->
                // 31/32 上背景已由 glassLens 以折射方式画过，不能再画一遍
                if (lensAnchor == null) drawBackdrop()
            },
            onDrawSurface = {
                drawRect(Color.White.copy(alpha = surfaceAlpha))
            }
        )
    )
}

@Composable
fun LiquidSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop? = LocalControlBackdrop.current,
    height: Dp = 52.dp,
    edgePadding: Dp? = null,
    verticalInset: Dp? = null,
    restingRefraction: Float? = null,
    showTrack: Boolean = true,
    labelContent: (@Composable (index: Int, selection: Float, color: Color) -> Unit)? = null
) {
    if (options.isEmpty()) return

    val optionCount = options.size
    val clampedSelectedIndex = selectedIndex.coerceIn(0, optionCount - 1)
    val glassBackdrop = backdrop?.takeIf { isBackdropSupported() }
    val useGlass = glassBackdrop != null
    // API 33+ uses a runtime lens; API 31/32 refracts the background asynchronously.
    val hasRealLens = isRuntimeLensEnabled()
    val isLightTheme = LocalWallpaperAppearanceColors.current.usesDarkForeground
    val trackShape = RoundedCornerShape(percent = 50)
    val indicatorShape = RoundedCornerShape(percent = 50)
    // Keep labels in the optical source on both paths. API 31/32 refreshes this
    // small overlay independently from the more expensive page background.
    val segmentLabelsSnapshot = remember { GlassLensContentSnapshot() }
    val segmentsBackdrop = rememberLayerBackdrop(onDraw = { segmentLabelsSnapshot.draw(this) })
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
    // Reduced motion is resolved by the retained animation state, so re-enabling it
    // restores the normal spring without rebuilding the current drag position.
    val pressedScale = GlassRecipe.SegIndicatorPressedScale
    val latestSelectedIndex by rememberUpdatedState(clampedSelectedIndex)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val haptics = LocalHapticFeedback.current

    // 折射锚点在 BoxWithConstraints 内部创建（要用到 constraints 算出的尺寸），
    // 但必须挂在这一层 —— 所以用一个 State 把它带出来，下一帧生效。
    // 差一帧无所谓：底图本来就是"上一帧的结果"（见 GlassLens.kt 的说明）。
    var segLensAnchor by remember { mutableStateOf<GlassLensAnchor?>(null) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                clip = false
            }
            .glassLensAnchor(segLensAnchor)
    ) {
        val density = LocalDensity.current
        val compact = height <= 36.dp
        val horizontalPadding = edgePadding ?: if (compact) 3.dp else 5.dp
        val verticalPadding = verticalInset ?: if (compact) 3.dp else 5.dp
        val refractionFloor = restingRefraction?.coerceIn(0f, 1f) ?: if (compact) 0.30f else 0.42f
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
                settleAnimationSpec = MotionSpring.segmentedSettle(),
                releaseScaleAnimationSpec = MotionSpring.segmentedRelease(),
                pressScaleAnimationSpec = spring(dampingRatio = 0.64f, stiffness = 680f),
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> }
            )
        }

        LaunchedEffect(accessibility.reduceMotion, dragAnimation) {
            dragAnimation.setReducedMotion(accessibility.reduceMotion, latestSelectedIndex.toFloat())
        }

        val requestSelection: (Int) -> Unit = { requestedIndex ->
            if (enabled) {
                val targetIndex = requestedIndex.coerceIn(0, optionCount - 1)
                if (targetIndex != latestSelectedIndex) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
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

        // API 31/32：平台没有 AGSL，改用离屏 ES 2.0 做真折射（见 GlassLens.kt）。
        // 结构与 CapsuleNavigationBar 一致：锚点挂在**不随滑块移动**的外层，
        // 底图上传一次，滑块滑动时只改采样窗口。
        // The background stays sharp; the separate label overlay keeps real text
        // refraction without rereading the page whenever a glyph changes.
        val lensDensity = LocalDensity.current
        val lensAnchor = if (glassBackdrop != null) {
            // 标签带上选项文字：屏幕上同时有多个分段控件，且尺寸可能相同
            // （登录页的「密码登录/Cookie登录」与课程页的「可选/已选」都是 381x126），
            // 只按尺寸命名会互相覆盖。
            rememberGlassLensRegion("seg-" + options.joinToString("_"), isLightTheme,
                freshness = LocalPageGlassFreshness.current,
                rasterizeOverlayOnCpu = true,
                overlaySource = { coords ->
                    segmentLabelsSnapshot.draw(this, coords)
                }) { coords ->
                with(glassBackdrop) { drawBackdrop(lensDensity, coords, null) }
            }
        } else {
            null
        }
        // 把锚点交给外层的 glassLensAnchor（它需要外层那块不动的坐标）
        SideEffect { segLensAnchor = lensAnchor }
        // The region refreshes both after selection and after the underlying page settles.

        // One visible label row owns gestures and semantics on every API level.
        val segmentLabels: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup()
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)
                .pointerInput(enabled, optionCount, segmentWidthPx) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        down.consume()
                        val startValue = dragAnimation.value
                        val touchSlop = viewConfiguration.touchSlop
                        val tappedIndex =
                            (down.position.x / segmentWidthPx)
                                .toInt()
                                .coerceIn(0, optionCount - 1)
                        var totalDragX = 0f
                        var dragging = false
                        var completed = false
                        var pointerId = down.id

                        dragAnimation.press(down.uptimeMillis)

                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                    ?: break
                                if (event.changes.count { it.pressed } > 1) {
                                    event.changes.forEach { it.consume() }
                                    break
                                }
                                if (change.isConsumed) break
                                if (change.changedToUpIgnoreConsumed()) {
                                    completed = true
                                    change.consume()
                                    break
                                }
                                if (!dragging && abs(change.position.y - down.position.y) > touchSlop && abs(totalDragX) <= touchSlop) break

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
                                                .coerceIn(0f, (optionCount - 1).toFloat()),
                                            change.uptimeMillis
                                        )
                                    }
                                }
                                pointerId = change.id
                            }
                        } finally {
                            when {
                                !completed -> dragAnimation.animateToValue(latestSelectedIndex.toFloat())
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
                        MaterialTheme.colorScheme.onSurfaceVariant,
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
                        .focusable(enabled)
                        .onKeyEvent { event ->
                            if (enabled && event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                                requestSelection(index)
                                true
                            } else {
                                false
                            }
                        }
                        .selectable(
                            selected = index == clampedSelectedIndex,
                            enabled = enabled,
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { requestSelection(index) }
                        )
                        .then(if (labelContent != null) Modifier.semantics { contentDescription = label } else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    if (labelContent != null) labelContent(index, selectionAmount, textColor)
                    else Text(
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

        val trackTransform: GraphicsLayerScope.() -> Unit = {
            val maxGain = 16.dp.toPx()
            val pressScale = lerp(1f, 1f + maxGain / size.width, dragAnimation.pressProgress)
            val indicatorBoost = ((dragAnimation.scaleX + dragAnimation.scaleY) / 2f - 1f)
                .coerceIn(0f, 0.4f)
            val scale = pressScale * (1f + indicatorBoost * 0.18f)
            scaleX = scale
            scaleY = scale
        }
        val fallbackIndicatorColor = MaterialTheme.colorScheme.surface
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (!showTrack && glassBackdrop != null) {
                        Modifier
                    } else if (glassBackdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = glassBackdrop,
                            shape = { Capsule() },
                            effects = {
                                vibrancy()
                                if (hasRealLens) {
                                    val params = resolvePhysicalLens(
                                        scope = this,
                                        material = trackMaterial,
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
                            layerBlock = trackTransform,
                            onDrawSurface = { drawRect(trackBackgroundColor) }
                        )
                    } else {
                        (if (showTrack) Modifier
                            .clip(trackShape)
                            .background(trackBackgroundColor)
                            .border(0.75.dp, trackBorderColor, trackShape) else Modifier)
                            .drawBehind {
                                val transform = segIndicatorScale(dragAnimation)
                                val left = horizontalPaddingPx + dragAnimation.value * segmentWidthPx
                                val top = verticalPadding.toPx()
                                val w = segmentWidthPx
                                val h = indicatorHeight.toPx()
                                scale(transform.scaleX, transform.scaleY, Offset(left + w / 2f, top + h / 2f)) {
                                    drawRoundRect(fallbackIndicatorColor, Offset(left, top),
                                        androidx.compose.ui.geometry.Size(w, h),
                                        androidx.compose.ui.geometry.CornerRadius(h / 2f))
                                }
                            }
                    }
                )
        ) {
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
            val labelTint = ColorFilter.tint(if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            Row(
                modifier = Modifier
                    .clearAndSetSemantics { }
                    .layerBackdrop(segmentsBackdrop)
                    .align(Alignment.CenterStart)
                    .height(indicatorHeight)
                    .fillMaxWidth()
                    .onGloballyPositioned { segmentLabelsSnapshot.coordinates = it }
                    .drawWithContent {
                        // Async readback must own the glyph commands, not mutable child layers
                        // that page transitions can discard before the capture is rasterized.
                        segmentLabelsSnapshot.record(this, labelTint)
                        lensAnchor?.invalidateOverlay()
                    }
                    .padding(horizontal = horizontalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                options.forEachIndexed { index, label ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (labelContent != null) labelContent(index, 1f, MaterialTheme.colorScheme.primary)
                        else Text(
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
                modifier = indicatorBaseModifier
                    // API31/32 真折射：画在 drawBackdrop 之前，所以它提供背景，
                    // 库那层的 surface / highlight / shadow 仍叠在上面。
                    .glassLens(
                        anchor = lensAnchor,
                        // lambda 而非现成值：pressProgress / velocity 是 snapshot
                        // state，组合期读会让整个控件按压时每帧重组。
                        // w/h 是**实测**尺寸，由 glassLens 在 draw 里给。
                        optics = { w, h ->
                            val minDim = minOf(w, h)
                            glassLensOpticsFrom(
                                material = indicatorMaterial,
                                density = density,
                                cornerRadiusPx = minDim / 2f,
                                minDimensionPx = minDim,
                                interactionProgress = dragAnimation.pressProgress,
                                motionIntensity = motionIntensityFromVelocity(
                                    velocityX = dragAnimation.velocity * segmentWidthPx,
                                    fullEffectVelocity =
                                        indicatorMaterial.optics.velocityForFullEffect
                                ),
                                pressScalesRefraction = true,
                                // Dense multi-line labels can opt out of resting distortion.
                                // Both rendering paths retain the same press/motion optics.
                                refractionFloor = refractionFloor,
                                chromaticAberrationAtRest = refractionFloor > 0f
                            )
                        },
                        // 与下面 layerBlock **同一份**形变。不传的话按下时库的
                        // highlight 环按放大后的轮廓画，而折射还是原尺寸，
                        // 屏幕上是一圈白环浮在玻璃外面。
                        scale = { _, _ -> segIndicatorScale(dragAnimation) }
                    )
                    .drawBackdrop(
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
                                scope = this,
                                material = indicatorMaterial,
                                minCornerRadiusPx = size.minDimension / 2f,
                                minDimensionPx = size.minDimension,
                                interactionProgress = press,
                                motionIntensity = motion,
                                enableBlur = false,
                                allowChromaticAberration = true,
                                chromaticAberrationAtRest = refractionFloor > 0f,
                                pressScalesRefraction = true,
                                refractionFloor = refractionFloor
                            )
                            if (params.useLens) {
                                lens(
                                    refractionHeight = params.refractionHeightPx,
                                    refractionAmount = params.refractionAmountPx,
                                    chromaticAberration = params.chromaticAberration
                                )
                            }
                        } else if (lensAnchor == null) {
                            // 既无 AGSL 也无离屏折射（API ≤ 30）：只剩 RGB 分离近似。
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
                        // lensAnchor != null（API31/32）：折射与七波长色散都已由
                        // glassLens 在这之前画完。这里**不能**再叠 chromaticFringe ——
                        // 那是两套不同的边缘着色互相污染，屏幕上就是那圈蓝紫边。
                    },
                    highlight = {
                        val progress = dragAnimation.pressProgress
                        val scaleComp = (
                            (dragAnimation.scaleX + dragAnimation.scaleY) / 2f
                            ).coerceAtLeast(1f)
                        // 有真折射时用同一份轮廓光配方（含 blurRadius 补偿）：
                        // 参照图里滑块边缘有一圈明显的亮边，离屏折射这条路原本走的是
                        // 下面那条没有 blurRadius 的老路，边缘偏硬。
                        if (hasRealLens || lensAnchor != null) {
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
                        // 与上面 glassLens 的 scale 读同一个函数，两者不可能再脱钩
                        val segT = segIndicatorScale(dragAnimation)
                        val sx = segT.scaleX
                        val sy = segT.scaleY
                        scaleX = sx
                        scaleY = sy
                    },
                    onDrawBackdrop = { drawBackdrop ->
                        // API31/32 上背景已由 glassLens 以折射方式画过，不能再画一遍
                        if (lensAnchor == null) drawBackdrop()
                    },
                    onDrawSurface = {
                        val press = dragAnimation.pressProgress
                        // 有真折射（AGSL 或离屏 GL）就走同一份**提亮**填充。
                        //
                        // 下面那条 `Black×0.1` 的老路是"没有折射时靠压暗制造选中感"
                        // 留下的。现在 31/32 也有真折射了，继续叠黑的后果是：折射把
                        // 锐利壁纸带进来、再被这层黑压一遍，滑块整体发灰发褐，与粉色
                        // 轨道明显割裂（已在设备上截图确认）。
                        // 已在 API 35 上截库同一个控件对照：它是白色 0.18 的提亮，
                        // 滑块比轨道更亮更粉，而不是更暗。
                        if (hasRealLens || lensAnchor != null) {
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
        }

    }
}

/**
 * 分段控件滑块的按压 + 速度形变。
 *
 * 单独抽出来是因为它有**两个**消费者：库那层 `drawBackdrop(layerBlock)`，
 * 以及 API31/32 的 `glassLens(scale)`。两边必须逐帧一致 —— 不一致时按下会看到
 * 一圈白环（库画的 highlight）浮在玻璃（自家折射）外面。
 */
private fun segIndicatorScale(anim: DampedDragAnimation): GlassLensTransform {
    var sx = anim.scaleX
    var sy = anim.scaleY
    // The position spring already owns physical velocity. A second slow velocity filter
    // erased short taps before their deformation could become visible.
    val stretch = (abs(anim.positionVelocity) / 8f).coerceIn(0f, 1f) * GlassRecipe.SegIndicatorMaxVelocityStretch
    sx *= 1f + stretch
    sy /= 1f + stretch * 0.72f
    // 平移为 0：滑块的 translationX 在**外层** graphicsLayer 上，
    // glassLens 在它内部，已经跟着走了。见 GlassLensTransform。
    return GlassLensTransform(scaleX = sx, scaleY = sy)
}

/**
 * Fluid dropdown used by every Compose selector in the app.
 *
 * The stable expanded state is deliberately two objects: a pill trigger and a separate squircle
 * menu with a clear gap. During opening and closing, the menu briefly overlaps the trigger and a
 * both side walls pinch inward into a temporary hourglass bridge, creating a liquid
 * collision/absorption beat without keeping both surfaces permanently glued together.
 */
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
    backdrop: Backdrop? = LocalControlBackdrop.current,
    maxLabelLines: Int = 1
) {
    var expanded by remember { mutableStateOf(false) }
    var actionPending by remember { mutableStateOf(false) }
    val latestAction by rememberUpdatedState(onAction)
    var portalBodySpace by remember { mutableStateOf(PickerMaxBodyHeight) }
    var portalOpensUp by remember { mutableStateOf(false) }
    var highlightedRow by remember { mutableStateOf<Int?>(null) }
    val regionState = rememberWallpaperRegionState()
    val appearance = rememberWallpaperRegionAppearance(regionState)
    val validSelectedIndex = selectedIndex?.takeIf { it in options.indices }
    val selectedLabel = validSelectedIndex?.let { options[it].label }
    val haptics = LocalHapticFeedback.current
    val hasAction = !actionLabel.isNullOrBlank() && onAction != null
    val rowCount = options.size + if (hasAction) 1 else 0
    val hasAvailableOption = options.any { it.enabled }
    val canOpen = enabled && (hasAvailableOption || hasAction)
    val accessibility = rememberGlassAccessibilityMode()
    val reduceMotion = accessibility.reduceMotion
    val isLightTheme = appearance.usesDarkForeground
    // Keep the backdrop contract but avoid animated backdrop sampling. Both settled surfaces use
    // the cheap captured-content glass recipe, and the transient bridge is a single cached path.
    val glassBackdrop = backdrop?.takeIf { isBackdropSupported() }
    val backdropToneAvailable = glassBackdrop != null
    val headerInteraction = remember { MutableInteractionSource() }
    val headerPressed by headerInteraction.collectIsPressedAsState()
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val labelLines = maxLabelLines.coerceIn(1, 3)
    val headerHeight = maxOf(PickerHeaderHeight,
        with(density) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() } * labelLines.toFloat() + 16.dp)
    val itemHeight = maxOf(PickerItemHeight, 48.dp,
        with(density) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() } * labelLines.toFloat() + 16.dp)

    val bodyContentHeight = if (rowCount == 0) {
        0.dp
    } else {
        PickerBodyVerticalPadding * 2f +
            itemHeight * rowCount.toFloat() +
            PickerItemGap * (rowCount - 1).toFloat()
    }
    val bodyHeight = minOf(bodyContentHeight, PickerMaxBodyHeight, portalBodySpace)
    val motion = remember { PickerMotionState(initialPosition = 0f) }
    LaunchedEffect(expanded, reduceMotion) {
        motion.animateTo(
            expanded = expanded,
            reducedMotion = reduceMotion
        )
    }
    val motionProgress = motion.travelPosition
    val motionVelocity = motion.travelVelocity
    val extentProgress = motion.extentPosition
    val extentVelocity = motion.extentVelocity
    val motionTimeSeconds = motion.phaseTimeSeconds
    val headerScale by animateFloatAsState(
        targetValue = if (!reduceMotion && headerPressed && canOpen) 0.985f else 1f,
        animationSpec = MotionSpring.liquidTap(),
        label = "morphPickerHeaderScale"
    )
    val settledProgress = motionProgress.coerceIn(0f, 1f)
    val heightProgress = extentProgress.coerceIn(0f, 1.09f)
    // Keep row composition warm while collapsed. Creating the list and its semantics on the first
    // open frame caused MuMu to miss the small-bud frames and made continuous geometry look like a
    // pop-in. The subtree stays clipped and undrawn until the physical body leaves the header.
    val bodyPrecomposed = rowCount > 0
    val bodyActive = rowCount > 0 && (
        expanded || !motion.isSettled || maxOf(abs(motionProgress), abs(extentProgress)) > 0.005f
    )
    val arrowRotation = 180f * smoothStep(settledProgress)

    // The spring position is also the body's physical travel coordinate. There is no authored
    // detach keyframe: as the rounded boxes move from overlap to a 12dp gap, the SDF neck
    // exists only while the smooth-min field is still one connected zero-level set.
    val bodyTravelProgress = motionProgress.coerceIn(-0.08f, 1.12f)
    val bodyOverlap = if (expanded) PickerOpeningOverlap else PickerClosingOverlap
    val bodyOffset = headerHeight - bodyOverlap +
        (bodyOverlap + PickerExpandedGap) * bodyTravelProgress
    val visualBodyHeight = bodyHeight * heightProgress
    val actualBodyBottom = bodyOffset + visualBodyHeight
    val layoutHeight = maxOf(
        headerHeight,
        actualBodyBottom
    )
    // The surface itself never fades. It begins fully inside the header, grows out as one mass,
    // develops an SDF neck, then separates. Only the final sub-pixel body is hidden.
    val bodyAlpha = if (bodyActive && visualBodyHeight >= 1.dp) 1f else 0f
    val bodyExtendsBeyondHeader = LiquidPickerLayerPolicy.bodyExtendsBeyondHeader(
        bodyActive = bodyActive,
        actualBodyBottom = actualBodyBottom.value,
        headerBottom = headerHeight.value,
        threshold = 0.5f
    )
    val bodySeparated = bodyExtendsBeyondHeader && bodyOffset >= headerHeight + 0.5.dp
    val layerPolicy = LiquidPickerLayerPolicy.resolve(
        bodyActive = bodyActive,
        bodyExtendsBeyondHeader = bodyExtendsBeyondHeader,
        bodySeparated = bodySeparated,
        bodyAlpha = bodyAlpha,
        interactionProgress = if (headerPressed && canOpen) 1f else 0f
    )
    val mergedMotionVelocity = if (abs(extentVelocity) > abs(motionVelocity)) {
        extentVelocity
    } else {
        motionVelocity
    }
    val collisionPenetration = (-motionProgress).coerceIn(0f, 0.13f)
    val collisionScaleX = 1f + collisionPenetration * 0.20f
    val collisionScaleY = 1f - collisionPenetration * 0.32f
    val mergeSmoothnessDp = 9.5f + minOf(
        maxOf(abs(motionVelocity), abs(extentVelocity)) * 0.45f,
        3.5f
    )
    val descriptorPresent = leadingIcon != null || !label.isNullOrBlank()
    val contentEnabled = enabled && (hasAvailableOption || hasAction)
    val primaryContentColor = if (contentEnabled) {
        appearance.onSurface
    } else {
        appearance.onSurface.copy(alpha = 0.38f)
    }
    val secondaryContentColor = if (contentEnabled) {
        appearance.onSurfaceVariant
    } else {
        appearance.onSurface.copy(alpha = 0.30f)
    }
    val highlightColor = if (isLightTheme) {
        Color.White.copy(alpha = if (backdropToneAvailable) 0.42f else 0.34f)
    } else {
        Color.White.copy(alpha = 0.11f)
    }
    val highlightedOffset = animateDpAsState(
        targetValue = highlightedRow
            ?.takeIf { it in 0 until rowCount }
            ?.let { index ->
                PickerBodyVerticalPadding +
                    (itemHeight + PickerItemGap) * index.toFloat()
            }
            ?: PickerBodyVerticalPadding,
        animationSpec = if (reduceMotion) {
            tween(durationMillis = 0)
        } else {
            spring(dampingRatio = 0.82f, stiffness = 500f)
        },
        label = "morphPickerHighlightOffset"
    )
    val highlightedAlpha = if (highlightedRow == null) {
        0f
    } else if (expanded) {
        smoothStep((motionTimeSeconds - 0.34f) / 0.14f)
    } else {
        smoothStep((heightProgress - 0.02f) / 0.12f)
    }
    fun rowRevealProgress(index: Int): Float {
        if (reduceMotion) return if (expanded) 1f else 0f
        return if (expanded) {
            val timed = smoothStep(
                (motionTimeSeconds - 0.22f - index * PickerItemStaggerSeconds) / 0.20f
            )
            val containerReady = smoothStep((settledProgress - 0.50f) / 0.34f)
            minOf(timed, containerReady)
        } else {
            // Closing is driven by geometry, not a reverse authored cascade. The body clip removes
            // rows from bottom to top while the remaining content stays legible until absorption.
            smoothStep((heightProgress - 0.015f) / 0.11f)
        }
    }

    LaunchedEffect(canOpen) {
        if (!canOpen) {
            expanded = false
            actionPending = false
        }
    }
    LaunchedEffect(expanded, validSelectedIndex, bodyHeight) {
        if (expanded) {
            highlightedRow = validSelectedIndex
            if (validSelectedIndex != null && bodyHeight > 0.dp) {
                withFrameNanos { }
                val rowTop = with(density) {
                    (PickerBodyVerticalPadding +
                        (itemHeight + PickerItemGap) * validSelectedIndex.toFloat())
                        .roundToPx()
                }
                val viewport = with(density) { bodyHeight.roundToPx() }
                val itemHeight = with(density) { itemHeight.roundToPx() }
                val centered = rowTop - (viewport - itemHeight) / 2
                scrollState.scrollTo(centered.coerceIn(0, scrollState.maxValue))
            }
        }
    }
    BackHandler(enabled = expanded) { expanded = false }

    val headerShape = remember { Capsule() }
    val bodyShape = remember {
        RoundedRectangle(
            cornerRadius = PickerCornerRadius,
            style = RoundedCornerStyle.Continuous
        )
    }
    val resolvedLayoutHeight = layoutHeight.coerceAtLeast(48.dp)
    val headerOffset = if (portalOpensUp && bodyActive) resolvedLayoutHeight - headerHeight else 0.dp
    val renderedBodyOffset = if (portalOpensUp && bodyActive) resolvedLayoutHeight - actualBodyBottom else bodyOffset
    val headerTopFraction = headerOffset.value / resolvedLayoutHeight.value
    val headerHeightFraction =
        (headerHeight.value / resolvedLayoutHeight.value).coerceIn(0f, 1f)
    val bodyTopFraction =
        (renderedBodyOffset.value / resolvedLayoutHeight.value).coerceIn(0f, 1f)
    val bodyBottomFraction =
        ((renderedBodyOffset + visualBodyHeight).value / resolvedLayoutHeight.value).coerceIn(0f, 1f)
    val bodyCornerToHeaderRatio = PickerCornerRadius.value / headerHeight.value
    val mergeSmoothnessToHeaderRatio = mergeSmoothnessDp / headerHeight.value
    val surfaceShape = remember(
        headerTopFraction,
        portalOpensUp,
        headerHeightFraction,
        bodyTopFraction,
        bodyBottomFraction,
        bodyCornerToHeaderRatio,
        bodyExtendsBeyondHeader,
        mergeSmoothnessToHeaderRatio
    ) {
        GenericShape { size, _ ->
            val headerHeightPx = (size.height * headerHeightFraction)
                .coerceIn(0f, size.height)
            val headerTopPx = size.height * headerTopFraction
            val bodyTopPx = (size.height * bodyTopFraction)
                .coerceIn(0f, size.height)
            val bodyBottomPx = (size.height * bodyBottomFraction)
                .coerceIn(0f, size.height)
            val bodyCornerPx = headerHeightPx * bodyCornerToHeaderRatio
            val bodyVisible = bodyExtendsBeyondHeader && bodyBottomPx > bodyTopPx + 0.5f
            val implicitContour = if (bodyVisible) {
                val headerBox = RoundedRectMergeGeometry.RoundedBox(
                    left = 0f, top = headerTopPx, right = size.width,
                    bottom = headerTopPx + headerHeightPx, radius = headerHeightPx / 2f
                )
                val bodyBox = RoundedRectMergeGeometry.RoundedBox(
                    left = 0f, top = bodyTopPx, right = size.width,
                    bottom = bodyBottomPx, radius = bodyCornerPx
                )
                RoundedRectMergeGeometry.mergedVerticalOutline(
                    header = if (portalOpensUp) bodyBox else headerBox,
                    body = if (portalOpensUp) headerBox else bodyBox,
                    smoothness = headerHeightPx * mergeSmoothnessToHeaderRatio,
                    stationCount = 44
                )
            } else {
                null
            }

            if (implicitContour != null) {
                addPickerImplicitContour(implicitContour)
            } else {
                addPickerRoundedContour(
                    left = 0f,
                    top = headerTopPx,
                    right = size.width,
                    bottom = headerTopPx + headerHeightPx,
                    radius = headerHeightPx / 2f
                )
                if (bodyVisible) {
                    addPickerRoundedContour(
                        left = 0f,
                        top = bodyTopPx,
                        right = size.width,
                        bottom = bodyBottomPx,
                        radius = bodyCornerPx
                    )
                }
            }
        }
    }
    val surfaceModifier = if (accessibility.highContrast) {
        Modifier
            .clip(surfaceShape)
            .background(appearance.solidSurface)
            .border(1.dp, appearance.border, surfaceShape)
    } else if (glassBackdrop != null) {
        if (layerPolicy.bridgeOverlayAlpha > 0f) {
            // Restore the full SDF neck while the two masses are connected. The layer is absent
            // at both settled endpoints, so its perimeter cannot return as a final thin ring.
            Modifier
                .clip(surfaceShape)
                .background(
                    appearance.surface.copy(
                        alpha = (if (isLightTheme) 0.055f else 0.035f) *
                            layerPolicy.bridgeOverlayAlpha
                    )
                )
                .glassRim(
                    shape = surfaceShape,
                    intensity = if (contentEnabled) 1.14f else 0.62f,
                    isLightTheme = isLightTheme,
                    pressProgress = { layerPolicy.perimeterInteractionProgress }
                )
        } else {
            Modifier
        }
    } else {
        Modifier
            .clip(surfaceShape)
            .background(appearance.solidSurface)
            .border(0.75.dp, appearance.border, surfaceShape)
    }

    AnchoredGlassPortal(
        active = bodyActive,
        anchorHeight = headerHeight,
        renderedHeight = resolvedLayoutHeight,
        desiredBodyHeight = minOf(bodyContentHeight, PickerMaxBodyHeight),
        // A second tap can land on the scrim as the action row retracts. Keep the
        // accepted action pending; removing this picker from its page cancels it.
        onDismiss = { expanded = false },
        onClosed = {
            if (actionPending) {
                actionPending = false
                latestAction?.invoke()
            }
        },
        onSpaceAvailable = { space, up ->
            portalBodySpace = with(density) { space.toDp() }
            portalOpensUp = up
        },
        modifier = modifier.fillMaxWidth()
    ) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .height(resolvedLayoutHeight)
            .wallpaperRegion(regionState)
    ) {
        // Rounded children use the real lens path (the backdrop library requires a rounded-
        // rectangular shape). While the body is still connected, one generic-outline backdrop
        // layer owns the entire mass so no independent body-top refraction edge can appear.
        if (glassBackdrop != null && !accessibility.highContrast) {
            PickerLensLayer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(resolvedLayoutHeight)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        scaleX = headerScale * collisionScaleX
                        scaleY = headerScale * collisionScaleY
                        alpha = layerPolicy.mergedBodyLensAlpha
                    }
                    .drawWithContent {
                        // The persistent header lens owns the complete outer capsule. Restrict the
                        // temporary generic blur to the newly emerged body so its different edge
                        // sampling cannot flash a second ring around the header during hand-off.
                        val clipTop = if (portalOpensUp) 0f else headerHeight.toPx().coerceAtMost(size.height)
                        val clipBottom = if (portalOpensUp) headerOffset.toPx() else size.height
                        clipRect(top = clipTop, bottom = clipBottom) {
                            this@drawWithContent.drawContent()
                        }
                    },
                backdrop = glassBackdrop,
                shape = surfaceShape,
                cornerRadius = PickerCornerRadius,
                motionVelocity = mergedMotionVelocity,
                pressProgress = layerPolicy.perimeterInteractionProgress,
                enabled = contentEnabled,
                // The runtime lens is rounded-rectangular. The transient implicit outline uses a
                // backdrop blur so it remains one sampled glass surface without an internal seam.
                forceBlurFallback = true
            )
            PickerLensLayer(
                modifier = Modifier
                    .offset(y = headerOffset)
                    .fillMaxWidth()
                    .height(headerHeight)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        scaleX = headerScale * collisionScaleX
                        scaleY = headerScale * collisionScaleY
                        alpha = layerPolicy.headerLensAlpha
                    },
                backdrop = glassBackdrop,
                shape = headerShape,
                cornerRadius = headerHeight / 2f,
                motionVelocity = 0f,
                pressProgress = layerPolicy.perimeterInteractionProgress,
                enabled = contentEnabled
            )
            if (bodyPrecomposed) {
                PickerLensLayer(
                    modifier = Modifier
                        .offset(y = renderedBodyOffset)
                        .fillMaxWidth()
                        .height(visualBodyHeight.coerceAtLeast(1.dp))
                        .graphicsLayer {
                            alpha = layerPolicy.separatedBodyLensAlpha
                        },
                    backdrop = glassBackdrop,
                    shape = bodyShape,
                    cornerRadius = PickerCornerRadius,
                    motionVelocity = extentVelocity,
                    pressProgress = layerPolicy.perimeterInteractionProgress,
                    enabled = contentEnabled
                )
            }
        }

        // The contour exists only during the connected phase. At both settled endpoints it draws
        // nothing, so the persistent rounded lenses cannot acquire a second perimeter.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    scaleX = headerScale * collisionScaleX
                    scaleY = headerScale * collisionScaleY
                }
                .drawWithContent {
                    if (glassBackdrop != null && !accessibility.highContrast) {
                        val top = if (portalOpensUp) 0f else headerHeight.toPx().coerceAtMost(size.height)
                        val bottom = if (portalOpensUp) headerOffset.toPx() else size.height
                        clipRect(top = top, bottom = bottom) { this@drawWithContent.drawContent() }
                    } else drawContent()
                }
                .then(surfaceModifier)
        )

        Row(
            modifier = Modifier
                .offset(y = headerOffset)
                .fillMaxWidth()
                .height(headerHeight)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    scaleX = headerScale * collisionScaleX
                    scaleY = headerScale * collisionScaleY
                }
                .clip(headerShape)
                .clickable(
                    interactionSource = headerInteraction,
                    indication = null,
                    enabled = canOpen && !actionPending,
                    role = Role.Button,
                    onClick = { expanded = !expanded }
                )
                .semantics {
                    contentDescription = label ?: placeholder
                    stateDescription = buildString {
                        append(selectedLabel ?: placeholder)
                        append(if (expanded) "，已展开" else "，已收起")
                    }
                    if (!canOpen) disabled()
                }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (descriptorPresent) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (leadingIcon != null) {
                        Icon(
                            imageVector = leadingIcon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = secondaryContentColor
                        )
                    }
                    if (!label.isNullOrBlank()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = secondaryContentColor,
                            maxLines = 1
                        )
                    }
                }
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = if (descriptorPresent) {
                    Alignment.CenterEnd
                } else {
                    Alignment.CenterStart
                }
            ) {
                AnimatedContent(
                    targetState = selectedLabel ?: placeholder,
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (descriptorPresent) {
                        Alignment.CenterEnd
                    } else {
                        Alignment.CenterStart
                    },
                    transitionSpec = {
                        if (reduceMotion) {
                            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        } else {
                            (fadeIn(
                                animationSpec = tween(
                                    durationMillis = 160,
                                    delayMillis = 20,
                                    easing = PickerItemEasing
                                )
                            ) + slideInVertically(
                                animationSpec = tween(
                                    durationMillis = 180,
                                    easing = PickerItemEasing
                                ),
                                initialOffsetY = { it / 5 }
                            )) togetherWith fadeOut(tween(durationMillis = 90))
                        }
                    },
                    label = "morphPickerSelectedLabel"
                ) { value ->
                    Text(
                        text = value,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selectedLabel == null) {
                            FontWeight.Normal
                        } else {
                            FontWeight.SemiBold
                        },
                        color = if (selectedLabel == null) {
                            secondaryContentColor.copy(
                                alpha = secondaryContentColor.alpha * 0.72f
                            )
                        } else {
                            primaryContentColor
                        },
                        textAlign = if (descriptorPresent) TextAlign.End else TextAlign.Start,
                        maxLines = labelLines,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起选项" else "展开选项",
                modifier = Modifier
                    .size(19.dp)
                    .rotate(arrowRotation),
                tint = secondaryContentColor.copy(alpha = 0.82f)
            )
        }

        if (bodyPrecomposed) {
            Box(
                modifier = Modifier
                    .offset(y = renderedBodyOffset)
                    .fillMaxWidth()
                    .height(visualBodyHeight.coerceAtLeast(1.dp))
                    .graphicsLayer {
                        alpha = bodyAlpha
                    }
                    .clip(bodyShape)
                    .padding(horizontal = PickerBodyHorizontalPadding)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(bodyContentHeight)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(itemHeight)
                                .graphicsLayer {
                                    translationY = highlightedOffset.value.toPx()
                                    alpha = highlightedAlpha
                                }
                                .clip(Capsule())
                                .background(highlightColor)
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = PickerBodyVerticalPadding),
                            verticalArrangement = Arrangement.spacedBy(PickerItemGap)
                        ) {
                            options.forEachIndexed { index, option ->
                                MorphingPickerRow(
                                    label = option.label,
                                    height = itemHeight,
                                    maxLines = labelLines,
                                    expanded = expanded,
                                    revealProgress = rowRevealProgress(index),
                                    enabled = option.enabled,
                                    selected = index == validSelectedIndex,
                                    action = false,
                                    onHighlight = { highlightedRow = index },
                                    onClick = {
                                        highlightedRow = index
                                        if (index != validSelectedIndex) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                        onSelect(index)
                                        expanded = false
                                    }
                                )
                            }

                            if (hasAction) {
                                MorphingPickerRow(
                                    label = actionLabel.orEmpty(),
                                    height = itemHeight,
                                    maxLines = labelLines,
                                    expanded = expanded,
                                    revealProgress = rowRevealProgress(options.size),
                                    enabled = true,
                                    selected = false,
                                    action = true,
                                    onHighlight = { highlightedRow = options.size },
                                    onClick = {
                                        if (!actionPending) {
                                            actionPending = true
                                            expanded = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

}

@Composable
private fun MorphingPickerRow(
    label: String,
    height: Dp,
    maxLines: Int,
    expanded: Boolean,
    revealProgress: Float,
    enabled: Boolean,
    selected: Boolean,
    action: Boolean,
    onHighlight: () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(pressed, expanded, enabled) {
        if (pressed && expanded && enabled) onHighlight()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                val progress = revealProgress.coerceIn(0f, 1f)
                alpha = progress * if (enabled) 1f else 0.38f
                translationY = (1f - progress) * 6.dp.toPx()
            }
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = expanded && enabled,
                role = if (action) Role.Button else Role.RadioButton,
                onClick = onClick
            )
            .semantics {
                if (!expanded) hideFromAccessibility()
                if (!action) this.selected = selected
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected || action) FontWeight.SemiBold else FontWeight.Normal,
            color = if (action) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

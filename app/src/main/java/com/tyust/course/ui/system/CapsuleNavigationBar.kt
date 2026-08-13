package com.tyust.course.ui.system

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
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
import com.tyust.course.BottomNavItem
import com.tyust.course.ui.system.glass.DampedDragAnimation
import com.tyust.course.ui.system.glass.motionIntensityFromVelocity
import com.tyust.course.ui.system.glass.resolvePhysicalLens
import com.tyust.course.ui.theme.MotionSpring
import com.tyust.course.ui.theme.NeuPrimary
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

@Composable
fun CapsuleNavigationBar(
    items: List<BottomNavItem>,
    selectedTab: Int,
    onTabSelect: (Int) -> Unit,
    minimized: Boolean = false,
    onExpandRequest: () -> Unit = {},
    backdrop: Backdrop? = LocalAppBackdrop.current,
    modifier: Modifier = Modifier
) {
    val useGlass = backdrop != null && isBackdropSupported()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        when {
            useGlass -> {
                // 滚动最小化：整条玻璃横向收拢淡出，单胶囊液态弹出
                val minimizeFraction by animateFloatAsState(
                    targetValue = if (minimized) 1f else 0f,
                    animationSpec = MotionSpring.liquidSettle(),
                    label = "navMinimizeFraction"
                )
                if (minimizeFraction < 0.999f) {
                    Box(
                        modifier = Modifier.graphicsLayer {
                            val f = minimizeFraction
                            alpha = (1f - f * 1.6f).fastCoerceIn(0f, 1f)
                            scaleX = 1f - 0.82f * f
                            scaleY = 1f - 0.30f * f
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                    ) {
                        GlassNavigationBar(
                            items = items,
                            selectedTab = selectedTab,
                            onTabSelect = onTabSelect,
                            backdrop = requireNotNull(backdrop)
                        )
                    }
                }
                if (minimizeFraction > 0.001f) {
                    MinimizedNavCapsule(
                        item = items.getOrElse(selectedTab) { items.first() },
                        backdrop = requireNotNull(backdrop),
                        onClick = onExpandRequest,
                        modifier = Modifier.graphicsLayer {
                            val f = minimizeFraction
                            alpha = ((f - 0.35f) / 0.65f).fastCoerceIn(0f, 1f)
                            val scale = 0.55f + 0.45f * f
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                    )
                }
            }
            else -> FallbackNavigationBar(
                items = items,
                selectedTab = selectedTab,
                onTabSelect = onTabSelect
            )
        }
    }
}

/** 最小化形态：仅显示当前 tab 的小玻璃胶囊，点按展开。 */
@Composable
private fun MinimizedNavCapsule(
    item: BottomNavItem,
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLightTheme = !isSystemInDarkTheme()
    val containerColor = if (isLightTheme) {
        Color.White.copy(alpha = 0.28f)
    } else {
        Color.Black.copy(alpha = 0.26f)
    }
    Row(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(8.dp.toPx())
                },
                highlight = { Highlight.Default.copy(alpha = 0.14f) },
                shadow = { Shadow(alpha = 0.10f) },
                onDrawSurface = { drawRect(containerColor) }
            )
            .clip(RoundedCornerShape(percent = 50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .height(48.dp)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = NeuPrimary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = item.label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun GlassNavigationBar(
    items: List<BottomNavItem>,
    selectedTab: Int,
    onTabSelect: (Int) -> Unit,
    backdrop: Backdrop
) {
    val tabsCount = items.size
    // 隐藏 tint 内容层：供选中透镜 combined 采样，避免只看到空雾
    val tabsBackdrop = rememberLayerBackdrop()
    val accessibility = rememberGlassAccessibilityMode()
    // 平台是否真出折射/色散（API 33+）
    val hasRealLens = isRuntimeShaderTrulySupported()
    // API33+ 加大尺寸可溢出；API32 回归 cba2a09：64/56/4 + pressedScale 78/56
    val trackHeight = if (hasRealLens) 72.dp else 64.dp
    val indicatorHeight = if (hasRealLens) 56.dp else 56.dp
    val barPadding = if (hasRealLens) 6.dp else 4.dp
    val isLightTheme = !isSystemInDarkTheme()
    // API32：cba2a09 半透轨；API33+：半透主题底
    val containerColor = if (hasRealLens) {
        // 提浊：降低穿透内容对比度，深色文字经过栏后不再形成清晰污块
        if (isLightTheme) Color.White.copy(alpha = 0.28f)
        else Color.Black.copy(alpha = 0.26f)
    } else {
        if (isLightTheme) {
            Color(GlassRecipe.NavLegacyTrackSurfaceLight)
                .copy(alpha = GlassRecipe.NavLegacyTrackSurfaceAlpha)
        } else {
            Color.Black.copy(alpha = GlassRecipe.NavLegacyTrackSurfaceAlpha)
        }
    }
    val accentColor = NeuPrimary
    val barMaterial = GlassMaterials.resolve(
        role = GlassMaterialRole.Navigation,
        accessibility = accessibility,
        interactionProgress = 0f
    )
    val indicatorMaterial = GlassMaterials.resolve(
        role = GlassMaterialRole.Interactive,
        accessibility = accessibility,
        interactionProgress = 0f
    )
    val pressedScale = if (hasRealLens) {
        GlassRecipe.NavPressedScale
    } else {
        GlassRecipe.NavLegacyPressedScale
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { clip = false },
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val barPaddingPx = with(density) { barPadding.toPx() }
        val tabWidth = (constraints.maxWidth.toFloat() - barPaddingPx * 2f) / tabsCount

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth)
                    .fastCoerceIn(-1f, 1f)
                with(density) {
                    4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val animationScope = rememberCoroutineScope()
        var currentIndex by remember { mutableIntStateOf(selectedTab) }
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTab.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = pressedScale,
                directManipulationSpec = MotionSpring.liquidFollow(),
                settleAnimationSpec = MotionSpring.liquidSettle(),
                releaseScaleAnimationSpec = MotionSpring.liquidSelectionRelease(),
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue
                        .fastRoundToInt()
                        .fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    onTabSelect(targetIndex)
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, MotionSpring.liquidJellyRebound())
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }

        LaunchedEffect(selectedTab) {
            if (currentIndex != selectedTab) {
                currentIndex = selectedTab
                dampedDragAnimation.animateToValue(selectedTab.toFloat())
            }
        }

        val indicatorBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)

        // 1) 外层磨砂轨道：API32 固定 blur 8（cba2a09）；API33+ 轻 blur + 弱 lens
        Box(
            modifier = Modifier
                .graphicsLayer {
                    translationX = panelOffset
                    clip = false
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        if (hasRealLens) {
                            val params = resolvePhysicalLens(
                                density = this,
                                material = barMaterial,
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
                            // cba2a09 Layer1：固定 vibrancy + blur(8)
                            blur(GlassRecipe.NavLegacyTrackBlurDp.dp.toPx())
                        }
                    },
                    highlight = {
                        if (hasRealLens) Highlight.Default.copy(alpha = 0.16f) else null
                    },
                    shadow = {
                        if (hasRealLens) {
                            Shadow(alpha = 0.08f)
                        } else {
                            // cba2a09：轨道无投影
                            null
                        }
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        // 轨道随按压 + 指示器 scale 呼吸；滑块果冻回弹时底栏同步放大回落
                        val maxGain = 16.dp.toPx()
                        val pressScale = lerp(1f, 1f + maxGain / size.width, progress)
                        val indicatorBoost = (
                            (dampedDragAnimation.scaleX + dampedDragAnimation.scaleY) / 2f - 1f
                            ).fastCoerceIn(0f, 0.4f)
                        val scale = pressScale * (1f + indicatorBoost * 0.18f)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = {
                        drawRect(containerColor)
                    }
                )
                .height(trackHeight)
                .fillMaxWidth()
                .padding(barPadding)
        ) {
            // 可见图标文字
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(tabsCount, tabWidth) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startValue = dampedDragAnimation.value
                            val startX = down.position.x
                            val touchSlop = viewConfiguration.touchSlop
                            var totalDragX = 0f
                            var dragging = false
                            var pointerId = down.id

                            dampedDragAnimation.press()

                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                        ?: break
                                    if (change.changedToUpIgnoreConsumed()) {
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
                                            dampedDragAnimation.updateValue(
                                                (startValue + totalDragX / tabWidth)
                                                    .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                                            )
                                            animationScope.launch {
                                                offsetAnimation.snapTo(totalDragX)
                                            }
                                        }
                                    }
                                    pointerId = change.id
                                }
                            } finally {
                                val targetIndex = if (dragging) {
                                    dampedDragAnimation.targetValue
                                        .fastRoundToInt()
                                        .fastCoerceIn(0, tabsCount - 1)
                                } else {
                                    (startX / tabWidth)
                                        .toInt()
                                        .fastCoerceIn(0, tabsCount - 1)
                                }
                                currentIndex = targetIndex
                                onTabSelect(targetIndex)
                                dampedDragAnimation.animateToValue(targetIndex.toFloat())
                                animationScope.launch {
                                    offsetAnimation.animateTo(0f, MotionSpring.liquidJellyRebound())
                                }
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    val selectionWeight = (1f - abs(dampedDragAnimation.value - index))
                        .fastCoerceIn(0f, 1f)
                    NavTab(
                        item = item,
                        selected = selectionWeight > 0.55f,
                        selectionWeight = selectionWeight,
                        onClick = null
                    )
                }
            }
        }

        // 2) 隐藏 tint 内容层：被选中透镜 refraction / combined 采样
        Row(
            modifier = Modifier
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .graphicsLayer {
                    translationX = panelOffset
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        vibrancy()
                        if (hasRealLens) {
                            val params = resolvePhysicalLens(
                                density = this,
                                material = barMaterial,
                                shape = Capsule(),
                                minCornerRadiusPx = size.minDimension / 2f,
                                minDimensionPx = size.minDimension,
                                interactionProgress = progress,
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
                            // cba2a09 Layer2：固定 blur 8
                            blur(GlassRecipe.NavLegacyTrackBlurDp.dp.toPx())
                        }
                    },
                    highlight = {
                        // 该层被选中透镜采样，白环压低避免折射进胶囊形成白圈
                        Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress * 0.35f)
                    },
                    onDrawSurface = {
                        drawRect(containerColor)
                    }
                )
                .height(indicatorHeight)
                .fillMaxWidth()
                .padding(horizontal = barPadding)
                .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                NavTab(
                    item = item,
                    selected = true,
                    selectionWeight = 1f,
                    onClick = null,
                    forceAccent = true
                )
            }
        }

        // 3) 选中透镜：缩放只在 layerBlock；API32 必须 blur 补偿无 lens
        Box(
            modifier = Modifier
                .padding(horizontal = barPadding)
                .graphicsLayer {
                    translationX = dampedDragAnimation.value * tabWidth + panelOffset
                    clip = false
                }
                .drawBackdrop(
                    backdrop = indicatorBackdrop,
                    shape = { Capsule() },
                    effects = {
                        val press = dampedDragAnimation.pressProgress
                        vibrancy()
                        if (hasRealLens) {
                            // 横向速度 → 运动强度，驱动色散随滑动增强
                            val motion = motionIntensityFromVelocity(
                                velocityX = dampedDragAnimation.velocity * tabWidth,
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
                                // 静止保留轻折射（凸起镜片感），交互时抬到满值
                                pressScalesRefraction = true,
                                refractionFloor = 0.42f
                            )
                            if (params.useLens) {
                                lens(
                                    refractionHeight = params.refractionHeightPx,
                                    refractionAmount = params.refractionAmountPx,
                                    chromaticAberration = params.chromaticAberration
                                )
                            }
                        } else {
                            // cba2a09：31/32 上 lens 为平台 no-op，毛玻璃来自 combined 采样轨道模糊层
                            lens(
                                refractionHeight = 10.dp.toPx() * press,
                                refractionAmount = 14.dp.toPx() * press,
                                chromaticAberration = true
                            )
                        }
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        // 整层被 graphicsLayer 等比放大，描边宽度反向补偿保持视觉细度恒定
                        val scaleComp = (
                            (dampedDragAnimation.scaleX + dampedDragAnimation.scaleY) / 2f
                            ).coerceAtLeast(1f)
                        if (hasRealLens) {
                            Highlight.Default.copy(
                                width = Highlight.Default.width / scaleComp,
                                blurRadius = Highlight.Default.blurRadius / scaleComp,
                                alpha = 0.12f + progress * 0.35f
                            )
                        } else {
                            // 按压渐显边缘高光（压低亮度，避免白圈）
                            Highlight.Default.copy(
                                width = Highlight.Default.width / scaleComp,
                                alpha = progress * 0.35f
                            )
                        }
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        if (hasRealLens) {
                            Shadow(alpha = 0.10f + progress * 0.15f)
                        } else {
                            // 按压渐显投影（减半，滑动残影更轻）
                            Shadow(alpha = progress * 0.5f)
                        }
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 4.dp * progress,
                            alpha = progress * 0.5f
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        // 速度形变系数加大（/6 相对原 /10）
                        val velocity = dampedDragAnimation.velocity / 6f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.22f, 0.22f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.22f, 0.22f)
                    },
                    onDrawBackdrop = { drawBackdrop ->
                        // 无环 indicatorBackdrop（壁纸+页面），两平台都采样
                        drawBackdrop()
                    },
                    onDrawSurface = {
                        val press = dampedDragAnimation.pressProgress
                        if (hasRealLens) {
                            // API33+：低透明中性 tint，静止即玻璃；按下更透露出折射/色散
                            val solidColor = if (isLightTheme) {
                                Color(GlassRecipe.NavSelectedSolidColorLight)
                            } else {
                                Color(GlassRecipe.NavSelectedSolidColorDark)
                            }
                            val restAlpha = if (isLightTheme) {
                                GlassRecipe.NavSelectedSolidAlpha
                            } else {
                                GlassRecipe.NavSelectedSolidAlphaDark
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
                            // API31/32 cba2a09：Black×0.1，按下淡出
                            drawRect(Color.Black.copy(0.1f), alpha = 1f - press)
                            drawRect(Color.Black.copy(alpha = 0.03f * press))
                        }
                    }
                )
                .height(indicatorHeight)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}

@Composable
private fun FallbackNavigationBar(
    items: List<BottomNavItem>,
    selectedTab: Int,
    onTabSelect: (Int) -> Unit
) {
    val capsuleShape = RoundedCornerShape(28.dp)
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
        shape = capsuleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                NavTab(
                    item = item,
                    selected = selectedTab == index,
                    onClick = { onTabSelect(index) }
                )
            }
        }
    }
}

@Composable
private fun RowScope.NavTab(
    item: BottomNavItem,
    selected: Boolean,
    selectionWeight: Float = if (selected) 1f else 0f,
    onClick: (() -> Unit)?,
    forceAccent: Boolean = false
) {
    val weight = selectionWeight.fastCoerceIn(0f, 1f)
    val iconTint by animateColorAsState(
        targetValue = if (forceAccent) {
            NeuPrimary
        } else {
            androidx.compose.ui.graphics.lerp(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                NeuPrimary,
                weight
            )
        },
        label = "navIconTint"
    )
    val labelColor by animateColorAsState(
        targetValue = if (forceAccent) {
            NeuPrimary
        } else {
            androidx.compose.ui.graphics.lerp(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                MaterialTheme.colorScheme.onSurface,
                weight
            )
        },
        label = "navLabelColor"
    )
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    } else {
        Modifier
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .then(clickModifier)
            .fillMaxHeight()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = iconTint,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer {
                    // 选中：上移 + 放大 1.15x（跟随 selectionWeight，回弹来自底层气泡运动）
                    val w = if (forceAccent) 1f else weight
                    val scale = 1f + 0.15f * w
                    scaleX = scale
                    scaleY = scale
                    translationY = -3.dp.toPx() * w
                }
        )
        Text(
            text = item.label,
            color = labelColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (weight > 0.55f || forceAccent) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
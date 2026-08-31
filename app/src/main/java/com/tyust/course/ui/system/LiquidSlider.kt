package com.tyust.course.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.tyust.course.ui.system.glass.DampedDragAnimation
import com.tyust.course.ui.system.glass.GlassLensFreshness
import com.tyust.course.ui.system.glass.glassLens
import com.tyust.course.ui.system.glass.glassLensAnchor
import com.tyust.course.ui.system.glass.isGlassLensApplicable
import com.tyust.course.ui.system.glass.rememberGlassLensRegion
import com.tyust.course.ui.system.glass.thumbLensMaterial
import com.tyust.course.ui.system.glass.thumbLensOptics
import com.tyust.course.ui.system.glass.thumbLensTransform
import kotlinx.coroutines.flow.collectLatest

/**
 * backdrop 库 catalog 原版 LiquidSlider 的移植，轨道换成调用方传入的语义渐变：
 *
 *   - 轨道 6dp 细胶囊，渐变本身就是取值可视化（色相=色环、蒙版=白→黑、模糊=灰阶），
 *     不再是原版的"无色灰+蓝色填充段"，也不是 GlassGradientSlider 的 26dp 粗轨道
 *   - thumb 40×24 玻璃胶囊，经 combined(外部backdrop, 轨道层) 采样——
 *     **移动到哪里就折射出脚下轨道的颜色**
 *   - 物理：DampedDragAnimation 做动画与光学载体（liquidFollow 质量滞后 + settle/release
 *     弹性）+ 速度果冻拉伸 + 按压 1.5× + 轨道采样随按压纵向展开 + 点哪跳哪
 *
 * ## 手势挂在整行容器上（GlassGradientSlider 同款纪律）
 *
 * 第一版把拖拽手势放在 thumb、点按放在 6dp 轨道上——在弹窗的 verticalScroll 里
 * 完全拖不动：thumb 靠 graphicsLayer translationX 定位，命中区域不跟随绘制位移；
 * 且弹窗滚动列一旦消费了移动事件，增量式拖拽就整体放弃。这里改为整行
 * awaitEachGesture + 绝对坐标换算，首个移动事件即消费，滚动列无从抢占；
 * thumb 与轨道都是纯视觉节点。
 *
 * thumb 与轨道是兄弟节点、轨道单独成捕获层——防自采样纪律同 GlassGradientSlider。
 */
@Composable
fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    trackBrush: Brush,
    backdrop: Backdrop? = LocalControlBackdrop.current,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val span = valueRange.endInclusive - valueRange.start
    val visibilityThreshold = if (span > 0f) span / 400f else 0.001f

    val trackBackdrop = rememberLayerBackdrop()
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    val animationScope = rememberCoroutineScope()
    // 手势期间外部值不许再驱动动画：调用方会把我们刚发出的值原路送回来，
    // 两条路径同时对一个 Animatable 发 animateTo 会互相抢占，胶囊就卡住。
    var isDragging by remember { mutableStateOf(false) }
    val dampedDragAnimation = remember(animationScope, valueRange) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = value().coerceIn(valueRange),
            valueRange = valueRange,
            visibilityThreshold = visibilityThreshold,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {},
            onDrag = { _, _ -> }
        )
    }

    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { value() }
            .collectLatest { current ->
                if (!isDragging && dampedDragAnimation.targetValue != current) {
                    dampedDragAnimation.animateToValue(current)
                }
            }
    }

    // API31/32：平台没有 AGSL，改用离屏 ES 2.0 做真折射（见 ThumbLens.kt）。
    // 底图 = 环境背景 + **未缩放**的轨道层（为什么不跟那层按压缩放：见 ThumbLens.kt
    // 顶部）。锚点挂在外层这条 36dp 高的行容器上 —— 它不随 thumb 移动。
    val usableBackdropForLens = backdrop?.takeIf { isBackdropSupported() }
    // 拖动时**环境**在变：蒙版/模糊滑块一拖整张壁纸跟着变，底图不重拍的话折射里
    // 是拖动前的壁纸。限频重拍（100ms 一次），松手补一次。
    // 轨道自己的渐变是静态的，不需要为它重拍。
    val lensFreshness = remember {
        if (isGlassLensApplicable()) GlassLensFreshness() else null
    }
    // 显式命名：DrawScope 里的 `density` 是 Float 成员，写 `density` 靠的是重载
    // 解析恰好挑中外层这个 Density。改个名就不必依赖那个巧合。
    val lensDensity = density
    val sliderLensAnchor = if (usableBackdropForLens != null) {
        rememberGlassLensRegion(
            tag = "slider",
            freshness = lensFreshness
        ) { coords ->
            with(usableBackdropForLens) { drawBackdrop(lensDensity, coords, null) }
            with(trackBackdrop) { drawBackdrop(lensDensity, coords, null) }
        }
    } else {
        null
    }
    val sliderLensMaterial = remember {
        // 库：lens(10dp * press, 14dp * press)
        thumbLensMaterial(refractionHeightDp = 10f, refractionAmountDp = 14f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .onSizeChanged { widthPx = it.width }
            .glassLensAnchor(sliderLensAnchor)
            .then(
                if (enabled) {
                    Modifier.pointerInput(widthPx, valueRange, isLtr) {
                        if (widthPx <= 0) return@pointerInput
                        // 可行程是「总宽 - thumb 宽」，thumb 才不会探出轨道两端
                        val thumbPx = 40.dp.toPx()
                        val travel = (widthPx - thumbPx).coerceAtLeast(1f)
                        fun valueAt(x: Float): Float {
                            val fraction = ((x - thumbPx / 2f) / travel).fastCoerceIn(0f, 1f)
                            val along = if (isLtr) fraction else 1f - fraction
                            return (valueRange.start + span * along)
                                .fastCoerceIn(valueRange.start, valueRange.endInclusive)
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            isDragging = true
                            try {
                                dampedDragAnimation.press()
                                val initial = valueAt(down.position.x)
                                dampedDragAnimation.updateValue(initial)
                                onValueChange(initial)
                                // 值一变环境背景就可能整张变（蒙版/模糊滑块改的
                                // 就是壁纸本身），底图要跟着重拍。限频在
                                // GlassLensFreshness 里。
                                lensFreshness?.onScroll()
                                drag(down.id) { change ->
                                    val target = valueAt(change.position.x)
                                    dampedDragAnimation.updateValue(target)
                                    onValueChange(target)
                                    lensFreshness?.onScroll()
                                    change.consume()
                                }
                            } finally {
                                dampedDragAnimation.release()
                                isDragging = false
                                // 松手补一张：用户真正盯着看的是静止态
                                lensFreshness?.onSettled()
                            }
                        }
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        // 轨道层：渐变胶囊，单独进入捕获层供 thumb 折射
        Box(Modifier.layerBackdrop(trackBackdrop)) {
            Box(
                Modifier
                    .clip(Capsule())
                    .background(trackBrush)
                    .height(6.dp)
                    .fillMaxWidth()
            )
        }

        val trackWidth = widthPx.toFloat()
        val progress = ((dampedDragAnimation.value - valueRange.start) / span)
            .fastCoerceIn(0f, 1f)

        // thumb：玻璃路径采样"外部背景 + 轨道层"，轨道采样随按压纵向展开（原版配方）
        val usableBackdrop = backdrop?.takeIf { isBackdropSupported() }
        Box(
            Modifier
                .graphicsLayer {
                    translationX =
                        (trackWidth - size.width).coerceAtLeast(0f) * progress *
                            if (isLtr) 1f else -1f
                }
                .then(
                    if (usableBackdrop != null) {
                        // 画在 drawBackdrop **之前**：折射结果就是 thumb 的背景，
                        // 库那层的 surface / highlight / shadow 仍叠在上面。
                        Modifier.glassLens(
                            anchor = sliderLensAnchor,
                            optics = { w, h ->
                                thumbLensOptics(
                                    material = sliderLensMaterial,
                                    density = density,
                                    // 胶囊：圆角就是短边的一半
                                    cornerRadiusPx = minOf(w, h) / 2f,
                                    minDimensionPx = minOf(w, h),
                                    press = dampedDragAnimation.pressProgress
                                )
                            },
                            // 与下面 layerBlock 读**同一个**函数
                            scale = { _, _ ->
                                thumbLensTransform(
                                    anim = dampedDragAnimation,
                                    velocityDivisor = 10f,
                                    reduceMotion = false
                                )
                            }
                        ).drawBackdrop(
                            backdrop = rememberCombinedBackdrop(
                                usableBackdrop,
                                rememberBackdrop(trackBackdrop) { drawBackdrop ->
                                    val press = dampedDragAnimation.pressProgress
                                    val scaleX = lerp(2f / 3f, 1f, press)
                                    val scaleY = lerp(0f, 1f, press)
                                    scale(scaleX, scaleY) {
                                        drawBackdrop()
                                    }
                                }
                            ),
                            shape = { Capsule() },
                            effects = {
                                val press = dampedDragAnimation.pressProgress
                                if (sliderLensAnchor == null) {
                                    blur(8.dp.toPx() * (1f - press))
                                    lens(
                                        10.dp.toPx() * press,
                                        14.dp.toPx() * press,
                                        chromaticAberration = true
                                    )
                                }
                                // 31/32：折射与色散都已由上面的 glassLens 画完。
                                // 那层 blur 也不能留 —— 它会把自家折射糊掉。
                            },
                            onDrawBackdrop = { drawBackdrop ->
                                // 31/32 上背景已由 glassLens 折射过，不能再画一遍
                                if (sliderLensAnchor == null) drawBackdrop()
                            },
                            highlight = {
                                val press = dampedDragAnimation.pressProgress
                                Highlight.Ambient.copy(
                                    width = Highlight.Ambient.width / 1.5f,
                                    blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                                    alpha = press
                                )
                            },
                            shadow = {
                                Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f))
                            },
                            innerShadow = {
                                val press = dampedDragAnimation.pressProgress
                                InnerShadow(radius = 4.dp * press, alpha = press)
                            },
                            layerBlock = {
                                // 与上面 glassLens(scale) 读**同一个** thumbLensTransform，
                                // 两者不可能再脱钩
                                val t = thumbLensTransform(
                                    anim = dampedDragAnimation,
                                    velocityDivisor = 10f,
                                    reduceMotion = false
                                )
                                scaleX = t.scaleX
                                scaleY = t.scaleY
                            },
                            onDrawSurface = {
                                val press = dampedDragAnimation.pressProgress
                                drawRect(Color.White.copy(alpha = 1f - press))
                            }
                        )
                    } else {
                        // 无 backdrop 回退：实色玻璃拟物（浅描边 + 柔影），交互能力与玻璃路径等价
                        Modifier
                            .shadow(2.dp, Capsule(), clip = false)
                            .clip(Capsule())
                            .background(Color.White)
                            .graphicsLayer {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                            }
                    }
                )
                .size(40.dp, 24.dp)
        )
    }
}

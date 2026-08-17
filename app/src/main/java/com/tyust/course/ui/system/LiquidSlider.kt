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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .onSizeChanged { widthPx = it.width }
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
                                drag(down.id) { change ->
                                    val target = valueAt(change.position.x)
                                    dampedDragAnimation.updateValue(target)
                                    onValueChange(target)
                                    change.consume()
                                }
                            } finally {
                                dampedDragAnimation.release()
                                isDragging = false
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
                        Modifier.drawBackdrop(
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
                                blur(8.dp.toPx() * (1f - press))
                                lens(
                                    10.dp.toPx() * press,
                                    14.dp.toPx() * press,
                                    chromaticAberration = true
                                )
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
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                                val velocity = dampedDragAnimation.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
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

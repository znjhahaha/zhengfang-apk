package com.tyust.course.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
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
import kotlin.math.abs

private val ThumbSize = 26.dp

/** 按压时圆钮胀大的倍率。容器要比圆钮高一截，胀开才不会被父级裁掉。 */
private const val PressedThumbScale = 1.4f

/**
 * 渐变色条。取色器用的那种：轨道本身就是取值范围的可视化（色相彩虹、蒙版、模糊），
 * 拖动圆钮取值。
 *
 * 全 App 之前没有任何滑块组件，所以从零写一个而不是套 Material `Slider`——
 * Material 的轨道是纯色 + 刻度，塞不进渐变。
 *
 * ## 圆钮的玻璃配方
 *
 * 与 [LiquidSwitch] 逐参数同源（`LiquidComponents.kt`），那是官方 LiquidSlider 的做法：
 *
 * 1. 轨道自己 `layerBackdrop` 成一层——彩虹渐变正好是圆钮需要折射的高对比内容；
 * 2. `rememberBackdrop` 把这一层的近场在静止时纵向压扁到 0，于是圆钮是不被轨道
 *    颜色污染的实色实体；按下才展开成折射与色散（"绽开"的来源）；
 * 3. 圆钮与轨道是**兄弟**节点，采 `壁纸 + 缩放后的轨道` 的合成 backdrop。
 *    挂在轨道自己身上会自采样 → RenderThread 死循环 → native SIGSEGV。
 *
 * ## 手势为什么还是手写的
 *
 * [DampedDragAnimation.modifier] 走 `inspectDragGestures`，是增量 + touch slop 的，
 * 没有"点哪跳哪"。取色条必须点一下就跳过去，所以保留绝对坐标的 `awaitEachGesture`，
 * 手动调 `press()` / `updateValue()` / `release()`，把 [DampedDragAnimation]
 * 只当动画与光学的载体用。
 *
 * 也不要拆成 `detectTapGestures` + `detectHorizontalDragGestures` 两个 pointerInput：
 * 它们会互相抢手势，按下那一下经常丢。
 *
 * @param onValueChangeFinished 松手时回调。调用方在这里落盘——拖动过程中每帧写
 *                              SharedPreferences 没有意义。
 */
@Composable
fun GlassGradientSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    brush: Brush,
    modifier: Modifier = Modifier,
    thumbColor: Color = Color.White,
    onValueChangeFinished: () -> Unit = {},
    trackHeight: Dp = 26.dp
) {
    val density = LocalDensity.current
    val isLight = !isSystemInDarkTheme()
    val accessibility = rememberGlassAccessibilityMode()
    val glassBackdrop = LocalControlBackdrop.current?.takeIf { isBackdropSupported() }
    var widthPx by remember { mutableIntStateOf(0) }
    val thumbPx = with(density) { ThumbSize.toPx() }
    val trackShape = Capsule()

    val animationScope = rememberCoroutineScope()
    // 手势期间外部值不许再驱动动画：调用方会把我们刚发出的值原路送回来，
    // 两条路径同时对一个 Animatable 发 animateTo 会互相抢占，圆钮就卡住。
    var isDragging by remember { mutableStateOf(false) }
    val dragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = value.fastCoerceIn(0f, 1f),
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = PressedThumbScale,
            onDragStarted = {},
            onDragStopped = {},
            onDrag = { _, _ -> }
        )
    }

    LaunchedEffect(value) {
        val target = value.fastCoerceIn(0f, 1f)
        if (!isDragging && abs(target - dragAnimation.targetValue) > 0.001f) {
            dragAnimation.animateToValue(target)
        }
    }

    val trackBackdrop = rememberLayerBackdrop()
    val scaledTrackBackdrop = rememberBackdrop(trackBackdrop) { drawTrackBackdrop ->
        val progress = dragAnimation.pressProgress
        scale(lerp(2f / 3f, 0.75f, progress), lerp(0f, 0.75f, progress)) {
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
            .fillMaxWidth()
            .height(ThumbSize + 12.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(widthPx) {
                if (widthPx <= 0) return@pointerInput
                // 可行程是「总宽 - 圆钮直径」，圆钮才不会探出轨道两端
                val travel = (widthPx - thumbPx).coerceAtLeast(1f)
                fun fractionAt(x: Float) = ((x - thumbPx / 2f) / travel).fastCoerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isDragging = true
                    dragAnimation.press()
                    val initial = fractionAt(down.position.x)
                    dragAnimation.updateValue(initial)
                    onValueChange(initial)
                    drag(down.id) { change ->
                        val fraction = fractionAt(change.position.x)
                        dragAnimation.updateValue(fraction)
                        onValueChange(fraction)
                        change.consume()
                    }
                    dragAnimation.release()
                    isDragging = false
                    onValueChangeFinished()
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .layerBackdrop(trackBackdrop)
                .clip(trackShape)
                .background(brush)
                .border(
                    width = 0.5.dp,
                    color = if (isLight) {
                        Color.Black.copy(alpha = 0.10f)
                    } else {
                        Color.White.copy(alpha = 0.18f)
                    },
                    shape = trackShape
                )
                .fillMaxWidth()
                .height(trackHeight)
        )

        val thumbOffsetModifier = Modifier.graphicsLayer {
            translationX = (widthPx - thumbPx).coerceAtLeast(0f) * dragAnimation.value
        }

        if (thumbBackdrop != null) {
            Box(
                modifier = thumbOffsetModifier
                    .drawBackdrop(
                        backdrop = thumbBackdrop,
                        shape = { CircleShape },
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
                                color = Color.Black.copy(alpha = 0.10f)
                            )
                        },
                        innerShadow = {
                            val progress = dragAnimation.pressProgress
                            InnerShadow(radius = 4.dp * progress, alpha = progress)
                        },
                        layerBlock = {
                            if (accessibility.reduceMotion) {
                                scaleX = 1f
                                scaleY = 1f
                            } else {
                                scaleX = dragAnimation.scaleX
                                scaleY = dragAnimation.scaleY
                                val velocity = dragAnimation.velocity / 50f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            }
                        },
                        onDrawSurface = {
                            val progress = dragAnimation.pressProgress
                            // 静止态是「圆钮就是当前颜色」的读法：白环 + 色芯；
                            // 按下时整块表面退去，露出轨道折射与色散。
                            val surfaceAlpha = lerp(1f, 0f, progress)
                            drawCircle(Color.White.copy(alpha = surfaceAlpha))
                            drawCircle(
                                color = thumbColor.copy(alpha = surfaceAlpha),
                                radius = size.minDimension / 2f - 3.dp.toPx()
                            )
                        }
                    )
                    .size(ThumbSize)
            )
        } else {
            Box(
                modifier = thumbOffsetModifier
                    .graphicsLayer {
                        if (!accessibility.reduceMotion) {
                            scaleX = dragAnimation.scaleX
                            scaleY = dragAnimation.scaleY
                        }
                    }
                    .size(ThumbSize)
                    .shadow(2.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(Color.White)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(thumbColor)
            )
        }
    }
}

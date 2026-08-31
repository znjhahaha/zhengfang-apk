package com.tyust.course.ui.system.glass

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
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
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import com.tyust.course.ui.system.rememberGlassDarkTheme
import com.tyust.course.ui.system.LocalControlBackdrop
import com.tyust.course.ui.system.isBackdropSupported
import com.tyust.course.ui.system.rememberGlassAccessibilityMode

private val PuckSize = 28.dp
private const val PressedPuckScale = 1.35f

/**
 * 二维取色板：横轴饱和度、纵轴明度，色相由外部的色相条给。
 *
 * 为什么要二维：饱和度与明度本来就是同一个平面上的两个方向，拆成两条滑块调色
 * 要来回拉三四趟才能找到一个颜色；一块方板一次落指就到位。
 *
 * 摘钮（puck）的玻璃配方与 [com.tyust.course.ui.system.GlassGradientSlider] 逐参数同源：
 * 色板自己 `layerBackdrop` 成一层 → 静止时把这层近场压扁（摘钮是干净的实色）→
 * 按下展开成折射与色散。摘钮必须是色板的**兄弟**节点，挂在色板身上会自采样 →
 * RenderThread 死循环 → native SIGSEGV。
 *
 * ## 手势必须立刻 consume
 *
 * 这块板通常放在 `verticalScroll` 的弹窗里。不 consume 的话，纵向拖动会在
 * touch slop 之后被滚动容器抢走——调明度变成了滚页面。直接操纵面本来就不该
 * 等 slop，所以按下与每一次 change 都当场 consume。
 */
@Composable
fun LiquidColorField(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onChange: (saturation: Float, brightness: Float) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    fieldHeight: Dp = 150.dp
) {
    val density = LocalDensity.current
    val isLight = !rememberGlassDarkTheme()
    val accessibility = rememberGlassAccessibilityMode()
    val glassBackdrop = LocalControlBackdrop.current?.takeIf { isBackdropSupported() }
    val animationScope = rememberCoroutineScope()
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    val puckPx = with(density) { PuckSize.toPx() }
    val fieldShape = RoundedRectangle(cornerRadius = 20.dp, style = RoundedCornerStyle.Continuous)

    // 这里只用它的按压光学（pressProgress / scaleX / scaleY / velocity）；
    // 位置直接由 (saturation, brightness) 算，直接操纵面不需要弹簧跟随。
    val dragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = saturation.fastCoerceIn(0f, 1f),
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = PressedPuckScale,
            onDragStarted = {},
            onDragStopped = {},
            onDrag = { _, _ -> }
        )
    }

    val fieldBackdrop = rememberLayerBackdrop()
    val scaledFieldBackdrop = rememberBackdrop(fieldBackdrop) { drawFieldBackdrop ->
        val progress = dragAnimation.pressProgress
        scale(lerp(2f / 3f, 0.75f, progress), lerp(0f, 0.75f, progress)) {
            drawFieldBackdrop()
        }
    }
    val puckBackdrop = if (glassBackdrop != null) {
        rememberCombinedBackdrop(glassBackdrop, scaledFieldBackdrop)
    } else {
        null
    }

    // API31/32：平台没有 AGSL，改用离屏 ES 2.0 做真折射（见 ThumbLens.kt）。
    // 底图 = 环境背景 + **未缩放**的色板层（为什么不跟那层按压缩放：见 ThumbLens.kt
    // 顶部）。锚点挂在外层容器上 —— 它不随摘钮移动。
    // 这里**不**像开关那样把 0.75 烤进底图（见 LiquidComponents.kt 里 switchLensAnchor
    // 的注释）。原因是两者的处境不同：
    //  - 开关的轨道是**纯色**，不缩就等于折射一片平场，屏幕上旋钮直接消失 ——
    //    那一步是"有没有效果"的问题；
    //  - 这块板是 150dp 的连续渐变，摘钮无论缩不缩都有内容可折射，差别只是
    //    放大率略有不同 —— 是"效果强弱"的问题。
    // 而要缩就得知道摘钮中心，它跟着 (saturation, brightness) 每帧动，写进重拍
    // 键就是拖动全程每帧重拍（一次约 5ms）。为一点放大率差付这个代价不值。
    // 实测按住摘钮：折射输出是 af88a8→b07ca6 的连续渐变（静止是平的 bd91b5），
    // 效果本身在。
    val fieldLensAnchor = if (glassBackdrop != null) {
        // 色板的内容跟着色相走：换色相整块板都变色，底图必须重拍。
        // 量化到 1 度，拖色相条时最多重拍 360 次而不是每帧。
        // 显式命名：DrawScope 里的 `density` 是 Float 成员，写 `density` 靠的是
        // 重载解析恰好挑中外层这个 Density。改个名就不必依赖那个巧合。
        val lensDensity = density
        rememberGlassLensRegion(tag = "colorfield", hue.toInt()) { coords ->
            with(glassBackdrop) { drawBackdrop(lensDensity, coords, null) }
            with(fieldBackdrop) { drawBackdrop(lensDensity, coords, null) }
        }
    } else {
        null
    }
    val puckLensMaterial = remember {
        // 库：lens(5dp * press, 10dp * press)
        thumbLensMaterial(refractionHeightDp = 5f, refractionAmountDp = 10f)
    }

    val pureHue = Color.hsv(hue.coerceIn(0f, 360f), 1f, 1f)
    val currentColor = Color.hsv(
        hue.coerceIn(0f, 360f),
        saturation.fastCoerceIn(0f, 1f),
        brightness.fastCoerceIn(0f, 1f)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(fieldHeight)
            .onSizeChanged { fieldSize = it }
            .glassLensAnchor(fieldLensAnchor)
            .pointerInput(fieldSize) {
                val width = fieldSize.width.toFloat()
                val height = fieldSize.height.toFloat()
                if (width <= 0f || height <= 0f) return@pointerInput
                // 摘钮半径内缩：圆心行程比板子小一个直径，摘钮才不会探出板外
                val travelX = (width - puckPx).coerceAtLeast(1f)
                val travelY = (height - puckPx).coerceAtLeast(1f)
                fun valuesAt(position: Offset): Pair<Float, Float> {
                    val s = ((position.x - puckPx / 2f) / travelX).fastCoerceIn(0f, 1f)
                    val v = 1f - ((position.y - puckPx / 2f) / travelY).fastCoerceIn(0f, 1f)
                    return s to v
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    dragAnimation.press()
                    val (s0, v0) = valuesAt(down.position)
                    dragAnimation.updateValue(s0)
                    onChange(s0, v0)
                    drag(down.id) { change ->
                        val (s, v) = valuesAt(change.position)
                        dragAnimation.updateValue(s)
                        onChange(s, v)
                        change.consume()
                    }
                    dragAnimation.release()
                    onChangeFinished()
                }
            },
        contentAlignment = Alignment.TopStart
    ) {
        Box(
            modifier = Modifier
                .layerBackdrop(fieldBackdrop)
                .clip(fieldShape)
                .drawBehind {
                    // 标准 HSV 方块：横向 白→纯色相，再叠一层纵向 透明→黑
                    drawRect(Brush.horizontalGradient(listOf(Color.White, pureHue)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                }
                .fillMaxSize()
        )

        val puckOffsetModifier = Modifier.graphicsLayer {
            val travelX = (fieldSize.width - puckPx).coerceAtLeast(0f)
            val travelY = (fieldSize.height - puckPx).coerceAtLeast(0f)
            translationX = travelX * saturation.fastCoerceIn(0f, 1f)
            translationY = travelY * (1f - brightness.fastCoerceIn(0f, 1f))
        }

        if (puckBackdrop != null) {
            Box(
                modifier = puckOffsetModifier
                    // 画在 drawBackdrop **之前**：折射结果就是摘钮的背景，
                    // 库那层的 surface / highlight / shadow 仍叠在上面。
                    .glassLens(
                        anchor = fieldLensAnchor,
                        optics = { w, h ->
                            thumbLensOptics(
                                material = puckLensMaterial,
                                density = density,
                                // 圆：圆角就是短边的一半
                                cornerRadiusPx = minOf(w, h) / 2f,
                                minDimensionPx = minOf(w, h),
                                press = dragAnimation.pressProgress
                            )
                        },
                        // 与下面 layerBlock 读**同一个**函数
                        scale = { _, _ ->
                            thumbLensTransform(
                                anim = dragAnimation,
                                velocityDivisor = 50f,
                                reduceMotion = accessibility.reduceMotion
                            )
                        }
                    )
                    .drawBackdrop(
                        backdrop = puckBackdrop,
                        shape = { CircleShape },
                        effects = {
                            val progress = dragAnimation.pressProgress
                            if (fieldLensAnchor == null) {
                                blur(8.dp.toPx() * (1f - progress))
                                lens(
                                    refractionHeight = 5.dp.toPx() * progress,
                                    refractionAmount = 10.dp.toPx() * progress,
                                    chromaticAberration = true
                                )
                            }
                            // 31/32：折射与色散都已由上面的 glassLens 画完。
                            // 那层 blur 也不能留 —— 它会把自家折射糊掉。
                        },
                        onDrawBackdrop = { drawBackdrop ->
                            // 31/32 上背景已由 glassLens 折射过，不能再画一遍
                            if (fieldLensAnchor == null) drawBackdrop()
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
                            Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.14f))
                        },
                        innerShadow = {
                            val progress = dragAnimation.pressProgress
                            InnerShadow(radius = 4.dp * progress, alpha = progress)
                        },
                        layerBlock = {
                            // 与上面 glassLens(scale) 读**同一个** thumbLensTransform，
                            // 两者不可能再脱钩
                            val t = thumbLensTransform(
                                anim = dragAnimation,
                                velocityDivisor = 50f,
                                reduceMotion = accessibility.reduceMotion
                            )
                            scaleX = t.scaleX
                            scaleY = t.scaleY
                        },
                        onDrawSurface = {
                            val surfaceAlpha = lerp(1f, 0f, dragAnimation.pressProgress)
                            drawCircle(Color.White.copy(alpha = surfaceAlpha))
                            drawCircle(
                                color = currentColor.copy(alpha = surfaceAlpha),
                                radius = size.minDimension / 2f - 3.dp.toPx()
                            )
                        }
                    )
                    .size(PuckSize)
            )
        } else {
            Box(
                modifier = puckOffsetModifier
                    .size(PuckSize)
                    .shadow(2.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(Color.White)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(currentColor)
            )
        }

        // 描边压在最上层：色板四角是白与黑，没有描边时板子会和弹窗底融在一起
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = 0.5.dp,
                    color = if (isLight) {
                        Color.Black.copy(alpha = 0.10f)
                    } else {
                        Color.White.copy(alpha = 0.18f)
                    },
                    shape = fieldShape
                )
        )
    }
}

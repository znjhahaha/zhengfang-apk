package com.tyust.course.ui.system.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import com.tyust.course.ui.system.GlassRecipe
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.tanh

/**
 * 液体玻璃的唯一交互状态源。
 *
 * 为什么必须唯一：按压同时要驱动形变、折射高度、色散强度、边缘光位置和邻居融合。
 * 这些量一旦各自 remember 一条动画，松手瞬间就会出现"缩放已经弹回、折射还在最大值"
 * 的错位；而 clickable 的 interactionSource 又会在快速点击时先发 Release 再发 Press，
 * 让某一条链卡在中间值上。所有量都从这里读，才能保证它们始终描述同一个物理时刻。
 *
 * 按下与松手用两条不同的弹簧，这是"跟手"和"Q 弹"能同时成立的原因：
 *   按下 -> 高刚度近临界阻尼，手指一碰就到位，不许回弹（回弹会让人觉得没按到）
 *   松手 -> 低阻尼，progress 冲过 0 变负，于是各处光学量都过冲一次再落回
 */
class InteractiveOptics(
    private val animationScope: CoroutineScope
) {
    private val pressAnimation = Animatable(0f, 0.001f)
    /**
     * 拖拽位移【目标值】。按住期间在 pointer 事件里【同步】写入，不经过 Animatable。
     *
     * 这里踩过一个坑：原先每来一个 move 事件就 `launch { animatable.snapTo(...) }`。
     * 每次 launch 都要等一次协程调度，而 Animatable.snapTo 还要抢 mutatorMutex、
     * 顺带取消上一次未完成的调用——于是位移比手指慢一帧且时快时慢，
     * 表现出来就是"卡顿"。所以目标值仍然直写；现在多了一层【渲染值】：
     *
     * [renderTravel] 用临界弹簧追目标（与底部 tab 滑块 liquidFollow 同参），
     * 获得 2-3 帧"推着有质量的东西走"的滞后；松手时带动能初速回零，
     * 甩一下和轻轻挪一下的回弹不再一样。[dragTravel] 读渲染值——玻璃层、
     * 内容层、融合桥三处同源，自动继承这套物理。
     */
    private var offsetState by mutableStateOf(Offset.Zero)
    private val renderTravel = Animatable(Offset.Zero, Offset.VectorConverter)
    private var travelFollowJob: Job? = null
    private var travelReleaseJob: Job? = null
    private var pointerState by mutableStateOf(Offset.Unspecified)
    private var velocityState by mutableFloatStateOf(0f)
    private var pressedState by mutableStateOf(false)
    private var velocitySettleJob: Job? = null
    private var startPosition = Offset.Zero

    /**
     * 0..1 按压能量。松手时因低阻尼会短暂变负（过冲），
     * 读取方按用途决定是否 coerce：形变要保留负值才有回弹，
     * 折射强度要 coerceAtLeast(0) 否则会反向。
     */
    val pressProgress: Float
        get() = pressAnimation.value

    /** 折射/色散等"强度"类用量：过冲段截断，避免负强度。 */
    val opticalProgress: Float
        get() = pressAnimation.value.coerceIn(0f, 1f)

    /** 手指是否仍在控件上。松手瞬间即为 false，与动画是否结束无关。 */
    val isPressed: Boolean
        get() = pressedState

    val pointerPosition: Offset
        get() = pointerState

    /** 指针相对按压起点的位移，释放后弹回零点。 */
    val offset: Offset
        get() = offsetState

    val velocityX: Float
        get() = velocityState

    val direction: Float
        get() = when {
            velocityX > 1f -> 1f
            velocityX < -1f -> -1f
            else -> 0f
        }

    fun motionIntensity(fullEffectVelocity: Float): Float =
        (abs(velocityX) / fullEffectVelocity.coerceAtLeast(1f)).coerceIn(0f, 1f)

    /**
     * 有界跟手行程：把原始拖拽位移压进半径 [maxTravelPx] 的圆内。
     *
     * `m * tanh(d / m)` 作用在【位移长度】上，不是分别作用在两个分量上。
     * 这一点是手感的关键：分量各自饱和时可达区域是个【正方形】，手指画圆时
     * 芯片走的是方形轨迹、在四个角上发滞，对角方向还能跑到 1.41m ——
     * 那就是"八向摇杆"的由来。按长度饱和后可达区域是正圆，各方向同性。
     *
     * 原点处导数为 1，所以小位移完全跟手，接近 m 时平滑停住。
     *
     * **玻璃层与内容层必须调用同一个函数、传同一个上限。** drawBackdrop 的
     * layerBlock 只变换被采样的玻璃层，内容不在其中；两边算出不同的行程，
     * 拖动时内容就会从玻璃里脱出。
     */
    fun dragTravel(maxTravelPx: Float): Offset {
        if (maxTravelPx <= 0f) return Offset.Zero
        val raw = renderTravel.value
        val distance = raw.getDistance()
        if (distance <= 0.01f) return Offset.Zero
        return raw * (maxTravelPx * tanh(distance / maxTravelPx) / distance)
    }

    /**
     * 触点在控件内的归一化横向位置（0=左缘, 0.5=中心, 1=右缘）。
     * 边缘高光靠它移动到手指下方；未按压时回到中心。
     */
    fun pointerFractionX(widthPx: Float): Float {
        if (!pressedState || pointerState == Offset.Unspecified || widthPx <= 0f) return 0.5f
        return (pointerState.x / widthPx).coerceIn(0f, 1f)
    }

    fun pointerFractionY(heightPx: Float): Float {
        if (!pressedState || pointerState == Offset.Unspecified || heightPx <= 0f) return 0.5f
        return (pointerState.y / heightPx).coerceIn(0f, 1f)
    }

    val gestureModifier: Modifier = Modifier.pointerInput(this) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial
            )
            var pointerId = down.id
            var previousPosition = down.position
            var previousTime = down.uptimeMillis

            velocitySettleJob?.cancel()
            travelReleaseJob?.cancel()
            travelFollowJob?.cancel()
            pointerState = down.position
            velocityState = 0f
            pressedState = true
            startPosition = down.position
            offsetState = Offset.Zero
            animationScope.launch {
                pressAnimation.animateTo(1f, PressDownSpring)
            }

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == pointerId }
                if (change == null || !change.pressed) break

                val elapsedMillis = (change.uptimeMillis - previousTime).coerceAtLeast(1L)
                val deltaX = change.position.x - previousPosition.x
                val instantaneousVelocity = deltaX * 1000f / elapsedMillis

                pointerState = change.position
                velocityState = velocityState * 0.56f + instantaneousVelocity * 0.44f
                // 目标值同步写入；渲染值由 ChipFollowSpring 追——每次 move 重新设定
                // 弹簧目标（与 tab 滑块 updateValue 同款 retarget），获得质量滞后
                offsetState = change.position - startPosition
                travelFollowJob?.cancel()
                travelFollowJob = animationScope.launch {
                    renderTravel.animateTo(offsetState, ChipFollowSpring)
                }
                previousPosition = change.position
                previousTime = change.uptimeMillis
                pointerId = change.id
            }

            pressedState = false
            // 位移、按压能量、速度三者在同一时刻一起回落，这样折射收缩、
            // 形变回弹和高光归位是同一个动作，而不是三段先后播放的动画。
            animationScope.launch {
                pressAnimation.animateTo(0f, ReleaseSpring)
            }
            // 松手带动能初速：甩动时渲染值过冲一次（Q 弹），轻挪则几乎直接归位
            travelFollowJob?.cancel()
            travelReleaseJob = animationScope.launch {
                val kick = (velocityState * 1000f * ChipReleaseVelocityKickScale)
                    .coerceIn(-ChipReleaseMaxKickPxPerSec, ChipReleaseMaxKickPxPerSec)
                renderTravel.animateTo(
                    Offset.Zero,
                    ChipReleaseTravelSpring,
                    initialVelocity = Offset(kick, 0f)
                )
                offsetState = Offset.Zero
            }
            val releaseVelocity = velocityState
            velocitySettleJob = animationScope.launch {
                Animatable(releaseVelocity).animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 260f)
                ) {
                    velocityState = value
                }
            }
        }
    }

    private companion object {
        /** 按下：几乎不过冲，追求瞬时到位。 */
        val PressDownSpring = spring<Float>(
            dampingRatio = 0.9f,
            stiffness = 900f,
            visibilityThreshold = 0.001f
        )

        /** 松手：低阻尼，过冲一次形成液体回弹。 */
        val ReleaseSpring = spring<Float>(
            dampingRatio = 0.42f,
            stiffness = 340f,
            visibilityThreshold = 0.001f
        )

        /**
         * 拖拽跟手弹簧：与底部 tab 滑块的 liquidFollow 同参——临界阻尼高刚度，
         * 只有 2-3 帧的"质量滞后"。原先渲染值直读目标值（0 帧延迟），
         * 响应最快但没有"推着东西走"的物理感。
         */
        private val ChipFollowSpring = spring(
            dampingRatio = 1f,
            stiffness = 1100f,
            visibilityThreshold = Offset.VisibilityThreshold
        )

        /** 松手位移回零：低阻尼 + 动能初速，甩动时过冲一次。 */
        private val ChipReleaseTravelSpring = spring(
            dampingRatio = 0.42f,
            stiffness = 340f,
            visibilityThreshold = Offset.VisibilityThreshold
        )

        /** 初速换算：拖速(px/ms) → px/s 后按此比例打折作为弹簧初速。 */
        private const val ChipReleaseVelocityKickScale = 0.05f

        /** 初速上限：7px 量级的行程给 120px/s 已经是明显的甩动过冲。 */
        private const val ChipReleaseMaxKickPxPerSec = 120f
    }
}

/**
 * 组件侧统一入口。之前各组件自己 rememberCoroutineScope + remember，
 * 收敛成一个函数避免作用域绑错导致的动画取消串联。
 */
@Composable
fun rememberInteractiveOptics(): InteractiveOptics {
    val animationScope = rememberCoroutineScope()
    return remember(animationScope) { InteractiveOptics(animationScope) }
}

/**
 * 按压挤压：横向压缩幅度取纵向的一半。
 *
 * 等比缩放在小控件上看起来像"整体后退"，而 iOS filled button 的手感是纵向
 * 压扁得更多，所以横向只压一半，读起来才是"被按下去"。
 *
 * 用在【没有 drawBackdrop 可用】的场合：adaptiveGlassChip 的无 backdrop 回退
 * 分支，以及 LiquidButton 的实色分支（实色不参与折射，本就没有玻璃层）。
 */
fun GraphicsLayerScope.applyPressSquash(progress: Float, depth: Float) {
    val p = progress.coerceIn(0f, 1f)
    val squash = depth * 0.5f
    scaleX = 1f - (depth - squash) * p
    scaleY = 1f - depth * p
}

/**
 * 玻璃层形变：跟手平移 + 按压涨 + 等体积各向异性。
 *
 * 装到 drawBackdrop 的 `layerBlock` 上——只有那里的矩阵变换不会把被折射的
 * 背景图像一起拉伸。外层 graphicsLayer 缩放会让折射内容跟着变形，成为假的
 * "纸片贴图拉伸"。
 */
fun GraphicsLayerScope.applyChipGlassDeformation(
    optics: InteractiveOptics,
    travelPx: Float,
    swellPx: Float,
    stretch: Float
) {
    val travel = optics.dragTravel(travelPx)
    translationX = travel.x
    translationY = travel.y

    val swell = 1f + optics.pressProgress * swellPx / size.height
    val (sx, sy) = anisotropy(travel, travelPx, stretch)
    // 速度驱动的等体积拉伸：快拖沿运动方向明显拉长、垂直方向等量收窄——
    // 没有它，快拖慢拖看起来一样，玻璃没有"重物在动"的视觉质量
    val velocityStretch = GlassRecipe.ChipVelocityStretch *
        optics.motionIntensity(GlassRecipe.ChipVelocityFullEffectMsPx)
    val (vx, vy) = if (abs(travel.x) >= abs(travel.y)) {
        velocityStretch to -velocityStretch
    } else {
        -velocityStretch to velocityStretch
    }
    scaleX = swell * (1f + sx + vx)
    scaleY = swell * (1f + sy + vy)
}

/**
 * 内容层形变：与玻璃【同一段行程、同向的各向异性】，但幅度打折、且按压是压扁
 * 而不是涨。
 *
 * 为什么内容不能照抄玻璃：玻璃是液体、图标是固体。等幅跟着拉伸会让整个按钮
 * 显得在橡皮化；完全不跟，横拖时玻璃变扁而图标仍是正圆，两者又会明显脱节。
 * 折一半是这两者之间唯一说得通的位置。
 */
fun GraphicsLayerScope.applyChipContentDeformation(
    optics: InteractiveOptics,
    travelPx: Float,
    stretch: Float,
    pressDepth: Float,
    damping: Float
) {
    val travel = optics.dragTravel(travelPx)
    translationX = travel.x
    translationY = travel.y

    val p = optics.pressProgress.coerceIn(0f, 1f)
    val (sx, sy) = anisotropy(travel, travelPx, stretch * damping)
    scaleX = (1f - pressDepth * 0.5f * p) * (1f + sx)
    scaleY = (1f - pressDepth * p) * (1f + sy)
}

/**
 * 等体积各向异性：沿运动方向拉长多少，垂直方向就收窄同样多。
 * 两个轴都只加不减会让芯片越拖越大，最后是一颗蛋而不是液滴。
 */
private fun anisotropy(travel: Offset, travelPx: Float, stretch: Float): Pair<Float, Float> {
    if (travelPx <= 0f || stretch == 0f) return 0f to 0f
    val ax = stretch * (abs(travel.x) / travelPx).coerceIn(0f, 1f)
    val ay = stretch * (abs(travel.y) / travelPx).coerceIn(0f, 1f)
    return (ax - ay) to (ay - ax)
}
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
    private val offsetAnimation = Animatable(
        Offset.Zero,
        Offset.VectorConverter,
        Offset.VisibilityThreshold
    )
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
        get() = offsetAnimation.value

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
     * 有界跟手行程：把原始拖拽位移压进 ±[maxTravelPx]。
     *
     * `m * tanh(x / m)` 在原点导数为 1，所以小位移是【完全跟手】的，
     * 接近 m 时平滑饱和，永远不会越过 m。这正是"能拖动但拖不走"的手感。
     *
     * **玻璃层与内容层必须调用同一个函数、传同一个上限。** drawBackdrop 的
     * layerBlock 只变换被采样的玻璃层，图标不在其中；两边算出不同的行程，
     * 拖动时图标就会从玻璃里脱出。
     */
    fun dragTravel(maxTravelPx: Float): Offset {
        if (maxTravelPx <= 0f) return Offset.Zero
        val raw = offset
        return Offset(
            maxTravelPx * tanh(raw.x / maxTravelPx),
            maxTravelPx * tanh(raw.y / maxTravelPx)
        )
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
            pointerState = down.position
            velocityState = 0f
            pressedState = true
            startPosition = down.position
            animationScope.launch {
                offsetAnimation.snapTo(Offset.Zero)
            }
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
                animationScope.launch {
                    offsetAnimation.snapTo(change.position - startPosition)
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
            animationScope.launch {
                offsetAnimation.animateTo(Offset.Zero, ReleaseOffsetSpring)
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

        val ReleaseOffsetSpring = spring(
            dampingRatio = 0.5f,
            stiffness = 300f,
            visibilityThreshold = Offset.VisibilityThreshold
        )
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
 * 两处复用它，都是【没有 drawBackdrop 可用】的场合——liquidChip 的形变发生在
 * drawBackdrop 的 layerBlock 里，那一层不存在时挤压只能自己挂 graphicsLayer：
 *   1. adaptiveGlassChip 的无 backdrop 回退分支
 *   2. LiquidButton 的实色分支（实色不参与折射，本就没有 backdrop 层）
 */
fun GraphicsLayerScope.applyPressSquash(progress: Float, depth: Float) {
    val p = progress.coerceIn(0f, 1f)
    val squash = depth * 0.5f
    scaleX = 1f - (depth - squash) * p
    scaleY = 1f - depth * p
}
package com.tyust.course.ui.system.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 统一维护按压能量、指针位置和横向速度；渲染层只读取状态，不参与手势判定。
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
    private var velocitySettleJob: Job? = null
    private var startPosition = Offset.Zero

    val pressProgress: Float
        get() = pressAnimation.value

    val pointerPosition: Offset
        get() = pointerState

    /** 官方 InteractiveHighlight 语义：当前指针相对按压起点的位移，释放后弹回零点。 */
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
            startPosition = down.position
            animationScope.launch {
                offsetAnimation.snapTo(Offset.Zero)
            }
            animationScope.launch {
                pressAnimation.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = 0.72f,
                        stiffness = 520f,
                        visibilityThreshold = 0.001f
                    )
                )
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

            animationScope.launch {
                pressAnimation.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 180)
                )
            }
            animationScope.launch {
                offsetAnimation.animateTo(
                    targetValue = Offset.Zero,
                    animationSpec = spring(
                        dampingRatio = 0.5f,
                        stiffness = 300f,
                        visibilityThreshold = Offset.VisibilityThreshold
                    )
                )
            }
            val releaseVelocity = velocityState
            velocitySettleJob = animationScope.launch {
                Animatable(releaseVelocity).animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 200)
                ) {
                    velocityState = value
                }
            }
        }
    }
}
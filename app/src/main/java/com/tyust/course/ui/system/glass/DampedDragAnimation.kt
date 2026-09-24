package com.tyust.course.ui.system.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastCoerceIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import android.os.SystemClock

class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    private val settleAnimationSpec: AnimationSpec<Float> =
        spring(0.5f, 340f, visibilityThreshold),
    private val releaseScaleAnimationSpec: AnimationSpec<Float> =
        spring(0.34f, 280f, 0.001f),
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
    val onDragCancelled: DampedDragAnimation.() -> Unit = {},
    private val pressScaleAnimationSpec: AnimationSpec<Float>? = null,
) {
    // tint/色散转玻璃过渡：略降刚度让\"实色→玻璃\"更平滑，不突兀。
    private val pressProgressAnimationSpec = spring(0.85f, 360f, 0.001f)
    // 按下阶段：较高阻尼，缩放跟手贴合，不抖。
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val velocityTracker = VelocityTracker()
    private var interactionJob: Job? = null
    private var valueJob: Job? = null
    private var requestedValue by mutableFloatStateOf(initialValue)
    private var dragging by mutableStateOf(false)
    private var dragValue by mutableFloatStateOf(initialValue)
    private var dragVelocity by mutableFloatStateOf(0f)
    private var reducedMotion = false
    private var gestureStartValue = initialValue
    private var pointerUptimeMillis: Long? = null

    val value: Float get() = when {
        reducedMotion -> requestedValue
        dragging -> dragValue
        else -> valueAnimation.value
    }
    val targetValue: Float get() = requestedValue
    val pressProgress: Float get() = if (reducedMotion) 0f else pressProgressAnimation.value
    val scaleX: Float get() = if (reducedMotion) initialScale else scaleXAnimation.value
    val scaleY: Float get() = if (reducedMotion) initialScale else scaleYAnimation.value
    val velocity: Float get() = positionVelocity / (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.001f)
    val positionVelocity: Float get() = when {
        reducedMotion -> 0f
        dragging -> dragVelocity
        else -> valueAnimation.velocity
    }

    fun setReducedMotion(reduced: Boolean, selectedValue: Float = targetValue) {
        reducedMotion = reduced
        if (reduced) snapToValue(selectedValue)
    }

    private fun snapToValue(value: Float) {
        requestedValue = value.coerceIn(valueRange)
        interactionJob?.cancel()
        valueJob?.cancel()
        interactionJob = animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            valueAnimation.snapTo(requestedValue)
            dragging = false
            dragVelocity = 0f
            pressProgressAnimation.snapTo(0f)
            scaleXAnimation.snapTo(initialScale)
            scaleYAnimation.snapTo(initialScale)
        }
    }

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                gestureStartValue = targetValue
                press(down.uptimeMillis)
                onDragStarted(down.position)
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                animateToValue(gestureStartValue)
                onDragCancelled()
            }
        ) { change, dragAmount ->
            pointerUptimeMillis = change.uptimeMillis
            try { onDrag(size, dragAmount) } finally { pointerUptimeMillis = null }
        }
    }

    fun press(uptimeMillis: Long = SystemClock.uptimeMillis()) {
        val current = value
        val currentVelocity = positionVelocity
        interactionJob?.cancel()
        valueJob?.cancel()
        dragValue = current
        dragVelocity = currentVelocity
        dragging = true
        velocityTracker.resetTracking()
        velocityTracker.addPosition(uptimeMillis, Offset(current, 0f))
        if (reducedMotion) return
        interactionJob = animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, pressScaleAnimationSpec ?: scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, pressScaleAnimationSpec ?: scaleYAnimationSpec) }
        }
    }

    fun release() {
        settleTo(targetValue, animatePress = false)
    }

    /** Choose a release destination without replacing the currently drawn position. */
    fun updateTarget(value: Float) {
        requestedValue = value.coerceIn(valueRange)
    }

    /** The finger owns position. Springs are only used after release or for a tap. */
    fun updateValue(value: Float, uptimeMillis: Long = pointerUptimeMillis ?: SystemClock.uptimeMillis()) {
        val targetValue = value.coerceIn(valueRange)
        requestedValue = targetValue
        dragValue = targetValue
        dragging = true
        velocityTracker.addPosition(uptimeMillis, Offset(targetValue, 0f))
        dragVelocity = velocityTracker.calculateVelocity().x
    }

    fun animateToValue(value: Float) {
        settleTo(value, animatePress = true)
    }

    private fun settleTo(value: Float, animatePress: Boolean) {
        val target = value.coerceIn(valueRange)
        val current = this.value
        val currentVelocity = positionVelocity
        requestedValue = target
        if (reducedMotion) { snapToValue(target); return }
        interactionJob?.cancel()
        valueJob?.cancel()
        interactionJob = animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Publish the spring at the last drawn position before handing ownership back.
            valueAnimation.snapTo(current)
            dragging = false
            coroutineScope {
                if (animatePress) {
                    launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                    launch { scaleXAnimation.animateTo(pressedScale, pressScaleAnimationSpec ?: scaleXAnimationSpec) }
                    launch { scaleYAnimation.animateTo(pressedScale, pressScaleAnimationSpec ?: scaleYAnimationSpec) }
                }
                valueJob = launch {
                    valueAnimation.animateTo(target, settleAnimationSpec, initialVelocity = currentVelocity)
                }
                launch {
                    awaitReleaseGate()
                    startReleaseAnimations(this)
                }
            }
        }
    }

    /** 滑动过大半程即开始褪光缩小，避免全亮白环拖出长残影。 */
    private suspend fun awaitReleaseGate() {
        withFrameNanos { }
        if (value != targetValue) {
            val threshold = maxOf(visibilityThreshold, (valueRange.endInclusive - valueRange.start) * 0.15f)
            snapshotFlow { valueAnimation.value }
                .filter { abs(it - targetValue) <= threshold }
                .first()
        }
    }

    private fun startReleaseAnimations(scope: CoroutineScope) {
        scope.launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
        scope.launch { scaleXAnimation.animateTo(initialScale, releaseScaleAnimationSpec) }
        scope.launch { scaleYAnimation.animateTo(initialScale, releaseScaleAnimationSpec) }
    }

}

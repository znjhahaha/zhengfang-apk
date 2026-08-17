package com.tyust.course.ui.system.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastCoerceIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Clock

class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    private val directManipulationSpec: AnimationSpec<Float> =
        spring(1f, 1000f, visibilityThreshold),
    private val settleAnimationSpec: AnimationSpec<Float> =
        spring(0.5f, 340f, visibilityThreshold),
    private val releaseScaleAnimationSpec: AnimationSpec<Float> =
        spring(0.34f, 280f, 0.001f),
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    // tint/色散转玻璃过渡：略降刚度让\"实色→玻璃\"更平滑，不突兀。
    private val pressProgressAnimationSpec = spring(0.85f, 360f, 0.001f)
    // 按下阶段：较高阻尼，缩放跟手贴合，不抖。
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            }
        ) { change, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            awaitReleaseGate()
            startReleaseAnimations(this)
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            launch { valueAnimation.animateTo(targetValue, directManipulationSpec) { updateVelocity() } }
        }
    }

    fun animateToValue(value: Float) {
        val target = value.coerceIn(valueRange)
        animationScope.launch {
            mutatorMutex.mutate {
                // 结构化会话：增亮与位移并行推进，褪光协程等 value 走过大半程就启动，
                // 于是"褪光"与"位移"是重叠的，而不是等位移完全结束才回弹。
                coroutineScope {
                    launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                    launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
                    launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
                    launch {
                        // value 被另一路 animateTo 抢占是正常竞争，必须就地消化：
                        // 一旦让它逃逸出去，会连坐取消同作用域的褪光协程，
                        // 控件就永久卡在按下态（放大 + 表面透明）。
                        try {
                            valueAnimation.animateTo(target, settleAnimationSpec) {
                                updateVelocity()
                            }
                        } catch (_: CancellationException) {
                            currentCoroutineContext().ensureActive()
                        }
                    }
                    if (velocity != 0f) {
                        launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                    }
                    launch {
                        awaitReleaseGate()
                        startReleaseAnimations(this)
                    }
                }
            }
        }
    }

    /** 滑动过大半程即开始褪光缩小，避免全亮白环拖出长残影。 */
    private suspend fun awaitReleaseGate() {
        awaitFrame()
        if (value != targetValue) {
            val threshold = (valueRange.endInclusive - valueRange.start) * 0.15f
            snapshotFlow { valueAnimation.value }
                .filter { abs(it - valueAnimation.targetValue) < threshold }
                .first()
        }
    }

    private fun startReleaseAnimations(scope: CoroutineScope) {
        scope.launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
        scope.launch { scaleXAnimation.animateTo(initialScale, releaseScaleAnimationSpec) }
        scope.launch { scaleYAnimation.animateTo(initialScale, releaseScaleAnimationSpec) }
    }

    private fun updateVelocity() {
        val valueSpan = valueRange.endInclusive - valueRange.start
        if (valueSpan <= 0f) {
            animationScope.launch { velocityAnimation.snapTo(0f) }
            return
        }
        velocityTracker.addPosition(
            Clock.System.now().toEpochMilliseconds(),
            Offset(value, 0f)
        )
        val targetVelocity = velocityTracker.calculateVelocity().x / valueSpan
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}

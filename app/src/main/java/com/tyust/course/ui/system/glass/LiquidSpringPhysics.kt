package com.tyust.course.ui.system.glass

import kotlin.math.ceil
import kotlin.math.sqrt

/** Semi-implicit mass-spring-damper integration that is independent of Android duration scales. */
internal object LiquidSpringPhysics {
    data class State(
        val position: Float,
        val velocity: Float
    )

    fun step(
        state: State,
        target: Float,
        stiffness: Float,
        dampingRatio: Float,
        deltaSeconds: Float
    ): State {
        val clampedStiffness = stiffness.coerceAtLeast(0.001f)
        val clampedDamping = dampingRatio.coerceAtLeast(0f)
        val totalDelta = deltaSeconds.coerceIn(0f, MAX_FRAME_DELTA_SECONDS)
        if (totalDelta <= 0f) return state

        val substeps = ceil(totalDelta / MAX_SUBSTEP_SECONDS).toInt().coerceAtLeast(1)
        val dt = totalDelta / substeps
        val damping = 2f * clampedDamping * sqrt(clampedStiffness)
        var position = state.position
        var velocity = state.velocity
        repeat(substeps) {
            val acceleration =
                -clampedStiffness * (position - target) - damping * velocity
            velocity += acceleration * dt
            position += velocity * dt
        }
        return State(position = position, velocity = velocity)
    }

    private const val MAX_SUBSTEP_SECONDS = 1f / 120f
    private const val MAX_FRAME_DELTA_SECONDS = 0.1f
}

package com.tyust.course.ui.system.glass

/**
 * Two-degree-of-freedom mass-spring model for the liquid picker.
 *
 * Opening stretches volume faster than it moves the lower mass away from the header. This creates
 * the reference cell-division order: bud, elongate, neck, detach. Closing gives the lower boundary
 * a stiffer spring so the panel retracts from the bottom first, then its remaining mass collides
 * with the anchored header and the top axis carries the physical overshoot.
 */
internal object LiquidPickerMotionPhysics {
    data class Axis(
        val position: Float,
        val velocity: Float
    )

    data class State(
        val travel: Axis,
        val extent: Axis
    ) {
        companion object {
            fun collapsed() = State(
                travel = Axis(position = 0f, velocity = 0f),
                extent = Axis(position = 0f, velocity = 0f)
            )

            fun expanded() = State(
                travel = Axis(position = 1f, velocity = 0f),
                extent = Axis(position = 1f, velocity = 0f)
            )
        }
    }

    fun step(
        state: State,
        expanded: Boolean,
        deltaSeconds: Float
    ): State {
        val target = if (expanded) 1f else 0f
        val travelSpec = if (expanded) OPEN_TRAVEL_SPEC else CLOSE_TRAVEL_SPEC
        val extentSpec = if (expanded) OPEN_EXTENT_SPEC else CLOSE_EXTENT_SPEC

        return State(
            travel = state.travel.step(target, travelSpec, deltaSeconds),
            extent = state.extent.step(target, extentSpec, deltaSeconds)
        )
    }

    fun isAtRest(state: State, expanded: Boolean): Boolean {
        val target = if (expanded) 1f else 0f
        return state.travel.isAtRest(target) && state.extent.isAtRest(target)
    }

    private fun Axis.step(
        target: Float,
        spec: SpringSpec,
        deltaSeconds: Float
    ): Axis {
        val next = LiquidSpringPhysics.step(
            state = LiquidSpringPhysics.State(position, velocity),
            target = target,
            stiffness = spec.stiffness,
            dampingRatio = spec.dampingRatio,
            deltaSeconds = deltaSeconds
        )
        return Axis(position = next.position, velocity = next.velocity)
    }

    private fun Axis.isAtRest(target: Float): Boolean =
        kotlin.math.abs(position - target) < POSITION_EPSILON &&
            kotlin.math.abs(velocity) < VELOCITY_EPSILON

    private data class SpringSpec(
        val stiffness: Float,
        val dampingRatio: Float
    )

    // The travel mass keeps the longer tail while volume stretches rapidly out of the pill.
    private val OPEN_TRAVEL_SPEC = SpringSpec(stiffness = 190f, dampingRatio = 0.62f)
    private val OPEN_EXTENT_SPEC = SpringSpec(stiffness = 650f, dampingRatio = 0.62f)

    // The anchored top closes quickly and remains underdamped enough to carry collision momentum.
    private val CLOSE_TRAVEL_SPEC = SpringSpec(stiffness = 410f, dampingRatio = 0.55f)

    // The lower boundary is lighter/stiffer, so the menu loses height before it reaches the pill.
    private val CLOSE_EXTENT_SPEC = SpringSpec(stiffness = 540f, dampingRatio = 0.65f)

    private const val POSITION_EPSILON = 0.0025f
    private const val VELOCITY_EPSILON = 0.035f
}

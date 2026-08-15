package com.tyust.course.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Motion Design Tokens for unified animation timing and easing across the app.
 *
 * Based on Material Design 3 motion principles:
 * - Fast: Immediate feedback (100ms)
 * - Medium: Standard transitions (200ms)
 * - Slow: Emphasized transitions (400ms)
 */
object MotionDuration {
    /** Immediate feedback for micro-interactions */
    const val Fast = 100

    /** Standard UI transitions */
    const val Medium = 200

    /** Emphasized, attention-grabbing transitions */
    const val Slow = 400

    /** Tab switching transitions */
    const val TabTransition = 220

    /** Dialog enter/exit animations */
    const val DialogEnter = 400

    /** Expand/collapse animations */
    const val ExpandCollapse = 250

    /** Moving indicator for bottom navigation */
    const val NavIndicator = 260

    /** Selected label reveal in bottom navigation */
    const val NavLabel = 180

    /** Repeating emphasis pulse for highlighted UI */
    const val EmphasisPulse = 1400
}

object MotionEasing {
    /** Standard easing for most transitions (Material 3 default) */
    val Standard = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    /** Emphasized easing for dramatic transitions */
    val Emphasized = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    /** Decelerate easing for elements entering */
    val FastOutSlowIn = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

    /** Linear easing for continuous motion */
    val Linear = CubicBezierEasing(0.0f, 0.0f, 1.0f, 1.0f)

    /** Accelerate easing for elements exiting */
    val Accelerate = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)
}

object MotionSpring {
    /** Bouncy spring for playful animations (damping 0.6, stiffness 200) */
    fun <T> bounce() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )

    /** Snappy spring for quick, decisive animations */
    fun <T> snappy() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    /** Gentle spring for smooth, natural animations */
    fun <T> gentle() = spring<T>(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessLow
    )

    /** Extra bouncy spring for expressive animations */
    fun <T> extraBouncy() = spring<T>(
        dampingRatio = 0.5f,
        stiffness = 300f
    )

    /** Liquid-control tap: visible overshoot without a long wobble. */
    fun <T> liquidTap() = spring<T>(
        dampingRatio = 0.62f,
        stiffness = 460f
    )

    /** Liquid-control settle: a single restrained overshoot when the capsule snaps to a tab. */
    fun <T> liquidSettle() = spring<T>(
        dampingRatio = 0.80f,
        stiffness = 420f
    )

    /** Segmented settle: decisive snap with a single restrained jelly overshoot. */
    fun <T> segmentedSettle() = spring<T>(
        dampingRatio = 0.78f,
        stiffness = 460f
    )

    /** Segmented release: return to rest with a slight jelly rebound. */
    fun <T> segmentedRelease() = spring<T>(
        dampingRatio = 0.80f,
        stiffness = 520f
    )

    /** Nav settle: long travel across the bar wants a pronounced jelly overshoot. */
    fun <T> navSettle() = spring<T>(
        dampingRatio = 0.60f,
        stiffness = 310f
    )

    /** Nav release: springy scale relaxation matching the jelly slide. */
    fun <T> navRelease() = spring<T>(
        dampingRatio = 0.55f,
        stiffness = 290f
    )

    /** Liquid selection release: one gentle bounce for generic liquid controls. */
    fun <T> liquidSelectionRelease() = spring<T>(
        dampingRatio = 0.72f,
        stiffness = 360f
    )

    /** Jelly rebound for the track/panel drifting back to rest after a drag or tab switch. */
    fun liquidJellyRebound() = spring(
        dampingRatio = 0.66f,
        stiffness = 320f,
        visibilityThreshold = 0.5f
    )

    /** Liquid-control follow: high damping keeps direct manipulation attached to the finger. */
    fun <T> liquidFollow() = spring<T>(
        dampingRatio = 1f,
        stiffness = 1100f
    )

    /** Anchored menu reveal: soft enough to show depth, short enough to stay responsive. */
    fun <T> liquidMenu() = spring<T>(
        dampingRatio = 0.78f,
        stiffness = 520f
    )
}

/**
 * Convenience tween specs using motion tokens
 */
object MotionSpecs {
    /** Standard tween with default duration and easing */
    fun <T> standard(duration: Int = MotionDuration.Medium) = tween<T>(
        durationMillis = duration,
        easing = MotionEasing.Standard
    )

    /** Emphasized tween for important transitions */
    fun <T> emphasized(duration: Int = MotionDuration.Slow) = tween<T>(
        durationMillis = duration,
        easing = MotionEasing.Emphasized
    )

    /** Tab transition tween */
    fun <T> tabTransition() = tween<T>(
        durationMillis = MotionDuration.TabTransition,
        easing = MotionEasing.Standard
    )

    /** Dialog enter tween */
    fun <T> dialogEnter() = tween<T>(
        durationMillis = MotionDuration.DialogEnter,
        easing = MotionEasing.FastOutSlowIn
    )

    /** Expand/collapse tween */
    fun <T> expandCollapse() = tween<T>(
        durationMillis = MotionDuration.ExpandCollapse,
        easing = MotionEasing.FastOutSlowIn
    )

    /** Sliding active pill in bottom navigation */
    fun <T> navIndicator() = tween<T>(
        durationMillis = MotionDuration.NavIndicator,
        easing = MotionEasing.Emphasized
    )

    /** Selected label appearance in bottom navigation */
    fun <T> navLabel() = tween<T>(
        durationMillis = MotionDuration.NavLabel,
        easing = MotionEasing.FastOutSlowIn
    )
}

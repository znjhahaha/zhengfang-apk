package com.tyust.course.ui.system

import kotlin.math.PI
import kotlin.math.sin

/** Bounded C1 gestures: the final frame is the resting glyph, including its velocity. */
internal object NavigationIconMotion {
    fun envelope(t: Float): Float {
        if (t <= 0f || t >= 1f) return 0f
        val wave = sin(PI * t).toFloat()
        return wave * wave
    }

    fun flourish(t: Float): Float = envelope(t) * (1f - 1.25f * t) * 1.8f

    fun charge(t: Float): Float = sin(2.0 * PI * t).toFloat() * envelope(t)

    fun barScale(t: Float): Float = 1f - 0.68f * flourish(t)
}

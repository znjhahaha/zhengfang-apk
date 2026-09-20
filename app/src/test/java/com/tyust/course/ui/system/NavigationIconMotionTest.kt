package com.tyust.course.ui.system

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class NavigationIconMotionTest {
    @Test fun gesturesHaveIdenticalRestFramesAndZeroEndpointVelocity() {
        val gestures = listOf<(Float) -> Float>(
            NavigationIconMotion::envelope, NavigationIconMotion::flourish,
            NavigationIconMotion::charge, NavigationIconMotion::barScale
        )
        gestures.forEach { motion ->
            assertEquals(motion(0f), motion(1f), 0f)
            val dt = 0.0001f
            assertTrue(abs((motion(dt) - motion(0f)) / dt) < 0.003f)
            assertTrue(abs((motion(1f) - motion(1f - dt)) / dt) < 0.003f)
        }
    }

    @Test fun chartStaysInsideItsMotionBoundsWithoutPiecewiseVelocityJumps() {
        val dt = 0.001f
        for (frame in 1 until 999) {
            val t = frame / 1000f
            val scale = NavigationIconMotion.barScale(t)
            assertTrue(scale in 0.4f..1.1f)
            val before = (scale - NavigationIconMotion.barScale(t - dt)) / dt
            val after = (NavigationIconMotion.barScale(t + dt) - scale) / dt
            assertTrue("velocity jump at $t", abs(after - before) < 0.05f)
        }
    }
}

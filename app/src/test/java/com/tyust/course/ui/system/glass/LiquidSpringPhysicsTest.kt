package com.tyust.course.ui.system.glass

import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidSpringPhysicsTest {
    @Test
    fun openingMatchesReferenceOvershootAndSettleWindow() {
        var state = LiquidSpringPhysics.State(position = 0f, velocity = 0f)
        val samples = ArrayList<LiquidSpringPhysics.State>()
        repeat(36) {
            state = LiquidSpringPhysics.step(
                state = state,
                target = 1f,
                stiffness = 190f,
                dampingRatio = 0.62f,
                deltaSeconds = 1f / 60f
            )
            samples += state
        }

        val peakIndex = samples.indices.maxBy { samples[it].position }
        val peak = samples[peakIndex].position
        assertTrue("峰值帧 ${peakIndex + 1}", peakIndex + 1 in 14..21)
        assertTrue("峰值 $peak", peak in 1.04f..1.11f)
        assertTrue("36 帧应视觉稳定: ${samples.last()}", samples.last().position in 0.99f..1.01f)
    }

    @Test
    fun closingReturnsToPillWithinReferenceWindow() {
        var state = LiquidSpringPhysics.State(position = 1f, velocity = 0f)
        repeat(24) {
            state = LiquidSpringPhysics.step(
                state = state,
                target = 0f,
                stiffness = 230f,
                dampingRatio = 0.74f,
                deltaSeconds = 1f / 60f
            )
        }
        assertTrue("24 帧时应回到胶囊附近: $state", state.position in -0.025f..0.025f)
    }

    @Test
    fun frameDropSubstepsStayCloseToRegularFrames() {
        var regular = LiquidSpringPhysics.State(0f, 0f)
        repeat(6) {
            regular = LiquidSpringPhysics.step(regular, 1f, 190f, 0.62f, 1f / 60f)
        }
        val dropped = LiquidSpringPhysics.step(
            state = LiquidSpringPhysics.State(0f, 0f),
            target = 1f,
            stiffness = 190f,
            dampingRatio = 0.62f,
            deltaSeconds = 0.1f
        )
        assertTrue(kotlin.math.abs(regular.position - dropped.position) < 0.04f)
        assertTrue(kotlin.math.abs(regular.velocity - dropped.velocity) < 0.45f)
    }
}

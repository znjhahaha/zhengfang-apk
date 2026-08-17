package com.tyust.course.ui.system.glass

import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidPickerMotionPhysicsTest {
    @Test
    fun openingStretchesVolumeBeforeTheBodyDetaches() {
        var state = LiquidPickerMotionPhysics.State.collapsed()
        val samples = ArrayList<LiquidPickerMotionPhysics.State>()

        repeat(36) {
            state = LiquidPickerMotionPhysics.step(
                state = state,
                expanded = true,
                deltaSeconds = 1f / 60f
            )
            samples += state
        }

        val peakIndex = samples.indices.maxBy { samples[it].travel.position }
        val peak = samples[peakIndex].travel.position
        assertTrue("峰值帧 ${peakIndex + 1}", peakIndex + 1 in 14..21)
        assertTrue("峰值 $peak", peak in 1.04f..1.11f)
        assertTrue(
            "36 帧应视觉稳定: ${samples.last()}",
            samples.last().travel.position in 0.99f..1.01f
        )
        assertTrue(
            "第 4 帧体积应先于位移轴快速拉长: ${samples[3]}",
            samples[3].extent.position in 0.70f..0.75f &&
                samples[3].extent.position > samples[3].travel.position + 0.38f
        )
        assertTrue(
            "第 6 帧体积应接近完整高度: ${samples[5].extent.position}",
            samples[5].extent.position in 0.95f..1.00f
        )
        val extentPeakIndex = samples.indices.maxBy { samples[it].extent.position }
        assertTrue("体积回弹峰值帧 ${extentPeakIndex + 1}", extentPeakIndex + 1 in 7..10)
        assertTrue(
            "体积回弹峰值 ${samples[extentPeakIndex].extent.position}",
            samples[extentPeakIndex].extent.position in 1.04f..1.08f
        )
    }

    @Test
    fun closingRetractsTheBottomBeforeTheBodyHitsTheHeader() {
        var state = LiquidPickerMotionPhysics.State.expanded()
        repeat(3) {
            state = LiquidPickerMotionPhysics.step(
                state = state,
                expanded = false,
                deltaSeconds = 1f / 60f
            )
        }

        val contactProgress = 24f / (24f + 12f)
        assertTrue(
            "第 3 帧菜单顶部应进入更深的碰撞区: ${state.travel.position}",
            state.travel.position <= contactProgress
        )
        assertTrue(
            "底边自由度应比顶部自由度回收更快: $state",
            state.extent.position < state.travel.position - 0.045f
        )
        assertTrue(
            "碰撞时仍需保留足够体积形成吸收形变: ${state.extent.position}",
            state.extent.position in 0.46f..0.58f
        )
    }

    @Test
    fun closingAbsorbsTheBodyWithinTheReferenceWindow() {
        var state = LiquidPickerMotionPhysics.State.expanded()
        val samples = ArrayList<LiquidPickerMotionPhysics.State>()
        repeat(36) {
            state = LiquidPickerMotionPhysics.step(
                state = state,
                expanded = false,
                deltaSeconds = 1f / 60f
            )
            samples += state
        }

        assertTrue(
            "第 8 帧菜单体积应已被吸收: ${samples[7].extent.position}",
            samples[7].extent.position < 0.01f
        )
        assertTrue(
            "顶部质量应明显越过静止点，形成更长的碰撞拉伸: ${samples.minOf { it.travel.position }}",
            samples.minOf { it.travel.position } in -0.13f..-0.07f
        )
        assertTrue(
            "第 18 帧顶部仍应携带可见尾部能量: ${samples[17].travel}",
            kotlin.math.abs(samples[17].travel.position) > 0.0005f &&
                kotlin.math.abs(samples[17].travel.velocity) > 0.35f
        )
        assertTrue(
            "第 36 帧应基本稳定: ${samples.last().travel}",
            kotlin.math.abs(samples.last().travel.position) < 0.0025f &&
                kotlin.math.abs(samples.last().travel.velocity) < 0.035f
        )
    }

    @Test
    fun droppedFrameIntegrationStaysCloseToRegularFrames() {
        var regular = LiquidPickerMotionPhysics.State.expanded()
        repeat(6) {
            regular = LiquidPickerMotionPhysics.step(
                state = regular,
                expanded = false,
                deltaSeconds = 1f / 60f
            )
        }
        val dropped = LiquidPickerMotionPhysics.step(
            state = LiquidPickerMotionPhysics.State.expanded(),
            expanded = false,
            deltaSeconds = 0.1f
        )

        assertTrue(kotlin.math.abs(regular.travel.position - dropped.travel.position) < 0.04f)
        assertTrue(kotlin.math.abs(regular.extent.position - dropped.extent.position) < 0.04f)
        assertTrue(kotlin.math.abs(regular.travel.velocity - dropped.travel.velocity) < 0.55f)
        assertTrue(kotlin.math.abs(regular.extent.velocity - dropped.extent.velocity) < 0.55f)
    }
}

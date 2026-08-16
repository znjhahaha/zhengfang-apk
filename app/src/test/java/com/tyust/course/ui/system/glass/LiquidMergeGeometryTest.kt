package com.tyust.course.ui.system.glass

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LiquidMergeGeometryTest {
    // 抢课顶栏真实量级：38dp 芯片 (r≈50px) + 4dp 间距 (gap≈10px)，这里取整数便于断言
    private val left = LiquidMergeGeometry.MergeCircle(Offset(100f, 100f), 50f)
    private val right = LiquidMergeGeometry.MergeCircle(Offset(220f, 100f), 50f)
    private val gap = 220f - 100f - 50f - 50f // 20

    @Test
    fun smin_degeneratesToMinAndSmothesAtEquality() {
        assertEquals(-3f, LiquidMergeGeometry.smin(-3f, 7f, 0f), 0f)
        assertEquals(-3f, LiquidMergeGeometry.smin(-3f, 7f, 0.0001f), 0.01f)
        // a = b 时凸出 k/4（融合方向外扩的来源）
        assertEquals(-10f - 25f, LiquidMergeGeometry.smin(-10f, -10f, 100f), 0.001f)
        // 永远不超过 min
        assertTrue(LiquidMergeGeometry.smin(-50f, 70f, 40f) <= -50f)
        assertTrue(LiquidMergeGeometry.smin(70f, -50f, 40f) <= -50f)
    }

    @Test
    fun mergeOutline_noNeckBelowBirthThreshold() {
        // 出生点 k = 2×边距；低于它中点场值为正，两圆不相连
        assertNull(LiquidMergeGeometry.mergeOutlinePoints(left, right, 1.9f * gap))
        assertNull(LiquidMergeGeometry.mergeOutlinePoints(left, right, 0.5f * gap))
    }

    @Test
    fun mergeOutline_birthIsAFilament() {
        val k = 2.02f * gap
        val waist = LiquidMergeGeometry.waistHalfWidth(left, right, k)
        assertNotNull(waist)
        // 出生即细丝：腰半宽明显小于半径
        assertTrue("出生腰半宽 $waist 应小于 0.35r", waist!! < 0.35f * 50f)
    }

    @Test
    fun mergeOutline_fullStrengthReachesConcaveWaist() {
        val k = 2.0f * gap + 0.24f * 50f
        val waist = LiquidMergeGeometry.waistHalfWidth(left, right, k)
        assertNotNull(waist)
        // 压满腰宽约为直径 3/4：半宽 ≈ 0.375r，且必须内凹（小于端点半宽 = 圆半径）
        assertTrue("压满腰半宽 $waist", waist!! in 0.30f * 50f..0.48f * 50f)
        assertTrue("腰必须细于圆半径（内凹）", waist < 50f)
    }

    @Test
    fun waistGrowsMonotonicallyWithK() {
        var previous = -1f
        var k = 2.02f * gap
        while (k <= 2f * gap + 60f) {
            val waist = LiquidMergeGeometry.waistHalfWidth(left, right, k) ?: break
            assertTrue("k=$k 腰宽应单调增长 ($waist <= $previous)", waist >= previous)
            previous = waist
            k += 4f
        }
        assertTrue("至少应采样到几个增长点", previous > 0f)
    }

    @Test
    fun mergeOutline_producesSymmetricClosedLoop() {
        val points = LiquidMergeGeometry.mergeOutlinePoints(
            left, right, 2.0f * gap + 0.24f * 50f, stationCount = 16
        )
        assertNotNull(points)
        assertEquals(32, points!!.size)
        // 水平等圆配置：上下缘关于连心线 (y=100) 镜像，同站 x 相同。
        // 屏幕坐标系 y 向下，首半段是下缘、后半段是上缘——只断言镜像与异侧。
        for (i in 0 until 16) {
            val first = points[i]
            val mirror = points[31 - i]
            assertEquals(first.x, mirror.x, 0.001f)
            assertEquals(200f, first.y + mirror.y, 0.001f)
            assertTrue(first.y > 100f && mirror.y < 100f || first.y < 100f && mirror.y > 100f)
        }
        // 端站落在圆心正上/下方（竖直端边藏进芯片）
        assertEquals(100f, points.first().x, 0.001f)
        assertEquals(100f, points.last().x, 0.001f)
    }

    @Test
    fun mergeOutline_staysInsideFieldEnvelope() {
        val k = 2.0f * gap + 0.24f * 50f
        val points = LiquidMergeGeometry.mergeOutlinePoints(left, right, k)!!
        // 融合体任何点都不应超出 [圆心 − (r + k), 圆心 + (r + k)] 的理论扩张范围
        val envelope = 50f + k
        for (p in points) {
            assertTrue(p.x > 100f - envelope && p.x < 220f + envelope)
            assertTrue(p.y > 100f - envelope && p.y < 100f + envelope)
            assertTrue(!p.x.isNaN() && !p.y.isNaN())
        }
        // 轮廓整体窄于两圆外包络 + k/4（多项式 smin 的最大外扩）
        val maxY = points.maxOf { abs(it.y - 100f) }
        assertTrue("最大半宽 $maxY 超出理论上限", maxY <= 50f + k / 4f + 0.5f)
    }

    @Test
    fun mergeOutline_rejectsOverlappingOrDegenerateCircles() {
        val touching = LiquidMergeGeometry.MergeCircle(Offset(150f, 100f), 50f)
        assertNull(LiquidMergeGeometry.mergeOutlinePoints(left, touching, 100f))
        assertNull(LiquidMergeGeometry.mergeOutlinePoints(left, left, 100f))
        assertNull(LiquidMergeGeometry.mergeOutlinePoints(left, right, -1f))
    }

    @Test
    fun mergeOutline_supportsUnequalRadiiAndTiltedAxis() {
        // 吸收场景：左侧液滴被压扁(r=30)，且不在同一水平线上
        val small = LiquidMergeGeometry.MergeCircle(Offset(100f, 96f), 30f)
        val big = LiquidMergeGeometry.MergeCircle(Offset(215f, 104f), 50f)
        val d = (big.center - small.center).getDistance()
        val k = 2.05f * (d - 30f - 50f)
        val points = LiquidMergeGeometry.mergeOutlinePoints(small, big, k)
        assertNotNull(points)
        points!!.forEach { p ->
            assertTrue(!p.x.isNaN() && !p.y.isNaN())
        }
    }
}

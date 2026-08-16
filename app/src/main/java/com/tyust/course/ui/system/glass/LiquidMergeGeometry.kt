package com.tyust.course.ui.system.glass

import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot

/**
 * SDF smooth-min 液滴融合几何（Apple Liquid Glass「控件融合」/ 社区 metaball 同款算法）。
 *
 * 多项式 smin：`h = clamp(0.5 + 0.5(a−b)/k, 0, 1); smin = mix(b, a, h) − k·h(1−h)`。
 * 两个圆的 SDF 做 smin 后取等值面 field = 0，即融合轮廓：
 *
 *   - k ≤ 2×边距时两圆互不相连（场在中点仍为正）；
 *   - 越过出生点后从一条细丝连续长成腰身，压满时腰宽约为直径 3/4；
 *   - 轮廓与圆的衔接永远落在圆内——不存在旧贝塞尔方案「锚点出圆」的翼片尖刺。
 *
 * 实现上不构造 Path（android.graphics 在 JVM 单测中是空桩），只产出采样点，
 * 绘制侧负责平滑成 Path——几何因此可以纯 JVM 断言。
 */
internal object LiquidMergeGeometry {

    /** 融合端点：圆心（父级坐标系）+ 半径。 */
    data class MergeCircle(val center: Offset, val radius: Float)

    fun smin(a: Float, b: Float, k: Float): Float {
        if (k <= 0f) return minOf(a, b)
        // iq 原式：h 由 (b−a) 决定，b−a ≥ 2k 时 h=1 退化为纯 min(a)
        val h = (0.5f + 0.5f * (b - a) / k).coerceIn(0f, 1f)
        return b + (a - b) * h - k * h * (1f - h)
    }

    /**
     * 采样站（沿两圆心连线，t ∈ [0, d]）上融合体的半宽（垂直于连线方向）。
     * 返回 null 表示该站在融合体之外——两圆之间出现断站即颈未形成。
     */
    fun stationHalfWidth(
        t: Float,
        left: MergeCircle,
        right: MergeCircle,
        d: Float,
        k: Float
    ): Float? {
        fun field(w: Float): Float {
            val sdLeft = hypot(t, w) - left.radius
            val sdRight = hypot(d - t, w) - right.radius
            return smin(sdLeft, sdRight, k)
        }

        if (field(0f) > 0f) return null
        var lo = 0f
        // 圆外该高度上两个 SDF 都 ≥ k，smin ≥ 0.75k > 0，一定是融合体外
        var hi = maxOf(left.radius, right.radius) + k
        repeat(BISECTION_STEPS) {
            val mid = (lo + hi) / 2f
            if (field(mid) < 0f) lo = mid else hi = mid
        }
        return (lo + hi) / 2f
    }

    /** 两圆心连线中点处的半宽（腰）。颈未形成时为 null。 */
    fun waistHalfWidth(left: MergeCircle, right: MergeCircle, k: Float): Float? {
        val d = offsetDistance(right.center, left.center)
        if (d <= 0f) return null
        return stationHalfWidth(d / 2f, left, right, d, k)
    }

    /**
     * 融合轮廓采样点：上缘从左圆心到右圆心正序、下缘逆序，首尾即两条竖直端边
     * （都落在圆内、会被芯片盖住）。坐标为父级坐标系。
     *
     * 返回 null 表示该 k 下没有形成颈（间距过大 / k 不足 / 圆已相交）。
     */
    fun mergeOutlinePoints(
        left: MergeCircle,
        right: MergeCircle,
        k: Float,
        stationCount: Int = STATION_COUNT
    ): List<Offset>? {
        if (stationCount < 2 || k <= 0f) return null
        val axis = right.center - left.center
        val d = axis.getDistance()
        // 圆已相交或内含：没有「间隙」需要桥，融合交给芯片自身轮廓
        if (d <= left.radius + right.radius || d <= 0f) return null

        val ux = axis.x / d
        val uy = axis.y / d
        val nx = -uy
        val ny = ux

        val top = ArrayList<Offset>(stationCount)
        val bottom = ArrayList<Offset>(stationCount)
        for (i in 0 until stationCount) {
            val t = d * i / (stationCount - 1)
            val w = stationHalfWidth(t, left, right, d, k) ?: return null
            val bx = left.center.x + ux * t
            val by = left.center.y + uy * t
            top += Offset(bx + nx * w, by + ny * w)
            bottom += Offset(bx - nx * w, by - ny * w)
        }
        top.addAll(bottom.asReversed())
        return top
    }

    private fun offsetDistance(a: Offset, b: Offset): Float = (a - b).getDistance()

    private const val STATION_COUNT = 16
    private const val BISECTION_STEPS = 12
}

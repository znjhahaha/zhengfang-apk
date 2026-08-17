package com.tyust.course.ui.system.glass

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Implicit-surface fusion for two vertically aligned rounded rectangles.
 *
 * Each surface is represented by an exact rounded-box signed distance field. The two fields are
 * combined with the same polynomial smooth-min used by [LiquidMergeGeometry], then the zero-level
 * contour is recovered by vertical station sampling plus bisection. No Bezier control point encodes
 * the neck: its birth, width, concavity and rupture all follow from distance and smoothness `k`.
 */
internal object RoundedRectMergeGeometry {

    data class RoundedBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val radius: Float
    ) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        val halfWidth: Float get() = (right - left) / 2f
        val halfHeight: Float get() = (bottom - top) / 2f
    }

    /** Exact Euclidean SDF for an axis-aligned rounded rectangle. */
    fun signedDistance(point: Offset, box: RoundedBox): Float {
        val halfWidth = box.halfWidth.coerceAtLeast(0f)
        val halfHeight = box.halfHeight.coerceAtLeast(0f)
        val radius = box.radius.coerceIn(0f, minOf(halfWidth, halfHeight))
        val coreHalfWidth = (halfWidth - radius).coerceAtLeast(0f)
        val coreHalfHeight = (halfHeight - radius).coerceAtLeast(0f)
        val qx = abs(point.x - box.centerX) - coreHalfWidth
        val qy = abs(point.y - box.centerY) - coreHalfHeight
        val outside = hypot(maxOf(qx, 0f), maxOf(qy, 0f))
        val inside = minOf(maxOf(qx, qy), 0f)
        return outside + inside - radius
    }

    fun field(
        point: Offset,
        header: RoundedBox,
        body: RoundedBox,
        smoothness: Float
    ): Float = LiquidMergeGeometry.smin(
        signedDistance(point, header),
        signedDistance(point, body),
        smoothness.coerceAtLeast(0f)
    )

    /**
     * Returns one closed symmetric contour, ordered down the right edge and back up the left edge.
     * `null` means the zero-level set has split into two components, so callers should draw the two
     * settled rounded rectangles independently.
     */
    fun mergedVerticalOutline(
        header: RoundedBox,
        body: RoundedBox,
        smoothness: Float,
        stationCount: Int = DEFAULT_STATION_COUNT,
        bisectionSteps: Int = DEFAULT_BISECTION_STEPS
    ): List<Offset>? {
        if (stationCount < 3 || bisectionSteps < 1) return null
        if (header.right <= header.left || header.bottom <= header.top) return null
        if (body.right <= body.left || body.bottom <= body.top) return null

        val centerX = (header.centerX + body.centerX) / 2f
        val top = minOf(header.top, body.top)
        val bottom = maxOf(header.bottom, body.bottom)
        val verticalSpan = bottom - top
        if (verticalSpan <= 0f) return null

        // Explicitly probe the geometrically critical gap midpoint. A coarse station grid could
        // otherwise step over a very narrow split and incorrectly return one connected loop.
        if (body.top > header.bottom) {
            val gapMidY = (header.bottom + body.top) / 2f
            if (field(Offset(centerX, gapMidY), header, body, smoothness) > FIELD_EPSILON) {
                return null
            }
        }

        val searchHalfWidth = maxOf(
            centerX - minOf(header.left, body.left),
            maxOf(header.right, body.right) - centerX
        ) + smoothness.coerceAtLeast(0f) + maxOf(header.radius, body.radius)
        val right = ArrayList<Offset>(stationCount)
        val left = ArrayList<Offset>(stationCount)

        for (index in 0 until stationCount) {
            val y = top + verticalSpan * index / (stationCount - 1)
            fun stationField(halfWidth: Float): Float = field(
                Offset(centerX + halfWidth, y),
                header,
                body,
                smoothness
            )

            if (stationField(0f) > FIELD_EPSILON) return null
            var lo = 0f
            var hi = searchHalfWidth
            if (stationField(hi) <= 0f) return null
            repeat(bisectionSteps) {
                val mid = (lo + hi) / 2f
                if (stationField(mid) <= 0f) lo = mid else hi = mid
            }
            val halfWidth = (lo + hi) / 2f
            right += Offset(centerX + halfWidth, y)
            left += Offset(centerX - halfWidth, y)
        }

        right.addAll(left.asReversed())
        return right
    }

    private const val FIELD_EPSILON = 0.001f
    private const val DEFAULT_STATION_COUNT = 48
    private const val DEFAULT_BISECTION_STEPS = 11
}

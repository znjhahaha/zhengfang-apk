package com.tyust.course.ui.system.glass

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RoundedRectMergeGeometryTest {
    private val header = RoundedRectMergeGeometry.RoundedBox(
        left = 0f,
        top = 0f,
        right = 420f,
        bottom = 56f,
        radius = 28f
    )

    @Test
    fun separatedBoxes_doNotMergeBelowSmoothMinBirthThreshold() {
        val body = RoundedRectMergeGeometry.RoundedBox(
            left = 0f,
            top = 68f,
            right = 420f,
            bottom = 250f,
            radius = 22f
        )

        // 中点距两块表面各 6px；多项式 smin 的出生点是 k = 2 * gap = 24px。
        assertNull(RoundedRectMergeGeometry.mergedVerticalOutline(header, body, 23.5f))
        assertNotNull(RoundedRectMergeGeometry.mergedVerticalOutline(header, body, 24.3f))
    }

    @Test
    fun overlappingBoxes_mergeWithoutFabricatedBridgeCurve() {
        val body = RoundedRectMergeGeometry.RoundedBox(
            left = 0f,
            top = 40f,
            right = 420f,
            bottom = 220f,
            radius = 22f
        )

        val points = RoundedRectMergeGeometry.mergedVerticalOutline(
            header = header,
            body = body,
            smoothness = 10f,
            stationCount = 48
        )
        assertNotNull(points)
        assertEquals(96, points!!.size)
    }

    @Test
    fun outline_isHorizontallySymmetricAndLiesOnTheImplicitSurface() {
        val body = RoundedRectMergeGeometry.RoundedBox(
            left = 0f,
            top = 60f,
            right = 420f,
            bottom = 250f,
            radius = 22f
        )
        val smoothness = 12f
        val stationCount = 52
        val points = RoundedRectMergeGeometry.mergedVerticalOutline(
            header = header,
            body = body,
            smoothness = smoothness,
            stationCount = stationCount
        )
        assertNotNull(points)
        points!!
        assertEquals(stationCount * 2, points.size)

        for (index in 0 until stationCount) {
            val right = points[index]
            val left = points[points.lastIndex - index]
            assertEquals(420f, right.x + left.x, 0.05f)
            assertEquals(right.y, left.y, 0.001f)
            assertTrue(
                abs(
                    RoundedRectMergeGeometry.field(
                        point = right,
                        header = header,
                        body = body,
                        smoothness = smoothness
                    )
                ) < 0.08f
            )
        }
    }

    @Test
    fun roundedBoxSignedDistance_hasExpectedInsideEdgeAndOutsideSigns() {
        assertTrue(RoundedRectMergeGeometry.signedDistance(Offset(210f, 28f), header) < 0f)
        assertEquals(0f, RoundedRectMergeGeometry.signedDistance(Offset(210f, 0f), header), 0.001f)
        assertTrue(RoundedRectMergeGeometry.signedDistance(Offset(210f, -5f), header) > 0f)
        assertEquals(0f, RoundedRectMergeGeometry.signedDistance(Offset(0f, 28f), header), 0.001f)
    }
}

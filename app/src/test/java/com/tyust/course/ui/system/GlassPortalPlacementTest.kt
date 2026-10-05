package com.tyust.course.ui.system

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class GlassPortalPlacementTest {
    @Test fun dismissRegionsCoverPageButNeverLiveToolbar() {
        val window = Rect(0f, 0f, 360f, 640f)
        val toolbar = Rect(20f, 24f, 340f, 76f)
        val regions = dismissRegions(window, toolbar)
        assertEquals(window.width * window.height - toolbar.width * toolbar.height,
            regions.sumOf { (it.width * it.height).toDouble() }.toFloat(), .01f)
        assertTrue(regions.none { it.overlaps(toolbar) })
        for (i in regions.indices) for (j in 0 until i) assertFalse(regions[i].overlaps(regions[j]))
        assertEquals(listOf(window), dismissRegions(window, Rect.Zero))
    }

    @Test fun menuUsesSpaceAboveWithoutMovingItsAnchor() {
        val result = resolvePortalPlacement(Rect(20f, 600f, 340f, 648f), Rect(12f, 36f, 348f, 760f), 320f, 48f, 380f, 12f)
        assertTrue(result.opensUp)
        assertEquals(600f, result.y + 380f - 48f, 0.001f)
        assertEquals(552f, result.bodySpace, 0.001f)
    }

    @Test fun smallWindowsClampWidthAndRemainFinite() {
        val result = resolvePortalPlacement(Rect(-20f, 40f, 500f, 88f), Rect(12f, 30f, 308f, 400f), 320f, 48f, 380f, 12f)
        assertFalse(result.opensUp)
        assertEquals(296f, result.width, 0.001f)
        assertEquals(12f, result.x, 0.001f)
        assertEquals(300f, result.bodySpace, 0.001f)
    }
}

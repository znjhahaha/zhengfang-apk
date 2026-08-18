package com.tyust.course.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperToneMapTest {
    @Test
    fun pinkWallpaperUsesDarkForegroundWithoutDarkSurface() {
        val appearance = WallpaperToneMap.uniform(0xFFBD91B5.toInt()).resolve(
            viewportWidth = 1080,
            viewportHeight = 2400,
            region = WallpaperRegion(0, 0, 1080, 2400),
            blur = 0f,
            dim = 0f
        )

        assertTrue(appearance.usesDarkForeground)
        assertEquals(0xFFFFFFFF.toInt(), appearance.surfaceArgb)
        assertTrue(appearance.surfaceAlpha <= 0.32f)
        assertTrue(appearance.minimumContrast >= 4.5)
    }

    @Test
    fun darkWallpaperUsesLightForeground() {
        val appearance = WallpaperToneMap.uniform(0xFF08090A.toInt()).resolve(
            viewportWidth = 1080,
            viewportHeight = 2400,
            region = WallpaperRegion(0, 0, 1080, 2400),
            blur = 0f,
            dim = 0f
        )

        assertFalse(appearance.usesDarkForeground)
        assertTrue(appearance.minimumContrast >= 4.5)
    }

    @Test
    fun mixedWallpaperAddsEnoughNeutralScrimForEverySample() {
        val checkerboard = WallpaperToneMap(
            sourceWidth = 2,
            sourceHeight = 2,
            gridWidth = 2,
            gridHeight = 2,
            sharpArgb = intArrayOf(
                0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
                0xFFFFFFFF.toInt(), 0xFF000000.toInt()
            ),
            softArgb = intArrayOf(
                0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
                0xFFFFFFFF.toInt(), 0xFF000000.toInt()
            )
        )

        val appearance = checkerboard.resolve(
            viewportWidth = 100,
            viewportHeight = 100,
            region = WallpaperRegion(0, 0, 100, 100),
            blur = 0f,
            dim = 0f
        )

        assertTrue(appearance.isMixed)
        assertTrue(appearance.surfaceAlpha > 0.28f)
        assertTrue(appearance.minimumContrast >= 4.5)
    }

    @Test
    fun centerCropSamplesTheVisibleCenterOfLandscapeImage() {
        val toneMap = WallpaperToneMap(
            sourceWidth = 4,
            sourceHeight = 2,
            gridWidth = 4,
            gridHeight = 2,
            sharpArgb = intArrayOf(
                0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(),
                0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt()
            ),
            softArgb = intArrayOf(
                0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(),
                0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt()
            )
        )

        val appearance = toneMap.resolve(
            viewportWidth = 100,
            viewportHeight = 400,
            region = WallpaperRegion(0, 0, 100, 400),
            blur = 0f,
            dim = 0f
        )

        assertTrue(appearance.usesDarkForeground)
        assertFalse(appearance.isMixed)
    }

    @Test
    fun dimIsAppliedWithoutReanalyzingTheBitmap() {
        val toneMap = WallpaperToneMap.uniform(0xFFBD91B5.toInt())

        val undimmed = toneMap.resolve(100, 100, WallpaperRegion(0, 0, 100, 100), 0f, 0f)
        val dimmed = toneMap.resolve(100, 100, WallpaperRegion(0, 0, 100, 100), 0f, 1f)

        assertTrue(undimmed.usesDarkForeground)
        assertFalse(dimmed.usesDarkForeground)
    }

    @Test
    fun toneMapRoundTripsAndRejectsCorruptData() {
        val original = WallpaperToneMap(
            sourceWidth = 1440,
            sourceHeight = 2560,
            gridWidth = 2,
            gridHeight = 2,
            sharpArgb = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt(), 0xFFAABBCC.toInt()),
            softArgb = intArrayOf(0xFF223344.toInt(), 0xFF556677.toInt(), 0xFF8899AA.toInt(), 0xFFBBCCDD.toInt())
        )

        val decoded = WallpaperToneMap.decode(original.encode())

        assertNotNull(decoded)
        assertEquals(original, decoded)
        assertNull(WallpaperToneMap.decode("not-a-tone-map"))
    }

    @Test
    fun toneGridPreservesSourceQuadrants() {
        val pixels = intArrayOf(
            0xFFFF0000.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(),
            0xFFFF0000.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(),
            0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(),
            0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()
        )

        val grid = buildWallpaperToneGrid(
            pixels = pixels,
            sourceWidth = 4,
            sourceHeight = 4,
            gridWidth = 2,
            gridHeight = 2
        )

        assertTrue(grid.contentEquals(intArrayOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(),
            0xFF0000FF.toInt(), 0xFFFFFFFF.toInt()
        )))
    }
}

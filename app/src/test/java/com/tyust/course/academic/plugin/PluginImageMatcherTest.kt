package com.tyust.course.academic.plugin

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PluginImageMatcherTest {
    @Test fun texturedMaskedPieceMatchesItsPositionAndReportsOriginalImageCoordinates() {
        val random = Random(824)
        val background = Bitmap.createBitmap(320, 120, Bitmap.Config.ARGB_8888)
        for (y in 0 until 120) for (x in 0 until 320) {
            val v = random.nextInt(60, 230)
            background.setPixel(x, y, (0xff shl 24) or (v shl 16) or (v shl 8) or v)
        }
        val piece = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        for (y in 0 until 40) for (x in 0 until 40) if ((x - 20) * (x - 20) + (y - 20) * (y - 20) < 380) {
            piece.setPixel(x, y, background.getPixel(130 + x, 40 + y))
        }
        val result = PluginImageMatcher.match(background, piece, expectedY = 40.0)
        assertEquals(130.0, result.getDouble("x"), 1.5)
        assertEquals(40.0, result.getDouble("y"), 0.0)
        assertFalse(result.getBoolean("ambiguous"))
        val scaled = PluginImageMatcher.match(background, piece, 640, 240, 80, 80, 80.0)
        assertEquals(260.0, scaled.getDouble("x"), 3.0)
        assertEquals(640, scaled.getInt("width"))
        background.recycle(); piece.recycle()
    }

    @Test fun uniformOrUnusableMasksFallBackInsteadOfGuessing() {
        val background = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffaaaaaa.toInt()) }
        val piece = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffaaaaaa.toInt()) }
        assertTrue(PluginImageMatcher.match(background, piece).getBoolean("ambiguous"))
        piece.eraseColor(0)
        assertThrows(PluginException::class.java) { PluginImageMatcher.match(background, piece) }
        background.recycle(); piece.recycle()
    }
}

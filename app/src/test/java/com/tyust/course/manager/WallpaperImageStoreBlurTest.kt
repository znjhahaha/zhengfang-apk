package com.tyust.course.manager

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WallpaperImageStoreBlurTest {
    // 宽高都取奇数：中心像素精确存在，镜像对称断言才成立（偶数尺寸的"中心"落在半格上）
    private val w = 65
    private val h = 49

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun zeroRadiusIsIdentity() {
        val pixels = IntArray(w * h) { argb(it % 256, (it * 3) % 256, (it * 7) % 256) }
        assertArrayEquals(pixels, boxBlurPixels(pixels, w, h, 0))
        // 不修改入参
        val copy = pixels.copyOf()
        boxBlurPixels(pixels, w, h, 3)
        assertArrayEquals(copy, pixels)
    }

    @Test
    fun uniformFieldIsInvariant() {
        val color = argb(200, 100, 30)
        val out = boxBlurPixels(IntArray(w * h) { color }, w, h, 4)
        assertTrue(out.all { it == color })
    }

    @Test
    fun impulseConservesChannelMassAwayFromEdges() {
        val pixels = IntArray(w * h) { argb(0, 0, 0) }
        val cx = w / 2
        val cy = h / 2
        pixels[cy * w + cx] = argb(255, 255, 255)

        val out = boxBlurPixels(pixels, w, h, 3)

        fun mass(channel: (Int) -> Int) = out.sumOf(channel).toInt()
        // 半径 3 远离边界,总量只受 6 次整数截断损耗(<10%)
        assertTrue("R 通道质量 ${mass { (it shr 16) and 0xFF }}", mass { (it shr 16) and 0xFF } in 230..255)
        assertTrue(mass { (it shr 8) and 0xFF } in 230..255)
        assertTrue(mass { it and 0xFF } in 230..255)
    }

    @Test
    fun outputStaysWithinInputRange() {
        val pixels = IntArray(w * h) { argb(it % 256, (it * 5) % 256, (it * 11) % 256) }
        val out = boxBlurPixels(pixels, w, h, 5)
        val minR = pixels.minOf { (it shr 16) and 0xFF }
        val maxR = pixels.maxOf { (it shr 16) and 0xFF }
        assertTrue(out.all { ((it shr 16) and 0xFF) in minR..maxR })
    }

    @Test
    fun impulseResponseIsSymmetric() {
        val pixels = IntArray(w * h) { argb(0, 0, 0) }
        pixels[(h / 2) * w + w / 2] = argb(255, 0, 0)
        val out = boxBlurPixels(pixels, w, h, 3)

        for (y in 0 until h / 2) {
            for (x in 0 until w / 2) {
                val tl = (out[y * w + x] shr 16) and 0xFF
                val tr = (out[y * w + (w - 1 - x)] shr 16) and 0xFF
                val bl = (out[(h - 1 - y) * w + x] shr 16) and 0xFF
                assertEquals("水平对称 @($x,$y)", tl, tr)
                assertEquals("垂直对称 @($x,$y)", tl, bl)
            }
        }
    }

    @Test
    fun blurActuallySpreadsEnergy() {
        val pixels = IntArray(w * h) { argb(0, 0, 0) }
        pixels[(h / 2) * w + w / 2] = argb(255, 0, 0)
        val out = boxBlurPixels(pixels, w, h, 3)
        val neighbors = listOf(
            out[(h / 2) * w + w / 2 - 1], out[(h / 2) * w + w / 2 + 1],
            out[(h / 2 - 1) * w + w / 2], out[(h / 2 + 1) * w + w / 2]
        )
        // 邻居被点亮、中心被摊薄 → 这是"扩散"而不是平移
        assertTrue(neighbors.all { ((it shr 16) and 0xFF) > 0 })
        assertTrue(((out[(h / 2) * w + w / 2] shr 16) and 0xFF) < 255)
    }

    @Test
    fun rec709LuminanceMatchesKnownValues() {
        assertTrue(abs(rec709Luminance(0xFFFFFFFF.toInt()) - 1f) < 0.001f)
        assertTrue(abs(rec709Luminance(0xFF000000.toInt()) - 0f) < 0.001f)
        assertTrue(abs(rec709Luminance(0xFF00FF00.toInt()) - 0.7152f) < 0.001f)
        assertTrue(abs(rec709Luminance(argb(128, 128, 128)) - 128f / 255f) < 0.001f)
    }
}

package com.tyust.course.ui.system.glass

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RecordingCanvas
import android.graphics.RenderNode
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 两条平台行为的真机确认。原先只是从 androidx.compose.ui:ui-graphics 的字节码
 * 推断出来的，必须在真机/模拟器上执行过才算数。
 *
 * 1. `Bitmap.createBitmap(Picture)` 对**覆写了 draw() 的 Picture 子类**有效，
 *    且给到 draw() 的 Canvas 是硬件加速的 RecordingCanvas
 *    —— 这是 backdrop 里 `drawLayer` → `drawRenderNode` 能工作的前提，
 *    也是现在 `GlassLensAnchor.capture()` 拍底图那条路的地基。
 * 2. `drawBitmapMesh` 接受 HARDWARE config 位图作为源，且形变真的生效。
 *    这一条属于**已放弃**的几何形变方案（网格只能位移顶点，做不出逐像素位移与
 *    逐通道色散）。留着是因为它是那次选型的证据，不要据此以为代码里还有网格路径。
 *
 * 全部只用 public API。
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
class GlassPlatformCapabilityTest {

    private val w = 64
    private val h = 32

    /** 覆写 draw() 的 Picture 子类，与 MeshLens 里的 BackdropPicture 同构。 */
    private class DrawPicture(
        private val width: Int,
        private val height: Int,
        private val onDraw: (android.graphics.Canvas) -> Unit
    ) : Picture() {
        var sawCanvasClass: String? = null
            private set
        var sawHardwareAccelerated: Boolean? = null
            private set
        var sawRecordingCanvas: Boolean? = null
            private set

        override fun beginRecording(w: Int, h: Int): android.graphics.Canvas =
            android.graphics.Canvas()

        override fun endRecording() = Unit

        override fun getWidth(): Int = width

        override fun getHeight(): Int = height

        override fun requiresHardwareAcceleration(): Boolean = true

        override fun draw(canvas: android.graphics.Canvas) {
            sawCanvasClass = canvas.javaClass.name
            sawHardwareAccelerated = canvas.isHardwareAccelerated
            sawRecordingCanvas = canvas is RecordingCanvas
            onDraw(canvas)
        }
    }

    /** 左半红、右半蓝。边界位置用来测量形变。 */
    private fun drawHalves(canvas: android.graphics.Canvas) {
        val paint = Paint()
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, w / 2f, h.toFloat(), paint)
        paint.color = Color.BLUE
        canvas.drawRect(w / 2f, 0f, w.toFloat(), h.toFloat(), paint)
    }

    /** 找第 y 行上红->蓝的跳变位置。 */
    private fun findBoundary(bmp: Bitmap, y: Int): Int {
        val soft = if (bmp.config == Bitmap.Config.HARDWARE) {
            bmp.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bmp
        }
        assertNotNull("copy to ARGB_8888 failed", soft)
        for (x in 0 until soft!!.width) {
            if (Color.blue(soft.getPixel(x, y)) > 128) return x
        }
        return -1
    }

    @Test
    fun createBitmapFromPictureSubclass_givesHardwareBitmapAndRecordingCanvas() {
        val pic = DrawPicture(w, h) { drawHalves(it) }
        val bmp = Bitmap.createBitmap(pic)

        assertNotNull("Bitmap.createBitmap(Picture) returned null", bmp)
        assertEquals(w, bmp.width)
        assertEquals(h, bmp.height)

        // 关键推断 1：draw() 拿到的是硬件加速的 RecordingCanvas
        assertEquals("draw() should get a hardware-accelerated canvas", true, pic.sawHardwareAccelerated)
        assertEquals(
            "draw() should get a RecordingCanvas (got ${pic.sawCanvasClass})",
            true,
            pic.sawRecordingCanvas
        )
        assertEquals(
            "default createBitmap(Picture) should yield HARDWARE config",
            Bitmap.Config.HARDWARE,
            bmp.config
        )

        // 内容确实画进去了
        val boundary = findBoundary(bmp, h / 2)
        assertEquals("red/blue boundary", w / 2, boundary)
    }

    @Test
    fun pictureDrawCanReplayRenderNode() {
        // 关键推断 1 的真正用途：backdrop 内部是 drawLayer -> drawRenderNode
        val node = RenderNode("meshLensTest")
        node.setPosition(0, 0, w, h)
        val rc = node.beginRecording()
        drawHalves(rc)
        node.endRecording()

        var replayed = false
        val pic = DrawPicture(w, h) { canvas ->
            assertTrue("expected RecordingCanvas to replay RenderNode", canvas is RecordingCanvas)
            (canvas as RecordingCanvas).drawRenderNode(node)
            replayed = true
        }
        val bmp = Bitmap.createBitmap(pic)

        assertTrue("RenderNode was not replayed", replayed)
        assertEquals(
            "RenderNode content should survive the Picture snapshot",
            w / 2,
            findBoundary(bmp, h / 2)
        )
    }

    @Test
    fun drawBitmapMesh_acceptsHardwareBitmapAndActuallyWarps() {
        // 源：HARDWARE 位图，与生产路径一致
        val src = Bitmap.createBitmap(DrawPicture(w, h) { drawHalves(it) })
        assertEquals(Bitmap.Config.HARDWARE, src.config)

        val shift = 12f
        val meshW = 2
        val meshH = 2
        val verts = FloatArray((meshW + 1) * (meshH + 1) * 2)
        var k = 0
        for (row in 0..meshH) {
            for (col in 0..meshW) {
                // 整体左移 shift 像素：红/蓝边界应随之左移
                verts[k++] = w.toFloat() * col / meshW - shift
                verts[k++] = h.toFloat() * row / meshH
            }
        }

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        var meshThrew: Throwable? = null
        val out = Bitmap.createBitmap(
            DrawPicture(w, h) { canvas ->
                try {
                    canvas.drawBitmapMesh(src, meshW, meshH, verts, 0, null, 0, paint)
                } catch (t: Throwable) {
                    meshThrew = t
                }
            }
        )

        assertEquals("drawBitmapMesh threw on a HARDWARE bitmap: $meshThrew", null, meshThrew)

        val boundary = findBoundary(out, h / 2)
        assertTrue("no boundary found; mesh probably drew nothing", boundary >= 0)
        assertEquals(
            "boundary should shift left by $shift px",
            (w / 2 - shift).toInt(),
            boundary,
        )
    }

    @Test
    fun drawBitmapMesh_nonUniformWarpBendsAStraightLine() {
        // 更接近真实用途：非均匀位移应把直线弯曲，而不是整体平移
        val src = Bitmap.createBitmap(
            DrawPicture(w, h) { canvas ->
                val paint = Paint()
                paint.color = Color.RED
                canvas.drawRect(0f, 0f, w / 2f, h.toFloat(), paint)
                paint.color = Color.BLUE
                canvas.drawRect(w / 2f, 0f, w.toFloat(), h.toFloat(), paint)
            }
        )

        val meshW = 16
        val meshH = 16
        val verts = FloatArray((meshW + 1) * (meshH + 1) * 2)
        var k = 0
        for (row in 0..meshH) {
            val fy = row.toFloat() / meshH
            // 顶部不动、中间左移最多，形成弯曲
            val bend = 16f * kotlin.math.sin(fy * Math.PI).toFloat()
            for (col in 0..meshW) {
                verts[k++] = w.toFloat() * col / meshW - bend
                verts[k++] = h.toFloat() * row / meshH
            }
        }

        val paint = Paint().apply { isFilterBitmap = true }
        val out = Bitmap.createBitmap(
            DrawPicture(w, h) { canvas ->
                canvas.drawBitmapMesh(src, meshW, meshH, verts, 0, null, 0, paint)
            }
        )

        val top = findBoundary(out, 1)
        val middle = findBoundary(out, h / 2)
        assertTrue("top boundary not found", top >= 0)
        assertTrue("middle boundary not found", middle >= 0)
        assertTrue(
            "middle ($middle) should be well left of top ($top) — line should bend, not translate",
            top - middle >= 8
        )
    }
}

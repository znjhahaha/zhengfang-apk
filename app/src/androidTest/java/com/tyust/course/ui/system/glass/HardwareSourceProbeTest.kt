package com.tyust.course.ui.system.glass

import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RenderNode
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 验证「持久 HardwareRenderer + SurfaceTexture → GL 外部纹理」这条零回读通路
 * 在 API 31/32 上成立，并量出它比 `Bitmap.createBitmap(Picture)` 快多少。
 *
 * 为什么要换掉 `Bitmap.createBitmap(Picture)`：实测它每次调用要 4.6ms，
 * 因为每次都新建一个用完即弃的 HardwareRenderer（建上下文 + 分配缓冲 + 渲染 + 同步）。
 * 复用一个常驻 renderer，并让结果以 SurfaceTexture 形式直接进 GL，
 * 就能把 GPU→CPU→GPU 那一圈整个去掉。
 *
 * **结论是这条路不可用，已放弃。** 实测中位 4.69ms、p90 15.99ms，比它要替换的方案
 * 更差：HardwareRenderer 按 vsync 节拍走，`syncAndDraw` 持续返回 status 8。
 * 现在的做法是保留 `Bitmap.createBitmap(Picture)`，改为**按需重拍**
 * （内容变化 / 滚动限频 / 停下补一次，静止零开销，见 [GlassLensFreshness]）。
 * 这个用例留作那次测量的证据 —— 不要再试一遍。
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
class HardwareSourceProbeTest {

    private val w = 1002
    private val h = 180

    /** 与真实底图同构的绘制内容：渐变 + 12px 周期的细斜纹。 */
    private fun makePicture(phase: Int): Picture {
        val pic = Picture()
        val c = pic.beginRecording(w, h)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = android.graphics.Color.rgb(246, 246, 246)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.strokeWidth = 1f
        p.color = android.graphics.Color.argb(14, 0, 0, 0)
        var x = phase
        while (x < w + h) {
            c.drawLine((x - h).toFloat(), h.toFloat(), x.toFloat(), 0f, p)
            x += 12
        }
        pic.endRecording()
        return pic
    }

    @Test
    fun persistentHardwareRendererIntoSurfaceTextureFeedsGl() {
        // GL 上下文与 SurfaceTexture 回调都要一条带 Looper 的线程：
        // instrumentation 线程没有 Looper，setFrameCommitCallback 与
        // OnFrameAvailableListener 都无法派发（首版就是在这里卡住的）。
        val glThread = android.os.HandlerThread("probe-gl").apply { start() }
        val glHandler = android.os.Handler(glThread.looper)
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        glHandler.post {
            try {
                runProbe(glHandler)
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.countDown()
            }
        }
        assertTrue("probe did not finish", done.await(120, TimeUnit.SECONDS))
        glThread.quitSafely()
        failure?.let { throw it }
    }

    private fun runProbe(glHandler: android.os.Handler) {
        // ---- GL 上下文（消费端）----
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)
        val cfg = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            ),
            0, cfg, 0, 1, IntArray(1), 0
        )
        val ctx: EGLContext = EGL14.eglCreateContext(
            display, cfg[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        val pbuf: EGLSurface = EGL14.eglCreatePbufferSurface(
            display, cfg[0], intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0
        )
        assertTrue(EGL14.eglMakeCurrent(display, pbuf, pbuf, ctx))

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )

        val st = SurfaceTexture(tex[0])
        st.setDefaultBufferSize(w, h)
        val frameLatch = arrayOf(CountDownLatch(1))
        // 回调必须落在**另一条**线程：本线程会阻塞在 await 上等它，
        // 派发到同一个 Looper 会直接死锁。
        val cbThread = android.os.HandlerThread("probe-cb").apply { start() }
        val cbHandler = android.os.Handler(cbThread.looper)
        val cbExecutor = java.util.concurrent.Executor { cbHandler.post(it) }
        st.setOnFrameAvailableListener({ frameLatch[0].countDown() }, cbHandler)
        val surface = Surface(st)

        // ---- HardwareRenderer（生产端），常驻复用 ----
        val renderer = HardwareRenderer()
        renderer.setSurface(surface)
        val root = RenderNode("probe")
        root.setPosition(0, 0, w, h)
        renderer.setContentRoot(root)

        fun renderOnce(phase: Int) {
            val pic = makePicture(phase)
            val rc: Canvas = root.beginRecording(w, h)
            rc.drawPicture(pic)
            root.endRecording()
            val req = renderer.createRenderRequest()
            req.setVsyncTime(System.nanoTime())
            val committed = CountDownLatch(1)
            req.setFrameCommitCallback(cbExecutor) { committed.countDown() }
            // syncAndDraw 的返回码必须看：出错时 commit 回调根本不会来，
            // 只 await 会白等满超时（首版 90 次迭代 × 5s 直接把测试拖死）。
            val status = req.syncAndDraw()
            if (status != HardwareRenderer.SYNC_OK) {
                android.util.Log.w("HardwareSourceProbe", "syncAndDraw status=$status")
            }
            assertTrue(
                "frame not committed, syncAndDraw status=$status",
                committed.await(3, TimeUnit.SECONDS)
            )
        }

        android.util.Log.i("HardwareSourceProbe", "setup done, first render")
        // 首帧：证明通路成立
        renderOnce(0)
        android.util.Log.i("HardwareSourceProbe", "first render committed")
        assertTrue("no frame available", frameLatch[0].await(2, TimeUnit.SECONDS))
        st.updateTexImage()
        val m = FloatArray(16)
        st.getTransformMatrix(m)
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())

        // ---- 计时：复用 renderer 的稳定态成本 ----
        // 预热，避开 JIT 与首帧分配。
        // **每帧都必须消费**：SurfaceTexture 的 BufferQueue 槽位有限，
        // 不调 updateTexImage 就不会把 buffer 还给生产端，第二帧的 commit
        // 回调永远不来（syncAndDraw 仍返回 SYNC_OK，所以只看返回码会误判）。
        repeat(30) {
            frameLatch[0] = CountDownLatch(1)
            renderOnce(it)
            frameLatch[0].await(2, TimeUnit.SECONDS)
            st.updateTexImage()
        }
        val n = 60
        val samples = LongArray(n)
        for (i in 0 until n) {
            frameLatch[0] = CountDownLatch(1)
            val t0 = System.nanoTime()
            renderOnce(i)
            frameLatch[0].await(2, TimeUnit.SECONDS)
            st.updateTexImage()
            samples[i] = System.nanoTime() - t0
        }
        samples.sort()
        val median = samples[n / 2] / 1e6
        val p90 = samples[(n * 9) / 10] / 1e6
        android.util.Log.i(
            "HardwareSourceProbe",
            "persistent replay+updateTexImage median=%.2fms p90=%.2fms".format(median, p90)
        )

        // 对照：每次新建的 Bitmap.createBitmap(Picture) + ARGB_8888 回读
        val bmSamples = LongArray(n)
        for (i in 0 until n) {
            val pic = makePicture(i)
            val t0 = System.nanoTime()
            val hw = android.graphics.Bitmap.createBitmap(pic)
            val soft = if (hw.config == android.graphics.Bitmap.Config.HARDWARE) {
                hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            } else {
                hw
            }
            bmSamples[i] = System.nanoTime() - t0
            soft?.recycle()
            if (hw.config == android.graphics.Bitmap.Config.HARDWARE) hw.recycle()
        }
        bmSamples.sort()
        android.util.Log.i(
            "HardwareSourceProbe",
            "createBitmap(Picture)+copy median=%.2fms p90=%.2fms".format(
                bmSamples[n / 2] / 1e6, bmSamples[(n * 9) / 10] / 1e6
            )
        )

        surface.release()
        st.release()
        renderer.destroy()
        cbThread.quitSafely()
        EGL14.eglMakeCurrent(
            display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
        )
        EGL14.eglDestroySurface(display, pbuf)
        EGL14.eglDestroyContext(display, ctx)
        EGL14.eglTerminate(display)
    }
}

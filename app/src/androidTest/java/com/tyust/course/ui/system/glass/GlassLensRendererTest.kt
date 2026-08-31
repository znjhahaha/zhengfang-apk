package com.tyust.course.ui.system.glass

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 验证 [GlassLensSource] + [GlassLensTarget] 在 API 32 真机/模拟器上真的产出
 * 折射结果，而不只是「编译通过」。接进 UI 之前必须先过这一关。
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
class GlassLensRendererTest {

    /** 底图：竖条纹。折射会把直条纹在边缘带弯曲/压缩。 */
    private fun stripes(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val on = ((x / 10) % 2) == 0
                b.setPixel(x, y, if (on) Color.rgb(30, 30, 30) else Color.rgb(225, 225, 225))
            }
        }
        return b
    }

    private fun params(w: Int, h: Int, left: Float, top: Float) = GlassLensParams(
        widthPx = w,
        heightPx = h,
        srcLeftPx = left,
        srcTopPx = top,
        cornerRadiusPx = h / 2f,
        thicknessPx = 30f,
        // amount/height = 1.4，与库 LiquidBottomTabs 的 lens(10dp, 14dp) 同比
        lensAmountPx = 42f,
        dispersion = 1f,
        depthEffect = 0f,
        vibrancy = 1.28f
    )

    /**
     * 把拆开的 [GlassLensSource]（底图纹理，按区域共享）与 [GlassLensTarget]
     * （FBO / 回读缓冲 / 输出位图，按元素独占）配成一对。
     *
     * 这两件事原本在同一个类里，那时「一个锚点一个元素」成立所以没露出问题；
     * 改成区域共享后所有元素往同一个 FBO 提交、又都读同一张 latest，
     * 每个元素画出来的是最后渲染完的那个元素的输出。拆分就是修这个。
     *
     * 测试里绝大多数用例仍是「一个底图配一个元素」，所以这里配成对，
     * 让用例读起来跟拆分前一样；真正验证共享语义的是
     * [multipleRenderersShareOneContextAndAllProduceFrames]。
     */
    private class RendererPair {
        val source = GlassLensSource()
        val target = GlassLensTarget(source)

        val latest: Bitmap? get() = target.latest
        val failed: Boolean get() = source.failed || target.failed

        fun uploadSource(bitmap: Bitmap, version: Int) = source.uploadSource(bitmap, version)
        fun submit(params: GlassLensParams) = target.submit(params)

        fun release() {
            target.release()
            source.release()
        }
    }

    private fun awaitFrame(r: RendererPair, timeoutMs: Long = 5000): Bitmap? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            r.latest?.let { return it }
            if (r.failed) return null
            Thread.sleep(16)
        }
        return null
    }

    @Test
    fun rendersRefractedOutput() {
        val srcW = 1002
        val srcH = 168
        val ew = 195
        val eh = 156
        val renderer = RendererPair()
        try {
            val src = stripes(srcW, srcH)
            renderer.uploadSource(src, version = 1)
            renderer.submit(params(ew, eh, left = 11f, top = 6f))

            val out = awaitFrame(renderer)
            assertFalse("renderer reported failure", renderer.failed)
            assertNotNull("no frame produced within timeout", out)
            out!!
            assertTrue("wrong size", out.width == ew && out.height == eh)

            // 形状外应为透明
            assertTrue(
                "corner should be transparent (outside the capsule)",
                Color.alpha(out.getPixel(1, 1)) < 8
            )

            // 诊断：先看实际像素，避免凭猜测改代码
            val diagY = eh / 2
            val diag = StringBuilder("DIAG midY=$diagY: ")
            for (x in intArrayOf(0, 1, 2, 5, 10, 20, 40, 97, 150, 193)) {
                val p = out.getPixel(x, diagY)
                diag.append(
                    "x=$x(a=${Color.alpha(p)},r=${Color.red(p)},g=${Color.green(p)},b=${Color.blue(p)}) "
                )
            }
            println(diag.toString())
            val colDiag = StringBuilder("DIAG col x=97: ")
            for (y in intArrayOf(0, 1, 5, 20, 78, 150, 154, 155)) {
                val p = out.getPixel(97, y)
                colDiag.append("y=$y(a=${Color.alpha(p)},g=${Color.green(p)}) ")
            }
            println(colDiag.toString())

            // 中线扫描：边缘带内应与「同位置直通」明显不同
            val midY = eh / 2
            var displaced = 0
            var opaque = 0
            for (x in 2 until 45) {
                val px = out.getPixel(x, midY)
                if (Color.alpha(px) < 200) continue
                opaque++
                // 直通值：元素本地 x -> 底图 x = 11 + x
                val srcOn = (((11 + x) / 10) % 2) == 0
                val expect = if (srcOn) 30 else 225
                if (abs(Color.green(px) - expect) > 45) displaced++
            }
            assertTrue("no opaque pixels sampled in the rim band", opaque > 20)
            assertTrue(
                "rim band shows no displacement ($displaced of $opaque) — not refracting",
                displaced > 5
            )

            // 色散：**只在圆角附近**，不在两条中轴上。
            //
            // 库的色散量调制是 `(p.x * p.y) / (hx * hy)`：四角最大，两条中轴上为 0。
            // 所以中线（p.y = 0）扫不到任何色散，这不是缺陷，是同式移植的结果 ——
            // iOS 上看到的蓝黄边也正是只出现在圆角处。
            // 这一条曾经扫中线，于是一直红着，实际上被测的位置根本不该有色散。
            val cornerY = eh / 5
            var dispersed = 0
            for (x in 2 until 45) {
                val px = out.getPixel(x, cornerY)
                if (Color.alpha(px) < 200) continue
                if (abs(Color.red(px) - Color.blue(px)) > 12) dispersed++
            }
            assertTrue(
                "no per-channel dispersion near the corner (y=$cornerY)",
                dispersed > 2
            )

            // 中轴上必须**没有**色散，否则就不是库那套位置调制了
            var midAxisDispersed = 0
            for (x in 2 until 45) {
                val px = out.getPixel(x, midY)
                if (Color.alpha(px) < 200) continue
                if (abs(Color.red(px) - Color.blue(px)) > 12) midAxisDispersed++
            }
            assertTrue(
                "dispersion on the centre axis ($midAxisDispersed px) — " +
                    "position modulation is wrong; it should peak at the corners only",
                midAxisDispersed == 0
            )

            src.recycle()
        } finally {
            renderer.release()
        }
    }

    @Test
    fun movingElementProducesDifferentOutput() {
        // 指示器滑动时，采样窗口跟着动，输出必须跟着变
        val renderer = RendererPair()
        try {
            val src = stripes(1002, 168)
            renderer.uploadSource(src, version = 1)

            renderer.submit(params(195, 156, left = 11f, top = 6f))
            val first = awaitFrame(renderer)
            assertNotNull("no first frame", first)
            val firstCopy = first!!.copy(Bitmap.Config.ARGB_8888, false)

            // 移到另一个 tab 的位置（半个条纹周期的奇数倍，确保内容确实不同）
            renderer.submit(params(195, 156, left = 405f, top = 6f))
            // 等到内容真的变了，而不是等固定时间
            val deadline = System.currentTimeMillis() + 5000
            var changed = false
            while (System.currentTimeMillis() < deadline) {
                val now = renderer.latest
                if (now != null && !now.sameAs(firstCopy)) {
                    changed = true
                    break
                }
                Thread.sleep(16)
            }
            assertFalse("renderer failed", renderer.failed)
            assertTrue("output did not change when the sampling window moved", changed)

            firstCopy.recycle()
            src.recycle()
        } finally {
            renderer.release()
        }
    }

    @Test
    fun reUploadSameSizeUpdatesOutput() {
        // 同尺寸、版本号变化的重传走 texSubImage2D 子区域更新：滚动限频期间
        // 每 100ms 一次都是这条路。若重传被短路、或子区域更新写错了位置/内容，
        // 折射会永远停在第一张底图上（屏幕上是「滚了半天胶囊里还是老画面」）。
        val renderer = RendererPair()
        try {
            val src = stripes(1002, 168)
            renderer.uploadSource(src, version = 1)
            renderer.submit(params(195, 156, left = 11f, top = 6f))
            val first = awaitFrame(renderer)
            assertNotNull("no first frame", first)
            val firstCopy = first!!.copy(Bitmap.Config.ARGB_8888, false)

            // 同尺寸、相位偏移 5px（20px 周期的 1/4）：每个采样点的内容都变
            val src2 = Bitmap.createBitmap(1002, 168, Bitmap.Config.ARGB_8888)
            for (y in 0 until 168) {
                for (x in 0 until 1002) {
                    val on = (((x + 5) / 10) % 2) == 0
                    src2.setPixel(x, y, if (on) Color.rgb(30, 30, 30) else Color.rgb(225, 225, 225))
                }
            }
            renderer.uploadSource(src2, version = 2)
            renderer.submit(params(195, 156, left = 11f, top = 6f))

            val deadline = System.currentTimeMillis() + 5000
            var changed = false
            while (System.currentTimeMillis() < deadline) {
                val now = renderer.latest
                if (now != null && !now.sameAs(firstCopy)) {
                    changed = true
                    break
                }
                Thread.sleep(16)
            }
            assertFalse("renderer failed", renderer.failed)
            assertTrue("same-size re-upload did not change the output", changed)

            firstCopy.recycle()
            src.recycle()
            src2.recycle()
        } finally {
            renderer.release()
        }
    }

    @Test
    fun restPathIsExactlyVibrancyOfSource() {
        // 静止态（lensAmount = 0）着色器走短路：输出应当**逐点等于**
        // setSaturation(vibrancy) 作用在底图同点上，位移与色散都不参与。
        //
        // 这一条是探针脚本 cmp.py 的核心断言，此前只在设备上手工跑过，而它的
        // 「预测 vs 实测」曾报出约 6% 的亮度差（文档第 9 节）。把它钉成用例：
        // 若差异真在着色器/采样/回读这一侧，这里会直接红；若这里绿，那 6% 就
        // 只可能出在截图侧（合成的 tint/highlight）或对比用的底图不是同一帧。
        //
        // 采样点必须落在纹素中心才谈得上「逐点相等」：toSrc 给出
        // (srcLeft + i + 0.5) / srcWidth，所以 left/top 取**整数**时正中纹素中心，
        // GL_LINEAR 不混邻居。真实调用方传的是浮点窗口坐标（元素在动），那属于
        // 另一回事，不在本用例范围内。
        val srcW = 320
        val srcH = 200
        val ew = 128
        val eh = 96
        val left = 16
        val top = 12
        val sat = 1.28f
        val renderer = RendererPair()
        try {
            // 彩色内容：纯灰的话 setSaturation 是恒等变换，测不出饱和度是否真的施加了
            val src = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
            for (y in 0 until srcH) {
                for (x in 0 until srcW) {
                    src.setPixel(
                        x, y,
                        Color.rgb((x * 7 + y * 3) % 256, (x * 3 + y * 11) % 256, (x * 13 + y * 5) % 256)
                    )
                }
            }
            renderer.uploadSource(src, version = 1)
            renderer.submit(
                GlassLensParams(
                    widthPx = ew,
                    heightPx = eh,
                    srcLeftPx = left.toFloat(),
                    srcTopPx = top.toFloat(),
                    // 半径 0：整块都在形状内，sd < 0 处处成立
                    cornerRadiusPx = 0f,
                    thicknessPx = 30f,
                    // 静止：位移为 0，走 applyVibrancy 短路
                    lensAmountPx = 0f,
                    dispersion = 1f,
                    depthEffect = 0f,
                    vibrancy = sat
                )
            )
            val out = awaitFrame(renderer)
            assertFalse("renderer failed", renderer.failed)
            assertNotNull("no frame produced", out)
            out!!

            var worst = 0f
            var worstAt = ""
            var sumAbs = 0.0
            var n = 0
            for (j in 0 until eh) {
                for (i in 0 until ew) {
                    val s = src.getPixel(left + i, top + j)
                    val o = out.getPixel(i, j)
                    // 与着色器 applyVibrancy 同式：BT.709 luma 与原色之间 mix，末端 clamp
                    val l = 0.213f * Color.red(s) + 0.715f * Color.green(s) + 0.072f * Color.blue(s)
                    for (ch in 0..2) {
                        val c = when (ch) {
                            0 -> Color.red(s)
                            1 -> Color.green(s)
                            else -> Color.blue(s)
                        }
                        val expect = (l + (c - l) * sat).coerceIn(0f, 255f)
                        val actual = when (ch) {
                            0 -> Color.red(o)
                            1 -> Color.green(o)
                            else -> Color.blue(o)
                        }
                        val d = abs(actual - expect)
                        sumAbs += d
                        n++
                        if (d > worst) {
                            worst = d
                            worstAt = "($i,$j) ch$ch expect=$expect actual=$actual"
                        }
                    }
                    assertTrue("alpha must be opaque inside the shape at ($i,$j)", Color.alpha(o) == 255)
                }
            }
            val mean = sumAbs / n
            // 容差 2/255：留给 8 位量化与 GPU 浮点精度，堵不住 6% 级（约 15/255）的偏差
            assertTrue(
                "rest path deviates from setSaturation($sat): mean=$mean worst=$worst at $worstAt",
                worst <= 2
            )

            src.recycle()
        } finally {
            renderer.release()
        }
    }

    @Test
    fun multipleRenderersShareOneContextAndAllProduceFrames() {
        // 玻璃元素有十来处。每处一个 EGL 上下文是走不通的（建一个要 10–50ms，
        // 还各占一条线程），所以上下文与 program 是进程级共享的（GlassLensEngine）。
        // 这一条守的是共享之后**每个元素仍然各自出图**：它们共用 program，
        // 但纹理、FBO、输出位图必须是独占的 —— 混用会让所有元素画出同一块内容。
        val count = 6
        val renderers = List(count) { RendererPair() }
        // 每个 renderer 一张**不同**的底图，用条纹相位区分。
        // 相位步长必须让 count 个相位都落在一个周期（20px）内，否则会绕回来撞上：
        // 步长 5 时 i=0 与 i=4 的相位是 0 和 20，同一张图，测试会误报"纹理被串用"。
        val sources = List(count) { i ->
            val b = Bitmap.createBitmap(600, 168, Bitmap.Config.ARGB_8888)
            for (y in 0 until 168) {
                for (x in 0 until 600) {
                    val on = (((x + i * 3) / 10) % 2) == 0
                    b.setPixel(x, y, if (on) Color.rgb(20, 20, 20) else Color.rgb(235, 235, 235))
                }
            }
            b
        }
        try {
            renderers.forEachIndexed { i, r ->
                r.uploadSource(sources[i], version = 1)
                r.submit(params(195, 156, left = 11f, top = 6f))
            }
            val frames = renderers.map { r ->
                val f = awaitFrame(r)
                assertFalse("renderer reported failure", r.failed)
                assertNotNull("a renderer produced no frame", f)
                f!!.copy(Bitmap.Config.ARGB_8888, false)
            }
            // 各自的底图不同 ⇒ 输出必须不同。全都相同就说明纹理被串用了。
            var distinctPairs = 0
            for (i in frames.indices) {
                for (j in i + 1 until frames.size) {
                    if (!frames[i].sameAs(frames[j])) distinctPairs++
                }
            }
            val totalPairs = count * (count - 1) / 2
            assertTrue(
                "renderers produced identical output ($distinctPairs of $totalPairs pairs " +
                    "differ) — per-element textures are being shared",
                distinctPairs == totalPairs
            )
            frames.forEach { it.recycle() }
        } finally {
            renderers.forEach { it.release() }
            sources.forEach { it.recycle() }
        }
    }

    @Test
    fun releasingOneRendererLeavesOthersWorking() {
        // 引擎是共享的，但 release() 只能拆自己那份资源。
        // 曾经 release() 会 glDeleteProgram —— 那是共享的 program，
        // 一个元素离屏会把所有元素一起打死。
        val a = RendererPair()
        val b = RendererPair()
        try {
            val src = stripes(600, 168)
            a.uploadSource(src, version = 1)
            b.uploadSource(src, version = 1)
            a.submit(params(195, 156, left = 11f, top = 6f))
            assertNotNull("first renderer produced no frame", awaitFrame(a))

            a.release()

            b.submit(params(195, 156, left = 11f, top = 6f))
            val out = awaitFrame(b)
            assertFalse("surviving renderer failed after sibling release", b.failed)
            assertNotNull("surviving renderer produced no frame after sibling release", out)
            src.recycle()
        } finally {
            b.release()
        }
    }

    @Test
    fun survivesRapidSubmitsWithoutQueueingUp() {
        // 滑动时每帧提交：submit 应当丢掉排队中的请求而不是堆积
        val renderer = RendererPair()
        try {
            val src = stripes(1002, 168)
            renderer.uploadSource(src, version = 1)
            repeat(200) { i ->
                renderer.submit(params(195, 156, left = (i * 4).toFloat() % 700f, top = 6f))
            }
            val out = awaitFrame(renderer)
            assertFalse("renderer failed under rapid submits", renderer.failed)
            assertNotNull("no frame after rapid submits", out)
            src.recycle()
        } finally {
            renderer.release()
        }
    }
}

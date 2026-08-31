package com.tyust.course.ui.system.glass

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** 一次折射渲染的全部参数。除 [dispersion] 外，所有长度单位为像素。 */
internal data class GlassLensParams(
    val widthPx: Int,
    val heightPx: Int,
    /** 元素左上角在底图中的位置（像素） */
    val srcLeftPx: Float,
    val srcTopPx: Float,
    val cornerRadiusPx: Float,
    /** 边缘斜坡宽度，语义同库 AGSL 的 `refractionHeight`。 */
    val thicknessPx: Float,
    /** 位移幅度，语义同库 AGSL 的 `refractionAmount`。可以大于 [thicknessPx]。 */
    val lensAmountPx: Float,
    /** 色散倍数，**无量纲**，语义同库 AGSL 的 `chromaticAberration` uniform。 */
    val dispersion: Float,
    /** 把梯度混向径向，语义同库 AGSL 的 `depthEffect`。0 = 关。 */
    val depthEffect: Float,
    val vibrancy: Float
)

/**
 * 一块底图的 GL 纹理，被**同一个区域内的所有元素**共享。
 *
 * ## 为什么底图与渲染目标必须分开
 *
 * 曾经这两件事在同一个类里，那时每个折射站点都有自己的锚点，"一个锚点一个元素"
 * 成立，所以没露出问题。改成区域共享（一个 App 级锚点服务所有按钮/芯片/选择器）
 * 之后立刻炸了：所有元素往**同一个 FBO** 提交、又都去读同一张 `latest`，于是每个
 * 元素画出来的是**最后一个渲染完的那个元素**的结果，再拉伸到自己的尺寸。
 *
 * 屏幕上的样子：选择器胶囊里是一条灰带，140 行像素逐行几乎相同（实测均值
 * 163.7±0.3），只有横向有变化 —— 那是一枚 102×102 圆钮的输出被拉成 970×147。
 *
 * 所以：底图纹理按区域共享（它是贵的那一头，上传一次约 5ms），
 * FBO / 回读缓冲 / 输出位图按元素独占（[GlassLensTarget]）。
 *
 * ## 线程模型
 * GL 线程与 EGL 上下文来自进程级的 [GlassLensEngine]，一个上下文服务所有元素。
 */
internal class GlassLensSource {

    internal var textureId = 0
        private set

    internal var srcWidth = 0
        private set

    internal var srcHeight = 0
        private set

    private var uploadedVersion = -1

    /**
     * 失败就永久停用，退回无折射。引擎级失败对所有元素生效。
     *
     * **用 snapshot state 存**，不是普通 Boolean：调用方在组合期读它来决定
     * "这个站点要不要走折射路径"。折射路径里 `onDrawBackdrop` 会**让掉**正常的
     * 背景绘制（背景由折射负责画）。所以一旦折射失效而组合期读不到这个变化，
     * 元素就永远没有背景 —— 屏幕上是整块面板消失。
     *
     * 写成 state 之后，失败会触发重组，站点重新读到 null 锚点，
     * 于是连 blur 兜底一起切回去，前后一致。
     */
    val failed: Boolean
        get() = ownFailed || GlassLensEngine.failed

    private var ownFailed by mutableStateOf(false)

    @Volatile
    private var released = false

    init {
        GlassLensEngine.retain()
    }

    /** 底图是否已就绪。**只能在 GL 线程读**。 */
    internal val ready: Boolean
        get() = uploadedVersion >= 0 && textureId != 0 && srcWidth > 0 && srcHeight > 0

    /**
     * 上传底图。[version] 变化才会真正重传。
     * 传入的 bitmap 必须是可读的（非 HARDWARE config）。
     */
    fun uploadSource(bitmap: Bitmap, version: Int) {
        if (failed || released) return
        GlassLensEngine.post {
            if (failed || released) return@post
            try {
                if (!GlassLensEngine.ensureReady()) return@post
                if (uploadedVersion == version &&
                    srcWidth == bitmap.width &&
                    srcHeight == bitmap.height
                ) {
                    return@post
                }
                ensureTexture()
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                if (srcWidth == bitmap.width && srcHeight == bitmap.height) {
                    // 尺寸未变的重传（滚动限频约 100ms 一次）：子区域更新复用既有
                    // 分配，不再整张重分配 1080p 级纹理（约 10MB/次）。首次上传
                    // （srcWidth 还是 0）与尺寸变化走下面的 texImage2D。
                    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
                } else {
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                }
                val e = GLES20.glGetError()
                if (e != GLES20.GL_NO_ERROR) {
                    fail("uploadSource glError=$e", null)
                    return@post
                }
                srcWidth = bitmap.width
                srcHeight = bitmap.height
                uploadedVersion = version
            } catch (t: Throwable) {
                fail("uploadSource", t)
            }
        }
    }

    fun release() {
        released = true
        GlassLensEngine.post {
            try {
                if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
                srcWidth = 0
                srcHeight = 0
                uploadedVersion = -1
            } catch (_: Throwable) {
                // 释放期异常无意义，忽略
            }
        }
        GlassLensEngine.release()
    }

    private fun ensureTexture() {
        if (textureId != 0) return
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        // CLAMP_TO_EDGE：折射会把采样点推出底图边界，重复或镜像都会露馅
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
    }

    private fun fail(where: String, t: Throwable?) {
        if (!ownFailed) {
            ownFailed = true
            android.util.Log.w("GlassLensSource", "disabled at $where", t)
        }
    }

    /**
     * 底图捕获失败（锚点快照拿不到）：走 snapshot 失败传播。
     *
     * `rememberGlassLensAnchor` 读到 [failed] 后收回锚点，站点重组切回 blur
     * 兜底 —— 与 GL 失败同一条路。曾经捕获失败只 latch 在锚点里的一个普通
     * Boolean 上，组合期读不到，元素从此永久空白。
     */
    internal fun failCapture() {
        fail("capture", null)
    }
}

/**
 * **单个玻璃元素**的折射渲染目标：FBO、回读缓冲、输出位图。
 *
 * 底图不在这里，它按区域共享（见 [GlassLensSource]）。一个元素一个 target，
 * 否则多个元素会互相覆盖对方的输出。
 *
 * **调用方拿到的是上一帧的结果**（[latest]）。这是刻意的：在 Compose 的 `draw()`
 * 里同步等 `glReadPixels` 等于把 UI 线程钉在 GPU 上，而折射差一帧完全看不出来。
 *
 * 实测渲染+回读 195×156 约 0.27ms（中位）/ 0.78ms（p90），Adreno 640 / API 32。
 */
internal class GlassLensTarget(private val source: GlassLensSource) {

    // 渲染目标：必须用 FBO，不能直接画进默认 framebuffer。
    // 默认 framebuffer 就是引擎那个 1×1 的 pbuffer，glViewport 再大也会被裁到
    // 1 像素，glReadPixels 读回来全是 0。
    private var fbo = 0
    private var fboTexture = 0
    private var fboWidth = 0
    private var fboHeight = 0

    private var readBuffer: ByteBuffer? = null

    // 双缓冲：绘制侧在读 latest 的同时 GL 线程可能在写下一帧。
    // 复用同一个 Bitmap 会撕裂，所以两张交替。
    private val outBitmaps = arrayOfNulls<Bitmap>(2)
    private var outIndex = 0

    /** 最近一帧渲染结果。绘制侧只读这个字段。 */
    @Volatile
    var latest: Bitmap? = null
        private set

    /**
     * 新帧就绪时回调，用来请求重绘。
     *
     * 缺了这个会出现「只有滑动时才看得到折射」：静止时没人触发重绘，
     * 异步渲好的帧永远画不出去，屏幕上留的是上一次重绘时那张旧帧。
     */
    @Volatile
    var onFrameReady: (() -> Unit)? = null

    /** 失败就永久停用，退回无折射。区域级/引擎级失败对所有元素生效。 */
    val failed: Boolean
        get() = ownFailed || source.failed

    @Volatile
    private var ownFailed = false

    private val pending = AtomicBoolean(false)
    private var released = false

    init {
        GlassLensEngine.retain()
    }

    /**
     * 待渲染的最新参数。滑动时每帧都会提交，但 GL 线程可能还在画上一帧；
     * 这时**覆盖**而不是丢弃，保证画的总是最新位置，否则指示器会滞后。
     */
    @Volatile
    private var requested: GlassLensParams? = null

    /**
     * 请求渲染一帧。
     *
     * 同一时刻只排一个任务，但参数取的是**最新**的（见 [requested]）：
     * 滑动时每帧提交，若 GL 线程还在画上一帧，新参数覆盖旧的而不是被丢掉。
     *
     * 注意「底图是否就绪」的判断必须放在 GL 线程内：上传是 post 的，
     * 在调用方线程上读那个标志既是竞态也是逻辑错误 —— 那会让紧跟在
     * upload 之后的 submit 全部提前返回，一帧都画不出来。
     */
    fun submit(params: GlassLensParams) {
        if (failed || released) return
        requested = params
        if (!pending.compareAndSet(false, true)) return
        GlassLensEngine.post {
            try {
                if (failed || released) return@post
                // 排到这里时 uploadSource 的 post 一定已执行（同一 handler 顺序保证）
                if (!source.ready) return@post
                val p = requested ?: return@post
                renderBlocking(p)
            } catch (t: Throwable) {
                fail("render", t)
            } finally {
                pending.set(false)
            }
        }
    }

    fun release() {
        released = true
        GlassLensEngine.post {
            try {
                if (fboTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(fboTexture), 0)
                if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                fboTexture = 0
                fbo = 0
                latest = null
                outBitmaps.forEach { it?.recycle() }
                outBitmaps.fill(null)
            } catch (_: Throwable) {
                // 释放期异常无意义，忽略
            }
        }
        // 上下文与 program 是进程级共享的，底图是区域级的，都不随单个元素拆
        GlassLensEngine.release()
    }

    // ---- GL 线程内部 ----

    private fun renderBlocking(p: GlassLensParams) {
        if (!GlassLensEngine.ensureReady()) return
        val srcWidth = source.srcWidth
        val srcHeight = source.srcHeight
        if (srcWidth <= 0 || srcHeight <= 0) return
        val w = p.widthPx
        val h = p.heightPx
        if (w <= 0 || h <= 0) return

        val program = GlassLensEngine.program
        val vb = GlassLensEngine.vertexBuffer ?: return
        if (!ensureFbo(w, h)) return

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val aPos = GLES20.glGetAttribLocation(program, GlassLensShader.ATTR_POSITION)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vb)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source.textureId)
        uniform1i(GlassLensShader.U_TEXTURE, 0)

        uniform2f(GlassLensShader.U_RESOLUTION, w.toFloat(), h.toFloat())
        uniform2f(
            GlassLensShader.U_SRC_ORIGIN,
            p.srcLeftPx / srcWidth,
            p.srcTopPx / srcHeight
        )
        uniform2f(
            GlassLensShader.U_SRC_SCALE,
            w.toFloat() / srcWidth,
            h.toFloat() / srcHeight
        )
        uniform1f(GlassLensShader.U_CORNER_RADIUS, p.cornerRadiusPx)
        uniform1f(GlassLensShader.U_THICKNESS, p.thicknessPx)
        uniform1f(GlassLensShader.U_LENS_AMOUNT, p.lensAmountPx)
        uniform1f(GlassLensShader.U_DISPERSION, p.dispersion)
        uniform1f(GlassLensShader.U_DEPTH_EFFECT, p.depthEffect)
        uniform1f(GlassLensShader.U_VIBRANCY, p.vibrancy)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        val need = w * h * 4
        var buf = readBuffer
        if (buf == null || buf.capacity() != need) {
            buf = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder())
            readBuffer = buf
        }
        buf.position(0)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)

        // 写进「另一张」，避免覆盖绘制侧正在读的那张
        outIndex = 1 - outIndex
        var out = outBitmaps[outIndex]
        if (out == null || out.width != w || out.height != h || out.isRecycled) {
            out?.recycle()
            out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            outBitmaps[outIndex] = out
        }
        buf.position(0)
        out.copyPixelsFromBuffer(buf)

        // 方向说明：glReadPixels 从左下开始读，buffer 第 0 行 = GL 最底行；
        // copyPixelsFromBuffer 把 buffer 第 0 行写进 bitmap 第 0 行（最上行）。
        // 两次反向恰好抵消，所以产物已经是 Canvas 期望的自上而下，
        // **绘制侧不要再翻转**，否则内容上下镜像。
        latest = out
        onFrameReady?.invoke()
    }

    /** 按元素尺寸准备 FBO，尺寸变化才重建。 */
    private fun ensureFbo(w: Int, h: Int): Boolean {
        if (fbo != 0 && fboWidth == w && fboHeight == h) return true

        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            fbo = 0
        }
        if (fboTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(fboTexture), 0)
            fboTexture = 0
        }

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        fboTexture = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST
        )

        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        fbo = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTexture, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            return fail("fbo incomplete: status=$status")
        }
        fboWidth = w
        fboHeight = h
        return true
    }

    private fun uniform1i(name: String, v: Int) {
        GLES20.glUniform1i(GlassLensEngine.uniformLocation(name), v)
    }

    private fun uniform1f(name: String, v: Float) {
        GLES20.glUniform1f(GlassLensEngine.uniformLocation(name), v)
    }

    private fun uniform2f(name: String, a: Float, b: Float) {
        GLES20.glUniform2f(GlassLensEngine.uniformLocation(name), a, b)
    }

    private fun checkGl(stage: String) {
        val e = GLES20.glGetError()
        if (e != GLES20.GL_NO_ERROR) fail("$stage: glError=$e")
    }

    /**
     * 单元素失败。不连带停用引擎：一个元素的 FBO 建不起来（比如尺寸超限）
     * 不代表别的元素也不行。
     */
    private fun fail(stage: String, t: Throwable? = null): Boolean {
        if (!ownFailed) {
            ownFailed = true
            android.util.Log.w("GlassLensRenderer", "element lens disabled at $stage", t)
        }
        return false
    }
}

package com.tyust.course.ui.system.glass

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 关键可行性验证：**API 32 上能不能用 OpenGL ES 2.0 的 GLSL 跑真正的
 * Snell 折射着色器，且完全离屏（不需要 GLSurfaceView）。**
 *
 * 这条路之所以重要：AGSL / RuntimeShader 要 API 33，但 GLSL ES 2.0 自 API 8 就有。
 * 之前的 drawBitmapMesh 方案只是几何形变，无法表达逐像素法线、Snell 折射、
 * Fresnel 与色散 —— 那不是调参能补的，是技术选型错了。
 *
 * 注意：这里编译的是探路阶段那版 Snell/Fresnel 着色器，**不是**最终上线的那个。
 * 上线版是 kyant `RoundedRectRefractionWithDispersion` 的移植，只做位移 + 色散
 * （Snell 残量实测约 1px，而位移项 11px；rim/高光由 Compose 侧负责，着色器里再画
 * 一份就是一圈过曝白壳）。所以这个用例证明的是**平台能力**——ES 2.0 离屏能跑
 * 复杂片元着色器——而不是当前的渲染实现。真正验上线代码的是
 * [GlassLensRendererTest]。
 *
 * 这里用 EGL pbuffer 建离屏上下文，渲染后 glReadPixels 取回，确认：
 *  1. ES 2.0 上下文能建起来
 *  2. 着色器（含 refract()、SDF、高度场、Fresnel、色散）能编译链接
 *  3. 输出确实发生了折射位移，而不是原图直通
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
class GlesRefractionProbeTest {

    private val w = 256
    private val h = 128

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    private fun setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertNotEquals("no EGL display", EGL14.EGL_NO_DISPLAY, display)
        val version = IntArray(2)
        assertTrue("eglInitialize failed", EGL14.eglInitialize(display, version, 0, version, 1))

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        assertTrue(
            "eglChooseConfig failed",
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        )
        assertTrue("no ES2 pbuffer config", num[0] > 0)

        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        assertNotEquals("eglCreateContext failed", EGL14.EGL_NO_CONTEXT, context)

        surface = EGL14.eglCreatePbufferSurface(
            display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0
        )
        assertNotEquals("eglCreatePbufferSurface failed", EGL14.EGL_NO_SURFACE, surface)
        assertTrue(
            "eglMakeCurrent failed",
            EGL14.eglMakeCurrent(display, surface, surface, context)
        )
    }

    private fun teardownEgl() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private fun compile(type: Int, src: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src)
        GLES20.glCompileShader(id)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(id)
            throw AssertionError("shader compile failed: $log")
        }
        return id
    }

    private val vertexSrc = """
        attribute vec2 a_pos;
        varying vec2 v_uv;
        void main() {
            v_uv = a_pos * 0.5 + 0.5;
            gl_Position = vec4(a_pos, 0.0, 1.0);
        }
    """.trimIndent()

    /**
     * 与 Prismal 同类的折射着色器精简版：SDF -> 圆弧高度场 -> 有限差分法线 ->
     * 两界面 Snell 折射 -> 按通道色散取样。这是 drawBitmapMesh 根本做不到的部分。
     */
    private val fragmentSrc = """
        precision highp float;
        varying vec2 v_uv;
        uniform sampler2D u_tex;
        uniform vec2 u_res;
        uniform float u_radius;
        uniform float u_thickness;
        uniform float u_ior;
        uniform float u_normalStrength;
        uniform float u_dispersion;
        uniform float u_lensAmount;

        float sdRoundedRect(vec2 p, vec2 halfSize, float r) {
            vec2 c = abs(p) - (halfSize - vec2(r));
            return length(max(c, 0.0)) - r + min(max(c.x, c.y), 0.0);
        }

        // 圆弧剖面：轮廓处厚度为 0，保证边缘是弯的而不是竖直墙
        float heightAt(float d, float tw) {
            float t = clamp(-d / tw, 0.0, 1.0);
            return sqrt(max(0.0, 2.0 * t - t * t));
        }

        void main() {
            vec2 px = v_uv * u_res;
            vec2 halfSize = u_res * 0.5;
            vec2 p = px - halfSize;

            float d = sdRoundedRect(p, halfSize, u_radius);
            if (d > 0.0) { gl_FragColor = texture2D(u_tex, v_uv); return; }

            float tw = u_thickness;
            float hC = heightAt(d, tw);

            // 有限差分求高度场梯度 -> 逐像素法线
            float hx1 = heightAt(sdRoundedRect(p + vec2(1.0, 0.0), halfSize, u_radius), tw);
            float hx0 = heightAt(sdRoundedRect(p - vec2(1.0, 0.0), halfSize, u_radius), tw);
            float hy1 = heightAt(sdRoundedRect(p + vec2(0.0, 1.0), halfSize, u_radius), tw);
            float hy0 = heightAt(sdRoundedRect(p - vec2(0.0, 1.0), halfSize, u_radius), tw);
            vec2 grad = vec2(hx1 - hx0, hy1 - hy0) * 0.5;
            vec3 N = normalize(vec3(-grad * u_normalStrength, 1.0));

            // 两界面 Snell：空气 -> 玻璃 -> 空气
            vec3 V = vec3(0.0, 0.0, 1.0);
            vec3 refIn = refract(-V, N, 1.0 / u_ior);
            vec3 refOut = refract(refIn, -N, u_ior);

            // Schlick Fresnel
            float r0 = pow((u_ior - 1.0) / (u_ior + 1.0), 2.0);
            float cosVN = max(dot(N, V), 0.0);
            float F = r0 + (1.0 - r0) * pow(1.0 - cosVN, 5.0);

            float strength = hC * (0.5 + F * 0.35);
            vec2 snellOff = refOut.xy * u_thickness * strength / u_res;

            // 透镜位移：可见形变的主项。两界面 Snell 进出几乎抵消，残量是亚像素级，
            // 所以 Prismal / kyant 都是 lensDelta + snell + bulge 相加，
            // Snell 只贡献「玻璃质感」而不是主要位移量。
            // 这里的 1 − sqrt(1 − x²) 与 kyant AGSL 的 circleMap 同形。
            float lensT = clamp(1.0 + d / u_thickness, 0.0, 1.0);
            float lensMag = (1.0 - sqrt(max(0.0, 1.0 - lensT * lensT))) * u_lensAmount;
            vec2 lensDir = normalize(grad + vec2(1e-6));
            vec2 lensOff = lensDir * lensMag / u_res;

            vec2 off = snellOff + lensOff;

            // 逐通道色散：红外移、蓝内移
            vec2 dispDir = normalize(grad + vec2(1e-6));
            vec2 push = dispDir * u_dispersion / u_res;
            float cr = texture2D(u_tex, v_uv + off + push).r;
            float cg = texture2D(u_tex, v_uv + off).g;
            float cb = texture2D(u_tex, v_uv + off - push).b;

            gl_FragColor = vec4(cr, cg, cb, 1.0);
        }
    """.trimIndent()

    /** 造一张竖条纹纹理：折射会把直条纹弯曲，便于判定是否真的发生位移。 */
    private fun stripeBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val on = ((x / 8) % 2) == 0
                bmp.setPixel(x, y, if (on) 0xFF202020.toInt() else 0xFFE0E0E0.toInt())
            }
        }
        return bmp
    }

    /**
     * 每帧成本：GL 渲染 + glReadPixels 回读 + 组装 Bitmap，按导航指示器实际尺寸
     * （195×156 @ MuMu density 3.0）测。这个数字决定折射能不能跟着滑动的元素走：
     * 若够便宜就每帧重渲，否则只能锚定静止区域。
     */
    @Test
    fun measurePerFrameRenderAndReadbackCost() {
        setupEgl()
        try {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)

            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
            )
            val src = stripeBitmap()
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, src, 0)

            val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val vb = ByteBuffer.allocateDirect(quad.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            vb.put(quad).position(0)

            // 指示器实际尺寸
            val ew = 195
            val eh = 156
            val buf = ByteBuffer.allocateDirect(ew * eh * 4).order(ByteOrder.nativeOrder())
            val outBmp = Bitmap.createBitmap(ew, eh, Bitmap.Config.ARGB_8888)

            fun oneFrame() {
                GLES20.glViewport(0, 0, ew, eh)
                GLES20.glUseProgram(prog)
                val aPos = GLES20.glGetAttribLocation(prog, "a_pos")
                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vb)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "u_tex"), 0)
                GLES20.glUniform2f(
                    GLES20.glGetUniformLocation(prog, "u_res"), ew.toFloat(), eh.toFloat()
                )
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "u_radius"), eh / 2f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "u_thickness"), 30f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "u_ior"), 1.5f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "u_normalStrength"), 1.2f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "u_dispersion"), 3f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "u_lensAmount"), 42f)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }

            fun bench(label: String, iters: Int, warmup: Int, block: () -> Unit) {
                repeat(warmup) { block() }
                val s = LongArray(iters)
                for (i in 0 until iters) {
                    val t0 = System.nanoTime()
                    block()
                    s[i] = System.nanoTime() - t0
                }
                s.sort()
                println(
                    "GLCOST $label: median=${"%.3f".format(s[iters / 2] / 1e6)}ms " +
                        "p90=${"%.3f".format(s[(iters * 9 / 10).coerceAtMost(iters - 1)] / 1e6)}ms"
                )
            }

            bench("render only ${ew}x$eh", 60, 20) {
                oneFrame()
                GLES20.glFinish()
            }
            bench("render + glReadPixels ${ew}x$eh", 60, 20) {
                oneFrame()
                buf.position(0)
                GLES20.glReadPixels(0, 0, ew, eh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            }
            bench("render + readback + copyPixelsFromBuffer", 60, 20) {
                oneFrame()
                buf.position(0)
                GLES20.glReadPixels(0, 0, ew, eh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                buf.position(0)
                outBmp.copyPixelsFromBuffer(buf)
            }

            outBmp.recycle()
            src.recycle()
        } finally {
            teardownEgl()
        }
    }

    @Test
    fun gles20_compilesSnellRefractionShader_andActuallyRefracts() {
        setupEgl()
        try {
            val glVersion = GLES20.glGetString(GLES20.GL_VERSION)
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
            println("GLES version=$glVersion renderer=$renderer")
            assertTrue("expected an ES 2.0+ context", glVersion != null)

            // --- 编译链接 ---
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
            assertEquals(
                "program link failed: ${GLES20.glGetProgramInfoLog(prog)}",
                GLES20.GL_TRUE, linked[0]
            )

            // --- 上传纹理 ---
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
            )
            val src = stripeBitmap()
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, src, 0)

            // --- 全屏四边形 ---
            val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val vb = ByteBuffer.allocateDirect(quad.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            vb.put(quad).position(0)

            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(prog)

            val aPos = GLES20.glGetAttribLocation(prog, "a_pos")
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vb)

            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "u_tex"), 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "u_res"), w.toFloat(), h.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "u_radius"), h / 2f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "u_thickness"), 28f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "u_ior"), 1.5f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "u_normalStrength"), 1.2f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "u_dispersion"), 3f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "u_lensAmount"), 40f)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            assertEquals("GL error after draw", GLES20.GL_NO_ERROR, GLES20.glGetError())

            // --- 读回 ---
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            assertEquals("GL error after readPixels", GLES20.GL_NO_ERROR, GLES20.glGetError())

            fun outAt(x: Int, y: Int): Triple<Int, Int, Int> {
                val i = (y * w + x) * 4
                return Triple(
                    buf.get(i).toInt() and 0xFF,
                    buf.get(i + 1).toInt() and 0xFF,
                    buf.get(i + 2).toInt() and 0xFF
                )
            }

            // 中线（GL 原点在左下，取 h/2 即形状中心，条纹应被边缘弯曲）
            val mid = h / 2
            var edgeDiffers = 0
            var dispersionSeen = 0
            for (x in 2 until 40) {
                val (r, g, b) = outAt(x, mid)
                val srcOn = ((x / 8) % 2) == 0
                val srcVal = if (srcOn) 0x20 else 0xE0
                // 边缘带内输出应与原位置取样不同（发生了位移）
                if (kotlin.math.abs(g - srcVal) > 40) edgeDiffers++
                // 逐通道不同 => 色散生效
                if (kotlin.math.abs(r - b) > 12) dispersionSeen++
            }
            println("edgeDiffers=$edgeDiffers dispersionSeen=$dispersionSeen")
            assertTrue(
                "no displacement detected in the rim band; shader ran but did not refract",
                edgeDiffers > 4
            )
            assertTrue(
                "no per-channel dispersion detected",
                dispersionSeen > 2
            )

            // 形状中心应基本是直通（高度场平坦，位移趋零）
            val (_, gc, _) = outAt(w / 2, mid)
            val srcCenterOn = (((w / 2) / 8) % 2) == 0
            val srcCenter = if (srcCenterOn) 0x20 else 0xE0
            assertTrue(
                "centre should be near pass-through, got g=$gc expected~$srcCenter",
                kotlin.math.abs(gc - srcCenter) < 90
            )

            src.recycle()
        } finally {
            teardownEgl()
        }
    }
}

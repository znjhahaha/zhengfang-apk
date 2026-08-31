package com.tyust.course.ui.system.glass

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 进程级共享的 GL 环境：一条线程、一个 EGL 上下文、一个着色器程序。
 *
 * ## 为什么必须共享
 * 建一个 EGL 上下文要 10–50ms，还各自占一条线程。玻璃元素有十来处，
 * 一处一个上下文就是几百毫秒的启动开销和十几条线程 —— 这条路走不通。
 * 而上下文里真正**能**共享的东西（program、顶点缓冲）恰好都是只读的，
 * 每元素独占的只有纹理和 FBO，那些留在 [GlassLensRenderer] 里。
 *
 * ## 线程约束
 * EGL 上下文只属于绑定它的那条线程。所以所有 GL 调用都必须 post 到 [post]
 * 提供的那条线程上 —— 包括各 renderer 自己的纹理/FBO 操作。
 *
 * 失败一次就整体停用：[failed] 置位后所有 renderer 一起退回无折射，
 * 不做每帧重试（那只会把一次性的兼容问题变成持续掉帧）。
 */
internal object GlassLensEngine {

    private const val TAG = "GlassLensEngine"

    private val thread by lazy {
        HandlerThread("GlassLensGL").apply { start() }
    }
    private val handler by lazy { Handler(thread.looper) }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    /** 折射程序。所有 renderer 共用，uniform 逐次设置。 */
    var program = 0
        private set

    /** 全屏四边形。所有 renderer 共用，只读。 */
    var vertexBuffer: FloatBuffer? = null
        private set

    @Volatile
    var failed = false
        private set

    /** 引用计数：归零时才拆上下文，避免最后一个元素离屏后又立刻有新的进来。 */
    private var refCount = 0

    /** 把一段 GL 工作排到共享线程上。 */
    fun post(block: () -> Unit) {
        if (failed) return
        handler.post {
            if (failed) return@post
            block()
        }
    }

    fun retain() {
        post { refCount++ }
    }

    /**
     * 释放一个引用。归零时**不**拆上下文：重建代价远大于留着它，
     * 而这块资源是进程级的、有界的（一个上下文 + 一个 program）。
     * 各 renderer 自己的纹理/FBO 在它们的 release() 里已经删掉了。
     */
    fun release() {
        post { if (refCount > 0) refCount-- }
    }

    /**
     * 确保上下文与程序就绪。**必须在共享线程上调用。**
     * 返回 false 表示这台设备上折射不可用。
     */
    fun ensureReady(): Boolean {
        if (failed) return false
        if (context != EGL14.EGL_NO_CONTEXT) return true

        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return fail("eglGetDisplay")
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return fail("eglInitialize")

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
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] <= 0) {
            return fail("eglChooseConfig")
        }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (context == EGL14.EGL_NO_CONTEXT) return fail("eglCreateContext")

        // pbuffer 尺寸无关紧要：真正的渲染目标是各 renderer 自己的 FBO。
        // 但 EGL 要求有个当前 surface 才能 makeCurrent。
        surface = EGL14.eglCreatePbufferSurface(
            display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        if (surface == EGL14.EGL_NO_SURFACE) return fail("eglCreatePbufferSurface")
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            return fail("eglMakeCurrent")
        }

        program = buildProgram() ?: return fail("buildProgram")

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        vertexBuffer = ByteBuffer.allocateDirect(quad.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(quad)
                position(0)
            }
        return true
    }

    private fun buildProgram(): Int? {
        val vs = compile(GLES20.GL_VERTEX_SHADER, GlassLensShader.VERTEX) ?: return null
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, GlassLensShader.FRAGMENT) ?: return null
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (ok[0] != GLES20.GL_TRUE) {
            Log.w(TAG, "program link failed: ${GLES20.glGetProgramInfoLog(p)}")
            GLES20.glDeleteProgram(p)
            return null
        }
        return p
    }

    private fun compile(type: Int, src: String): Int? {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src)
        GLES20.glCompileShader(id)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.w(TAG, "shader compile failed: ${GLES20.glGetShaderInfoLog(id)}")
            GLES20.glDeleteShader(id)
            return null
        }
        return id
    }

    fun fail(stage: String, t: Throwable? = null): Boolean {
        if (!failed) {
            failed = true
            Log.w(TAG, "glass lens disabled at $stage", t)
        }
        return false
    }

    /** uniform location 缓存：`glGetUniformLocation` 每帧每元素调十几次不划算。 */
    private val uniformLocations = HashMap<String, Int>()

    fun uniformLocation(name: String): Int =
        uniformLocations.getOrPut(name) {
            GLES20.glGetUniformLocation(program, name)
        }
}

package com.tyust.course.ui.system.glass

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderNode
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import com.tyust.course.BuildConfig
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CanvasHolder
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.tyust.course.manager.AppearanceSettingsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * API 31/32 上的真折射：离屏 OpenGL ES 2.0。
 *
 * ## 为什么走 GL
 * `RuntimeShader`（AGSL）要 API 33，没有兼容库。但 GLSL ES 2.0 自 API 8 就有，
 * 这才是 33 以下拿到逐像素着色的正路。参见 [GlassLensShader] 里对
 * 「为什么几何形变（drawBitmapMesh）做不到」的说明。
 *
 * ## 结构
 * ```
 * [GlassLensAnchor]  锚定一块不随元素移动的区域（如整条导航条）
 *      │  绘制结束后录制 RenderNode；专用线程完成位图回读
 *      ▼
 * [GlassLensRenderer]  自带 GL 线程；底图上传一次，逐帧只改 uniform
 *      │  渲染 + glReadPixels -> Bitmap（实测 0.27ms 中位 @ Adreno 640）
 *      ▼
 * Modifier.glassLens  校正上一帧的采样位置，并异步提交下一帧
 * ```
 *
 * UI 线程不等待 GPU。无折射位移时直接重放实时图层；有折射时，旧帧的文字
 * 保持在背景原位，避免随着滑块移动，直到新帧就绪。
 */

private const val TAG = "GlassLens"

/** 只在 31/32 生效：33+ 走平台 AGSL，30 及以下连 RenderEffect 都没有。 */
fun isGlassLensApplicable(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

/**
 * 折射的取样源。挂在一块**不随内部元素移动**的区域上，区域内元素共享同一张底图。
 *
 * 若挂在移动的元素上，每帧都要重新快照（实测约 3ms），必然掉帧。
 */
@android.annotation.SuppressLint("NewApi")
@Stable
class GlassLensAnchor internal constructor(
    /**
     * 底图的绘制序列。**必须画出元素在屏幕上实际压着的东西**，包括各层自己的
     * tint / surface，而不只是原始 backdrop。
     *
     * 这一点是踩出来的：只快照 `combined(backdrop, tabsBackdrop)` 时，捕获到的
     * 是接近纯白的壁纸（实测元素下方一行 R 241–245、相邻差均值 0.02），而屏幕上
     * 同一位置是 157 —— 差的正是轨道层的 blur + containerColor。平场折射后仍是
     * 平场，所以屏幕上看不出任何折射。
     */
    internal var drawSource: DrawScope.(LayoutCoordinates) -> Unit,
    internal val density: Density,
    /**
     * 底图纹理，**区域内所有元素共享**。
     *
     * 渲染目标不在这里：每个元素自己一份 [GlassLensTarget]。共用一个 FBO 会让
     * 每个元素画出最后一个渲染完的那个元素的输出（实测：选择器胶囊里是一枚
     * 102×102 圆钮被拉成 970×147 的灰带）。见 [GlassLensSource] 的注释。
     */
    internal val source: GlassLensSource,
    /** 站点名，出现在 [warnUnanchored] 的报错里，用来指认是哪块区域没挂上。 */
    internal val tag: String = "anon",
    internal var drawOverlaySource: (DrawScope.(LayoutCoordinates) -> Unit)? = null,
    internal var maxCapturePixels: Int = Int.MAX_VALUE,
    internal var rasterizeOverlayOnCpu: Boolean = false
) {

    internal var coordinates: LayoutCoordinates? by mutableStateOf(null)
        private set

    internal var sizePx: IntSize by mutableStateOf(IntSize.Zero)
        private set

    /** 调用方在内容变化（选中项、壁纸、主题）时 bump。 */
    internal var version by mutableIntStateOf(0)
        private set

    internal var requireCurrentSource = false
    internal var retainCompatibleBackground = false

    private var uploadedVersion = -1
    private var overlayVersion by mutableIntStateOf(0)
    private var uploadedOverlayVersion = -1
    private var uploadSequence = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var captureQueued = false
    private var captureInFlight = false
    @Volatile private var disposed = false
    private var recordedBackground: RenderNode? = null
    private var recordedOverlay: RenderNode? = null
    internal var captureFrame: GlassLensCaptureFrame? by mutableStateOf(null)
        private set
    internal var sourceFrame: GlassLensCaptureFrame? by mutableStateOf(null)
        private set
    internal var timedSourceGeneration = -1
    internal var lastReadbackOnMain = false
        private set
    internal var lastReadbackNanos = 0L
        private set
    private var cachedBackground: Bitmap? = null
    private var cachedOverlay: Bitmap? = null
    private var lastInvalidationMillis = 0L
    internal var backgroundCaptureCount = 0
        private set
    internal var overlayCaptureCount = 0
        private set
    private var captureFailed = false

    internal fun onPositioned(coords: LayoutCoordinates) {
        coordinates = coords
        unattachedDraws = 0
        val s = IntSize(coords.size.width, coords.size.height)
        if (s != sizePx) sizePx = s
    }

    /** 内容变了，下一帧重新快照上传。 */
    fun invalidate(throttleMillis: Long = 0L) {
        if (invalidateBackgroundVersion(throttleMillis)) overlayVersion++
    }

    /** Page scrolling changes the background, while settled glyphs can keep their snapshot. */
    fun invalidateBackground(throttleMillis: Long = 0L) {
        invalidateBackgroundVersion(throttleMillis)
    }

    private fun invalidateBackgroundVersion(throttleMillis: Long): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (throttleMillis > 0 && now - lastInvalidationMillis < throttleMillis) return false
        lastInvalidationMillis = now
        version++
        return true
    }

    /** Animated glyphs only repaint the small overlay, without replaying blur and the page. */
    fun invalidateOverlay() {
        androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { overlayVersion++ }
    }

    /** Live replay also needs invalidation after a frozen glyph picture is replaced. */
    internal fun liveSourceRevision(): Long = (version.toLong() shl 32) or (overlayVersion.toLong() and 0xffffffffL)

    private var warnedUnanchored = false

    /** 连续多少次 draw 仍然没有 coordinates。挂上即清零。 */
    private var unattachedDraws = 0

    /**
     * draw 期发现锚点还没有 coordinates。
     *
     * 立刻吼是误报：分段控件的锚点经 `SideEffect` + state 绕一帧才挂上
     * （LiquidSelectionComponents 的 segLensAnchor），首个 draw 必然读不到
     * coordinates —— 实测每个分段控件都会在首帧吼一次。数到第 3 次仍未挂上
     * 才算真的没挂。组合期自检（等 2 帧）与这里互为冗余，谁先到谁报。
     */
    internal fun noteDrawUnanchored() {
        unattachedDraws++
        if (unattachedDraws >= 3) warnUnanchored()
    }

    /**
     * 只吼一次，别把 logcat 刷满。已经挂上的锚点不吼（组合期自检可能在
     * `glassLensAnchor` 尚未布局完成时先来问一次）。
     *
     * debug 构建用 `Log.wtf` 让 Logcat 高亮：这是调用方的编码错误，而它的
     * 后果（整块元素无背景）属于"看截图猜不出来"的那一级。
     */
    internal fun warnUnanchored() {
        if (warnedUnanchored || coordinates != null) return
        warnedUnanchored = true
        val msg =
            "lens region '$tag' was never attached with Modifier.glassLensAnchor — " +
                "elements using it will have no background at all"
        if (BuildConfig.DEBUG) {
            android.util.Log.wtf(TAG, msg)
        } else {
            android.util.Log.e(TAG, msg)
        }
    }

    /** 确保底图已上传。返回底图尺寸，未就绪返回 null。 */
    internal fun ensureSource(): IntSize? {
        if (captureFailed || source.failed) return null
        val size = sizePx
        if (size.width <= 0 || size.height <= 0) return null
        val coords = coordinates?.takeIf { it.isAttached } ?: return null
        if (needsCapture(coords)) queueCapture()
        if (requireCurrentSource) {
            val currentIdentity = GlassLensFrameIdentity(
                GlassLensCaptureGeometry.from(coords).fitPixelBudget(maxCapturePixels), overlayVersion)
            val compatible = canRetainLensFrame(sourceFrame?.identity, currentIdentity, retainCompatibleBackground)
            if (sourceFrame?.geometry != currentIdentity.geometry ||
                (!compatible && (sourceFrame?.sourceRevision != version ||
                    (retainCompatibleBackground && sourceFrame?.overlayRevision != overlayVersion)))) return null
        }
        return size
    }

    private fun needsCapture(coords: LayoutCoordinates): Boolean =
        captureFrame?.geometry != GlassLensCaptureGeometry.from(coords).fitPixelBudget(maxCapturePixels) ||
            uploadedVersion != version || uploadedOverlayVersion != overlayVersion

    private fun queueCapture() {
        if (disposed || captureFailed || captureQueued || captureInFlight) return
        captureQueued = true
        // Capture after this draw traversal. The header may be drawn before the
        // list it samples; replaying its layer inside draw() reads the old layout.
        mainHandler.post {
            captureQueued = false
            if (disposed || source.failed) return@post
            val coords = coordinates?.takeIf { it.isAttached } ?: return@post
            if (!needsCapture(coords)) return@post
            val geometry = GlassLensCaptureGeometry.from(coords).fitPixelBudget(maxCapturePixels)
            val size = geometry.size
            if (size.width <= 0 || size.height <= 0) return@post
            val geometryChanged = captureFrame?.geometry != geometry
            val backgroundChanged = geometryChanged || uploadedVersion != version || recordedBackground == null
            val overlay = drawOverlaySource
            val overlayChanged = overlay != null &&
                (geometryChanged || uploadedOverlayVersion != overlayVersion || recordedOverlay == null)
            try {
                val recordStarted = System.nanoTime()
                val background = if (backgroundChanged) {
                    backgroundCaptureCount++
                    recordGlassLensSource("$tag-background", size, density, coords.size) { drawSource(coords) }
                        .also { recordedBackground = it }
                } else null
                val frozenOverlay = if (overlayChanged && rasterizeOverlayOnCpu) {
                    recordGlassLensPicture(size, density, coords.size) { overlay(coords) }
                } else null
                val foreground = if (overlayChanged) {
                    overlayCaptureCount++
                    val layer = if (frozenOverlay == null) {
                        recordGlassLensSource("$tag-overlay", size, density, coords.size) { overlay(coords) }
                    } else {
                        recordGlassLensSource("$tag-overlay", size, density) {
                            drawContext.canvas.nativeCanvas.drawPicture(frozenOverlay)
                        }
                    }
                    layer.also { recordedOverlay = it }
                } else null
                val combined = recordGlassLensSource("$tag-fallback", size, density) {
                    recordedBackground?.let { drawContext.canvas.nativeCanvas.drawRenderNode(it) }
                    if (overlay != null) recordedOverlay?.let { drawContext.canvas.nativeCanvas.drawRenderNode(it) }
                }
                GlassLensCaptureObserver.onTiming?.invoke(tag, "record", System.nanoTime() - recordStarted)
                val generation = ++uploadSequence
                val queuedAt = System.nanoTime()
                val recording = GlassLensCaptureFrame(generation, geometry, combined, queuedAt, version, overlayVersion)
                captureFrame = recording
                uploadedVersion = version
                uploadedOverlayVersion = overlayVersion
                captureInFlight = true
                val readback = readback@{
                    // A tab can disappear while another region is being captured.
                    // Do not spend GPU time reading an outgoing page nobody can use.
                    if (disposed) return@readback
                    val started = System.nanoTime()
                    GlassLensCaptureObserver.onTiming?.invoke(tag, "capture-queue", started - queuedAt)
                    lastReadbackOnMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
                    val bitmap = try {
                        android.os.Trace.beginSection("GlassReadback:$tag")
                        if (background != null) cachedBackground = readGlassLensPixels(background, size, tag)
                        if (foreground != null) cachedOverlay = if (frozenOverlay != null)
                            readGlassLensPixels(frozenOverlay, size, tag)
                        else readGlassLensPixels(foreground, size, tag)
                        if (overlay == null) cachedBackground else {
                            val bg = cachedBackground
                            val fg = cachedOverlay
                            if (bg == null || fg == null) null else {
                                bg.copy(Bitmap.Config.ARGB_8888, true)?.also {
                                    android.graphics.Canvas(it).drawBitmap(fg, 0f, 0f, null)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        android.util.Log.w(TAG, "backdrop readback failed", t)
                        null
                    } finally {
                        lastReadbackNanos = System.nanoTime() - started
                        GlassLensCaptureObserver.onTiming?.invoke(tag, "readback", lastReadbackNanos)
                        android.os.Trace.endSection()
                    }
                    mainHandler.post {
                        captureInFlight = false
                        if (!disposed) {
                            if (bitmap == null) {
                                failCapture()
                            } else {
                                GlassLensCaptureObserver.onCaptured?.invoke(tag, recording, bitmap)
                                source.uploadSource(bitmap, generation) {
                                    GlassLensCaptureObserver.onTiming?.invoke(tag, "capture-to-upload", System.nanoTime() - queuedAt)
                                    mainHandler.post {
                                        if (!disposed && (sourceFrame?.generation ?: -1) < recording.generation) sourceFrame = recording
                                    }
                                }
                            }
                            // A final scroll/layout update must not be lost while a
                            // readback is running. Keep one latest request per anchor.
                            coordinates?.takeIf { it.isAttached && needsCapture(it) }?.let { queueCapture() }
                        }
                    }
                    Unit
                }
                if (background == null && foreground != null) GlassLensCaptureDispatcher.postOverlay(readback)
                else GlassLensCaptureDispatcher.post(readback)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "backdrop recording failed", t)
                failCapture()
            }
        }
    }

    private fun failCapture() {
        captureFailed = true
        source.failCapture()
        clearFallback()
    }

    /** Release after pending capture work; published bitmaps remain owned by their draw lists. */
    internal fun clearFallback() {
        disposed = true
        mainHandler.removeCallbacksAndMessages(null)
        captureFrame = null
        sourceFrame = null
        recordedBackground = null
        recordedOverlay = null
        GlassLensCaptureDispatcher.post { cachedBackground = null; cachedOverlay = null }
    }
}

/**
 * 把一段绘制经过高斯模糊后画出，边缘按 CLAMP 处理。
 *
 * ## 为什么不复用 Compose 侧那层模糊
 * 轨道的模糊层是**有形状的**（`shape = { Capsule() }`）。把它 replay 进底图后，
 * 胶囊的裁边正好压在指示器边缘上 —— 指示器与轨道内侧的间隙只有几 dp，第一个
 * 标签处左端更是完全重合。底图里于是有一条沿着指示器轮廓的半透明边界，折射的
 * 斜坡再把它放大，屏幕上就是那圈灰罩。
 *
 * 这里画的是**铺满锚点的矩形**，指示器附近没有任何裁边。
 *
 * CLAMP 复制边缘像素，避免 DECAL 将边界外的透明区域混入模糊。
 * 底图自身的透明度仍由折射着色器保留。
 *
 * 只在快照时执行（内容变化才重拍），不是逐帧成本。
 */
internal fun DrawScope.drawBlurred(radiusPx: Float, block: DrawScope.() -> Unit) {
    val canvas = drawContext.canvas.nativeCanvas
    if (radiusPx <= 0f ||
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        !canvas.isHardwareAccelerated ||
        size.minDimension < 1f
    ) {
        block()
        return
    }
    drawBlurredApi31(canvas, radiusPx, block)
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
private fun DrawScope.drawBlurredApi31(
    canvas: android.graphics.Canvas,
    radiusPx: Float,
    block: DrawScope.() -> Unit
) {
    val w = size.width.toInt().coerceAtLeast(1)
    val h = size.height.toInt().coerceAtLeast(1)
    val node = android.graphics.RenderNode("glassLensBlur")
    node.setPosition(0, 0, w, h)
    node.setRenderEffect(
        android.graphics.RenderEffect.createBlurEffect(
            radiusPx, radiusPx, android.graphics.Shader.TileMode.CLAMP
        )
    )
    val recording = node.beginRecording()
    try {
        CanvasHolder().drawInto(recording) {
            CanvasDrawScope().draw(
                density = this@drawBlurredApi31,
                layoutDirection = layoutDirection,
                canvas = this,
                size = size,
                block = block
            )
        }
    } finally {
        node.endRecording()
    }
    canvas.drawRenderNode(node)
}

/**
 * 建一个锚点，并在离开组合时释放 GL 资源。
 */
@Composable
fun rememberGlassLensAnchor(
    /** 站点名，只在锚点没挂上时的报错里出现。 */
    tag: String = "anon",
    overlaySource: (DrawScope.(LayoutCoordinates) -> Unit)? = null,
    maxCapturePixels: Int = Int.MAX_VALUE,
    rasterizeOverlayOnCpu: Boolean = false,
    drawSource: DrawScope.(LayoutCoordinates) -> Unit
): GlassLensAnchor? {
    if (!isGlassLensApplicable()) return null
    val density = LocalDensity.current
    val anchor = remember(density) {
        GlassLensAnchor(drawSource, density, GlassLensSource(), tag)
    }
    // lambda 每次组合都是新实例，但 anchor 要保持同一个（它持有 GL 资源），
    // 所以逐次刷新引用而不是把 lambda 放进 remember 的 key
    anchor.drawSource = drawSource
    anchor.drawOverlaySource = overlaySource
    anchor.maxCapturePixels = maxCapturePixels
    anchor.rasterizeOverlayOnCpu = rasterizeOverlayOnCpu
    if (BuildConfig.DEBUG) {
        // 组合期自检：锚点忘了挂 Modifier.glassLensAnchor 是编码错误，但它的
        // 后果（元素无背景）只在区域内首个元素 draw 时才暴露；区域内暂时没有
        // 折射元素时（如弹窗还没开过）就一直静默。等两帧（布局跑完）主动查一次。
        // 33+ 在函数开头就早退了，这里零开销。
        LaunchedEffect(anchor) {
            repeat(2) { withFrameNanos { } }
            anchor.warnUnanchored()
        }
    }
    // 换壁纸必须重拍，**这一条建在锚点里，不交给调用方**。
    //
    // 壁纸是每一张底图的最底层：没有哪个区域的底图能在壁纸变了之后还是对的。
    // 曾经这是各调用点自己的 key（MainActivity 的两个区域写了
    // `AppearanceSettingsManager.style`，底栏和分段控件没写），于是漏的那两个
    // 在换壁纸后永远refract 着旧壁纸。
    //
    // 实测（API 32，「背景」弹窗切 预设/颜色/图片 三段）：切页会由
    // `LaunchedEffect(tabIndex)` 立刻换壁纸，而分段控件的底图只 key 了选中项。
    // 把底图与屏幕逐张对比，底图**每次都等于上一张屏幕**：
    // 与上一张的差 6.2 / 10.8 / 6.6，与当前的差 42.7 / 47.6 / 18.7。
    // 屏幕上就是用户报的"切换的时候会取样上一个"。
    //
    // ## 为什么要拍两次
    //
    // 只立刻拍一次不够：`version++` 之后下一次 draw 才会重拍，而那一帧壁纸层
    // （LayerBackdrop 的 RenderNode）可能还没按新样式重录一遍，拍到的仍是旧的。
    // 图片壁纸更慢 —— 位图是异步解码的。所以照底栏换页的做法：立刻拍一张
    // （总比留着上一张好），稍后再补一张。collectLatest 让连续切换只有最后一次
    // 走到底。
    //
    // 在 snapshotFlow **里**读 style，不在组合期读：组合期读会让每个带折射的
    // 子树都订阅壁纸。
    val resolvedDark = com.tyust.course.ui.theme.LocalAppAppearance.current.isDark
    LaunchedEffect(anchor, resolvedDark) {
        snapshotFlow { AppearanceSettingsManager.style to AppearanceSettingsManager.themeMode }.collectLatest {
            anchor.invalidate()
            delay(240)
            anchor.invalidate()
        }
    }
    DisposableEffect(anchor) {
        onDispose {
            anchor.source.release()
            anchor.clearFallback()
        }
    }
    // 底图彻底失败（GL 挂了、快照拿不到）时把锚点收回去，让站点整体退回 blur 路径。
    // 不收的话站点会保持折射路径，而折射路径把背景绘制让给了折射本身 ——
    // 结果是元素没有任何背景。`failed` 是 snapshot state，所以这里读得到变化。
    if (anchor.source.failed) return null
    return anchor
}

/** 标记锚点区域，记录它的窗口位置与尺寸。 */
fun Modifier.glassLensAnchor(anchor: GlassLensAnchor?, prewarm: Boolean = false): Modifier =
    if (anchor == null) this else onGloballyPositioned {
        anchor.onPositioned(it)
        if (prewarm) anchor.ensureSource()
    }

/**
 * 折射的光学参数。
 *
 * 语义与库 AGSL 的 `lens(refractionHeight, refractionAmount, chromaticAberration)`
 * 一一对应，好处是 API31/32 与 API33+ 可以从**同一份** [GlassMaterialSpec]
 * 推出参数，两条路不会各自漂移。见 [glassLensOpticsFrom]。
 *
 * 这里**没有** rim / 高光 / 光源方向：轮廓光由 Compose 侧的 Highlight、
 * InnerShadow、Shadow 承担，与库一致。着色器里再加一份会得到一圈过曝白壳。
 */
data class GlassLensOptics(
    val cornerRadiusPx: Float,
    /**
     * 边缘斜坡宽度，**绝对像素**，语义同库的 `refractionHeight`。
     *
     * ## 为什么是绝对像素而不是比例
     *
     * 曾经这里是「占 min(w,h)/2 的比例」，想法是让"斜坡不能吃掉整个形状"这条
     * 约束无法被绕过。但比例的分母来自调用方传的**标称**尺寸，而 [Modifier.glassLens]
     * 施加时乘的是元素**实测**尺寸 —— 两者不一致时比例就被悄悄缩放了。
     *
     * 实测踩到过：导航指示器标称 163px、实测 152px，差 6.9%，于是斜坡比预期窄
     * 6.9%，而位移是绝对值不受影响，`位移/斜坡` 从配方的 1.4 漂到 1.504。
     * 单元测试查不出来，因为它只看得到标称值那一侧。
     *
     * 改成绝对像素后，两个量都是绝对的，比值恒等于配方比值，与任何尺寸无关。
     * 上限仍在 [Modifier.glassLens] 里按实测尺寸施加，
     * 且**位移会同比例跟着缩**（见那边），所以夹住也不改变比值。
     */
    val thicknessPx: Float,
    /**
     * 位移幅度（像素），语义同库的 `refractionAmount`。
     *
     * **可以大于斜坡宽度**，库就是这么用的：LiquidBottomTabs 的指示器是
     * `lens(10dp, 14dp)`，amount/height = 1.4；Glass playground 默认更极端，
     * 位移 2× 于斜坡。曾经这里被 clamp 到 `height × 0.5`，位移只有库的 1/3，
     * 折射弱到看不见 —— 当时误以为是底图没有高频内容，还为此在着色器里加了
     * 一层伪造微纹理。两个都是错的，都已改掉。
     */
    val lensAmountPx: Float,
    /** 色散倍数，无量纲。0 = 关闭。库开启色散时等价于 1。 */
    val dispersion: Float,
    /** 把梯度混向径向，同库的 `depthEffect`。库的底部标签栏用 0。 */
    val depthEffect: Float = 0f,
    /**
     * 饱和度提升，对应库那层 `effects { vibrancy() }`。
     *
     * **只有调用点真的调了 `vibrancy()` 才该 > 1。** 默认 1.28 是给底栏/分段控件
     * 那些确实调了的站点用的；开关、滑块 thumb、取色摘钮的 effects 里只有
     * `blur` 与 `lens`，没有 vibrancy，那几处必须传 1。
     *
     * 实测过差别：API 32 上开关按住时折射出 `16d245`，而 API 37 同一处是
     * `34c759`（= 轨道原色）。纯色轨道折射出来本该还是同一个绿，1.28 把它推得
     * 更饱和更暗，一眼能看出两台机器不是一个颜色。
     */
    val vibrancy: Float = 1.28f,
    val maxRenderPixels: Int = Int.MAX_VALUE
)

/**
 * 斜坡宽度占半短边的比例上限。
 *
 * 取 1.0 —— 与库的约束**同值**：`refractionHeight.coerceIn(0, minCornerRadiusPx)`，
 * 在胶囊/圆钮上 minCornerRadius 就是 min(w,h)/2。两条路同一个上限，才不会在
 * 上限生效的那些状态下悄悄分叉。
 *
 * ## 为什么从 0.5 改回 1.0
 *
 * 0.5 的理由是"实测 0.6 会退化成空心灰环"。但静止态根本碰不到上限（芯片的
 * heightScale 是 0.62 的 floor，12dp × 0.62 = 7.44dp，占半短边 0.44），
 * 所以那个上限只在**按压**时生效：progress = 1 → 12dp = 36px，半短边 51px，
 * 占 0.71。0.5 会把它夹到 25.5px，而 33+ 那边 36 ≤ 51 原样通过 ——
 * 按下去时 31/32 的斜坡只有 33+ 的 71%。
 *
 * 改成 1.0 后在 API 32 上按住圆钮实拍确认：边缘挤压干净、色散彩边正常，
 * 没有空心环。当年 0.6 那次退化应当另有原因（那时底图与渲染目标还共用一个
 * 渲染器，输出会串站点，见 [GlassLensSource]），不再作为依据。
 *
 * **不要在这个上限上单独夹斜坡**：位移会继续涨，`位移/斜坡` 比值随之漂移，
 * 边缘挤压越来越陡。要夹就夹整体强度，见 [glassLensOpticsFrom] 的 maxScale。
 */
internal const val GLASS_LENS_THICKNESS_FRACTION_MAX = 1.0f

/**
 * 折射要跟随的形变，与库那层 `drawBackdrop(layerBlock = …)` 的形变**必须逐帧一致**。
 *
 * 为什么连平移一起带：`glassLens` 挂在 `drawBackdrop` **上游**，不在它创建的
 * graphicsLayer 内。那个 layer 里的 `translationX/Y`（芯片的跟手位移就在里面）
 * 作用不到折射上 —— 屏幕上是玻璃跟着手指走、而折射留在原地。
 *
 * 缩放那一半已经栽过一次：底栏按下时库的 highlight 环按放大后的轮廓画、
 * 折射还是原尺寸，看起来是一圈白环浮在玻璃外面。平移是同一个错误的另一半。
 */
data class GlassLensTransform(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f
) {
    internal companion object {
        val Identity = GlassLensTransform()
    }
}

/**
 * 绘制期求值的形变，用来与库那层 `layerBlock` 对齐。
 *
 * 拿到的是**实测**尺寸，理由同 [GlassLensOpticsProvider]：库那边的 `layerBlock`
 * 读的是 layer 自己的 `size`，这边要对齐就必须读同一个量，而不是调用点的常量。
 * 芯片的按压涨幅 `swell = 1 + press * swellPx / height` 就依赖它。
 */
fun interface GlassLensScale {
    fun compute(widthPx: Float, heightPx: Float): GlassLensTransform
}

/**
 * 绘制期求值的光学参数。
 *
 * **必须是 lambda，不能是现成的 [GlassLensOptics]。** 参数里的 `pressProgress` /
 * `velocity` 来自 `Animatable.value`，那是 snapshot state：在组合期算好再传进来，
 * 会让**整个导航栏**订阅这些值，按压期间每帧重组一次。而且这个代价与平台无关，
 * API 33+ 也一样要付 —— 那条路根本不用这个结果。
 *
 * 做成 lambda 之后，读取发生在 [Modifier.Node] 的 `draw()` 里，与库那层
 * `drawBackdrop(layerBlock = …)` 的时机一致：不订阅、不重组，只重绘。
 *
 * ## 为什么把实测尺寸传进来
 *
 * 尺寸相关的光学量（圆角半径、斜坡上限、位移上限）本该按元素**实测**尺寸算，
 * 而组合期只有**标称**尺寸。这个错配已经害过两次：导航指示器标称 163px、
 * 实测 152px，一次让斜坡窄了 6.9%（`位移/斜坡` 从 1.4 漂到 1.504），一次让
 * 圆角半径 81.65 > 76 使 SDF 退化，屏幕上是一层套在玻璃外的"壳"。
 * 单元测试两次都查不出来，因为它只看得到标称值那一侧。
 *
 * 传进来之后，调用方可以直接用实测值，也可以继续用自己的常量 —— 但至少
 * 「不知道自己多大」的控件（如共用材质的圆钮）不必再瞎猜一个尺寸。
 */
fun interface GlassLensOpticsProvider {
    /**
     * @param widthPx 元素实测宽度
     * @param heightPx 元素实测高度
     */
    fun compute(widthPx: Float, heightPx: Float): GlassLensOptics
}

/** Draw-local handshake: a non-null anchor is not proof that any background was painted. */
class GlassLensDrawState {
    var hasBackground: Boolean by mutableStateOf(false)
        internal set
}

/**
 * 在本元素上画折射。API 33+ 与 30- 上返回原 Modifier，由调用方走各自既有路径。
 *
 * 画在 `drawContent()` **之前**：折射结果是元素的背景，元素自身内容盖在上面。
 *
 * @param scale 与 `drawBackdrop(layerBlock = …)` **同一份**形变。必须传，理由见下。
 *
 * ## 为什么缩放要单独传进来
 * 库那层的按压放大写在 `drawBackdrop` 的 `layerBlock` 里，作用于它自己创建的
 * graphicsLayer；而 `glassLens` 挂在 `drawBackdrop` **上游**，不在那个 layer 内，
 * 于是按下时库的 highlight 环按放大后的轮廓画，折射却还是原尺寸 ——
 * 屏幕上就是一圈白环浮在玻璃外面。
 *
 * 在**绘制期**求值（而不是组合期读一个 Float），是为了保持和 `layerBlock` 相同的
 * 时机：那边刻意在 draw 里读动画值，避免每帧重组。
 */
fun Modifier.glassLens(
    anchor: GlassLensAnchor?,
    optics: GlassLensOpticsProvider,
    scale: GlassLensScale? = null,
    shape: Shape? = null,
    drawState: GlassLensDrawState? = null,
    drawPendingSource: Boolean = true
): Modifier {
    if (anchor == null || !isGlassLensApplicable()) return this
    return this then GlassLensElement(anchor, optics, scale, shape, drawState, drawPendingSource)
}

private data class GlassLensElement(
    val anchor: GlassLensAnchor,
    val optics: GlassLensOpticsProvider,
    val scale: GlassLensScale?,
    val shape: Shape?,
    val drawState: GlassLensDrawState?,
    val drawPendingSource: Boolean
) : ModifierNodeElement<GlassLensNode>() {

    override fun create(): GlassLensNode = GlassLensNode(anchor, optics, scale, shape, drawState, drawPendingSource)

    override fun update(node: GlassLensNode) {
        node.anchor = anchor
        node.optics = optics
        node.scale = scale
        node.shape = shape
        node.drawState = drawState
        node.drawPendingSource = drawPendingSource
    }
}

@android.annotation.SuppressLint("NewApi")
private class GlassLensNode(
    anchor: GlassLensAnchor,
    var optics: GlassLensOpticsProvider,
    var scale: GlassLensScale?,
    var shape: Shape?,
    var drawState: GlassLensDrawState?,
    var drawPendingSource: Boolean
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 渲染目标**每个元素一份**，不能挂在锚点上。
     *
     * 一个区域现在服务很多元素（App 级锚点覆盖所有按钮/芯片/选择器）。共用一个
     * FBO + 一张输出位图时，每个元素画的都是「最后一个渲染完的元素」的结果，
     * 再拉伸到自己的尺寸 —— 屏幕上是一条逐行相同的灰带。见 [GlassLensSource]。
     */
    private var target = GlassLensTarget(anchor.source, anchor.tag)
    private var targetReleased = false
    // Movable content detaches while crossing into/out of a same-window portal.
    // Releasing immediately discards the completed optical frame for one draw.
    private val releaseDetachedTarget = Runnable {
        if (!isAttached) {
            target.release()
            targetReleased = true
        }
    }

    var anchor: GlassLensAnchor = anchor
        set(value) {
            if (field === value) return
            field = value
            // 换了区域就换底图，旧 target 绑的是旧纹理，必须重建
            target.onFrameReady = null
            target.release()
            target = GlassLensTarget(value.source, value.tag)
            targetReleased = false
            if (isAttached) hookFrameReady()
        }

    override fun onAttach() {
        mainHandler.removeCallbacks(releaseDetachedTarget)
        if (targetReleased) {
            target = GlassLensTarget(anchor.source, anchor.tag)
            targetReleased = false
        }
        hookFrameReady()
    }

    private fun hookFrameReady() {
        // 新帧就绪要主动请求重绘，否则静止时渲好的帧永远画不出去。
        // 回调发生在 GL 线程，而 invalidateDraw 必须在主线程调用。
        target.onFrameReady = {
            mainHandler.post { if (isAttached) invalidateDraw() }
        }
    }

    override fun onDetach() {
        target.onFrameReady = null
        mainHandler.removeCallbacksAndMessages(null)
        // Reparenting finishes in this UI traversal. Actual disposal still
        // releases the GPU target at the next main-loop turn.
        mainHandler.post(releaseDetachedTarget)
    }

    private var coordinates: LayoutCoordinates? = null
    private val paint = Paint().apply { isFilterBitmap = true }
    private val fallbackMatrix = android.graphics.Matrix()
    private val frameMatrix = android.graphics.Matrix()
    private val previousFrameClip = Path()
    private val previousFrameBounds = android.graphics.RectF()
    private val matrixValues = FloatArray(9)
    private val cornerValues = FloatArray(8)

    // 兜底路径的绘制用具：饱和度滤镜复现着色器的 applyVibrancy
    // （ColorMatrix.setSaturation 与 mix(luminance, rgb, sat) 同式），
    // 圆角轮廓用 Path 裁剪。
    private var fallbackPaint: Paint? = null
    private var fallbackSaturation = Float.NaN
    private val fallbackClip = Path()

    /** 元素完整轮廓的 dst 框（圆角按它构造，与被裁到的可见 dstRect 区分开）。 */
    private val fallbackFullDst = android.graphics.RectF()

    // 逐实例可变对象，避免多个指示器共享时互相踩
    private val srcRect = android.graphics.Rect()
    private val dstRect = android.graphics.RectF()

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        invalidateDraw()
    }

    private var paintedBackground = false

    override fun ContentDrawScope.draw() {
        paintedBackground = false
        drawLens()
        drawState?.hasBackground = paintedBackground
        drawContent()
    }

    private fun DrawScope.drawLens() {
        val w = size.width.toInt()
        val h = size.height.toInt()
        if (w <= 1 || h <= 1) return


        // 在这里求值（而不是组合期）：参数里的按压进度/速度是 snapshot state，
        // 组合期读会让整个导航栏每帧重组。见 GlassLensOpticsProvider。
        // 实测尺寸一并交给调用方，省得它拿标称尺寸去推圆角/上限。
        val optics = optics.compute(size.width, size.height)

        // 锚点没被 Modifier.glassLensAnchor 挂到任何节点上 —— 这是调用方的编码错误，
        // 而它的后果很隐蔽：折射一个像素都不画，同时调用方的 onDrawBackdrop 已经把
        // 背景绘制让给了折射，于是整块元素**没有背景**（弹窗上实拍到过一次：卡片
        // 完全消失，只剩文字和按钮浮在页面上）。所以必须吵 —— 但要去抖（见
        // noteDrawUnanchored：SideEffect 绕一帧挂锚点的控件首帧必然在这里误报）。
        if (anchor.coordinates == null) {
            anchor.noteDrawUnanchored()
            return
        }
        val anchorCoords = anchor.coordinates?.takeIf { it.isAttached } ?: return
        // At zero refraction the GPU can replay the live source directly. Sending
        // animated glyphs through readback here freezes them when the slider stops.
        val useLiveSource = optics.lensAmountPx <= 0f
        if (useLiveSource) anchor.liveSourceRevision()
        if (!useLiveSource) anchor.ensureSource() ?: return
        // Keep animating against the uploaded source while a newer capture is pending.
        // Publishing its coordinates before its pixels arrive stalls the optical motion.
        val samplingFrame = if (useLiveSource) null else anchor.sourceFrame ?: anchor.captureFrame
        val renderer = target

        // 先画上一帧的结果，再提交这一帧：在 draw() 里等 GPU 会钉住 UI 线程。
        // 与库那层 layerBlock 同一份形变：缩放与平移都要跟上，
        // 否则库画的 surface/highlight 与折射会错开（见 GlassLensTransform）。
        val t = scale?.compute(size.width, size.height) ?: GlassLensTransform.Identity
        val localCoordinates = coordinates?.takeIf { it.isAttached } ?: return
        // Read live coordinates: a cached window offset misses graphicsLayer movement.
        // Include the inner glass deformation exactly once, just like the AGSL backdrop.
        val sampleOrigin = Offset((w - w * t.scaleX) / 2f + t.translationX,
            (h - h * t.scaleY) / 2f + t.translationY)
        fun sourcePosition(point: Offset): Offset {
            // Captures use anchor-local pixels. An ancestor's entrance/collapse
            // transform already moves both the anchor and the lens; applying the
            // old window transform again compresses several labels into one lens.
            if (samplingFrame == null || samplingFrame.geometry.size == anchorCoords.size) {
                return anchorCoords.localPositionOf(localCoordinates, point)
            }
            // A resized sampling region (the action fan opening) still needs the
            // previous crop's origin until the matching larger texture is uploaded.
            return samplingFrame.geometry.windowToLocal(localCoordinates.localToWindow(point))
        }
        val origin = sourcePosition(sampleOrigin)
        val alongX = sourcePosition(sampleOrigin + Offset(w * t.scaleX, 0f)) - origin
        val alongY = sourcePosition(sampleOrigin + Offset(0f, h * t.scaleY)) - origin
        if (!origin.x.isFinite() || !origin.y.isFinite() || !alongX.x.isFinite() ||
            !alongX.y.isFinite() || !alongY.x.isFinite() || !alongY.y.isFinite()) return
        val left = origin.x
        val top = origin.y
        val axes = GlassLensSourceAxes(alongX.x / w, alongX.y / w, alongY.x / h, alongY.y / h)
        GlassLensCaptureObserver.onSampled?.invoke(anchor.tag, IntSize(w, h), origin, axes,
            samplingFrame?.generation ?: 0)

        val halfMin = minOf(w, h) / 2f

        // Resolve the measured outline. Only uniform capsule radii are limited to
        // half the short edge; asymmetric radii must retain their own geometry.
        val corners = shape?.let { lensCornerRadiiPx(it, size.width, size.height, this, layoutDirection) }
            ?: GlassLensCorners.uniform(optics.cornerRadiusPx.coerceIn(0f, halfMin))
        val radius = corners.minimum
        updateClip(w, h, corners, t)

        if (useLiveSource) {
            if (drawPendingSource) drawFallback(left, top, w, h, axes, t, optics.vibrancy, useLiveSource = true)
            return
        }

        // Keep a completed optical frame until its replacement is ready. Switching
        // to a plain source on every background revision produces visible flashes.
        // The renderer separately rejects mismatched source/coordinate generations.
        val rendered = if (renderer.failed) null else renderer.latestFrame?.takeIf {
            !anchor.requireCurrentSource || it.sourceGeneration == samplingFrame?.generation ||
                canRetainLensFrame(it.params.sourceIdentity, samplingFrame?.identity, anchor.retainCompatibleBackground)
        }
        val frame = rendered?.bitmap
        if (frame != null && !frame.isRecycled) {
            // 尺寸不一致时**拉伸**而不是丢弃：上一帧的尺寸在布局收敛或动画中经常
            // 与当前差几像素，严格相等的判断会把「差一帧」放大成「永远不画」。
            // 几像素的缩放看不出来，不画则是致命的。
            // 不再翻转：glReadPixels 的自下而上与 copyPixelsFromBuffer 的
            // 自上而下已互相抵消（见 GlassLensRenderer）。多翻一次会上下镜像。
            srcRect.set(0, 0, frame.width, frame.height)
            // 拉伸而非重渲染：库也是先按原尺寸跑完着色器、再由 graphicsLayer
            // 整层缩放，两者等价。这样 GL 侧的 FBO 尺寸也不必随动画每帧重建。
            val dw = w * t.scaleX
            val dh = h * t.scaleY
            val dx = (w - dw) / 2f + t.translationX
            val dy = (h - dh) / 2f + t.translationY
            dstRect.set(dx, dy, dx + dw, dy + dh)
            val canvas = drawContext.canvas.nativeCanvas
            val saved = canvas.save()
            canvas.clipPath(fallbackClip)
            if (rendered.sourceSize == samplingFrame?.geometry?.size &&
                updateFrameTransform(rendered, left, top, w, h, axes, t)) {
                // Keep labels fixed in source space while the next optical frame
                // renders. Moving an old bitmap with the slider drags its text too.
                // Only newly exposed pixels need the current, unrefracted source.
                val uncovered = canvas.save()
                canvas.clipOutPath(previousFrameClip)
                if (drawPendingSource) drawFallback(left, top, w, h, axes, t, optics.vibrancy)
                canvas.restoreToCount(uncovered)
                canvas.drawBitmap(frame, frameMatrix, paint)
            } else {
                canvas.drawBitmap(frame, srcRect, dstRect, paint)
            }
            canvas.restoreToCount(saved)
            paintedBackground = true
            if (rendered.sourceGeneration == samplingFrame?.generation &&
                rendered.sourceGeneration != anchor.timedSourceGeneration) {
                anchor.timedSourceGeneration = rendered.sourceGeneration
                GlassLensCaptureObserver.onTiming?.invoke(anchor.tag, "capture-to-draw",
                    System.nanoTime() - samplingFrame.queuedAtNanos)
            }
        } else {
            if (drawPendingSource) drawFallback(left, top, w, h, axes, t, optics.vibrancy)
        }

        // 元素级失败（如这台设备的 FBO 建不起来）：上面的 stale latest / 兜底已
        // 保证有东西可看，这里只是不再提交新帧 —— 冻结在最后一帧好过永远空白。
        // 曾经这行是放在画 latest **之前**的提前 return，而 target 的 ownFailed
        // 不是 snapshot state、组合期读不到：失败后既不切回 blur 路径，也一个
        // 像素都不画，直到元素重组（持久元素如底栏则直到重启）。
        if (renderer.failed || anchor.sourceFrame == null || samplingFrame == null) return

        // 斜坡上限同样按实测短边施加：斜坡宽到吃掉整个形状时，折射会退化成一个
        // 空心发光环而不是玻璃（实测占半短边 0.6 就是这样）。
        //
        // 夹住时**位移同比例缩小**。只夹斜坡会让 `位移/斜坡` 比值变大，而观感由这个
        // 比值决定 —— 比值越大，同一条斜坡里塞进越多位移，边缘挤压越陡。同比例缩
        // 之后，被夹的元素只是折射整体变弱，形状不变。
        val maxThickness = halfMin * GLASS_LENS_THICKNESS_FRACTION_MAX
        val thickness = optics.thicknessPx.coerceIn(1f, maxThickness.coerceAtLeast(1f))
        val clampScale = if (optics.thicknessPx > 0f) thickness / optics.thicknessPx else 1f

        renderer.submit(
            GlassLensParams(
                widthPx = w,
                heightPx = h,
                srcLeftPx = left,
                srcTopPx = top,
                cornerRadiusPx = radius,
                thicknessPx = thickness,
                lensAmountPx = optics.lensAmountPx * clampScale,
                dispersion = optics.dispersion,
                depthEffect = optics.depthEffect,
                vibrancy = optics.vibrancy,
                corners = corners,
                sourceAxes = axes,
                maxRenderPixels = optics.maxRenderPixels,
                sourceGeneration = samplingFrame.generation,
                sourceIdentity = samplingFrame.identity
            )
        )
    }

    /** Maps the completed frame's source coordinates into the lens's current shape. */
    private fun updateFrameTransform(
        frame: GlassLensRenderedFrame,
        left: Float,
        top: Float,
        w: Int,
        h: Int,
        axes: GlassLensSourceAxes,
        t: GlassLensTransform
    ): Boolean {
        val previous = frame.params
        if (previous.srcLeftPx == left && previous.srcTopPx == top && previous.sourceAxes == axes &&
            previous.widthPx == w && previous.heightPx == h) return false
        val determinant = axes.xx * axes.yy - axes.xy * axes.yx
        if (!determinant.isFinite() || kotlin.math.abs(determinant) < 0.0001f) return false
        fun map(x: Float, y: Float) = Offset(
            (axes.yy * x - axes.yx * y) / determinant,
            (axes.xx * y - axes.xy * x) / determinant
        )
        val origin = map(previous.srcLeftPx - left, previous.srcTopPx - top)
        val xAxis = map(previous.sourceAxes.xx, previous.sourceAxes.xy)
        val yAxis = map(previous.sourceAxes.yx, previous.sourceAxes.yy)
        matrixValues[0] = t.scaleX * xAxis.x
        matrixValues[1] = t.scaleX * yAxis.x
        matrixValues[2] = (w - w * t.scaleX) / 2f + t.translationX + t.scaleX * origin.x
        matrixValues[3] = t.scaleY * xAxis.y
        matrixValues[4] = t.scaleY * yAxis.y
        matrixValues[5] = (h - h * t.scaleY) / 2f + t.translationY + t.scaleY * origin.y
        matrixValues[6] = 0f
        matrixValues[7] = 0f
        matrixValues[8] = 1f
        frameMatrix.setValues(matrixValues)
        previousFrameBounds.set(0f, 0f, previous.widthPx.toFloat(), previous.heightPx.toFloat())
        val corners = previous.corners
        cornerValues[0] = corners.topLeft
        cornerValues[1] = corners.topLeft
        cornerValues[2] = corners.topRight
        cornerValues[3] = corners.topRight
        cornerValues[4] = corners.bottomRight
        cornerValues[5] = corners.bottomRight
        cornerValues[6] = corners.bottomLeft
        cornerValues[7] = corners.bottomLeft
        previousFrameClip.reset()
        previousFrameClip.addRoundRect(previousFrameBounds, cornerValues, Path.Direction.CW)
        previousFrameClip.transform(frameMatrix)
        frameMatrix.preScale(previous.widthPx.toFloat() / frame.bitmap.width,
            previous.heightPx.toFloat() / frame.bitmap.height)
        return true
    }

    /**
     * 首帧间隙 / 元素级失败后的兜底：直接画底图上自己那块区域。
     *
     * 与着色器的差异只有两处，都是刻意的近似：
     * - 没有折射位移与色散（那正是还没渲出来的东西）；
     * - 饱和度用 `ColorMatrix.setSaturation(vibrancy)` 复现 applyVibrancy。
     *
     * The same four corners and inverse source transform keep the fallback aligned
     * with the final shader frame during translation, rotation and scaling.
     */
    private fun DrawScope.drawFallback(
        left: Float,
        top: Float,
        w: Int,
        h: Int,
        axes: GlassLensSourceAxes,
        t: GlassLensTransform,
        vibrancy: Float,
        useLiveSource: Boolean = false
    ) {
        val recorded = if (useLiveSource) null else anchor.sourceFrame ?: anchor.captureFrame
        val sourceCoordinates = anchor.coordinates?.takeIf { it.isAttached } ?: return
        val determinant = axes.xx * axes.yy - axes.xy * axes.yx
        if (!determinant.isFinite() || kotlin.math.abs(determinant) < 0.0001f) return

        val p = fallbackPaint ?: Paint(Paint.FILTER_BITMAP_FLAG).also { fallbackPaint = it }
        if (fallbackSaturation != vibrancy) {
            fallbackSaturation = vibrancy
            p.colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply { setSaturation(vibrancy) }
            )
        }

        val dw = w * t.scaleX
        val dh = h * t.scaleY
        val dx = (w - dw) / 2f + t.translationX
        val dy = (h - dh) / 2f + t.translationY
        matrixValues[0] = t.scaleX * axes.yy / determinant
        matrixValues[1] = -t.scaleX * axes.yx / determinant
        matrixValues[2] = dx + t.scaleX * (-axes.yy * left + axes.yx * top) / determinant
        matrixValues[3] = -t.scaleY * axes.xy / determinant
        matrixValues[4] = t.scaleY * axes.xx / determinant
        matrixValues[5] = dy + t.scaleY * (axes.xy * left - axes.xx * top) / determinant
        matrixValues[6] = 0f
        matrixValues[7] = 0f
        matrixValues[8] = 1f
        fallbackMatrix.setValues(matrixValues)

        val canvas = drawContext.canvas.nativeCanvas
        val save = canvas.saveLayer(fallbackFullDst, p)
        // 圆角按元素**完整**轮廓构造，半径只随形变缩放 —— 圆角是形状的属性，不是
        // 「可见了多少」的属性。曾经这里把半径乘了可见比例 (u1-u0)，于是被底图
        // 边界裁掉一半的元素会得到半圆角。与可见区域求交不必再 clip 一次：
        // 下面的 drawBitmap 本就只覆盖 dstRect。
        canvas.clipPath(fallbackClip)
        canvas.concat(fallbackMatrix)
        if (recorded != null) {
            canvas.drawRenderNode(recorded.node)
        } else {
            // Replay live layers before the first readback, and whenever no optical
            // displacement is needed. This also preserves animated foregrounds.
            CanvasDrawScope().draw(anchor.density, layoutDirection, drawContext.canvas,
                androidx.compose.ui.geometry.Size(anchor.sizePx.width.toFloat(), anchor.sizePx.height.toFloat())) {
                anchor.drawSource(this, sourceCoordinates)
                anchor.drawOverlaySource?.invoke(this, sourceCoordinates)
            }
        }
        canvas.restoreToCount(save)
        paintedBackground = true
    }

    private fun updateClip(w: Int, h: Int, corners: GlassLensCorners, t: GlassLensTransform) {
        val dw = w * t.scaleX
        val dh = h * t.scaleY
        val dx = (w - dw) / 2f + t.translationX
        val dy = (h - dh) / 2f + t.translationY
        fallbackFullDst.set(dx, dy, dx + dw, dy + dh)
        cornerValues[0] = corners.topLeft * t.scaleX
        cornerValues[1] = corners.topLeft * t.scaleY
        cornerValues[2] = corners.topRight * t.scaleX
        cornerValues[3] = corners.topRight * t.scaleY
        cornerValues[4] = corners.bottomRight * t.scaleX
        cornerValues[5] = corners.bottomRight * t.scaleY
        cornerValues[6] = corners.bottomLeft * t.scaleX
        cornerValues[7] = corners.bottomLeft * t.scaleY
        fallbackClip.reset()
        fallbackClip.addRoundRect(fallbackFullDst, cornerValues, Path.Direction.CW)
    }
}

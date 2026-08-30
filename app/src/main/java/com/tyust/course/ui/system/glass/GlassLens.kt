package com.tyust.course.ui.system.glass

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Picture
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CanvasHolder
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.backdrop.Backdrop

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
 *      │  Backdrop -> Picture -> 硬件位图 -> ARGB_8888（仅在内容变化时）
 *      ▼
 * [GlassLensRenderer]  自带 GL 线程；底图上传一次，逐帧只改 uniform
 *      │  渲染 + glReadPixels -> Bitmap（实测 0.27ms 中位 @ Adreno 640）
 *      ▼
 * Modifier.glassLens  在 draw() 里画上一帧的结果并提交下一帧
 * ```
 *
 * 用「上一帧结果」是刻意的：在 `draw()` 里同步等 GPU 会把 UI 线程钉住，
 * 而折射差一帧看不出来。
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
    internal val tag: String = "anon"
) {

    internal var coordinates: LayoutCoordinates? by mutableStateOf(null)
        private set

    internal var sizePx: IntSize by mutableStateOf(IntSize.Zero)
        private set

    /** 调用方在内容变化（选中项、壁纸、主题）时 bump。 */
    internal var version by mutableIntStateOf(0)
        private set

    private var uploadedKey = -1L
    private var uploadedVersion = -1
    private var captureFailed = false

    internal fun onPositioned(coords: LayoutCoordinates) {
        coordinates = coords
        val s = IntSize(coords.size.width, coords.size.height)
        if (s != sizePx) sizePx = s
    }

    /** 内容变了，下一帧重新快照上传。 */
    fun invalidate() {
        version++
    }

    private var warnedUnanchored = false

    /** 只吼一次，别把 logcat 刷满。 */
    internal fun warnUnanchored() {
        if (warnedUnanchored) return
        warnedUnanchored = true
        android.util.Log.e(
            TAG,
            "lens region '$tag' was never attached with Modifier.glassLensAnchor — " +
                "elements using it will have no background at all"
        )
    }

    /** 确保底图已上传。返回底图尺寸，未就绪返回 null。 */
    internal fun ensureSource(): IntSize? {
        if (captureFailed || source.failed) return null
        val size = sizePx
        if (size.width <= 0 || size.height <= 0) return null
        val key = (size.width.toLong() shl 32) or size.height.toLong()
        if (uploadedKey == key && uploadedVersion == version) {
            return size
        }

        val coords = coordinates ?: return null
        val bitmap =
            try {
                capture(coords, size)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "backdrop capture failed, lens disabled", t)
                null
            }
        if (bitmap == null) {
            if (!captureFailed) android.util.Log.w(TAG, "backdrop capture returned null")
            captureFailed = true
            return null
        }
        source.uploadSource(bitmap, version)
        uploadedKey = key
        uploadedVersion = version
        return size
    }

    /**
     * 把 backdrop 快照成可读位图。
     *
     * `Bitmap.createBitmap(Picture)` 会把 Picture 录进 RenderNode 的 RecordingCanvas
     * （硬件加速），所以 backdrop 内部的 `drawLayer` → `drawRenderNode` 合法 ——
     * 这条路和 Compose 自己的 `GraphicsLayer.toImageBitmap()` 在 API 28+ 上一致。
     * 但产物是 HARDWARE config，`GLUtils.texImage2D` 读不了，所以要 copy 成
     * ARGB_8888。这一次回读只在内容变化时发生，不是每帧。
     */
    private fun capture(coords: LayoutCoordinates, size: IntSize): Bitmap? {
        val picture = BackdropPicture(drawSource, coords, density, size)
        val hw = Bitmap.createBitmap(picture) ?: return null
        return if (hw.config == Bitmap.Config.HARDWARE) {
            val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
            hw.recycle()
            soft
        } else {
            hw
        }
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
 * CLAMP 而非 DECAL：DECAL 把边界外当透明拉进来，四边会变暗；CLAMP 复制边缘
 * 像素，输出处处不透明 —— 着色器输出的 alpha 恒为 1，底图一旦有透明像素就会
 * 变成不该有的黑。
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
 * 覆写 `draw()` 的 Picture 子类，与 Compose 内部 `LayerSnapshotV28.GraphicsLayerPicture`
 * 同构：`beginRecording` 返回一个弃用的空 Canvas、`endRecording` 空实现，
 * 让框架的 `Canvas.drawPicture()` 直接回调到这里。
 */
private class BackdropPicture(
    private val drawSource: DrawScope.(LayoutCoordinates) -> Unit,
    private val coordinates: LayoutCoordinates,
    private val density: Density,
    private val size: IntSize
) : Picture() {

    private val canvasHolder = CanvasHolder()
    private val drawScope = CanvasDrawScope()

    override fun beginRecording(width: Int, height: Int): android.graphics.Canvas =
        android.graphics.Canvas()

    override fun endRecording() = Unit

    override fun getWidth(): Int = size.width

    override fun getHeight(): Int = size.height

    /** 内含 RenderNode 回放，必须硬件加速。 */
    override fun requiresHardwareAcceleration(): Boolean = true

    override fun draw(canvas: android.graphics.Canvas) {
        val d = density
        canvasHolder.drawInto(canvas) {
            drawScope.draw(
                density = d,
                layoutDirection = LayoutDirection.Ltr,
                canvas = this,
                size = androidx.compose.ui.geometry.Size(
                    size.width.toFloat(),
                    size.height.toFloat()
                )
            ) {
                // 把锚点自身的 coordinates 交给调用方：LayerBackdrop 需要它
                // 才能把自己平移到锚点左上角
                drawSource(coordinates)
            }
        }
    }
}

/**
 * 建一个锚点，并在离开组合时释放 GL 资源。
 *
 * @param backdrop 与目标元素实际取样的背景一致（组合背景直接传进来）
 */
@Composable
fun rememberGlassLensAnchor(
    /** 站点名，只在锚点没挂上时的报错里出现。 */
    tag: String = "anon",
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
    DisposableEffect(anchor) {
        onDispose { anchor.source.release() }
    }
    // 底图彻底失败（GL 挂了、快照拿不到）时把锚点收回去，让站点整体退回 blur 路径。
    // 不收的话站点会保持折射路径，而折射路径把背景绘制让给了折射本身 ——
    // 结果是元素没有任何背景。`failed` 是 snapshot state，所以这里读得到变化。
    if (anchor.source.failed) return null
    return anchor
}

/** 标记锚点区域，记录它的窗口位置与尺寸。 */
fun Modifier.glassLensAnchor(anchor: GlassLensAnchor?): Modifier =
    if (anchor == null) this else onGloballyPositioned { anchor.onPositioned(it) }

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
    val vibrancy: Float = 1.28f
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
    scale: GlassLensScale? = null
): Modifier {
    if (anchor == null || !isGlassLensApplicable()) return this
    return this then GlassLensElement(anchor, optics, scale)
}

private data class GlassLensElement(
    val anchor: GlassLensAnchor,
    val optics: GlassLensOpticsProvider,
    val scale: GlassLensScale?
) : ModifierNodeElement<GlassLensNode>() {

    override fun create(): GlassLensNode = GlassLensNode(anchor, optics, scale)

    override fun update(node: GlassLensNode) {
        node.anchor = anchor
        node.optics = optics
        node.scale = scale
    }
}

private class GlassLensNode(
    anchor: GlassLensAnchor,
    var optics: GlassLensOpticsProvider,
    var scale: GlassLensScale?
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 渲染目标**每个元素一份**，不能挂在锚点上。
     *
     * 一个区域现在服务很多元素（App 级锚点覆盖所有按钮/芯片/选择器）。共用一个
     * FBO + 一张输出位图时，每个元素画的都是「最后一个渲染完的元素」的结果，
     * 再拉伸到自己的尺寸 —— 屏幕上是一条逐行相同的灰带。见 [GlassLensSource]。
     */
    private var target = GlassLensTarget(anchor.source)

    var anchor: GlassLensAnchor = anchor
        set(value) {
            if (field === value) return
            field = value
            // 换了区域就换底图，旧 target 绑的是旧纹理，必须重建
            target.onFrameReady = null
            target.release()
            target = GlassLensTarget(value.source)
            if (isAttached) hookFrameReady()
        }

    override fun onAttach() = hookFrameReady()

    private fun hookFrameReady() {
        // 新帧就绪要主动请求重绘，否则静止时渲好的帧永远画不出去。
        // 回调发生在 GL 线程，而 invalidateDraw 必须在主线程调用。
        target.onFrameReady = {
            mainHandler.post { if (isAttached) invalidateDraw() }
        }
    }

    override fun onDetach() {
        target.onFrameReady = null
        target.release()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private var windowOffset = Offset.Zero
    private val paint = Paint().apply { isFilterBitmap = true }


    // 逐实例可变对象，避免多个指示器共享时互相踩
    private val srcRect = android.graphics.Rect()
    private val dstRect = android.graphics.RectF()

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        windowOffset = coordinates.positionInWindow()
    }

    override fun ContentDrawScope.draw() {
        drawLens()
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
        // 完全消失，只剩文字和按钮浮在页面上）。所以这里必须吵。
        if (anchor.coordinates == null) {
            anchor.warnUnanchored()
            return
        }
        anchor.ensureSource() ?: return
        val anchorCoords = anchor.coordinates ?: return
        val renderer = target
        if (renderer.failed) return

        val origin = anchorCoords.positionInWindow()
        val left = windowOffset.x - origin.x
        val top = windowOffset.y - origin.y

        // 先画上一帧的结果，再提交这一帧：在 draw() 里等 GPU 会钉住 UI 线程
        // 尺寸不一致时**拉伸**而不是丢弃：上一帧的尺寸在布局收敛或动画中经常
        // 与当前差几像素，严格相等的判断会把「差一帧」放大成「永远不画」。
        // 几像素的缩放看不出来，不画则是致命的。
        // 与库那层 layerBlock 同一份形变：缩放与平移都要跟上，
        // 否则库画的 surface/highlight 与折射会错开（见 GlassLensTransform）。
        val t = scale?.compute(size.width, size.height) ?: GlassLensTransform.Identity

        renderer.latest?.let { frame ->
            if (!frame.isRecycled) {
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
                drawContext.canvas.nativeCanvas.drawBitmap(frame, srcRect, dstRect, paint)
            }
        }

        val halfMin = minOf(w, h) / 2f

        // 圆角半径必须夹到**实测**短边的一半。胶囊的半径就**是** min(w,h)/2，
        // 更大的值会让 sdRoundedRect 里的 `halfSize - r` 出现负分量，SDF 退化：
        // 形状既不是胶囊（两端被压平，看着像圆角矩形），也不再与 Compose 侧的
        // `shape = { Capsule() }` 重合。不重合的那一圈里着色器输出透明，而库的
        // surface/highlight 照胶囊画 —— 屏幕上就是一层套在玻璃外面的"壳"。
        //
        // 调用方传的是**标称**尺寸（如 indicatorHeight.toPx()），与元素实测尺寸
        // 可以差百分之几：实测踩到过标称 163px、实测 152px，半径 81.65 > 76。
        // 这类"标称 vs 实测"的错配已经害过两次（另一次是斜坡宽度），
        // 所以这里的原则是：**光学参数一律按实测尺寸夹一遍**。
        val radius = optics.cornerRadiusPx.coerceIn(0f, halfMin)

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
                vibrancy = optics.vibrancy
            )
        )
    }
}

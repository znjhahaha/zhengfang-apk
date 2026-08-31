package com.tyust.course.ui.system.glass

import androidx.compose.ui.unit.Density
import androidx.compose.ui.util.fastCoerceIn
import com.tyust.course.ui.system.GlassMaterialSpec
import com.tyust.course.ui.system.GlassOpticsSpec

/*
 * 「圆钮压在自己轨道上」这一类控件在 API31/32 上的真折射参数。
 *
 * 三个控件共用（都是库 catalog 里 LiquidSlider 那一套配方逐参数搬过来的）：
 * - [com.tyust.course.ui.system.LiquidSwitch] 的旋钮（40×24 胶囊，纯色轨道）
 * - [com.tyust.course.ui.system.LiquidSlider] 的 thumb（40×24 胶囊，渐变轨道）
 * - [LiquidColorField] 的摘钮（28dp 圆，HSV 色板）
 *
 * ## 为什么这几个控件不能用 App 级的 [LocalGlassLensAnchor]
 *
 * 那张底图只有壁纸。而这几个圆钮 33+ 上采的是
 * `combined(环境背景, 轨道层)` —— 折射里该浮出来的**正是脚下轨道的颜色**
 * （开关的绿、蒙版条的黑白、色板的色相）。少了轨道那一层，按下去只看到壁纸，
 * 整个效果的意义就没了。所以每个控件自己建一块区域，底图 = 环境 + 自己的轨道，
 * 锚点挂在**不随圆钮移动**的那层容器上。
 *
 * ## 底图里的轨道**不缩放**，这是一处刻意的偏差
 *
 * 33+ 那边轨道层包在 `rememberBackdrop { scale(sx, sy) { … } }` 里，`sx/sy`
 * 每帧跟着 pressProgress 变（静止 scaleY = 0，轨道被纵向压成 0，圆钮因此是
 * 不被轨道色污染的纯白实体）。
 *
 * 那个形变没法烤进底图：它的原点是**圆钮自己**的中心，而底图是**锚点**坐标系里
 * 的一张位图，圆钮还在里面来回移动 —— 要跟就得每帧重拍，一次约 5ms
 * （见 [GlassLensFreshness]），必然掉帧。
 *
 * 不跟的代价在两个端点上都是对的：
 * - **静止**：`lens` 两个参数都乘 pressProgress = 0，折射为 0，着色器把底图原样
 *   画出；而圆钮表面此时是 alpha = 1 的实色，底图**根本看不见**。
 * - **按满**：LiquidSlider 的 scale 到 (1, 1)，本来就是恒等，逐像素一致。
 *   开关与色板到 (0.75, 0.75)，这里采的是未缩小的轨道 —— 差别是折射里的轨道
 *   略大一点，方向上更接近"透过玻璃看到脚下真实的轨道"。
 *
 * 只有按压途中那一两百毫秒有可见差异，而那期间折射强度与表面透明度都在同步
 * 变化，差异被自己盖住。
 */

/**
 * 圆钮的折射材质。
 *
 * 数字照库 catalog 的 LiquidSlider 抄：开关与色板是
 * `lens(5dp * press, 10dp * press)`，滑块 thumb 是 `lens(10dp * press, 14dp * press)`。
 * 静止**全为 0**，与库一致 —— 这一条栽过一次，见 `CapsuleNavigationBar` 里
 * `NavIndicatorRefractionFloor` 的注释。
 */
fun thumbLensMaterial(
    refractionHeightDp: Float,
    refractionAmountDp: Float
): GlassMaterialSpec = GlassMaterialSpec(
    // 33+ 那条路的 blur 是 `8dp * (1 - press)`，作用是静止时把背景糊掉。但静止时
    // 表面是 alpha = 1 的实色，糊了也看不见；按满时它本来就是 0。两个端点都不
    // 需要，所以这里不带 blur —— 留着反而会把自家折射糊掉（LiquidButton 那边
    // 同样的理由已经撤掉过一次）。
    blurDp = 0f,
    refractionHeightDp = refractionHeightDp,
    refractionAmountDp = refractionAmountDp,
    // 表面/描边/投影都由 Compose 侧的 onDrawSurface + Highlight + Shadow 画，
    // 与 33+ 共用那段代码，这里给 0 只是占位。
    surfaceAlpha = 0f,
    borderAlpha = 0f,
    shadowAlpha = 0f,
    optics = GlassOpticsSpec(chromaticAberration = true)
)

/**
 * 圆钮的折射参数。[press] 是 pressProgress。
 *
 * `motionIntensity` 固定为 0：库那边这几个控件的 `lens()` 只乘 pressProgress，
 * 没有速度项。带上速度会让 31/32 在快拖时折射比 33+ 强，那就不是复现而是第二种
 * 效果了。速度形变仍然有 —— 它在 [thumbLensTransform] 里，与库同源。
 */
fun thumbLensOptics(
    material: GlassMaterialSpec,
    density: Density,
    cornerRadiusPx: Float,
    minDimensionPx: Float,
    press: Float
): GlassLensOptics = glassLensOpticsFrom(
    material = material,
    density = density,
    cornerRadiusPx = cornerRadiusPx,
    minDimensionPx = minDimensionPx,
    interactionProgress = press,
    motionIntensity = 0f,
    pressScalesRefraction = true,
    // 静止不折射，与库一致
    refractionFloor = 0f,
    chromaticAberrationAtRest = false,
    // 这三个控件的 effects 里只有 blur 与 lens，**没有** vibrancy()，所以是 1。
    // 实测：给 1.28 时 API32 上开关按住折射出 16d245，而 API37 同处是 34c759
    // （轨道原色）—— 纯色轨道折射出来本该还是同一个绿。
    vibrancy = 1f
)

/**
 * 圆钮的按压 + 速度形变。**唯一算式**，两个消费者：库那层
 * `drawBackdrop(layerBlock)` 与 API31/32 的 `glassLens(scale)`。
 *
 * 不一致的后果实测过：按下时库画的 highlight 环按放大后的轮廓走、折射还是原
 * 尺寸，屏幕上是一圈白环浮在玻璃外面。
 *
 * @param velocityDivisor 库在各控件里除的数不同（开关/色板 50、滑块 10），
 *                        所以传进来而不是写死。
 */
fun thumbLensTransform(
    anim: DampedDragAnimation,
    velocityDivisor: Float,
    reduceMotion: Boolean
): GlassLensTransform {
    if (reduceMotion) return GlassLensTransform()
    var sx = anim.scaleX
    var sy = anim.scaleY
    val velocity = anim.velocity / velocityDivisor
    sx /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
    sy *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
    // 平移为 0：圆钮的 translationX 挂在**外层** graphicsLayer 上，glassLens 在它
    // 内部，已经跟着一起走了。见 GlassLensTransform。
    return GlassLensTransform(scaleX = sx, scaleY = sy)
}

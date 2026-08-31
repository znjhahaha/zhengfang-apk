package com.tyust.course.ui.system.glass

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import com.tyust.course.ui.system.GlassMaterialSpec
import com.tyust.course.ui.system.canUseLiquidLens
import com.tyust.course.ui.system.isRuntimeLensEnabled
import kotlin.math.abs

/**
 * 物理透镜参数解析结果。
 * 调用侧在 drawBackdrop.effects 中按 vibrancy → blur → lens 顺序应用。
 * 色散只允许来自 backdrop 的 RGB 分通道折射。
 */
data class PhysicalLensParams(
    val blurPx: Float,
    val refractionHeightPx: Float,
    val refractionAmountPx: Float,
    val useLens: Boolean,
    val chromaticAberration: Boolean,
    /** API 31/32 色散近似的 RGB 分离偏移（px）；0 表示关闭。交互时才 > 0。 */
    val fringePx: Float = 0f
)

/**
 * 解析物理透镜参数。
 *
 * ## 为什么第一个参数是 `BackdropEffectScope` 而不是 `Density`
 *
 * `useLens` 的判定必须包含形状：库的 `lens()` 对不支持的形状会**直接抛异常闪退**
 * （见 [canUseLiquidLens]）。而形状必须与 `lens()` 自己读的那一个**是同一个对象**——
 * 曾经的做法是让调用点额外传一个 `shape` 实参，那是一份可以和
 * `drawBackdrop(shape = { … })` 悄悄不一致的副本。改成从 scope 上读之后，
 * 判据用的就是库将要用的那个形状，两者不可能再脱钩。
 *
 * `BackdropEffectScope : Density`，所以 `with(scope)` 里的 dp 换算照旧。
 */
fun resolvePhysicalLens(
    scope: BackdropEffectScope,
    material: GlassMaterialSpec,
    minCornerRadiusPx: Float,
    minDimensionPx: Float,
    interactionProgress: Float = 0f,
    motionIntensity: Float = 0f,
    enableBlur: Boolean = true,
    allowChromaticAberration: Boolean = true,
    /** 仅选中透镜使用：静止态也保持弱色散，交互时由运动强度继续增强。 */
    chromaticAberrationAtRest: Boolean = false,
    /**
     * true：折射随按压从 floor 抬到满额（选中透镜，静止也保留弱折射）。
     * false：静止即满额基础折射，按压只小幅增强（轨道）。
     */
    pressScalesRefraction: Boolean = false,
    /** pressScalesRefraction=true 时的静止折射下限，避免静止像灰片。 */
    refractionFloor: Float = 0.55f
): PhysicalLensParams = with(scope) {
    val progress = interactionProgress.coerceIn(0f, 1f)
    val motion = motionIntensity.coerceIn(0f, 1f)
    val blurPx = if (enableBlur && material.blurDp > 0f) {
        material.blurDp.dp.toPx()
    } else {
        0f
    }

    // 真 RuntimeShader/lens 仅 API 33+；API31/32 保留 blur + RGB 分离色散近似
    if (!isRuntimeLensEnabled()) {
        val fringeDp = if (allowChromaticAberration && material.chromaticAberration) {
            val restFringe = if (chromaticAberrationAtRest) 0.55f else 0f
            (restFringe + progress * 1.8f + motion * 1.2f).coerceIn(0f, 2.2f)
        } else {
            0f
        }
        return@with PhysicalLensParams(
            blurPx = blurPx,
            refractionHeightPx = 0f,
            refractionAmountPx = 0f,
            useLens = false,
            chromaticAberration = false,
            fringePx = fringeDp.dp.toPx()
        )
    }

    val (heightScale, amountScale) = lensScales(
        pressScalesRefraction, refractionFloor, progress, motion
    )

    val refractionHeight = (material.refractionHeightDp.dp.toPx() * heightScale)
        .coerceIn(0f, minCornerRadiusPx)
    val refractionAmount = (material.refractionAmountDp.dp.toPx() * amountScale)
        .coerceIn(0f, minDimensionPx * 0.40f)

    val useLens = canUseLiquidLens(
        shape = shape,
        refractionHeightPx = refractionHeight,
        refractionAmountPx = refractionAmount,
        minCornerRadiusPx = minCornerRadiusPx,
        minDimensionPx = minDimensionPx
    )
    val chromatic = useLens &&
        allowChromaticAberration &&
        material.chromaticAberration &&
        (chromaticAberrationAtRest || progress > 0.05f || motion > 0.05f)

    PhysicalLensParams(
        blurPx = blurPx,
        refractionHeightPx = refractionHeight,
        refractionAmountPx = refractionAmount,
        useLens = useLens,
        chromaticAberration = chromatic
    )
}

/**
 * 折射强度的缩放：返回 (斜坡宽度系数, 位移幅度系数)。
 *
 * **两条路读同一个函数** —— [resolvePhysicalLens]（API33+ 走平台 AGSL）和
 * [glassLensOpticsFrom]（API31/32 走离屏 ES 2.0）。目标是同一个控件在两种平台上
 * 的折射**观感一致**，所以随按压/速度的变化曲线必须逐帧相同。
 *
 * 曾经离屏那条路自己写了一份"两者同乘一个数"的版本，理由是想让
 * `位移/斜坡` 比值恒定。那条推理本身没错（比值确实决定边缘挤压的陡度），
 * 但代价是 31/32 与 33+ 的曲线不同 —— 同一个控件在两种机器上折射强度对不上，
 * 那就不是"在低版本上复现 AGSL"，而是做出了第二种效果。既然目标是复现，
 * 曲线就以 33+ 为准。
 */
private fun lensScales(
    pressScalesRefraction: Boolean,
    refractionFloor: Float,
    progress: Float,
    motion: Float
): Pair<Float, Float> {
    val floor = refractionFloor.coerceIn(0f, 1f)
    return if (pressScalesRefraction) {
        // 选中透镜：静止保留 floor 折射；按压/速度抬到 1
        val base = floor + (1f - floor) * progress
        (base * (1f + motion * 0.25f)) to (base * (1f + motion * 0.35f))
    } else {
        // 轨道：静止满额弱折射，按压小幅增强
        (1f + progress * 0.20f + motion * 0.10f) to
            (1f + progress * 0.30f + motion * 0.15f)
    }
}

/**
 * 从同一份 [GlassMaterialSpec] 推出 API 31/32 离屏折射的光学参数。
 *
 * ## 为什么要有这个函数
 *
 * API 33+ 的折射参数走 [resolvePhysicalLens] → 库的 `lens()`；API 31/32 走
 * [GlassLensOptics] → 自家 ES 2.0 着色器。这里把 dp 配方换成着色器要的像素量，
 * 并复用同一个 [GlassMaterialSpec] 的 `chromaticAberration` 开关。
 *
 * 数值与随交互的变化曲线都取自**同一份** [material] 和同一个 [lensScales]，
 * 因为目标是在低版本上复现 33+ 的观感，不是做第二种效果。改配方时两条路一起变。
 *
 * ## 与 33+ 的差异只在换算方式
 *
 * 库的 `refractionHeight` 是**绝对像素**且被 `coerceIn(0, minCornerRadiusPx)` 夹住；
 * 这里换成**占 min(w,h)/2 的比例**，因为 Prismal 那条 ≤ 0.35 的尺寸规则是相对量
 * （超了整个形状会退化成空心环）。在胶囊上两者等价：胶囊的 minCornerRadius
 * 就是 min(w,h)/2。
 */
fun glassLensOpticsFrom(
    material: GlassMaterialSpec,
    density: Density,
    cornerRadiusPx: Float,
    /** 元素较短边的像素长度，用来把 dp 配方换成比例 */
    minDimensionPx: Float,
    interactionProgress: Float = 0f,
    motionIntensity: Float = 0f,
    /** true：静止保留 floor 折射，按压抬到满额（选中透镜）。 */
    pressScalesRefraction: Boolean = false,
    refractionFloor: Float = 0.55f,
    /** 静止是否保留弱色散。选中透镜给 true，静态面板给 false。 */
    chromaticAberrationAtRest: Boolean = false,
    /**
     * 饱和度提升，必须与调用点在 33+ 那条路上**是否调了 `vibrancy()`** 一致。
     * 详见 [GlassLensOptics.vibrancy]。
     */
    vibrancy: Float = 1.28f
): GlassLensOptics = with(density) {
    val progress = interactionProgress.coerceIn(0f, 1f)
    val motion = motionIntensity.coerceIn(0f, 1f)

    // 与 resolvePhysicalLens 读**同一个** lensScales：两条路的曲线逐帧相同
    val (heightScale, amountScale) = lensScales(
        pressScalesRefraction, refractionFloor, progress, motion
    )

    // 上限不在这里施加：斜坡上限要按元素**实测**尺寸算，而这里只有标称尺寸。
    // 见 Modifier.glassLens —— 它夹斜坡时会把位移同比例缩，形状不变。
    val heightPx = material.refractionHeightDp.dp.toPx() * heightScale

    // 位移**照库的原值**，不再压。
    //
    // 曾经这里有 `.coerceAtMost(heightPx * 0.5f)`，理由是"锐利采样源经不起大位移"。
    // 这条推理链整个是错的，已在 API 35 上截库自己的实现验证：
    //   - 库的 LiquidBottomTabs 指示器是 `lens(10dp, 14dp)`，amount/height = 1.4；
    //     Glass playground 默认 amount = 2× height。位移大于斜坡是**常态**。
    //   - 库采的也是 blur(8dp) 过的 backdrop（LiquidBottomTabs.kt:242），
    //     和这边一样，并没有更"锐利"的源。
    //   - 那道鱼眼鼓包的真凶是**静止态也在折射**（refractionFloor 0.42）。库静止
    //     态 lens 参数全乘 pressProgress = 0，压根不折射，所以不可能有这个问题。
    // 只保留一条防御：位移不超过元素短边，避免采样跑到底图之外。
    val amountPx = (material.refractionAmountDp.dp.toPx() * amountScale)
        .coerceIn(0f, minDimensionPx)

    // 色散：库那边是布尔开关（开 = 1.0）。这里做成连续量，静止弱、交互强，
    // 滑动时蓝黄边随速度浮现而不是硬跳。
    val restDispersion = if (chromaticAberrationAtRest) 0.55f else 0f
    val dispersion = if (material.chromaticAberration) {
        (restDispersion + progress * 0.8f + motion * 0.7f).coerceIn(0f, 1.6f)
    } else {
        0f
    }

    GlassLensOptics(
        cornerRadiusPx = cornerRadiusPx,
        thicknessPx = heightPx,
        lensAmountPx = amountPx,
        dispersion = dispersion,
        // 库的底部标签栏不开 depthEffect（LiquidBottomTabs.kt:246 只有两个位置参数）。
        // 开了会把梯度混向径向，胶囊中部也跟着位移，观感是一颗球而不是一片玻璃。
        depthEffect = 0f,
        vibrancy = vibrancy
    )
}

/** 把横向速度归一成 0..1 的运动强度。 */
fun motionIntensityFromVelocity(
    velocityX: Float,
    fullEffectVelocity: Float
): Float = (abs(velocityX) / fullEffectVelocity.coerceAtLeast(1f)).coerceIn(0f, 1f)

/**
 * 从任意 [Shape] 取出离屏着色器要的圆角半径（像素）。
 *
 * 着色器只有一个 `sdRoundedRect`，所以形状必须落到「一个圆角半径」上。三类：
 *
 * - [CornerBasedShape]：读 `topStart`。`CircleShape` / `RoundedCornerShape(50%)`
 *   会解析成 `min(w,h)/2`，也就是胶囊/圆，正好是想要的；
 * - kyant 的 `RoundedRectangularShape`（连续曲率 squircle，`RoundedRectangle` /
 *   `Capsule` 都是）：调 `corners()` 拿已解析的像素半径，读左上角。
 *   **这一类不能落到下面那条兜底**：弹窗的 `RoundedRectangle(32dp)` 会被当成
 *   `min(w,h)/2` ≈ 98dp，SDF 于是是个胶囊而不是 32dp 圆角的矩形 —— 折射的斜坡
 *   沿胶囊轮廓走，屏幕上是卡片里一道大弧。API32 上实拍到过，与 33+ 对照才看出来。
 *   库自己的 `lens()` 也是从这个接口取 `cornerRadii` 的，所以两条路同源。
 * - 其它形状：退回 `min(w,h)/2`。这是**保守**方向 —— 半径宁可偏大：
 *   偏小会让折射的轮廓落在 Compose 侧形状的**内部**，两者之间那一圈里着色器输出
 *   透明、而库的 surface/highlight 照真形状画，屏幕上就是一层套在玻璃外的"壳"。
 *   那个缺陷之前修过一次，别再从这里放回来。
 *
 * 实际的上限仍由 `Modifier.glassLens` 按实测短边夹一次（见那边的注释），
 * 所以这里返回的值即便偏大也不会让 SDF 退化。
 */
fun lensCornerRadiusPx(
    shape: androidx.compose.ui.graphics.Shape,
    widthPx: Float,
    heightPx: Float,
    density: Density
): Float {
    val halfMin = minOf(widthPx, heightPx) / 2f
    val size = androidx.compose.ui.geometry.Size(widthPx, heightPx)
    if (shape is androidx.compose.foundation.shape.CornerBasedShape) {
        val r = shape.topStart.toPx(size, density)
        if (r.isFinite() && r > 0f) return minOf(r, halfMin)
    }
    if (shape is com.kyant.shapes.RoundedRectangularShape) {
        val r = shape.corners(size, LayoutDirection.Ltr, density).topLeft
        if (r.isFinite() && r > 0f) return minOf(r, halfMin)
    }
    return halfMin
}

package com.tyust.course.ui.system.glass

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

    val floor = refractionFloor.coerceIn(0f, 1f)
    // 选中透镜：静止保留 floor 折射；按压/速度抬到 1
    // 轨道：静止满额弱折射，按压小幅增强
    val heightScale = if (pressScalesRefraction) {
        (floor + (1f - floor) * progress) * (1f + motion * 0.25f)
    } else {
        1f + progress * 0.20f + motion * 0.10f
    }
    val amountScale = if (pressScalesRefraction) {
        (floor + (1f - floor) * progress) * (1f + motion * 0.35f)
    } else {
        1f + progress * 0.30f + motion * 0.15f
    }

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

/** 把横向速度归一成 0..1 的运动强度。 */
fun motionIntensityFromVelocity(
    velocityX: Float,
    fullEffectVelocity: Float
): Float = (abs(velocityX) / fullEffectVelocity.coerceAtLeast(1f)).coerceIn(0f, 1f)

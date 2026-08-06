package com.tyust.course.ui.system.glass

import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.GlassMaterialSpec
import com.tyust.course.ui.system.canUseLiquidLens
import com.tyust.course.ui.system.isRuntimeShaderTrulySupported
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
    val chromaticAberration: Boolean
)

fun resolvePhysicalLens(
    density: Density,
    material: GlassMaterialSpec,
    shape: Shape,
    minCornerRadiusPx: Float,
    minDimensionPx: Float,
    interactionProgress: Float = 0f,
    motionIntensity: Float = 0f,
    enableBlur: Boolean = true,
    allowChromaticAberration: Boolean = true,
    /**
     * true：折射随按压从 floor 抬到满额（选中透镜，静止也保留弱折射）。
     * false：静止即满额基础折射，按压只小幅增强（轨道）。
     */
    pressScalesRefraction: Boolean = false,
    /** pressScalesRefraction=true 时的静止折射下限，避免静止像灰片。 */
    refractionFloor: Float = 0.55f
): PhysicalLensParams = with(density) {
    val progress = interactionProgress.coerceIn(0f, 1f)
    val motion = motionIntensity.coerceIn(0f, 1f)
    val blurPx = if (enableBlur && material.blurDp > 0f) {
        material.blurDp.dp.toPx()
    } else {
        0f
    }

    // 真 RuntimeShader/lens 仅 API 33+；API31/32 只保留 blur（调用侧需自行保证 blur 开启）
    if (!isRuntimeShaderTrulySupported()) {
        return@with PhysicalLensParams(
            blurPx = blurPx,
            refractionHeightPx = 0f,
            refractionAmountPx = 0f,
            useLens = false,
            chromaticAberration = false
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
    // 有 lens 即出色散（静止微量、运动增强），不再设进入门槛
    val chromatic = useLens &&
        allowChromaticAberration &&
        material.chromaticAberration

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
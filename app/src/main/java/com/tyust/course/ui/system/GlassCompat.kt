package com.tyust.course.ui.system

import android.os.Build
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop

enum class GlassCapability {
    Material,
    StaticGlass,
    Backdrop,
    DynamicLens
}

fun currentGlassCapability(): GlassCapability = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> GlassCapability.StaticGlass
    !GlassRuntimeGuard.isBackdropEnabled() -> GlassCapability.Material
    // 实验期：Android 12（API 31/32）也进入 lens 尝试路径，不预先按版本屏蔽。
    // 库内部 isRuntimeShaderSupported() 仍会在 <33 时 no-op，属于平台能力上限。
    GlassRuntimeGuard.isDynamicOpticsEnabled() -> GlassCapability.DynamicLens
    else -> GlassCapability.Backdrop
}

/**
 * Backdrop 的 blur / vibrancy 依赖 Android 12 图形管线。
 * 设备默认启用；只有真实运行失败后才由 GlassRuntimeGuard 降级。
 */
fun isBackdropSupported(): Boolean =
    currentGlassCapability() == GlassCapability.Backdrop ||
        currentGlassCapability() == GlassCapability.DynamicLens

/**
 * 实验档：是否进入 lens 调用路径。API 31+ 均放行；
 * 真正的 RuntimeShader 折射/色散仅 API 33+ 生效（见 isRuntimeShaderTrulySupported）。
 */
fun isLensSupported(): Boolean = currentGlassCapability() == GlassCapability.DynamicLens

/**
 * 平台是否真支持 RuntimeShader/AGSL（Android 13+）。
 * 仅用于决定是否叠加 33+ 才合理的增强（如镜面高光/阴影），不用于屏蔽 lens 调用。
 */
fun isRuntimeShaderTrulySupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

private const val roundedRectangularShapeInterface =
    "com.kyant.shapes.RoundedRectangularShape"

private fun Class<*>.implementsInterface(interfaceName: String): Boolean =
    interfaces.any { candidate ->
        candidate.name == interfaceName || candidate.implementsInterface(interfaceName)
    } || superclass?.implementsInterface(interfaceName) == true

fun canUseLiquidLens(
    shape: Shape,
    refractionHeightPx: Float,
    refractionAmountPx: Float,
    minCornerRadiusPx: Float,
    minDimensionPx: Float
): Boolean =
    isBackdropSupported() &&
        // 真折射依赖 RuntimeShader；不以 DynamicLens 实验档冒充 API31/32 有 lens
        isRuntimeShaderTrulySupported() &&
        (shape is CornerBasedShape ||
            shape.javaClass.implementsInterface(roundedRectangularShapeInterface)) &&
        refractionHeightPx.isFinite() &&
        refractionAmountPx.isFinite() &&
        minCornerRadiusPx.isFinite() &&
        minDimensionPx.isFinite() &&
        refractionHeightPx > 0f &&
        refractionAmountPx > 0f &&
        minCornerRadiusPx > 0f &&
        minDimensionPx > 0f &&
        refractionHeightPx <= minCornerRadiusPx &&
        refractionAmountPx <= minDimensionPx

/**
 * 根 Backdrop 由页面入口注入；为空时组件使用非玻璃实现。
 */
val LocalAppBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 页面底部不可被 Popup 覆盖的浮层高度，例如底部导航栏。
 */
val LocalAppOverlayBottomInset = staticCompositionLocalOf<Dp> { 0.dp }
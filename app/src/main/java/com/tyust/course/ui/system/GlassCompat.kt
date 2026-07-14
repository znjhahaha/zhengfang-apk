package com.tyust.course.ui.system

import android.os.Build
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop

/**
 * Backdrop 的 blur / vibrancy 依赖 Android 12 图形管线。
 * 设备默认启用；只有真实运行失败后才由 GlassRuntimeGuard 降级。
 */
fun isBackdropSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        GlassRuntimeGuard.isBackdropEnabled()

/**
 * lens 使用 RuntimeShader / AGSL，仅在 Android 13 及以上启用。
 */
fun isLensSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

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
        isLensSupported() &&
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
package com.tyust.course.ui.system

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.RoundedRectangularShape
import com.tyust.course.manager.AppearanceSettingsManager

enum class GlassCapability {
    Material,
    StaticGlass,
    Backdrop,
    DynamicLens
}

fun currentGlassCapability(): GlassCapability = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> GlassCapability.StaticGlass
    // 用户在设置里显式关掉：直接落到 Material，走各组件已有的不透明回退分支
    // （低于 API 31 的设备一直走那条路径，不需要新写任何渲染分支）。
    // 读的是 Compose state，而 isBackdropSupported() 都在组合期被调用，拨动即时重绘。
    !AppearanceSettingsManager.glassEffectEnabled -> GlassCapability.Material
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

/** 当前设备与性能档位是否都允许真正的 RuntimeShader 透镜。 */
fun isRuntimeLensEnabled(): Boolean =
    isLensSupported() && isRuntimeShaderTrulySupported()

/**
 * 库的 lens 支持哪些形状。
 *
 * **这不是画质判据，是崩溃判据。** `com.kyant.backdrop.effects.lens()` 会从
 * `BackdropEffectScope.shape` 推 `cornerRadii`，只认这三类：
 * `RoundedRectangularShape`（kyant 的连续曲率形状）、`AbsoluteRoundedCornerShape`、
 * `CornerBasedShape`；**其它形状一律 `throwUnsupportedSDFException()` 直接抛
 * `UnsupportedOperationException`，而它发生在 draw 阶段，整个 App 立刻闪退。**
 * （`AbsoluteRoundedCornerShape` 是 `CornerBasedShape` 的子类，所以两条判定就够。）
 *
 * App 里确实有形状进不了这道门：`LiquidPicker` 的液滴融合体用的是 Path 拼出来的
 * 隐式曲面（generic outline），它靠 `forceBlurFallback` 走纯模糊路径。但
 * `forceBlurFallback` 只改 `enableBlur`，**拦不住 lens**——拦它的就是这里。
 *
 * 写成 `is` 而不是按类名反射：反射版本在混淆/裁剪下可能静默失效，而且 R8 只有看见
 * 真正的 `instanceof` 才会把这个接口关系当成"被用作类型检查"而完整保留。
 */
fun isLensShapeSupported(shape: Shape): Boolean =
    shape is RoundedRectangularShape || shape is CornerBasedShape

/**
 * 能不能开真透镜。
 *
 * 形状那一项**必须**在（见 [isLensShapeSupported]）：它不是画质优化，是防闪退。
 * 我曾把它当成"多余的防御"删掉，结果 `LiquidPicker` 的液滴融合体一上屏就抛
 * `UnsupportedOperationException`，打开即闪退。
 *
 * 顺带记下另一件已被证伪的判断：我曾认为 R8 full mode 裁掉了
 * `Capsule → RoundedRectangularShape` 的实现关系，导致 release 包全 App 无折射。
 * **不成立**：R8 mapping 里该接口原名保留未混淆，`dexdump` release APK 也能看到
 * `Lcom/kyant/shapes/Capsule;` 仍然声明 `Interfaces #0: RoundedRectangularShape`。
 * release 包看起来没有折射的真实原因在 `MainActivity.debugPiracyWatermark` 的注释里。
 *
 * 其余判据都是纯算术，与构建类型无关。**别再往这里加按类名反射的判据。**
 */
fun canUseLiquidLens(
    shape: Shape,
    refractionHeightPx: Float,
    refractionAmountPx: Float,
    minCornerRadiusPx: Float,
    minDimensionPx: Float
): Boolean =
    isRuntimeLensEnabled() &&
        isLensShapeSupported(shape) &&
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

/** 全局材质明暗只跟随系统；自定义壁纸的颜色由局部外观解析器处理。 */
@Composable
fun rememberGlassDarkTheme(): Boolean =
    isSystemInDarkTheme()

/**
 * 根 Backdrop 由页面入口注入；为空时组件使用非玻璃实现。
 */
val LocalAppBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 交互控件（按钮、开关、分段栏、内联通知）的采样源。与底部 Tab 同源，
 * 都采样壁纸层，因此折射与高光只来自真实底色，而不是人造灰阶。
 */
val LocalControlBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 模态层的采样源：包含壁纸与页面内容，由页面根容器在捕获层之外下发。
 * 弹窗需要折射"身后真实画面"，因此比控件采样源多一层页面内容。
 */
val LocalModalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 页面底部不可被 Popup 覆盖的浮层高度，例如底部导航栏。
 */
val LocalAppOverlayBottomInset = staticCompositionLocalOf<Dp> { 0.dp }

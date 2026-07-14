package com.tyust.course.ui.system

import android.os.Build
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop

// ═══════════════════════════════════════════════════════════
// Liquid Glass 兼容性守卫 + 全局 Backdrop CompositionLocal
// ═══════════════════════════════════════════════════════════

/**
 * Backdrop 的核心 RenderEffect（blur / vibrancy）需要 Android 12 (API 31) 的图形管线。
 * 老设备会自动回退到现有 neumorphicShadow + glassHighlight 视觉。
 */
fun isBackdropSupported(): Boolean {
    // API < 31 无 RenderEffect，直接禁用
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    // 已知 GPU 驱动不兼容 RenderEffect / RuntimeShader 的厂商
    val manufacturer = Build.MANUFACTURER.lowercase()
    val incompatibleVendors = listOf(
        "vivo",     // OriginOS — GPU shader 兼容性问题
        "oppo",     // ColorOS
        "oneplus",  // OxygenOS（OPPO 旗下）
        "realme",   // realme UI（OPPO 旗下）
    )
    if (incompatibleVendors.any { manufacturer.contains(it) }) return false
    return true
}

/**
 * lens 透镜折射 + 色散使用 RuntimeShader / AGSL，需要 Android 13 (API 33)。
 * 在 API 31~32 上仅启用 vibrancy + blur，得到磨砂玻璃；
 * 在 API 33+ 上额外叠加 lens，得到 iOS 26 风格的边缘 RGB 色散。
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
 * 全局根 Backdrop。由 MainActivity / 各入口屏幕在最外层注入，
 * 子层（SystemCard、SystemTopBar 等）通过此 CompositionLocal 自动获取，
 * 避免一层层 prop drilling。
 *
 * 为 null 时表示当前作用域没有可用 Backdrop，调用方应回退到非玻璃实现。
 */
val LocalAppBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 应用内覆盖在页面之上的底部区域，例如悬浮导航栏。
 * Popup 使用该值收紧可用窗口，避免菜单进入不可交互的覆盖层。
 */
val LocalAppOverlayBottomInset = staticCompositionLocalOf<Dp> { 0.dp }

/**
 * 玻璃配方常量。所有数值均为 dp / alpha，调用方按需 toPx。
 *
 * 设计原则（对齐 Apple Liquid Glass）：
 *  - SurfaceAlpha 极低（0.06~0.10），玻璃以透明为主
 *  - blur 作为辅助磨砂，主视觉来自 lens 折射 + 色散
 *  - refractionHeight ≤ shape.minCornerRadius，否则角部不连续
 */
object GlassRecipe {
    // ═══════════════════════════════════════════════════════════
    // 参数调校原则：
    //  - 降低 SurfaceAlpha 以实现极高通透度的水滴感，杜绝塑料片
    //  - 增大 refractionHeight 和 refractionAmount 强化边缘折射与色散
    //  - 适当减小卡片的 blur 半径，保留底座透光的炫彩细节
    // ═══════════════════════════════════════════════════════════

    // 底栏胶囊（28dp 圆角）
    val BlurDp = 6f
    val RefractionHeightDp = 14f          // ≤ 28dp 圆角，展现极厚玻璃底座
    val RefractionAmountDp = 32f          // 调高以增强边缘折射彩色拉扯
    val SurfaceAlpha = 0.30f              // 对齐文档示例，保证磨砂可见
    val BarBorderAlpha = 0.40f            // 描边微亮，构筑高光边缘

    // 普通卡片（16dp 圆角）
    val CardBlurDp = 4f                   // 降低模糊，增加底层色彩透射
    val CardRefractionHeightDp = 10f       // 隆起更高，边缘光带更明显
    val CardRefractionAmountDp = 24f      // 偏折更剧烈
    val CardSurfaceAlpha = 0.08f          // 极大通透度
    val CardBorderAlpha = 0.40f

    // 选中指示器（22dp 圆角，嵌套 combined 玻璃）
    val IndicatorRefractionHeightDp = 12f  // 强透镜隆起
    val IndicatorRefractionAmountDp = 24f  // 调高偏折
    val IndicatorSurfaceAlpha = 0.05f     // 极其通透，仅作折射
    val IndicatorBorderAlpha = 0.45f      // 高反光描边

    // 顶栏（矩形，无圆角限制但保持低调）
    val TopBarBlurDp = 6f
    val TopBarSurfaceAlpha = 0.08f
    val TopBarLensRefractionHeightDp = 4f
    val TopBarLensRefractionAmountDp = 12f

    // 登录卡片 / 弹窗（24dp 圆角）
    val SheetBlurDp = 8f
    val SheetRefractionHeightDp = 14f
    val SheetRefractionAmountDp = 32f
    val SheetSurfaceAlpha = 0.12f

    // SegmentedControl：轨道保持安静，选中指示器用中性折射与动态高光表达液态感
    val SegTrackBlurDp = 7f
    val SegTrackSurfaceAlpha = 0.10f
    val SegTrackBorderAlpha = 0.26f
    val SegIndicatorBlurDp = 3f
    val SegIndicatorRefractionHeightDp = 6f
    val SegIndicatorRefractionAmountDp = 10f
    val SegIndicatorSurfaceAlpha = 0.14f
    val SegIndicatorBorderAlpha = 0.52f
    val SegIndicatorShadowAlpha = 0.10f
    val SegIndicatorPressedShadowAlpha = 0.18f
    val SegIndicatorPressedScale = 1.055f
    val SegIndicatorMaxVelocityStretch = 0.13f

    // ActionButton：只有 prominent / destructive 使用语义色，其余保持中性玻璃
    val ActionBlurDp = 4f
    val ActionRefractionHeightDp = 6f
    val ActionRefractionAmountDp = 10f
    val ActionSurfaceAlpha = 0.16f
    val ActionBorderAlpha = 0.40f
    val ActionShadowAlpha = 0.12f
    val ActionPressedScale = 0.975f
    val ActionTintAlpha = 0.84f
    val ActionDisabledSurfaceAlpha = 0.20f

    // Toggle：轨道提供状态色，玻璃拇指只在交互时承担液态质感
    val SwitchTrackInactiveAlpha = 0.58f
    val SwitchTrackActiveAlpha = 0.82f
    val SwitchThumbBlurDp = 2.5f
    val SwitchThumbRefractionHeightDp = 8f
    val SwitchThumbRefractionAmountDp = 14f
    val SwitchThumbSurfaceAlpha = 0.34f
    val SwitchThumbBorderAlpha = 0.72f
    val SwitchThumbShadowAlpha = 0.14f
    val SwitchPressedScaleX = 1.12f
    val SwitchPressedScaleY = 0.94f

    // Picker：触发器与锚定菜单共享 regular glass 语言，菜单提高不透明度保证文字可读
    val PickerBlurDp = 6f
    val PickerRefractionHeightDp = 6f
    val PickerRefractionAmountDp = 10f
    val PickerSurfaceAlpha = 0.12f
    val PickerExpandedSurfaceAlpha = 0.18f
    val PickerBorderAlpha = 0.34f
    val PickerExpandedBorderAlpha = 0.54f
    val PickerShadowAlpha = 0.10f
    val PickerExpandedShadowAlpha = 0.18f
    val PickerMenuBlurDp = 10f
    val PickerMenuRefractionHeightDp = 8f
    val PickerMenuRefractionAmountDp = 12f
    val PickerMenuSurfaceAlpha = 0.76f
    val PickerMenuBorderAlpha = 0.42f
    val PickerMenuShadowAlpha = 0.20f
    val PickerSelectedSurfaceAlpha = 0.10f

    // ═══════════════════════════════════════════════════════════
    // 3D 景深与色彩饱和度补偿（对齐官方 Demo 配方）
    // ═══════════════════════════════════════════════════════════

    // 全局色彩增艳倍率（补偿磨砂模糊造成的彩光损耗）
    val Saturation = 1.5f

    // 使用渐变背景时的景深折射
    val CardDepthEffect = true

    // Dialog：模态层只使用中性环境阴影与细边缘，不制造新拟态光晕
    val DialogCornerDp = 32f
    val DialogBlurDp = 10f
    val DialogRefractionHeightDp = 10f
    val DialogRefractionAmountDp = 18f
    val DialogSurfaceAlpha = 0.78f
    val DialogBorderAlpha = 0.30f
    val DialogShadowAlpha = 0.14f
    val DialogShadowElevationDp = 6f

    // 亮度自适应采样参数
    val LuminanceSampleSize = 5           // 5x5 = 25 像素极低分化采样
    val LuminanceSampleHz = 3             // 每秒采样 3 帧
}
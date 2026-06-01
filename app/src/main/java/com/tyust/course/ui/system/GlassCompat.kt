package com.tyust.course.ui.system

import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import com.kyant.backdrop.Backdrop

// ═══════════════════════════════════════════════════════════
// Liquid Glass 兼容性守卫 + 全局 Backdrop CompositionLocal
// ═══════════════════════════════════════════════════════════

/**
 * Backdrop 的核心 RenderEffect（blur / vibrancy）需要 Android 12 (API 31) 的图形管线。
 * 老设备会自动回退到现有 neumorphicShadow + glassHighlight 视觉。
 * x86 模拟器：ARM 翻译层与 native 代码不兼容。
 */

private fun isEmulator(): Boolean =
    Build.FINGERPRINT.startsWith("generic") ||
    Build.FINGERPRINT.startsWith("unknown") ||
    Build.MODEL.contains("google_sdk") ||
    Build.MODEL.contains("Emulator") ||
    Build.MODEL.contains("Android SDK built for x86") ||
    Build.HARDWARE.contains("goldfish") ||
    Build.HARDWARE.contains("ranchu") ||
    Build.PRODUCT.contains("sdk") ||
    Build.PRODUCT.contains("emulator")

fun isBackdropSupported(): Boolean {
    return true
}

/**
 * lens 透镜折射 + 色散使用 RuntimeShader / AGSL，需要 Android 13 (API 33)。
 * 在 API 31~32 上仅启用 vibrancy + blur，得到磨砂玻璃；
 * 在 API 33+ 上额外叠加 lens，得到 iOS 26 风格的边缘 RGB 色散。
 */
fun isLensSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * 全局根 Backdrop。由 MainActivity / 各入口屏幕在最外层注入，
 * 子层（SystemCard、SystemTopBar 等）通过此 CompositionLocal 自动获取，
 * 避免一层层 prop drilling。
 *
 * 为 null 时表示当前作用域没有可用 Backdrop，调用方应回退到非玻璃实现。
 */
val LocalAppBackdrop = staticCompositionLocalOf<Backdrop?> { null }

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

    // SegmentedControl（14dp 轨道圆角 / 10dp 指示器圆角）
    val SegTrackBlurDp = 6f
    val SegTrackSurfaceAlpha = 0.08f
    val SegIndicatorBlurDp = 6f
    val SegIndicatorRefractionHeightDp = 8f
    val SegIndicatorRefractionAmountDp = 16f
    val SegIndicatorSurfaceAlpha = 0.08f

    // ActionButton（12dp 圆角）
    val ActionBlurDp = 6f
    val ActionRefractionHeightDp = 8f
    val ActionRefractionAmountDp = 16f
    val ActionSurfaceAlpha = 0.08f

    // ═══════════════════════════════════════════════════════════
    // 3D 景深与色彩饱和度补偿（对齐官方 Demo 配方）
    // ═══════════════════════════════════════════════════════════

    // 全局色彩增艳倍率（补偿磨砂模糊造成的彩光损耗）
    val Saturation = 1.5f

    // 使用渐变背景时的景深折射
    val CardDepthEffect = true

    // Dialog / 弹窗专用高折射配方（48dp 超大圆角）
    val DialogCornerDp = 48f
    val DialogBlurDp = 12f
    val DialogRefractionHeightDp = 24f    // ≤ 48dp 圆角，极致水晶镇纸感
    val DialogRefractionAmountDp = 48f
    val DialogSurfaceAlpha = 0.08f
    val DialogDepthEffect = true
    val DialogBrightness = 0.2f           // 亮色模式提亮

    // 亮度自适应采样参数
    val LuminanceSampleSize = 5           // 5x5 = 25 像素极低分化采样
    val LuminanceSampleHz = 3             // 每秒采样 3 帧
}
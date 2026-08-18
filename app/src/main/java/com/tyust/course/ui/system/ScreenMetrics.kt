package com.tyust.course.ui.system

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 可用高的宽松线。到这个高度就不再压缩任何东西。 */
private const val SqueezeCeilDp = 720f

/** 从宽松线再往下 160dp 压到底：720 → 0，560 → 1。 */
private const val SqueezeRangeDp = 160f

private const val NarrowCeilDp = 380f
private const val NarrowRangeDp = 40f

/**
 * 屏幕余量。两个系数都是 **0 = 宽松，1 = 最紧**，专门用来 lerp 写死的几何。
 *
 * ## 为什么需要它
 *
 * 全 App 的尺寸是照 20:9（411×914dp）手调的常量。换到 16:9（360×640dp）横向少 51dp、
 * 纵向少 274dp，可用高更是从 862dp 掉到 568dp——顶栏加底栏能吃掉近一半屏幕。
 *
 * ## 阈值为什么这么选
 *
 * **20:9 上两个系数都恰好为 0**，于是所有 `tall()/wide()` 都返回原来那个常量，
 * 20:9 的观感一个 dp 都不变。这是这次适配的硬约束：调这里的阈值前先想清楚
 * 它会不会把 20:9 拖离 0。
 *
 * ## 为什么是连续系数而不是尺寸档
 *
 * 分档会在阈值附近出现"两台差 5dp 的设备排版完全不同"的跳变，而且 18:9 这种中间
 * 比例只能被硬塞进某一档。连续插值让它自然拿到中间值。
 */
data class ScreenMetrics(
    val widthDp: Dp,
    val heightDp: Dp,
    /** 可用高 = 窗口高 − 状态栏 − 导航栏。布局真正能用的就是这一段。 */
    val usableHeightDp: Dp,
    /** 纵向紧凑度。可用高 862（20:9）→ 0，568（16:9 三键导航）→ 0.95。 */
    val squeeze: Float,
    /** 横向紧凑度。宽 411 → 0，360 → 0.5。 */
    val narrow: Float
) {
    /** 按纵向紧凑度插值。[loose] 是现有的 20:9 尺寸，短屏收到 [tight]。 */
    fun tall(loose: Dp, tight: Dp): Dp = lerpDp(loose, tight, squeeze)

    /** 按横向紧凑度插值。 */
    fun wide(loose: Dp, tight: Dp): Dp = lerpDp(loose, tight, narrow)
}

/**
 * 窗口尺寸取 `LocalWindowInfo.containerSize` 而不是 `Configuration.screenHeightDp`：
 * 后者在不同 API 版本上对"是否已扣掉系统栏"的口径不一致，扣两遍会让中等屏被误判成短屏。
 * containerSize 是确定的窗口像素尺寸，减一次 inset 就得到真实可用高。
 */
@Composable
fun rememberScreenMetrics(): ScreenMetrics {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val containerSize = LocalWindowInfo.current.containerSize
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // containerSize 理论上在首帧之前就有值；真为 0 时退回 Configuration，
    // 否则首帧会被判成"最紧"，出现一帧压缩后的布局。
    val width = with(density) { containerSize.width.toDp() }
        .takeIf { it > 0.dp } ?: configuration.screenWidthDp.dp
    val height = with(density) { containerSize.height.toDp() }
        .takeIf { it > 0.dp } ?: configuration.screenHeightDp.dp
    return remember(width, height, statusBar, navBar) {
        val usable = (height - statusBar - navBar).coerceAtLeast(0.dp)
        ScreenMetrics(
            widthDp = width,
            heightDp = height,
            usableHeightDp = usable,
            squeeze = ((SqueezeCeilDp - usable.value) / SqueezeRangeDp).coerceIn(0f, 1f),
            narrow = ((NarrowCeilDp - width.value) / NarrowRangeDp).coerceIn(0f, 1f)
        )
    }
}

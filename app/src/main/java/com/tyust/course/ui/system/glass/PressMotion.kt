package com.tyust.course.ui.system.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * 按压形变量。分开给出 X/Y 是因为 iOS 的按压不是等比缩放：
 * 手指压下去时控件是被"压扁"的，宽度收得比高度少，松手时再弹回来。
 * 等比缩放看起来像整体退远，各向异性才有被按下去的实感。
 */
@Immutable
data class PressMotion(
    val scaleX: Float,
    val scaleY: Float,
    /** 0..1 按压进度，供玻璃层同步做光斑聚集与边缘收紧。 */
    val progress: Float = 0f
) {
    companion object {
        val None = PressMotion(1f, 1f, 0f)
    }
}

/**
 * 按下要"即刻到位"：高刚度、几乎不回弹，手指一碰就到底，这是跟手感的来源。
 */
private val PressDownSpec = spring<Float>(dampingRatio = 0.90f, stiffness = 900f)

/**
 * 松手才"弹"：低阻尼会让 progress 冲过 0 变成负值，
 * 于是 scale 越过 1 到约 1.02 再落回——这一下过冲就是 Q 感的全部来源。
 * 两端共用一条 spring 时按下和回弹一样软，既不跟手也不弹。
 */
private val ReleaseSpec = spring<Float>(dampingRatio = 0.42f, stiffness = 340f)

/**
 * iOS 式按压动效。
 *
 * 只负责【形变】，颜色反馈由 GlassPressIndication 承担，两者互不重叠：
 * 形变必须挂在按钮最外层才能带上底色一起动，而灰罩挂在 clickable 处即可。
 *
 * @param depth 压下深度。图标芯片 0.10，弹窗内文字按钮 0.06，无容器裸图标 0.14。
 */
@Composable
fun rememberPressMotion(
    pressed: Boolean,
    enabled: Boolean = true,
    depth: Float = 0.06f,
    reduceMotion: Boolean = false
): PressMotion {
    if (reduceMotion) return PressMotion.None

    val active = pressed && enabled
    val progress = remember { Animatable(0f) }
    LaunchedEffect(active) {
        progress.animateTo(
            targetValue = if (active) 1f else 0f,
            animationSpec = if (active) PressDownSpec else ReleaseSpec
        )
    }

    // 压扁：Y 吃满深度，X 只吃一半，两者之差就是横向铺开的幅度。
    // 差值太小（早先是 0.35 系数，depth 0.06 时只差 0.021）肉眼根本看不出来。
    val p = progress.value
    val squash = depth * 0.5f
    return PressMotion(
        scaleX = 1f - (depth - squash) * p,
        scaleY = 1f - depth * p,
        progress = p.coerceIn(0f, 1f)
    )
}
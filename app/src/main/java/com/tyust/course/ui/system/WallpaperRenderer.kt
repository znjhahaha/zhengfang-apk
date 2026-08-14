package com.tyust.course.ui.system

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.tyust.course.manager.WallpaperPreset

/**
 * 应用统一的流体壁纸绘制：底色 + 高光 + 多彩光斑 + 暗角。
 * 主界面与登录页共用，保证玻璃采样层次一致。
 */
fun DrawScope.drawWallpaperPattern(preset: WallpaperPreset) {
    val w = size.width
    val h = size.height

    // 底色 + 左上高光 + 右下暗角，提升明暗对比，让 Backdrop 折射有可采样层次。
    drawRect(preset.baseColor)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                preset.glowColor.copy(alpha = if (preset.isDark) 0.42f else 0.72f),
                Color.Transparent
            ),
            center = Offset(w * 0.18f, h * 0.12f),
            radius = w * 0.72f
        ),
        radius = w * 0.72f,
        center = Offset(w * 0.18f, h * 0.12f)
    )
    // 多彩流体光斑（Aurora 类预设）：静态径向渐变叠加，无动画开销
    for (accent in preset.accents) {
        val center = Offset(w * accent.cx, h * accent.cy)
        val radius = w * accent.radius
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accent.color.copy(alpha = accent.alpha),
                    Color.Transparent
                ),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
    }
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                preset.shadeColor.copy(alpha = if (preset.isDark) 0.30f else 0.12f),
                Color.Transparent
            ),
            center = Offset(w * 0.78f, h * 0.88f),
            radius = w * 0.64f
        ),
        radius = w * 0.64f,
        center = Offset(w * 0.78f, h * 0.88f)
    )
}

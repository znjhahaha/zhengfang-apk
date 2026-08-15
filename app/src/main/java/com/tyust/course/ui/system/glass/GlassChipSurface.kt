package com.tyust.course.ui.system.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 无背景玻璃：纯色底上的玻璃感来自【边缘】，不是来自【体积】。
 *
 * 这里踩过一个坑：用径向渐变在中下部造聚焦亮区，结果做出来是一颗珍珠。
 * 真实的玻璃圆片是【扁平】的——光在边缘因 Fresnel 效应聚集成一圈亮线，
 * 盘面本身几乎均匀。所以：
 *
 *   内缘亮线  -> 光感的唯一来源，紧贴边界，不会产生球体感
 *   外轮廓线  -> 界定形状，一条均匀的细灰线
 *   表面      -> 近乎平整，只留极弱的上下梯度
 *   阴影      -> 极轻甚至没有；重了就从"玻璃"变成"浮起的白球"
 */

/**
 * 把任意区域变成一枚玻璃芯片。不需要 Backdrop，因此顶栏图标按钮、
 * 纯色卡片上的控件都能用。
 *
 * @param dimmed 禁用态。容器保留，只压暗——容器直接消失会让按钮失去可点区域的暗示
 * @param pressProgress 按压进度。用 lambda 读取，形变只触发重绘不触发重组
 */
@Composable
fun Modifier.glassChip(
    shape: Shape,
    elevation: Dp = 0.dp,
    rimIntensity: Float = 1f,
    dimmed: Boolean = false,
    pressProgress: () -> Float = { 0f }
): Modifier {
    val isLight = !isSystemInDarkTheme()
    val strength = if (dimmed) 0.55f else 1f
    val rim = rimIntensity * if (dimmed) 0.5f else 1f
    // 浅色主题彻底不投影。参考图那枚返回键是没有阴影的，
    // 只要有阴影就会在浅底上糊出一圈晕，立刻从"玻璃片"变成"浮起的球"。
    val shadowColor = Color.Black.copy(alpha = 0.18f * strength)
    val useShadow = !dimmed && !isLight && elevation > 0.dp

    // 盘面要够淡。浅色背景上白色表面越实，轮廓线越糊，最后就是一颗白球。
    // 参考图里盘面只比背景亮一点点，形状全靠轮廓线交代。
    val surfaceBrush = if (isLight) {
        Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = 0.18f * strength),
            1.0f to Color.White.copy(alpha = 0.26f * strength)
        )
    } else {
        Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = 0.07f * strength),
            1.0f to Color.White.copy(alpha = 0.13f * strength)
        )
    }

    return this
        .then(
            if (useShadow) {
                Modifier.shadow(
                    elevation = elevation,
                    shape = shape,
                    clip = false,
                    ambientColor = shadowColor,
                    spotColor = shadowColor
                )
            } else {
                Modifier
            }
        )
        .clip(shape)
        .background(surfaceBrush)
        .glassRim(shape, rim, isLight, pressProgress)
}

/**
 * 玻璃边缘：外轮廓线 + 内缘亮线。
 *
 * 内缘亮线是关键——它把光锁在边界一圈之内，于是盘面保持平整，
 * 既有玻璃的通透光泽，又不会鼓成一颗球。顶部亮于底部只是很轻的偏置，
 * 用来暗示光源方向，幅度必须小，大了立刻又变立体。
 *
 * 直接用 [Shape.createOutline]，所以对 Capsule、连续曲率 squircle 的 Generic path、
 * CircleShape 一视同仁；不走 backdrop 库那条只认 CornerBasedShape 的圆角判断，
 * 也不依赖 AGSL，API 31/32 同样有效。
 */
fun Modifier.glassRim(
    shape: Shape,
    intensity: Float = 1f,
    isLightTheme: Boolean = true,
    pressProgress: () -> Float = { 0f }
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val contourWidth = 1.dp.toPx()

    // 内缘：把轮廓整体内缩一圈再描边，光就贴在玻璃内壁上
    val insetPx = 1.1.dp.toPx()
    val innerSize = Size(
        (size.width - insetPx * 2f).coerceAtLeast(0.1f),
        (size.height - insetPx * 2f).coerceAtLeast(0.1f)
    )
    val innerOutline = shape.createOutline(innerSize, layoutDirection, this)
    val innerWidth = 1.2.dp.toPx()

    // 浅色主题下白色内缘线画在白色盘面上等于没画（白叠白），只留一点点；
    // 深色主题反过来，白线才是唯一能看见的光。
    val baseContour = if (isLightTheme) 0.22f else 0.20f
    val baseInner = if (isLightTheme) 0.26f else 0.30f

    onDrawWithContent {
        drawContent()
        val p = pressProgress().coerceIn(0f, 1f)

        // 内缘亮线：顶部略强、底部略弱，只作为光源方向的暗示
        val innerAlpha = baseInner * intensity * (1f + 0.30f * p)
        val innerBrush = Brush.verticalGradient(
            colorStops = arrayOf(
                0.00f to Color.White.copy(alpha = innerAlpha),
                0.50f to Color.White.copy(alpha = innerAlpha * 0.55f),
                1.00f to Color.White.copy(alpha = innerAlpha * 0.75f)
            )
        )
        translate(insetPx, insetPx) {
            drawOutline(innerOutline, brush = innerBrush, style = Stroke(innerWidth))
        }

        // 外轮廓：均匀一圈，界定形状。按下时收紧变实，像被压出的应力边
        val contourColor = if (isLightTheme) {
            Color.Black.copy(alpha = baseContour * intensity * (1f + 0.5f * p))
        } else {
            Color.White.copy(alpha = baseContour * intensity * (1f + 0.5f * p))
        }
        drawOutline(outline, color = contourColor, style = Stroke(contourWidth))
    }
}
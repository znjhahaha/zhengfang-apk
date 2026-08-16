package com.tyust.course.ui.system

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.tyust.course.manager.WallpaperStyle
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 应用统一的流体壁纸绘制：底色 + 高光 + 多彩光斑 + 暗角；
 * 用户上传了图片时改画图片 + 模糊 + 蒙版。
 * 主界面、登录页、引导页与课表设置共用，保证玻璃采样层次一致。
 *
 * 参数是解析后的 [WallpaperStyle] 而不是预设枚举——用户自定义的底色/图片要走同一条路径。
 */
fun DrawScope.drawWallpaperPattern(style: WallpaperStyle) {
    val image = style.image
    if (image != null) {
        drawImageWallpaper(image, style)
        return
    }

    val w = size.width
    val h = size.height

    // 底色 + 左上高光 + 右下暗角，提升明暗对比，让 Backdrop 折射有可采样层次。
    drawRect(style.baseColor)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                style.glowColor.copy(alpha = if (style.isDark) 0.42f else 0.72f),
                Color.Transparent
            ),
            center = Offset(w * 0.18f, h * 0.12f),
            radius = w * 0.72f
        ),
        radius = w * 0.72f,
        center = Offset(w * 0.18f, h * 0.12f)
    )
    // 多彩流体光斑（Aurora 预设与自定义底色）：静态径向渐变叠加，无动画开销
    for (accent in style.accents) {
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
                style.shadeColor.copy(alpha = if (style.isDark) 0.30f else 0.12f),
                Color.Transparent
            ),
            center = Offset(w * 0.78f, h * 0.88f),
            radius = w * 0.64f
        ),
        radius = w * 0.64f,
        center = Offset(w * 0.78f, h * 0.88f)
    )
}

/**
 * 图片壁纸：模糊层 + 原图 + 蒙版。
 *
 * ## 模糊为什么不用 shader
 *
 * `RenderEffect.createBlurEffect` 要 API 31，`RenderScript` 已废弃。这里改用
 * 「把长边 72px 的缩略图双线性拉满整屏」——放大本身就是模糊，再用 `alpha = 1 - blur`
 * 把原图叠在上面，就得到 0→1 连续可调的模糊，minSdk 24 上一样跑。
 *
 * 顺带一个好处：模糊过的照片透过玻璃折射比锐利照片好看得多，锐利照片的高频细节
 * 会在 lens 里碎成噪点。
 */
private fun DrawScope.drawImageWallpaper(sharp: ImageBitmap, style: WallpaperStyle) {
    if (size.width <= 0f || size.height <= 0f) return

    val soft = style.imageSoft
    val blur = style.imageBlur.coerceIn(0f, 1f)
    // 底色先铺一层：图片比例与屏幕不一致的极端情况下不至于露出黑边
    drawRect(style.baseColor)
    if (soft != null && blur > 0f) {
        drawCenterCrop(soft, alpha = 1f)
    }
    val sharpAlpha = if (soft != null) 1f - blur else 1f
    if (sharpAlpha > 0.004f) {
        drawCenterCrop(sharp, alpha = sharpAlpha)
    }
    val dim = style.imageDim.coerceIn(0f, 1f)
    if (dim > 0f) {
        // 上限压到 0.55：拉满也还能看见照片，不会变成一块纯黑
        drawRect(Color.Black.copy(alpha = dim * 0.55f))
    }
}

/** 居中裁切铺满，比例不变。 */
private fun DrawScope.drawCenterCrop(image: ImageBitmap, alpha: Float) {
    if (image.width <= 0 || image.height <= 0) return
    val scale = max(size.width / image.width, size.height / image.height)
    val srcWidth = (size.width / scale).roundToInt().coerceIn(1, image.width)
    val srcHeight = (size.height / scale).roundToInt().coerceIn(1, image.height)
    drawImage(
        image = image,
        srcOffset = IntOffset((image.width - srcWidth) / 2, (image.height - srcHeight) / 2),
        srcSize = IntSize(srcWidth, srcHeight),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        alpha = alpha.coerceIn(0f, 1f)
    )
}

package com.tyust.course.ui.system

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.tyust.course.manager.WallpaperStyle
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 应用统一的流体壁纸绘制：底色 + 高光 + 多彩光斑 + 暗角 + 微纹理；
 * 用户上传了图片时改画图片 + 模糊 + 蒙版，微纹理照样补在最上面。
 * 主界面、登录页、引导页与课表设置共用，保证玻璃采样层次一致。
 *
 * 参数是解析后的 [WallpaperStyle] 而不是预设枚举——用户自定义的底色/图片要走同一条路径。
 *
 * @param microTexture 是否补那层高频微纹理。关掉液态玻璃时传 false：
 *                     没有折射要服务，留着它只剩噪点风险。
 */
fun DrawScope.drawWallpaperPattern(style: WallpaperStyle, microTexture: Boolean = true) {
    val image = style.image
    if (image != null) {
        drawImageWallpaper(image, style)
    } else {
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

    if (microTexture) drawWallpaperMicroTexture()
}

/**
 * 微纹理周期（**设备像素**，不是 dp）。
 *
 * 反对角线族的垂直间距是 `tile / √2 ≈ 8.5px`，白线与相邻黑线相距一半即约 4.2px——
 * 再密下去两组线的抗锯齿边就开始互相抵消，看起来是一层毛刺而不是纹理；
 * 再稀下去在 2x 屏上就能数出条纹了。12px 在 2x/3x 屏上分别约 6dp / 4dp 周期。
 *
 * 相对折射位移（选中透镜满档 18dp，3x 屏约 54px）是 6 个周期以上，足够看出弯折。
 */
private const val MicroTextureTilePx = 12

/**
 * 微纹理强度。**这是唯一的旋钮**：看着像噪点或摩尔纹就往下调，置 0 即整层关闭。
 */
private const val WallpaperMicroTextureAlpha = 0.055f

/**
 * 壁纸微纹理的平铺 tile。
 *
 * ## 为什么壁纸必须有高频内容
 *
 * 折射是**位移采样**：把背景按法线偏移一段距离再采回来。弯曲一片均匀颜色，采回来
 * 还是同一个颜色——所以在"底色 + 几个大半径径向渐变"这种极低频的壁纸上，
 * **任何折射强度都不可能显形**，玻璃只会退化成一层扁平磨砂。
 * （debug 包之所以看着正常，是因为它在采样层里多画了一张高对比斜线水印当标靶。）
 *
 * ## 为什么是等量的白线 + 黑线
 *
 * 两组线等宽等 α、错开半个周期，叠加后壁纸的**平均明度基本不变**——读起来是织物的
 * 微观质感，而不是把整张壁纸糊上一层灰雾或脏点。
 *
 * ## 它只对不模糊的玻璃层有效
 *
 * 底栏轨道那层是 `blur(10dp)` → `lens(8/12dp)`：30px 的模糊先把纹理抹平，
 * lens 再位移一个均匀场等于没动。收益全在 `enableBlur = false` 的层上——
 * 底栏选中胶囊、`liquidChip` 芯片、开关滑块、分段滑块（`interactive` 材质 blurDp = 0）。
 * 模糊层的玻璃感由 `drawGlassWall` 那套边缘光学承担。
 *
 * ## 为什么是 tile + shader 而不是每帧画线
 *
 * 壁纸层挂在 `layerBackdrop` 上，交互期间每帧都会因 backdrop 失效而重绘。
 * 满屏画几百条 `drawLine` 是每帧几百条绘制指令；缓存成平铺 tile 之后
 * 每帧只有一个 `drawRect`。反对角线在正方 tile 里天然无缝。
 */
private val microTextureBrush: ShaderBrush? by lazy {
    if (WallpaperMicroTextureAlpha <= 0.001f) return@lazy null
    val tile = MicroTextureTilePx
    val bitmap = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
    val lineAlpha = (WallpaperMicroTextureAlpha * 255f).roundToInt().coerceIn(0, 255)

    // 反对角线族 x + y = c。斜线端点取到 tile 外面，让 c 落在边界上的那条
    // 在相邻 tile 里接得上，平铺后是一条连续的长斜纹。
    fun stripes(color: Int, phase: Int) {
        paint.color = color
        var c = phase - tile
        while (c <= tile * 2) {
            canvas.drawLine(
                (c + tile * 2).toFloat(), (-tile * 2).toFloat(),
                (c - tile * 2).toFloat(), (tile * 2).toFloat(),
                paint
            )
            c += tile
        }
    }
    stripes(AndroidColor.argb(lineAlpha, 255, 255, 255), phase = 0)
    stripes(AndroidColor.argb(lineAlpha, 0, 0, 0), phase = tile / 2)

    ShaderBrush(
        ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated)
    )
}

private fun DrawScope.drawWallpaperMicroTexture() {
    drawRect(brush = microTextureBrush ?: return)
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

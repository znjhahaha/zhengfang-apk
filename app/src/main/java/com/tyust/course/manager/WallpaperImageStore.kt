package com.tyust.course.manager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 自定义图片壁纸的本地仓库。
 *
 * ## 为什么必须在选中的那一刻就把字节拷走
 *
 * 系统相册选择器（`PickVisualMedia`）返回的 URI 只带**临时授权**，
 * 进程重启后再去 `openInputStream` 必然抛 SecurityException。所以选中即导入：
 * 拷进 `context.filesDir`（App 私有目录，读写都不需要任何权限）。
 *
 * ## 为什么在导入时就归一化
 *
 * 取向（EXIF）、降采样、重编码全部在导入这一次做完，落盘的就是"可以直接画"的图。
 * 于是每次冷启动只剩两个 `decodeFile`，启动路径上没有 EXIF 解析、没有 Matrix 旋转。
 *
 * ## 为什么要存两张
 *
 * [SOFT_NAME] 是长边 [SoftLongEdge]px 并做过 **3-pass box blur（≈高斯）** 的模糊层。
 * 绘制时与原图做 alpha 交叉淡化，于是"模糊"滑块不需要 RenderEffect（31+）也不需要
 * RenderScript（已废弃），API 24 上一样连续可调。见 `WallpaperRenderer.drawWallpaperPattern`。
 * 曾用 72px 裸缩略图上采样冒充模糊——那是低分辨率放大，看起来像压缩画质而不是高斯。
 */
object WallpaperImageStore {
    private const val TAG = "WallpaperImageStore"
    private const val SHARP_NAME = "wallpaper_custom.jpg"
    private const val SOFT_NAME = "wallpaper_custom_soft.jpg"

    /**
     * 模糊层的长边。540px 经上采样到屏幕仍有轻微软化（正好是低模糊档需要的），
     * 而满档模糊由导入时的 box blur 负责，不再有色块/压缩感。
     */
    internal const val SoftLongEdge = 540

    /** box blur 半径相对 soft 长边的比例：540px 时半径 22px，3-pass 后 ≈ σ11 的高斯。 */
    internal const val SoftBlurRadiusRatio = 1f / 24f

    private fun sharpFile(context: Context) = File(context.filesDir, SHARP_NAME)

    private fun softFile(context: Context) = File(context.filesDir, SOFT_NAME)

    fun exists(context: Context): Boolean = sharpFile(context).exists()

    /**
     * 导入一张图片并归一化落盘。
     *
     * @param maxDimenPx 归一化后的长边上限，传屏幕长边即可。
     * @return 图片主色（ARGB）；任何一步失败都返回 null，且不留下半张残图。
     */
    fun import(context: Context, uri: Uri, maxDimenPx: Int): Int? {
        val resolver = context.contentResolver
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var scaled: Bitmap? = null
        var soft: Bitmap? = null
        return try {
            // 量尺寸：decodeStream 在 inJustDecodeBounds 下【按契约返回 null】、
            // 只把尺寸写进 bounds——它的返回值不能进 elvis，否则任何图都判定失败
            // （曾因 `openInputStream(...)?.use { decodeStream(...) } ?: return null`
            // 一路静默返回 null，任何图片都提示读取失败）。
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = resolver.openInputStream(uri) ?: run {
                Log.w(TAG, "打开图片流失败: $uri")
                return null
            }
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "图片尺寸解析失败(${bounds.outWidth}x${bounds.outHeight}): $uri")
                return null
            }

            val target = maxDimenPx.coerceIn(320, 4096)
            val longEdge = max(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longEdge / (sample * 2) >= target) sample *= 2

            // 相册给的流不可 seek，量尺寸和真解码必须各开一次；复用同一个流会解出 null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            decoded = resolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, options) }
            if (decoded == null) {
                Log.w(TAG, "图片解码失败: $uri")
                return null
            }

            val rotation = resolver.openInputStream(uri)?.use { readOrientation(it) } ?: NoTransform
            oriented = applyOrientation(decoded, rotation)
            scaled = downscale(oriented, target)

            if (!writeJpeg(scaled, sharpFile(context), quality = 90)) return null

            soft = buildSoftLayer(scaled)
            if (!writeJpeg(soft, softFile(context), quality = 80)) return null

            dominantColor(soft)
        } catch (t: Throwable) {
            // OutOfMemoryError 也在内：一张超大图不该把 App 带走
            Log.w(TAG, "导入壁纸图片失败", t)
            clear(context)
            null
        } finally {
            // scaled/oriented 可能与 decoded 是同一个对象（无需旋转/缩放时直接复用）
            listOf(soft, scaled, oriented, decoded)
                .distinct()
                .forEach { if (it != null && !it.isRecycled) it.recycle() }
        }
    }

    fun loadSharp(context: Context): Bitmap? = decodeFile(sharpFile(context))

    fun loadSoft(context: Context): Bitmap? = decodeFile(softFile(context))

    /**
     * 旧版本（72px 裸缩略图）的存量壁纸升级：从 sharp 文件按当前管道重生成模糊层。
     * @return 成功与否（失败时调用方下次启动再试）。
     */
    fun regenerateSoft(context: Context): Boolean {
        val sharp = decodeFile(sharpFile(context)) ?: return false
        return try {
            val soft = buildSoftLayer(sharp)
            val ok = writeJpeg(soft, softFile(context), quality = 80)
            if (!ok) runCatching { softFile(context).delete() }
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "重生成壁纸模糊层失败", t)
            false
        } finally {
            listOf(sharp).forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    /** 模糊层 = 缩到 [SoftLongEdge] + 3-pass box blur（≈高斯）。总是产出新位图，不动入参。 */
    private fun buildSoftLayer(source: Bitmap): Bitmap {
        val longEdge = max(source.width, source.height)
        val scale = SoftLongEdge.toFloat() / longEdge.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
        val blurred = applyBoxBlur(scaled)
        if (scaled !== source && !scaled.isRecycled) scaled.recycle()
        return blurred
    }

    private fun applyBoxBlur(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val radius = (max(w, h) * SoftBlurRadiusRatio).roundToInt().coerceAtLeast(1)
        val blurred = boxBlurPixels(pixels, w, h, radius)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(blurred, 0, w, 0, 0, w, h)
        return out
    }

    fun clear(context: Context) {
        runCatching { sharpFile(context).delete() }
        runCatching { softFile(context).delete() }
    }

    private fun decodeFile(file: File): Bitmap? {
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (t: Throwable) {
            Log.w(TAG, "读取壁纸图片失败: ${file.name}", t)
            null
        }
    }

    private fun writeJpeg(bitmap: Bitmap, file: File, quality: Int): Boolean = try {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "写入壁纸图片失败: ${file.name}", t)
        false
    }

    private fun downscale(source: Bitmap, target: Int): Bitmap {
        val longEdge = max(source.width, source.height)
        if (longEdge <= target) return source
        val ratio = target.toFloat() / longEdge.toFloat()
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).roundToInt().coerceAtLeast(1),
            (source.height * ratio).roundToInt().coerceAtLeast(1),
            true
        )
    }

    private data class Orientation(val degrees: Float, val mirrored: Boolean)

    private val NoTransform = Orientation(0f, false)

    private fun readOrientation(stream: java.io.InputStream): Orientation = try {
        when (
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> Orientation(90f, false)
            ExifInterface.ORIENTATION_ROTATE_180 -> Orientation(180f, false)
            ExifInterface.ORIENTATION_ROTATE_270 -> Orientation(270f, false)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Orientation(0f, true)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Orientation(180f, true)
            ExifInterface.ORIENTATION_TRANSPOSE -> Orientation(90f, true)
            ExifInterface.ORIENTATION_TRANSVERSE -> Orientation(270f, true)
            else -> NoTransform
        }
    } catch (t: Throwable) {
        Log.w(TAG, "读取 EXIF 取向失败，按原样使用", t)
        NoTransform
    }

    private fun applyOrientation(source: Bitmap, orientation: Orientation): Bitmap {
        if (orientation.degrees == 0f && !orientation.mirrored) return source
        val matrix = Matrix().apply {
            if (orientation.mirrored) postScale(-1f, 1f)
            if (orientation.degrees != 0f) postRotate(orientation.degrees)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** 主色 = 缩到 1×1 的那一个像素。位图到位前用它当底色，冷启动就不会闪白。 */
    private fun dominantColor(soft: Bitmap): Int {
        val single = Bitmap.createScaledBitmap(soft, 1, 1, true)
        val color = single.getPixel(0, 0)
        if (single != soft) single.recycle()
        return color
    }
}

/**
 * 3-pass box blur（水平/垂直交替三次），数学上等效于同 σ 的高斯模糊
 * （每 pass 的 box 宽 2r+1，三次叠卷积逼近高斯核；"Fast Gaussian Blur" 经典结论）。
 *
 * 边缘用钳位复制处理——均色输入不变、输出值域不越界 [min, max]。
 * 纯 IntArray 实现不依赖 Bitmap，JVM 单测可直测。返回新数组，不改入参。
 */
internal fun boxBlurPixels(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
    if (width <= 0 || height <= 0 || pixels.size != width * height) return pixels.copyOf()
    if (radius <= 0) return pixels.copyOf()
    var src = pixels.copyOf()
    var dst = IntArray(src.size)
    repeat(BOX_BLUR_PASSES) {
        boxBlurPass(src, dst, width, height, radius, horizontal = true)
        boxBlurPass(dst, src, width, height, radius, horizontal = false)
    }
    return src
}

private const val BOX_BLUR_PASSES = 3

private fun boxBlurPass(
    src: IntArray,
    dst: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    horizontal: Boolean
) {
    val outer = if (horizontal) height else width
    val inner = if (horizontal) width else height
    val window = 2 * radius + 1
    val halfWindow = window / 2
    for (o in 0 until outer) {
        val stride = if (horizontal) o * width else o
        var sumR = 0
        var sumG = 0
        var sumB = 0
        fun px(i: Int): Int = src[stride + i * (if (horizontal) 1 else width)]
        for (i in -radius..radius) {
            val p = px(i.coerceIn(0, inner - 1))
            sumR += (p shr 16) and 0xFF
            sumG += (p shr 8) and 0xFF
            sumB += p and 0xFF
        }
        for (i in 0 until inner) {
            dst[stride + i * (if (horizontal) 1 else width)] =
                (0xFF shl 24) or
                    (((sumR + halfWindow) / window and 0xFF) shl 16) or
                    (((sumG + halfWindow) / window and 0xFF) shl 8) or
                    ((sumB + halfWindow) / window and 0xFF)
            val add = (i + radius + 1).coerceIn(0, inner - 1)
            val sub = (i - radius).coerceIn(0, inner - 1)
            val pAdd = px(add)
            val pSub = px(sub)
            sumR += ((pAdd shr 16) and 0xFF) - ((pSub shr 16) and 0xFF)
            sumG += ((pAdd shr 8) and 0xFF) - ((pSub shr 8) and 0xFF)
            sumB += (pAdd and 0xFF) - (pSub and 0xFF)
        }
    }
}

/** Rec.709 相对亮度（0..1）。用于壁纸明暗判定与文字颜色自适应。 */
internal fun rec709Luminance(argb: Int): Float {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

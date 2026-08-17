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
 * [SOFT_NAME] 是长边约 [SoftLongEdge]px 的缩略图。绘制时把它拉满整屏就是天然的模糊，
 * 于是"模糊"滑块不需要 RenderEffect（31+）也不需要 RenderScript（已废弃），
 * API 24 上一样连续可调。见 `WallpaperRenderer.drawWallpaperPattern`。
 */
object WallpaperImageStore {
    private const val TAG = "WallpaperImageStore"
    private const val SHARP_NAME = "wallpaper_custom.jpg"
    private const val SOFT_NAME = "wallpaper_custom_soft.jpg"

    /** 模糊层缩略图的长边。再小会出现明显的色块台阶，再大就糊不动了。 */
    private const val SoftLongEdge = 72

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

            val softLongEdge = max(scaled.width, scaled.height)
            val softScale = SoftLongEdge.toFloat() / softLongEdge.toFloat()
            soft = Bitmap.createScaledBitmap(
                scaled,
                (scaled.width * softScale).roundToInt().coerceAtLeast(1),
                (scaled.height * softScale).roundToInt().coerceAtLeast(1),
                true
            )
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

package com.tyust.course.manager

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

internal const val WallpaperToneGridWidth = 16
internal const val WallpaperToneGridHeight = 32

internal data class WallpaperRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

internal data class WallpaperRegionAppearance(
    val foregroundArgb: Int,
    val surfaceArgb: Int,
    val surfaceAlpha: Float,
    val borderArgb: Int,
    val usesDarkForeground: Boolean,
    val isMixed: Boolean,
    val minimumContrast: Double
)

internal class WallpaperToneMap(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val gridWidth: Int,
    val gridHeight: Int,
    sharpArgb: IntArray,
    softArgb: IntArray
) {
    val sharpArgb: IntArray = sharpArgb.copyOf()
    val softArgb: IntArray = softArgb.copyOf()

    init {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(gridWidth > 0 && gridHeight > 0)
        require(this.sharpArgb.size == gridWidth * gridHeight)
        require(this.softArgb.size == gridWidth * gridHeight)
    }

    fun resolve(
        viewportWidth: Int,
        viewportHeight: Int,
        region: WallpaperRegion,
        blur: Float,
        dim: Float
    ): WallpaperRegionAppearance {
        val backgrounds = sampleRegion(
            viewportWidth = viewportWidth.coerceAtLeast(1),
            viewportHeight = viewportHeight.coerceAtLeast(1),
            region = region,
            blur = blur.coerceIn(0f, 1f),
            dim = dim.coerceIn(0f, 1f)
        )
        return resolveWallpaperAppearance(backgrounds)
    }

    fun encode(): String {
        val count = gridWidth * gridHeight
        val buffer = ByteBuffer.allocate(ToneMapHeaderBytes + count * 6)
        buffer.putInt(ToneMapMagic)
        buffer.putInt(ToneMapVersion)
        buffer.putInt(sourceWidth)
        buffer.putInt(sourceHeight)
        buffer.putInt(gridWidth)
        buffer.putInt(gridHeight)
        sharpArgb.forEach(buffer::putRgb)
        softArgb.forEach(buffer::putRgb)
        return buffer.array().toByteString().base64()
    }

    private fun sampleRegion(
        viewportWidth: Int,
        viewportHeight: Int,
        region: WallpaperRegion,
        blur: Float,
        dim: Float
    ): List<Int> {
        val scale = max(
            viewportWidth.toDouble() / sourceWidth.toDouble(),
            viewportHeight.toDouble() / sourceHeight.toDouble()
        )
        val visibleSourceWidth = viewportWidth / scale
        val visibleSourceHeight = viewportHeight / scale
        val sourceLeft = (sourceWidth - visibleSourceWidth) / 2.0
        val sourceTop = (sourceHeight - visibleSourceHeight) / 2.0

        fun sourceX(windowX: Double): Double =
            sourceLeft + windowX.coerceIn(0.0, viewportWidth.toDouble()) / scale
        fun sourceY(windowY: Double): Double =
            sourceTop + windowY.coerceIn(0.0, viewportHeight.toDouble()) / scale

        val left = sourceX(min(region.left, region.right).toDouble())
        val right = sourceX(max(region.left, region.right).toDouble())
        val top = sourceY(min(region.top, region.bottom).toDouble())
        val bottom = sourceY(max(region.top, region.bottom).toDouble())

        val indices = linkedSetOf<Int>()
        val sampleXs = doubleArrayOf(left, (left + right) / 2.0, right)
        val sampleYs = doubleArrayOf(top, (top + bottom) / 2.0, bottom)
        for (y in sampleYs) for (x in sampleXs) {
            indices += gridIndex(x, y)
        }

        for (row in 0 until gridHeight) {
            val centerY = (row + 0.5) * sourceHeight / gridHeight
            if (centerY < top || centerY > bottom) continue
            for (column in 0 until gridWidth) {
                val centerX = (column + 0.5) * sourceWidth / gridWidth
                if (centerX in left..right) indices += row * gridWidth + column
            }
        }

        val dimFactor = 1f - dim * WallpaperDimMaximumAlpha
        return indices.map { index ->
            blendAndDim(sharpArgb[index], softArgb[index], blur, dimFactor)
        }
    }

    private fun gridIndex(sourceX: Double, sourceY: Double): Int {
        val column = (sourceX / sourceWidth * gridWidth).toInt().coerceIn(0, gridWidth - 1)
        val row = (sourceY / sourceHeight * gridHeight).toInt().coerceIn(0, gridHeight - 1)
        return row * gridWidth + column
    }

    override fun equals(other: Any?): Boolean =
        other is WallpaperToneMap &&
            sourceWidth == other.sourceWidth &&
            sourceHeight == other.sourceHeight &&
            gridWidth == other.gridWidth &&
            gridHeight == other.gridHeight &&
            sharpArgb.contentEquals(other.sharpArgb) &&
            softArgb.contentEquals(other.softArgb)

    override fun hashCode(): Int {
        var result = sourceWidth
        result = 31 * result + sourceHeight
        result = 31 * result + gridWidth
        result = 31 * result + gridHeight
        result = 31 * result + sharpArgb.contentHashCode()
        result = 31 * result + softArgb.contentHashCode()
        return result
    }

    companion object {
        fun uniform(argb: Int): WallpaperToneMap = WallpaperToneMap(
            sourceWidth = 1,
            sourceHeight = 1,
            gridWidth = 1,
            gridHeight = 1,
            sharpArgb = intArrayOf(argb),
            softArgb = intArrayOf(argb)
        )

        fun decode(encoded: String?): WallpaperToneMap? = runCatching {
            val bytes = encoded?.decodeBase64()?.toByteArray() ?: return null
            if (bytes.size < ToneMapHeaderBytes) return null
            val buffer = ByteBuffer.wrap(bytes)
            if (buffer.int != ToneMapMagic || buffer.int != ToneMapVersion) return null
            val sourceWidth = buffer.int
            val sourceHeight = buffer.int
            val gridWidth = buffer.int
            val gridHeight = buffer.int
            val count = Math.multiplyExact(gridWidth, gridHeight)
            if (sourceWidth <= 0 || sourceHeight <= 0 || count <= 0 || count > 4096) return null
            if (bytes.size != ToneMapHeaderBytes + count * 6) return null
            val sharp = IntArray(count) { buffer.readRgb() }
            val soft = IntArray(count) { buffer.readRgb() }
            WallpaperToneMap(sourceWidth, sourceHeight, gridWidth, gridHeight, sharp, soft)
        }.getOrNull()
    }
}

private const val ToneMapMagic = 0x57544D50
private const val ToneMapVersion = 1
private const val ToneMapHeaderBytes = 24
private const val WallpaperDimMaximumAlpha = 0.55f
private const val DarkForeground = 0xFF1C1C1E.toInt()
private const val LightForeground = 0xFFF8F8FC.toInt()
private const val WhiteSurface = 0xFFFFFFFF.toInt()
private const val BlackSurface = 0xFF000000.toInt()
private const val NormalTextContrast = 4.5
private const val BaseSurfaceAlpha = 0.24f
private const val MaximumSurfaceAlpha = 0.52f

private fun ByteBuffer.putRgb(argb: Int) {
    put(((argb shr 16) and 0xFF).toByte())
    put(((argb shr 8) and 0xFF).toByte())
    put((argb and 0xFF).toByte())
}

private fun ByteBuffer.readRgb(): Int =
    (0xFF shl 24) or
        ((get().toInt() and 0xFF) shl 16) or
        ((get().toInt() and 0xFF) shl 8) or
        (get().toInt() and 0xFF)

private fun blendAndDim(sharp: Int, soft: Int, blur: Float, dimFactor: Float): Int {
    fun channel(shift: Int): Int {
        val sharpChannel = (sharp shr shift) and 0xFF
        val softChannel = (soft shr shift) and 0xFF
        return ((sharpChannel + (softChannel - sharpChannel) * blur) * dimFactor)
            .toInt()
            .coerceIn(0, 255)
    }
    return (0xFF shl 24) or
        (channel(16) shl 16) or
        (channel(8) shl 8) or
        channel(0)
}

private fun resolveWallpaperAppearance(backgrounds: List<Int>): WallpaperRegionAppearance {
    val samples = backgrounds.ifEmpty { listOf(WhiteSurface) }
    val luminances = samples.map(::relativeLuminance)
    val mixed = (luminances.maxOrNull()!! - luminances.minOrNull()!!) > 0.24

    val darkDirect = minimumContrast(DarkForeground, WhiteSurface, BaseSurfaceAlpha, samples)
    val lightDirect = minimumContrast(LightForeground, BlackSurface, BaseSurfaceAlpha, samples)
    val darkMeets = darkDirect >= NormalTextContrast
    val lightMeets = lightDirect >= NormalTextContrast

    if (darkMeets || lightMeets) {
        val useDark = when {
            darkMeets && !lightMeets -> true
            lightMeets && !darkMeets -> false
            else -> darkDirect >= lightDirect
        }
        val foreground = if (useDark) DarkForeground else LightForeground
        val surface = if (useDark) WhiteSurface else BlackSurface
        return WallpaperRegionAppearance(
            foregroundArgb = foreground,
            surfaceArgb = surface,
            surfaceAlpha = BaseSurfaceAlpha,
            borderArgb = foreground,
            usesDarkForeground = useDark,
            isMixed = mixed,
            minimumContrast = if (useDark) darkDirect else lightDirect
        )
    }

    val darkAlpha = minimumOverlayAlpha(DarkForeground, WhiteSurface, samples)
    val lightAlpha = minimumOverlayAlpha(LightForeground, BlackSurface, samples)
    val useDark = when {
        darkAlpha != null && lightAlpha == null -> true
        lightAlpha != null && darkAlpha == null -> false
        darkAlpha != null && lightAlpha != null -> darkAlpha <= lightAlpha
        else -> minimumContrast(DarkForeground, WhiteSurface, MaximumSurfaceAlpha, samples) >=
            minimumContrast(LightForeground, BlackSurface, MaximumSurfaceAlpha, samples)
    }
    val foreground = if (useDark) DarkForeground else LightForeground
    val surface = if (useDark) WhiteSurface else BlackSurface
    val alpha = (if (useDark) darkAlpha else lightAlpha) ?: MaximumSurfaceAlpha
    return WallpaperRegionAppearance(
        foregroundArgb = foreground,
        surfaceArgb = surface,
        surfaceAlpha = alpha,
        borderArgb = foreground,
        usesDarkForeground = useDark,
        isMixed = true,
        minimumContrast = minimumContrast(foreground, surface, alpha, samples)
    )
}

private fun minimumOverlayAlpha(foreground: Int, surface: Int, backgrounds: List<Int>): Float? {
    if (minimumContrast(foreground, surface, MaximumSurfaceAlpha, backgrounds) < NormalTextContrast) {
        return null
    }
    var low = BaseSurfaceAlpha
    var high = MaximumSurfaceAlpha
    repeat(12) {
        val middle = (low + high) / 2f
        if (minimumContrast(foreground, surface, middle, backgrounds) >= NormalTextContrast) {
            high = middle
        } else {
            low = middle
        }
    }
    return high
}

private fun minimumContrast(
    foreground: Int,
    surface: Int,
    surfaceAlpha: Float,
    backgrounds: List<Int>
): Double = backgrounds.minOf { background ->
    contrastRatio(foreground, composite(surface, background, surfaceAlpha))
}

private fun composite(foreground: Int, background: Int, alpha: Float): Int {
    fun channel(shift: Int): Int {
        val front = (foreground shr shift) and 0xFF
        val back = (background shr shift) and 0xFF
        return (front * alpha + back * (1f - alpha)).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

internal fun contrastRatio(first: Int, second: Int): Double {
    val firstLuminance = relativeLuminance(first)
    val secondLuminance = relativeLuminance(second)
    return (max(firstLuminance, secondLuminance) + 0.05) /
        (min(firstLuminance, secondLuminance) + 0.05)
}

internal fun buildWallpaperToneGrid(
    pixels: IntArray,
    sourceWidth: Int,
    sourceHeight: Int,
    gridWidth: Int = WallpaperToneGridWidth,
    gridHeight: Int = WallpaperToneGridHeight
): IntArray {
    require(sourceWidth > 0 && sourceHeight > 0)
    require(pixels.size == sourceWidth * sourceHeight)
    require(gridWidth > 0 && gridHeight > 0)
    return IntArray(gridWidth * gridHeight) { index ->
        val column = index % gridWidth
        val row = index / gridWidth
        val left = (column * sourceWidth / gridWidth).coerceIn(0, sourceWidth - 1)
        val right = (((column + 1) * sourceWidth + gridWidth - 1) / gridWidth)
            .coerceIn(left + 1, sourceWidth)
        val top = (row * sourceHeight / gridHeight).coerceIn(0, sourceHeight - 1)
        val bottom = (((row + 1) * sourceHeight + gridHeight - 1) / gridHeight)
            .coerceIn(top + 1, sourceHeight)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        for (y in top until bottom) {
            for (x in left until right) {
                val pixel = pixels[y * sourceWidth + x]
                red += (pixel shr 16) and 0xFF
                green += (pixel shr 8) and 0xFF
                blue += pixel and 0xFF
                count++
            }
        }
        (0xFF shl 24) or
            (((red / count).toInt() and 0xFF) shl 16) or
            (((green / count).toInt() and 0xFF) shl 8) or
            ((blue / count).toInt() and 0xFF)
    }
}

private fun relativeLuminance(argb: Int): Double {
    fun linear(channel: Int): Double {
        val value = channel / 255.0
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear((argb shr 16) and 0xFF) +
        0.7152 * linear((argb shr 8) and 0xFF) +
        0.0722 * linear(argb and 0xFF)
}

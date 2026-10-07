@file:android.annotation.SuppressLint("NewApi")

package com.tyust.course.ui.system.glass

import android.graphics.Bitmap
import android.graphics.Picture
import android.graphics.RenderNode
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.CanvasHolder
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

/** Coordinates belong to the recorded pixels, never to a later layout of the anchor. */
internal data class GlassLensCaptureGeometry(
    val size: IntSize,
    val origin: Offset,
    val xAxis: Offset,
    val yAxis: Offset
) {
    fun fitPixelBudget(maxPixels: Int): GlassLensCaptureGeometry {
        val area = size.width.toLong() * size.height
        if (area <= maxPixels.coerceAtLeast(1) || area <= 0L) return this
        val factor = kotlin.math.sqrt(maxPixels.coerceAtLeast(1).toDouble() / area)
        val reduced = IntSize((size.width * factor).toInt().coerceAtLeast(1),
            (size.height * factor).toInt().coerceAtLeast(1))
        return copy(size = reduced, xAxis = xAxis * (size.width.toFloat() / reduced.width),
            yAxis = yAxis * (size.height.toFloat() / reduced.height))
    }

    fun windowToLocal(point: Offset): Offset {
        val delta = point - origin
        val determinant = xAxis.x * yAxis.y - xAxis.y * yAxis.x
        if (!determinant.isFinite() || kotlin.math.abs(determinant) < 0.00001f) return Offset.Unspecified
        return Offset((delta.x * yAxis.y - delta.y * yAxis.x) / determinant,
            (delta.y * xAxis.x - delta.x * xAxis.y) / determinant)
    }

    companion object {
        fun from(coordinates: LayoutCoordinates): GlassLensCaptureGeometry {
            val origin = coordinates.localToWindow(Offset.Zero)
            return GlassLensCaptureGeometry(coordinates.size, origin,
                coordinates.localToWindow(Offset(1f, 0f)) - origin,
                coordinates.localToWindow(Offset(0f, 1f)) - origin)
        }
    }
}

/** A frozen recording and its coordinate system travel together through the pipeline. */
internal data class GlassLensCaptureFrame(
    val generation: Int,
    val geometry: GlassLensCaptureGeometry,
    val node: RenderNode,
    val queuedAtNanos: Long = 0L,
    val sourceRevision: Int = 0,
    val overlayRevision: Int = 0
) {
    val identity get() = GlassLensFrameIdentity(geometry, overlayRevision)
}

/** Freezes glyph draw commands while their Compose draw nodes are still attached. */
internal class GlassLensContentSnapshot {
    var coordinates: LayoutCoordinates? = null
    private var picture: Picture? = null

    fun record(scope: ContentDrawScope, tint: ColorFilter) = with(scope) {
        val next = Picture()
        val canvas = Canvas(next.beginRecording(size.width.toInt(), size.height.toInt()))
        try {
            draw(this, layoutDirection, canvas, size, graphicsLayer = null) {
                canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { colorFilter = tint })
                try { scope.drawContent() } finally { canvas.restore() }
            }
        } finally { next.endRecording() }
        picture = next
    }

    fun draw(scope: DrawScope, destination: LayoutCoordinates? = null): Unit = with(scope) {
        val content = picture ?: return
        val offset = if (destination == null) Offset.Zero else {
            val source = coordinates?.takeIf { it.isAttached } ?: return
            source.localPositionOf(destination)
        }
        withTransform({ translate(-offset.x, -offset.y) }) {
            drawContext.canvas.nativeCanvas.drawPicture(content)
        }
    }
}

/** Optional instrumentation observers; production never retains source pixels here. */
internal object GlassLensCaptureObserver {
    var onGlyphRevision: ((Int) -> Unit)? = null
    var onCaptured: ((String, GlassLensCaptureFrame, Bitmap) -> Unit)? = null
    var onSampled: ((String, IntSize, Offset, GlassLensSourceAxes, Int) -> Unit)? = null
    @Volatile var onTiming: ((String, String, Long) -> Unit)? = null
}

internal object GlassLensCaptureDispatcher {
    private val handler by lazy { Handler(HandlerThread("GlassLensCapture").apply { start() }.looper) }
    private val overlayHandler by lazy { Handler(HandlerThread("GlassLensLabels").apply { start() }.looper) }
    fun post(block: () -> Unit) { handler.post(block) }
    fun postOverlay(block: () -> Unit) { overlayHandler.post(block) }
}

/** Record on the UI thread; rasterization and hardware readback run on the capture thread. */
internal fun recordGlassLensSource(
    name: String,
    size: IntSize,
    density: Density,
    logicalSize: IntSize = size,
    draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit
): RenderNode = RenderNode(name).apply {
    setPosition(0, 0, size.width, size.height)
    val canvas = beginRecording()
    try {
        canvas.scale(size.width.toFloat() / logicalSize.width, size.height.toFloat() / logicalSize.height)
        CanvasHolder().drawInto(canvas) {
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, this,
                androidx.compose.ui.geometry.Size(logicalSize.width.toFloat(), logicalSize.height.toFloat()), draw)
        }
    } finally { endRecording() }
}

/** An immutable vector overlay can be rasterized without sharing HWUI display lists. */
internal fun recordGlassLensPicture(
    size: IntSize,
    density: Density,
    logicalSize: IntSize = size,
    draw: DrawScope.() -> Unit
): Picture = Picture().apply {
    val canvas = beginRecording(size.width, size.height)
    try {
        canvas.scale(size.width.toFloat() / logicalSize.width, size.height.toFloat() / logicalSize.height)
        CanvasHolder().drawInto(canvas) {
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, this,
                androidx.compose.ui.geometry.Size(logicalSize.width.toFloat(), logicalSize.height.toFloat()), draw)
        }
    } finally { endRecording() }
}

internal fun readGlassLensPixels(picture: Picture, size: IntSize, tag: String): Bitmap {
    val started = System.nanoTime()
    return Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).also {
        android.graphics.Canvas(it).drawPicture(picture)
        GlassLensCaptureObserver.onTiming?.invoke(tag, "rasterize", System.nanoTime() - started)
    }
}

internal fun readGlassLensPixels(node: RenderNode, size: IntSize, tag: String = "anon"): Bitmap? {
    val picture = object : Picture() {
        override fun beginRecording(width: Int, height: Int) = android.graphics.Canvas()
        override fun endRecording() = Unit
        override fun getWidth() = size.width
        override fun getHeight() = size.height
        override fun requiresHardwareAcceleration() = true
        override fun draw(canvas: android.graphics.Canvas) { canvas.drawRenderNode(node) }
    }
    val started = System.nanoTime()
    val hardware = Bitmap.createBitmap(picture)
    GlassLensCaptureObserver.onTiming?.invoke(tag, "rasterize", System.nanoTime() - started)
    return if (hardware.config == Bitmap.Config.HARDWARE) {
        val copyStarted = System.nanoTime()
        try { hardware.copy(Bitmap.Config.ARGB_8888, false) } finally {
            GlassLensCaptureObserver.onTiming?.invoke(tag, "cpu-copy", System.nanoTime() - copyStarted)
            hardware.recycle()
        }
    } else hardware
}

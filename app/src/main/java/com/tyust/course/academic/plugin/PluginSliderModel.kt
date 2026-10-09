package com.tyust.course.academic.plugin

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONObject
import java.nio.FloatBuffer
import kotlin.math.abs

/** Local inference for the model's 320x160, full-height strip puzzle format. */
internal object PluginSliderModel {
    private const val MODEL = "plugin-models/slider-gap.ort"
    private const val DIGEST = "ce9623e1f58b46bc5e7485dffc6449d36255af5e9e2d877bb4db94e8c8b21ee7"
    private const val SIDE = 640
    private var session: OrtSession? = null
    private var unavailable = false

    private fun session(app: Context): OrtSession? {
        if (unavailable) return null
        session?.let { return it }
        return try {
            val bytes = app.assets.open(MODEL).use { it.readBytes() }
            check(PluginJson.sha256(bytes) == DIGEST)
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                OrtEnvironment.getEnvironment().createSession(bytes, options).also { session = it }
            }
        } catch (_: Exception) {
            unavailable = true
            null
        } catch (_: LinkageError) {
            unavailable = true
            null
        }
    }

    // Serialize inference so simultaneous manual and background tasks have bounded memory use.
    @Synchronized fun match(app: Context, background: PluginImageMatcher.Image, piece: PluginImageMatcher.Image, expectedY: Double?): JSONObject? {
        if (background.width != 320 || background.height != 160 || piece.width != 56 || piece.height != 160 ||
            expectedY != null && expectedY != 0.0) return null
        val model = session(app) ?: return null
        return try {
            val bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(SIDE * SIDE)
            try {
                Canvas(bitmap).drawBitmap(background.bitmap, null, RectF(0f, 0f, SIDE.toFloat(), SIDE / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
                bitmap.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
            } finally { bitmap.recycle() }
            val plane = pixels.size
            val channels = FloatArray(plane * 3)
            pixels.forEachIndexed { index, color ->
                channels[index] = (color ushr 16 and 255) / 255f
                channels[plane + index] = (color ushr 8 and 255) / 255f
                channels[plane * 2 + index] = (color and 255) / 255f
            }
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(channels), longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { input ->
                model.run(mapOf("images" to input)).use { output ->
                    @Suppress("UNCHECKED_CAST")
                    val rows = (output[0].value as Array<Array<FloatArray>>)[0]
                    check(rows.size == 5 && rows.all { it.size == rows[0].size })
                    val candidates = rows[4].indices.mapNotNull { index ->
                        val confidence = rows[4][index].toDouble()
                        val width = rows[2][index] / 2.0
                        val height = rows[3][index] / 2.0
                        // The model detects the gap contour; the strip includes a 4px left margin.
                        val x = (rows[0][index] - rows[2][index] / 2.0) / 2.0 - 4.0
                        val y = (rows[1][index] - rows[3][index] / 2.0) / 2.0
                        if (!confidence.isFinite() || confidence < 0.25 || !x.isFinite() || x !in 0.0..264.0 ||
                            width !in 20.0..70.0 || height !in 20.0..70.0 || y < 0 || y + height > 160) null
                        else Detection(x, y, width, height, confidence)
                    }.sortedByDescending { it.confidence }
                    val best = candidates.firstOrNull()
                    val competing = best?.let { first -> candidates.drop(1).firstOrNull {
                        abs(it.x - first.x) > first.width / 2 || abs(it.y - first.y) > first.height / 2
                    } }
                    val confident = best != null && best.confidence >= 0.70 &&
                        (competing == null || best.confidence - competing.confidence >= 0.12)
                    JSONObject().put("x", best?.x ?: 0.0).put("y", 0).put("width", 320).put("height", 160)
                        .put("confidence", best?.confidence ?: 0.0).put("ambiguous", !confident)
                }
            }
        } catch (_: Exception) { null }
    }

    private data class Detection(val x: Double, val y: Double, val width: Double, val height: Double, val confidence: Double)
}

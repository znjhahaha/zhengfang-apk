package com.tyust.course.ui.system.glass

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntSize

/** The actual Text layout, not a second hidden Row or a recording of mutable child layers. */
internal class SegmentLabelSource(private val count: Int) {
    private data class Glyph(val layout: TextLayoutResult, val offset: Offset)
    private val glyphs = mutableMapOf<Int, Glyph>()
    var size: IntSize = IntSize.Zero
        private set
    var revision by mutableIntStateOf(0)
        private set
    val ready get() = size.width > 0 && size.height > 0 && glyphs.size == count
    fun resize(value: IntSize) {
        if (size != value) { size = value; glyphs.clear(); revision++ }
    }
    fun update(index: Int, layout: TextLayoutResult, offset: Offset) {
        if (!offset.x.isFinite() || !offset.y.isFinite()) return
        val next = Glyph(layout, offset)
        if (glyphs[index] != next) { glyphs[index] = next; revision++ }
    }
    fun draw(scope: DrawScope, color: Color) = with(scope) {
        revision // Draw observation invalidates the source when glyphs/layout change.
        if (!ready) return@with
        glyphs.values.toList().forEach { glyph ->
            // A small optical magnification uses the real glyph layout and its own centre.
            // It is baked once per layout, so dragging does not rasterize fonts every frame.
            val centre = glyph.offset + Offset(glyph.layout.size.width / 2f, glyph.layout.size.height / 2f)
            clipRect(0f, 0f, size.width.toFloat(), size.height.toFloat()) {
                scale(1.06f, 1.06f, centre) { drawText(glyph.layout, color = color, topLeft = glyph.offset) }
            }
        }
    }
}

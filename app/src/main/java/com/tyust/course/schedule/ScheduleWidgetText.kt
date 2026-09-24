package com.tyust.course.schedule

import android.content.Context
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class ScheduleWidgetText(
    val text: String,
    val size: Float,
    val bold: Boolean = false,
    val margin: Float = 0f,
    val singleLine: Boolean = false
)

/** Measure the same wrapping, font metrics and nonlinear SP scaling as the host TextViews. */
internal class ScheduleWidgetTextFitter(private val context: Context, width: Float) {
    private val metrics = context.resources.displayMetrics
    private val width = (width - 2).toInt().coerceAtLeast(1)

    fun dp(value: Float): Int = (value * metrics.density).roundToInt()

    private fun layout(field: ScheduleWidgetText, scale: Float): StaticLayout {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, field.size * scale, metrics)
            typeface = if (field.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        return StaticLayout.Builder.obtain(field.text, 0, field.text.length, paint, width)
            .setIncludePad(false)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .apply { if (Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(true) }
            .build()
    }

    fun height(fields: List<ScheduleWidgetText>, scale: Float = 1f): Int = fields.filter { it.text.isNotEmpty() }
        .sumOf { layout(it, scale).height + dp(it.margin) + 1 }

    fun fits(fields: List<ScheduleWidgetText>, available: Int, scale: Float = 1f): Boolean {
        var height = 0
        for (field in fields.filter { it.text.isNotEmpty() }) {
            val layout = layout(field, scale)
            height += layout.height + dp(field.margin) + 1
            if (height > available || (field.singleLine && layout.lineCount > 1)) return false
            for (line in 0 until layout.lineCount) if (ceil(layout.getLineWidth(line)) > width) return false
        }
        return true
    }

    fun scale(fields: List<ScheduleWidgetText>, available: Int): Float {
        if (fits(fields, available)) return 1f
        var low = 0.01f
        var high = 1f
        repeat(14) {
            val mid = (low + high) / 2
            if (fits(fields, available, mid)) low = mid else high = mid
        }
        return low
    }
}

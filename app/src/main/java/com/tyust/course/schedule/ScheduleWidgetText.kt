package com.tyust.course.schedule

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import com.tyust.course.R
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

    private fun measure(field: ScheduleWidgetText, scale: Float): TextView {
        // Match RemoteViews' actual TextView metrics, including CJK fallback line spacing.
        // Measure all lines even for a one-line field so fits() can reject truncation.
        return TextView(context, null, 0, R.style.ScheduleWidgetFullText).apply {
            text = field.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, field.size * scale)
            typeface = if (field.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            measure(View.MeasureSpec.makeMeasureSpec(this@ScheduleWidgetTextFitter.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        }
    }

    fun height(fields: List<ScheduleWidgetText>, scale: Float = 1f): Int = fields.filter { it.text.isNotEmpty() }
        .sumOf { measure(it, scale).measuredHeight + dp(it.margin) + 1 }

    fun fits(fields: List<ScheduleWidgetText>, available: Int, scale: Float = 1f): Boolean {
        var height = 0
        for (field in fields.filter { it.text.isNotEmpty() }) {
            val label = measure(field, scale)
            val layout = label.layout ?: return false
            height += label.measuredHeight + dp(field.margin) + 1
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

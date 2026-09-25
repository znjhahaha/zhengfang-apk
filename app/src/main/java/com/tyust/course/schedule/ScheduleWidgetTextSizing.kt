package com.tyust.course.schedule

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import com.tyust.course.R

/** Measure the complete fields at the launcher's size before allocating optional widget chrome. */
internal class ScheduleWidgetTextSizing(private val context: Context) {
    fun px(dp: Float) = dp * context.resources.displayMetrics.density
    fun textHeight(text: String, size: Float, width: Float, bold: Boolean = false): Float {
        // Use the same TextView style as RemoteViews. StaticLayout's primary-font
        // metrics miss the extra line spacing of CJK fallback fonts on Android.
        val label = TextView(context, null, 0, R.style.ScheduleWidgetFullText).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        }
        label.measure(View.MeasureSpec.makeMeasureSpec(width.toInt().coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        return label.measuredHeight.toFloat()
    }

    data class CardPlan(val two: Boolean, val nameSize: Float, val roomSize: Float, val timeSize: Float,
                        val header: Boolean, val status: Boolean, val footer: Boolean, val time: Boolean = true)

    fun cards(state: ScheduleWidgetState, width: Int, height: Int, scale: Float, style: ScheduleWidgetStyle, verticalPadding: Int): CardPlan {
        val available = px((height - verticalPadding * 2).toFloat())
        val preferTwo = style == ScheduleWidgetStyle.Double && state.secondary != null && width >= 250 && height >= 110 * scale
        fun plan(two: Boolean, nameSize: Float, roomSize: Float, timeSize: Float, showTime: Boolean = true): CardPlan? {
            val column = px(if (two) (width - 16 - 21) / 2f else width - 16f)
            val items = listOfNotNull(state.primary, state.secondary.takeIf { two })
            val core = items.maxOfOrNull { item ->
                textHeight(item.name, nameSize, column, true) + textHeight(item.location, roomSize, column) + px(2f) +
                    if (showTime) textHeight(listOf(item.dateLabel, item.time).filter(String::isNotBlank).joinToString(" "), timeSize, column) + px(2f) else 0f
            } ?: 0f
            // Leave a small rounding allowance for the launcher's TextView metrics.
            var remaining = available - core - px(2f)
            if (remaining < 0) return null
            fun reserve(wanted: Boolean, size: Float): Boolean = (wanted && remaining >= size).also { if (it) remaining -= size }
            val status = reserve(two || height >= 128 * scale, textHeight("正在上课", 11f, column) + px(2f))
            val header = reserve(height >= 94 * scale, textHeight(state.heading, 12f, px(width - 16f), true) + px(2f))
            val footer = reserve(height >= 224 * scale, textHeight(state.summary, 11f, px(width - 16f)) + px(6f))
            return CardPlan(two, nameSize, roomSize, timeSize, header, status, footer, showTime)
        }
        val singleTime = if (style == ScheduleWidgetStyle.Single && height >= 180 * scale) 23f
            else if (style == ScheduleWidgetStyle.Single && height >= 128 * scale) 18f else 11f
        if (preferTwo) plan(true, 15f, 11f, 11f)?.let { return it }
        val singleName = if (height < 128 * scale) 14f else 16f
        plan(false, singleName, 11f, singleTime)?.let { return it }
        plan(false, singleName, 11f, 11f)?.let { return it }
        for (nameSize in singleName.toInt() downTo 11) {
            plan(false, nameSize.toFloat(), 10f, 10f)?.let { return it }
        }
        // At unusually large font scales, retain the complete course and room before the time.
        plan(false, 11f, 10f, 10f, false)?.let { return it }
        // Scale oversized system fonts only as far as the normal readable widget minimum.
        val minimum = 10f / scale
        var size = 10f
        while (size >= minimum) {
            plan(false, size, size, size, false)?.let { return it }
            size -= .5f
        }
        return CardPlan(false, minimum, minimum, minimum, false, false, false, false)
    }
}

package com.tyust.course.schedule

import android.app.PendingIntent
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.widget.RemoteViews
import com.tyust.course.R
import com.tyust.course.manager.AppThemeCoordinator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ScheduleWidgetAction { Today, Course, Login, Sync, Calendar }

internal data class ScheduleWidgetCourse(
    val occurrence: ScheduleOccurrence,
    val status: String,
    val name: String,
    val location: String,
    val time: String,
    val dateLabel: String
) {
    val course get() = occurrence.course
}

/** Structured fields shared by every widget size; prediction never guesses unknown teaching weeks. */
internal data class ScheduleWidgetState(
    val snapshot: ScheduleSnapshot?, val agenda: ScheduleAgenda?, val now: Long,
    val heading: String, val date: String,
    val primary: ScheduleWidgetCourse?, val secondary: ScheduleWidgetCourse?,
    val message: String?, val actionLabel: String, val action: ScheduleWidgetAction, val summary: String
) {
    val title get() = primary?.name ?: message.orEmpty()
    companion object {
        fun from(snapshot: ScheduleSnapshot?, now: Long, zone: TimeZone = TimeZone.getDefault()): ScheduleWidgetState {
            fun format(pattern: String, time: Long) = SimpleDateFormat(pattern, Locale.CHINA).apply { timeZone = zone }.format(Date(time))
            val date = format("M月d日 E", now)
            fun empty(message: String, label: String, action: ScheduleWidgetAction, agenda: ScheduleAgenda? = null) =
                ScheduleWidgetState(snapshot, agenda, now, "今日课表", date, null, null, message, label, action, "")
            if (snapshot == null) return empty("登录后查看课表", "去登录", ScheduleWidgetAction.Login)
            val agenda = ScheduleAgenda.calculate(snapshot.courses, snapshot.timeBase, now, zone)
            if (!snapshot.hasCache && snapshot.courses.isEmpty()) return empty("还没有本地课表", "同步课表", ScheduleWidgetAction.Sync, agenda)
            if (agenda.needsCalendar) return empty("请设置开学日期", "去设置", ScheduleWidgetAction.Calendar, agenda)
            val current = agenda.current.firstOrNull()
            val primary = current ?: agenda.upcoming.firstOrNull()
            if (primary == null) {
                if (snapshot.courses.any { !ScheduleWeeks.parse(it.weeks).valid })
                    return empty("课程周次待核对", "查看课表", ScheduleWidgetAction.Today, agenda)
                val hasMissingTime = snapshot.courses.any { snapshot.timeBase.periodStarts[it.startPeriod].isNullOrBlank() ||
                    snapshot.timeBase.periodEnds[it.endPeriod].isNullOrBlank() }
                if (hasMissingTime) return empty("请补全节次时间", "去设置", ScheduleWidgetAction.Calendar, agenda)
                return empty(if (agenda.today.isEmpty()) "今天没有课程" else "今日课程已结束", "查看课表", ScheduleWidgetAction.Today, agenda)
            }
            val tomorrow = Calendar.getInstance(zone).apply { timeInMillis = now; add(Calendar.DATE, 1) }.timeInMillis
            fun row(item: ScheduleOccurrence, status: String): ScheduleWidgetCourse {
                val dateLabel = when (format("yyyy-MM-dd", item.startsAt)) {
                    format("yyyy-MM-dd", now) -> ""
                    format("yyyy-MM-dd", tomorrow) -> "明天"
                    else -> format("M/d", item.startsAt)
                }
                return ScheduleWidgetCourse(item, status, item.course.name, item.course.location.ifBlank { "教室待定" },
                    "${format("HH:mm", item.startsAt)}–${format("HH:mm", item.endsAt)}", dateLabel)
            }
            val secondary = if (current != null) agenda.upcoming.firstOrNull() else agenda.upcoming.getOrNull(1)
            val heading = when {
                agenda.today.isEmpty() -> "今天无课"
                agenda.remaining(now) == 0 -> "今日已结束"
                else -> "今日课表"
            }
            return ScheduleWidgetState(snapshot, agenda, now, heading, date,
                row(primary, if (current != null) "正在上课" else "下一节"),
                secondary?.let { row(it, if (current != null) "下一节" else "随后") },
                null, "查看课表", ScheduleWidgetAction.Today,
                "今日 ${agenda.today.size} 堂 · 还剩 ${agenda.remaining(now)} 堂")
        }
    }
}

internal object ScheduleWidgetRenderer {
    fun views(context: Context, state: ScheduleWidgetState, width: Int = 280, height: Int = 160,
              style: ScheduleWidgetStyle = ScheduleWidgetStyle.Double): RemoteViews {
        val config = AppThemeCoordinator.wrapContext(context).resources.configuration
        val dark = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val primaryColor = if (dark) 0xFFF5F5FA.toInt() else 0xFF1D2433.toInt()
        val secondaryColor = if (dark) 0xFFBFC7D9.toInt() else 0xFF536078.toInt()
        val accentColor = if (dark) 0xFF9CB8FF.toInt() else 0xFF345DC4.toInt()
        val scale = config.fontScale.coerceAtLeast(1f)
        if (style == ScheduleWidgetStyle.Timeline && height >= 170 * scale && width >= 200)
            return timeline(context, state, width, height, scale, dark, primaryColor, secondaryColor, accentColor)
        val views = RemoteViews(context.packageName, if (style == ScheduleWidgetStyle.Single) R.layout.schedule_widget_single else R.layout.schedule_widget)
        val two = style == ScheduleWidgetStyle.Double && state.secondary != null && width >= 250 && height >= 110 * scale
        val compactSingle = !two && height < 128 * scale
        val tall = height >= 180 * scale
        val showHeader = height >= 94 * scale
        val density = context.resources.displayMetrics.density
        val horizontalPadding = (8 * density).toInt()
        val verticalPadding = ((if (compactSingle) 4 else 6) * density).toInt()
        // Reserve the compact card's height for its name, room and time, including
        // large system fonts, before spending that space on decorative padding.
        views.setViewPadding(R.id.widget_root, horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        views.setInt(R.id.widget_root, "setBackgroundResource", when {
            style == ScheduleWidgetStyle.Single && dark -> R.drawable.schedule_widget_single_dark
            style == ScheduleWidgetStyle.Single -> R.drawable.schedule_widget_single_light
            dark -> R.drawable.schedule_widget_dark
            else -> R.drawable.schedule_widget_light
        })
        views.setTextViewText(R.id.widget_heading, state.heading)
        views.setTextViewText(R.id.widget_date, state.date)
        views.setViewVisibility(R.id.widget_header, if (showHeader) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_date, if (width >= 250) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_courses, if (state.primary != null) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_empty, if (state.primary == null) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_next, if (two) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_separator, if (two) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_footer, if (state.primary != null && height >= 224 * scale) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_footer, state.summary)
        views.setTextViewText(R.id.widget_message, state.message)
        views.setTextViewText(R.id.widget_action, state.actionLabel)
        listOf(R.id.widget_heading, R.id.widget_message, R.id.widget_name, R.id.widget_next_name).forEach { views.setTextColor(it, primaryColor) }
        listOf(R.id.widget_date, R.id.widget_location, R.id.widget_time, R.id.widget_next_location, R.id.widget_next_time, R.id.widget_footer)
            .forEach { views.setTextColor(it, secondaryColor) }
        listOf(R.id.widget_status, R.id.widget_next_status, R.id.widget_action).forEach { views.setTextColor(it, accentColor) }
        fun row(item: ScheduleWidgetCourse?, name: Int, room: Int, time: Int, status: Int) {
            if (item == null) return
            views.setTextViewText(name, item.name)
            views.setTextViewText(room, item.location)
            views.setTextViewText(time, listOf(item.dateLabel, item.time).filter(String::isNotBlank).joinToString(" "))
            views.setTextViewText(status, item.status)
            views.setInt(name, "setMaxLines", if (tall) 2 else 1)
            views.setInt(room, "setMaxLines", if (height >= 220 * scale) 2 else 1)
            val nameSize = when {
                two -> 15f
                compactSingle -> 14f
                else -> 16f
            }
            views.setTextViewTextSize(name, android.util.TypedValue.COMPLEX_UNIT_SP, nameSize)
            views.setViewVisibility(status, if (two || height >= 128 * scale) View.VISIBLE else View.GONE)
        }
        row(state.primary, R.id.widget_name, R.id.widget_location, R.id.widget_time, R.id.widget_status)
        row(state.secondary, R.id.widget_next_name, R.id.widget_next_location, R.id.widget_next_time, R.id.widget_next_status)
        if (style == ScheduleWidgetStyle.Single && !compactSingle) {
            views.setTextViewTextSize(R.id.widget_time, android.util.TypedValue.COMPLEX_UNIT_SP, if (height >= 180 * scale) 23f else 18f)
            views.setTextColor(R.id.widget_time, accentColor)
        }
        fun click(id: Int, item: ScheduleWidgetCourse? = null, action: ScheduleWidgetAction = ScheduleWidgetAction.Today) {
            val snapshot = state.snapshot
            val intent = ScheduleWidgetNavigation.intent(context, snapshot?.account.orEmpty(), snapshot?.school.orEmpty(), snapshot?.term.orEmpty(),
                item?.course?.id, if (item != null) ScheduleWidgetAction.Course else action, item?.occurrence?.startsAt)
            views.setOnClickPendingIntent(id, PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        click(R.id.widget_root)
        click(R.id.widget_header)
        click(R.id.widget_course, state.primary)
        click(R.id.widget_next, state.secondary)
        click(R.id.widget_empty, action = state.action)
        click(R.id.widget_action, action = state.action)
        return views
    }

    internal fun timelineItems(state: ScheduleWidgetState, height: Int, fontScale: Float): List<ScheduleOccurrence> {
        val today = state.agenda?.today.orEmpty()
        val limit = ((height - 58 * fontScale) / (54 * fontScale)).toInt().coerceIn(1, 6)
        if (today.size <= limit) return today
        val nextIndex = today.indexOfFirst { it.endsAt > state.now }.takeIf { it >= 0 } ?: today.lastIndex
        return today.drop(nextIndex.coerceAtMost((today.size - limit).coerceAtLeast(0))).take(limit)
    }

    private fun timeline(context: Context, state: ScheduleWidgetState, width: Int, height: Int, scale: Float,
                         dark: Boolean, primary: Int, secondary: Int, accent: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.schedule_widget_timeline)
        views.setInt(R.id.widget_root, "setBackgroundResource", if (dark) R.drawable.schedule_widget_dark else R.drawable.schedule_widget_light)
        views.setTextViewText(R.id.widget_heading, "今日时间轴")
        views.setTextViewText(R.id.widget_date, state.date)
        views.setViewVisibility(R.id.widget_date, if (width >= 280) View.VISIBLE else View.GONE)
        views.setTextColor(R.id.widget_heading, primary)
        views.setTextColor(R.id.widget_date, secondary)
        views.setTextColor(R.id.widget_footer, secondary)
        views.removeAllViews(R.id.widget_timeline_rows)
        val rows = timelineItems(state, height, scale)
        val snapshot = state.snapshot
        fun intent(item: ScheduleOccurrence? = null, action: ScheduleWidgetAction = ScheduleWidgetAction.Today): PendingIntent = PendingIntent.getActivity(context, 0,
            ScheduleWidgetNavigation.intent(context, snapshot?.account.orEmpty(), snapshot?.school.orEmpty(), snapshot?.term.orEmpty(),
                item?.course?.id, if (item != null) ScheduleWidgetAction.Course else action, item?.startsAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_root, intent())
        views.setOnClickPendingIntent(R.id.widget_header, intent())
        for (item in rows) {
            val row = RemoteViews(context.packageName, R.layout.schedule_widget_timeline_row)
            val past = item.endsAt <= state.now
            val current = state.now in item.startsAt until item.endsAt
            val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(item.startsAt))
            row.setTextViewText(R.id.widget_timeline_time, time)
            row.setTextViewText(R.id.widget_name, item.course.name)
            row.setTextViewText(R.id.widget_location, item.course.location.ifBlank { "教室待定" })
            row.setTextViewText(R.id.widget_status, when { current -> "进行中"; past -> "已结束"; else -> "待上课" })
            row.setTextColor(R.id.widget_timeline_time, if (current) accent else secondary)
            row.setTextColor(R.id.widget_name, if (past) secondary else primary)
            row.setTextColor(R.id.widget_location, secondary)
            row.setTextColor(R.id.widget_status, if (current) accent else secondary)
            row.setInt(R.id.widget_timeline_dot, "setBackgroundColor", if (current) accent else secondary)
            row.setViewVisibility(R.id.widget_status, if (width >= 320 && scale <= 1.3f) View.VISIBLE else View.GONE)
            row.setOnClickPendingIntent(R.id.widget_course, intent(item))
            views.addView(R.id.widget_timeline_rows, row)
        }
        views.setViewVisibility(R.id.widget_empty, if (rows.isEmpty()) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_message, state.message ?: "今天没有课程")
        views.setTextViewText(R.id.widget_action, state.actionLabel)
        views.setTextColor(R.id.widget_message, primary)
        views.setTextColor(R.id.widget_action, accent)
        views.setOnClickPendingIntent(R.id.widget_empty, intent(action = state.action))
        views.setOnClickPendingIntent(R.id.widget_action, intent(action = state.action))
        val total = state.agenda?.today?.size ?: 0
        views.setTextViewText(R.id.widget_footer, if (total > rows.size) "今日 $total 堂 · 点按查看全部" else state.summary.ifBlank { "点按查看课表" })
        return views
    }
}

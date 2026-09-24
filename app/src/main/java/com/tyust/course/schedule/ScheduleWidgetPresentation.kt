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
    /** Use the launcher's actual size alternatives, including both legacy orientations. */
    fun responsiveViews(context: Context, state: ScheduleWidgetState, options: android.os.Bundle,
                        style: ScheduleWidgetStyle): RemoteViews {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            val sizes = options.getParcelableArrayList<android.util.SizeF>(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_SIZES)
                .orEmpty().filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }.distinct().take(16)
            if (sizes.isNotEmpty()) return RemoteViews(sizes.associateWith {
                views(context, state, it.width.toInt(), it.height.toInt(), style)
            })
        }
        val minW = options.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, if (style == ScheduleWidgetStyle.Single) 56 else 130).coerceAtLeast(1)
        val minH = options.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, if (style == ScheduleWidgetStyle.Timeline) 130 else 56).coerceAtLeast(1)
        val maxW = options.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW).coerceAtLeast(minW)
        val maxH = options.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH).coerceAtLeast(minH)
        return RemoteViews(views(context, state, maxW, minH, style), views(context, state, minW, maxH, style))
    }

    fun views(context: Context, state: ScheduleWidgetState, width: Int = 176, height: Int = 88,
              style: ScheduleWidgetStyle = ScheduleWidgetStyle.Double): RemoteViews {
        val config = AppThemeCoordinator.wrapContext(context).resources.configuration
        val dark = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val primaryColor = if (dark) 0xFFF1F4F8.toInt() else 0xFF1D2433.toInt()
        val secondaryColor = if (dark) 0xFFB8C2D0.toInt() else 0xFF536078.toInt()
        val accentColor = if (dark) 0xFF88BFFF.toInt() else 0xFF0069D9.toInt()
        val scale = config.fontScale.coerceAtLeast(1f)
        if (style == ScheduleWidgetStyle.Timeline)
            return timeline(context, state, width, height, scale, dark, primaryColor, secondaryColor, accentColor)
        val views = RemoteViews(context.packageName, if (style == ScheduleWidgetStyle.Single) R.layout.schedule_widget_single else R.layout.schedule_widget)
        val two = style == ScheduleWidgetStyle.Double
        val padding = if (height < 100 || width < 100) 6 else 10
        val density = context.resources.displayMetrics.density
        views.setViewPadding(R.id.widget_root, (padding * density).toInt(), (padding * density).toInt(),
            (padding * density).toInt(), (padding * density).toInt())
        views.setInt(R.id.widget_root, "setBackgroundResource", if (dark) R.drawable.schedule_widget_dark else R.drawable.schedule_widget_light)
        val columnWidth = (width - padding * 2 - if (two) 13 else 0) / if (two) 2f else 1f
        val fitter = ScheduleWidgetTextFitter(context, columnWidth * density)
        val headerHeight = fitter.height(listOf(ScheduleWidgetText(state.heading, 12f, bold = true))) + fitter.dp(2f)
        val footerHeight = fitter.height(listOf(ScheduleWidgetText(state.summary, 12f))) + fitter.dp(6f)
        val compactTime = columnWidth < 116 * scale
        fun fields(item: ScheduleWidgetCourse?): List<ScheduleWidgetText> {
            val time = when {
                item == null -> "查看课表"
                compactTime -> listOf(item.dateLabel, item.time.substringBefore('–')).filter(String::isNotBlank).joinToString(" ")
                else -> listOf(item.dateLabel, item.time).filter(String::isNotBlank).joinToString(" ")
            }
            val room = item?.let {
                if (compactTime) ScheduleLocation.room(it.location) ?: ScheduleLocation.compact(it.location)
                else ScheduleLocation.compact(it.location)
            }.orEmpty()
            return listOf(
                ScheduleWidgetText(item?.name ?: "暂无后续", 14f, bold = true),
                ScheduleWidgetText(item?.course?.teacher?.ifBlank { "教师待定" }.orEmpty(), 11f, margin = 2f),
                ScheduleWidgetText(time, 11f, margin = 3f, singleLine = true),
                ScheduleWidgetText(room, 11f, margin = 2f)
            )
        }
        val required = listOfNotNull(state.primary, state.secondary.takeIf { two }).map(::fields)
        var showHeader = height >= 154 * scale && width >= 150 * scale
        var showFooter = state.primary != null && height >= 232 * scale && width >= 160 * scale
        fun bodyPixels() = fitter.dp((height - padding * 2).toFloat()) -
            (if (showHeader) headerHeight else 0) - (if (showFooter) footerHeight else 0)
        // Optional labels yield their space before any required text is made smaller.
        if (required.any { !fitter.fits(it, bodyPixels()) }) showFooter = false
        if (required.any { !fitter.fits(it, bodyPixels()) }) showHeader = false
        val bodyHeight = bodyPixels()
        val separateTargets = two
        views.setTextViewText(R.id.widget_heading, state.heading)
        views.setTextViewText(R.id.widget_date, state.date)
        views.visible(R.id.widget_header, showHeader)
        views.visible(R.id.widget_date, width >= 260 * scale)
        views.visible(R.id.widget_courses, state.primary != null)
        views.visible(R.id.widget_empty, state.primary == null)
        views.visible(R.id.widget_next, two)
        views.visible(R.id.widget_separator, two)
        views.visible(R.id.widget_footer, showFooter)
        views.setTextViewText(R.id.widget_footer, state.summary)
        views.setTextViewText(R.id.widget_message, state.message)
        views.setTextViewText(R.id.widget_action, state.actionLabel)
        val showAction = bodyHeight >= fitter.dp(44f)
        val emptyFields = listOf(ScheduleWidgetText(state.message.orEmpty(), 14f, bold = true),
            ScheduleWidgetText(if (showAction) state.actionLabel else "", 12f, margin = 4f))
        val emptyScale = ScheduleWidgetTextFitter(context, (width - padding * 2) * density).scale(emptyFields, bodyHeight)
        views.setInt(R.id.widget_message, "setMaxLines", Int.MAX_VALUE)
        views.setTextViewTextSize(R.id.widget_message, android.util.TypedValue.COMPLEX_UNIT_SP, 14f * emptyScale)
        views.setTextViewTextSize(R.id.widget_action, android.util.TypedValue.COMPLEX_UNIT_SP, 12f * emptyScale)
        views.visible(R.id.widget_action, showAction)
        listOf(R.id.widget_heading, R.id.widget_message, R.id.widget_name, R.id.widget_next_name).forEach { views.setTextColor(it, primaryColor) }
        listOf(R.id.widget_date, R.id.widget_location, R.id.widget_next_location, R.id.widget_footer,
            R.id.widget_teacher, R.id.widget_next_teacher)
            .forEach { views.setTextColor(it, secondaryColor) }
        listOf(R.id.widget_status, R.id.widget_next_status, R.id.widget_action, R.id.widget_time, R.id.widget_next_time)
            .forEach { views.setTextColor(it, accentColor) }
        fun row(item: ScheduleWidgetCourse?, container: Int, name: Int, teacher: Int, room: Int, time: Int, status: Int) {
            val fields = fields(item)
            val withStatus = listOf(ScheduleWidgetText(item?.status.orEmpty(), 11f, margin = 2f, singleLine = true)) + fields
            val showStatus = item != null && bodyHeight >= fitter.dp(96f * scale) && fitter.fits(withStatus, bodyHeight)
            val fit = fitter.scale(if (showStatus) withStatus else fields, bodyHeight)
            listOf(name, teacher, time, room).zip(fields).forEach { (id, field) ->
                views.setTextViewText(id, field.text)
                views.setTextViewTextSize(id, android.util.TypedValue.COMPLEX_UNIT_SP, field.size * fit)
            }
            views.setTextViewText(status, item?.status.orEmpty())
            views.setTextViewTextSize(status, android.util.TypedValue.COMPLEX_UNIT_SP, 11f * fit)
            views.visible(name, true)
            views.visible(teacher, item != null)
            views.visible(room, item != null)
            views.visible(status, showStatus)
            views.setContentDescription(container, item?.let { "${it.dateLabel} ${it.status}，${it.name}，${it.course.teacher.ifBlank { "教师待定" }}，${it.time}，${it.location}" }
                ?: "暂无后续课程，查看完整课表")
        }
        row(state.primary, R.id.widget_course, R.id.widget_name, R.id.widget_teacher, R.id.widget_location, R.id.widget_time, R.id.widget_status)
        row(state.secondary, R.id.widget_next, R.id.widget_next_name, R.id.widget_next_teacher, R.id.widget_next_location, R.id.widget_next_time, R.id.widget_next_status)
        val rootIntent = pending(context, state, if (style == ScheduleWidgetStyle.Single) state.primary?.occurrence else null,
            if (state.primary == null) state.action else ScheduleWidgetAction.Today)
        views.setOnClickPendingIntent(R.id.widget_root, rootIntent)
        views.setOnClickPendingIntent(R.id.widget_header, rootIntent)
        views.setOnClickPendingIntent(R.id.widget_course, if (style == ScheduleWidgetStyle.Single || separateTargets) pending(context, state, state.primary?.occurrence) else rootIntent)
        views.setOnClickPendingIntent(R.id.widget_next, if (separateTargets) pending(context, state, state.secondary?.occurrence) else rootIntent)
        views.setOnClickPendingIntent(R.id.widget_empty, pending(context, state, action = state.action))
        views.setOnClickPendingIntent(R.id.widget_action, pending(context, state, action = state.action))
        // The entire empty panel performs the same action as its label.
        views.setContentDescription(R.id.widget_empty, "${state.message}，${state.actionLabel}")
        return views
    }

    internal fun timelineItems(state: ScheduleWidgetState, limit: Int): List<ScheduleOccurrence> {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val upcoming = state.agenda?.upcoming.orEmpty()
        val nextDate = upcoming.firstOrNull()?.let { date.format(Date(it.startsAt)) }
        val today = state.agenda?.today.orEmpty().ifEmpty {
            upcoming.takeWhile { date.format(Date(it.startsAt)) == nextDate }
        }
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
        views.visible(R.id.widget_date, width >= 280 * scale)
        views.setTextColor(R.id.widget_heading, primary)
        views.setTextColor(R.id.widget_date, secondary)
        views.setTextColor(R.id.widget_footer, secondary)
        views.removeAllViews(R.id.widget_timeline_rows)
        val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(state.now))
        val next = state.agenda?.upcoming?.firstOrNull()
        val fitter = ScheduleWidgetTextFitter(context, (width - 34) * context.resources.displayMetrics.density)
        val headerHeight = fitter.height(listOf(ScheduleWidgetText("今日时间轴", 12f, bold = true))) + fitter.dp(6f)
        val footerHeight = fitter.height(listOf(ScheduleWidgetText("课表", 12f))) + fitter.dp(6f)
        var showHeader = height >= 100 * scale
        var showFooter = height >= 232 * scale
        val showStatus = width >= 320 * scale
        fun status(item: ScheduleOccurrence) = when {
            state.now in item.startsAt until item.endsAt -> "进行中"
            next != null && item.course.id == next.course.id && item.startsAt == next.startsAt -> "下一节"
            item.endsAt <= state.now -> "已结束"
            else -> "待上课"
        }
        fun fields(item: ScheduleOccurrence): List<ScheduleWidgetText> {
            val itemDate = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(item.startsAt))
            val tomorrow = Calendar.getInstance().apply { timeInMillis = state.now; add(Calendar.DATE, 1) }.time
            val dateLabel = when (itemDate) {
                todayDate -> ""
                SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(tomorrow) -> "明天 "
                else -> SimpleDateFormat("M/d ", Locale.CHINA).format(Date(item.startsAt))
            }
            val time = dateLabel + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(item.startsAt)) +
                if (width >= 250 * scale) "–" + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(item.endsAt)) else ""
            val room = if (width < 250 * scale) ScheduleLocation.room(item.course.location) ?: ScheduleLocation.compact(item.course.location)
                else ScheduleLocation.compact(item.course.location)
            return listOf(ScheduleWidgetText(time, 12f, singleLine = true),
                ScheduleWidgetText(item.course.name, 14f, bold = true, margin = 2f),
                ScheduleWidgetText(item.course.teacher.ifBlank { "教师待定" }, 12f, margin = 2f),
                ScheduleWidgetText(room, 12f, margin = 2f))
        }
        fun measuredFields(item: ScheduleOccurrence) = fields(item).mapIndexed { index, field ->
            if (index == 0 && showStatus) field.copy(text = "${field.text}  ${status(item)}") else field
        }
        fun available() = fitter.dp((height - 16).toFloat()) - (if (showHeader) headerHeight else 0) -
            (if (showFooter) footerHeight else 0)
        fun rowHeight(item: ScheduleOccurrence) = maxOf(fitter.dp(48f), fitter.height(measuredFields(item)) + fitter.dp(8f))
        fun fittingRows(): List<ScheduleOccurrence>? {
            for (limit in 6 downTo 1) {
                val candidates = timelineItems(state, limit)
                if (candidates.sumOf(::rowHeight) <= available() &&
                    candidates.all { fitter.fits(measuredFields(it), Int.MAX_VALUE) }) return candidates
            }
            return null
        }
        // Keep normal typography by showing fewer complete rows; only the smallest
        // single-row layout needs type fitting after the optional chrome is removed.
        var fitting = fittingRows()
        if (fitting == null) { showFooter = false; fitting = fittingRows() }
        if (fitting == null) { showHeader = false; fitting = fittingRows() }
        val rows = fitting ?: timelineItems(state, 1)
        views.visible(R.id.widget_header, showHeader)
        views.visible(R.id.widget_footer, showFooter)
        if (rows.firstOrNull()?.let { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(it.startsAt)) != todayDate } == true)
            views.setTextViewText(R.id.widget_heading, "接下来的课程")
        views.setOnClickPendingIntent(R.id.widget_root, pending(context, state, action = if (rows.isEmpty()) state.action else ScheduleWidgetAction.Today))
        views.setOnClickPendingIntent(R.id.widget_header, pending(context, state))
        for (item in rows) {
            val row = RemoteViews(context.packageName, R.layout.schedule_widget_timeline_row)
            val past = item.endsAt <= state.now
            val highlighted = status(item) in listOf("进行中", "下一节")
            val fields = fields(item)
            val rowPixels = if (fitting != null) rowHeight(item) else available()
            val typeFit = fitter.scale(measuredFields(item), rowPixels - fitter.dp(8f))
            listOf(R.id.widget_timeline_time, R.id.widget_name, R.id.widget_teacher, R.id.widget_location).zip(fields).forEach { (id, field) ->
                row.setTextViewText(id, field.text)
                row.setTextViewTextSize(id, android.util.TypedValue.COMPLEX_UNIT_SP, field.size * typeFit)
            }
            row.setTextViewTextSize(R.id.widget_status, android.util.TypedValue.COMPLEX_UNIT_SP, 12f * typeFit)
            row.setTextViewText(R.id.widget_status, status(item))
            row.setTextColor(R.id.widget_timeline_time, if (highlighted) accent else secondary)
            row.setTextColor(R.id.widget_name, if (past) secondary else primary)
            row.setTextColor(R.id.widget_location, secondary)
            row.setTextColor(R.id.widget_teacher, secondary)
            row.setTextColor(R.id.widget_status, if (highlighted) accent else secondary)
            row.setInt(R.id.widget_timeline_dot, "setBackgroundColor", if (highlighted) accent else secondary)
            row.setInt(R.id.widget_course, "setMinimumHeight", rowPixels.coerceAtLeast(0))
            row.visible(R.id.widget_location, true)
            row.visible(R.id.widget_status, showStatus)
            row.setContentDescription(R.id.widget_course, "${fields[0].text}，${item.course.name}，${item.course.teacher.ifBlank { "教师待定" }}，${item.course.location}，${status(item)}")
            row.setOnClickPendingIntent(R.id.widget_course, if (width >= 64 && height >= 64) pending(context, state, item) else pending(context, state))
            views.addView(R.id.widget_timeline_rows, row)
        }
        views.visible(R.id.widget_timeline_rows, rows.isNotEmpty())
        views.visible(R.id.widget_empty, rows.isEmpty())
        views.setTextViewText(R.id.widget_message, state.message ?: "今天没有课程")
        views.setTextViewText(R.id.widget_action, state.actionLabel)
        views.visible(R.id.widget_action, height >= 82 * scale)
        views.setTextColor(R.id.widget_message, primary)
        views.setTextColor(R.id.widget_action, accent)
        views.setOnClickPendingIntent(R.id.widget_empty, pending(context, state, action = state.action))
        views.setOnClickPendingIntent(R.id.widget_action, pending(context, state, action = state.action))
        views.setContentDescription(R.id.widget_empty, "${state.message ?: "今天没有课程"}，${state.actionLabel}")
        val total = state.agenda?.today?.size ?: 0
        views.setTextViewText(R.id.widget_footer, if (total > rows.size) "今日 $total 堂 · 点按查看全部" else state.summary.ifBlank { "点按查看课表" })
        return views
    }

    private fun RemoteViews.visible(id: Int, show: Boolean) = setViewVisibility(id, if (show) View.VISIBLE else View.GONE)
    private fun pending(context: Context, state: ScheduleWidgetState, item: ScheduleOccurrence? = null,
                        action: ScheduleWidgetAction = ScheduleWidgetAction.Today): PendingIntent {
        val snapshot = state.snapshot
        return PendingIntent.getActivity(context, 0, ScheduleWidgetNavigation.intent(context,
            snapshot?.account.orEmpty(), snapshot?.school.orEmpty(), snapshot?.term.orEmpty(), item?.course?.id,
            if (item != null) ScheduleWidgetAction.Course else action, item?.startsAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

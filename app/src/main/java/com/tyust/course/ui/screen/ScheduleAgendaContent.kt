package com.tyust.course.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.schedule.*
import com.tyust.course.ui.system.*

@Composable
internal fun ScheduleNotice(message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp).testTag("schedule-notice"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) TextButton(onAction) { Text(action) }
    }
}

@Composable
internal fun ScheduleDayList(
    courses: List<ScheduleCourseUi>, week: Int, day: Int, firstWeekDate: String?,
    times: List<PeriodTimeUi>, compact: Boolean, scrollState: ScrollState, topInset: Dp,
    onCourseClick: (ScheduleCourseUi) -> Unit,
    onCourseLongClick: (ScheduleCourseUi) -> Unit = {},
    agenda: ScheduleAgenda, now: Long, isToday: Boolean, onCalendar: () -> Unit
) {
    val daily = remember(courses, week, day) { courses.filter { it.day == day && isInWeek(it.weeks, week) }
        .sortedWith(compareBy<ScheduleCourseUi> { it.startPeriod }.thenBy { it.name }) }
    val unknown = daily.count { !ScheduleWeeks.parse(it.weeks).valid }
    Column(Modifier.fillMaxSize().testTag("schedule-day-list").verticalScroll(scrollState)
        .padding(start = 16.dp, end = 16.dp, top = topInset + 12.dp, bottom = LocalAppOverlayBottomInset.current + 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
        when {
            ScheduleDates.firstMonday(firstWeekDate) == null ->
                ScheduleNotice("设置开学日期后显示当天课程", "设置开学日期", onCalendar)
            week !in 1..ScheduleMaxWeeks -> ScheduleNotice(if (week < 1) "尚未开学，当天没有课程" else "本学期已结束")
            else -> {
                val summary = if (isToday) {
                    "今日 ${daily.size} 堂" + when {
                        unknown > 0 -> " · $unknown 堂周次待核对"
                        daily.isNotEmpty() && agenda.remaining(now) == 0 -> " · 已结束"
                        else -> " · 还剩 ${agenda.remaining(now)} 堂"
                    }
                } else "当日 ${daily.size} 堂" + if (unknown > 0) " · $unknown 堂周次待核对" else ""
                Text(summary, Modifier.testTag("schedule-day-summary").padding(start = 4.dp, bottom = 2.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (daily.isEmpty()) ScheduleNotice(if (isToday) "今天没有课程" else "当天没有课程")
                daily.forEach { course -> key(course.id) {
                    ScheduleDayCourse(course, times, compact, { onCourseClick(course) }, { onCourseLongClick(course) })
                } }
            }
        }
    }
}

/** A static, wallpaper-safe surface: time at left, then name, room and a small status. */
@Composable
internal fun ScheduleDayCourse(course: ScheduleCourseUi, times: List<PeriodTimeUi>, compact: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    val start = times.firstOrNull { it.period == course.startPeriod }?.startTime.orEmpty()
    val end = times.firstOrNull { it.period == course.endPeriod }?.endTime.orEmpty()
    val unknown = !ScheduleWeeks.parse(course.weeks).valid
    val colors = MaterialTheme.colorScheme
    val emphasis = course.isCurrent || course.isNext
    val fill = scheduleCardColor(course.color, if (course.isCurrent) 0.13f else if (course.isNext) 0.07f else 0.025f)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = rememberGlassAccessibilityMode().reduceMotion
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed && !reduced) 0.985f else 1f,
        androidx.compose.animation.core.tween(if (reduced) 0 else 140), label = "schedule-card-press")
    val status = when {
        unknown -> "周次待核对"
        course.isCurrent -> "正在上课"
        course.isNext -> "下一节"
        course.hasConflict -> "同一时段有其他课程"
        else -> ""
    }
    val focus = remember { FocusRequester() }
    val registry = LocalScheduleFocus.current
    var bounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    DisposableEffect(course.id, registry, focus) {
        registry?.register(course.id, focus)
        onDispose { registry?.remove(course.id, focus) }
    }
    Surface(Modifier.fillMaxWidth().testTag("schedule-day-course-${course.id}").focusRequester(focus)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .onGloballyPositioned { bounds = it.boundsInWindow(); registry?.place(course.id, it.boundsInWindow()) }
        .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = {
            registry?.register(course.id, focus); bounds?.let { registry?.place(course.id, it) }; onClick()
        }, onLongClickLabel = "课程快捷操作", onLongClick = onLongClick)
        .semantics { stateDescription = status },
        color = fill, shape = RoundedCornerShape(20.dp),
        border = scheduleCardBorder(course.color, emphasis)) {
        Row(Modifier.height(IntrinsicSize.Min).scheduleGlassSheen().padding(if (compact) 12.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(60.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(start.ifBlank { "--:--" }, Modifier.fillMaxWidth(), fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                    color = if (emphasis) colors.primary else colors.onSurface, maxLines = 1)
                Text(end.ifBlank { "--:--" }, Modifier.fillMaxWidth(), fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1)
                Text("${course.startPeriod}–${course.endPeriod} 节", Modifier.padding(top = 4.dp), fontSize = 11.sp,
                    color = colors.onSurfaceVariant)
            }
            Box(Modifier.width(3.dp).fillMaxHeight().background(course.color.copy(alpha = 0.78f), RoundedCornerShape(2.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(course.name, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(course.location.ifBlank { "教室待定" }, Modifier.fillMaxWidth().testTag("schedule-day-location-${course.id}"),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                if (status.isNotEmpty()) Text(status, Modifier.testTag("schedule-day-status-${course.id}"),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium,
                    color = if (unknown || course.hasConflict && !emphasis) colors.error else colors.primary)
            }
        }
    }
}

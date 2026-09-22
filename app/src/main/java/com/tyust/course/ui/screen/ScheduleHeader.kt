package com.tyust.course.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.tyust.course.schedule.ScheduleDates
import com.tyust.course.schedule.ScheduleMaxWeeks
import com.tyust.course.ui.system.*
import com.tyust.course.ui.system.glass.LocalGlassLensAnchor
import com.tyust.course.ui.system.glass.LocalPageGlassFreshness
import com.tyust.course.ui.system.glass.drawBackdropSource
import com.tyust.course.ui.system.glass.glassLensAnchor
import com.tyust.course.ui.system.glass.rememberGlassLensRegion
import java.util.Calendar

@Composable
internal fun scheduleHeaderHeight() = maxOf(56.dp, 44.dp * LocalDensity.current.fontScale) * 2

/** Floating controls at rest; scrolling reveals one retained glass surface beneath them. */
@Composable
fun WeekHeaderCompact(
    currentWeek: Int,
    onPrevClick: () -> Unit,
    onNextClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    onExportClick: () -> Unit = {},
    isNextSemester: Boolean = false,
    onToggleSemester: () -> Unit = {},
    collapseFraction: Float = 0f,
    sampleBackdrop: Backdrop? = null,
    weekOffset: Float = 0f,
    firstWeekDate: String? = null,
    actualWeek: Int? = null,
    showWeekend: Boolean = true,
    selectedDay: Int? = null,
    onDayClick: (Int) -> Unit = {},
    dayView: Boolean = false,
    onDayView: (Boolean) -> Unit = {},
    onWeekSelect: (Int) -> Unit = {},
    onTodayClick: () -> Unit = {},
    showToday: Boolean = false,
    onSyncClick: () -> Unit = {},
    onAddClick: () -> Unit = {},
    onWidgetClick: () -> Unit = {},
    now: Long = System.currentTimeMillis()
) {
    var calendarOpen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val registry = LocalScheduleFocus.current
    DisposableEffect(registry, focus) {
        registry?.register("header", focus)
        onDispose { registry?.remove("header", focus) }
    }
    val anchor = firstWeekDate?.takeIf { ScheduleDates.firstMonday(it) != null }
        ?: com.tyust.course.schedule.ScheduleTimeBase.dateFromMillis(ScheduleDates.mondayOfWeek(now).timeInMillis)
    val date = requireNotNull(ScheduleDates.date(anchor, currentWeek, selectedDay ?: 1))
    val weekday = ScheduleDates.dayAt(now)
    val colors = MaterialTheme.colorScheme
    val statusHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val height = scheduleHeaderHeight()
    val actionHeight = height / 2
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val lift = collapseFraction.coerceIn(0f, 1f)
    val collapsedControls = lift >= 0.5f
    val headerBackdrop = if (sampleBackdrop != null) rememberLayerBackdrop() else null
    val controlBackdrop = if (sampleBackdrop != null && headerBackdrop != null)
        rememberCombinedBackdrop(sampleBackdrop, headerBackdrop) else null
    val headerLens = if (controlBackdrop != null) rememberGlassLensRegion(
        "schedule-header", currentWeek, selectedDay, lift >= 0.99f,
        freshness = LocalPageGlassFreshness.current
    ) { coordinates -> drawBackdropSource(controlBackdrop, density, coordinates) } else null
    Box(Modifier.fillMaxWidth().height(statusHeight + height).testTag("schedule-header")
        .then(wallpaperHeaderScrim()).glassLensAnchor(headerLens)) {
        // The sampled surface is a sibling of the controls, never their parent.
        Box(Modifier.matchParentSize().then(if (headerBackdrop != null) Modifier.layerBackdrop(headerBackdrop) else Modifier)) {
            val floatingPanel = Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp,
                top = statusHeight + 4.dp, bottom = 4.dp)
            if (sampleBackdrop != null) {
                StatusBarFrost(statusHeight + 1.dp, lift, sampleBackdrop)
                HeaderGlassSlab(strength = lift, backdrop = sampleBackdrop, cornerRadius = 26.dp,
                    modifier = floatingPanel)
            } else {
                Box(floatingPanel.shadow(8.dp * lift, RoundedCornerShape(26.dp), clip = false)
                    .background(colors.surface.copy(alpha = lift), RoundedCornerShape(26.dp)))
            }
        }
        CompositionLocalProvider(LocalControlBackdrop provides controlBackdrop, LocalGlassLensAnchor provides headerLens) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(actionHeight).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).heightIn(min = 48.dp).testTag("schedule-header-title").focusRequester(focus)
                    .clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { calendarOpen = true }
                    .semantics { contentDescription = "选择日期与学期" }.padding(start = 4.dp),
                    verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${date.get(Calendar.MONTH) + 1}月${date.get(Calendar.DAY_OF_MONTH)}日",
                            modifier = Modifier.weight(1f, fill = false),
                            fontSize = when { fontScale > 1.3f -> 15.sp; fontScale > 1.1f -> 20.sp; else -> 22.sp },
                            lineHeight = if (fontScale > 1.3f) 18.sp else 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        AnimatedLineIcon(AnimatedIconSpec.Chevron, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                    }
                    Text(when {
                        ScheduleDates.firstMonday(firstWeekDate) == null -> "开学日期待设置"
                        currentWeek < 1 -> "尚未开学"
                        currentWeek > ScheduleMaxWeeks -> "本学期已结束"
                        else -> (if (isNextSemester) "下学期 · " else "") + "第 $currentWeek 周"
                    }, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, color = colors.onSurfaceVariant)
                }
                Row(Modifier.testTag("schedule-header-actions"), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(!collapsedControls, enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End), exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End)) {
                        ScheduleViewToggle(dayView, { if (!collapsedControls) onDayView(it) }, controlBackdrop)
                    }
                    SystemActionMenu("更多课表操作", (if (collapsedControls) listOf(
                        SystemMenuAction(if (dayView) "切换为周视图" else "切换为日视图", Icons.Outlined.CalendarMonth, { onDayView(!dayView) }),
                        SystemMenuAction("回到今天", Icons.Outlined.Today, onTodayClick)
                    ) else emptyList()) + listOf(
                        SystemMenuAction("同步课表", Icons.Outlined.Refresh, onSyncClick),
                        SystemMenuAction("导出课表", Icons.Outlined.Share, onExportClick),
                        SystemMenuAction("添加课程", Icons.Outlined.Add, onAddClick),
                        SystemMenuAction("桌面组件", Icons.Outlined.Widgets, onWidgetClick),
                        SystemMenuAction("课表设置", Icons.Outlined.Settings, onSettingsClick)
                    ), Modifier.testTag("schedule-more"), trigger = { toggle ->
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            LiquidButton(toggle, backdrop = controlBackdrop, modifier = Modifier.size(40.dp),
                                minHeight = 40.dp, horizontalPadding = 0.dp) {
                                AnimatedLineIcon(AnimatedIconSpec.More, Modifier.size(21.dp), description = "更多课表操作",
                                    tint = colors.onSurface)
                            }
                        }
                    })
                }
            }
            Row(Modifier.fillMaxWidth().height(height - actionHeight).padding(horizontal = scheduleGridPadding()),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(scheduleTimeColumnWidth() + ScheduleTimeColumnShadowWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    androidx.compose.animation.AnimatedVisibility(showToday && !collapsedControls, enter = fadeIn(), exit = fadeOut()) {
                    LiquidButton({ if (!collapsedControls) onTodayClick() }, backdrop = controlBackdrop,
                        modifier = Modifier.size(40.dp).testTag("schedule-today")
                            .semantics { contentDescription = "回到今天" }, minHeight = 40.dp, horizontalPadding = 0.dp) {
                        Text("今天", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.primary)
                    }
                    }
                }
                ScheduleDateStrip(anchor, currentWeek, selectedDay ?: 1, if (showWeekend) 7 else 5,
                    today = weekday.takeIf { currentWeek == actualWeek && !isNextSemester },
                    backdrop = controlBackdrop, height = actionHeight - 8.dp, modifier = Modifier.weight(1f), onDayClick)
            }
        }
        }
    }
    if (calendarOpen) ScheduleCalendarPanel(currentWeek, isNextSemester, onToggleSemester,
        onPrevClick, onNextClick, { onWeekSelect(it); calendarOpen = false }, { calendarOpen = false })
}

@Composable
internal fun ScheduleViewToggle(dayView: Boolean, onChange: (Boolean) -> Unit, backdrop: Backdrop? = LocalControlBackdrop.current) {
    Box(Modifier.width(88.dp).height(48.dp).testTag("schedule-view-toggle"), contentAlignment = Alignment.Center) {
        LiquidSegmentedControl(listOf("日视图", "周视图"), if (dayView) 0 else 1, { onChange(it == 0) },
            height = 36.dp, backdrop = backdrop) { index, selection, color ->
            Text(if (index == 0) "日" else "周", fontSize = 14.sp, color = color,
                fontWeight = if (selection >= 0.5f) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

@Composable
private fun ScheduleDateStrip(anchor: String, week: Int, selectedDay: Int, dayCount: Int, today: Int?,
    backdrop: Backdrop?, height: androidx.compose.ui.unit.Dp, modifier: Modifier, onDayClick: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val labels = remember(dayCount) { (1..dayCount).map { "星期${"一二三四五六日"[it - 1]}" } }
    // One retained lens source for the whole strip. Zero edge padding keeps every
    // date centered on its timetable column, including narrow and large-font layouts.
    LiquidSegmentedControl(labels, selectedDay - 1, { onDayClick(it + 1) }, modifier,
        backdrop = backdrop, height = height, edgePadding = 0.dp,
        verticalInset = 2.dp, restingRefraction = 0f, showTrack = false) { index, selection, color ->
        val day = index + 1
        val date = requireNotNull(ScheduleDates.date(anchor, week, day)).get(Calendar.DAY_OF_MONTH)
        Column(Modifier.fillMaxSize().testTag("schedule-weekday-$day").semantics {
            if (today == day) stateDescription = "今天"
        }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("一二三四五六日"[index].toString(), fontSize = 11.sp, lineHeight = 15.sp, color = color)
            Text(date.toString(), fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold,
                color = if (today == day || selection >= 0.5f) colors.primary else colors.onSurface)
            Box(Modifier.padding(top = 2.dp).size(3.dp)
                .background(if (today == day) colors.primary else Color.Transparent, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun ScheduleCalendarPanel(week: Int, next: Boolean, onSemester: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    SystemDialog(onDismissRequest = onDismiss, title = { Text("日期与学期") }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiquidSegmentedControl(listOf("本学期", "下学期"), if (next) 1 else 0,
                { if (next != (it == 1)) onSemester() }, height = 44.dp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onPrevious, enabled = week > 1) { AnimatedLineIcon(AnimatedIconSpec.Back, description = "上一周") }
                Text("选择周次", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onNext, enabled = week < ScheduleMaxWeeks) { AnimatedLineIcon(AnimatedIconSpec.Forward, description = "下一周") }
            }
            (1..ScheduleMaxWeeks).chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { number ->
                        Box(Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (week == number) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                            .selectable(week == number, role = Role.Button) { onSelect(number) }
                            .semantics { contentDescription = "选择第 $number 周" }, contentAlignment = Alignment.Center) {
                            Text(number.toString(), fontWeight = if (week == number) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

package com.tyust.course.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
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

// Numeric and CJK fallback fonts share a stable line box. Tabular digits keep
// single- and double-digit dates centered when the selected week changes.
private val ScheduleDateTypography = TextStyle(
    letterSpacing = 0.sp,
    fontFeatureSettings = "tnum",
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
)

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
    stripSwipesWeeks: Boolean = false,
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
    val anchor = firstWeekDate?.takeIf { ScheduleDates.firstWeekDate(it) != null }
        ?: com.tyust.course.schedule.ScheduleTimeBase.dateFromMillis(ScheduleDates.mondayOfWeek(now).timeInMillis)
    val date = requireNotNull(ScheduleDates.date(anchor, currentWeek, selectedDay ?: 1))
    val weekday = ScheduleDates.dayAt(now)
    val colors = MaterialTheme.colorScheme
    val region = rememberWallpaperRegionState()
    val appearance = rememberWallpaperRegionAppearance(region)
    val statusHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val height = scheduleHeaderHeight()
    val actionHeight = height / 2
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val lift = collapseFraction.coerceIn(0f, 1f)
    val collapsedControls = lift >= 0.5f
    val effectiveBackdrop = sampleBackdrop?.takeIf { isBackdropSupported() }
    val headerBackdrop = if (effectiveBackdrop != null) rememberLayerBackdrop() else null
    val controlBackdrop = if (effectiveBackdrop != null && headerBackdrop != null)
        rememberCombinedBackdrop(effectiveBackdrop, headerBackdrop) else null
    val headerLens = if (controlBackdrop != null) rememberGlassLensRegion(
        "schedule-header", currentWeek, selectedDay, lift >= 0.99f,
        freshness = LocalPageGlassFreshness.current
    ) { coordinates -> drawBackdropSource(controlBackdrop, density, coordinates) } else null
    ProvideWallpaperAppearance(appearance) {
    Box(Modifier.fillMaxWidth().height(statusHeight + height).testTag("schedule-header").wallpaperRegion(region)
        .then(wallpaperHeaderScrim()).glassLensAnchor(headerLens)) {
        // The sampled surface is a sibling of the controls, never their parent.
        Box(Modifier.matchParentSize().then(if (headerBackdrop != null) Modifier.layerBackdrop(headerBackdrop) else Modifier)) {
            val floatingPanel = Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp,
                top = statusHeight + 4.dp, bottom = 4.dp)
            StatusBarFrost(statusHeight + 1.dp, lift, effectiveBackdrop)
            HeaderGlassSlab(strength = lift, backdrop = effectiveBackdrop, cornerRadius = 26.dp,
                modifier = floatingPanel)
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
                        ScheduleDateText("${date.get(Calendar.MONTH) + 1}月${date.get(Calendar.DAY_OF_MONTH)}日",
                            timestamp = date.timeInMillis, tag = "schedule-date-title",
                            modifier = Modifier.weight(1f, fill = false), color = appearance.onSurface,
                            style = ScheduleDateTypography.copy(
                                fontSize = when { fontScale > 1.3f -> 15.sp; fontScale > 1.1f -> 20.sp; else -> 22.sp },
                                lineHeight = if (fontScale > 1.3f) 18.sp else 24.sp, fontWeight = FontWeight.Bold))
                        AnimatedLineIcon(AnimatedIconSpec.Chevron, Modifier.size(14.dp), tint = appearance.onSurfaceVariant)
                    }
                    val weekStatus = when {
                        ScheduleDates.firstWeekDate(firstWeekDate) == null -> "开学日期待设置"
                        currentWeek < 1 -> "尚未开学"
                        currentWeek > ScheduleMaxWeeks -> "本学期已结束"
                        else -> null
                    }
                    val weekStyle = MaterialTheme.typography.labelSmall.merge(ScheduleDateTypography)
                        .copy(fontSize = 11.sp, lineHeight = 14.sp)
                    if (weekStatus != null) Text(weekStatus, Modifier.testTag("schedule-header-week"),
                        style = weekStyle, color = appearance.onSurfaceVariant)
                    else ScheduleWeekLabel(currentWeek, isNextSemester, weekStyle, appearance.onSurfaceVariant)
                }
                Row(Modifier.testTag("schedule-header-actions"), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ScheduleViewToggle(dayView, onDayView, controlBackdrop)
                    SystemActionMenu("更多课表操作", listOf(
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
                                    tint = appearance.onSurface)
                            }
                        }
                    })
                }
            }
            Row(Modifier.fillMaxWidth().height(height - actionHeight).padding(horizontal = scheduleGridPadding()),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(scheduleTimeColumnWidth() + ScheduleTimeColumnShadowWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    if (showToday) {
                        TextButton(onTodayClick, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("schedule-today").semantics { contentDescription = "回到今天" },
                            contentPadding = PaddingValues(0.dp)) {
                            Text("今天", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.primary)
                        }
                    }
                }
                ScheduleDateStrip(anchor, currentWeek, selectedDay ?: 1, ScheduleDates.weekDayOrder(firstWeekDate, showWeekend),
                    todayWeek = actualWeek.takeIf { !isNextSemester }, todayDay = weekday,
                    backdrop = controlBackdrop, height = actionHeight - 8.dp, modifier = Modifier.weight(1f), onDayClick,
                    swipeWeeks = stripSwipesWeeks, onWeekSwipe = onWeekSelect)
            }
        }
        }
    }
    }
    if (calendarOpen) ScheduleCalendarPanel(currentWeek, isNextSemester, onToggleSemester,
        onPrevClick, onNextClick, { onWeekSelect(it); calendarOpen = false }, { calendarOpen = false })
}

/** Reserve two digits as a centered number, rather than padding a single digit on its left. */
@Composable
internal fun ScheduleWeekLabel(week: Int, next: Boolean, style: TextStyle, color: Color) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val width = remember(style, density, measurer) {
        with(density) { (1..ScheduleMaxWeeks).maxOf { measurer.measure(it.toString(), style).size.width }.toDp() }
    }
    Row(Modifier.testTag("schedule-header-week"), verticalAlignment = Alignment.CenterVertically) {
        Text((if (next) "下学期 · " else "") + "第 ", Modifier.testTag("schedule-week-prefix"), style = style, color = color)
        ScheduleDateText(week.toString(), week.toLong(), "schedule-week-number", style, color,
            modifier = Modifier.width(width), fillColumn = true)
        Text(" 周", Modifier.testTag("schedule-week-suffix"), style = style, color = color)
    }
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

/** Zero-based page of the week strip; one page is exactly one week and never wraps. */
internal fun stripWeekPage(week: Int): Int = (week - 1).coerceIn(0, ScheduleMaxWeeks - 1)

/** The week a week-strip page shows; 1-based, clamped to the semester, never wrapped. */
internal fun stripPageWeek(page: Int): Int = (page + 1).coerceIn(1, ScheduleMaxWeeks)

/** Draws the seven dates of one week. When a pager owns the drag the row only resolves taps. */
@Composable
private fun ScheduleWeekRow(anchor: String, week: Int, selectedDay: Int, order: List<Int>, today: Int?,
    backdrop: Backdrop?, height: androidx.compose.ui.unit.Dp, modifier: Modifier, tapsOnly: Boolean,
    onDayClick: (Int) -> Unit) {
    val appearance = LocalWallpaperAppearanceColors.current
    val accent = readableAccent(appearance)
    val labels = remember(order) { order.map { "星期${"一二三四五六日"[it - 1]}" } }
    val latestOrder by rememberUpdatedState(order)
    val latestDayClick by rememberUpdatedState(onDayClick)
    val tapModifier = if (!tapsOnly) modifier else modifier.pointerInput(Unit) {
        detectTapGestures { offset ->
            val current = latestOrder
            val segment = size.width.toFloat() / current.size.coerceAtLeast(1)
            val index = if (segment <= 0f) 0 else (offset.x / segment).toInt().coerceIn(0, current.lastIndex)
            latestDayClick(current[index])
        }
    }
    // One retained lens source for the whole strip. Zero edge padding keeps every
    // date centered on its timetable column, including narrow and large-font layouts.
    LiquidSegmentedControl(labels, order.indexOf(selectedDay).coerceAtLeast(0), { onDayClick(order[it]) }, tapModifier,
        gestures = !tapsOnly,
        backdrop = backdrop, height = height, edgePadding = 0.dp,
        verticalInset = 2.dp, restingRefraction = 0f, showTrack = false,
        refractLabels = false) { index, selection, color ->
        val day = order[index]
        val date = requireNotNull(ScheduleDates.date(anchor, week, day))
        Column(Modifier.fillMaxSize().testTag("schedule-weekday-$day").semantics {
            if (today == day) stateDescription = "今天"
        }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("一二三四五六日"[day - 1].toString(),
                modifier = Modifier.fillMaxWidth().testTag("schedule-weekday-label-$day"),
                style = ScheduleDateTypography, textAlign = TextAlign.Center,
                fontSize = 11.sp, lineHeight = 15.sp, color = color, maxLines = 1)
            ScheduleDateText(date.get(Calendar.DAY_OF_MONTH).toString(), timestamp = date.timeInMillis,
                tag = "schedule-day-number-$day", modifier = Modifier.fillMaxWidth(), fillColumn = true,
                style = ScheduleDateTypography.copy(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
                color = if (today == day || selection >= 0.5f) accent else appearance.onSurface)
            Box(Modifier.padding(top = 2.dp).size(3.dp)
                .background(if (today == day) accent else Color.Transparent, RoundedCornerShape(2.dp)))
        }
    }
}

/**
 * The date strip. By default it shows the selected week and its own drag walks the days of that
 * week. In week mode it becomes a one-week-per-page pager: a swipe always turns exactly one week,
 * the seven dates travel together as a single unit, and tapping a date still picks that day.
 */
@Composable
private fun ScheduleDateStrip(anchor: String, week: Int, selectedDay: Int, order: List<Int>, todayWeek: Int?, todayDay: Int?,
    backdrop: Backdrop?, height: androidx.compose.ui.unit.Dp, modifier: Modifier, onDayClick: (Int) -> Unit,
    swipeWeeks: Boolean = false, onWeekSwipe: (Int) -> Unit = {}) {
    if (!swipeWeeks) {
        ScheduleWeekRow(anchor, week, selectedDay, order, todayDay?.takeIf { week == todayWeek },
            backdrop, height, modifier, tapsOnly = false, onDayClick)
        return
    }
    val pager = rememberPagerState(initialPage = stripWeekPage(week), pageCount = { ScheduleMaxWeeks })
    val navigating = remember(pager) { mutableStateOf(false) }
    val latestWeekSwipe by rememberUpdatedState(onWeekSwipe)
    val motion = tween<Float>(com.tyust.course.ui.theme.MotionDuration.Medium,
        easing = com.tyust.course.ui.theme.MotionEasing.Standard)
    LaunchedEffect(pager, week) {
        val target = stripWeekPage(week)
        if (pager.currentPage == target && pager.currentPageOffsetFraction == 0f) return@LaunchedEffect
        navigating.value = true
        try { pager.animateScrollToPage(target, animationSpec = motion) } finally { navigating.value = false }
    }
    LaunchedEffect(pager) {
        // Only a user scroll may write a week back upstream, and it always lands on one page.
        var userScrolling = false
        snapshotFlow { Triple(pager.isScrollInProgress, navigating.value, pager.settledPage) }
            .collect { (scrolling, programmed, page) ->
                if (programmed) userScrolling = false
                else if (scrolling) userScrolling = true
                else if (userScrolling) { userScrolling = false; latestWeekSwipe(stripPageWeek(page)) }
            }
    }
    HorizontalPager(pager, modifier, overscrollEffect = null,
        flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = motion),
        beyondViewportPageCount = 0) { page ->
        val pageWeek = stripPageWeek(page)
        ScheduleWeekRow(anchor, pageWeek, selectedDay, order, todayDay?.takeIf { pageWeek == todayWeek },
            backdrop, height, Modifier.fillMaxSize(), tapsOnly = true, onDayClick)
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

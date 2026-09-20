package com.tyust.course.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.tyust.course.schedule.*
import com.tyust.course.ui.system.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun ScheduleScreen(
    currentWeek: Int,
    courses: List<ScheduleCourseUi>,
    isLoading: Boolean,
    periodTimes: List<PeriodTimeUi> = emptyList(),
    periodCount: Int = 12,
    onWeekChange: (Int) -> Unit,
    onCourseClick: (ScheduleCourseUi) -> Unit,
    onSettingsClick: () -> Unit = {},
    onExportClick: () -> Unit = {},
    isNextSemester: Boolean = false,
    onToggleSemester: () -> Unit = {},
    errorMessage: String = "",
    onRetry: () -> Unit = {},
    firstWeekDate: String? = null,
    weekRequestKey: String? = null,
    displayPreferences: ScheduleDisplayPreferences = ScheduleDisplayPreferences(),
    onDisplayPreferences: (ScheduleDisplayPreferences) -> Unit = {},
    selectedDay: Int = ScheduleDates.dayAt(System.currentTimeMillis()),
    onDayChange: (Int) -> Unit = {},
    onTodayClick: () -> Unit = {},
    positionKey: String = "",
    positionCalendar: String = "",
    restoredPosition: ScheduleViewPosition? = null,
    onPositionChange: (ScheduleViewPosition) -> Unit = {},
    onCourseLongClick: (ScheduleCourseUi) -> Unit = {},
    onAddClick: () -> Unit = {},
    onWidgetClick: () -> Unit = {},
    now: Long? = null
) {
    val scope = rememberCoroutineScope()
    val reduced = rememberGlassAccessibilityMode().reduceMotion
    val lifecycleOwner = LocalLifecycleOwner.current
    val minuteClock by produceState(System.currentTimeMillis(), lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { value = System.currentTimeMillis(); delay(60_000L - value % 60_000L) }
        }
    }
    val clock = now ?: minuteClock
    val base = remember(firstWeekDate, periodTimes) { ScheduleTimeBase(firstWeekDate.orEmpty(),
        periodTimes.associate { it.period to it.startTime }, periodTimes.associate { it.period to it.endTime }) }
    val agenda = remember(courses, base, clock) { ScheduleAgenda.calculate(courses.map { it.record() }, base, clock) }
    val actualWeek = ScheduleDates.weekIndexAt(firstWeekDate, clock) ?: 1
    val actualDay = ScheduleDates.dayAt(clock)
    val firstWeek = minOf(1, actualWeek)
    val lastWeek = maxOf(ScheduleMaxWeeks, actualWeek)
    val dayView = displayPreferences.dayView
    val firstPageUnit = if (dayView) (firstWeek - 1) * 7 + 1 else firstWeek
    val lastPageUnit = if (dayView) lastWeek * 7 else lastWeek
    val requestedUnit = if (dayView) (currentWeek - 1) * 7 + selectedDay else currentWeek
    val pager = key(positionKey, firstWeekDate, dayView, firstWeek, lastWeek) {
        rememberPagerState(initialPage = (requestedUnit - firstPageUnit).coerceIn(0, lastPageUnit - firstPageUnit),
            pageCount = { lastPageUnit - firstPageUnit + 1 })
    }
    var dateRequest by remember { mutableIntStateOf(0) }
    val requestKey = if (weekRequestKey == null && dateRequest == 0) null else "$weekRequestKey|date:$dateRequest"
    val latestUnit by rememberUpdatedState(requestedUnit)
    val latestKey by rememberUpdatedState(requestKey)
    val latestWeekChange by rememberUpdatedState(onWeekChange)
    val latestDayChange by rememberUpdatedState(onDayChange)
    val sync = remember(pager) { ScheduleWeekPagerSync(requestedUnit, requestKey, firstPageUnit, lastPageUnit) }
    val gridScroll = rememberSaveable(positionKey, positionCalendar, stateSaver = ScrollState.Saver) {
        mutableStateOf(ScrollState(restoredPosition?.takeIf { it.calendar == positionCalendar }?.weekScroll ?: 0))
    }.value
    val dayScroll = rememberSaveable(positionKey, positionCalendar, stateSaver = ScrollState.Saver) {
        mutableStateOf(ScrollState(restoredPosition?.takeIf { it.calendar == positionCalendar }?.dayScroll ?: 0))
    }.value
    RestoreScheduleScroll(gridScroll)
    RestoreScheduleScroll(dayScroll)
    val headerLiftDistance = with(LocalDensity.current) { 64.dp.toPx() }
    val headerLift by remember(dayView, gridScroll, dayScroll, headerLiftDistance) {
        derivedStateOf {
            ((if (dayView) dayScroll.value else gridScroll.value) / headerLiftDistance).coerceIn(0f, 1f)
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { Triple(latestUnit to latestKey, pager.isScrollInProgress, pager.settledPage) }
            .collect { (request, scrolling, page) ->
                val target = sync.requestPage(request.first, request.second)
                if (target != null) {
                    pager.scrollToPage(target)
                    sync.settledWeek(target)
                } else if (!scrolling) sync.settledWeek(page)?.let { unit ->
                    if (dayView) {
                        val day = Math.floorMod(unit - 1, 7) + 1
                        latestWeekChange(Math.floorDiv(unit - 1, 7) + 1)
                        latestDayChange(day)
                        dayScroll.scrollTo(0)
                    } else latestWeekChange(unit)
                }
            }
    }
    val latestPosition by rememberUpdatedState(ScheduleViewPosition(currentWeek, selectedDay,
        gridScroll.value, dayScroll.value, positionCalendar))
    val savePosition by rememberUpdatedState(onPositionChange)
    LaunchedEffect(positionKey) { snapshotFlow { latestPosition }.collect { savePosition(it) } }
    DisposableEffect(positionKey) { onDispose { onPositionChange(latestPosition) } }
    val shownUnit = pager.currentPage + firstPageUnit
    val shownWeek = if (dayView) Math.floorDiv(shownUnit - 1, 7) + 1 else shownUnit
    val shownDay = if (dayView) Math.floorMod(shownUnit - 1, 7) + 1 else selectedDay
    fun selectWeek(week: Int) {
        onWeekChange(week); dateRequest++
        if (dayView) scope.launch { dayScroll.scrollTo(0) }
    }
    fun today() {
        scope.launch { gridScroll.scrollTo(0); dayScroll.scrollTo(0) }
        onTodayClick()
    }
    val contentAlpha = remember { Animatable(1f) }
    LaunchedEffect(dayView, reduced) {
        if (reduced) contentAlpha.snapTo(1f) else {
            contentAlpha.snapTo(0.65f)
            contentAlpha.animateTo(1f, tween(200))
        }
    }
    val wallpaper = LocalAppBackdrop.current
    val contentBackdrop = if (wallpaper != null && isBackdropSupported()) rememberLayerBackdrop() else null
    val sample = if (wallpaper != null && contentBackdrop != null) rememberCombinedBackdrop(wallpaper, contentBackdrop) else null
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + scheduleHeaderHeight()
    Scaffold(containerColor = Color.Transparent, topBar = {
        Column(Modifier.reportNoticeAnchor()) {
            WeekHeaderCompact(shownWeek, { selectWeek((shownWeek - 1).coerceAtLeast(1)) },
                { selectWeek((shownWeek + 1).coerceAtMost(ScheduleMaxWeeks)) },
                onSettingsClick, onExportClick, isNextSemester, onToggleSemester,
                collapseFraction = headerLift,
                sampleBackdrop = sample, firstWeekDate = firstWeekDate, actualWeek = actualWeek,
                showWeekend = dayView || displayPreferences.showWeekend, selectedDay = shownDay,
                onDayClick = { onWeekChange(shownWeek); onDayChange(it); dateRequest++
                    scope.launch { dayScroll.scrollTo(0) }
                    onDisplayPreferences(displayPreferences.copy(dayView = true)) },
                dayView = dayView, onDayView = { if (it != dayView) onDisplayPreferences(displayPreferences.copy(dayView = it)) },
                onWeekSelect = ::selectWeek, onTodayClick = ::today,
                showToday = isNextSemester || shownWeek != actualWeek || shownDay != actualDay,
                onSyncClick = onRetry, onAddClick = onAddClick, onWidgetClick = onWidgetClick, now = clock)
        }
    }) { padding ->
        when {
            isLoading && courses.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                GlassLoadingState(text = "正在同步课表…")
            }
            errorMessage.isNotBlank() && courses.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                ScheduleNotice(errorMessage, "重新同步", onRetry)
            }
            else -> HorizontalPager(pager, modifier = Modifier.fillMaxSize().testTag("schedule-pager")
                .graphicsLayer { alpha = contentAlpha.value }
                .then(if (contentBackdrop != null) Modifier.layerBackdrop(contentBackdrop) else Modifier),
                flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = tween(if (reduced) 0 else 200)),
                beyondViewportPageCount = 0) { page ->
                val unit = page + firstPageUnit
                val week = if (dayView) Math.floorDiv(unit - 1, 7) + 1 else unit
                val day = if (dayView) Math.floorMod(unit - 1, 7) + 1 else selectedDay
                val visible = remember(courses, week) { courses.filter { isInWeek(it.weeks, week) } }
                val conflicts = remember(visible) { scheduleOverlapGroups(visible.map { it.record() })
                    .filter { it.courses.size > 1 }.flatMap { it.courses }.map { it.id }.toSet() }
                val displayed = remember(courses, conflicts, agenda, week, actualWeek, isNextSemester) {
                    val liveIds = agenda.current.map { it.course.id }.toSet()
                    val nextId = agenda.next?.takeIf { next -> agenda.today.any { it.startsAt == next.startsAt && it.course.id == next.course.id } }?.course?.id
                    courses.map { it.copy(hasConflict = it.id in conflicts,
                        isCurrent = !isNextSemester && week == actualWeek && it.id in liveIds,
                        isNext = !isNextSemester && week == actualWeek && it.id == nextId) }
                }
                if (dayView) ScheduleDayList(displayed, week, day, firstWeekDate, periodTimes,
                    displayPreferences.compact, dayScroll, topInset, onCourseClick,
                    onCourseLongClick = onCourseLongClick, agenda = agenda, now = clock,
                    isToday = !isNextSemester && week == actualWeek && day == actualDay,
                    onCalendar = onSettingsClick)
                else ScheduleGrid(displayed, week, periodTimes, periodCount, onCourseClick,
                    scrollState = gridScroll, topInset = topInset, showWeekend = displayPreferences.showWeekend,
                    compact = displayPreferences.compact, onCourseLongClick = onCourseLongClick,
                    beforeGrid = { if (agenda.needsCalendar) ScheduleNotice("设置开学日期，让课程对应实际日期", "设置开学日期", onSettingsClick) })
            }
        }
    }
}

/** Insets and cached courses can arrive after the first measurement on recreation. */
@Composable
private fun RestoreScheduleScroll(scroll: ScrollState) {
    val target = remember(scroll) { scroll.value }
    LaunchedEffect(scroll) {
        if (target <= 0) return@LaunchedEffect
        coroutineScope {
            val restore = launch {
                snapshotFlow { scroll.maxValue }.first { maximum ->
                    if (maximum == Int.MAX_VALUE) false else {
                        scroll.scrollTo(target)
                        maximum >= target
                    }
                }
            }
            // A user gesture always takes precedence over pending restoration.
            val interaction = launch {
                scroll.interactionSource.interactions.first { it is DragInteraction.Start }
                restore.cancel()
            }
            restore.join()
            interaction.cancel()
        }
    }
}

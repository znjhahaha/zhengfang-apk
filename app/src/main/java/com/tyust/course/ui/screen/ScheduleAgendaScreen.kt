package com.tyust.course.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.togetherWith
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
    val dayView = displayPreferences.dayView
    var dateRequest by remember { mutableIntStateOf(0) }
    var weekOffset by rememberSaveable(positionKey) {
        mutableIntStateOf(restoredPosition?.takeIf { it.calendar == positionCalendar }?.weekScroll ?: 0)
    }
    var dayOffset by rememberSaveable(positionKey) {
        mutableIntStateOf(restoredPosition?.takeIf { it.calendar == positionCalendar }?.dayScroll ?: 0)
    }
    // A mode/page owns exactly one scroll container, including during a crossfade.
    val pageScrolls = remember(positionKey) { mutableMapOf<Pair<Boolean, Int>, ScrollState>() }
    val restoredScrolls = remember(positionKey) { mutableSetOf<ScrollState>() }
    var activeScroll by remember(positionKey) { mutableStateOf<ScrollState?>(null) }
    var shownWeek by remember(positionKey) { mutableIntStateOf(currentWeek) }
    var shownDay by remember(positionKey) { mutableIntStateOf(selectedDay) }
    val latestPosition by rememberUpdatedState(ScheduleViewPosition(currentWeek, selectedDay, weekOffset, dayOffset, positionCalendar))
    val savePosition by rememberUpdatedState(onPositionChange)
    LaunchedEffect(positionKey) { snapshotFlow { latestPosition }.collect { savePosition(it) } }
    DisposableEffect(positionKey) { onDispose { savePosition(latestPosition) } }
    val headerLiftDistance = with(LocalDensity.current) { 64.dp.toPx() }
    val headerLift by remember(activeScroll, headerLiftDistance) {
        derivedStateOf { ((activeScroll?.value ?: 0) / headerLiftDistance).coerceIn(0f, 1f) }
    }
    fun selectWeek(week: Int) { onWeekChange(week); dateRequest++ }
    fun today() {
        weekOffset = 0; dayOffset = 0
        val scrolls = pageScrolls.values.toList()
        restoredScrolls += scrolls
        scope.launch { scrolls.forEach { it.scrollTo(0) } }
        onTodayClick()
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
                collapseFraction = headerLift, sampleBackdrop = sample, firstWeekDate = firstWeekDate,
                actualWeek = actualWeek, showWeekend = dayView || displayPreferences.showWeekend, selectedDay = shownDay,
                onDayClick = {
                    onWeekChange(shownWeek); onDayChange(it); dateRequest++
                    onDisplayPreferences(displayPreferences.copy(dayView = true))
                },
                dayView = dayView, onDayView = {
                    if (it != dayView) {
                        // Switching the presentation keeps the date the user is looking at.
                        onWeekChange(shownWeek); onDayChange(shownDay)
                        onDisplayPreferences(displayPreferences.copy(dayView = it))
                    }
                },
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
            else -> Box(Modifier.fillMaxSize().then(
                if (contentBackdrop != null) Modifier.layerBackdrop(contentBackdrop) else Modifier)) {
                androidx.compose.animation.AnimatedContent(dayView, modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        if (reduced) (androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None)
                        else ((androidx.compose.animation.fadeIn(tween(com.tyust.course.ui.theme.MotionDuration.Medium)) +
                            androidx.compose.animation.slideInVertically(tween(com.tyust.course.ui.theme.MotionDuration.Medium)) { if (targetState) it / 40 else -it / 40 }) togetherWith
                            (androidx.compose.animation.fadeOut(tween(com.tyust.course.ui.theme.MotionDuration.Fast)) +
                                androidx.compose.animation.slideOutVertically(tween(com.tyust.course.ui.theme.MotionDuration.Medium)) { if (targetState) -it / 40 else it / 40 }))
                            .using(null)
                    }, label = "schedule-view") { mode ->
                    SchedulePages(mode, mode == dayView, currentWeek, selectedDay,
                        "$weekRequestKey|$dateRequest", firstWeekDate, actualWeek, isNextSemester,
                        courses, periodTimes, periodCount, displayPreferences, agenda, clock, topInset,
                        pageScrolls, restoredScrolls, if (mode) dayOffset else weekOffset, reduced,
                        onWeekChange, onDayChange, onCourseClick, onCourseLongClick, onSettingsClick,
                        onShown = { week, day, scroll -> shownWeek = week; shownDay = day; activeScroll = scroll },
                        onScroll = { if (mode) dayOffset = it else weekOffset = it })
                }
            }
        }
    }
}

/** The outgoing presentation cannot write the incoming presentation's date or scroll state. */
@Composable
private fun SchedulePages(
    dayView: Boolean, active: Boolean, requestedWeek: Int, requestedDay: Int, requestKey: String,
    firstWeekDate: String?, actualWeek: Int, nextSemester: Boolean,
    courses: List<ScheduleCourseUi>, times: List<PeriodTimeUi>, periodCount: Int,
    preferences: ScheduleDisplayPreferences, agenda: ScheduleAgenda, now: Long, topInset: androidx.compose.ui.unit.Dp,
    scrolls: MutableMap<Pair<Boolean, Int>, ScrollState>, restored: MutableSet<ScrollState>, initialOffset: Int,
    reduced: Boolean, onWeek: (Int) -> Unit, onDay: (Int) -> Unit,
    onCourse: (ScheduleCourseUi) -> Unit, onLongClick: (ScheduleCourseUi) -> Unit, onCalendar: () -> Unit,
    onShown: (Int, Int, ScrollState) -> Unit, onScroll: (Int) -> Unit
) {
    val firstWeek = minOf(1, actualWeek, requestedWeek)
    val lastWeek = maxOf(ScheduleMaxWeeks, actualWeek, requestedWeek)
    val firstUnit = if (dayView) (firstWeek - 1) * 7 + 1 else firstWeek
    val lastUnit = if (dayView) lastWeek * 7 else lastWeek
    val requestedUnit = if (dayView) (requestedWeek - 1) * 7 + requestedDay else requestedWeek
    val pager = key(firstUnit, lastUnit, firstWeekDate) {
        rememberPagerState(initialPage = (requestedUnit - firstUnit).coerceIn(0, lastUnit - firstUnit),
            pageCount = { lastUnit - firstUnit + 1 })
    }
    var navigating by remember(pager) { mutableStateOf(false) }
    val latestActive by rememberUpdatedState(active)
    val latestWeek by rememberUpdatedState(onWeek)
    val latestDay by rememberUpdatedState(onDay)
    val latestShown by rememberUpdatedState(onShown)
    val latestScroll by rememberUpdatedState(onScroll)
    val selectedDay by rememberUpdatedState(requestedDay)
    val initialUnit = remember(pager) { requestedUnit }
    fun pageScroll(unit: Int) = scrolls.getOrPut(dayView to unit) {
        ScrollState(if (!dayView || unit == initialUnit) initialOffset else 0)
    }
    LaunchedEffect(pager, requestedUnit, requestKey, active) {
        if (!active) return@LaunchedEffect
        val target = (requestedUnit - firstUnit).coerceIn(0, pager.pageCount - 1)
        if (pager.currentPage == target && pager.currentPageOffsetFraction == 0f) return@LaunchedEffect
        navigating = true
        try {
            if (reduced) pager.scrollToPage(target)
            else pager.animateScrollToPage(target, animationSpec = tween(com.tyust.course.ui.theme.MotionDuration.Medium,
                easing = com.tyust.course.ui.theme.MotionEasing.Standard))
        } finally { navigating = false }
    }
    LaunchedEffect(pager) {
        var userScrolling = false
        snapshotFlow { Triple(pager.isScrollInProgress, navigating, pager.settledPage) }.collect { (scrolling, programmed, page) ->
            // Initial/restored pager positions are observations, not a new date
            // request. Only a user scroll may write a settled date back upstream.
            if (!latestActive || programmed) userScrolling = false
            else if (scrolling) userScrolling = true
            else if (userScrolling) {
                userScrolling = false
                val unit = page + firstUnit
                if (dayView) {
                    latestWeek(Math.floorDiv(unit - 1, 7) + 1)
                    latestDay(Math.floorMod(unit - 1, 7) + 1)
                } else latestWeek(unit)
            }
        }
    }
    val shownUnit = pager.currentPage + firstUnit
    val shownWeek = if (dayView) Math.floorDiv(shownUnit - 1, 7) + 1 else shownUnit
    val shownDay = if (dayView) Math.floorMod(shownUnit - 1, 7) + 1 else requestedDay
    val activeScroll = pageScroll(pager.settledPage + firstUnit)
    LaunchedEffect(active, activeScroll, shownWeek, shownDay) {
        if (active) latestShown(shownWeek, shownDay, activeScroll)
    }
    LaunchedEffect(active, activeScroll) {
        if (active) snapshotFlow { activeScroll.value }.collect { latestScroll(it) }
    }
    HorizontalPager(pager, modifier = Modifier.fillMaxSize().testTag("schedule-pager"),
        overscrollEffect = null,
        flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = tween(if (reduced) 0 else com.tyust.course.ui.theme.MotionDuration.Medium)),
        beyondViewportPageCount = 0) { page ->
        val unit = page + firstUnit
        val week = if (dayView) Math.floorDiv(unit - 1, 7) + 1 else unit
        val day = if (dayView) Math.floorMod(unit - 1, 7) + 1 else requestedDay
        val scroll = pageScroll(unit)
        RestoreScheduleScroll(scroll, restored)
        val visible = remember(courses, week) { courses.filter { isInWeek(it.weeks, week) } }
        val conflicts = remember(visible) { scheduleOverlapGroups(visible.map { it.record() })
            .filter { it.courses.size > 1 }.flatMap { it.courses }.map { it.id }.toSet() }
        val displayed = remember(courses, conflicts, agenda, week, actualWeek, nextSemester) {
            val current = agenda.current.map { it.course.id }.toSet()
            val next = agenda.next?.takeIf { next -> agenda.today.any { it.startsAt == next.startsAt && it.course.id == next.course.id } }?.course?.id
            courses.map { it.copy(hasConflict = it.id in conflicts,
                isCurrent = !nextSemester && week == actualWeek && it.id in current,
                isNext = !nextSemester && week == actualWeek && it.id == next) }
        }
        if (dayView) ScheduleDayList(displayed, week, day, firstWeekDate, times,
            preferences.compact, scroll, topInset, onCourse, onCourseLongClick = onLongClick,
            agenda = agenda, now = now, isToday = !nextSemester && week == actualWeek && day == ScheduleDates.dayAt(now), onCalendar = onCalendar)
        else ScheduleGrid(displayed, week, times, periodCount, onCourse, scrollState = scroll,
            topInset = topInset, showWeekend = preferences.showWeekend, compact = preferences.compact,
            onCourseLongClick = onLongClick)
    }
}

/** One initial restoration per container; after a drag starts there is no later compensation. */
@Composable
private fun RestoreScheduleScroll(scroll: ScrollState, restored: MutableSet<ScrollState>) {
    val target = remember(scroll) { scroll.value }
    LaunchedEffect(scroll) {
        if (scroll in restored) return@LaunchedEffect
        coroutineScope {
            val restore = launch {
                snapshotFlow { scroll.viewportSize > 0 && scroll.maxValue != Int.MAX_VALUE && scroll.maxValue >= target }.first { it }
                withFrameNanos { }
                if (scroll !in restored) scroll.scrollTo(target.coerceIn(0, scroll.maxValue))
            }
            val interaction = launch {
                scroll.interactionSource.interactions.first { it is DragInteraction.Start }
                restore.cancel()
            }
            try { restore.join() } finally {
                interaction.cancel()
                restored += scroll
            }
        }
    }
}

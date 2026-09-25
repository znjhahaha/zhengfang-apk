package com.tyust.course.ui.route

import com.tyust.course.ui.system.GlassToaster
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.tyust.course.schedule.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentActivity
import com.tyust.course.demo.DemoData
import com.tyust.course.academic.AcademicGatewayFactory
import com.tyust.course.academic.AcademicStudyBridge
import com.tyust.course.manager.ScheduleSettingsManager
import com.tyust.course.manager.UserManager
import com.tyust.course.network.CourseApiClient
import com.tyust.course.ui.screen.PeriodTimeUi
import com.tyust.course.ui.screen.ScheduleCourseUi
import com.tyust.course.ui.screen.ScheduleScreen
import com.tyust.course.ui.screen.ScheduleSettingsScreen
import com.tyust.course.ui.system.DisablePlatformDialogDim
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.theme.MotionDuration
import com.tyust.course.ui.theme.MotionEasing
import com.tyust.course.ui.theme.MotionSpring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar
import com.tyust.course.utils.ICalExporter

private const val ScheduleRouteSnapshotMaxAgeMs = 5 * 60 * 1000L

/** 保留课表数据状态，但页面 Composition 与玻璃图层仍在切走时立即释放。 */
private data class ScheduleRouteSnapshot(
    val browsingSession: String,
    val savedAtMs: Long,
    val currentWeek: Int,
    val courses: List<ScheduleCourseUi>,
    val periodTimes: List<PeriodTimeUi>,
    val periodCount: Int,
    val isNextSemester: Boolean,
    val termId: String = "",
    val appliedCalendar: String? = null
)

private object ScheduleRouteMemoryCache {
    private val snapshots = mutableMapOf<String, ScheduleRouteSnapshot>()

    fun get(accountKey: String, browsingSession: String): ScheduleRouteSnapshot? = snapshots[accountKey]?.takeIf {
        it.browsingSession == browsingSession && System.currentTimeMillis() - it.savedAtMs <= ScheduleRouteSnapshotMaxAgeMs
    }

    fun put(accountKey: String, snapshot: ScheduleRouteSnapshot) {
        snapshots[accountKey] = snapshot
    }
}

@Composable
fun ScheduleRoute(isActive: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDemoMode = remember { UserManager.getInstance().isDemoMode }
    val routeAccountKey = remember { UserManager.getInstance().currentAccountStorageKey }
    val browsingSession = rememberSaveable(routeAccountKey) { java.util.UUID.randomUUID().toString() }
    val sessions = UserManager.getInstance().sessionState
    val syncModel: ScheduleSyncViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val session by sessions.state.collectAsState()
    val requests = remember { com.tyust.course.manager.SessionRequestGate(sessions) }
    DisposableEffect(requests) { onDispose { requests.cancelAll() } }
    val restoredSnapshot = remember(routeAccountKey) {
        ScheduleRouteMemoryCache.get(routeAccountKey, browsingSession)
    }
    var hasInitializedRoute by remember(routeAccountKey) {
        mutableStateOf(restoredSnapshot != null)
    }
    var hasLocalSchedule by remember(routeAccountKey) { mutableStateOf(restoredSnapshot != null) }
    
    // State
    var currentWeek by rememberSaveable(routeAccountKey) {
        mutableIntStateOf(restoredSnapshot?.currentWeek ?: 1)
    }
    var courses by remember(routeAccountKey) {
        mutableStateOf(restoredSnapshot?.courses ?: emptyList())
    }
    var isLoading by remember { mutableStateOf(false) }
    var backgroundRefreshing by remember { mutableStateOf(false) }
    var loadError by remember(routeAccountKey) { mutableStateOf("") }
    var studyLoadJob by remember(routeAccountKey) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var studyGeneration by remember(routeAccountKey) { mutableIntStateOf(0) }
    var periodTimes by remember(routeAccountKey) {
        mutableStateOf(restoredSnapshot?.periodTimes ?: emptyList())
    }
    var periodCount by remember(routeAccountKey) {
        mutableIntStateOf(restoredSnapshot?.periodCount ?: 12)
    }
    var isNextSemester by rememberSaveable(routeAccountKey) {
        mutableStateOf(restoredSnapshot?.isNextSemester ?: false)
    }
    val displayStore = remember(context, browsingSession) {
        ScheduleDisplayStore(context.getSharedPreferences("schedule_display", android.content.Context.MODE_PRIVATE), browsingSession)
    }
    var displayPreferences by remember(routeAccountKey) { mutableStateOf(displayStore.read(routeAccountKey)) }
    var selectedDay by rememberSaveable(routeAccountKey) {
        mutableIntStateOf(displayStore.position(routeAccountKey, restoredSnapshot?.termId.orEmpty())?.day
            ?: (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1)
    }
    var pendingToday by rememberSaveable(routeAccountKey) { mutableStateOf(false) }
    var todayRequest by rememberSaveable(routeAccountKey) { mutableIntStateOf(0) }
    
    // Dialog State
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var detailId by rememberSaveable(routeAccountKey) { mutableStateOf<String?>(null) }
    var quickCourseId by rememberSaveable(routeAccountKey) { mutableStateOf<String?>(null) }
    var detailSourceBounds by remember(routeAccountKey) { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var editingId by rememberSaveable(routeAccountKey) { mutableStateOf<String?>(null) }
    var resolvedTermId by rememberSaveable(routeAccountKey) { mutableStateOf(restoredSnapshot?.termId.orEmpty()) }
    var appliedCalendar by rememberSaveable(routeAccountKey) { mutableStateOf(restoredSnapshot?.appliedCalendar) }
    var notificationCourseJson by rememberSaveable(routeAccountKey) { mutableStateOf<String?>(null) }
    var settingsTermOverride by rememberSaveable(routeAccountKey) { mutableStateOf<String?>(null) }
    var deletedCourseJson by rememberSaveable(routeAccountKey) { mutableStateOf<String?>(null) }
    var deletedRemindersJson by rememberSaveable(routeAccountKey) { mutableStateOf("[]") }
    var undoDeadline by rememberSaveable(routeAccountKey) { mutableLongStateOf(0L) }
    
    // Managers
    val settingsManager = remember { ScheduleSettingsManager.getInstance().apply { init(context) } }
    val scheduleCache = remember(context) {
        ScheduleCacheStore(context.getSharedPreferences("schedule_cache", android.content.Context.MODE_PRIVATE))
    }
    val scheduleRepository = remember(context) { ScheduleRepository(context) }
    val routeSchoolId = UserManager.getInstance().currentSchool?.id.orEmpty()
    val actualTermId = if (isDemoMode) DemoData.currentTerm.id else scheduleCache.currentTerm(routeAccountKey, routeSchoolId).id
    val savedPosition = remember(routeAccountKey, resolvedTermId) { displayStore.position(routeAccountKey, resolvedTermId) }
    val reminderScheduler = remember(context) { ScheduleReminderScheduler.get(context) }
    val customRevision = settingsManager.revision
    val customCourses = remember(customRevision, routeAccountKey) { settingsManager.getCustomCourses(routeAccountKey) }
    val remindersRevision = reminderScheduler.revision
    val settingsTerm = settingsTermOverride ?: resolvedTermId
    val termTimeBase = remember(settingsTerm, remindersRevision, customRevision, actualTermId) {
        scheduleRepository.timeBase(routeAccountKey, settingsTerm, actualTermId)
    }
    val displayedTimeBase = remember(resolvedTermId, remindersRevision, customRevision, actualTermId) {
        scheduleRepository.timeBase(routeAccountKey, resolvedTermId, actualTermId)
    }
    fun periodTimesFor(base: ScheduleTimeBase?): List<ScheduleSettingsManager.PeriodTime> = settingsManager.getPeriodTimes().map {
        it.copy(startTime = base?.periodStarts?.get(it.period) ?: it.startTime, endTime = base?.periodEnds?.get(it.period) ?: it.endTime)
    }
    val focusRegistry = remember { com.tyust.course.ui.screen.ScheduleFocusRegistry() }
    val reminderRequest = CourseReminderNavigation.requestedId
    LaunchedEffect(reminderRequest) {
        if (reminderRequest != null) {
            detailSourceBounds = null
            reminderScheduler.findById(reminderRequest)?.let {
                notificationCourseJson = ReminderJson.reminder(it).toString()
                detailId = it.course.id
            }
            CourseReminderNavigation.consume()
        }
    }

    val snapshotForCache = ScheduleRouteSnapshot(
        browsingSession = browsingSession,
        savedAtMs = System.currentTimeMillis(),
        currentWeek = currentWeek,
        courses = courses,
        periodTimes = periodTimes,
        periodCount = periodCount,
        isNextSemester = isNextSemester,
        termId = resolvedTermId,
        appliedCalendar = appliedCalendar
    )
    val latestSnapshotForCache by rememberUpdatedState(snapshotForCache)
    val canCacheSnapshot by rememberUpdatedState(hasInitializedRoute && !isLoading && loadError.isBlank())
    DisposableEffect(routeAccountKey) {
        onDispose {
            if (canCacheSnapshot) {
                ScheduleRouteMemoryCache.put(routeAccountKey, latestSnapshotForCache)
            }
        }
    }
    
    // Colors
    val courseColors = remember {
        listOf(
            Color(0xFF5C6BC0), Color(0xFF42A5F5), Color(0xFF66BB6A), Color(0xFFFFA726),
            Color(0xFFAB47BC), Color(0xFFEF5350), Color(0xFF26C6DA), Color(0xFF8D6E63)
        )
    }

    fun parseSchedule(json: String): List<ScheduleCourseUi>? = ScheduleJson.parse(json)?.map { entry ->
        val c = entry.course
        ScheduleCourseUi(c.name, c.teacher, c.location, c.day, c.startPeriod, c.endPeriod, c.weeks,
            courseColors[ScheduleIdentity.colorIndex(c.id, courseColors.size)], sourceId = entry.sourceId, id = c.id)
    }

    fun reloadCustomCourses(currentList: List<ScheduleCourseUi>): List<ScheduleCourseUi> {
        val existing = currentList.associateBy { it.id }
        return ScheduleRepository.mergeCustom(currentList.map { it.record() }, settingsManager.getCustomCourses(routeAccountKey)).map { c ->
            if (!c.custom) existing.getValue(c.id) else ScheduleCourseUi(c.name, c.teacher, c.location, c.day,
                c.startPeriod, c.endPeriod, c.weeks, courseColors[ScheduleIdentity.colorIndex(c.id, courseColors.size)],
                isCustom = true, customId = c.id.removePrefix("custom:"), id = c.id)
        }
    }

    // Paint local data first. Network work lives in the Activity ViewModel across tab transitions.
    val loadSchedule = remember(isNextSemester, session.token) {
        fun(manual: Boolean) {
            if (isDemoMode) {
                resolvedTermId = (if (isNextSemester) DemoData.currentTerm.next() else DemoData.currentTerm).id
                courses = reloadCustomCourses(DemoData.scheduleCourses())
                hasLocalSchedule = true
                isLoading = false
                return
            }
            val user = UserManager.getInstance()
            val school = user.currentSchool ?: return
            val ticket = requests.begin("schedule")
            studyLoadJob?.cancel()
            val generation = ++studyGeneration
            val account = user.currentAccountStorageKey
            val currentTerm = scheduleCache.currentTerm(account, school.id)
            val requestedTerm = if (isNextSemester) runCatching { currentTerm.next() }.getOrDefault(currentTerm) else currentTerm
            val cached = scheduleCache.selected(account, school.id, isNextSemester)
            loadError = ""
            if (resolvedTermId != requestedTerm.id) {
                courses = reloadCustomCourses(emptyList())
                hasLocalSchedule = false
                resolvedTermId = requestedTerm.id
            }
            if (cached != null) {
                courses = reloadCustomCourses(requireNotNull(parseSchedule(cached.json)))
                resolvedTermId = cached.term.id
                hasLocalSchedule = true
                reminderScheduler.updateSnapshot(account, cached.term.id, courses.map { it.record() })
            }
            val retained = hasLocalSchedule || courses.isNotEmpty()
            isLoading = !retained
            backgroundRefreshing = retained
            val mode = if (manual) ScheduleLoadMode.Manual else ScheduleLoadMode.Automatic
            val feedback = if (manual) com.tyust.course.manager.RequestFeedback.Interactive else com.tyust.course.manager.RequestFeedback.Silent
            val nextSemester = isNextSemester
            studyLoadJob = scope.launch {
                try {
                    val loaded = syncModel.refresh.load(ScheduleRefreshKey(ticket.session, school.id, requestedTerm.id), mode) {
                        try {
                            val fresh = if (AcademicGatewayFactory.supports(school)) withContext(Dispatchers.IO) {
                                scheduleCache.load(account, school.id, nextSemester, true) {
                                    AcademicStudyBridge.reader(school, account, ticket.session)
                                }
                            } else fetchLegacySchedule(school, currentTerm, requestedTerm, ticket.session, feedback)
                            if (!sessions.isCurrent(ticket.session) || user.currentSchool?.id != school.id)
                                throw CancellationException("Session replaced")
                            scheduleCache.save(account, school.id, fresh)
                            fresh.calendar?.let { settingsManager.applyProviderCalendar(account, it) }
                            // Widgets and reminders receive fresh data even if the user already left this tab.
                            val merged = ScheduleRepository.mergeCustom(requireNotNull(ScheduleJson.parse(fresh.json)).map { it.course },
                                settingsManager.getCustomCourses(account))
                            reminderScheduler.updateSnapshot(account, fresh.term.id, merged)
                            fresh
                        } catch (e: com.tyust.course.academic.AcademicException) {
                            if (e.status == com.tyust.course.academic.AcademicStatus.SESSION_EXPIRED)
                                CourseApiClient.getInstance().notifyCookieExpired(ticket.session, feedback)
                            throw e
                        }
                    }
                    if (!requests.isCurrent(ticket) || studyGeneration != generation) return@launch
                    courses = reloadCustomCourses(requireNotNull(parseSchedule(loaded.json)))
                    resolvedTermId = loaded.term.id
                    hasLocalSchedule = true
                    if (manual) GlassToaster.show("已同步课表")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (requests.isCurrent(ticket) && studyGeneration == generation) {
                        val message = e.message ?: "课表同步失败，请重试"
                        if (!retained) loadError = message
                        if (manual) {
                            if (e is com.tyust.course.academic.AcademicException &&
                                e.status == com.tyust.course.academic.AcademicStatus.SESSION_EXPIRED)
                                CourseApiClient.getInstance().notifyCookieExpired(ticket.session,
                                    com.tyust.course.manager.RequestFeedback.Interactive)
                            GlassToaster.show(if (retained) "同步失败，已保留本地课表" else message)
                        }
                    }
                } finally {
                    if (requests.isCurrent(ticket) && studyGeneration == generation) {
                        isLoading = false
                        backgroundRefreshing = false
                    }
                }
            }
        }
    }
    // Init Effect
    LaunchedEffect(routeAccountKey) {
        if (restoredSnapshot == null) {
            periodCount = settingsManager.periodCount
            periodTimes = settingsManager.getPeriodTimes().map {
                PeriodTimeUi(it.period, it.startTime, it.endTime)
            }
        }
    }
    
    // 监听学期切换并重新加载
    LaunchedEffect(isActive, isNextSemester, session.token) {
        if (!isActive) return@LaunchedEffect
        hasInitializedRoute = true
        loadSchedule(false)
    }

    // Refresh custom courses when dialogs close
    LaunchedEffect(customRevision, routeAccountKey) {
        courses = reloadCustomCourses(courses)
        periodCount = settingsManager.periodCount
    }
    LaunchedEffect(resolvedTermId) {
        if (resolvedTermId.isNotBlank() && !isNextSemester) {
            reminderScheduler.migrateLegacyTimeBase(routeAccountKey, resolvedTermId, ScheduleTimeBase(
                ScheduleTimeBase.dateFromMillis(settingsManager.semesterStartDate), periodTimes.associate { it.period to it.startTime },
                periodTimes.associate { it.period to it.endTime }))
        }
    }
    LaunchedEffect(displayedTimeBase, customRevision) {
        periodTimes = periodTimesFor(displayedTimeBase).map { PeriodTimeUi(it.period, it.startTime, it.endTime) }
    }
    LaunchedEffect(resolvedTermId, displayedTimeBase.firstWeekDate, pendingToday, isNextSemester) {
        if (resolvedTermId.isBlank()) return@LaunchedEffect
        val calendar = "$resolvedTermId|${displayedTimeBase?.firstWeekDate.orEmpty()}"
        // Apply date changes when saved, including system-back dismissal. Retain a
        // browsed week across page restoration and unrelated reminder/time changes.
        if (pendingToday && !isNextSemester && resolvedTermId == actualTermId) {
            currentWeek = ScheduleDates.weekIndexAt(displayedTimeBase.firstWeekDate, System.currentTimeMillis()) ?: 1
            selectedDay = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
            appliedCalendar = calendar
            todayRequest++
            pendingToday = false
        } else if (appliedCalendar != calendar) {
            val position = displayStore.position(routeAccountKey, resolvedTermId)?.takeIf { it.calendar == calendar }
            val previous = appliedCalendar?.split('|', limit = 2)
            val browsedDate = previous?.takeIf { it.first() == resolvedTermId }?.getOrNull(1)?.let {
                ScheduleDates.date(it, currentWeek, selectedDay)?.timeInMillis
            }
            appliedCalendar = calendar
            // Provider calendar corrections keep the civil date the user is browsing.
            currentWeek = browsedDate?.let { ScheduleDates.weekIndexAt(displayedTimeBase.firstWeekDate, it) }
                ?: position?.week ?: if (isNextSemester) 1 else ScheduleDates.weekIndexAt(displayedTimeBase.firstWeekDate, System.currentTimeMillis()) ?: 1
            selectedDay = browsedDate?.let(ScheduleDates::dayAt) ?: position?.day ?: ScheduleDates.dayAt(System.currentTimeMillis())
        }
    }
    LaunchedEffect(undoDeadline) {
        if (undoDeadline > 0) {
            kotlinx.coroutines.delay((undoDeadline - System.currentTimeMillis()).coerceAtLeast(0))
            deletedCourseJson = null
            deletedRemindersJson = "[]"
        }
    }
    val widgetRequest = ScheduleWidgetNavigation.requested
    LaunchedEffect(widgetRequest, resolvedTermId, isNextSemester, isLoading) {
        val request = widgetRequest ?: return@LaunchedEffect
        if (!request.matches(routeAccountKey, routeSchoolId)) {
            ScheduleWidgetNavigation.consume()
            return@LaunchedEffect
        }
        if (isNextSemester) { isNextSemester = false; pendingToday = true; return@LaunchedEffect }
        if (resolvedTermId.isBlank() || isLoading) return@LaunchedEffect
        displayPreferences = displayPreferences.copy(dayView = true)
        displayStore.write(routeAccountKey, displayPreferences)
        pendingToday = request.course == null
        if (request.course != null) {
            if (request.term == resolvedTermId && courses.any { it.id == request.course }) {
                notificationCourseJson = null
                detailSourceBounds = null
                detailId = request.course
                request.startsAt?.let { startsAt ->
                    currentWeek = ScheduleDates.weekIndexAt(displayedTimeBase.firstWeekDate, startsAt) ?: currentWeek
                    selectedDay = courses.first { it.id == request.course }.day
                    todayRequest++
                }
            } else GlassToaster.show("这节课已更新，请查看当前课表")
        }
        ScheduleWidgetNavigation.consume()
        when (request.action) {
            ScheduleWidgetAction.Calendar -> { settingsTermOverride = null; showSettingsDialog = true }
            ScheduleWidgetAction.Sync -> loadSchedule(true)
            else -> Unit
        }
    }
    var showWidgetPicker by rememberSaveable { mutableStateOf(false) }
    if (showWidgetPicker) com.tyust.course.ui.screen.ScheduleWidgetPicker { showWidgetPicker = false }
    com.tyust.course.ui.system.ReportPageContent(courses.isNotEmpty())
    Box(Modifier.fillMaxSize()) {
    CompositionLocalProvider(com.tyust.course.ui.screen.LocalScheduleFocus provides focusRegistry) {
    ScheduleScreen(
        currentWeek = currentWeek,
        courses = courses,
        isLoading = isLoading,
        errorMessage = loadError,
        onRetry = { loadSchedule(true) },
        periodTimes = periodTimes,
        periodCount = periodCount,
        firstWeekDate = displayedTimeBase?.firstWeekDate,
        weekRequestKey = "${appliedCalendar.orEmpty()}|today:$todayRequest",
        displayPreferences = displayPreferences,
        onDisplayPreferences = { displayPreferences = it; displayStore.write(routeAccountKey, it) },
        selectedDay = selectedDay,
        onDayChange = { selectedDay = it },
        onTodayClick = { isNextSemester = false; pendingToday = true },
        positionKey = "$routeAccountKey|$resolvedTermId",
        positionCalendar = appliedCalendar?.takeIf { it == "$resolvedTermId|${displayedTimeBase.firstWeekDate}" }.orEmpty(),
        restoredPosition = savedPosition,
        onPositionChange = { if (it.calendar.isNotBlank()) displayStore.savePosition(routeAccountKey, resolvedTermId, it) },
        onWeekChange = { currentWeek = it },
        onCourseClick = {
            notificationCourseJson = null
            // Freeze the tapped card before pager neighbours or sheet layout update their bounds.
            detailSourceBounds = focusRegistry.bounds(it.id)
            detailId = it.id
        },
        onCourseLongClick = { quickCourseId = it.id },
        onAddClick = { editingId = java.util.UUID.randomUUID().toString() },
        onWidgetClick = { showWidgetPicker = true },
        onSettingsClick = { settingsTermOverride = null; showSettingsDialog = true },
        onExportClick = {
            if (courses.isEmpty()) {
                GlassToaster.show("课表为空，无法导出")
            } else {
                try {
                    val semesterStart = ScheduleDates.firstMonday(displayedTimeBase?.firstWeekDate)
                    if (semesterStart == null) {
                        GlassToaster.show("请先设置这个学期的第一周周一日期")
                        settingsTermOverride = null
                        showSettingsDialog = true
                    } else {
                    ICalExporter.exportAndShare(
                        context = context,
                        courses = courses,
                        semesterStartDate = semesterStart,
                        totalWeeks = ScheduleMaxWeeks,
                        periodTimes = periodTimes.associate { it.period to (it.startTime to it.endTime) }
                    )
                    GlassToaster.show("课表已导出，可导入到系统日历中查看")
                    }
                } catch (e: Exception) {
                    GlassToaster.show("导出失败：${e.message}")
                }
            }
        },
        isNextSemester = isNextSemester,
        onToggleSemester = { isNextSemester = !isNextSemester }
    )
    }
    if (deletedCourseJson != null) {
        androidx.compose.material3.Snackbar(
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp)
                .padding(bottom = com.tyust.course.ui.system.LocalAppOverlayBottomInset.current + 12.dp),
            action = {
                androidx.compose.material3.TextButton(onClick = {
                    if (System.currentTimeMillis() < undoDeadline) {
                        val record = deletedCourseJson?.let { ReminderJson.course(JSONObject(it)) }
                        if (record != null) {
                            settingsManager.updateCustomCourse(ScheduleSettingsManager.CustomCourse(record.id.removePrefix("custom:"), record.name,
                                record.location, record.teacher, record.day, record.startPeriod, record.endPeriod, record.weeks), routeAccountKey)
                            reminderScheduler.restoreUndo(deletedRemindersJson)
                        }
                    }
                    deletedCourseJson = null
                }) { Text("撤销") }
            }
        ) { Text("已删除课程") }
    }
    }
    
    if (showSettingsDialog) {
        com.tyust.course.ui.system.GlassSubpage(onDismiss = { showSettingsDialog = false; settingsTermOverride = null }) { close ->
            ScheduleSettingsScreen(
                manager = settingsManager,
                reminderAccountLabel = UserManager.getInstance().username.ifBlank { "当前登录账号" },
                reminderTerm = settingsTerm,
                reminderSummary = reminderScheduler.semesterSummary(routeAccountKey, settingsTerm,
                    if (settingsTerm == resolvedTermId) courses.map { it.record() } else emptyList()),
                canChangeReminders = !isDemoMode && !isLoading && settingsTerm.isNotBlank() &&
                    settingsTerm == resolvedTermId && courses.isNotEmpty() && session.token.accountStorageKey == routeAccountKey,
                onSemesterReminders = { enabled ->
                    if (!isLoading && settingsTerm == resolvedTermId && session.token.accountStorageKey == routeAccountKey &&
                        reminderScheduler.setSemesterEnabled(routeAccountKey, settingsTerm, courses.map { it.record() }, enabled)) {
                        GlassToaster.show(if (enabled) "已保存本学期提醒，请查看生效状态" else "已关闭本学期全部提醒")
                    }
                },
                displayPreferences = displayPreferences,
                onDisplayPreferences = { displayPreferences = it; displayStore.write(routeAccountKey, it) },
                periodTimesOverride = periodTimesFor(termTimeBase),
                semesterStartOverride = termTimeBase?.firstWeekDate?.takeIf { it.isNotBlank() }?.let {
                    runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).parse(it)?.time }.getOrNull()
                } ?: 0L,
                onSemesterStartChange = { millis ->
                    if (!isNextSemester && settingsTerm == resolvedTermId) pendingToday = true
                    if (!isNextSemester && settingsTermOverride == null) settingsManager.semesterStartDate = millis
                    val times = periodTimesFor(termTimeBase)
                    reminderScheduler.updateTimeBase(routeAccountKey, settingsTerm, ScheduleTimeBase(
                        ScheduleTimeBase.dateFromMillis(millis), times.associate { it.period to it.startTime }, times.associate { it.period to it.endTime }))
                },
                onPeriodTimesChange = { times ->
                    if (!isNextSemester && settingsTermOverride == null) settingsManager.savePeriodTimes(times)
                    reminderScheduler.updateTimeBase(routeAccountKey, settingsTerm,
                        (reminderScheduler.timeBase(routeAccountKey, settingsTerm) ?: ScheduleTimeBase()).copy(
                            periodStarts = times.associate { it.period to it.startTime }, periodEnds = times.associate { it.period to it.endTime }))
                },
                customCourses = customCourses,
                onAddCustomCourse = { editingId = java.util.UUID.randomUUID().toString() },
                onEditCustomCourse = { editingId = it },
                onSyncSchedule = {
                    close()
                    loadSchedule(true)
                },
                onClose = {
                    periodCount = settingsManager.periodCount
                    periodTimes = periodTimesFor(displayedTimeBase).map { PeriodTimeUi(it.period, it.startTime, it.endTime) }
                    close()
                }
            )
        }
    }
    
    val notificationCourse = notificationCourseJson?.let { runCatching { ReminderJson.reminder(JSONObject(it)) }.getOrNull() }
    val detailTerm = notificationCourse?.key?.term ?: resolvedTermId
    val selectedDetail = courses.firstOrNull { it.id == detailId && detailTerm == resolvedTermId } ?: notificationCourse?.course?.let {
        ScheduleCourseUi(it.name, it.teacher, it.location, it.day, it.startPeriod, it.endPeriod, it.weeks,
            courseColors[ScheduleIdentity.colorIndex(it.id, courseColors.size)], it.custom, if (it.custom) it.id.removePrefix("custom:") else "", id = it.id)
    }
    courses.firstOrNull { it.id == quickCourseId }?.let { course ->
        val reminderKey = CourseReminderKey(routeAccountKey, resolvedTermId, course.id)
        val enabled = reminderScheduler.find(reminderKey)?.enabled == true
        com.tyust.course.ui.screen.ScheduleQuickActions(course, enabled,
            onDismiss = { quickCourseId = null },
            onReminder = {
                quickCourseId = null
                reminderScheduler.setEnabled(reminderKey, course.record(), !enabled)
                val availability = reminderScheduler.status(reminderKey).availability
                if (!enabled && availability != ReminderAvailability.Scheduled) {
                    notificationCourseJson = null; detailSourceBounds = null; detailId = course.id
                } else GlassToaster.show(if (enabled) "已关闭提醒" else "已开启课前提醒")
            },
            onCopy = {
                quickCourseId = null
                context.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("教室", course.location))
                if (android.os.Build.VERSION.SDK_INT < 33) GlassToaster.show("教室已复制")
            },
            onEdit = { quickCourseId = null; editingId = course.customId })
    }
    selectedDetail?.let { course ->
        com.tyust.course.ui.screen.ScheduleCourseSheet(course, routeAccountKey, detailTerm, if (detailTerm == resolvedTermId) courses else listOf(course),
            sourceCenterX = detailSourceBounds?.center?.x,
            sourceBounds = detailSourceBounds,
            onDismiss = { detailId = null; detailSourceBounds = null; notificationCourseJson = null; focusRegistry.restore(course.id) },
            onEdit = { editingId = course.customId },
            onConfigureTime = { settingsTermOverride = detailTerm; showSettingsDialog = true },
            onDelete = {
                deletedCourseJson = ReminderJson.course(course.record()).toString()
                deletedRemindersJson = reminderScheduler.encodeUndo(reminderScheduler.removeCourse(routeAccountKey, course.id))
                undoDeadline = System.currentTimeMillis() + 5000L
                settingsManager.removeCustomCourse(course.customId, routeAccountKey)
                detailId = null
                detailSourceBounds = null
                notificationCourseJson = null
                focusRegistry.restore(course.id)
            })
    }
    editingId?.let { id ->
        val initial = customCourses.firstOrNull { it.id == id } ?: ScheduleSettingsManager.CustomCourse(
            id, "", "", "", selectedDay, 1, 2, "1-16周")
        com.tyust.course.ui.system.GlassSubpage(onDismiss = { editingId = null }) { close ->
            com.tyust.course.ui.screen.ScheduleCourseEditor(initial, courses, periodCount,
                isNew = customCourses.none { it.id == id }, onClose = close, onSave = { saved ->
                    settingsManager.updateCustomCourse(saved, routeAccountKey)
                    reminderScheduler.updateCustomCourse(routeAccountKey, ScheduleCourseRecord("custom:${saved.id}", saved.name,
                        saved.teacher, saved.location, saved.day, saved.startPeriod, saved.endPeriod, saved.weeks, true))
                    notificationCourse?.key?.storageId?.let { reminderScheduler.findById(it) }?.let {
                        notificationCourseJson = ReminderJson.reminder(it).toString()
                    }
                    close()
                })
        }
    }
}

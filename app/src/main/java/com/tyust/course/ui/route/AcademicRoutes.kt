package com.tyust.course.ui.route

import com.tyust.course.ui.theme.moduleEntrance
import com.tyust.course.ui.theme.ModuleMotion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tyust.course.academic.*
import com.tyust.course.manager.UserManager
import com.tyust.course.model.Course
import com.tyust.course.model.SchoolConfig
import com.tyust.course.service.GrabService
import com.tyust.course.ui.screen.CourseListScreen
import com.tyust.course.ui.screen.GrabProScreen
import com.tyust.course.ui.screen.SelectedCoursesScreen
import com.tyust.course.ui.system.*
import com.tyust.course.ui.system.glass.LiquidActionGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AcademicCourseListRoute(school: SchoolConfig, isActive: Boolean = true) {
    val providerRevision by com.tyust.course.academic.plugin.AcademicProviderRegistry.revision.collectAsState()
    val provider = remember(school, providerRevision) {
        com.tyust.course.academic.plugin.AcademicProviderRegistry.operationProvider(school, "selection.courses")?.digest.orEmpty()
    }
    val account = UserManager.getInstance().currentAccountStorageKey
    val session by UserManager.getInstance().sessionState.state.collectAsState()
    key(account, provider, session.token) { AcademicCourseListContent(school, "$account:$provider:${session.token}", isActive) }
}

@Composable
private fun AcademicCourseListContent(school: SchoolConfig, cacheKey: String, isActive: Boolean) {
    if (!com.tyust.course.academic.plugin.AcademicProviderRegistry.hasCapability(school, "selection.courses")) {
        ReportInitialPageReady()
        AcademicCapabilityUnavailable("课程", "该学校尚未适配选课查询"); return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account = UserManager.getInstance().currentAccountStorageKey
    val sessions = UserManager.getInstance().sessionState
    val session by sessions.state.collectAsState()
    val expectedSession = session.token
    var tab by rememberSaveable(account) { mutableIntStateOf(0) }
    var selectedCategory by rememberSaveable(account) { mutableStateOf("") }
    val browser by rememberPageData("academic.browser.$cacheKey") { AcademicCourseBridge.browser(school, account, expectedSession) }
    val browserState by browser.state.collectAsState()
    ReportInitialPageReady(browserState.courses.isNotEmpty() || browserState.error.isNotBlank() ||
        (browserState.context != null && !browserState.loading))
    val courses = browserState.courses
    val loading = browserState.loading
    val error = browserState.error
    val categories = browserState.context?.scopes.orEmpty()
    var query by rememberSaveable(account) { mutableStateOf("") }
    var searchVisible by rememberSaveable(account) { mutableStateOf(false) }
    var revision by remember(account) { mutableIntStateOf(0) }
    var selectedRevision by remember(account) { mutableIntStateOf(0) }
    var pendingSelection by remember(account) { mutableStateOf<Course?>(null) }
    val queue = remember(context) { AcademicGrabQueueStore(context) }
    var multiSelect by remember(account, selectedCategory) { mutableStateOf(false) }
    var checkedIds by remember(account, selectedCategory) { mutableStateOf(emptySet<String>()) }
    var batchConfirmation by remember(account) { mutableStateOf<List<Course>?>(null) }
    var selecting by remember(account) { mutableStateOf(false) }
    var batchJob by remember(account) { mutableStateOf<Job?>(null) }
    val websiteFilters = remember { com.tyust.course.academic.plugin.AcademicProviderRegistry.hasCapability(school, "selection.filters") }
    val definitions = browserState.filters
    val filtersError = browserState.filterError
    var appliedWebsiteFilters by rememberPageData<CourseFilterValues?>("academic.courses.$cacheKey.filters") { null }
    var draftWebsiteValues by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var serverQuery by rememberPageData("academic.courses.$cacheKey.keyword") { "" }
    LaunchedEffect(query) { kotlinx.coroutines.delay(300); serverQuery = query }
    var filter by rememberPageData("academic.courses.$cacheKey.filter") { AcademicCourseFilter() }
    var draftFilter by remember(account) { mutableStateOf(filter) }
    var showFilters by remember(account) { mutableStateOf(false) }
    val visibleCourses = remember(courses, query, filter, websiteFilters) { if (websiteFilters) courses else courses.filter {
        (query.isBlank() || listOf(it.name, it.teacher, it.courseId, it.jxbmc).any { value -> value.contains(query, true) }) && filter.matches(it)
    } }

    LaunchedEffect(selectedCategory, revision, appliedWebsiteFilters, serverQuery) {
        browser.open(CourseQuery(scopeId = selectedCategory, keyword = serverQuery, filters = appliedWebsiteFilters), refresh = revision > 0)
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    DisposableEffect(browser, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); browser.setActive(false) }
    }
    SideEffect { browser.setActive(foreground && isActive && tab == 0); browser.setBusy(selecting) }
    LaunchedEffect(courses) { checkedIds = checkedIds.intersect(courses.map { it.catalogSelectionKey() }.toSet()) }

    fun select(course: Course) {
        if (selecting) return
        selecting = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { AcademicCourseBridge.select(school, account, course, expectedSession) }
                if (!sessions.isCurrent(expectedSession)) return@launch
                GlassToaster.show(result.message.ifBlank { result.status.displayName() })
                if (result.status in setOf(AcademicStatus.SUCCESS, AcademicStatus.ALREADY_SELECTED)) { revision++; selectedRevision++ }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (sessions.isCurrent(expectedSession)) GlassToaster.show(e.message ?: "选课失败") }
            finally { if (sessions.isCurrent(expectedSession)) selecting = false }
        }
    }

    fun add(course: Course) {
        if (AcademicGrabRuntimeStore.get(account).running) { GlassToaster.show("请先停止当前抢课任务再修改队列"); return }
        val added = queue.add(AcademicGrabItem(account, school.id, course.name, course.teacher, course.time,
            course.completeParams["academic_course_id"].orEmpty().ifBlank { course.courseId }, course.classId,
            course.completeParams["academic_scope_id"].orEmpty(), sectionName = course.jxbmc,
            sectionIdentityKnown = course.completeParams["academic_stable_section"] == "true"))
        GlassToaster.show(if (added) "已加入抢课队列" else "课程已在队列中")
    }

    fun setTarget(course: Course, exact: Boolean) {
        if (AcademicGrabRuntimeStore.get(account).running) { GlassToaster.show("请先停止当前抢课任务再更换目标"); return }
        queue.setTarget(account, AcademicGrabItem(account, school.id, course.name,
            if (exact) course.teacher else "", if (exact) course.time else "",
            course.completeParams["academic_course_id"].orEmpty().ifBlank { course.courseId },
            if (exact) course.classId else "", course.completeParams["academic_scope_id"].orEmpty(), useExactMatch = exact,
            sectionName = if (exact) course.jxbmc else "",
            sectionIdentityKnown = course.completeParams["academic_stable_section"] == "true"))
        GlassToaster.show(if (exact) "已设为目标教学班" else "已设为同课程教学班监控目标")
    }

    Scaffold(containerColor = Color.Transparent, topBar = {
        Column(Modifier.fillMaxWidth().moduleEntrance(0).reportNoticeAnchor()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp).height(64.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SystemSegmentedControl(options = listOf("可选", "已选"), selectedIndex = tab,
                    onSelect = { tab = it }, modifier = Modifier.weight(1f), height = 48.dp)
                LiquidActionGroup(spacing = 4.dp) {
                    action(index = 0, icon = Icons.Default.Search, contentDescription = "搜索课程",
                        onClick = { searchVisible = !searchVisible }, presence = if (tab == 0) 1f else 0f)
                    action(index = 1, icon = Icons.Default.Refresh, contentDescription = "刷新课程",
                        onClick = { if (tab == 0) revision++ else selectedRevision++ })
                    action(index = 2, icon = Icons.Default.FilterList, contentDescription = "筛选课程",
                        enabled = !websiteFilters || !loading && categories.isNotEmpty(),
                        onClick = {
                            if (websiteFilters) {
                                when {
                                    loading -> GlassToaster.show("正在读取选课状态")
                                    categories.isEmpty() -> GlassToaster.show("选课未开放，暂无法获取筛选条件")
                                    selectedCategory.isBlank() -> GlassToaster.show("请先选择一个轮次，再使用学校筛选")
                                    else -> { draftWebsiteValues = appliedWebsiteFilters?.values.orEmpty(); showFilters = true }
                                }
                            } else { draftFilter = filter; showFilters = true }
                        }, presence = if (tab == 0) 1f else 0f)
                }
            }
            if (tab == 0) {
                Text(
                    text = "${school.name} · ${AcademicCapabilities.name(school.academicSystem)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                AnimatedVisibility(searchVisible, enter = ModuleMotion.expand(rememberGlassAccessibilityMode().reduceMotion),
                    exit = ModuleMotion.collapse(rememberGlassAccessibilityMode().reduceMotion)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassTextField(value = query, onValueChange = { query = it }, placeholder = "搜索课程、教师或课程号",
                            leadingIcon = Icons.Default.Search, modifier = Modifier.weight(1f))
                        SystemIconButton(onClick = { query = ""; searchVisible = false }, icon = Icons.Default.Close, contentDescription = "关闭搜索")
                    }
                }
                if (categories.isNotEmpty()) SystemPicker(
                    options = listOf(if (school.academicSystem == AcademicSystem.ZF_OLD.id) "全部课程类别" else "全部轮次与分类") + categories.map { it.name },
                    selectedIndex = (categories.indexOfFirst { it.id == selectedCategory } + 1).coerceAtLeast(0),
                    onSelect = { index ->
                        selectedCategory = if (index == 0) "" else categories[index - 1].id
                        appliedWebsiteFilters = null; draftWebsiteValues = emptyMap(); showFilters = false
                    },
                    label = if (school.academicSystem == AcademicSystem.ZF_OLD.id) "类别" else "轮次",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    maxLabelLines = if (school.academicSystem == AcademicSystem.ZF_OLD.id) 1 else 2
                )
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).moduleEntrance(1)) {
            if (tab == 1) {
                AcademicSelectedCoursesRoute(school, selectedRevision)
            } else {
                if (multiSelect) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已选 ${checkedIds.size}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { checkedIds = visibleCourses.filterNot { it.isSelected }.map { it.catalogSelectionKey() }.toSet() }, enabled = !selecting) { Text("全选") }
                    TextButton(onClick = { courses.filter { it.catalogSelectionKey() in checkedIds }.forEach(::add); multiSelect = false; checkedIds = emptySet() }, enabled = checkedIds.isNotEmpty() && !selecting) { Text("入队") }
                    TextButton(onClick = { batchConfirmation = courses.filter { it.catalogSelectionKey() in checkedIds } }, enabled = checkedIds.isNotEmpty() && !selecting) { Text("选课") }
                    SystemIconButton(onClick = { batchJob?.cancel(); multiSelect = false; checkedIds = emptySet() }, icon = Icons.Default.Close, contentDescription = if (selecting) "停止后续选课" else "退出多选")
                }
                val activeCategories = categories.filter { selectedCategory.isBlank() || it.id == selectedCategory }
                if (activeCategories.isNotEmpty() && activeCategories.all { it.params["selectionAllowed"] == "false" }) {
                    Text("当前为退选阶段，可查看课程和办理退课", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                if (error.isNotBlank() && courses.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        SystemEmptyState(title = "课程加载失败", message = error) {
                            SystemSecondaryButton(text = "重新加载", onClick = { appliedWebsiteFilters = null; revision++ })
                        }
                    }
                } else if (!loading && categories.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        SystemEmptyState(title = "选课未开放", message = "选课未开放，暂无法获取筛选条件。学校开放后请刷新；课表和成绩仍可查询。") {
                            SystemSecondaryButton(text = "刷新轮次", onClick = { revision++ })
                        }
                    }
                } else key(account, selectedCategory, serverQuery, appliedWebsiteFilters) {
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp))

                    CourseListScreen(
                        courses = visibleCourses,
                        isLoading = loading, onRefresh = { revision++ }, onSearch = { query = it },
                        onCourseSelect = { if (!selecting) pendingSelection = it }, onAutoGrab = { if (!selecting) pendingSelection = it }, isDetailsReady = !loading,
                        onAddToQueue = ::add, onSetTargetCourse = { setTarget(it, true) },
                        onSetFuzzyMatchTarget = { courseId, _, scopeId, _ ->
                            courses.firstOrNull { it.courseId == courseId && it.completeParams["academic_scope_id"] == scopeId }?.let { setTarget(it, false) }
                        },
                        isMultiSelectMode = multiSelect, selectedClassIds = checkedIds, isBatchSelecting = selecting,
                        onToggleSelection = { id, selected -> checkedIds = if (selected) checkedIds - id else checkedIds + id },
                        onEnterMultiSelect = { id -> if (!selecting) { multiSelect = true; checkedIds = setOf(id) } },
                        browserState = browserState,
                        onVisibleGroups = browser::visible,
                        onToggleGroup = browser::toggle,
                        onExpandAll = browser::expandAll,
                        onCollapseAll = browser::collapseAll,
                        onStopPreload = browser::stopBulk,
                        onRetryPreload = browser::retryFailed,
                        onLoadMore = browser::loadMore,
                        localFilterNotice = !websiteFilters && filter.active
                    )
                }
            }
        }
    }
    batchConfirmation?.let { selected ->
        SystemConfirmDialog(title = "确认批量选课", text = "按顺序提交已勾选的 ${selected.size} 个教学班。验证码、登录失效或结果待确认时，将停止后续提交。",
            confirmText = "确认提交", onDismiss = { batchConfirmation = null }, onConfirm = {
                batchConfirmation = null
                if (!selecting && sessions.isCurrent(expectedSession)) {
                    selecting = true
                    batchJob = scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) { runAcademicBatch(selected) { AcademicCourseBridge.select(school, account, it, expectedSession) } }
                            if (!sessions.isCurrent(expectedSession)) return@launch
                            GlassToaster.show("已提交 ${result.attempted} 个，成功 ${result.succeeded} 个" + if (result.stopped) "；${result.message}" else "")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { if (sessions.isCurrent(expectedSession)) GlassToaster.show(e.message ?: "批量选课已停止") }
                        finally {
                            if (sessions.isCurrent(expectedSession)) {
                                selecting = false; multiSelect = false; checkedIds = emptySet(); revision++; selectedRevision++
                            }
                        }
                    }
                }
            })
    }
    if (showFilters && websiteFilters) com.tyust.course.ui.screen.AcademicWebsiteFiltersDialog(
        definitions = definitions, error = filtersError, loading = loading, values = draftWebsiteValues,
        onValuesChange = { draftWebsiteValues = it }, onDismiss = { showFilters = false },
        onRetry = { appliedWebsiteFilters = null; draftWebsiteValues = emptyMap(); revision++ },
        onApply = { definitions?.let { appliedWebsiteFilters = CourseFilterValues(it.revision, draftWebsiteValues); revision++ }; showFilters = false },
        onClear = { draftWebsiteValues = emptyMap(); appliedWebsiteFilters = null; showFilters = false; revision++ })
    if (showFilters && !websiteFilters) SystemDialog(onDismissRequest = { showFilters = false }, title = { Text("课程筛选") },
        confirmButton = { SystemPrimaryButton(text = "应用", enabled = draftFilter.credit.isBlank() || draftFilter.credit.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true,
            onClick = { filter = draftFilter; showFilters = false }) },
        dismissButton = { SystemSecondaryButton(text = "清除", onClick = { filter = AcademicCourseFilter(); draftFilter = filter; showFilters = false }) }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.tyust.course.ui.screen.SchoolFormField("教师", draftFilter.teacher, { draftFilter = draftFilter.copy(teacher = it) })
            com.tyust.course.ui.screen.SchoolFormField("上课时间", draftFilter.time, { draftFilter = draftFilter.copy(time = it) })
            com.tyust.course.ui.screen.SchoolFormField("上课地点", draftFilter.location, { draftFilter = draftFilter.copy(location = it) })
            com.tyust.course.ui.screen.SchoolFormField("学分", draftFilter.credit, { draftFilter = draftFilter.copy(credit = it) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(draftFilter.availableOnly, { draftFilter = draftFilter.copy(availableOnly = it) })
                Text("仅显示学校已公布的有余量教学班", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            Text("按已加载的学校数据筛选；学校未公布的容量不会当作有余量。", style = MaterialTheme.typography.bodySmall)
        }
    }
    pendingSelection?.let { course ->
        val allowed = course.completeParams["selectionAllowed"] != "false"
        SystemConfirmDialog(title = if (allowed) "确认选课" else "当前不可选课",
            text = if (allowed) listOf(course.name, course.teacher, course.time).filter(String::isNotBlank).joinToString("\n") else "该轮次处于退选阶段。",
            onDismiss = { pendingSelection = null }, confirmText = if (allowed) "选课" else "知道了", showCancel = allowed,
            onConfirm = { pendingSelection = null; if (allowed) select(course) })
    }
}

@Composable
fun AcademicSelectedCoursesRoute(school: SchoolConfig, refreshRevision: Int = 0, onLoadingChange: (Boolean) -> Unit = {}) {
    if (!com.tyust.course.academic.plugin.AcademicProviderRegistry.hasCapability(school, "selection.enrolled")) {
        AcademicCapabilityUnavailable("已选课程", "该学校尚未适配已选课程查询"); return
    }
    val scope = rememberCoroutineScope()
    val account = UserManager.getInstance().currentAccountStorageKey
    val sessions = UserManager.getInstance().sessionState
    val session by sessions.state.collectAsState()
    val expectedSession = session.token
    var courses by rememberPageData<List<Course>>("academic.selected") { emptyList() }
    var loadedRevision by rememberPageData("academic.selected.loadedRevision") { -1 }
    var loading by remember(account) { mutableStateOf(true) }
    var error by remember(account) { mutableStateOf("") }
    var dropping by remember(account) { mutableStateOf(false) }
    var revision by remember(account) { mutableIntStateOf(0) }
    var pendingDrop by remember(account) { mutableStateOf<Course?>(null) }
    val currentLoadingChange by rememberUpdatedState(onLoadingChange)
    LaunchedEffect(loading) { currentLoadingChange(loading) }
    LaunchedEffect(expectedSession) { error = ""; pendingDrop = null; dropping = false }
    LaunchedEffect(account, revision, refreshRevision, expectedSession) {
        if (revision == 0 && loadedRevision == refreshRevision) { loading = false; return@LaunchedEffect }
        loading = true; error = ""
        try {
            val loaded = withContext(Dispatchers.IO) { AcademicCourseBridge.selectedCourses(school, account, expectedSession) }
            if (sessions.isCurrent(expectedSession)) { courses = loaded; loadedRevision = refreshRevision }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (sessions.isCurrent(expectedSession)) error = e.message ?: "已选课程加载失败" }
        finally { if (sessions.isCurrent(expectedSession) && kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive == true) loading = false }
    }
    if (error.isNotBlank()) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        SystemEmptyState(title = "已选课程加载失败", message = error) { SystemSecondaryButton(text = "重试", onClick = { revision++ }) }
    } else SelectedCoursesScreen(courses, loading, dropping, onRefresh = { revision++ }, onDropCourse = { pendingDrop = it })
    pendingDrop?.let { course ->
        SystemConfirmDialog(title = "确认退课", text = course.name, confirmText = "退课", onDismiss = { pendingDrop = null }, onConfirm = {
            pendingDrop = null; dropping = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { AcademicCourseBridge.drop(school, account, course, expectedSession) }
                    if (sessions.isCurrent(expectedSession)) {
                        GlassToaster.show(result.message.ifBlank { result.status.displayName() }); revision++
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (sessions.isCurrent(expectedSession)) GlassToaster.show(e.message ?: "退课失败") }
                finally { if (sessions.isCurrent(expectedSession)) dropping = false }
            }
        })
    }
}

@Composable
fun AcademicGrabQueueRoute(school: SchoolConfig) {
    val context = LocalContext.current
    val account = UserManager.getInstance().currentAccountStorageKey
    val sessions = UserManager.getInstance().sessionState
    val session by sessions.state.collectAsState()
    val expectedSession = session.token
    val providerRevision by com.tyust.course.academic.plugin.AcademicProviderRegistry.revision.collectAsState()
    val failureRevision by GrabCapabilityFailures.revision.collectAsState()
    val capabilities = remember(school, account, session.token, providerRevision, failureRevision) { GrabCapabilities.forSchool(school, account) }
    if (!capabilities.available) {
        AcademicCapabilityUnavailable("抢课", capabilities.reason, onOpenSchool = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(school.fullBasePath)))
        })
        return
    }
    val store = remember(context) { AcademicGrabQueueStore(context) }
    val prefs = remember(context) { context.getSharedPreferences("grab_pro_prefs", Context.MODE_PRIVATE) }
    val scheduler = remember(context) { AcademicGrabScheduler(context) }
    var items by remember(account) { mutableStateOf(store.items(account)) }
    var target by remember(account) { mutableStateOf(store.target(account)) }
    var itemStatuses by remember(account) { mutableStateOf(store.statuses(account)) }
    var parallel by remember(account) { mutableStateOf(prefs.getBoolean("parallel_mode_$account", false) && school.academicSystem == AcademicSystem.ZF.id) }
    var interval by remember(account) { mutableStateOf(prefs.getString("interval_$account", "1500").orEmpty()) }
    var maxRetry by remember(account) { mutableStateOf(prefs.getString("max_retry_$account", "100").orEmpty()) }
    var log by remember(account) { mutableStateOf(prefs.getString("log_text_$account", "").orEmpty()) }
    var running by remember(account) { mutableStateOf(AcademicGrabRuntimeStore.get(account).running) }
    var success by remember(account) { mutableIntStateOf(AcademicGrabRuntimeStore.get(account).success) }
    var failed by remember(account) { mutableIntStateOf(AcademicGrabRuntimeStore.get(account).failed) }
    var retries by remember(account) { mutableIntStateOf(AcademicGrabRuntimeStore.get(account).retries) }
    var scheduledMode by remember(account) { mutableStateOf(prefs.getBoolean("scheduled_mode_$account", false)) }
    var scheduledDateTime by remember(account) { mutableStateOf(prefs.getString("scheduled_datetime_$account", "").orEmpty()) }
    var hasScheduledTask by remember(account) { mutableStateOf(scheduler.isScheduled(account)) }
    var scheduledInfo by remember(account) { mutableStateOf(prefs.getString("scheduled_task_info_$account", "").orEmpty()) }
    var showDateTimePicker by remember(account) { mutableStateOf(false) }
    var showManualAdd by remember(account) { mutableStateOf(false) }
    var pendingScheduledStart by remember(account) { mutableStateOf(false) }
    fun replace(updated: List<AcademicGrabItem>) { if (!running) { store.replace(account, updated); items = updated } }
    var pendingTargetStart by remember(account) { mutableStateOf(false) }
    var fuzzy by remember(account) { mutableStateOf(prefs.getBoolean("queue_fuzzy_$account", false) || items.any { !it.useExactMatch }) }
    LaunchedEffect(capabilities) {
        if (capabilities.maxConcurrency == 1) parallel = false
    }
    fun changeMatching(value: Boolean) {
        if (running || hasScheduledTask) return
        if (!value && items.any { it.stableSectionId.isBlank() }) {
            GlassToaster.show("手动课程没有真实教学班，请从课程列表选择后再使用精确匹配"); return
        }
        fuzzy = value
        prefs.edit().putBoolean("queue_fuzzy_$account", value).apply()
        replace(items.map { it.copy(useExactMatch = !value) })
    }
    fun start(scheduled: Boolean = false, targetOnly: Boolean = false) {
        if (!sessions.isCurrent(expectedSession) || running) return
        val delay = interval.toIntOrNull()
        val attempts = maxRetry.toIntOrNull()
        if (delay == null || delay < 500 || attempts == null || attempts !in 1..1000) {
            GlassToaster.show("间隔至少为 500 毫秒，尝试次数为 1–1000 次"); return
        }
        val selectedItems = (if (targetOnly) listOfNotNull(target) else items).filter { it.enabled }
        if (selectedItems.isEmpty()) { GlassToaster.show("请先在队列中添加课程"); return }
        GrabCapabilities.forSchool(school, account).validate(selectedItems)?.let { GlassToaster.show(it); return }
        if (scheduled) {
            val trigger = runCatching { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).apply { isLenient = false }.parse(scheduledDateTime)?.time }.getOrNull()
            if (trigger == null || trigger <= System.currentTimeMillis()) { GlassToaster.show("请选择未来的开始时间"); return }
            if (!scheduler.canScheduleExactly()) {
                if (Build.VERSION.SDK_INT >= 31) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}")))
                GlassToaster.show("允许精确闹钟后，请重新创建定时任务")
                return
            }
            try {
                scheduler.schedule(UserManager.getInstance().currentAccountKey, account, trigger,
                    GrabRunPolicy(delay.toLong(), attempts), parallel)
                scheduledInfo = "队列课程: ${items.count { it.enabled }}门\n开始时间: $scheduledDateTime"
                prefs.edit().putString("scheduled_task_info_$account", scheduledInfo).apply()
                hasScheduledTask = true
                GlassToaster.show("定时任务已创建")
            } catch (e: Exception) { GlassToaster.show(e.message ?: "定时任务创建失败") }
            return
        }
        if (hasScheduledTask) { scheduler.cancel(account); hasScheduledTask = false }
        val intent = Intent(context, GrabService::class.java).apply {
            action = GrabService.ACTION_START_QUEUE
            putExtra(GrabService.EXTRA_ACCOUNT_STORAGE_KEY, account)
            putExtra(GrabService.EXTRA_ACCOUNT_KEY, UserManager.getInstance().currentAccountKey)
            putExtra(GrabService.EXTRA_PARALLEL_MODE, parallel && capabilities.maxConcurrency > 1)
            putExtra(GrabService.EXTRA_ACADEMIC_TARGET, targetOnly)
            putExtra(GrabService.EXTRA_INTERVAL, delay); putExtra(GrabService.EXTRA_MAX_RETRY, attempts)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
            // Lock reorder/add immediately; the service snapshots this same order on startup.
            running = true
        } catch (e: Exception) {
            running = false
            GlassToaster.show(e.message ?: "无法启动任务，请重试")
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start(pendingScheduledStart, pendingTargetStart) else GlassToaster.show("需要通知权限以显示抢课状态")
    }
    fun requestStart(scheduled: Boolean, targetOnly: Boolean = false) {
        pendingScheduledStart = scheduled
        pendingTargetStart = targetOnly
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else start(scheduled, targetOnly)
    }
    DisposableEffect(account) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getStringExtra(GrabService.EXTRA_ACCOUNT_STORAGE_KEY) != account) return
                running = intent.getBooleanExtra(GrabService.EXTRA_IS_RUNNING, false)
                success = intent.getIntExtra(GrabService.EXTRA_SUCCESS_COUNT, success)
                failed = intent.getIntExtra(GrabService.EXTRA_FAIL_COUNT, failed)
                retries = intent.getIntExtra(GrabService.EXTRA_RETRY_COUNT, retries)
                log = prefs.getString("log_text_$account", "").orEmpty()
                items = store.items(account)
                target = store.target(account)
                itemStatuses = store.statuses(account)
                hasScheduledTask = scheduler.isScheduled(account)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(GrabService.BROADCAST_UPDATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val courses = remember(items) { items.map { item -> Course().apply {
        name = item.courseName; teacher = item.teacher; time = item.time; jxbmc = item.sectionName
        courseId = item.stableCourseId; classId = item.stableSectionId; useExactMatch = item.useExactMatch
        uuid = item.key
        completeParams["academic_queue_key"] = item.key
    } } }
    GrabProScreen(isRunning = running, successCount = success, failCount = failed, retryCount = retries,
        targetCourseName = target?.courseName, targetCourseTeacher = target?.teacher, logText = log,
        schoolName = "${school.name} · ${AcademicCapabilities.name(school.academicSystem)}",
        onClearTargetCourse = { store.setTarget(account, null); target = null },
        isFuzzyMatchMode = fuzzy, onFuzzyMatchModeChange = ::changeMatching,
        queueExecution = true, grabCapabilities = capabilities,
        exactMatchingAvailable = items.none { it.stableSectionId.isBlank() },
        onStartTarget = target?.let { { requestStart(false, true) } },
        onStartFuzzyMatch = { requestStart(false) }, onClearFuzzyMatchTarget = { store.setTarget(account, null); target = null },
        supportsImmediateManual = true,
        systemNotice = "按队列顺序执行，当前课程成功或次数耗尽后再处理下一门。间隔至少 500 毫秒，每门最多 1000 次。模糊匹配可尝试同课程的多个教学班；手动填写的教学班、教师与时间必须符合。",
        interval = interval, onIntervalChange = { interval = it; prefs.edit().putString("interval_$account", it).apply() },
        maxRetry = maxRetry, onMaxRetryChange = { maxRetry = it; prefs.edit().putString("max_retry_$account", it).apply() },
        onStart = { requestStart(false) },
        scheduledDateTime = scheduledDateTime, isScheduledMode = scheduledMode,
        onScheduledModeChange = { scheduledMode = it; prefs.edit().putBoolean("scheduled_mode_$account", it).apply() },
        onScheduledStart = { requestStart(true) }, hasScheduledTask = hasScheduledTask, scheduledTaskInfo = scheduledInfo,
        onCancelScheduledTask = { scheduler.cancel(account); hasScheduledTask = false; scheduledInfo = "" },
        onPickDateTime = { showDateTimePicker = true },
        onStop = { context.startService(Intent(context, GrabService::class.java).apply {
            action = GrabService.ACTION_STOP; putExtra(GrabService.EXTRA_ACCOUNT_STORAGE_KEY, account)
        }) },
        onClearLog = { log = ""; prefs.edit().remove("log_text_$account").apply() },
        queue = courses, isParallelMode = parallel,
        currentQueueIndex = items.indexOfFirst { itemStatuses[it.key] == "GRABBING" },
        queueItemStatuses = itemStatuses.mapValues { (_, value) ->
            if (!running && value == "GRABBING") com.tyust.course.ui.screen.GrabQueueItemStatus.WAITING
            else runCatching { com.tyust.course.ui.screen.GrabQueueItemStatus.valueOf(value) }.getOrDefault(com.tyust.course.ui.screen.GrabQueueItemStatus.WAITING)
        },
        onParallelModeChange = { parallel = it; prefs.edit().putBoolean("parallel_mode_$account", it).apply() },
        onQueueMoveItem = { from, to -> replace(items.toMutableList().apply { add(to, removeAt(from)) }) },
        onQueueRemoveItem = { index -> replace(items.filterIndexed { i, _ -> i != index }) },
        onQueueClear = { replace(emptyList()) }, showQueueModeLabels = true,
        onQueueToggleMode = { index ->
            val item = items[index]
            if (!item.useExactMatch && item.stableSectionId.isBlank()) GlassToaster.show("手动课程须先从课程列表指定真实教学班")
            else replace(items.mapIndexed { i, value -> if (i == index) value.copy(useExactMatch = !value.useExactMatch) else value })
        },
        isExactModeGlobal = items.filter { it.stableSectionId.isNotBlank() }.all { it.useExactMatch },
        onQueueToggleAllMode = { exact -> changeMatching(!exact) },
        onAddCourse = { showManualAdd = true },
        supportsParallel = capabilities.maxConcurrency > 1, supportsScheduling = capabilities.scheduling, supportsManualAdd = capabilities.manual)
    if (showDateTimePicker) GlassDateTimePickerDialog(title = "选择抢课时间",
        initialMillis = remember(scheduledDateTime) {
            runCatching { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).apply { isLenient = false }
                .parse(scheduledDateTime)?.time }.getOrNull() ?: System.currentTimeMillis()
        }, onConfirm = { millis ->
        scheduledDateTime = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(millis))
        prefs.edit().putString("scheduled_datetime_$account", scheduledDateTime).apply()
        showDateTimePicker = false
    }, onDismiss = { showDateTimePicker = false })
    if (showManualAdd) com.tyust.course.ui.screen.ManualGrabCourseDialog(
        enabled = !running && !hasScheduledTask, onDismiss = { showManualAdd = false }, onAdd = { draft ->
            if (!sessions.isCurrent(expectedSession) || running || hasScheduledTask) false else {
                val added = store.add(AcademicGrabItem(account, school.id, draft.name, draft.teacher, draft.time,
                    useExactMatch = false, sectionName = draft.section))
                if (added) { items = store.items(account); fuzzy = true }
                else GlassToaster.show("该课程已在队列中")
                added
            }
        })
}

private fun AcademicStatus.displayName(): String = when (this) {
    AcademicStatus.SUCCESS -> "操作成功"
    AcademicStatus.ALREADY_SELECTED -> "课程已选"
    AcademicStatus.NO_CAPACITY -> "暂无余量"
    AcademicStatus.RESULT_UNKNOWN -> "结果待确认，请检查已选课程"
    AcademicStatus.CAPTCHA_REQUIRED -> "需要输入验证码，请重新登录后继续"
    AcademicStatus.HUMAN_VERIFICATION_REQUIRED -> "需要完成学校的人机验证或二次确认"
    AcademicStatus.SESSION_EXPIRED -> "登录已失效，请重新登录"
    AcademicStatus.ROUND_CLOSED -> "当前选课轮次未开放"
    AcademicStatus.CONFLICT -> "课程冲突"
    AcademicStatus.CREDIT_LIMIT -> "超出学分限制"
    AcademicStatus.PAGE_CHANGED -> "学校页面已变化，请重新加载"
    AcademicStatus.UNSUPPORTED -> "此项操作请在学校网页完成"
    AcademicStatus.INVALID_CREDENTIALS -> "账号或密码不正确"
    AcademicStatus.NETWORK_RETRYABLE -> "网络暂不可用，请稍后重试"
    AcademicStatus.UNTRUSTED_URL -> "学校页面跳转到了未配置的域名"
    AcademicStatus.VALIDATION_FAILED -> "学校未接受此次操作"
}

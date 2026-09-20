package com.tyust.course.ui.screen

import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.Backdrop

import com.tyust.course.ui.theme.moduleEntrance
import com.tyust.course.ui.theme.ModuleMotion
import com.tyust.course.ui.theme.LocalModuleEntrance
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.tyust.course.ui.system.glass.LocalGlassLensAnchor
import com.tyust.course.ui.system.glass.LocalPageGlassFreshness
import com.tyust.course.ui.system.glass.rememberGlassLensRegion
import com.tyust.course.ui.system.glass.drawBackdropSource
import com.tyust.course.ui.system.glass.isGlassLensApplicable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow

import android.os.Build

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.testTag
import com.tyust.course.ui.system.*
import com.tyust.course.ui.theme.MotionProfile
import com.tyust.course.ui.system.GlassToaster
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.model.Course
import com.tyust.course.ui.system.GlassTextField
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSecondaryButton
import com.tyust.course.ui.system.SystemSegmentedControl
import com.tyust.course.ui.system.rememberGlassAccessibilityMode
import androidx.compose.ui.semantics.Role
import com.tyust.course.ui.system.SystemTopBar
import com.tyust.course.ui.theme.SemanticSuccess
import com.tyust.course.ui.theme.SemanticWarning



@Composable
fun GrabProScreen(
    isRunning: Boolean,
    successCount: Int,
    failCount: Int,
    retryCount: Int,
    targetCourseName: String?,
    targetCourseTeacher: String?,
    logText: String,
    interval: String,
    onIntervalChange: (String) -> Unit,
    maxRetry: String,
    onMaxRetryChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearLog: () -> Unit,
    onClearTargetCourse: (() -> Unit)? = null,
    schoolName: String = "",
    courseKeywords: String = "",
    onCourseKeywordsChange: ((String) -> Unit)? = null,
    scheduledDateTime: String = "",
    isScheduledMode: Boolean = false,
    onScheduledModeChange: ((Boolean) -> Unit)? = null,
    onScheduledStart: (() -> Unit)? = null,
    hasScheduledTask: Boolean = false,
    scheduledTaskInfo: String = "",
    onCancelScheduledTask: (() -> Unit)? = null,
    onPickDateTime: (() -> Unit)? = null,
    queue: List<Course> = emptyList(),
    queueVersion: Int = 0,
    currentQueueIndex: Int = 0,
    queueItemStatuses: Map<String, GrabQueueItemStatus> = emptyMap(),
    isParallelMode: Boolean = false,
    onParallelModeChange: ((Boolean) -> Unit)? = null,
    onQueueMoveItem: ((Int, Int) -> Unit)? = null,
    onQueueRemoveItem: ((Int) -> Unit)? = null,
    onQueueToggleMode: ((Int) -> Unit)? = null,
    onQueueToggleAllMode: ((Boolean) -> Unit)? = null,
    isExactModeGlobal: Boolean = true,
    onQueueClear: (() -> Unit)? = null,
    onAddCourse: (() -> Unit)? = null,
    showScheduleWarning: Boolean = true,
    onDismissWarningForever: (() -> Unit)? = null,
    showQueueModeLabels: Boolean = true,
    isFuzzyMatchMode: Boolean = false,
    onFuzzyMatchModeChange: ((Boolean) -> Unit)? = null,
    fuzzyMatchTarget: String? = null,
    onStartFuzzyMatch: (() -> Unit)? = null,
    onClearFuzzyMatchTarget: (() -> Unit)? = null,
    supportsScheduling: Boolean = true,
    supportsParallel: Boolean = true,
    supportsManualAdd: Boolean = true,
    supportsImmediateManual: Boolean = false,
    systemNotice: String = ""
) {
    val scrollState = rememberLazyListState()
    var localScheduledMode by rememberSaveable { mutableStateOf(isScheduledMode && supportsScheduling) }
    var showWarningDialog by remember { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var logExpanded by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val reduced = rememberGlassAccessibilityMode().reduceMotion
    val overlayInset = LocalAppOverlayBottomInset.current
    val controlsState = rememberTaskControlsState()
    val configurationEnabled = !isRunning && !hasScheduledTask
    val activateScheduleSetup: () -> Unit = {
        if (configurationEnabled) {
            localScheduledMode = true
            onScheduledModeChange?.invoke(true)
            onPickDateTime?.invoke()
        }
    }
    LaunchedEffect(isScheduledMode, supportsScheduling) {
        localScheduledMode = isScheduledMode && supportsScheduling
    }
    if (showWarningDialog) ScheduleWarningDialog(
        onConfirm = {
            showWarningDialog = false
            activateScheduleSetup()
        },
        onDismissForever = {
            showWarningDialog = false
            onDismissWarningForever?.invoke()
            activateScheduleSetup()
        },
        onDismiss = { showWarningDialog = false }
    )

    val currentCourse = queue.getOrNull(currentQueueIndex)
    val console = GrabConsoleUiState(
        running = isRunning, scheduled = localScheduledMode,
        waitingForSchedule = hasScheduledTask && !isRunning,
        courseCount = queue.size, attempts = retryCount, successes = successCount, failures = failCount,
        taskTitle = when {
            isFuzzyMatchMode && !localScheduledMode -> fuzzyMatchTarget?.takeIf { it.isNotBlank() } ?: "选择要监控的课程组"
            isRunning -> currentCourse?.name ?: targetCourseName ?: "正在执行队列"
            !targetCourseName.isNullOrBlank() -> targetCourseName
            queue.isNotEmpty() -> queue.size.toString() + " 门课程待执行"
            else -> "添加你的第一门课程"
        },
        taskSubtitle = when {
            hasScheduledTask && localScheduledMode -> scheduledTaskInfo.ifBlank { "等待计划时间" }
            !targetCourseName.isNullOrBlank() -> targetCourseTeacher.orEmpty()
            isFuzzyMatchMode && !localScheduledMode -> "持续检查所选课程组"
            queue.isNotEmpty() -> if (isParallelMode) "全队列轮询 · 最多同时 2 门" else "全队列依次轮询"
            else -> "从课程列表添加到队列"
        }
    )
    val collapseTravel = with(LocalDensity.current) { 96.dp.toPx() }
    val headerCollapse by remember(collapseTravel) {
        derivedStateOf {
            if (scrollState.firstVisibleItemIndex > 0) 1f
            else (scrollState.firstVisibleItemScrollOffset / collapseTravel).coerceIn(0f, 1f)
        }
    }
    val canStart = when {
        isRunning -> true
        localScheduledMode && hasScheduledTask -> onCancelScheduledTask != null
        localScheduledMode -> scheduledDateTime.isNotBlank() && queue.isNotEmpty() && onScheduledStart != null
        isFuzzyMatchMode -> !fuzzyMatchTarget.isNullOrBlank() && onStartFuzzyMatch != null
        else -> !targetCourseName.isNullOrBlank() || queue.isNotEmpty()
    }
    val action: () -> Unit = {
        when {
            isRunning -> onStop()
            localScheduledMode && hasScheduledTask -> onCancelScheduledTask?.invoke()
            localScheduledMode -> onScheduledStart?.invoke()
            isFuzzyMatchMode -> onStartFuzzyMatch?.invoke()
            else -> {
                val manualCount = queue.count { it.classId.isNullOrEmpty() }
                if (!supportsImmediateManual && manualCount > 0 && targetCourseName.isNullOrBlank()) {
                    GlassToaster.show("包含 " + manualCount + " 门手动添加课程，即时模式暂不支持这些条目")
                } else onStart()
            }
        }
    }
    val openSchedule: () -> Unit = {
        if (configurationEnabled) {
            if (!localScheduledMode && showScheduleWarning) showWarningDialog = true
            else activateScheduleSetup()
        }
    }
    val queueHeadingIndex = 2 + if (systemNotice.isNotBlank()) 1 else 0
    val advancedIndex = queueHeadingIndex + 1 + if (queue.isEmpty()) 1 else queue.size + if (supportsManualAdd && onAddCourse != null) 1 else 0
    fun reveal(index: Int) { scope.launch { if (reduced) scrollState.scrollToItem(index) else scrollState.animateScrollToItem(index) } }
    val quickActions = buildList {
        if (supportsManualAdd && onAddCourse != null) add(TaskQuickAction("add", "添加课程", AnimatedIconSpec.Add,
            configurationEnabled, caption = "添加", onClick = onAddCourse))
        if (onFuzzyMatchModeChange != null) add(TaskQuickAction("match", if (isFuzzyMatchMode) "切换为精确执行" else "切换为模糊监控",
            AnimatedIconSpec.ScanLock, configurationEnabled && !localScheduledMode, isFuzzyMatchMode,
            caption = if (isFuzzyMatchMode) "精确" else "监控",
            iconProgress = if (isFuzzyMatchMode) 0f else 1f) { onFuzzyMatchModeChange(!isFuzzyMatchMode); reveal(0) })
        if (supportsScheduling && onPickDateTime != null) add(TaskQuickAction("schedule", "设置定时任务",
            AnimatedIconSpec.Clock, configurationEnabled, localScheduledMode,
            caption = "定时") { openSchedule(); reveal(0) })
        add(TaskQuickAction("advanced", "高级设置", AnimatedIconSpec.Settings, configurationEnabled, caption = "参数") { advancedExpanded = true; reveal(advancedIndex) })
        add(TaskQuickAction("logs", "运行日志", AnimatedIconSpec.Log, caption = "日志") { logExpanded = true; reveal(advancedIndex + 1) })
        add(TaskQuickAction("queue", "课程队列", AnimatedIconSpec.Courses, caption = "队列") { reveal(queueHeadingIndex) })
    }
    val wallpaper = LocalAppBackdrop.current
    val pageBackdrop = if (wallpaper != null && isBackdropSupported()) rememberLayerBackdrop() else null
    val sharpPageBackdrop = if (pageBackdrop != null && isGlassLensApplicable() && !reduced)
        rememberLayerBackdrop() else null
    val blurVisible by remember(controlsState) {
        derivedStateOf { controlsState.expanded || controlsState.progress > 0f }
    }
    val controlBackdrop = if (wallpaper != null && pageBackdrop != null) rememberCombinedBackdrop(wallpaper, pageBackdrop) else wallpaper
    val lensDensity = LocalDensity.current
    val controlsLens = if (controlBackdrop != null) rememberGlassLensRegion("grab-controls", console, queueVersion,
        controlsState.expanded, freshness = LocalPageGlassFreshness.current,
        // The resting button stays native; the expanded, blurred fan does not need
        // a million-pixel readback on every background update.
        maxCapturePixels = 180_000) { coordinates ->
        drawBackdropSource(controlBackdrop, lensDensity, coordinates)
    } else null
    val entrance = LocalModuleEntrance.current
    LaunchedEffect(controlsLens, entrance) {
        if (controlsLens != null) snapshotFlow { entrance?.invoke() ?: 1f }.collect { progress ->
            controlsLens.invalidate(if (progress > 0f && progress < 1f) 100L else 0L)
        }
    }
    LaunchedEffect(controlsLens, controlsState) {
        if (controlsLens != null) snapshotFlow { controlsState.progress }.collect { progress ->
            controlsLens.invalidate(if (progress > 0f && progress < 1f) 100L else 0L)
        }
    }
    // The source contains wallpaper + the page, while the button and its fan stay outside it.
    Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().then(if (pageBackdrop != null) Modifier.layerBackdrop(pageBackdrop) else Modifier)) {
    Scaffold(
        modifier = (if (sharpPageBackdrop != null && blurVisible) Modifier.graphicsLayer {
            alpha = 1f - controlsState.progress
        }.layerBackdrop(sharpPageBackdrop) else Modifier.graphicsLayer {
            val blur = controlsState.progress * 14.dp.toPx()
            renderEffect = if (!reduced && Build.VERSION.SDK_INT >= 31 && blur > 0.1f)
                BlurEffect(blur, blur, TileMode.Clamp) else null
        }).then(if (controlsState.expanded) Modifier.clearAndSetSemantics {} else Modifier),
        containerColor = Color.Transparent,
        topBar = {
            Box(Modifier.moduleEntrance(0)) {
            SystemTopBar(title = "抢课工作台", collapseFraction = headerCollapse,
                subtitle = schoolName.takeIf { it.isNotBlank() } ?: "管理队列与执行任务")
            }
        },
        bottomBar = {
            Spacer(Modifier.height(overlayInset + TaskControlsReservedHeight))
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("grab-console-list"),
            state = scrollState,
            contentPadding = PaddingValues(start = PagePadding, end = PagePadding,
                top = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "current-task") {
                Box(Modifier.moduleEntrance(1)) {
                TaskOverviewCard(console,
                    modeControl = {
                        TaskModeBar(isFuzzyMatchMode, localScheduledMode, configurationEnabled,
                            onFuzzyMatchModeChange, if (supportsScheduling && onPickDateTime != null) openSchedule else null)
                    },
                    onClearTarget = when {
                        !configurationEnabled -> null
                        isFuzzyMatchMode && !fuzzyMatchTarget.isNullOrBlank() -> onClearFuzzyMatchTarget
                        !targetCourseName.isNullOrBlank() -> onClearTargetCourse
                        else -> null
                    })
                }
            }
            if (systemNotice.isNotBlank()) item(key = "school-notice") {
                Text(systemNotice, Modifier.moduleEntrance(1), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item(key = "scheduled-details") {
                AnimatedVisibility(localScheduledMode,
                    modifier = Modifier.moduleEntrance(1),
                    enter = ModuleMotion.expand(reduced),
                    exit = ModuleMotion.collapse(reduced)) {
                    SystemCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("定时设置", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                TextButton(
                                    enabled = configurationEnabled,
                                    onClick = {
                                        localScheduledMode = false
                                        onScheduledModeChange?.invoke(false)
                                    },
                                    modifier = Modifier.testTag("task-cancel-timing")
                                ) { Text("取消定时设置", style = MaterialTheme.typography.labelMedium) }
                            }
                            if (onCourseKeywordsChange != null) GlassTextField(
                                courseKeywords, onCourseKeywordsChange, Modifier.fillMaxWidth(),
                                placeholder = "课程或教师关键词", enabled = !isRunning && !hasScheduledTask)
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clickable(enabled = configurationEnabled && onPickDateTime != null, role = androidx.compose.ui.semantics.Role.Button) { onPickDateTime?.invoke() }
                                .testTag("task-scheduled-time"),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AnimatedLineIcon(AnimatedIconSpec.Clock, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text("计划开始时间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    AnimatedValueText(scheduledDateTime.ifBlank { "选择日期和时间" }, style = MaterialTheme.typography.titleSmall)
                                }
                                AnimatedLineIcon(AnimatedIconSpec.Forward, Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            item(key = "queue-heading") {
                Row(Modifier.fillMaxWidth().moduleEntrance(2), verticalAlignment = Alignment.CenterVertically) {
                    Text("课程队列", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    AnimatedValueText(queue.size.toString(), Modifier.padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    SystemIconButton(Icons.Default.DeleteSweep, "清空队列", { onQueueClear?.invoke() },
                        enabled = configurationEnabled && queue.isNotEmpty() && onQueueClear != null, chip = false)
                }
            }
            grabQueueItems(
                queue = queue, currentIndex = currentQueueIndex, itemStatuses = queueItemStatuses,
                isRunning = isRunning, isParallelMode = isParallelMode, queueVersion = queueVersion,
                onMoveItem = { from, to -> onQueueMoveItem?.invoke(from, to) },
                onRemoveItem = { onQueueRemoveItem?.invoke(it) },
                onAddCourse = { onAddCourse?.invoke() },
                onToggleMode = { onQueueToggleMode?.invoke(it) },
                showMode = showQueueModeLabels, supportsManualAdd = supportsManualAdd && onAddCourse != null,
                editable = configurationEnabled
            )
            item(key = "advanced-options") {
                Box(Modifier.moduleEntrance(3)) {
                ConsoleDisclosure("高级设置", interval + " ms · 最多 " + maxRetry + " 次", AnimatedIconSpec.Settings, "grab-advanced",
                    expanded = advancedExpanded, onExpandedChange = { advancedExpanded = it }) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NumericField(interval, onIntervalChange, "重试间隔 (ms)", Modifier.weight(1f), configurationEnabled)
                            NumericField(maxRetry, onMaxRetryChange, "最大重试次数", Modifier.weight(1f), configurationEnabled)
                        }
                        if (supportsParallel && queue.size > 1 && onParallelModeChange != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("并行执行", style = MaterialTheme.typography.titleSmall)
                                    Text("轮询全部课程，同时处理最多 2 门；无名额时继续等待", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                LiquidSwitch(isParallelMode, onParallelModeChange, enabled = configurationEnabled)
                            }
                        }
                        if (showQueueModeLabels && onQueueToggleAllMode != null && queue.isNotEmpty()) {
                            Text("队列匹配方式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SystemSegmentedControl(listOf("智能匹配", "精确匹配"), if (isExactModeGlobal) 1 else 0,
                                enabled = configurationEnabled,
                                onSelect = { if (configurationEnabled) onQueueToggleAllMode(it == 1) })
                        }
                    }
                }
                }
            }
            item(key = "runtime-log") { Box(Modifier.moduleEntrance(3)) { LogConsole(logText, onClearLog, logExpanded, { logExpanded = it }) } }
        }
    }
    if (sharpPageBackdrop != null && blurVisible) {
        // API 31/32: keep one fixed blur result and crossfade it. Replacing a
        // full-screen blur kernel every animation frame stalls the phone's GPU.
        GrabPageBlur(sharpPageBackdrop,
            Modifier.matchParentSize().graphicsLayer { alpha = controlsState.progress }.clearAndSetSemantics {})
    }
    }
    CompositionLocalProvider(LocalAppBackdrop provides controlBackdrop, LocalControlBackdrop provides controlBackdrop,
        LocalGlassLensAnchor provides controlsLens) {
    LiquidTaskControls(quickActions, overlayInset, controlsState, lensAnchor = controlsLens) { expanded, progress, toggle ->
        TaskActionDock(console, canStart, isFuzzyMatchMode, { controlsState.close(); action() }, Modifier.moduleEntrance(3),
            expanded, progress, toggle)
    }
    }
    }
}

/** Keep the 14dp blur footprint while filtering a quarter-sized image on API 31/32. */
@Composable
private fun GrabPageBlur(backdrop: Backdrop, modifier: Modifier) {
    val layer = rememberGraphicsLayer()
    val density = LocalDensity.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(modifier.onGloballyPositioned { coordinates = it }.drawWithCache {
        val reducedSize = IntSize((size.width / 4f).toInt().coerceAtLeast(1),
            (size.height / 4f).toInt().coerceAtLeast(1))
        val scaleX = reducedSize.width / size.width
        val scaleY = reducedSize.height / size.height
        layer.renderEffect = BlurEffect(14.dp.toPx() * scaleX, 14.dp.toPx() * scaleY, TileMode.Clamp)
        onDrawBehind {
            coordinates?.takeIf { it.isAttached }?.let { coords ->
                layer.record(size = reducedSize) {
                    withTransform({ scale(scaleX, scaleY, Offset.Zero) }) {
                        drawBackdropSource(backdrop, density, coords)
                    }
                }
                withTransform({ scale(1f / scaleX, 1f / scaleY, Offset.Zero) }) { drawLayer(layer) }
            }
        }
    })
}

@Immutable
data class GrabConsoleUiState(
    val running: Boolean, val scheduled: Boolean, val waitingForSchedule: Boolean,
    val courseCount: Int, val attempts: Int, val successes: Int, val failures: Int,
    val taskTitle: String, val taskSubtitle: String
) {
    val status: String get() = when {
        running -> "执行中"
        waitingForSchedule -> "定时待命"
        successes + failures > 0 -> if (failures > 0) "执行结束 · 有失败" else "执行完成"
        courseCount > 0 -> "准备就绪"
        else -> "等待添加"
    }
}

@Composable
private fun TaskOverviewCard(ui: GrabConsoleUiState, modeControl: @Composable () -> Unit, onClearTarget: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val reduced = rememberGlassAccessibilityMode().reduceMotion
    val stateColor by animateColorAsState(
        when {
            ui.running -> colors.primary
            ui.waitingForSchedule -> SemanticWarning
            ui.failures > 0 -> colors.error
            ui.successes > 0 -> SemanticSuccess
            else -> colors.onSurfaceVariant
        }, animationSpec = if (reduced) snap() else tween(MotionProfile.IconMillis), label = "console-status-color")
    LiquidTaskSurface(Modifier.fillMaxWidth().testTag("grab-current-task"),
        contentPadding = PaddingValues(12.dp), cornerRadius = 20.dp,
        accent = stateColor, emphasized = ui.running) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = stateColor.copy(alpha = 0.08f)) {
                    RequestStateSymbol(ui.running, when {
                        ui.running || ui.waitingForSchedule -> SymbolResult.None
                        ui.failures > 0 -> SymbolResult.Failure
                        ui.successes > 0 -> SymbolResult.Success
                        else -> SymbolResult.None
                    }, modifier = Modifier.padding(8.dp).size(20.dp), tint = stateColor)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(ui.taskTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AnimatedValueText(ui.status, color = stateColor, style = MaterialTheme.typography.labelSmall)
                        if (ui.taskSubtitle.isNotBlank()) Text("· " + ui.taskSubtitle, Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
                if (onClearTarget != null) SystemIconButton(Icons.Default.Close, "清除当前目标", onClearTarget, chip = false)
            }
            modeControl()
            AnimatedVisibility(ui.running || ui.attempts + ui.successes + ui.failures > 0,
                enter = ModuleMotion.expand(reduced),
                exit = ModuleMotion.collapse(reduced)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ConsoleMetric(ui.attempts, "尝试", Modifier.weight(1f), colors.onSurfaceVariant)
                    ConsoleMetric(ui.successes, "成功", Modifier.weight(1f), SemanticSuccess)
                    ConsoleMetric(ui.failures, "失败", Modifier.weight(1f), colors.error)
                }
            }
        }
    }
}

@Composable
private fun ConsoleMetric(value: Int, label: String, modifier: Modifier, color: Color) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedValueText(value.toString(), color = color, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun TaskActionDock(ui: GrabConsoleUiState, enabled: Boolean, fuzzy: Boolean, onAction: () -> Unit, modifier: Modifier,
    expanded: Boolean, menuProgress: Float, onToggleMenu: () -> Unit) {
    val stopping = ui.running || ui.scheduled && ui.waitingForSchedule
    val label = when {
        ui.running -> "停止执行"
        ui.scheduled && ui.waitingForSchedule -> "取消定时任务"
        ui.scheduled -> "创建定时任务"
        fuzzy -> "开始监控"
        else -> "开始执行"
    }
    LiquidTaskButton(
        text = label,
        supportingText = when {
            ui.running -> "任务进行中 · 轻点停止"
            ui.waitingForSchedule && ui.scheduled -> "已安排 · 等待开始"
            ui.courseCount > 0 -> ui.courseCount.toString() + " 门课程已就绪"
            else -> "先添加待执行课程"
        },
        running = ui.running, stopping = stopping, enabled = enabled, onClick = onAction,
        expanded = expanded, menuProgress = menuProgress, onToggleMenu = onToggleMenu,
        modifier = modifier
    )
}
@Composable
private fun ConsoleDisclosure(title: String, summary: String, icon: AnimatedIconSpec, tag: String,
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit, content: @Composable () -> Unit) {
    val reduced = rememberGlassAccessibilityMode().reduceMotion
    SystemCard(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
                .clickable(role = Role.Button) { onExpandedChange(!expanded) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimatedLineIcon(icon, Modifier.size(20.dp), state = if (expanded) IconVisualState.Expanded else IconVisualState.Idle)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AnimatedLineIcon(AnimatedIconSpec.Chevron, Modifier.size(20.dp),
                    state = if (expanded) IconVisualState.Expanded else IconVisualState.Idle)
            }
            AnimatedVisibility(expanded,
                enter = ModuleMotion.expand(reduced),
                exit = ModuleMotion.collapse(reduced)) {
                Box(Modifier.padding(top = 14.dp, bottom = 4.dp)) { content() }
            }
        }
    }
}
@Composable
private fun NumericField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        GlassTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
private fun ScheduleWarningDialog(
    onConfirm: () -> Unit,
    onDismissForever: () -> Unit,
    onDismiss: () -> Unit
) {
    SystemDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = SemanticWarning,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "定时模式提示",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "我已知晓",
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            SystemSecondaryButton(
                text = "不再提示",
                onClick = onDismissForever,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "系统可能因电池优化、锁屏或后台限制导致定时触发延迟。",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "建议在抢课前保持应用存活，并将应用加入电池优化白名单。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LogConsole(
    logText: String,
    onClearLog: () -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    val scrollState = rememberScrollState()
    var followTail by remember { mutableStateOf(true) }
    val reduced = rememberGlassAccessibilityMode().reduceMotion
    val lastLine = remember(logText) { logText.lineSequence().lastOrNull { it.isNotBlank() } }

    LaunchedEffect(scrollState.isScrollInProgress) {
        if (!scrollState.isScrollInProgress) {
            followTail = scrollState.value >= scrollState.maxValue - 8
        }
    }
    LaunchedEffect(logText, expanded, scrollState.maxValue) {
        if (expanded && followTail) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).clickable { onExpandedChange(!expanded) }.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedLineIcon(
                    spec = AnimatedIconSpec.Log,
                    state = if (expanded) IconVisualState.Expanded else IconVisualState.Idle,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "运行日志",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                AnimatedLineIcon(AnimatedIconSpec.Chevron, Modifier.size(18.dp),
                    state = if (expanded) IconVisualState.Expanded else IconVisualState.Idle)
            }
            com.tyust.course.ui.system.SystemIconButton(
                onClick = onClearLog, icon = Icons.Default.DeleteSweep,
                contentDescription = "清空日志", enabled = logText.isNotBlank()
            )
        }

        if (!expanded) {
            AnimatedValueText(lastLine ?: "暂无运行记录", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { onExpandedChange(true) })
        }
        AnimatedVisibility(
            visible = expanded,
            enter = ModuleMotion.expand(reduced),
            exit = ModuleMotion.collapse(reduced)
        ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = com.tyust.course.ui.system.glassSurfaceColor(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(0.5.dp, com.tyust.course.ui.system.glassBorderColor())
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (logText.isBlank()) 96.dp else 200.dp)
                    .padding(14.dp)
            ) {
                Text(
                    text = logText.ifBlank { "暂无运行记录" },
                    modifier = Modifier.verticalScroll(scrollState),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
        }
    }
}

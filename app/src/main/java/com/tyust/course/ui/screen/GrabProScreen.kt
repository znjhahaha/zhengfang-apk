package com.tyust.course.ui.screen

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AlarmAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.model.Course
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemDestructiveButton
import com.tyust.course.ui.system.SystemEmptyState
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSectionHeader
import com.tyust.course.ui.system.SystemSecondaryButton
import com.tyust.course.ui.system.SystemSegmentedControl
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import com.tyust.course.ui.system.SystemTopBar
import com.tyust.course.ui.theme.BackgroundDark
import com.tyust.course.ui.theme.NeuDivider
import com.tyust.course.ui.theme.NeuInsetBackground
import com.tyust.course.ui.theme.NeuPrimary
import com.tyust.course.ui.theme.NeuSurface
import com.tyust.course.ui.theme.SemanticDanger
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
    onClearFuzzyMatchTarget: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scrollState = rememberLazyListState()
    var localScheduledMode by remember { mutableStateOf(isScheduledMode) }
    var showWarningDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isScheduledMode) {
        localScheduledMode = isScheduledMode
    }

    if (showWarningDialog) {
        ScheduleWarningDialog(
            onConfirm = {
                showWarningDialog = false
                localScheduledMode = true
                onScheduledModeChange?.invoke(true)
            },
            onDismissForever = {
                showWarningDialog = false
                localScheduledMode = true
                onScheduledModeChange?.invoke(true)
                onDismissWarningForever?.invoke()
            },
            onDismiss = { showWarningDialog = false }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SystemTopBar(
                title = "抢课工作台",
                subtitle = when {
                    localScheduledMode -> "定时任务模式"
                    isFuzzyMatchMode -> "模糊监控模式"
                    else -> "即时执行模式"
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            state = scrollState,
            contentPadding = PaddingValues(horizontal = PagePadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                RuntimeStatusCard(
                    isRunning = isRunning,
                    hasScheduledTask = hasScheduledTask,
                    successCount = successCount,
                    failCount = failCount,
                    retryCount = retryCount,
                    queueSize = queue.size
                )
            }

            item {
                SystemSegmentedControl(
                    options = listOf("即时执行", "定时任务"),
                    selectedIndex = if (localScheduledMode) 1 else 0,
                    onSelect = { index ->
                        val newMode = index == 1
                        if (newMode && !localScheduledMode && showScheduleWarning) {
                            showWarningDialog = true
                        } else {
                            localScheduledMode = newMode
                            onScheduledModeChange?.invoke(newMode)
                        }
                    }
                )
            }

            item {
                AnimatedContent(
                    targetState = localScheduledMode,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "grab_mode_switch"
                ) { scheduledMode ->
                    if (scheduledMode) {
                        ScheduledTaskForm(
                            schoolName = schoolName,
                            courseKeywords = courseKeywords,
                            onCourseKeywordsChange = onCourseKeywordsChange,
                            dateTime = scheduledDateTime,
                            onDateTimeClick = { onPickDateTime?.invoke() },
                            hasTask = hasScheduledTask,
                            taskInfo = scheduledTaskInfo,
                            onCancelTask = { onCancelScheduledTask?.invoke() },
                            queueSize = queue.size,
                            isRunning = isRunning,
                            onStop = onStop,
                            onCreateTask = { onScheduledStart?.invoke() }
                        )
                    } else {
                        ImmediateGrabForm(
                            targetCourseName = targetCourseName,
                            targetCourseTeacher = targetCourseTeacher,
                            interval = interval,
                            onIntervalChange = onIntervalChange,
                            maxRetry = maxRetry,
                            onMaxRetryChange = onMaxRetryChange,
                            isRunning = isRunning,
                            onStart = {
                                val manualCourses = queue.filter { it.classId.isNullOrEmpty() }
                                if (manualCourses.isNotEmpty()) {
                                    Toast.makeText(
                                        context,
                                        "包含 ${manualCourses.size} 门手动添加课程，即时模式暂不支持这些条目",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    onStart()
                                }
                            },
                            onStop = onStop,
                            onClearTargetCourse = onClearTargetCourse,
                            queueSize = queue.size,
                            isFuzzyMatchMode = isFuzzyMatchMode,
                            onFuzzyMatchModeChange = onFuzzyMatchModeChange,
                            fuzzyMatchTarget = fuzzyMatchTarget,
                            onStartFuzzyMatch = onStartFuzzyMatch,
                            onClearFuzzyMatchTarget = onClearFuzzyMatchTarget
                        )
                    }
                }
            }

            item {
                SystemCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surface,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    contentPadding = PaddingValues(16.dp)
                ) {
                    GrabQueueHeader(
                        queueSize = queue.size,
                        isParallelMode = isParallelMode,
                        onParallelModeChange = { onParallelModeChange?.invoke(it) },
                        onClearQueue = { onQueueClear?.invoke() },
                        isRunning = isRunning,
                        showMode = showQueueModeLabels,
                        isExactModeGlobal = isExactModeGlobal,
                        onToggleAllMode = onQueueToggleAllMode
                    )
                }
            }

            grabQueueItems(
                queue = queue,
                currentIndex = currentQueueIndex,
                itemStatuses = queueItemStatuses,
                isRunning = isRunning,
                isParallelMode = isParallelMode,
                queueVersion = queueVersion,
                onMoveItem = { from, to -> onQueueMoveItem?.invoke(from, to) },
                onRemoveItem = { onQueueRemoveItem?.invoke(it) },
                onAddCourse = { onAddCourse?.invoke() },
                onToggleMode = { onQueueToggleMode?.invoke(it) },
                showMode = showQueueModeLabels
            )

            item {
                LogConsole(
                    logText = logText,
                    onClearLog = onClearLog
                )
            }
        }
    }
}

@Composable
private fun RuntimeStatusCard(
    isRunning: Boolean,
    hasScheduledTask: Boolean,
    successCount: Int,
    failCount: Int,
    retryCount: Int,
    queueSize: Int
) {
    val (statusText, tone) = when {
        hasScheduledTask && !isRunning -> "定时待命" to SystemTone.Warning
        isRunning -> "执行中" to SystemTone.Success
        else -> "已就绪" to SystemTone.Neutral
    }

    SystemCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "运行状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (queueSize > 0) "当前队列 $queueSize 门课程" else "当前队列为空",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SystemStatusBadge(
                text = statusText,
                tone = tone
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusMetric("尝试", retryCount.toString(), Modifier.weight(1f))
            StatusMetric("成功", successCount.toString(), Modifier.weight(1f), SemanticSuccess)
            StatusMetric("失败", failCount.toString(), Modifier.weight(1f), SemanticDanger)
        }
    }
}

@Composable
private fun StatusMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(
        modifier = modifier,
        color = NeuInsetBackground,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ImmediateGrabForm(
    targetCourseName: String?,
    targetCourseTeacher: String?,
    interval: String,
    onIntervalChange: (String) -> Unit,
    maxRetry: String,
    onMaxRetryChange: (String) -> Unit,
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearTargetCourse: (() -> Unit)? = null,
    queueSize: Int = 0,
    isFuzzyMatchMode: Boolean = false,
    onFuzzyMatchModeChange: ((Boolean) -> Unit)? = null,
    fuzzyMatchTarget: String? = null,
    onStartFuzzyMatch: (() -> Unit)? = null,
    onClearFuzzyMatchTarget: (() -> Unit)? = null
) {
    SystemCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant
    ) {
        SystemSectionHeader(
            title = "即时执行",
            subtitle = if (isFuzzyMatchMode) "持续监控课程组人数变化" else "执行当前目标或队列任务"
        )

        if (onFuzzyMatchModeChange != null) {
            SystemSegmentedControl(
                options = listOf("精确模式", "模糊监控"),
                selectedIndex = if (isFuzzyMatchMode) 1 else 0,
                onSelect = { onFuzzyMatchModeChange(it == 1) }
            )
        }

        TargetSummaryCard(
            title = if (isFuzzyMatchMode) "监控目标" else "目标课程",
            primaryText = when {
                isFuzzyMatchMode && !fuzzyMatchTarget.isNullOrBlank() -> fuzzyMatchTarget
                !isFuzzyMatchMode && !targetCourseName.isNullOrBlank() -> targetCourseName
                !isFuzzyMatchMode && queueSize > 0 -> "队列模式已就绪"
                else -> "未设置目标"
            },
            secondaryText = when {
                isFuzzyMatchMode && !fuzzyMatchTarget.isNullOrBlank() -> "将监控该课程组的可选人数变化"
                isFuzzyMatchMode -> "请在课程页对课程组使用“监控”操作"
                !isFuzzyMatchMode && !targetCourseName.isNullOrBlank() -> "教师：${targetCourseTeacher ?: "未知"}"
                !isFuzzyMatchMode && queueSize > 0 -> "将按队列顺序尝试 ${queueSize} 门课程"
                else -> "请在课程页设置目标课程或添加队列"
            },
            tone = when {
                isFuzzyMatchMode -> SystemTone.Warning
                !targetCourseName.isNullOrBlank() || queueSize > 0 -> SystemTone.Info
                else -> SystemTone.Neutral
            },
            onClear = when {
                isFuzzyMatchMode && !fuzzyMatchTarget.isNullOrBlank() -> onClearFuzzyMatchTarget
                !isFuzzyMatchMode && !targetCourseName.isNullOrBlank() -> onClearTargetCourse
                else -> null
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NumericField(
                value = interval,
                onValueChange = onIntervalChange,
                label = "轮询间隔 (ms)",
                modifier = Modifier.weight(1f)
            )
            NumericField(
                value = maxRetry,
                onValueChange = onMaxRetryChange,
                label = "最大重试次数",
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SystemPrimaryButton(
                text = if (isFuzzyMatchMode) "开始监控" else "开始执行",
                onClick = { if (isFuzzyMatchMode) onStartFuzzyMatch?.invoke() else onStart() },
                modifier = Modifier.weight(1f),
                enabled = if (isFuzzyMatchMode) !isRunning && !fuzzyMatchTarget.isNullOrBlank() else !isRunning && (!targetCourseName.isNullOrBlank() || queueSize > 0),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
            SystemDestructiveButton(
                text = "停止",
                onClick = onStop,
                modifier = Modifier.weight(1f),
                enabled = isRunning,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun ScheduledTaskForm(
    schoolName: String,
    courseKeywords: String = "",
    onCourseKeywordsChange: ((String) -> Unit)? = null,
    dateTime: String,
    onDateTimeClick: () -> Unit,
    hasTask: Boolean,
    taskInfo: String,
    onCancelTask: () -> Unit,
    queueSize: Int = 0,
    isRunning: Boolean = false,
    onStop: (() -> Unit)? = null,
    onCreateTask: () -> Unit
) {
    SystemCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant
    ) {
        SystemSectionHeader(
            title = "定时任务",
            subtitle = if (hasTask) "任务已创建，等待触发" else "设定未来时间自动开始抢课"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SystemStatusBadge(
                text = schoolName.ifBlank { "未登录" },
                tone = SystemTone.Neutral
            )
            SystemStatusBadge(
                text = if (queueSize > 0) "队列 $queueSize 门" else "队列为空",
                tone = if (queueSize > 0) SystemTone.Info else SystemTone.Warning
            )
        }

        if (onCourseKeywordsChange != null) {
            OutlinedTextField(
                value = courseKeywords,
                onValueChange = onCourseKeywordsChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("课程关键词") },
                placeholder = { Text("输入课程名、教师名等关键词") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.outline,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDateTimeClick),
            color = NeuInsetBackground,
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AccessTime,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "开始时间",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = dateTime.ifBlank { "点击选择启动时间" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (dateTime.isBlank()) FontWeight.Normal else FontWeight.Medium
                    )
                }
            }
        }

        if (hasTask) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (isRunning) SemanticSuccess.copy(alpha = 0.12f) else SemanticWarning.copy(alpha = 0.12f),
                shape = MaterialTheme.shapes.small
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isRunning) "任务正在执行" else "任务已计划",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isRunning) SemanticSuccess else SemanticWarning
                    )
                    Text(
                        text = taskInfo.ifBlank { "等待到达计划时间后自动执行。" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isRunning && onStop != null) {
                SystemDestructiveButton(
                    text = "停止抢课",
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            } else {
                SystemSecondaryButton(
                    text = "取消任务",
                    onClick = onCancelTask,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            SystemPrimaryButton(
                text = "创建定时任务",
                onClick = onCreateTask,
                modifier = Modifier.fillMaxWidth(),
                enabled = dateTime.isNotBlank() && queueSize > 0,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.AlarmAdd,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
            if (queueSize == 0) {
                SystemEmptyState(
                    title = "队列为空",
                    message = "请先在课程页或队列区添加待抢课程后，再创建定时任务。"
                )
            }
        }
    }
}

@Composable
private fun TargetSummaryCard(
    title: String,
    primaryText: String,
    secondaryText: String,
    tone: SystemTone,
    onClear: (() -> Unit)?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = NeuInsetBackground,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SystemStatusBadge(text = title, tone = tone)
                }
                Text(
                    text = primaryText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = secondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onClear != null) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "清除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun NumericField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.outline,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun ScheduleWarningDialog(
    onConfirm: () -> Unit,
    onDismissForever: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = SemanticWarning
                )
                Text(
                    text = "定时模式提示",
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        text = {
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
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "我已知晓",
                onClick = onConfirm,
                modifier = Modifier.widthIn(min = 120.dp)
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "不再提示",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable(onClick = onDismissForever)
                )
                Text(
                    text = "取消",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable(onClick = onDismiss)
                )
            }
        }
    )
}

@Composable
private fun LogConsole(
    logText: String,
    onClearLog: () -> Unit
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(logText) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    SystemCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "运行日志",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onClearLog) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "清空日志",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF1A1D23),
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(14.dp)
            ) {
                Text(
                    text = logText.ifBlank { "[system] waiting for next action..." },
                    modifier = Modifier.verticalScroll(scrollState),
                    color = Color(0xFFA8D08D),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

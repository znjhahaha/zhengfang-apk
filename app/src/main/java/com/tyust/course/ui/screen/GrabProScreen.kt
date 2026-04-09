package com.tyust.course.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.tyust.course.ui.theme.*
import com.tyust.course.ui.system.*
@OptIn(ExperimentalAnimationApi::class)
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
    // 🔧 清除目标课程
    onClearTargetCourse: (() -> Unit)? = null,
    // Scheduled grab parameters
    schoolName: String = "",
    courseKeywords: String = "",
    onCourseKeywordsChange: ((String) -> Unit)? = null,
    scheduledDateTime: String = "",
    onScheduledDateTimeChange: ((String) -> Unit)? = null,
    isScheduledMode: Boolean = false,
    onScheduledModeChange: ((Boolean) -> Unit)? = null,
    onScheduledStart: (() -> Unit)? = null,
    hasScheduledTask: Boolean = false,
    scheduledTaskInfo: String = "",
    onCancelScheduledTask: (() -> Unit)? = null,
    onPickDateTime: (() -> Unit)? = null,
    // Queue parameters
    queue: List<com.tyust.course.model.Course> = emptyList(),
    queueVersion: Int = 0,  // 🔧 版本号，用于强制 UI 重组
    currentQueueIndex: Int = 0,
    queueItemStatuses: Map<String, GrabQueueItemStatus> = emptyMap(),
    isParallelMode: Boolean = false,
    onParallelModeChange: ((Boolean) -> Unit)? = null,
    onQueueMoveItem: ((Int, Int) -> Unit)? = null,
    onQueueRemoveItem: ((Int) -> Unit)? = null,
    onQueueToggleMode: ((Int) -> Unit)? = null,  // 🔧 切换精确/智能模式
    onQueueToggleAllMode: ((Boolean) -> Unit)? = null, // 🔧 一键设置模式
    isExactModeGlobal: Boolean = true, // 🔧 全局模式状态
    onQueueClear: (() -> Unit)? = null,
    onAddCourse: (() -> Unit)? = null,
    // Warning dialog control
    showScheduleWarning: Boolean = true,
    onDismissWarningForever: (() -> Unit)? = null,
    showQueueModeLabels: Boolean = true, // 🔧 控制是否显示精确/智能模式标签
    // 🔧 模糊匹配捡漏模式
    isFuzzyMatchMode: Boolean = false,
    onFuzzyMatchModeChange: ((Boolean) -> Unit)? = null,
    fuzzyMatchTarget: String? = null,
    onStartFuzzyMatch: (() -> Unit)? = null,
    onClearFuzzyMatchTarget: (() -> Unit)? = null
) {
    val scrollState = rememberLazyListState()
    val context = LocalContext.current
    
    var localScheduledMode by remember { mutableStateOf(isScheduledMode) }
    var localKeywords by remember { mutableStateOf(courseKeywords) }
    var localDateTime by remember { mutableStateOf(scheduledDateTime) }
    var showWarningDialog by remember { mutableStateOf(false) }
    
    LaunchedEffect(scheduledDateTime) { localDateTime = scheduledDateTime }
    LaunchedEffect(courseKeywords) { localKeywords = courseKeywords }
    LaunchedEffect(isScheduledMode) { localScheduledMode = isScheduledMode }
    
    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            title = { Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF9800))
                Spacer(modifier = Modifier.width(8.dp))
                Text("定时抢课注意事项")
            } },
            text = {
                Column {
                    Text("系统限制说明：", fontWeight = FontWeight.Bold)
                    Text("• 安卓系统可能会杀后台\n• 锁屏可能导致闹钟延迟\n• 建议保持屏幕常亮", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("建议操作：", fontWeight = FontWeight.Bold, color = PrimaryPurple)
                    Text("• 加入电池优化白名单\n• 抢课前手动打开App确保存活", fontSize = 14.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = { 
                        showWarningDialog = false
                        localScheduledMode = true
                        onScheduledModeChange?.invoke(true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SystemBlue)
                ) { Text("我已知晓") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showWarningDialog = false
                        localScheduledMode = true
                        onScheduledModeChange?.invoke(true)
                        onDismissWarningForever?.invoke()
                    }) { Text("不再提示", color = Color.Gray) }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { showWarningDialog = false }) { Text("取消") }
                }
            }
        )
    }
    
    Scaffold(
        floatingActionButton = {
            // 使用 AnimatedVisibility 为 FAB 添加动画
            AnimatedVisibility(
                visible = localScheduledMode && !hasScheduledTask,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = { onScheduledStart?.invoke() },
                    containerColor = SystemBlue,
                    contentColor = Color.White,
                    icon = { Icon(Icons.Default.AlarmAdd, contentDescription = null) },
                    text = { Text("添加定时任务") },
                    expanded = scrollState.isScrollInProgress.not(),
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp)
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .background(Neutral50) // iOS style background color
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            state = scrollState
        ) {
            // 1. Status Header + Statistics (Brutalist Data)
            item {
                BrutalistStatusDashboard(
                    isRunning = isRunning, 
                    hasScheduledTask = hasScheduledTask,
                    successCount = successCount,
                    failCount = failCount,
                    retryCount = retryCount
                )
            }

            // 3. Mode Switcher
            item {
                ModeSwitcherCard(
                    isScheduledMode = localScheduledMode,
                    onModeChange = { newMode ->
                        if (newMode && !localScheduledMode && showScheduleWarning) {
                            showWarningDialog = true
                        } else {
                            localScheduledMode = newMode
                            onScheduledModeChange?.invoke(newMode)
                        }
                    }
                )
            }

            // 4. Main Content Area (Animated Switch)
            item {
                AnimatedContent(
                    targetState = localScheduledMode,
                    transitionSpec = {
                        fadeIn() + slideInVertically { height -> height / 10 } with
                        fadeOut() + slideOutVertically { height -> -height / 10 }
                    },
                    label = "ModeSwitch"
                ) { isScheduled ->
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (isScheduled) {
                            ScheduledTaskForm(
                                schoolName = schoolName,
                                dateTime = localDateTime,
                                onDateTimeClick = { onPickDateTime?.invoke() },
                                hasTask = hasScheduledTask,
                                taskInfo = scheduledTaskInfo,
                                onCancelTask = {
                                    localScheduledMode = false
                                    onCancelScheduledTask?.invoke()
                                },
                                queueSize = queue.size,
                                isRunning = isRunning,  // 🔧 传入抢课状态
                                onStop = onStop  // 🔧 传入停止回调
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
                                    // 🔧 检查是否有手动添加的课程 (classId 为空)
                                    val manualCourses = queue.filter { it.classId.isNullOrEmpty() }
                                    if (manualCourses.isNotEmpty()) {
                                        Toast.makeText(context, "⚠️ 包含 ${manualCourses.size} 门手动添加课程，捡漏模式不支持", Toast.LENGTH_LONG).show()
                                    } else {
                                        onStart()
                                    }
                                },
                                onStop = onStop,
                                onClearTargetCourse = onClearTargetCourse,
                                queueSize = queue.size, // 🔧 传递队列大小
                                // 🔧 模糊匹配模式参数
                                isFuzzyMatchMode = isFuzzyMatchMode,
                                onFuzzyMatchModeChange = onFuzzyMatchModeChange,
                                fuzzyMatchTarget = fuzzyMatchTarget,
                                onStartFuzzyMatch = onStartFuzzyMatch,
                                onClearFuzzyMatchTarget = onClearFuzzyMatchTarget
                            )
                        }
                    }
                }
            }
            
            // 5. Grab Queue Header (Always visible in this layout)
            if (localScheduledMode || queue.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), // Add inset group feel
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, Neutral200)
                    ) {
                         GrabQueueHeader(
                            queueSize = queue.size,
                            isParallelMode = isParallelMode,
                            onParallelModeChange = { onParallelModeChange?.invoke(it) },
                            onClearQueue = { onQueueClear?.invoke() },
                            isRunning = isRunning,
                            showMode = showQueueModeLabels, // 🔧 传递显示控制
                            isExactModeGlobal = isExactModeGlobal, // 🔧 传递全局模式状态
                            onToggleAllMode = onQueueToggleAllMode, // 🔧 传递一键设置回调
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                
                // 6. Queue Items (Using extension function for items)
                grabQueueItems(
                    queue = queue,
                    currentIndex = currentQueueIndex,
                    itemStatuses = queueItemStatuses,
                    isRunning = isRunning,
                    isParallelMode = isParallelMode,
                    queueVersion = queueVersion, // 🔧 传递版本号强制重组
                    onMoveItem = { from, to -> onQueueMoveItem?.invoke(from, to) },
                    onRemoveItem = { onQueueRemoveItem?.invoke(it) },
                    onAddCourse = { onAddCourse?.invoke() },
                    onToggleMode = { onQueueToggleMode?.invoke(it) },  // 🔧 传递模式切换回调
                    showMode = showQueueModeLabels // 🔧 传递显示控制参数
                )
            }

            // 7. Log Console
            item {
                LogConsole(logText, onClearLog)
            }
            
            item {
                Spacer(modifier = Modifier.height(60.dp)) // Bottom padding for FAB
            }
        }
    }
}

@Composable
fun BrutalistStatusDashboard(
    isRunning: Boolean,
    hasScheduledTask: Boolean,
    successCount: Int,
    failCount: Int,
    retryCount: Int
) {
    val (statusColor, icon, text, pulse) = when {
        hasScheduledTask -> Quadruple(SemanticWarning, Icons.Outlined.Timer, "定时待机", true)
        isRunning -> Quadruple(SystemBlue, Icons.Default.Autorenew, "执行中", true)
        else -> Quadruple(Neutral500, Icons.Default.Speed, "已就绪", false)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceWhite, RoundedCornerShape(20.dp))
            .padding(24.dp)
    ) {
        // 顶栏状态标识
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = statusColor, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = text, 
                style = MaterialTheme.typography.titleLarge, 
                color = Neutral900, 
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp
            )
            Spacer(modifier = Modifier.weight(1f))
            if (pulse) {
                // 呼吸状态灯
                Box(modifier = Modifier.size(10.dp).background(statusColor, CircleShape))
            }
        }
        
        Spacer(modifier = Modifier.height(28.dp))
        
        // 极致的数据展示
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BrutalistDataBlock(label = "尝试", value = retryCount.toString(), color = Neutral900, modifier = Modifier.weight(1f))
            BrutalistDataBlock(label = "成功", value = successCount.toString(), color = SemanticSuccess, modifier = Modifier.weight(1f))
            BrutalistDataBlock(label = "失败", value = failCount.toString(), color = SemanticDanger, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun RowScope.BrutalistDataBlock(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // 使用内置的等宽数字体验，极高字重
        Text(
            text = value,
            fontSize = 38.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            color = color,
            letterSpacing = (-1.5).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Neutral500,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun ModeSwitcherCard(isScheduledMode: Boolean, onModeChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Neutral100, RoundedCornerShape(8.dp))
            .padding(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabButton(
                text = "捡漏",
                selected = !isScheduledMode,
                icon = Icons.Default.FlashOn,
                onClick = { onModeChange(false) },
                modifier = Modifier.weight(1f)
            )
            TabButton(
                text = "定时",
                selected = isScheduledMode,
                icon = Icons.Default.Schedule,
                onClick = { onModeChange(true) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun TabButton(text: String, selected: Boolean, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bgColor = if (selected) SurfaceWhite else Color.Transparent
    val contentColor = if (selected) Neutral900 else Neutral500
    
    Button(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bgColor, contentColor = contentColor),
        shape = RoundedCornerShape(6.dp),
        elevation = if (selected) ButtonDefaults.buttonElevation(defaultElevation = 1.dp) else ButtonDefaults.buttonElevation(0.dp),
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text)
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ImmediateGrabForm(
    targetCourseName: String?,
    targetCourseTeacher: String?,
    interval: String,
    onIntervalChange: (String) -> Unit,
    maxRetry: String,
    onMaxRetryChange: (String) -> Unit,
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearTargetCourse: (() -> Unit)? = null, // 🔧 清除目标课程
    queueSize: Int = 0, // 🔧 队列大小
    // 🔧 模糊匹配模式
    isFuzzyMatchMode: Boolean = false,
    onFuzzyMatchModeChange: ((Boolean) -> Unit)? = null,
    fuzzyMatchTarget: String? = null, // 监控的课程类别名
    onStartFuzzyMatch: (() -> Unit)? = null,
    onClearFuzzyMatchTarget: (() -> Unit)? = null
) {
    // 🔧 动画状态
    val themeColor by animateColorAsState(
        targetValue = if (isFuzzyMatchMode) SemanticWarning else SystemBlue,
        animationSpec = tween(durationMillis = 300)
    )
    val cardBgColor by animateColorAsState(
        targetValue = if (isFuzzyMatchMode) Color(0xFFFFF7ED) else Color(0xFFF0F5FF),
        animationSpec = tween(durationMillis = 300)
    )

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Neutral200)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 🔧 模式切换：精确捡漏 / 模糊匹配
            if (onFuzzyMatchModeChange != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 精确捡漏按钮
                    Button(
                        onClick = { onFuzzyMatchModeChange(false) },
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!isFuzzyMatchMode) SystemBlue else Neutral100,
                            contentColor = if (!isFuzzyMatchMode) Color.White else Neutral500
                        )
                    ) {
                        Icon(Icons.Default.GpsFixed, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("精确捡漏", fontSize = 12.sp)
                    }
                    // 模糊匹配按钮
                    Button(
                        onClick = { onFuzzyMatchModeChange(true) },
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isFuzzyMatchMode) SemanticWarning else Neutral100,
                            contentColor = if (isFuzzyMatchMode) Color.White else Neutral500
                        )
                    ) {
                        Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("模糊匹配", fontSize = 12.sp)
                    }
                }
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f), thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Target Course Info - 使用 AnimatedContent 实现标题和内容的平滑切换
            AnimatedContent(
                targetState = isFuzzyMatchMode,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) with fadeOut(animationSpec = tween(300))
                }
            ) { fuzzyMode ->
                Column {
                    Text(
                        if (fuzzyMode) "监控目标" else "目标课程", 
                        style = MaterialTheme.typography.labelMedium, 
                        color = themeColor, 
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(cardBgColor, RoundedCornerShape(12.dp))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (fuzzyMode) Icons.Default.Radar else Icons.Default.Book, 
                            contentDescription = null, 
                            tint = themeColor, 
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            if (fuzzyMode) {
                                // 模糊匹配模式显示
                                if (!fuzzyMatchTarget.isNullOrEmpty()) {
                                    Text(fuzzyMatchTarget, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.Black)
                                    Text("监控该类别所有教学班人数变化", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                } else {
                                    Text("未设置监控目标", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.Gray)
                                    Text("请在\"课程\"页面点击课程组的\"监控\"按钮", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                            } else if (targetCourseName != null) {
                                Text(targetCourseName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.Black)
                                Text("教师: ${targetCourseTeacher ?: "未知"}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                            } else if (queueSize > 0) {
                                Text("已就绪: 队列抢课模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = SystemBlue)
                                Text("共 ${queueSize} 门课程待尝试", style = MaterialTheme.typography.bodySmall, color = Neutral500)
                            } else {
                                Text("未选择课程", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.Gray)
                                Text("请在\"课程\"页面长按选择，或在下方添加队列", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                        
                        // 清除按钮
                        if (fuzzyMode && !fuzzyMatchTarget.isNullOrEmpty() && onClearFuzzyMatchTarget != null) {
                            IconButton(onClick = { onClearFuzzyMatchTarget() }) {
                                Icon(Icons.Default.Close, contentDescription = "清除监控目标", tint = Color.Gray, modifier = Modifier.size(20.dp))
                            }
                        } else if (!fuzzyMode && targetCourseName != null && onClearTargetCourse != null) {
                            IconButton(onClick = { onClearTargetCourse() }) {
                                Icon(Icons.Default.Close, contentDescription = "清除目标课程", tint = Color.Gray, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Settings
            Text("参数配置", style = MaterialTheme.typography.labelMedium, color = Color.Gray, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = interval,
                    onValueChange = onIntervalChange,
                    label = { Text("间隔 (ms)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = maxRetry,
                    onValueChange = onMaxRetryChange,
                    label = { Text("最大重试") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Action Buttons - 使用 AnimatedContent 实现底部按钮切换
            AnimatedContent(
                targetState = isFuzzyMatchMode,
                transitionSpec = {
                    slideInVertically { it } + fadeIn() with slideOutVertically { -it } + fadeOut()
                }
            ) { fuzzyMode ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (fuzzyMode) {
                        // 模糊匹配模式按钮
                        Button(
                            onClick = { onStartFuzzyMatch?.invoke() },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                            enabled = !isRunning && !fuzzyMatchTarget.isNullOrEmpty(),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("开始监控", fontSize = 14.sp, maxLines = 1)
                        }
                    } else {
                        // 精确捡漏模式按钮
                        Button(
                            onClick = onStart,
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SemanticSuccess),
                            enabled = !isRunning && (targetCourseName != null || queueSize > 0),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("启动", fontSize = 15.sp, maxLines = 1)
                        }
                    }
                    
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SemanticDanger),
                        enabled = isRunning,
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("停止", fontSize = 15.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun ScheduledTaskForm(
    schoolName: String,
    dateTime: String,
    onDateTimeClick: () -> Unit,
    hasTask: Boolean,
    taskInfo: String,
    onCancelTask: () -> Unit,
    queueSize: Int = 0,
    isRunning: Boolean = false,  // 🔧 新增：是否正在抢课
    onStop: (() -> Unit)? = null  // 🔧 新增：停止抢课回调
) {
    if (hasTask) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            colors = CardDefaults.cardColors(containerColor = if (isRunning) Color(0xFFE8F5E9) else Color(0xFFFFF7ED)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (isRunning) SemanticSuccess else SemanticWarning)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isRunning) Icons.Default.PlayCircle else Icons.Default.AlarmOn, 
                        contentDescription = null, 
                        tint = if (isRunning) Color(0xFF2E7D32) else Color(0xFFE65100)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        if (isRunning) "正在抢课中..." else "任务已计划", 
                        style = MaterialTheme.typography.titleMedium, 
                        fontWeight = FontWeight.Bold, 
                        color = if (isRunning) Color(0xFF2E7D32) else Color(0xFFE65100)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(taskInfo, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF5D4037))
                Spacer(modifier = Modifier.height(16.dp))
                
                // 🔧 当正在抢课时，显示红色的"停止抢课"按钮
                if (isRunning && onStop != null) {
                    Button(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("停止抢课", color = Color.White)
                    }
                } else {
                    // 未运行时显示"取消任务"按钮
                    OutlinedButton(
                        onClick = onCancelTask,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD84315))
                    ) {
                        Text("取消任务")
                    }
                }
            }
        }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            elevation = CardDefaults.cardElevation(0.dp),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, Neutral200)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // School
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Domain, contentDescription = null, tint = SystemBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(schoolName.ifEmpty { "未登录" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
                Divider(modifier = Modifier.padding(vertical = 12.dp).alpha(0.1f))
                
                // Queue status hint
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        if (queueSize > 0) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (queueSize > 0) Color(0xFF4CAF50) else Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (queueSize > 0) "队列中有 $queueSize 门课程待抢" else "请在下方队列中添加课程",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (queueSize > 0) Color(0xFF4CAF50) else Color.Gray
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // DateTime Input
                Text("开始时间", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = dateTime,
                    onValueChange = {},
                    placeholder = { Text("选择启动时间") },
                    modifier = Modifier.fillMaxWidth().clickable { onDateTimeClick() },
                    shape = RoundedCornerShape(12.dp),
                    readOnly = true,
                    enabled = false,
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    leadingIcon = { Icon(Icons.Default.Event, contentDescription = null) },
                    trailingIcon = { 
                        IconButton(onClick = onDateTimeClick) {
                            Icon(Icons.Default.EditCalendar, contentDescription = null, tint = SystemBlue)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun LogConsole(logText: String, onClearLog: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundDark)
            .padding(top = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Terminal, contentDescription = null, tint = Neutral500, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("PROCESS LOG", color = Neutral500, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            IconButton(onClick = onClearLog, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", tint = Neutral500, modifier = Modifier.size(16.dp))
            }
        }
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val scroll = rememberScrollState()
            LaunchedEffect(logText) { scroll.animateScrollTo(scroll.maxValue) }
            
            Text(
                text = logText.ifEmpty { "> system standby...\n" },
                color = Color(0xFFA3BE8C), // 典型的北极星色系代码绿
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
                modifier = Modifier.verticalScroll(scroll)
            )
        }
    }
}

data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)


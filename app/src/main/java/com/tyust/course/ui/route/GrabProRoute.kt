package com.tyust.course.ui.route

import com.tyust.course.ui.system.GlassToaster
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import com.tyust.course.demo.DemoData
import com.tyust.course.manager.SmartSelector
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.receiver.GrabAlarmReceiver
import com.tyust.course.service.GrabService
import com.tyust.course.ui.screen.GrabProScreen
import com.tyust.course.ui.screen.GrabQueueItemStatus
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemPicker
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSecondaryButton
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@Composable
fun GrabProRoute() {
    val context = LocalContext.current
    val isDemoMode = remember { UserManager.getInstance().isDemoMode }
    val demoScope = rememberCoroutineScope()
    
    // Settings State (Persisted in SharedPreferences)
    val prefs = remember { context.getSharedPreferences("grab_pro_prefs", Context.MODE_PRIVATE) }
    val accountKey = UserManager.getInstance().currentAccountKey
    val accountStorageKey = UserManager.getInstance().currentAccountStorageKey
    fun scopedPrefKey(key: String) = "${key}_${accountStorageKey}"
    fun Intent.putGrabAccountExtras() {
        putExtra(GrabService.EXTRA_ACCOUNT_KEY, accountKey)
        putExtra(GrabService.EXTRA_ACCOUNT_STORAGE_KEY, accountStorageKey)
    }
    
    // UI State - 从持久化存储加载
    var isRunning by remember { mutableStateOf(false) }
    var demoRunJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var demoQueueIndex by remember { mutableIntStateOf(-1) }
    var successCount by remember { mutableIntStateOf(0) }
    var failCount by remember { mutableIntStateOf(0) }
    var retryCount by remember { mutableIntStateOf(0) }
    var targetCourseName by remember { mutableStateOf<String?>(null) }
    var targetCourseTeacher by remember { mutableStateOf<String?>(null) }
    var logText by remember {
        mutableStateOf(if (isDemoMode) "" else prefs.getString(scopedPrefKey("log_text"), "").orEmpty())
    }
    
    var interval by remember {
        mutableStateOf(if (isDemoMode) "800" else prefs.getString(scopedPrefKey("interval"), "1500") ?: "1500")
    }
    var maxRetry by remember {
        mutableStateOf(if (isDemoMode) "20" else prefs.getString(scopedPrefKey("max_retry"), "100") ?: "100")
    }
    var courseKeywords by remember {
        mutableStateOf(if (isDemoMode) "" else prefs.getString(scopedPrefKey("course_keywords"), "").orEmpty())
    }
    var scheduledDateTime by remember {
        mutableStateOf(if (isDemoMode) "" else prefs.getString(scopedPrefKey("scheduled_datetime"), "").orEmpty())
    }
    var isScheduledMode by remember {
        mutableStateOf(if (isDemoMode) false else prefs.getBoolean(scopedPrefKey("scheduled_mode"), false))
    }
    var hasScheduledTask by remember {
        mutableStateOf(if (isDemoMode) false else prefs.getBoolean(scopedPrefKey("has_scheduled_task"), false))
    }
    var scheduledTaskInfo by remember {
        mutableStateOf(if (isDemoMode) "" else prefs.getString(scopedPrefKey("scheduled_task_info"), "").orEmpty())
    }
    var showGlassDateTimePicker by remember { mutableStateOf(false) }
    
    // 演示队列只存在于当前页面内存中，不复用真实账号的 SmartSelector 状态。
    var queue by remember {
        mutableStateOf(if (isDemoMode) DemoData.grabQueue() else SmartSelector.getInstance().queue.toList())
    }
    var queueVersion by remember { mutableIntStateOf(0) }
    var isParallelMode by remember {
        mutableStateOf(if (isDemoMode) false else prefs.getBoolean(scopedPrefKey("parallel_mode"), false))
    }
    var isExactModeGlobal by remember {
        mutableStateOf(if (isDemoMode) true else prefs.getBoolean(scopedPrefKey("exact_mode_global"), true))
    }
    
    var isFuzzyMatchMode by remember {
        mutableStateOf(if (isDemoMode) false else prefs.getBoolean(scopedPrefKey("fuzzy_match_mode"), false))
    }
    var fuzzyMatchTarget by remember {
        mutableStateOf<String?>(if (isDemoMode) queue.firstOrNull()?.name else SmartSelector.getInstance().fuzzyMatchCourseName)
    }

    // 🔧 辅助函数：添加日志并限制在 100 条以内，防止变卡
    fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val newEntry = "[$timestamp] $message\n"
        val currentLines = (logText + newEntry).split("\n").filter { it.isNotBlank() }
        logText = currentLines.takeLast(100).joinToString("\n") + "\n"
    }
    
    // 使用课程ID作为key的状态Map，支持持久化
    var queueItemStatuses by remember {
        val loaded = if (isDemoMode) {
            queue.associate { course ->
                "${course.name}_${course.teacher}_${course.time}" to GrabQueueItemStatus.WAITING
            }
        } else {
            val savedStatuses = prefs.getString(scopedPrefKey("queue_item_statuses"), null)
            if (savedStatuses != null) {
                try {
                    val map = mutableMapOf<String, GrabQueueItemStatus>()
                    savedStatuses.split(";").forEach { pair ->
                        val parts = pair.split("=")
                        if (parts.size == 2) {
                            val status = when(parts[1]) {
                                "SUCCESS" -> GrabQueueItemStatus.SUCCESS
                                "FAILED" -> GrabQueueItemStatus.FAILED
                                "GRABBING" -> GrabQueueItemStatus.GRABBING
                                else -> GrabQueueItemStatus.WAITING
                            }
                            map[parts[0]] = status
                        }
                    }
                    map.toMap()
                } catch (e: Exception) { emptyMap() }
            } else emptyMap()
        }
        mutableStateOf<Map<String, GrabQueueItemStatus>>(loaded)
    }
    var showAddCourseDialog by remember { mutableStateOf(false) }
    var manualCourseInput by remember { mutableStateOf("") }
    // 🔧 新增：分开的输入框状态
    var inputCourseName by remember { mutableStateOf("") }
    var inputTeacher by remember { mutableStateOf("") }
    var selectedWeekday by remember { mutableStateOf("") }
    var selectedPeriod by remember { mutableStateOf("") }
    
    // 警告对话框控制
    var showScheduleWarning by remember {
        mutableStateOf(if (isDemoMode) false else prefs.getBoolean(scopedPrefKey("show_schedule_warning"), true))
    }
    
    // 保存队列状态的辅助函数
    fun saveQueueStatuses() {
        if (isDemoMode) return
        val statusString = queueItemStatuses.entries
            .filter { it.value != GrabQueueItemStatus.WAITING }
            .joinToString(";") { "${it.key}=${it.value.name}" }
        prefs.edit().putString(scopedPrefKey("queue_item_statuses"), statusString).apply()
    }
    
    fun saveState() {
        if (isDemoMode) return
        saveQueueStatuses()
        prefs.edit()
            .putString(scopedPrefKey("interval"), interval)
            .putString(scopedPrefKey("max_retry"), maxRetry)
            .putString(scopedPrefKey("course_keywords"), courseKeywords)
            .putString(scopedPrefKey("scheduled_datetime"), scheduledDateTime)
            .putBoolean(scopedPrefKey("scheduled_mode"), isScheduledMode)
            .putBoolean(scopedPrefKey("has_scheduled_task"), hasScheduledTask)
            .putString(scopedPrefKey("scheduled_task_info"), scheduledTaskInfo)
            .putString(scopedPrefKey("log_text"), logText)
            .putBoolean(scopedPrefKey("parallel_mode"), isParallelMode)  // 保存并行模式
            .putBoolean(scopedPrefKey("exact_mode_global"), isExactModeGlobal) // 🔧 保存全局模式状态
            .apply()
    }
    
    fun refreshQueue() {
        if (isDemoMode) {
            queue = queue.map { it.copy() }
        } else {
            queue = SmartSelector.getInstance().queue.map { it.copy() }.toMutableList()
        }
        queueVersion++
    }
    
    LaunchedEffect(isDemoMode) {
        if (isDemoMode) {
            queue = DemoData.grabQueue()
            queueItemStatuses = queue.associate { course ->
                "${course.name}_${course.teacher}_${course.time}" to GrabQueueItemStatus.WAITING
            }
            logText = ""
            appendLog("演示队列已就绪，共 ${queue.size} 门课程")
            return@LaunchedEffect
        }

        SmartSelector.getInstance().init(context)
        SmartSelector.getInstance().restoreFuzzyMatchSettings()
        val course = SmartSelector.getInstance().targetCourse
        targetCourseName = course?.name
        targetCourseTeacher = course?.teacher
        isRunning = SmartSelector.getInstance().isRunning
        fuzzyMatchTarget = SmartSelector.getInstance().fuzzyMatchCourseName
        refreshQueue()
    }

    DisposableEffect(context, isDemoMode) {
        if (isDemoMode) {
            onDispose {
                demoRunJob?.cancel()
                isRunning = false
            }
        } else {
            val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == GrabService.BROADCAST_UPDATE) {
                    val eventAccountStorageKey = intent.getStringExtra(GrabService.EXTRA_ACCOUNT_STORAGE_KEY).orEmpty()
                    if (eventAccountStorageKey.isNotBlank() && eventAccountStorageKey != accountStorageKey) return

                    val logMessage = intent.getStringExtra(GrabService.EXTRA_LOG_MESSAGE) ?: ""
                    successCount = intent.getIntExtra(GrabService.EXTRA_SUCCESS_COUNT, successCount)
                    failCount = intent.getIntExtra(GrabService.EXTRA_FAIL_COUNT, failCount)
                    retryCount = intent.getIntExtra(GrabService.EXTRA_RETRY_COUNT, retryCount)
                    isRunning = intent.getBooleanExtra(GrabService.EXTRA_IS_RUNNING, isRunning)
                    
                    if (logMessage.isNotEmpty()) {
                        appendLog(logMessage)
                    }
                    
                    // 检查是否需要刷新队列
                    if (intent.getBooleanExtra(GrabService.EXTRA_QUEUE_UPDATED, false)) {
                        refreshQueue()
                        
                        // 1. 处理特定课程的状态更新
                        val courseId = intent.getStringExtra(GrabService.EXTRA_COURSE_ID)
                        val courseName = intent.getStringExtra(GrabService.EXTRA_COURSE_NAME_STATUS)
                        val courseStatus = intent.getStringExtra(GrabService.EXTRA_COURSE_STATUS)
                        
                        var courseKey = ""
                        if (!courseName.isNullOrEmpty()) {
                            // 查找匹配的课程以构建正确的 key
                            val matchedCourse = queue.find { 
                                it.name == courseName || (it.name?.contains(courseName) == true) || (courseName.contains(it.name ?: ""))
                            }
                            if (matchedCourse != null) {
                                courseKey = "${matchedCourse.name ?: ""}_${matchedCourse.teacher ?: ""}_${matchedCourse.time ?: ""}"
                            } else {
                                courseKey = "${courseName}__"
                            }
                        }

                        if (courseKey.isNotEmpty() && courseStatus != null) {
                            val newStatuses = queueItemStatuses.toMutableMap()
                            newStatuses[courseKey] = when (courseStatus) {
                                "success" -> com.tyust.course.ui.screen.GrabQueueItemStatus.SUCCESS
                                "failed" -> com.tyust.course.ui.screen.GrabQueueItemStatus.FAILED
                                "grabbing" -> com.tyust.course.ui.screen.GrabQueueItemStatus.GRABBING
                                else -> com.tyust.course.ui.screen.GrabQueueItemStatus.WAITING
                            }
                            queueItemStatuses = newStatuses
                            saveQueueStatuses()
                        }

                        // 2. 当服务停止时，将剩下的还在 GRABBING 状态且没有变为 SUCCESS 的多余课程标记为 FAILED
                        val wasRunning = intent.getBooleanExtra(GrabService.EXTRA_IS_RUNNING, true)
                        if (!wasRunning) {
                            val newStatuses = queueItemStatuses.toMutableMap()
                            var changed = false
                            newStatuses.forEach { (key, status) ->
                                if (status == com.tyust.course.ui.screen.GrabQueueItemStatus.GRABBING) {
                                    newStatuses[key] = com.tyust.course.ui.screen.GrabQueueItemStatus.FAILED
                                    changed = true
                                }
                            }
                            if (changed) {
                                queueItemStatuses = newStatuses
                                saveQueueStatuses()
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(GrabService.BROADCAST_UPDATE)
        // 使用 ContextCompat 统一处理 registerReceiver
        androidx.core.content.ContextCompat.registerReceiver(
            context, 
            receiver, 
            filter, 
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        
            onDispose {
                try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
                saveState()
            }
        }
    }
    
    // Logic Actions
    fun startGrabbing() {
        if (isDemoMode) {
            if (queue.isEmpty()) {
                GlassToaster.show("请先在队列中添加课程")
                return
            }

            demoRunJob?.cancel()
            successCount = 0
            failCount = 0
            retryCount = 0
            demoQueueIndex = -1
            queueItemStatuses = queue.associate { course ->
                "${course.name}_${course.teacher}_${course.time}" to GrabQueueItemStatus.WAITING
            }
            isRunning = true
            appendLog("开始演示抢课：${queue.size} 门课程（仅本地模拟）")
            GlassToaster.show("演示抢课已启动")

            demoRunJob = demoScope.launch {
                queue.forEachIndexed { index, course ->
                    val courseKey = "${course.name}_${course.teacher}_${course.time}"
                    demoQueueIndex = index
                    queueItemStatuses = queueItemStatuses + (courseKey to GrabQueueItemStatus.GRABBING)
                    appendLog("正在提交：${course.name} · ${course.teacher}")

                    repeat(if (index == 0) 2 else 1) {
                        delay((interval.toLongOrNull() ?: 800L).coerceIn(450L, 1500L))
                        retryCount++
                        if (index == 1 || index == 3) {
                            failCount++
                            appendLog("${course.name} 暂无余量，继续监控")
                        } else {
                            appendLog("${course.name} 请求已响应")
                        }
                    }

                    val status = when (index) {
                        0, 2 -> GrabQueueItemStatus.SUCCESS
                        1 -> GrabQueueItemStatus.FAILED
                        else -> GrabQueueItemStatus.WAITING
                    }
                    queueItemStatuses = queueItemStatuses + (courseKey to status)
                    when (status) {
                        GrabQueueItemStatus.SUCCESS -> {
                            successCount++
                            appendLog("抢课成功：${course.name}")
                        }
                        GrabQueueItemStatus.FAILED -> appendLog("本轮未成功：${course.name}")
                        else -> Unit
                    }
                }
                demoQueueIndex = -1
                isRunning = false
                appendLog("演示轮询已完成：成功 $successCount 门，可再次开始演示")
            }
            return
        }

        val school = UserManager.getInstance().currentSchool
        if (school == null) {
            GlassToaster.show("请先登录")
            return
        }

        // 🔧 优先检查队列，如果队列不为空，则启动队列模式
        val queueList = SmartSelector.getInstance().queue
        val targetCourse = SmartSelector.getInstance().targetCourse

        if (queueList.isNotEmpty()) {
            // 🔧 直接队列模式 (Direct Grab Queue)
            successCount = 0
            failCount = 0
            retryCount = 0
            
            // 🔧 如果是捡漏模式（非定时），强制所有课程使用精确模式
            if (!isScheduledMode) {
                SmartSelector.getInstance().setAllExactMatchMode(true)
                refreshQueue()
            }
            
            try { SmartSelector.getInstance().setInterval(interval.toIntOrNull() ?: 1500) } catch (e: Exception) {}
            try { SmartSelector.getInstance().setMaxRetry(maxRetry.toIntOrNull() ?: 100) } catch (e: Exception) {}
            
            val serviceIntent = Intent(context, GrabService::class.java).apply {
                action = GrabService.ACTION_START_QUEUE // 🔧 使用新开发的直接队列模式
                putGrabAccountExtras()
                putExtra(GrabService.EXTRA_INTERVAL, interval.toIntOrNull() ?: 1500)
                putExtra(GrabService.EXTRA_MAX_RETRY, maxRetry.toIntOrNull() ?: 100)
                putExtra(GrabService.EXTRA_PARALLEL_MODE, isParallelMode)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            appendLog("开始队列抢课：${queueList.size} 门课程（直接请求模式）")
            isRunning = true
            GlassToaster.show("队列抢课进程已启动")
        } else if (targetCourse != null) {
            // 单课模式 (保持原有逻辑作为 fallback)
            successCount = 0
            failCount = 0
            retryCount = 0
            
            try { SmartSelector.getInstance().setInterval(interval.toIntOrNull() ?: 1500) } catch (e: Exception) {}
            try { SmartSelector.getInstance().setMaxRetry(maxRetry.toIntOrNull() ?: 100) } catch (e: Exception) {}
            
            val serviceIntent = Intent(context, GrabService::class.java).apply {
                action = GrabService.ACTION_START
                putGrabAccountExtras()
                putExtra(GrabService.EXTRA_COURSE_NAME, targetCourse.name)
                putExtra(GrabService.EXTRA_COURSE_ID, targetCourse.courseId)
                putExtra(GrabService.EXTRA_INTERVAL, interval.toIntOrNull() ?: 1500)
                putExtra(GrabService.EXTRA_MAX_RETRY, maxRetry.toIntOrNull() ?: 100)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            appendLog("开始后台抢课：${targetCourse.name}")
            isRunning = true
            GlassToaster.show("后台抢课已启动")
        } else {
            GlassToaster.show("请先在「课程」页面长按选择要抢的课程，或在下方添加课程到队列")
        }
    }
    
    fun startFuzzyMatchGrabbing() {
        if (isDemoMode) {
            fuzzyMatchTarget = queue.firstOrNull()?.name
            appendLog("启动演示捡漏监控：${fuzzyMatchTarget ?: "暂无目标"}")
            startGrabbing()
            return
        }

        val school = UserManager.getInstance().currentSchool
        if (school == null) {
            GlassToaster.show("请先登录")
            return
        }
        
        val fuzzyTargetId = SmartSelector.getInstance().fuzzyMatchCourseId
        val fuzzyTargetName = SmartSelector.getInstance().fuzzyMatchCourseName
        
        if (fuzzyTargetId.isNullOrEmpty()) {
            GlassToaster.show("请先设置监控目标")
            return
        }
        
        // 启用模糊匹配模式
        SmartSelector.getInstance().setFuzzyMatchEnabled(true)
        
        val serviceIntent = Intent(context, GrabService::class.java).apply {
            action = GrabService.ACTION_START_FUZZY_MATCH
            putGrabAccountExtras()
            putExtra(GrabService.EXTRA_INTERVAL, interval.toIntOrNull() ?: 2000)
            putExtra(GrabService.EXTRA_MAX_RETRY, maxRetry.toIntOrNull() ?: 999)
            putExtra(GrabService.EXTRA_PARALLEL_MODE, isParallelMode)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        
        appendLog("启动模糊匹配模式：$fuzzyTargetName")
        isRunning = true
        GlassToaster.show("模糊匹配监控已启动：$fuzzyTargetName")
    }
    
    fun stopGrabbing() {
        if (isDemoMode) {
            demoRunJob?.cancel()
            demoRunJob = null
            demoQueueIndex = -1
            isRunning = false
            queueItemStatuses = queueItemStatuses.mapValues { (_, status) ->
                if (status == GrabQueueItemStatus.GRABBING) GrabQueueItemStatus.WAITING else status
            }
            appendLog("已停止演示抢课")
            GlassToaster.show("演示抢课已停止")
            return
        }

        val serviceIntent = Intent(context, GrabService::class.java).apply {
            action = GrabService.ACTION_STOP
            putGrabAccountExtras()
        }
        context.startService(serviceIntent)
        SmartSelector.getInstance().stop()
        SmartSelector.getInstance().setFuzzyMatchEnabled(false) // 🔧 关闭模糊匹配模式
        
        appendLog("已停止抢课")
        isRunning = false
    }
    
    // Scheduled Job Reference (to cancel if needed)
    var scheduledJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    
    // AlarmManager PendingIntent request code
    val ALARM_REQUEST_CODE = 9999

    fun alarmRequestCodeFor(accountStorageKey: String): Int {
        val hash = accountStorageKey.hashCode() and 0x0FFFFFFF
        return ALARM_REQUEST_CODE + hash
    }
    
    // Helper: 取消已设置的闹钟
    fun cancelScheduledAlarm(ctx: Context, requestCode: Int) {
        if (isDemoMode) return
        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ctx, GrabAlarmReceiver::class.java).apply {
            action = GrabAlarmReceiver.ACTION_SCHEDULED_GRAB
        }
        val pendingIntent = PendingIntent.getBroadcast(
            ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
    
    fun createScheduledTask() {
        // 只使用队列中的课程
        if (queue.isEmpty()) { 
            GlassToaster.show("请先在队列中添加课程")
            return 
        }
        if (scheduledDateTime.isBlank()) { GlassToaster.show("请选择开始时间"); return }
        
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val targetTime = try { dateFormat.parse(scheduledDateTime) } catch (e: Exception) { null }
        if (targetTime == null) { GlassToaster.show("日期格式错误"); return }
        
        val now = Date()
        val delayMs = targetTime.time - now.time
        if (delayMs <= 0) { GlassToaster.show("开始时间必须在当前时间之后"); return }

        if (isDemoMode) {
            hasScheduledTask = true
            scheduledTaskInfo = "演示队列: ${queue.size}门\n开始时间: $scheduledDateTime"
            appendLog("演示定时任务已创建：$scheduledDateTime，队列 ${queue.size} 门课程")
            GlassToaster.show("演示定时任务已创建（仅当前页面有效）")
            return
        }
        
        val userManager = UserManager.getInstance()
        val scheduledAccountKey = userManager.currentAccountKey
        val scheduledAccountStorageKey = userManager.currentAccountStorageKey
        val scheduledInterval = interval.toIntOrNull() ?: 1500
        val scheduledMaxRetry = maxRetry.toIntOrNull() ?: 100

        // Cancel any existing scheduled alarm for this account and legacy global alarm
        cancelScheduledAlarm(context, alarmRequestCodeFor(scheduledAccountStorageKey))
        cancelScheduledAlarm(context, ALARM_REQUEST_CODE)
        scheduledJob?.cancel()
        
        // 使用队列中的课程名作为关键词
        val queueKeywords = queue.mapNotNull { it.name }.joinToString(";")
        
        hasScheduledTask = true
        val taskDescription = "队列课程: ${queue.size}门\n开始时间: $scheduledDateTime"
        scheduledTaskInfo = taskDescription
        saveState()
        
        val delayMinutes = delayMs / 60000
        GlassToaster.show("定时任务已创建，将在 ${delayMinutes} 分钟后开始")
        appendLog("定时任务已设置，将在 $scheduledDateTime 开始抢课")
        if (queue.isNotEmpty()) {
            appendLog("队列包含 ${queue.size} 门课程")
        }
        appendLog("使用 AlarmManager 实现，后台也能可靠触发")
        
        // 使用 AlarmManager 设置可靠的定时任务
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, GrabAlarmReceiver::class.java).apply {
            action = GrabAlarmReceiver.ACTION_SCHEDULED_GRAB
            putExtra(GrabAlarmReceiver.EXTRA_COURSE_KEYWORDS, queueKeywords)
            putExtra(GrabAlarmReceiver.EXTRA_ACCOUNT_KEY, scheduledAccountKey)
            putExtra(GrabAlarmReceiver.EXTRA_ACCOUNT_STORAGE_KEY, scheduledAccountStorageKey)
            putExtra(GrabAlarmReceiver.EXTRA_INTERVAL, scheduledInterval)
            putExtra(GrabAlarmReceiver.EXTRA_MAX_RETRY, scheduledMaxRetry)
            putExtra(GrabAlarmReceiver.EXTRA_PARALLEL_MODE, isParallelMode)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmRequestCodeFor(scheduledAccountStorageKey),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val triggerAtMillis = targetTime.time
        
        // 使用 setExactAndAllowWhileIdle 确保即使在省电模式也能触发
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                Log.d("GrabProRoute", "✅ AlarmManager 设置成功: triggerAt=$triggerAtMillis")
            } catch (e: SecurityException) {
                // Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限
                Log.e("GrabProRoute", "AlarmManager 权限问题: ${e.message}")
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
        
        // 同时也使用协程作为备用方案（如果App在前台会更快响应）
        scheduledJob = scope.launch {
            kotlinx.coroutines.delay(delayMs)
            
            // 如果协程先触发（App在前台），直接启动
            if (hasScheduledTask) {
                Log.d("GrabProRoute", "协程触发定时任务")
                val activeManager = UserManager.getInstance()
                if (scheduledAccountKey.isBlank() || scheduledAccountKey == activeManager.currentAccountKey || activeManager.switchToAccount(scheduledAccountKey)) {
                    val serviceIntent = Intent(context, GrabService::class.java).apply {
                        action = GrabService.ACTION_START_QUEUE
                        putExtra(GrabService.EXTRA_ACCOUNT_KEY, scheduledAccountKey)
                        putExtra(GrabService.EXTRA_ACCOUNT_STORAGE_KEY, scheduledAccountStorageKey)
                        putExtra(GrabService.EXTRA_INTERVAL, scheduledInterval)
                        putExtra(GrabService.EXTRA_MAX_RETRY, scheduledMaxRetry)
                        putExtra(GrabService.EXTRA_PARALLEL_MODE, isParallelMode)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    appendLog("定时任务触发，已启动队列抢课服务")
                    isRunning = true
                } else {
                    appendLog("定时任务失败：找不到创建任务的账号，请重新登录")
                    GlassToaster.show("定时任务账号已失效，请重新登录")
                }
                
                hasScheduledTask = false
                saveState()
            }
        }
    }

    GrabProScreen(
        isRunning = isRunning,
        successCount = successCount,
        failCount = failCount,
        retryCount = retryCount,
        targetCourseName = targetCourseName,
        targetCourseTeacher = targetCourseTeacher,
        logText = logText,
        interval = interval,
        onIntervalChange = { interval = it },
        maxRetry = maxRetry,
        onMaxRetryChange = { maxRetry = it },
        onStart = { startGrabbing() },
        onStop = { stopGrabbing() },
        onClearLog = { logText = "" },
        onClearTargetCourse = {
            if (!isDemoMode) SmartSelector.getInstance().clearTargetCourse()
            targetCourseName = null
            targetCourseTeacher = null
            GlassToaster.show("已清除目标课程")
        },
        // 🔧 模糊匹配模式参数
        isFuzzyMatchMode = isFuzzyMatchMode,
        onFuzzyMatchModeChange = { mode ->
            isFuzzyMatchMode = mode
            if (!isDemoMode) {
                prefs.edit().putBoolean(scopedPrefKey("fuzzy_match_mode"), mode).apply()
            }
        },
        fuzzyMatchTarget = fuzzyMatchTarget,
        onStartFuzzyMatch = { startFuzzyMatchGrabbing() },
        onClearFuzzyMatchTarget = {
            if (!isDemoMode) SmartSelector.getInstance().clearFuzzyMatchTarget()
            fuzzyMatchTarget = null
            GlassToaster.show("已清除监控目标")
        },
        schoolName = UserManager.getInstance().currentSchool?.name ?: "",
        showQueueModeLabels = false, // 🔧 隐藏单项模式标签，只用全局开关控制
        scheduledDateTime = scheduledDateTime,
        isScheduledMode = isScheduledMode,
        onScheduledModeChange = { isScheduledMode = it },
        onScheduledStart = { createScheduledTask() },
        hasScheduledTask = hasScheduledTask,
        scheduledTaskInfo = scheduledTaskInfo,
        onCancelScheduledTask = {
            if (!isDemoMode) {
                val currentStorageKey = UserManager.getInstance().currentAccountStorageKey
                cancelScheduledAlarm(context, alarmRequestCodeFor(currentStorageKey))
                cancelScheduledAlarm(context, ALARM_REQUEST_CODE)
                scheduledJob?.cancel()
            }
            hasScheduledTask = false
            scheduledTaskInfo = ""
            appendLog(if (isDemoMode) "已取消演示定时任务" else "已取消定时任务")
            saveState()
        },
        onPickDateTime = { showGlassDateTimePicker = true },
        // Queue data
        queue = queue,
        queueVersion = queueVersion,
        currentQueueIndex = if (isDemoMode) demoQueueIndex else SmartSelector.getInstance().currentQueueIndex,
        queueItemStatuses = queueItemStatuses,
        isParallelMode = isParallelMode,
        onParallelModeChange = {
            isParallelMode = it
            saveState()
        },
        onQueueMoveItem = { from, to ->
            if (isDemoMode) {
                if (from in queue.indices && to in queue.indices) {
                    val updated = queue.toMutableList()
                    val item = updated.removeAt(from)
                    updated.add(to, item)
                    queue = updated
                    queueVersion++
                }
            } else {
                SmartSelector.getInstance().moveInQueue(from, to)
                refreshQueue()
            }
        },
        onQueueRemoveItem = { index ->
            if (index in queue.indices) {
                if (isDemoMode) {
                    val removed = queue[index]
                    val key = "${removed.name}_${removed.teacher}_${removed.time}"
                    queue = queue.toMutableList().also { it.removeAt(index) }
                    queueItemStatuses = queueItemStatuses - key
                    queueVersion++
                } else {
                    SmartSelector.getInstance().removeFromQueue(queue[index])
                    refreshQueue()
                }
            }
        },
        onQueueToggleMode = { index ->
            if (isDemoMode) {
                if (index in queue.indices) {
                    queue = queue.mapIndexed { itemIndex, course ->
                        course.copy().apply {
                            if (itemIndex == index) useExactMatch = !course.useExactMatch
                        }
                    }
                    queueVersion++
                }
            } else {
                SmartSelector.getInstance().toggleExactMatchMode(index)
                refreshQueue()
            }
        },
        onQueueToggleAllMode = if (isScheduledMode) {
            { exact ->
                isExactModeGlobal = exact
                if (isDemoMode) {
                    queue = queue.map { course -> course.copy().apply { useExactMatch = exact } }
                    queueVersion++
                } else {
                    SmartSelector.getInstance().setAllExactMatchMode(exact)
                    saveState()
                    refreshQueue()
                }
            }
        } else null,
        isExactModeGlobal = isExactModeGlobal,
        onQueueClear = {
            if (isDemoMode) {
                queue = emptyList()
                queueItemStatuses = emptyMap()
                queueVersion++
            } else {
                SmartSelector.getInstance().clearQueue()
                refreshQueue()
            }
        },
        onAddCourse = {
            showAddCourseDialog = true
        },
        showScheduleWarning = showScheduleWarning,
        onDismissWarningForever = {
            showScheduleWarning = false
            if (!isDemoMode) {
                prefs.edit().putBoolean(scopedPrefKey("show_schedule_warning"), false).apply()
            }
        }
    )

    // 玻璃滚轮日期时间选择器（替代原生 DatePicker/TimePicker）
    if (showGlassDateTimePicker) {
        com.tyust.course.ui.system.GlassDateTimePickerDialog(
            title = "选择抢课时间",
            onConfirm = { millis ->
                val cal = Calendar.getInstance().apply { timeInMillis = millis }
                scheduledDateTime = String.format(
                    Locale.US,
                    "%04d/%02d/%02d %02d:%02d",
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH) + 1,
                    cal.get(Calendar.DAY_OF_MONTH),
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE)
                )
                saveState()
                showGlassDateTimePicker = false
            },
            onDismiss = { showGlassDateTimePicker = false }
        )
    }

    // 手动添加课程对话框 - 🔧 改进版：独立输入框 + 时间选择器
    if (showAddCourseDialog) {
        val weekdays = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val periods = listOf("", "1-2节", "3-4节", "5-6节", "7-8节", "9-10节", "11-12节")
        
        SystemDialog(
            onDismissRequest = { 
                showAddCourseDialog = false
                inputCourseName = ""
                inputTeacher = ""
                selectedWeekday = ""
                selectedPeriod = ""
            },
            title = { Text("添加课程到队列") },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 课程名（必填）
                    androidx.compose.material3.OutlinedTextField(
                        value = inputCourseName,
                        onValueChange = { inputCourseName = it },
                        label = { Text("课程名 *") },
                        placeholder = { Text("例如: 高等数学") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    // 教师（选填）
                    androidx.compose.material3.OutlinedTextField(
                        value = inputTeacher,
                        onValueChange = { inputTeacher = it },
                        label = { Text("教师（选填）") },
                        placeholder = { Text("例如: 张老师") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    // 时间选择（选填）
                    Text("上课时间（选填）", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SystemPicker(
                            options = weekdays.map { it.ifBlank { "不限" } },
                            selectedIndex = weekdays.indexOf(selectedWeekday).takeIf { it >= 0 },
                            onSelect = { index -> selectedWeekday = weekdays[index] },
                            modifier = Modifier.weight(1f),
                            label = "周几"
                        )
                        SystemPicker(
                            options = periods.map { it.ifBlank { "不限" } },
                            selectedIndex = periods.indexOf(selectedPeriod).takeIf { it >= 0 },
                            onSelect = { index -> selectedPeriod = periods[index] },
                            modifier = Modifier.weight(1f),
                            label = "节次"
                        )
                    }
                }
            },
            confirmButton = {
                SystemPrimaryButton(
                    text = "添加",
                    onClick = {
                        if (inputCourseName.isNotBlank()) {
                            // 构造时间字符串
                            val timeStr = buildString {
                                if (selectedWeekday.isNotEmpty()) append(selectedWeekday)
                                if (selectedPeriod.isNotEmpty()) {
                                    if (isNotEmpty()) append(" ")
                                    append(selectedPeriod)
                                }
                            }
                            
                            val tempCourse = com.tyust.course.model.Course().apply {
                                name = inputCourseName.trim()
                                teacher = inputTeacher.trim()
                                time = timeStr
                                courseId = "manual_${System.currentTimeMillis()}"
                                useExactMatch = false // 手动输入强制使用智能模式
                            }
                            val added = if (isDemoMode) {
                                if (queue.any { it == tempCourse }) {
                                    false
                                } else {
                                    queue = queue + tempCourse
                                    true
                                }
                            } else {
                                SmartSelector.getInstance().addToQueue(tempCourse)
                            }
                            if (added) {
                                // 🔧 添加时重置该课程的状态，防止显示之前的“失败”状态
                                val courseKey = "${tempCourse.name ?: ""}_${tempCourse.teacher ?: ""}_${tempCourse.time ?: ""}"
                                val newStatuses = queueItemStatuses.toMutableMap()
                                newStatuses[courseKey] = com.tyust.course.ui.screen.GrabQueueItemStatus.WAITING
                                queueItemStatuses = newStatuses
                                saveQueueStatuses()
                                
                                refreshQueue()
                                val displayInfo = buildString {
                                    append(tempCourse.name)
                                    if (tempCourse.teacher.isNotEmpty()) append(" | ${tempCourse.teacher}")
                                    if (tempCourse.time.isNotEmpty()) append(" | ${tempCourse.time}")
                                }
                                GlassToaster.show("已添加：$displayInfo")
                            } else {
                                GlassToaster.show("课程「${tempCourse.name}」已在队列中")
                            }
                            // 清空输入
                            inputCourseName = ""
                            inputTeacher = ""
                            selectedWeekday = ""
                            selectedPeriod = ""
                        }
                        showAddCourseDialog = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = inputCourseName.isNotBlank()
                )
            },
            dismissButton = {
                SystemSecondaryButton(
                    text = "取消",
                    onClick = {
                        showAddCourseDialog = false
                        inputCourseName = ""
                        inputTeacher = ""
                        selectedWeekday = ""
                        selectedPeriod = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }
}

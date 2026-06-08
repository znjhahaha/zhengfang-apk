package com.tyust.course.ui.route

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.widget.Toast
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
import com.tyust.course.manager.SmartSelector
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.receiver.GrabAlarmReceiver
import com.tyust.course.service.GrabService
import com.tyust.course.ui.screen.GrabProScreen
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch


@Composable
fun GrabProRoute() {
    val context = LocalContext.current
    
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
    var successCount by remember { mutableIntStateOf(0) }
    var failCount by remember { mutableIntStateOf(0) }
    var retryCount by remember { mutableIntStateOf(0) }
    var targetCourseName by remember { mutableStateOf<String?>(null) }
    var targetCourseTeacher by remember { mutableStateOf<String?>(null) }
    var logText by remember { mutableStateOf(prefs.getString(scopedPrefKey("log_text"), "") ?: "") }  // 持久化日志
    
    var interval by remember { mutableStateOf(prefs.getString(scopedPrefKey("interval"), "1500") ?: "1500") }
    var maxRetry by remember { mutableStateOf(prefs.getString(scopedPrefKey("max_retry"), "100") ?: "100") }
    var courseKeywords by remember { mutableStateOf(prefs.getString(scopedPrefKey("course_keywords"), "") ?: "") }
    var scheduledDateTime by remember { mutableStateOf(prefs.getString(scopedPrefKey("scheduled_datetime"), "") ?: "") }
    var isScheduledMode by remember { mutableStateOf(prefs.getBoolean(scopedPrefKey("scheduled_mode"), false)) }
    var hasScheduledTask by remember { mutableStateOf(prefs.getBoolean(scopedPrefKey("has_scheduled_task"), false)) }
    var scheduledTaskInfo by remember { mutableStateOf(prefs.getString(scopedPrefKey("scheduled_task_info"), "") ?: "") }
    
    // 队列相关状态
    var queue by remember { mutableStateOf(SmartSelector.getInstance().queue.toList()) }
    var queueVersion by remember { mutableIntStateOf(0) }  // 🔧 强制刷新计数器
    var isParallelMode by remember { mutableStateOf(prefs.getBoolean(scopedPrefKey("parallel_mode"), false)) }
    var isExactModeGlobal by remember { mutableStateOf(prefs.getBoolean(scopedPrefKey("exact_mode_global"), true)) } // 🔧 全局模式状态持久化
    
    // 🔧 模糊匹配捡漏模式
    var isFuzzyMatchMode by remember { mutableStateOf(prefs.getBoolean(scopedPrefKey("fuzzy_match_mode"), false)) }
    var fuzzyMatchTarget by remember { mutableStateOf<String?>(SmartSelector.getInstance().fuzzyMatchCourseName) }

    // 🔧 辅助函数：添加日志并限制在 100 条以内，防止变卡
    fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val newEntry = "[$timestamp] $message\n"
        val currentLines = (logText + newEntry).split("\n").filter { it.isNotBlank() }
        logText = currentLines.takeLast(100).joinToString("\n") + "\n"
    }
    
    // 使用课程ID作为key的状态Map，支持持久化
    var queueItemStatuses by remember { 
        val savedStatuses = prefs.getString(scopedPrefKey("queue_item_statuses"), null)
        val loaded = if (savedStatuses != null) {
            try {
                val map = mutableMapOf<String, com.tyust.course.ui.screen.GrabQueueItemStatus>()
                savedStatuses.split(";").forEach { pair ->
                    val parts = pair.split("=")
                    if (parts.size == 2) {
                        val status = when(parts[1]) {
                            "SUCCESS" -> com.tyust.course.ui.screen.GrabQueueItemStatus.SUCCESS
                            "FAILED" -> com.tyust.course.ui.screen.GrabQueueItemStatus.FAILED
                            "GRABBING" -> com.tyust.course.ui.screen.GrabQueueItemStatus.GRABBING
                            else -> com.tyust.course.ui.screen.GrabQueueItemStatus.WAITING
                        }
                        map[parts[0]] = status
                    }
                }
                map.toMap()
            } catch (e: Exception) { emptyMap() }
        } else emptyMap()
        mutableStateOf<Map<String, com.tyust.course.ui.screen.GrabQueueItemStatus>>(loaded)
    }
    var showAddCourseDialog by remember { mutableStateOf(false) }
    var manualCourseInput by remember { mutableStateOf("") }
    // 🔧 新增：分开的输入框状态
    var inputCourseName by remember { mutableStateOf("") }
    var inputTeacher by remember { mutableStateOf("") }
    var selectedWeekday by remember { mutableStateOf("") }
    var selectedPeriod by remember { mutableStateOf("") }
    
    // 警告对话框控制
    var showScheduleWarning by remember { mutableStateOf(prefs.getBoolean(scopedPrefKey("show_schedule_warning"), true)) }
    
    // 保存队列状态的辅助函数
    fun saveQueueStatuses() {
        val statusString = queueItemStatuses.entries
            .filter { it.value != com.tyust.course.ui.screen.GrabQueueItemStatus.WAITING }
            .joinToString(";") { "${it.key}=${it.value.name}" }
        prefs.edit().putString(scopedPrefKey("queue_item_statuses"), statusString).apply()
    }
    
    // Save state helper - 保存所有状态包括日志
    fun saveState() {
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
    
    // 刷新队列的辅助函数 - 🔧 深拷贝确保 Compose 检测到变化
    fun refreshQueue() {
        // 使用 copy() 方法深拷贝每个 Course 对象，确保 Compose 能检测到属性变化
        queue = SmartSelector.getInstance().queue.map { it.copy() }.toMutableList()
        queueVersion++  // 递增版本号强制重组
    }
    
    // Update target course and queue on launch
    LaunchedEffect(Unit) {
        SmartSelector.getInstance().init(context)
        SmartSelector.getInstance().restoreFuzzyMatchSettings() // 🔧 恢复模糊匹配设置
        val course = SmartSelector.getInstance().targetCourse
        targetCourseName = course?.name
        targetCourseTeacher = course?.teacher
        isRunning = SmartSelector.getInstance().isRunning
        fuzzyMatchTarget = SmartSelector.getInstance().fuzzyMatchCourseName // 🔧 恢复监控目标
        refreshQueue()
    }

    // Broadcast Receiver
    DisposableEffect(context) {
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
    
    // Logic Actions
    fun startGrabbing() {
        val school = UserManager.getInstance().currentSchool
        if (school == null) {
            Toast.makeText(context, "请先登录", Toast.LENGTH_SHORT).show()
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
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            appendLog("🚀 开始队列抢课: ${queueList.size} 门课程 (直接请求模式)")
            isRunning = true
            Toast.makeText(context, "队列抢课进程已启动", Toast.LENGTH_LONG).show()
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
            
            appendLog("🚀 开始后台抢课: ${targetCourse.name}")
            isRunning = true
            Toast.makeText(context, "后台抢课已启动", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "请先在\"课程\"页面长按选择要抢的课程，或在下方添加课程到队列", Toast.LENGTH_LONG).show()
        }
    }
    
    // 🔧 启动模糊匹配捡漏模式
    fun startFuzzyMatchGrabbing() {
        val school = UserManager.getInstance().currentSchool
        if (school == null) {
            Toast.makeText(context, "请先登录", Toast.LENGTH_SHORT).show()
            return
        }
        
        val fuzzyTargetId = SmartSelector.getInstance().fuzzyMatchCourseId
        val fuzzyTargetName = SmartSelector.getInstance().fuzzyMatchCourseName
        
        if (fuzzyTargetId.isNullOrEmpty()) {
            Toast.makeText(context, "请先设置监控目标", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 启用模糊匹配模式
        SmartSelector.getInstance().setFuzzyMatchEnabled(true)
        
        val serviceIntent = Intent(context, GrabService::class.java).apply {
            action = GrabService.ACTION_START_FUZZY_MATCH
            putGrabAccountExtras()
            putExtra(GrabService.EXTRA_INTERVAL, interval.toIntOrNull() ?: 2000)
            putExtra(GrabService.EXTRA_MAX_RETRY, maxRetry.toIntOrNull() ?: 999)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        
        appendLog("🔍 启动模糊匹配模式: $fuzzyTargetName")
        isRunning = true
        Toast.makeText(context, "模糊匹配监控已启动: $fuzzyTargetName", Toast.LENGTH_LONG).show()
    }
    
    fun stopGrabbing() {
        val serviceIntent = Intent(context, GrabService::class.java).apply {
            action = GrabService.ACTION_STOP
            putGrabAccountExtras()
        }
        context.startService(serviceIntent)
        SmartSelector.getInstance().stop()
        SmartSelector.getInstance().setFuzzyMatchEnabled(false) // 🔧 关闭模糊匹配模式
        
        appendLog("⏹ 已停止抢课")
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
            Toast.makeText(context, "请先在队列中添加课程", Toast.LENGTH_SHORT).show()
            return 
        }
        if (scheduledDateTime.isBlank()) { Toast.makeText(context, "请选择开始时间", Toast.LENGTH_SHORT).show(); return }
        
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val targetTime = try { dateFormat.parse(scheduledDateTime) } catch (e: Exception) { null }
        if (targetTime == null) { Toast.makeText(context, "日期格式错误", Toast.LENGTH_LONG).show(); return }
        
        val now = Date()
        val delayMs = targetTime.time - now.time
        if (delayMs <= 0) { Toast.makeText(context, "开始时间必须在当前时间之后", Toast.LENGTH_SHORT).show(); return }
        
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
        Toast.makeText(context, "定时任务已创建，将在 ${delayMinutes} 分钟后开始", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(context, "定时任务账号已失效，请重新登录", Toast.LENGTH_LONG).show()
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
            SmartSelector.getInstance().clearTargetCourse()
            targetCourseName = null
            targetCourseTeacher = null
            Toast.makeText(context, "已清除目标课程", Toast.LENGTH_SHORT).show()
        },
        // 🔧 模糊匹配模式参数
        isFuzzyMatchMode = isFuzzyMatchMode,
        onFuzzyMatchModeChange = { mode ->
            isFuzzyMatchMode = mode
            prefs.edit().putBoolean(scopedPrefKey("fuzzy_match_mode"), mode).apply()
        },
        fuzzyMatchTarget = fuzzyMatchTarget,
        onStartFuzzyMatch = { startFuzzyMatchGrabbing() },
        onClearFuzzyMatchTarget = {
            SmartSelector.getInstance().clearFuzzyMatchTarget()
            fuzzyMatchTarget = null
            Toast.makeText(context, "已清除监控目标", Toast.LENGTH_SHORT).show()
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
            val currentStorageKey = UserManager.getInstance().currentAccountStorageKey
            cancelScheduledAlarm(context, alarmRequestCodeFor(currentStorageKey))
            cancelScheduledAlarm(context, ALARM_REQUEST_CODE)
            scheduledJob?.cancel()
            hasScheduledTask = false
            scheduledTaskInfo = ""
            saveState()
        },
        onPickDateTime = {
            val calendar = Calendar.getInstance()
            DatePickerDialog(context, { _, year, month, day ->
                TimePickerDialog(context, { _, hour, minute ->
                    scheduledDateTime = String.format("%04d/%02d/%02d %02d:%02d", year, month + 1, day, hour, minute)
                    saveState()
                }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
        },
        // Queue data
        queue = queue,
        queueVersion = queueVersion,
        currentQueueIndex = SmartSelector.getInstance().currentQueueIndex,
        queueItemStatuses = queueItemStatuses,
        isParallelMode = isParallelMode,
        onParallelModeChange = { 
            isParallelMode = it 
            saveState()
        },
        onQueueMoveItem = { from, to ->
            SmartSelector.getInstance().moveInQueue(from, to)
            refreshQueue()
        },
        onQueueRemoveItem = { index ->
            if (index < queue.size) {
                SmartSelector.getInstance().removeFromQueue(queue[index])
                refreshQueue()
            }
        },
        onQueueToggleMode = { index ->
            SmartSelector.getInstance().toggleExactMatchMode(index)
            refreshQueue()
        },
        onQueueToggleAllMode = if (isScheduledMode) { // 🔧 只在定时模式下显示开关
            { exact ->
                isExactModeGlobal = exact // 🔧 同步更新UI状态
                SmartSelector.getInstance().setAllExactMatchMode(exact)
                saveState() // 🔧 持久化
                refreshQueue()
            } 
        } else null,
        isExactModeGlobal = isExactModeGlobal, // 🔧 传递全局模式状态
        onQueueClear = {
            SmartSelector.getInstance().clearQueue()
            refreshQueue()
        },
        onAddCourse = {
            showAddCourseDialog = true
        },
        showScheduleWarning = showScheduleWarning,
        onDismissWarningForever = {
            showScheduleWarning = false
            prefs.edit().putBoolean(scopedPrefKey("show_schedule_warning"), false).apply()
        }
    )
    
    // 手动添加课程对话框 - 🔧 改进版：独立输入框 + 时间选择器
    if (showAddCourseDialog) {
        val weekdays = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val periods = listOf("", "1-2节", "3-4节", "5-6节", "7-8节", "9-10节", "11-12节")
        var weekdayExpanded by remember { mutableStateOf(false) }
        var periodExpanded by remember { mutableStateOf(false) }
        
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { 
                showAddCourseDialog = false
                inputCourseName = ""
                inputTeacher = ""
                selectedWeekday = ""
                selectedPeriod = ""
            },
            title = { Text("添加课程到队列") },
            text = {
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
                        // 周几选择
                        Box(modifier = Modifier.weight(1f)) {
                            androidx.compose.material3.OutlinedTextField(
                                value = selectedWeekday,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("周几") },
                                trailingIcon = {
                                    Icon(
                                        if (weekdayExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        modifier = Modifier.clickable { weekdayExpanded = !weekdayExpanded }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().clickable { weekdayExpanded = true }
                            )
                            androidx.compose.material3.DropdownMenu(
                                expanded = weekdayExpanded,
                                onDismissRequest = { weekdayExpanded = false }
                            ) {
                                weekdays.forEach { day ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(if (day.isEmpty()) "不限" else day) },
                                        onClick = {
                                            selectedWeekday = day
                                            weekdayExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        
                        // 节次选择
                        Box(modifier = Modifier.weight(1f)) {
                            androidx.compose.material3.OutlinedTextField(
                                value = selectedPeriod,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("节次") },
                                trailingIcon = {
                                    Icon(
                                        if (periodExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        modifier = Modifier.clickable { periodExpanded = !periodExpanded }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().clickable { periodExpanded = true }
                            )
                            androidx.compose.material3.DropdownMenu(
                                expanded = periodExpanded,
                                onDismissRequest = { periodExpanded = false }
                            ) {
                                periods.forEach { period ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(if (period.isEmpty()) "不限" else period) },
                                        onClick = {
                                            selectedPeriod = period
                                            periodExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
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
                            val added = SmartSelector.getInstance().addToQueue(tempCourse)
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
                                Toast.makeText(context, "已添加: $displayInfo", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "课程「${tempCourse.name}」已在队列中", Toast.LENGTH_SHORT).show()
                            }
                            // 清空输入
                            inputCourseName = ""
                            inputTeacher = ""
                            selectedWeekday = ""
                            selectedPeriod = ""
                        }
                        showAddCourseDialog = false
                    },
                    enabled = inputCourseName.isNotBlank()
                ) { Text("添加") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { 
                        showAddCourseDialog = false
                        inputCourseName = ""
                        inputTeacher = ""
                        selectedWeekday = ""
                        selectedPeriod = ""
                    }
                ) { Text("取消") }
            }
        )
    }
}

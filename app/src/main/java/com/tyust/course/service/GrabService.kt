package com.tyust.course.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tyust.course.MainActivity
import com.tyust.course.R
import com.tyust.course.manager.SmartSelector
import com.tyust.course.manager.UserManager
import com.tyust.course.model.Course
import com.tyust.course.model.SchoolConfig
import com.tyust.course.network.CourseApiClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException

class GrabService : Service() {
    
    companion object {
        private const val TAG = "GrabService"
        private const val CHANNEL_ID = "grab_service_channel"
        private const val NOTIFICATION_ID = 1001
        
        // Action constants
        const val ACTION_START = "com.tyust.course.action.START_GRAB"
        const val ACTION_START_KEYWORD = "com.tyust.course.action.START_GRAB_KEYWORD"  // 关键词模式
        const val ACTION_START_QUEUE = "com.tyust.course.action.START_GRAB_QUEUE"      // 🔧 新增：直接队列模式
        const val ACTION_START_FUZZY_MATCH = "com.tyust.course.action.START_FUZZY_MATCH" // 🔧 模糊匹配模式
        const val ACTION_STOP = "com.tyust.course.action.STOP_GRAB"
        
        // Extra keys
        const val EXTRA_COURSE_NAME = "course_name"
        const val EXTRA_COURSE_ID = "course_id"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_MAX_RETRY = "max_retry"
        const val EXTRA_COURSE_KEYWORDS = "course_keywords"  // 关键词
        const val EXTRA_PARALLEL_MODE = "parallel_mode"  // 并行模式
        
        // Broadcast action for updates
        const val BROADCAST_UPDATE = "com.tyust.course.GRAB_UPDATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_SUCCESS_COUNT = "success_count"
        const val EXTRA_FAIL_COUNT = "fail_count"
        const val EXTRA_RETRY_COUNT = "retry_count"
        const val EXTRA_LOG_MESSAGE = "log_message"
        const val EXTRA_IS_RUNNING = "is_running"
        const val EXTRA_QUEUE_UPDATED = "queue_updated"  // 队列已更新标志
        const val EXTRA_COURSE_STATUS = "course_status"  // success/failed/grabbing
        const val EXTRA_COURSE_NAME_STATUS = "course_name_status"  // 课程名
    }
    
    private var isRunning = false
    private var targetCourse: Course? = null
    private var currentSchool: SchoolConfig? = null
    private var courseParams: Map<String, String>? = null
    
    private var interval = 1500
    private var maxRetry = 100
    private var successCount = 0
    private var failCount = 0
    private var retryCount = 0
    
    // 服务器健康检查相关
    private val pingTimeoutMs = 3000L  // 服务器响应超时阈值（毫秒）
    private var skipCount = 0  // 因服务器卡顿跳过的次数
    
    // 多关键词队列支持 (用分号或换行分隔多组关键词)
    private var keywordQueue = mutableListOf<String>()
    private var currentKeywordIndex = 0
    private var multiKeywordTotalSuccess = 0
    
    // 并行抢课模式
    private var isParallelMode = false
    private var parallelWorkerCount = 2  // 同时处理2门课
    private val activeWorkers = mutableSetOf<Int>()  // 活跃的工作线程ID
    
    // 🔧 直接队列模式状态
    private var isQueueMode = false
    private var currentQueueIndex = 0
    private var totalQueueSuccess = 0
    
    // 🔧 模糊匹配捡漏模式
    private var isFuzzyMatchMode = false
    private var fuzzyMatchPollingCount = 0  // 轮询计数
    
    // 🔧 课程缓存（供智能模式复用，避免重复请求）
    private var cachedAllCourses: List<Course> = emptyList()
    
    private val handler = Handler(Looper.getMainLooper())
    private var grabRunnable: Runnable? = null
    
    // 🔧 全局 Cookie 失效广播接收器
    private val cookieExpiredReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == com.tyust.course.network.CourseApiClient.ACTION_COOKIE_EXPIRED) {
                if (isRunning) {
                    Log.e(TAG, "📡 收到全局广播: Cookie 已失效，正在执行强提醒并停止服务")
                    handleCookieInvalid()
                }
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // 🔧 注册 Cookie 失效监听
        val filter = android.content.IntentFilter(com.tyust.course.network.CourseApiClient.ACTION_COOKIE_EXPIRED)
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            cookieExpiredReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        
        Log.d(TAG, "GrabService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val courseName = intent.getStringExtra(EXTRA_COURSE_NAME) ?: ""
                val courseId = intent.getStringExtra(EXTRA_COURSE_ID) ?: ""
                interval = intent.getIntExtra(EXTRA_INTERVAL, 1500)
                maxRetry = intent.getIntExtra(EXTRA_MAX_RETRY, 100)
                
                // Get course and school from SmartSelector
                SmartSelector.getInstance().init(this)
                targetCourse = SmartSelector.getInstance().targetCourse
                currentSchool = UserManager.getInstance().currentSchool
                courseParams = SmartSelector.getInstance().courseParams
                
                if (targetCourse != null && currentSchool != null) {
                    startForeground(NOTIFICATION_ID, createNotification("正在抢课: $courseName"))
                    startGrabbing()
                } else {
                    Log.e(TAG, "Missing course or school info")
                    broadcastLog("❌ 缺少课程或学校信息")
                    stopSelf()
                }
            }
            ACTION_START_KEYWORD -> {
                // 关键词模式：先获取课程列表，匹配后开始抢课
                val keywords = intent.getStringExtra(EXTRA_COURSE_KEYWORDS) ?: ""
                interval = intent.getIntExtra(EXTRA_INTERVAL, 1500)
                maxRetry = intent.getIntExtra(EXTRA_MAX_RETRY, 100)
                isParallelMode = intent.getBooleanExtra(EXTRA_PARALLEL_MODE, false)
                
                currentSchool = UserManager.getInstance().currentSchool
                
                // 🔧 初始化 SmartSelector 以恢复队列，确保 fetchCourseDetailsAndMatch 能正确获取队列中的 classId
                SmartSelector.getInstance().init(this)
                
                if (currentSchool == null) {
                    Log.e(TAG, "未登录")
                    broadcastLog("❌ 未登录，无法抢课")
                    stopSelf()
                    return START_STICKY
                }
                
                // 🚀 获取列表前先检查 Cookie
                checkCookieValidity(currentSchool!!) { isValid ->
                    if (!isValid) return@checkCookieValidity
                    
                    val modeText = if (isParallelMode) "并行模式(${parallelWorkerCount}门)" else "顺序模式"
                    startForeground(NOTIFICATION_ID, createNotification("正在获取课程列表..."))
                    broadcastLog("📚 正在获取课程列表... [$modeText]")
                    
                    // 开始关键词抢课流程
                    startKeywordGrabbing(keywords)
                }
            }
            ACTION_START_QUEUE -> {
                // 🔧 直接队列模式：利用 SmartSelector.queue 中的现有参数
                interval = intent.getIntExtra(EXTRA_INTERVAL, 1500)
                maxRetry = intent.getIntExtra(EXTRA_MAX_RETRY, 100)
                currentSchool = UserManager.getInstance().currentSchool
                
                SmartSelector.getInstance().init(this)
                val queue = SmartSelector.getInstance().queue
                
                if (currentSchool == null || queue.isEmpty()) {
                    Log.e(TAG, "未登录或队列为空")
                    broadcastLog("❌ 未登录或队列为空")
                    stopSelf()
                    return START_STICKY
                }
                
                // 🚀 启动队列前先检查 Cookie
                checkCookieValidity(currentSchool!!) { isValid ->
                    if (!isValid) return@checkCookieValidity
                    
                    startForeground(NOTIFICATION_ID, createNotification("正在准备队列抢课..."))
                    broadcastLog("🚀 启动直接队列抢课 (共 ${queue.size} 门)")
                    
                    isQueueMode = true
                    currentQueueIndex = 0
                    totalQueueSuccess = 0
                    
                    startNextQueueItem()
                }
            }
            ACTION_START_FUZZY_MATCH -> {
                // 🔧 模糊匹配捡漏模式：监控课程类别人数变化
                interval = intent.getIntExtra(EXTRA_INTERVAL, 2000) // 默认2秒间隔
                maxRetry = intent.getIntExtra(EXTRA_MAX_RETRY, 999) // 模糊匹配模式默认持续监控
                currentSchool = UserManager.getInstance().currentSchool
                
                SmartSelector.getInstance().init(this)
                SmartSelector.getInstance().restoreFuzzyMatchSettings()
                
                val fuzzyTargetId = SmartSelector.getInstance().fuzzyMatchCourseId
                val fuzzyTargetName = SmartSelector.getInstance().fuzzyMatchCourseName
                
                if (currentSchool == null || fuzzyTargetId.isNullOrEmpty()) {
                    Log.e(TAG, "未登录或未设置模糊匹配目标")
                    broadcastLog("❌ 未登录或未设置监控目标")
                    stopSelf()
                    return START_STICKY
                }
                
                // 🔧 先从 SmartSelector 复制 courseParams
                courseParams = SmartSelector.getInstance().courseParams
                
                startForeground(NOTIFICATION_ID, createNotification("模糊匹配：监控 $fuzzyTargetName"))
                broadcastLog("🔍 启动模糊匹配模式: $fuzzyTargetName")
                broadcastLog("📊 每 ${interval}ms 轮询一次，检测人数变化")
                
                isFuzzyMatchMode = true
                fuzzyMatchPollingCount = 0
                isRunning = true
                
                // 🔧 先获取隐藏参数，再启动模糊匹配轮询
                fetchHiddenParamsAndStartFuzzyMatch(currentSchool!!)
            }
            ACTION_STOP -> {
                stopGrabbing()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        stopGrabbing()
        // 🔧 注销监听
        try {
            unregisterReceiver(cookieExpiredReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Unregistering receiver failed: ${e.message}")
        }
        super.onDestroy()
        Log.d(TAG, "GrabService destroyed")
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "抢课服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台抢课服务通知"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, GrabService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("抢课进行中")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "停止抢课", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun startGrabbing() {
        if (isRunning) return
        isRunning = true
        
        // 如果不是队列模式，重置计数（队列模式在 ACTION_START_QUEUE 处理）
        if (!isQueueMode) {
            successCount = 0
            failCount = 0
            retryCount = 0
        }
        
        val course = targetCourse
        broadcastUpdate("🚀 开始抢课: ${course?.name}")
        
        // 发送 GRABBING 状态给 UI
        if (course != null) {
            broadcastQueueUpdate(
                courseName = course.name,
                status = "grabbing",
                courseId = course.courseId ?: course.classId ?: course.name
            )
        }
        
        runGrabLoop()
    }
    
    // 🔧 启动队列中的下一个项目
    private fun startNextQueueItem() {
        val queue = SmartSelector.getInstance().queue
        if (currentQueueIndex >= queue.size) {
            broadcastLog("🏁 队列处理完成。成功: $totalQueueSuccess/${queue.size}")
            stopGrabbing()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        
        val course = queue[currentQueueIndex]
        targetCourse = course
        
        // 重置单课重试计数
        retryCount = 0
        skipCount = 0
        
        isRunning = false // 先重置状态以允许启动
        broadcastLog("🎯 队列进度 [${currentQueueIndex + 1}/${queue.size}]: ${course.name}")
        updateNotification("队列抢课: ${course.name} [${currentQueueIndex + 1}/${queue.size}]")
        
        // 🔧 发送 GRABBING 状态 (使用完整课程名，与UI匹配)
        broadcastQueueUpdate(
            courseName = course.name,
            status = "grabbing",
            courseId = course.courseId ?: course.classId ?: course.name
        )
        
        startGrabbing()
    }
    
    private fun stopGrabbing() {
        isRunning = false
        grabRunnable?.let { handler.removeCallbacks(it) }
        
        // 发送停止状态，让 UI 知道服务已停止
        val intent = Intent(BROADCAST_UPDATE).apply {
            putExtra(EXTRA_IS_RUNNING, false)
            putExtra(EXTRA_LOG_MESSAGE, "⏹ 抢课已停止")
            putExtra(EXTRA_SUCCESS_COUNT, successCount)
            putExtra(EXTRA_FAIL_COUNT, failCount)
            putExtra(EXTRA_RETRY_COUNT, retryCount)
            putExtra(EXTRA_QUEUE_UPDATED, true)  // 通知 UI 刷新队列状态
            setPackage(packageName)
        }
        sendBroadcast(intent)
        
        // Also stop SmartSelector
        SmartSelector.getInstance().stop()
    }
    
    private fun runGrabLoop() {
        if (!isRunning || retryCount >= maxRetry) {
            if (retryCount >= maxRetry) {
                broadcastLog("⚠️ 已达最大重试次数 ($maxRetry)")
                
                // 标记当前课程为失败
                failCount++
                val failedCourse = targetCourse
                if (failedCourse != null) {
                    broadcastQueueUpdate(failedCourse.name, "failed", failedCourse.courseId ?: failedCourse.classId ?: failedCourse.name)
                }
                
                // 🔧 检查是否还有更多关键词需要处理
                if (keywordQueue.size > 1 && currentKeywordIndex < keywordQueue.size - 1) {
                    broadcastLog("⏭️ 当前关键词未抢到，切换到下一个关键词...")
                    currentKeywordIndex++
                    isRunning = false
                    handler.postDelayed({
                        startSingleKeywordGrabbing(keywordQueue[currentKeywordIndex])
                    }, 1000)
                    return
                }
                
                // 🔧 队列模式自动跳到下一个
                if (isQueueMode && currentQueueIndex < SmartSelector.getInstance().queue.size - 1) {
                    broadcastLog("⏭️ 自动切换到队列下一门课程...")
                    currentQueueIndex++
                    isRunning = false
                    handler.postDelayed({ startNextQueueItem() }, 1000)
                    return
                }
            }
            
            // 没有更多任务，彻底停止
            if (keywordQueue.size > 1) {
                broadcastLog("🏁 全部关键词处理完成，成功: $multiKeywordTotalSuccess/${keywordQueue.size}")
            } else if (isQueueMode) {
                broadcastLog("🏁 队列处理完成。总成功: $totalQueueSuccess")
            }
            stopGrabbing()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        
        val course = targetCourse ?: return
        val school = currentSchool ?: return
        
        // 🚀 定期检查 Cookie 有效性 (Server Health 检查前)
        // 策略：首次必须检查，后续每隔 200 次请求检查一次
        if (retryCount == 0 || retryCount % 200 == 0) {
            checkCookieValidity(school) { isValid ->
                if (!isRunning) return@checkCookieValidity
                if (!isValid) return@checkCookieValidity // 失效自动处理

                // Cookie 有效，继续执行服务器检查
                proceedToServerHealthCheck(school, course)
            }
        } else {
            // 不需要检查 Cookie，直接进行服务器检查
            proceedToServerHealthCheck(school, course)
        }
    }

    private fun proceedToServerHealthCheck(school: SchoolConfig, course: Course) {
        broadcastLog("🔍 检测服务器状态...")
        checkServerHealth(school) { isHealthy, responseTime ->
            if (!isRunning) return@checkServerHealth
            
            if (isHealthy) {
                // 服务器正常，开始抢课
                retryCount++
                broadcastLog("🔄 第 $retryCount 次尝试 (响应: ${responseTime}ms)")
                updateNotification("第 $retryCount 次尝试 | 成功: $successCount | 失败: $failCount | 跳过: $skipCount")
                
                // Step 0: 获取隐藏参数
                fetchHiddenParamsAndProceed(school, course)
            } else {
                // 服务器响应慢或超时，跳过本次尝试
                skipCount++
                broadcastLog("⏸️ 服务器响应慢 (${responseTime}ms > ${pingTimeoutMs}ms)，跳过本次，已跳过 $skipCount 次")
                updateNotification("等待服务器恢复 | 跳过: $skipCount | 剩余尝试: ${maxRetry - retryCount}")
                
                // 等待后重试，但不计入重试次数
                scheduleNextAttempt()
            }
        }
    }
    
    // 检查 Cookie 有效性 (Pre-flight Check)
    private fun checkCookieValidity(school: SchoolConfig, callback: (Boolean) -> Unit) {
        CourseApiClient.getInstance().validateCookie(school, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // 网络错误暂时视为通过 (避免因网络波动误判为过期)
                Log.w(TAG, "Cookie validity check failed (network error): ${e.message}")
                handler.post { callback(true) }
            }

            override fun onResponse(call: Call, response: Response) {
                val html = response.body?.string() ?: ""
                response.close()

                // 判断逻辑：尝试解析学生姓名
                val studentName = com.tyust.course.utils.CourseParser.parseStudentName(html)
                val isValid = !studentName.isNullOrEmpty()

                handler.post {
                    if (isValid) {
                        Log.d(TAG, "✅ Cookie 有效，学生姓名: $studentName")
                        callback(true)
                    } else {
                        Log.e(TAG, "❌ Cookie 已失效，无法解析学生姓名")
                        handleCookieInvalid()
                        callback(false)
                    }
                }
            }
        })
    }

    private fun handleCookieInvalid() {
        stopGrabbing()
        
        // 发送高优先级通知
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚠️ 登录已过期")
            .setContentText("抢课已停止，请点击此通知重新登录！")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // 强提醒
            .setDefaults(Notification.DEFAULT_ALL)         // 震动+声音
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID + 1, notification) //都不把常驻通知顶掉

        broadcastLog("❌ 严重错误: Cookie 已失效！请重新登录")
        broadcastUpdate("❌ Cookie 已失效，请重新登录")
    }

    // 检查服务器健康状态（通过获取首页响应时间判断）
    private fun checkServerHealth(school: SchoolConfig, callback: (isHealthy: Boolean, responseTime: Long) -> Unit) {
        val startTime = System.currentTimeMillis()
        
        Thread {
            try {
                // 使用简单的 HEAD 请求检测服务器响应
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(pingTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(pingTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                
                val request = okhttp3.Request.Builder()
                    .url(school.baseUrl)
                    .head()
                    .build()
                
                val response = client.newCall(request).execute()
                val responseTime = System.currentTimeMillis() - startTime
                response.close()
                
                handler.post {
                    callback(responseTime < pingTimeoutMs, responseTime)
                }
            } catch (e: Exception) {
                val responseTime = System.currentTimeMillis() - startTime
                Log.w(TAG, "Server health check failed: ${e.message}")
                handler.post {
                    callback(false, responseTime)
                }
            }
        }.start()
    }
    
    // Step 0: 获取页面隐藏参数后继续选课流程
    private fun fetchHiddenParamsAndProceed(school: SchoolConfig, course: Course) {
        // 使用线程池异步获取隐藏参数
        Thread {
            val hiddenHtml = CourseApiClient.getInstance().fetchPageHiddenParamsSync(school)
            val hiddenParams = parseHiddenParams(hiddenHtml ?: "")
            
            Log.d(TAG, "Step 0: 获取隐藏参数完成，共 ${hiddenParams.size} 个")
            
            // 合并到 courseParams
            if (hiddenParams.isNotEmpty()) {
                val merged = (courseParams ?: emptyMap()).toMutableMap()
                hiddenParams.forEach { (key, value) ->
                    if (value.isNotEmpty() && merged[key].isNullOrEmpty()) {
                        merged[key] = value
                    }
                }
                courseParams = merged
            }
            
            // 🔧 根据模式选择不同的处理流程
            if (course.useExactMatch && !course.classId.isNullOrEmpty()) {
                // 精确模式：直接使用保存的 classId
                Log.d(TAG, "🔒 精确模式: 使用保存的 classId=${course.classId}")
                handler.post { fetchSelectionDetails(school, course) }
            } else {
                // 🔧 新智能模式：获取所有可选课程→本地匹配→获取详情→匹配教学班
                Log.d(TAG, "🔄 智能模式: 获取所有课程并匹配 ${course.name} | ${course.teacher} | ${course.time}")
                handler.post { 
                    broadcastLog("🔄 智能模式: 获取课程列表...")
                    smartModeMatchFromAllCourses(school, course) 
                }
            }
        }.start()
    }
    
    // 🔧 智能模式：搜索课程列表并匹配
    private fun searchAndMatchCourse(school: SchoolConfig, targetCourse: Course) {
        val searchName = targetCourse.name ?: ""
        if (searchName.isEmpty()) {
            broadcastLog("❌ 智能模式失败: 课程名为空")
            scheduleNextAttempt()
            return
        }
        
        // 🔧 使用与 fetchSelectionDetails 相同的约 40 个特定参数
        val params = courseParams?.takeIf { it.isNotEmpty() } ?: indexParams.toMap()
        
        fun getParam(baseName: String): String {
            params?.get(baseName)?.takeIf { it.isNotEmpty() }?.let { return it }
            for (i in 1..5) {
                params?.get("${baseName}_$i")?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return ""
        }
        
        // 构建搜索请求 - 使用特定参数（与 fetchSelectionDetails 一致）
        val formData = mutableMapOf<String, String>()
        formData["rwlx"] = targetCourse._rwlx?.takeIf { it.isNotEmpty() } ?: params?.get("rwlx") ?: "1"
        formData["xkly"] = params?.get("xkly") ?: "0"
        formData["bklx_id"] = params?.get("bklx_id") ?: "0"
        formData["sfkkjyxdxnxq"] = params?.get("sfkkjyxdxnxq") ?: "0"
        formData["kzkcgs"] = params?.get("kzkcgs") ?: "0"
        formData["xqh_id"] = getParam("xqh_id").ifEmpty { "1" }
        formData["jg_id"] = getParam("jg_id").ifEmpty { "05" }
        formData["zyh_id"] = targetCourse.zyh_id?.takeIf { it.isNotEmpty() } ?: getParam("zyh_id")
        formData["zyfx_id"] = getParam("zyfx_id").ifEmpty { "wfx" }
        formData["txbsfrl"] = params?.get("txbsfrl") ?: "0"
        formData["njdm_id"] = targetCourse.njdm_id?.takeIf { it.isNotEmpty() } ?: params?.get("njdm_id") ?: "2024"
        formData["bh_id"] = getParam("bh_id")
        formData["xbm"] = getParam("xbm").ifEmpty { "1" }
        formData["xslbdm"] = getParam("xslbdm").ifEmpty { "421" }
        formData["mzm"] = getParam("mzm").ifEmpty { "01" }
        formData["xz"] = getParam("xz").ifEmpty { "4" }
        formData["ccdm"] = getParam("ccdm").ifEmpty { "3" }
        formData["xsbj"] = getParam("xsbj").ifEmpty { "0" }
        formData["sfkknj"] = params?.get("sfkknj") ?: "0"
        formData["gnjkxdnj"] = params?.get("gnjkxdnj") ?: "0"
        formData["sfkkzy"] = params?.get("sfkkzy") ?: "0"
        formData["kzybkxy"] = params?.get("kzybkxy") ?: "0"
        formData["sfznkx"] = params?.get("sfznkx") ?: "0"
        formData["zdkxms"] = params?.get("zdkxms") ?: "0"
        formData["sfkxq"] = params?.get("sfkxq") ?: "0"
        formData["sfkcfx"] = params?.get("sfkcfx") ?: "0"
        formData["bbhzxjxb"] = params?.get("bbhzxjxb") ?: "0"
        formData["kkbk"] = params?.get("kkbk") ?: "0"
        formData["kkbkdj"] = params?.get("kkbkdj") ?: "0"
        formData["bklbkcj"] = params?.get("bklbkcj") ?: "0"
        formData["xkxnm"] = getParam("xkxnm").ifEmpty { "2025" }
        formData["xkxqm"] = getParam("xkxqm").ifEmpty { "12" }
        formData["xkxskcgskg"] = params?.get("xkxskcgskg") ?: "0"
        formData["rlkz"] = params?.get("rlkz") ?: "0"
        formData["cdrlkz"] = params?.get("cdrlkz") ?: "0"
        formData["rlzlkz"] = params?.get("rlzlkz") ?: "1"
        formData["kklxdm"] = targetCourse.kklxdm?.takeIf { it.isNotEmpty() } ?: params?.get("kklxdm") ?: "01"
        formData["kch_id"] = targetCourse.courseId ?: ""
        formData["jxbzcxskg"] = params?.get("jxbzcxskg") ?: "0"
        formData["xklc"] = targetCourse._xklc?.takeIf { it.isNotEmpty() } ?: params?.get("xklc") ?: "2"
        formData["xkkz_id"] = targetCourse._xkkz_id ?: ""
        formData["cxbj"] = params?.get("cxbj") ?: "0"
        formData["fxbj"] = params?.get("fxbj") ?: "0"
        // 搜索专用参数
        formData["filter_list[0]"] = searchName
        formData["kspage"] = "0"
        formData["jspage"] = "1000"
        
        Log.d(TAG, "智能模式请求参数数量: ${formData.size}, 课程名: $searchName")
        val postBody = formData.entries.joinToString("&") { "${it.key}=${it.value}" }
        
        CourseApiClient.getInstance().fetchAvailableCourses(school, postBody, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                broadcastLog("❌ 搜索课程失败: ${e.message}")
                scheduleNextAttempt()
            }
            
            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string() ?: ""
                try {
                    val courses = com.tyust.course.utils.CourseParser.parseCourseListFromJson(json)
                    Log.d(TAG, "智能模式: 搜索到 ${courses.size} 门课程")
                    
                    // 按课程名+老师+时间匹配
                    val matched = courses.find { c ->
                        val nameMatch = c.name == targetCourse.name
                        val teacherMatch = targetCourse.teacher.isNullOrEmpty() || 
                            (c.teacher?.contains(targetCourse.teacher ?: "") == true)
                        val timeMatch = targetCourse.time.isNullOrEmpty() || 
                            (c.time?.contains(targetCourse.time ?: "") == true)
                        nameMatch && (teacherMatch || timeMatch)
                    }
                    
                    if (matched != null) {
                        Log.d(TAG, "✅ 智能模式匹配成功: ${matched.name} | ${matched.teacher} | classId=${matched.classId}")
                        broadcastLog("✅ 匹配到课程: ${matched.teacher ?: "未知老师"}")
                        
                        // 更新 targetCourse 的 ID
                        targetCourse.classId = matched.classId
                        targetCourse.courseId = matched.courseId
                        targetCourse.kklxdm = matched.kklxdm
                        targetCourse._xkkz_id = matched._xkkz_id
                        targetCourse._rwlx = matched._rwlx
                        targetCourse._xklc = matched._xklc
                        // 🔧 智能模式匹配，确保不走精确模式
                        targetCourse.useExactMatch = false
                        
                        // 继续选课流程
                        fetchSelectionDetails(school, targetCourse)

                    } else {
                        broadcastLog("❌ 未找到匹配的课程: ${targetCourse.name}")
                        scheduleNextAttempt()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "智能模式解析失败: ${e.message}")
                    broadcastLog("❌ 解析课程列表失败")
                    scheduleNextAttempt()
                }
            }
        })
    }
    
    // 🔧 新智能模式：获取所有可选课程，本地匹配课程名，然后获取详情匹配教学班
    private fun smartModeMatchFromAllCourses(school: SchoolConfig, targetCourse: Course) {
        val targetName = targetCourse.name ?: ""
        if (targetName.isEmpty()) {
            broadcastLog("❌ 智能模式失败: 课程名为空")
            scheduleNextAttempt()
            return
        }
        
        // 🔧 优先使用缓存的课程列表（如果有）
        if (cachedAllCourses.isNotEmpty()) {
            Log.d(TAG, "🔄 智能模式: 使用缓存课程 (${cachedAllCourses.size}门)")
            broadcastLog("📋 使用缓存 ${cachedAllCourses.size} 门课程")
            matchCourseFromList(school, targetCourse, cachedAllCourses)
            return
        }
        
        // 没有缓存，需要请求API
        Log.d(TAG, "🔄 智能模式: 无缓存，请求API获取课程列表")
        
        // 使用已获取的参数 (从 fetchHiddenParamsAndProceed 获得的 courseParams)
        val params = courseParams?.takeIf { it.isNotEmpty() } ?: indexParams.toMap()
        
        fun getParam(baseName: String): String {
            params?.get(baseName)?.takeIf { it.isNotEmpty() }?.let { return it }
            for (i in 1..5) {
                params?.get("${baseName}_$i")?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return ""
        }
        
        // 🔧 构建请求：不带 filter_list 和 kch_id，获取所有可选课程
        val formData = mutableMapOf<String, String>()
        formData["rwlx"] = params?.get("rwlx") ?: "1"
        formData["xkly"] = params?.get("xkly") ?: "0"
        formData["bklx_id"] = params?.get("bklx_id") ?: "0"
        formData["sfkkjyxdxnxq"] = params?.get("sfkkjyxdxnxq") ?: "0"
        formData["kzkcgs"] = params?.get("kzkcgs") ?: "0"
        formData["xqh_id"] = getParam("xqh_id").ifEmpty { "1" }
        formData["jg_id"] = getParam("jg_id")
        formData["zyh_id"] = getParam("zyh_id")
        formData["zyfx_id"] = getParam("zyfx_id").ifEmpty { "wfx" }
        formData["txbsfrl"] = params?.get("txbsfrl") ?: "0"
        formData["njdm_id"] = getParam("njdm_id").ifEmpty { "2024" }
        formData["bh_id"] = getParam("bh_id")
        formData["xbm"] = getParam("xbm").ifEmpty { "1" }
        formData["xslbdm"] = getParam("xslbdm").ifEmpty { "421" }
        formData["mzm"] = getParam("mzm").ifEmpty { "01" }
        formData["xz"] = getParam("xz").ifEmpty { "4" }
        formData["ccdm"] = getParam("ccdm").ifEmpty { "3" }
        formData["xsbj"] = getParam("xsbj").ifEmpty { "0" }
        formData["sfkknj"] = params?.get("sfkknj") ?: "0"
        formData["gnjkxdnj"] = params?.get("gnjkxdnj") ?: "0"
        formData["sfkkzy"] = params?.get("sfkkzy") ?: "0"
        formData["kzybkxy"] = params?.get("kzybkxy") ?: "0"
        formData["sfznkx"] = params?.get("sfznkx") ?: "0"
        formData["zdkxms"] = params?.get("zdkxms") ?: "0"
        formData["sfkxq"] = params?.get("sfkxq") ?: "0"
        formData["sfkcfx"] = params?.get("sfkcfx") ?: "0"
        formData["bbhzxjxb"] = params?.get("bbhzxjxb") ?: "0"
        formData["kkbk"] = params?.get("kkbk") ?: "0"
        formData["kkbkdj"] = params?.get("kkbkdj") ?: "0"
        formData["bklbkcj"] = params?.get("bklbkcj") ?: "0"
        formData["xkxnm"] = getParam("xkxnm").ifEmpty { "2025" }
        formData["xkxqm"] = getParam("xkxqm").ifEmpty { "12" }
        formData["xkxskcgskg"] = params?.get("xkxskcgskg") ?: "0"
        formData["rlkz"] = params?.get("rlkz") ?: "0"
        formData["cdrlkz"] = params?.get("cdrlkz") ?: "0"
        formData["rlzlkz"] = params?.get("rlzlkz") ?: "1"
        formData["kklxdm"] = params?.get("kklxdm") ?: "01"
        // 🔧 关键：不传 kch_id 和 filter_list，获取所有课程
        formData["kch_id"] = ""
        formData["jxbzcxskg"] = params?.get("jxbzcxskg") ?: "0"
        formData["xklc"] = params?.get("xklc") ?: "2"
        formData["xkkz_id"] = params?.get("xkkz_id") ?: ""
        formData["cxbj"] = params?.get("cxbj") ?: "0"
        formData["fxbj"] = params?.get("fxbj") ?: "0"
        // 🔧 补充 CourseListRoute 中的额外参数
        formData["njdm_id_1"] = getParam("njdm_id_1").ifEmpty { getParam("njdm_id") }
        formData["zyh_id_1"] = getParam("zyh_id_1").ifEmpty { getParam("zyh_id") }
        formData["xkzgbj"] = "0"
        formData["jxbzb"] = ""
        // 🔧 获取所有课程（不加 filter）
        formData["kspage"] = "0"
        formData["jspage"] = "10000"  // 尽可能多获取
        
        // 🔧 调试：打印关键参数
        Log.d(TAG, "🔄 智能模式关键参数: xkkz_id=${formData["xkkz_id"]}, kklxdm=${formData["kklxdm"]}, xklc=${formData["xklc"]}")
        Log.d(TAG, "🔄 智能模式: 获取所有课程 (参数数: ${formData.size})")
        val postBody = formData.entries.joinToString("&") { "${it.key}=${it.value}" }
        
        CourseApiClient.getInstance().fetchAvailableCourses(school, postBody, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                broadcastLog("❌ 获取课程列表失败: ${e.message}")
                scheduleNextAttempt()
            }
            
            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string() ?: ""
                try {
                    val courses = com.tyust.course.utils.CourseParser.parseCourseListFromJson(json)
                    Log.d(TAG, "🔄 智能模式: 获取到 ${courses.size} 门课程，开始本地匹配...")
                    broadcastLog("📋 获取到 ${courses.size} 门课程")
                    
                    if (courses.isEmpty()) {
                        broadcastLog("❌ 课程列表为空")
                        scheduleNextAttempt()
                        return
                    }
                    
                    // 🔧 改进的本地匹配：带权重评分
                    val targetNameLower = targetName.lowercase()
                    val targetTeacher = targetCourse.teacher?.lowercase() ?: ""
                    val targetTime = targetCourse.time?.lowercase() ?: ""
                    
                    // 先打印部分课程名用于调试
                    Log.d(TAG, "🔍 部分课程名预览: ${courses.take(5).map { it.name }}")
                    
                    // 评分匹配
                    data class MatchResult(val course: Course, val score: Int)
                    
                    val scoredCourses = courses.mapNotNull { c ->
                        val courseName = c.name?.lowercase() ?: ""
                        var score = 0
                        
                        // 1. 精确匹配 (最高优先级: 1000分)
                        if (courseName == targetNameLower) {
                            score = 1000
                        }
                        // 2. 课程名包含目标名 (100分)
                        else if (courseName.contains(targetNameLower)) {
                            score = 100
                        }
                        // 3. 目标名包含课程名 (80分)
                        else if (targetNameLower.contains(courseName) && courseName.isNotEmpty()) {
                            score = 80
                        }
                        // 4. 部分关键词匹配 (50分)
                        else {
                            val keywords = targetNameLower.split("（", "(", "）", ")", " ", "、").filter { it.length >= 2 }
                            val matchCount = keywords.count { kw -> courseName.contains(kw) }
                            if (matchCount > 0) {
                                score = 50 + matchCount * 10
                            }
                        }
                        
                        // 5. 老师匹配加分 (如果有填写)
                        if (score > 0 && targetTeacher.isNotEmpty()) {
                            val courseTeacher = c.teacher?.lowercase() ?: ""
                            if (courseTeacher.contains(targetTeacher) || targetTeacher.contains(courseTeacher)) {
                                score += 20
                            }
                        }
                        
                        if (score > 0) MatchResult(c, score) else null
                    }.sortedByDescending { it.score }
                    
                    Log.d(TAG, "🔍 匹配结果: 找到 ${scoredCourses.size} 门候选课程")
                    if (scoredCourses.isNotEmpty()) {
                        Log.d(TAG, "🔍 前3名: ${scoredCourses.take(3).map { "${it.course.name}(${it.score}分)" }}")
                    }
                    
                    if (scoredCourses.isEmpty()) {
                        broadcastLog("❌ 未找到课程: $targetName")
                        scheduleNextAttempt()
                        return
                    }
                    
                    // 取分数最高的课程
                    val matched = scoredCourses.first().course
                    Log.d(TAG, "✅ 智能模式匹配: ${matched.name} (${scoredCourses.first().score}分) | courseId=${matched.courseId}")
                    broadcastLog("✅ 匹配到: ${matched.name} (${scoredCourses.first().score}分)")
                    
                    // 🔧 更新目标课程的关键参数
                    targetCourse.courseId = matched.courseId
                    targetCourse.kklxdm = matched.kklxdm
                    targetCourse._xkkz_id = matched._xkkz_id
                    targetCourse._rwlx = matched._rwlx
                    targetCourse._xklc = matched._xklc
                    targetCourse.zyh_id = matched.zyh_id
                    targetCourse.njdm_id = matched.njdm_id
                    // 🔧 不设置 classId，让 fetchSelectionDetails 通过老师/时间匹配教学班
                    targetCourse.useExactMatch = false
                    
                    // 继续获取详情 (fetchSelectionDetails 会通过老师匹配教学班)
                    handler.post { fetchSelectionDetails(school, targetCourse) }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "智能模式解析失败: ${e.message}")
                    broadcastLog("❌ 解析课程列表失败")
                    scheduleNextAttempt()
                }
            }
        })
    }
    
    // 🔧 从课程列表中匹配目标课程（供smartModeMatchFromAllCourses和缓存复用）
    private fun matchCourseFromList(school: SchoolConfig, targetCourse: Course, courses: List<Course>) {
        // 🔧 关键修复：确保从队列中同步最新的老师/时间要求
        val queue = SmartSelector.getInstance().queue
        val queueCourse = queue.find { it.name == targetCourse.name }
        
        Log.d(TAG, "❗❗❗ 开始同步信息: 目标=${targetCourse.name}, 队列大小=${queue.size}")
        if (queueCourse != null) {
            Log.d(TAG, "❗❗❗ 找到队列课程: 老师=${queueCourse.teacher}")
            if (!queueCourse.teacher.isNullOrEmpty()) targetCourse.teacher = queueCourse.teacher
            if (!queueCourse.time.isNullOrEmpty()) targetCourse.time = queueCourse.time
        } else {
            Log.d(TAG, "❗❗❗ 未在队列中找到课程: ${targetCourse.name}")
        }

        val targetName = targetCourse.name ?: ""
        val targetNameLower = targetName.lowercase()
        val targetTeacher = targetCourse.teacher?.lowercase() ?: ""
        
        Log.d(TAG, "🔍 开始匹配，共 ${courses.size} 门课程，目标老师: '$targetTeacher'")
        
        // 评分匹配
        data class MatchResult(val course: Course, val score: Int)
        
        val scoredCourses = courses.mapNotNull { c ->
            val courseName = c.name?.lowercase() ?: ""
            var score = 0
            
            // 1. 精确匹配 (最高优先级: 1000分)
            if (courseName == targetNameLower) {
                score = 1000
            }
            // 2. 课程名包含目标名 (100分)
            else if (courseName.contains(targetNameLower)) {
                score = 100
            }
            // 3. 目标名包含课程名 (80分)
            else if (targetNameLower.contains(courseName) && courseName.isNotEmpty()) {
                score = 80
            }
            // 4. 部分关键词匹配 (50分)
            else {
                val keywords = targetNameLower.split("（", "(", "）", ")", " ", "、").filter { it.length >= 2 }
                val matchCount = keywords.count { kw -> courseName.contains(kw) }
                if (matchCount > 0) {
                    score = 50 + matchCount * 10
                }
            }
            
            // 5. 老师匹配加分 (如果有填写)
            if (score > 0 && targetTeacher.isNotEmpty()) {
                val courseTeacher = c.teacher?.lowercase() ?: ""
                if (courseTeacher.contains(targetTeacher) || targetTeacher.contains(courseTeacher)) {
                    score += 20
                }
            }
            
            if (score > 0) MatchResult(c, score) else null
        }.sortedByDescending { it.score }
        
        Log.d(TAG, "🔍 匹配结果: 找到 ${scoredCourses.size} 门候选课程")
        if (scoredCourses.isNotEmpty()) {
            Log.d(TAG, "🔍 前3名: ${scoredCourses.take(3).map { "${it.course.name}(${it.score}分)" }}")
        }
        
        if (scoredCourses.isEmpty()) {
            broadcastLog("❌ 未找到课程: $targetName")
            scheduleNextAttempt()
            return
        }
        
        // 取分数最高的课程
        val matched = scoredCourses.first().course
        Log.d(TAG, "✅ 智能模式匹配: ${matched.name} (${scoredCourses.first().score}分) | courseId=${matched.courseId}")
        broadcastLog("✅ 匹配到: ${matched.name} (${scoredCourses.first().score}分)")
        
        // 更新目标课程的关键参数
        targetCourse.courseId = matched.courseId
        targetCourse.kklxdm = matched.kklxdm
        targetCourse._xkkz_id = matched._xkkz_id
        targetCourse._rwlx = matched._rwlx
        targetCourse._xklc = matched._xklc
        targetCourse.zyh_id = matched.zyh_id
        targetCourse.njdm_id = matched.njdm_id
        targetCourse.useExactMatch = false
        
        // 继续获取详情
        handler.post { fetchSelectionDetails(school, targetCourse) }
    }
    
    // 解析HTML页面中的隐藏参数 (与CourseListRoute保持一致)
    private fun parseHiddenParams(html: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        try {
            val pattern = java.util.regex.Pattern.compile(
                """<input[^>]*type\s*=\s*["']hidden["'][^>]*name\s*=\s*["']([^"']+)["'][^>]*value\s*=\s*["']([^"']*)["'][^>]*>""",
                java.util.regex.Pattern.CASE_INSENSITIVE
            )
            val matcher = pattern.matcher(html)
            while (matcher.find()) {
                val name = matcher.group(1)
                val value = matcher.group(2)
                if (name != null) {
                    params[name] = value ?: ""
                }
            }
            // 也尝试反向顺序
            val pattern2 = java.util.regex.Pattern.compile(
                """<input[^>]*value\s*=\s*["']([^"']*)["'][^>]*name\s*=\s*["']([^"']+)["'][^>]*>""",
                java.util.regex.Pattern.CASE_INSENSITIVE
            )
            val matcher2 = pattern2.matcher(html)
            while (matcher2.find()) {
                val value = matcher2.group(1)
                val name = matcher2.group(2)
                if (name != null && !params.containsKey(name)) {
                    params[name] = value ?: ""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseHiddenParams error: ${e.message}")
        }
        return params
    }
    
    private fun fetchSelectionDetails(school: SchoolConfig, course: Course) {
        val xkkz_id = course._xkkz_id?.takeIf { it.isNotEmpty() } ?: courseParams?.get("xkkz_id") ?: ""
        val njdm_id = course.njdm_id?.takeIf { it.isNotEmpty() } ?: courseParams?.get("njdm_id") ?: "2024"
        val zyh_id = course.zyh_id?.takeIf { it.isNotEmpty() } ?: courseParams?.get("zyh_id") ?: ""
        val kklxdm = course.kklxdm?.takeIf { it.isNotEmpty() } ?: courseParams?.get("kklxdm") ?: "01"
        val rwlx = course._rwlx?.takeIf { it.isNotEmpty() } ?: courseParams?.get("rwlx") ?: "1"
        val xklc = course._xklc?.takeIf { it.isNotEmpty() } ?: courseParams?.get("xklc") ?: "2"
        
        // 🔧 构建完整的 POST body（与 CourseListRoute 和 Web 版一致，约40个参数）
        // 注意：hiddenParams 已在 fetchHiddenParamsAndProceed 中合并到 courseParams
        fun getParam(baseName: String): String {
            courseParams?.get(baseName)?.takeIf { it.isNotEmpty() }?.let { return it }
            for (i in 1..5) {
                courseParams?.get("${baseName}_$i")?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return ""
        }
        
        val formData = mutableMapOf<String, String>()
        formData["rwlx"] = rwlx
        formData["xkly"] = courseParams?.get("xkly") ?: "0"
        formData["bklx_id"] = courseParams?.get("bklx_id") ?: "0"
        formData["sfkkjyxdxnxq"] = courseParams?.get("sfkkjyxdxnxq") ?: "0"
        formData["kzkcgs"] = courseParams?.get("kzkcgs") ?: "0"
        formData["xqh_id"] = getParam("xqh_id").ifEmpty { "1" }
        formData["jg_id"] = getParam("jg_id").ifEmpty { "05" }
        formData["zyh_id"] = zyh_id
        formData["zyfx_id"] = getParam("zyfx_id").ifEmpty { "wfx" }
        formData["txbsfrl"] = courseParams?.get("txbsfrl") ?: "0"
        formData["njdm_id"] = njdm_id
        formData["bh_id"] = getParam("bh_id")
        formData["xbm"] = getParam("xbm").ifEmpty { "1" }
        formData["xslbdm"] = getParam("xslbdm").ifEmpty { "421" }
        formData["mzm"] = getParam("mzm").ifEmpty { "01" }
        formData["xz"] = getParam("xz").ifEmpty { "4" }
        formData["ccdm"] = getParam("ccdm").ifEmpty { "3" }
        formData["xsbj"] = getParam("xsbj").ifEmpty { "0" }
        formData["sfkknj"] = courseParams?.get("sfkknj") ?: "0"
        formData["gnjkxdnj"] = courseParams?.get("gnjkxdnj") ?: "0"
        formData["sfkkzy"] = courseParams?.get("sfkkzy") ?: "0"
        formData["kzybkxy"] = courseParams?.get("kzybkxy") ?: "0"
        formData["sfznkx"] = courseParams?.get("sfznkx") ?: "0"
        formData["zdkxms"] = courseParams?.get("zdkxms") ?: "0"
        formData["sfkxq"] = courseParams?.get("sfkxq") ?: course._sfkxq ?: "0"
        formData["sfkcfx"] = courseParams?.get("sfkcfx") ?: "0"
        formData["bbhzxjxb"] = courseParams?.get("bbhzxjxb") ?: "0"
        formData["kkbk"] = courseParams?.get("kkbk") ?: "0"
        formData["kkbkdj"] = courseParams?.get("kkbkdj") ?: "0"
        formData["bklbkcj"] = courseParams?.get("bklbkcj") ?: "0"
        formData["xkxnm"] = getParam("xkxnm").ifEmpty { "2025" }
        formData["xkxqm"] = getParam("xkxqm").ifEmpty { "12" }
        formData["xkxskcgskg"] = courseParams?.get("xkxskcgskg") ?: course._xkxskcgskg ?: "0"
        formData["rlkz"] = courseParams?.get("rlkz") ?: "0"
        formData["cdrlkz"] = courseParams?.get("cdrlkz") ?: "0"
        formData["rlzlkz"] = courseParams?.get("rlzlkz") ?: "1"
        formData["kklxdm"] = kklxdm
        formData["kch_id"] = course.courseId ?: ""
        formData["jxbzcxskg"] = courseParams?.get("jxbzcxskg") ?: "0"
        formData["xklc"] = xklc
        formData["xkkz_id"] = xkkz_id
        formData["cxbj"] = courseParams?.get("cxbj") ?: "0"
        formData["fxbj"] = courseParams?.get("fxbj") ?: "0"
        
        val postBody = formData.entries.joinToString("&") { "${it.key}=${it.value}" }
        
        Log.d(TAG, "fetchSelectionDetails 参数数量: ${formData.size}")
        
        CourseApiClient.getInstance().fetchCourseSelectionDetails(school, postBody,
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    failCount++
                    broadcastUpdate("❌ 获取详情失败: ${e.message}")
                    scheduleNextAttempt()
                }
                
                override fun onResponse(call: Call, response: Response) {
                    val json = response.body?.string() ?: ""
                    // 🔧 智能模式时不传 classId，让 parseSelectionDetails 匹配第一个或按老师匹配
                    val targetClassId = if (course.useExactMatch) course.classId else null
                    val details = parseSelectionDetails(json, targetClassId, course.teacher)
                    
                    if (details != null) {
                        executeSelection(school, course, details, rwlx, xklc)
                    } else {
                        // 🔧 Fallback: 如果解析失败（如返回"0"）但我们有保存的 doJxbId，直接尝试抢课
                        if (!course.doJxbId.isNullOrEmpty() && !course.classId.isNullOrEmpty()) {
                            Log.w(TAG, "⚠️ 解析失败，强制使用保存的 doJxbId=${course.doJxbId}")
                            broadcastLog("⚠️ 解析失败，强制使用保存的 ID 抢课")
                            
                            val fallbackDetails = SelectionDetails(
                                doJxbId = course.doJxbId!!,
                                njdmId = course.njdm_id ?: "2024",
                                zyhId = course.zyh_id ?: "",
                                rlkz = course.rlkz ?: "0",
                                rlzlkz = course.rlzlkz ?: "1",
                                sxbj = course.sxbj ?: "0",
                                xxkbj = course.xxkbj ?: "0",
                                cxbj = "0",
                                xkxnm = "2025",
                                xkxqm = "12",
                                jcxxId = "",
                                xkkzId = course._xkkz_id ?: ""
                            )
                            executeSelection(school, course, fallbackDetails, rwlx, xklc)
                            return
                        }
                        
                        failCount++
                        broadcastUpdate("❌ 获取加密ID失败")
                        scheduleNextAttempt()
                    }
                }
            }
        )
    }
    
    // 🔧 修复：增加 targetClassId 和 targetTeacher 参数，匹配用户指定的教学班
    private fun parseSelectionDetails(json: String, targetClassId: String? = null, targetTeacher: String? = null): SelectionDetails? {
        // 如果返回 "0" 直接返回 null
        if (json.trim() == "0") return null
        
        try {
            val arr = JSONArray(json)
            if (arr.length() == 0) return null
            
            Log.d(TAG, "=== 开始匹配教学班 ===")
            Log.d(TAG, "目标 classId: '$targetClassId', 目标老师: '$targetTeacher', 响应包含 ${arr.length()} 个教学班")
            
            // 🔧 优先按 classId 匹配，否则按老师匹配，最后取第一个
            var targetObj: org.json.JSONObject? = null
            
            // 1. 精确模式：按 classId 匹配
            if (!targetClassId.isNullOrEmpty()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val jxbId = obj.optString("jxb_id", "")
                    val doJxbId = obj.optString("do_jxb_id", "")
                    
                    Log.d(TAG, "[$i] jxb_id='$jxbId', do_jxb_id长度=${doJxbId.length}")
                    
                    if (jxbId == targetClassId || doJxbId == targetClassId) {
                        targetObj = obj
                        Log.d(TAG, "✅ classId匹配成功: index=$i, jxb_id=$jxbId")
                        break
                    }
                }
            }
            
            // 2. 智能模式：按老师名匹配
            if (targetObj == null && !targetTeacher.isNullOrEmpty()) {
                Log.d(TAG, "🔄 智能模式：按老师名匹配...")
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val jsxx = obj.optString("jsxx", "") // 格式: "工号/姓名/职称;工号/姓名/职称"
                    val teacherNames = jsxx.split(";", "/").filter { it.length in 2..6 } // 提取姓名
                    
                    Log.d(TAG, "[$i] jsxx='$jsxx', 解析出老师: $teacherNames")
                    
                    if (teacherNames.any { it.contains(targetTeacher) || targetTeacher.contains(it) }) {
                        targetObj = obj
                        Log.d(TAG, "✅ 老师匹配成功: index=$i, 老师='$targetTeacher' in $teacherNames")
                        break
                    }
                }
            }
            
            // 3. 没有匹配到则取第一个
            val obj = targetObj ?: arr.getJSONObject(0)
            if (targetObj == null && arr.length() > 1) {
                Log.w(TAG, "⚠️ 未匹配到目标教学班，使用第一个")
            }
                
            // 提取加密的 jxb_id（Web版通常是100+字符的长字符串，但有时是32字符的短ID也可用）
            var doJxbId = obj.optString("do_jxb_id", "")
            if (doJxbId.isEmpty()) {
                doJxbId = obj.optString("jxb_id", "")
            }
            
            // 与 Web 版一致：短 ID 警告但继续执行，不直接失败
            if (doJxbId.isEmpty()) {
                Log.e(TAG, "jxb_id 为空，无法选课")
                return null
            }
            if (doJxbId.length < 50) {
                Log.w(TAG, "⚠️ jxb_id 长度较短 (${doJxbId.length}字符)，可能是短ID，强制继续...")
            } else {
                Log.d(TAG, "✅ jxb_id 验证通过，长度: ${doJxbId.length}字符")
            }
            
            // 提取所有参数（与Web版完全一致）
            return SelectionDetails(
                doJxbId = doJxbId,
                njdmId = obj.optString("njdm_id", "2024"),
                zyhId = obj.optString("zyh_id", ""),
                rlkz = obj.optString("rlkz", "0"),
                rlzlkz = obj.optString("rlzlkz", "1"),
                sxbj = obj.optString("sxbj", "1"),
                xxkbj = obj.optString("xxkbj", "0"),
                cxbj = obj.optString("cxbj", "0"),
                xkxnm = obj.optString("xkxnm", "2025"),
                xkxqm = obj.optString("xkxqm", "12"),
                jcxxId = obj.optString("jcxx_id", ""),  // Web版关键参数
                xkkzId = obj.optString("xkkz_id", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseSelectionDetails error: ${e.message}")
        }
        return null
    }
    
    // 选课详情数据类
    data class SelectionDetails(
        val doJxbId: String,
        val njdmId: String,
        val zyhId: String,
        val rlkz: String,
        val rlzlkz: String,
        val sxbj: String,
        val xxkbj: String,
        val cxbj: String,
        val xkxnm: String,
        val xkxqm: String,
        val jcxxId: String,
        val xkkzId: String
    )
    
    private fun executeSelection(
        school: SchoolConfig, course: Course, details: SelectionDetails,
        rwlx: String, xklc: String
    ) {
        // 优先使用课程数据中的参数
        val finalRwlx = if (course._rwlx?.isNotEmpty() == true) course._rwlx else rwlx
        val finalXklc = if (course._xklc?.isNotEmpty() == true) course._xklc else xklc
        val finalXkkzId = if (course._xkkz_id?.isNotEmpty() == true) course._xkkz_id else details.xkkzId
        val finalNjdmId = if (course.njdm_id?.isNotEmpty() == true) course.njdm_id else details.njdmId
        val finalZyhId = if (course.zyh_id?.isNotEmpty() == true) course.zyh_id else details.zyhId
        val finalKklxdm = if (course.kklxdm?.isNotEmpty() == true) course.kklxdm else "01"
        
        // 构建POST body（参数顺序与Web版完全一致）
        val postBody = StringBuilder()
        postBody.append("jxb_ids=").append(details.doJxbId)
        postBody.append("&kch_id=").append(course.courseId)
        postBody.append("&kcmc=(").append(course.courseId).append(")").append(course.name ?: "")
        postBody.append("&rwlx=").append(finalRwlx)
        postBody.append("&rlkz=").append(details.rlkz)
        postBody.append("&rlzlkz=").append(details.rlzlkz)
        postBody.append("&sxbj=").append(details.sxbj)
        postBody.append("&xxkbj=").append(details.xxkbj)
        postBody.append("&qz=0")
        postBody.append("&cxbj=").append(details.cxbj)
        postBody.append("&xkkz_id=").append(finalXkkzId)
        postBody.append("&njdm_id=").append(finalNjdmId)
        postBody.append("&zyh_id=").append(finalZyhId)
        postBody.append("&kklxdm=").append(finalKklxdm)
        postBody.append("&xklc=").append(finalXklc)
        postBody.append("&xkxnm=").append(details.xkxnm)
        postBody.append("&xkxqm=").append(details.xkxqm)
        postBody.append("&jcxx_id=").append(details.jcxxId)  // Web版关键参数
        
        CourseApiClient.getInstance().selectCourse(school, postBody.toString(), object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                failCount++
                broadcastUpdate("❌ 选课请求失败: ${e.message}")
                scheduleNextAttempt()
            }
            
            override fun onResponse(call: Call, response: Response) {
                val result = response.body?.string() ?: ""
                val success = result.contains("\"flag\":\"1\"") || result.contains("成功")
                
                if (success) {
                    successCount++
                    broadcastUpdate("🎉 抢课成功！${course.name}")
                    
                    // Step 3: 验证选课结果 (Web版 verifyCourseSelection)
                    verifySelectionAsync(school, course)
                } else {
                    failCount++
                    val errorMsg = parseErrorMessage(result)
                    broadcastUpdate("❌ 第 $retryCount 次失败: $errorMsg")
                    scheduleNextAttempt()
                }
            }
        })
    }
    
    // Step 3: 异步验证选课结果
    private fun verifySelectionAsync(school: SchoolConfig, course: Course) {
        Thread {
            val verified = verifySelection(school, course.courseId ?: "")
            
            handler.post {
                if (verified) {
                    broadcastLog("✅ 验证成功: 课程已加入已选列表")
                } else {
                    broadcastLog("⚠️ 验证未通过，但服务器已返回成功")
                }
                
                // 成功抢到后从队列中移除
                SmartSelector.getInstance().removeFromQueue(course)
                broadcastLog("📋 已从队列移除: ${course.name}")
                
                successCount++
                multiKeywordTotalSuccess++
                
                // 通知 UI 刷新队列，并标记课程为成功
                broadcastQueueUpdate(course.name, "success")
                
                showSuccessNotification(course.name ?: "未知课程")
                
                // 检查是否还有更多关键词需要处理
                if (keywordQueue.size > 1 && currentKeywordIndex < keywordQueue.size - 1) {
                    broadcastLog("🎉 课程「${course.name}」抢课成功！继续处理下一个...")
                    updateNotification("✅ 成功 $multiKeywordTotalSuccess/${keywordQueue.size} | 继续下一个...")
                    
                    // 处理下一个关键词
                    currentKeywordIndex++
                    handler.postDelayed({
                        startSingleKeywordGrabbing(keywordQueue[currentKeywordIndex])
                    }, 2000) // 等待2秒后处理下一个
                    return@post
                }
                
                // 🔧 队列模式抢到后继续下一个
                if (isQueueMode) {
                    val currentQueue = SmartSelector.getInstance().queue
                    if (currentQueue.isEmpty()) {
                        broadcastLog("🎊 队列处理完成！共抢到 $totalQueueSuccess 门课程")
                        isRunning = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        // ❌ 不要增加 currentQueueIndex，因为刚才已经 removeFromQueue 了，
                        // 后面的课程会自动往前移到当前 index。
                        if (currentQueueIndex >= currentQueue.size) {
                            currentQueueIndex = 0 // 回到开头（如果需要循环）或在此停止
                        }
                        
                        broadcastLog("🎉 课程「${course.name}」抢课成功！继续队列下一门...")
                        totalQueueSuccess++
                        // 1.5秒后开始下一门
                        handler.postDelayed({ startNextQueueItem() }, 1500)
                    }
                    return@post
                }
                
                // 所有处理完成
                if (keywordQueue.size > 1) {
                    broadcastLog("🎊 全部完成！成功抢到 $multiKeywordTotalSuccess/${keywordQueue.size} 门课程")
                } else if (isQueueMode) {
                    totalQueueSuccess++
                    broadcastLog("🎊 队列处理完成！共抢到 $totalQueueSuccess 门课程")
                }
                updateNotification("✅ 抢课成功！${course.name}")
                
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }
    
    // 验证选课是否成功 (Web版 verifyCourseSelection)
    private fun verifySelection(school: SchoolConfig, courseId: String): Boolean {
        try {
            val selectedCoursesJson = CourseApiClient.getInstance().fetchSelectedCoursesSync(school, "")
            if (selectedCoursesJson == null) return false
            
            val arr = JSONArray(selectedCoursesJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val kchId = obj.optString("kch_id", "")
                if (kchId == courseId) {
                    Log.d(TAG, "验证成功: 找到已选课程 $courseId")
                    return true
                }
            }
            Log.w(TAG, "验证未通过: 未在已选列表中找到 $courseId")
        } catch (e: Exception) {
            Log.e(TAG, "verifySelection error: ${e.message}")
        }
        return false
    }
    
    private fun parseErrorMessage(json: String): String {
        return try {
            val obj = org.json.JSONObject(json)
            obj.optString("msg", null) ?: obj.optString("message", "请重试")
        } catch (e: Exception) {
            if (json.contains("人数已满")) "人数已满"
            else if (json.contains("冲突")) "时间冲突"
            else if (json.contains("已选")) "已选过该课程"
            else "请重试"
        }
    }
    
    private fun scheduleNextAttempt() {
        if (!isRunning) return
        grabRunnable = Runnable { runGrabLoop() }
        handler.postDelayed(grabRunnable!!, interval.toLong())
    }
    
    private fun broadcastUpdate(logMessage: String) {
        Log.d(TAG, logMessage)
        
        val intent = Intent(BROADCAST_UPDATE).apply {
            putExtra(EXTRA_LOG_MESSAGE, logMessage)
            putExtra(EXTRA_SUCCESS_COUNT, successCount)
            putExtra(EXTRA_FAIL_COUNT, failCount)
            putExtra(EXTRA_RETRY_COUNT, retryCount)
            putExtra(EXTRA_IS_RUNNING, isRunning)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
    
    // 通知 UI 队列已更新（带课程状态）
private fun broadcastQueueUpdate(courseName: String? = null, status: String? = null, courseId: String? = null) {
    val intent = Intent(BROADCAST_UPDATE).apply {
        putExtra(EXTRA_QUEUE_UPDATED, true)
        putExtra(EXTRA_IS_RUNNING, isRunning)  // 🔧 添加运行状态
        courseName?.let { putExtra(EXTRA_COURSE_NAME_STATUS, it) }
        status?.let { putExtra(EXTRA_COURSE_STATUS, it) }
        courseId?.let { putExtra(EXTRA_COURSE_ID, it) }
        putExtra(EXTRA_SUCCESS_COUNT, successCount)
        putExtra(EXTRA_FAIL_COUNT, failCount)
        setPackage(packageName)
    }
    sendBroadcast(intent)
}
    
    private fun showSuccessNotification(courseName: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎉 抢课成功！")
            .setContentText("已成功选上: $courseName")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID + 1, notification)
    }
    
    // ============ 关键词抢课模式 ============
    
    private fun broadcastLog(message: String) {
        Log.d(TAG, message)
        broadcastUpdate(message)
        
        // 同时保存到 SharedPreferences 供 UI 读取
        val prefs = getSharedPreferences("grab_pro_prefs", Context.MODE_PRIVATE)
        val currentLog = prefs.getString("log_text", "") ?: ""
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val newLog = "$currentLog[$timestamp] $message\n"
        prefs.edit().putString("log_text", newLog).apply()
    }
    
    // 关键词抢课重试计数
    private var keywordFetchRetryCount = 0
    private val MAX_FETCH_RETRIES = 5
    private val FETCH_RETRY_DELAY = 10000L // 10秒
    private var currentKeywords = ""
    
    // 多分类遍历状态（与 CourseListLogicHelper 一致）
    data class TabParam(val kklxdm: String, val xkkz_id: String, val njdm_id: String, val zyh_id: String)
    private var tabParamsList = mutableListOf<TabParam>()
    private var currentTabIndex = 0
    private var indexParams = mutableMapOf<String, String>()
    private var displayParams = mutableMapOf<String, String>()
    private var allFetchedCourses = mutableListOf<Course>()
    
    private fun startKeywordGrabbing(keywords: String) {
        // 解析多关键词：使用分号(;)或换行分隔多组关键词
        keywordQueue.clear()
        keywordQueue.addAll(
            keywords.split(";", "；", "\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        )
        
        currentKeywordIndex = 0
        multiKeywordTotalSuccess = 0
        
        if (keywordQueue.isEmpty()) {
            broadcastLog("❌ 未提供有效的课程关键词")
            stopSelf()
            return
        }
        
        if (keywordQueue.size > 1) {
            val modeDesc = if (isParallelMode) "并行处理" else "顺序处理"
            broadcastLog("📋 检测到 ${keywordQueue.size} 组课程关键词，将 $modeDesc")
            keywordQueue.forEachIndexed { index, kw ->
                broadcastLog("   ${index + 1}. $kw")
            }
        }
        
        if (isParallelMode && keywordQueue.size > 1) {
            // 并行模式：同时启动多个关键词的抢课
            startParallelGrabbing()
        } else {
            // 顺序模式：开始处理第一个关键词
            startSingleKeywordGrabbing(keywordQueue[currentKeywordIndex])
        }
    }
    
    // 并行抢课：同时处理多个关键词
    private fun startParallelGrabbing() {
        val workersToStart = minOf(parallelWorkerCount, keywordQueue.size)
        broadcastLog("⚡ 并行启动 $workersToStart 个抢课任务")
        
        activeWorkers.clear()
        
        for (i in 0 until workersToStart) {
            if (i < keywordQueue.size) {
                val workerId = i
                activeWorkers.add(workerId)
                
                // 延迟启动以避免同时请求导致服务器拒绝
                handler.postDelayed({
                    startParallelWorker(workerId, keywordQueue[i])
                }, (i * 500).toLong())
            }
        }
    }
    
    // 并行工作线程：独立处理一个关键词
    private fun startParallelWorker(workerId: Int, keywords: String) {
        broadcastLog("🔄 工作线程 $workerId 启动: $keywords")
        
        // 发送 GRABBING 状态
        val keyParts = keywords.split(",", "，", " ")
        val displayName = keyParts.firstOrNull() ?: keywords
        broadcastQueueUpdate(displayName, "grabbing", keywords)
        
        // 直接开始获取课程参数和抢课 (复用单关键词逻辑)
        currentKeywords = keywords
        currentKeywordIndex = workerId
        keywordFetchRetryCount = 0
        fetchCourseParamsWithRetry()
    }
    
    // 处理单个关键词组的抢课
    private fun startSingleKeywordGrabbing(keywords: String) {
        currentKeywords = keywords
        keywordFetchRetryCount = 0
        tabParamsList.clear()
        indexParams.clear()
        displayParams.clear()
        allFetchedCourses.clear()
        currentTabIndex = 0
        
        // 重置单次抢课计数
        successCount = 0
        failCount = 0
        retryCount = 0
        skipCount = 0
        
        broadcastLog("🎯 开始处理关键词 [${currentKeywordIndex + 1}/${keywordQueue.size}]: $keywords")
        
        // 发送 GRABBING 状态给 UI
        val keyParts = keywords.split(",", "，", " ", ";", "；")
        val displayName = keyParts.firstOrNull() ?: keywords
        broadcastQueueUpdate(displayName, "grabbing", keywords)
        
        fetchCourseParamsWithRetry()
    }
    
    private fun fetchCourseParamsWithRetry() {
        val school = currentSchool ?: return
        
        keywordFetchRetryCount++
        broadcastLog("📚 获取选课参数 (第${keywordFetchRetryCount}/${MAX_FETCH_RETRIES}次)...")
        
        CourseApiClient.getInstance().fetchCourseParams(school, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                broadcastLog("⚠️ 请求失败: ${e.message}")
                retryOrFail("网络错误")
            }
            
            override fun onResponse(call: Call, response: Response) {
                val html = response.body?.string() ?: ""
                if (html.isEmpty()) {
                    retryOrFail("响应为空")
                    return
                }
                parseIndexParamsAndStart(html)
            }
        })
    }
    
    private fun retryOrFail(reason: String) {
        if (keywordFetchRetryCount < MAX_FETCH_RETRIES) {
            broadcastLog("⏳ ${reason}，${FETCH_RETRY_DELAY/1000}秒后重试...")
            handler.postDelayed({
                if (isRunning || keywordFetchRetryCount < MAX_FETCH_RETRIES) {
                    fetchCourseParamsWithRetry()
                }
            }, FETCH_RETRY_DELAY)
        } else {
            broadcastLog("❌ 连续${MAX_FETCH_RETRIES}次获取失败")
            
            // 标记当前课程为失败 - 使用 currentKeywords 作为 courseId
            failCount++
            val failedCourseName = currentKeywords.split(",", "，", " ", ";", "；").firstOrNull() ?: "未知课程"
            broadcastQueueUpdate(failedCourseName, "failed", currentKeywords)
            
            // 检查是否还有更多关键词需要处理
            if (keywordQueue.size > 1 && currentKeywordIndex < keywordQueue.size - 1) {
                broadcastLog("⏭️ 当前课程获取失败，切换到下一个关键词...")
                currentKeywordIndex++
                isRunning = false  // 重置以便下一个关键词可以启动
                
                handler.postDelayed({
                    startSingleKeywordGrabbing(keywordQueue[currentKeywordIndex])
                }, 1000)
            } else {
                // 没有更多关键词，停止服务
                if (keywordQueue.size > 1) {
                    broadcastLog("🏁 全部关键词处理完成，成功: $multiKeywordTotalSuccess/${keywordQueue.size}")
                }
                stopSelf()
            }
        }
    }
    
    // 与 CourseListLogicHelper.parseIndexParamsAndFetch 完全一致的逻辑
    private fun parseIndexParamsAndStart(html: String) {
        try {
            indexParams.clear()
            tabParamsList.clear()
            
            // 解析 <input> 隐藏字段 (正向)
            val pattern = """<input[^>]*name="([^"]+)"[^>]*value="([^"]*)"[^>]*>""".toRegex()
            pattern.findAll(html).forEach { match ->
                indexParams[match.groupValues[1]] = match.groupValues[2]
            }
            
            // 解析 <input> 隐藏字段 (反向)
            val pattern2 = """<input[^>]*value="([^"]*)"[^>]*name="([^"]+)"[^>]*>""".toRegex()
            pattern2.findAll(html).forEach { match ->
                val name = match.groupValues[2]
                if (!indexParams.containsKey(name)) {
                    indexParams[name] = match.groupValues[1]
                }
            }
            
            Log.d(TAG, "解析到 ${indexParams.size} 个 Index 参数")
            
            // 提取所有 queryCourse Tab 参数（与 CourseListLogicHelper 完全一致）
            val queryCoursePattern = """queryCourse\s*\(\s*this\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*\)""".toRegex()
            queryCoursePattern.findAll(html).forEach { match ->
                tabParamsList.add(TabParam(
                    match.groupValues[1],  // kklxdm
                    match.groupValues[2],  // xkkz_id
                    match.groupValues[3],  // njdm_id
                    match.groupValues[4]   // zyh_id
                ))
            }
            
            // 如果没找到 queryCourse，使用默认参数
            if (tabParamsList.isEmpty()) {
                val defaultTab = TabParam(
                    indexParams["firstKklxdm"] ?: indexParams["kklxdm"] ?: "10",
                    indexParams["firstXkkzId"] ?: indexParams["xkkz_id"] ?: "",
                    indexParams["njdm_id"] ?: "2024",
                    indexParams["zyh_id"] ?: ""
                )
                if (defaultTab.xkkz_id.isNotEmpty()) {
                    tabParamsList.add(defaultTab)
                }
            }
            
            if (tabParamsList.isEmpty()) {
                retryOrFail("选课参数为空（可能选课未开放）")
                return
            }
            
            broadcastLog("📝 解析到 ${tabParamsList.size} 个选课分类")
            
            // 开始遍历所有分类
            currentTabIndex = 0
            allFetchedCourses.clear()
            fetchNextCategory()
            
        } catch (e: Exception) {
            broadcastLog("❌ 解析参数失败: ${e.message}")
            retryOrFail("解析失败")
        }
    }
    
    // 遍历下一个分类（与 CourseListLogicHelper.fetchNextCategory 一致）
    private fun fetchNextCategory() {
        if (currentTabIndex >= tabParamsList.size) {
            // 所有分类遍历完成，开始匹配
            onAllCategoriesFetched()
            return
        }
        
        val tab = tabParamsList[currentTabIndex]
        currentTabIndex++
        val school = currentSchool ?: return
        
        broadcastLog("📂 获取分类 $currentTabIndex/${tabParamsList.size} (kklxdm=${tab.kklxdm})...")
        
        // 先获取 Display 页面参数
        CourseApiClient.getInstance().fetchCourseDisplayParams(
            school, tab.xkkz_id, tab.kklxdm, tab.njdm_id, tab.zyh_id,
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // 失败则跳过 Display 参数，直接获取课程列表
                    fetchCategoryList(tab)
                }
                
                override fun onResponse(call: Call, response: Response) {
                    val html = response.body?.string() ?: ""
                    // 解析 Display 参数
                    val pattern = """<input[^>]*name="([^"]+)"[^>]*value="([^"]*)"[^>]*>""".toRegex()
                    pattern.findAll(html).forEach { match ->
                        displayParams[match.groupValues[1]] = match.groupValues[2]
                    }
                    fetchCategoryList(tab)
                }
            }
        )
    }
    
    // 获取某个分类的课程列表（与 CourseListLogicHelper.fetchCategoryList 一致）
    // 分页状态变量（与 Web 版 course-fetcher.ts 一致）
    private var currentKspage = 0
    private var currentJspage = 1000
    private var currentTab: TabParam? = null
    private var currentMergedParams = mutableMapOf<String, String>()
    
    private fun fetchCategoryList(tab: TabParam) {
        val school = currentSchool ?: return
        currentTab = tab
        
        // 合并参数（与 CourseListLogicHelper 完全一致）
        currentMergedParams.clear()
        currentMergedParams.putAll(indexParams)
        currentMergedParams.putAll(displayParams)
        
        currentMergedParams["xkkz_id"] = tab.xkkz_id
        currentMergedParams["kklxdm"] = tab.kklxdm
        currentMergedParams["njdm_id"] = tab.njdm_id
        currentMergedParams["zyh_id"] = tab.zyh_id
        
        // rwlx 逻辑（与 CourseListLogicHelper 一致）
        val kklxdm = tab.kklxdm
        if (kklxdm == "01") {
            currentMergedParams["rwlx"] = "1"
        } else if (kklxdm == "10" || kklxdm == "05") {
            currentMergedParams["rwlx"] = "2"
        }
        
        // 初始化分页参数（使用 1000 步长以减少请求次数）
        currentKspage = 0
        currentJspage = 1000
        
        Log.d(TAG, "开始获取分类 ${tab.kklxdm} 的所有页面课程...")
        fetchCategoryPage()
    }
    
    // 获取分类的单页数据（递归调用实现多页获取）
    private fun fetchCategoryPage() {
        val school = currentSchool ?: return
        val tab = currentTab ?: return
        
        // 🔧 构建特定的 40 个参数的 POST body（与 CourseListRoute 和 Web 版一致）
        fun getParam(baseName: String): String {
            currentMergedParams[baseName]?.takeIf { it.isNotEmpty() }?.let { return it }
            for (i in 1..5) {
                currentMergedParams["${baseName}_$i"]?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return ""
        }
        
        val kklxdm = tab.kklxdm
        val rwlx = when (kklxdm) {
            "01" -> "1"
            "10", "05" -> "2"
            else -> currentMergedParams["rwlx"] ?: "1"
        }
        val xklc = when (kklxdm) {
            "01" -> "1"
            "10" -> "4"
            else -> currentMergedParams["xklc"] ?: "2"
        }
        
        val formData = mutableMapOf<String, String>()
        formData["rwlx"] = rwlx
        formData["xkly"] = currentMergedParams["xkly"] ?: "0"
        formData["bklx_id"] = currentMergedParams["bklx_id"] ?: "0"
        formData["sfkkjyxdxnxq"] = currentMergedParams["sfkkjyxdxnxq"] ?: "0"
        formData["xqh_id"] = getParam("xqh_id").ifEmpty { "1" }
        formData["jg_id"] = getParam("jg_id").ifEmpty { "05" }
        formData["zyh_id"] = tab.zyh_id
        formData["zyfx_id"] = getParam("zyfx_id").ifEmpty { "wfx" }
        formData["njdm_id"] = tab.njdm_id
        formData["bh_id"] = getParam("bh_id")
        formData["xz"] = getParam("xz").ifEmpty { "4" }
        formData["mzm"] = getParam("mzm").ifEmpty { "01" }
        formData["xslbdm"] = getParam("xslbdm").ifEmpty { "421" }
        formData["ccdm"] = getParam("ccdm").ifEmpty { "3" }
        formData["xsbj"] = getParam("xsbj").ifEmpty { "0" }
        formData["sfkknj"] = currentMergedParams["sfkknj"] ?: "0"
        formData["sfkkzy"] = currentMergedParams["sfkkzy"] ?: "0"
        formData["kzybkxy"] = currentMergedParams["kzybkxy"] ?: "0"
        formData["sfznkx"] = currentMergedParams["sfznkx"] ?: "0"
        formData["zdkxms"] = currentMergedParams["zdkxms"] ?: "0"
        formData["sfkxq"] = currentMergedParams["sfkxq"] ?: "0"
        formData["sfkcfx"] = currentMergedParams["sfkcfx"] ?: "0"
        formData["kkbk"] = currentMergedParams["kkbk"] ?: "0"
        formData["kkbkdj"] = currentMergedParams["kkbkdj"] ?: "0"
        formData["sfkgbcx"] = currentMergedParams["sfkgbcx"] ?: "0"
        formData["sfrxtjkcxs"] = currentMergedParams["sfrxtjkcxs"] ?: "0"
        formData["tykczgxdcs"] = currentMergedParams["tykczgxdcs"] ?: "0"
        formData["xbm"] = getParam("xbm").ifEmpty { "1" }
        formData["xkxnm"] = getParam("xkxnm").ifEmpty { "2025" }
        formData["xkxqm"] = getParam("xkxqm").ifEmpty { "12" }
        formData["kklxdm"] = kklxdm
        formData["xkkz_id"] = tab.xkkz_id
        formData["kspage"] = currentKspage.toString()
        formData["jspage"] = currentJspage.toString()
        formData["bbhzxjxb"] = currentMergedParams["bbhzxjxb"] ?: "0"
        formData["rlkz"] = currentMergedParams["rlkz"] ?: "0"
        formData["xkzgbj"] = currentMergedParams["xkzgbj"] ?: "0"
        formData["jxbzb"] = currentMergedParams["jxbzb"] ?: ""
        
        val postBody = formData.entries.joinToString("&") { "${it.key}=${it.value}" }
        
        Log.d(TAG, "📄 请求页面: kspage=$currentKspage, jspage=$currentJspage, 参数数量=${formData.size}")
        
        CourseApiClient.getInstance().fetchAvailableCourses(school, postBody, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // 请求失败，停止这个分类的获取，继续下一个分类
                Log.e(TAG, "获取页面失败: ${e.message}")
                fetchNextCategory()
            }
            
            override fun onResponse(call: Call, response: Response) {
                try {
                    val json = response.body?.string() ?: ""
                    val parsed = parseCourseListFromJson(json)
                    
                    if (parsed.isEmpty()) {
                        // 没有更多数据，这个分类获取完成
                        Log.d(TAG, "✅ 分类 ${tab.kklxdm} 页面 kspage=$currentKspage 没有数据，分类获取完成")
                        fetchNextCategory()
                        return
                    }
                    
                    // 补充分类参数
                    parsed.forEach { c ->
                        c.kklxdm = tab.kklxdm
                        c._xkkz_id = tab.xkkz_id
                    }
                    
                    allFetchedCourses.addAll(parsed)
                    Log.d(TAG, "分类 ${tab.kklxdm} 页面 kspage=$currentKspage 获取到 ${parsed.size} 门课程")
                    
                    // 准备下一页参数（与 Web 版一致）
                    // currentKspage = currentJspage + 1
                    // currentJspage += 10
                    currentKspage = currentJspage + 1
                    currentJspage += 1000
                    
                    // 递归获取下一页
                    fetchCategoryPage()
                    
                } catch (e: Exception) {
                    Log.e(TAG, "解析页面失败: ${e.message}")
                    fetchNextCategory()
                }
            }
        })
    }
    
    // 所有分类获取完成后的处理
    private fun onAllCategoriesFetched() {
        if (allFetchedCourses.isEmpty()) {
            broadcastLog("❌ 所有分类课程列表为空，可能选课未开放")
            retryOrFail("课程列表为空")
            return
        }
        
        broadcastLog("✅ 共获取到 ${allFetchedCourses.size} 门课程")
        
        // 🔧 缓存课程列表供智能模式复用
        cachedAllCourses = allFetchedCourses.toList()
        
        // 匹配关键词
        val match = findBestMatch(allFetchedCourses, currentKeywords)
        if (match == null) {
            broadcastLog("❌ 未找到匹配「$currentKeywords」的课程")
            
            // 标记当前课程为失败 - 使用 currentKeywords 作为 courseId
            failCount++
            val failedCourseName = currentKeywords.split(",", "，", " ", ";", "；").firstOrNull() ?: "未知课程"
            broadcastQueueUpdate(failedCourseName, "failed", currentKeywords)
            
            // 检查是否还有更多关键词需要处理
            if (keywordQueue.size > 1 && currentKeywordIndex < keywordQueue.size - 1) {
                broadcastLog("⏭️ 当前课程未找到，切换到下一个关键词...")
                currentKeywordIndex++
                isRunning = false
                
                handler.postDelayed({
                    startSingleKeywordGrabbing(keywordQueue[currentKeywordIndex])
                }, 1000)
            } else {
                // 没有更多关键词
                if (keywordQueue.size > 1) {
                    broadcastLog("🏁 全部关键词处理完成，成功: $multiKeywordTotalSuccess/${keywordQueue.size}")
                }
                stopSelf()
            }
            return
        }
        
        broadcastLog("🎯 匹配到课程: ${match.name} (${match.teacher})")
        
        // 🔧 新增：获取课程详情，匹配具体教学班（按老师/时间）
        fetchCourseDetailsAndMatch(match, currentKeywords, currentSchool!!)
    }
    
    // 🔧 获取课程详情并匹配教学班
    private fun fetchCourseDetailsAndMatch(baseCourse: Course, keywords: String, school: SchoolConfig) {
        // 先解析用户输入的关键词
        val userKeywords = keywords.split(",", "，", " ", ";", "；").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        
        // 🔧 新增：从队列中获取该课程的精确 classId
        val queue = SmartSelector.getInstance().queue
        Log.d(TAG, "📋 队列状态: 共 ${queue.size} 门课程")
        
        // 🔧 修复：只按课程名匹配，支持手动添加的课程（classId 可能为空）
        val queueCourse = queue.find { 
            it.name == baseCourse.name
        }
        val targetClassId = queueCourse?.classId
        val useExactMatch = queueCourse?.useExactMatch == true && !targetClassId.isNullOrEmpty()
        
        if (queueCourse != null) {
            Log.d(TAG, "📌 找到队列课程: ${queueCourse.name}, classId=${queueCourse.classId}, useExactMatch=${queueCourse.useExactMatch}")
        } else {
            Log.d(TAG, "⚠️ 队列中未找到课程: ${baseCourse.name}，将使用智能匹配")
        }
        
        // 🔧 合并关键词：用户输入 + 队列中保存的老师/时间
        val keywordList = mutableListOf<String>()
        // 🔧 所有关键词都转小写，确保匹配成功
        keywordList.addAll(userKeywords.map { it.lowercase() })
        if (!queueCourse?.teacher.isNullOrEmpty()) {
            keywordList.add(queueCourse!!.teacher.lowercase())
            Log.d(TAG, "📌 添加老师关键词: ${queueCourse.teacher}")
        }
        if (!queueCourse?.time.isNullOrEmpty()) {
            keywordList.add(queueCourse!!.time.lowercase())
        }
        Log.d(TAG, "🔍 匹配关键词列表: $keywordList")
        
        if (useExactMatch) {
            broadcastLog("🔒 精确模式: 使用队列中的 classId=$targetClassId")
        }
        
        // 🔧 构建详情请求参数 - 与 fetchSelectionDetails 一致，使用特定的约 40 个参数
        val params = courseParams?.takeIf { it.isNotEmpty() } ?: indexParams.toMap()
        
        fun getParam(baseName: String): String {
            params?.get(baseName)?.takeIf { it.isNotEmpty() }?.let { return it }
            for (i in 1..5) {
                params?.get("${baseName}_$i")?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return ""
        }
        
        val formData = mutableMapOf<String, String>()
        formData["rwlx"] = baseCourse._rwlx?.takeIf { it.isNotEmpty() } ?: params?.get("rwlx") ?: "1"
        formData["xkly"] = params?.get("xkly") ?: "0"
        formData["bklx_id"] = params?.get("bklx_id") ?: "0"
        formData["sfkkjyxdxnxq"] = params?.get("sfkkjyxdxnxq") ?: "0"
        formData["kzkcgs"] = params?.get("kzkcgs") ?: "0"
        formData["xqh_id"] = getParam("xqh_id").ifEmpty { "1" }
        formData["jg_id"] = getParam("jg_id").ifEmpty { "05" }
        formData["zyh_id"] = baseCourse.zyh_id?.takeIf { it.isNotEmpty() } ?: getParam("zyh_id")
        formData["zyfx_id"] = getParam("zyfx_id").ifEmpty { "wfx" }
        formData["txbsfrl"] = params?.get("txbsfrl") ?: "0"
        formData["njdm_id"] = baseCourse.njdm_id?.takeIf { it.isNotEmpty() } ?: params?.get("njdm_id") ?: "2024"
        formData["bh_id"] = getParam("bh_id")
        formData["xbm"] = getParam("xbm").ifEmpty { "1" }
        formData["xslbdm"] = getParam("xslbdm").ifEmpty { "421" }
        formData["mzm"] = getParam("mzm").ifEmpty { "01" }
        formData["xz"] = getParam("xz").ifEmpty { "4" }
        formData["ccdm"] = getParam("ccdm").ifEmpty { "3" }
        formData["xsbj"] = getParam("xsbj").ifEmpty { "0" }
        formData["sfkknj"] = params?.get("sfkknj") ?: "0"
        formData["gnjkxdnj"] = params?.get("gnjkxdnj") ?: "0"
        formData["sfkkzy"] = params?.get("sfkkzy") ?: "0"
        formData["kzybkxy"] = params?.get("kzybkxy") ?: "0"
        formData["sfznkx"] = params?.get("sfznkx") ?: "0"
        formData["zdkxms"] = params?.get("zdkxms") ?: "0"
        formData["sfkxq"] = params?.get("sfkxq") ?: "0"
        formData["sfkcfx"] = params?.get("sfkcfx") ?: "0"
        formData["bbhzxjxb"] = params?.get("bbhzxjxb") ?: "0"
        formData["kkbk"] = params?.get("kkbk") ?: "0"
        formData["kkbkdj"] = params?.get("kkbkdj") ?: "0"
        formData["bklbkcj"] = params?.get("bklbkcj") ?: "0"
        formData["xkxnm"] = getParam("xkxnm").ifEmpty { "2025" }
        formData["xkxqm"] = getParam("xkxqm").ifEmpty { "12" }
        formData["xkxskcgskg"] = params?.get("xkxskcgskg") ?: "0"
        formData["rlkz"] = params?.get("rlkz") ?: "0"
        formData["cdrlkz"] = params?.get("cdrlkz") ?: "0"
        formData["rlzlkz"] = params?.get("rlzlkz") ?: "1"
        formData["kklxdm"] = baseCourse.kklxdm?.takeIf { it.isNotEmpty() } ?: params?.get("kklxdm") ?: "01"
        formData["kch_id"] = baseCourse.courseId ?: ""
        formData["jxbzcxskg"] = params?.get("jxbzcxskg") ?: "0"
        formData["xklc"] = baseCourse._xklc?.takeIf { it.isNotEmpty() } ?: params?.get("xklc") ?: "2"
        formData["xkkz_id"] = baseCourse._xkkz_id ?: ""
        formData["cxbj"] = params?.get("cxbj") ?: "0"
        formData["fxbj"] = params?.get("fxbj") ?: "0"
        
        val detailBody = formData.entries.joinToString("&") { "${it.key}=${it.value}" }
        Log.d(TAG, "详情请求参数数量: ${formData.size}")
        
        broadcastLog("📋 获取课程详情，匹配教学班...")
        
        CourseApiClient.getInstance().fetchCourseSelectionDetails(school, detailBody.toString(), object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                broadcastLog("⚠️ 获取课程详情失败: ${e.message}")
                handleFallback(baseCourse, queueCourse)
            }
            
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                
                try {
                    val classes = JSONArray(body)
                    var matchedClass: Course? = null
                    var bestScore = 0
                    
                    // 🔧 优先级1：精确模式 - 使用队列中保存的 classId 精确匹配
                    if (useExactMatch && targetClassId != null) {
                        for (i in 0 until classes.length()) {
                            val cls = classes.getJSONObject(i)
                            val jxbId = cls.optString("jxb_id", "")
                            val doJxbId = cls.optString("do_jxb_id", "")
                            
                            if (jxbId == targetClassId || doJxbId == targetClassId) {
                                matchedClass = Course().apply {
                                    name = baseCourse.name
                                    courseId = baseCourse.courseId
                                    classId = jxbId
                                    this.doJxbId = doJxbId
                                    teacher = cls.optString("jsxm", "")
                                    time = cls.optString("sksj", "")
                                    _rwlx = baseCourse._rwlx
                                    _xklc = baseCourse._xklc
                                    _xkkz_id = baseCourse._xkkz_id
                                    rlkz = cls.optString("rlkz", "0")
                                    rlzlkz = cls.optString("rlzlkz", "1")
                                    sxbj = cls.optString("sxbj", "0")
                                    xxkbj = cls.optString("xxkbj", "0")
                                }
                                broadcastLog("✅ 精确匹配成功: ${matchedClass.teacher} | classId=$jxbId")
                                break
                            }
                        }
                        
                        if (matchedClass != null) {
                            useMatchedCourseAndGrab(matchedClass)
                            return
                        } else {
                            broadcastLog("⚠️ 精确匹配失败，回退到智能匹配")
                        }
                    }
                    
                    // 🔧 优先级2：智能模式 - 按关键词匹配老师/时间
                    for (i in 0 until classes.length()) {
                        val cls = classes.getJSONObject(i)
                        val teacher = cls.optString("jsxm", "").lowercase()
                        val time = cls.optString("sksj", "").lowercase()
                        
                        var score = 0
                        for (keyword in keywordList) {
                            // 🔧 老师名直接匹配
                            if (teacher.contains(keyword)) {
                                score += 10
                            }
                            // 🔧 时间智能匹配：处理「周一」和「星期一」等变体
                            val normalizedKeyword = normalizeTimeKeyword(keyword)
                            val normalizedTime = normalizeTimeKeyword(time)
                            if (normalizedTime.contains(normalizedKeyword) || time.contains(keyword)) {
                                score += 1
                            }
                        }
                        
                        // 如果没有指定关键词，或者匹配到了关键词
                        if (keywordList.isEmpty() || score > 0) {
                            if (score > bestScore) {  // 🔧 用严格大于，防止后者覆盖
                                bestScore = score
                                matchedClass = Course().apply {
                                    name = baseCourse.name
                                    courseId = baseCourse.courseId
                                    classId = cls.optString("jxb_id", "")
                                    doJxbId = cls.optString("do_jxb_id", "")
                                    this.teacher = cls.optString("jsxm", "")
                                    this.time = cls.optString("sksj", "")
                                    _rwlx = baseCourse._rwlx
                                    _xklc = baseCourse._xklc
                                    _xkkz_id = baseCourse._xkkz_id
                                    rlkz = cls.optString("rlkz", "0")
                                    rlzlkz = cls.optString("rlzlkz", "1")
                                    sxbj = cls.optString("sxbj", "0")
                                    xxkbj = cls.optString("xxkbj", "0")
                                    // 🔧 智能模式匹配成功后，设置为精确模式以便后续直接用 classId 选课
                                    this.useExactMatch = true
                                }
                            }

                        }
                    }
                    
                    if (matchedClass != null) {
                        broadcastLog("✅ 智能匹配成功: ${matchedClass.teacher} | ${matchedClass.time}")
                        useMatchedCourseAndGrab(matchedClass)
                    } else {
                        broadcastLog("⚠️ 未找到匹配的教学班，随机尝试...")
                        handleFallback(baseCourse, queueCourse)
                    }
                    
                } catch (e: Exception) {
                    broadcastLog("⚠️ 解析课程详情失败: ${e.message}")
                    handleFallback(baseCourse, queueCourse)
                }
            }
        })
    }
    
    // 处理回退逻辑：优先使用队列中的 Course 信息
    private fun handleFallback(baseCourse: Course, queueCourse: Course?) {
        if (queueCourse != null && queueCourse.useExactMatch && !queueCourse.classId.isNullOrEmpty()) {
            broadcastLog("⚠️ 使用队列中的精确配置进行强制抢课: classId=${queueCourse.classId}")
            val fallbackCourse = Course().apply {
                name = baseCourse.name
                courseId = baseCourse.courseId
                classId = queueCourse.classId
                doJxbId = queueCourse.doJxbId
                teacher = queueCourse.teacher
                time = queueCourse.time
                // 确保复制所有必要字段
                _rwlx = baseCourse._rwlx
                _xklc = baseCourse._xklc
                _xkkz_id = baseCourse._xkkz_id
                
                // 尝试从 queueCourse 恢复其他参数，如果不存在则使用默认值
                rlkz = queueCourse.rlkz ?: "0"
                rlzlkz = queueCourse.rlzlkz ?: "1"
                sxbj = queueCourse.sxbj ?: "0"
                xxkbj = queueCourse.xxkbj ?: "0"
            }
            useMatchedCourseAndGrab(fallbackCourse)
        } else {
            // 🔧 智能模式：确保不走精确模式
            baseCourse.useExactMatch = false
            useMatchedCourseAndGrab(baseCourse)
        }
    }
    
    // 使用匹配到的课程开始抢课
    private fun useMatchedCourseAndGrab(match: Course) {
        SmartSelector.getInstance().init(this@GrabService)
        SmartSelector.getInstance().targetCourse = match
        targetCourse = match
        
        // 🔧 关键修复：同步更新队列中对应课程的 classId 和 doJxbId
        val queue = SmartSelector.getInstance().queue
        for (c in queue) {
            if (c.name == match.name) {
                c.classId = match.classId
                c.doJxbId = match.doJxbId
                c.useExactMatch = match.useExactMatch
                c.rlkz = match.rlkz
                c.rlzlkz = match.rlzlkz
                c.sxbj = match.sxbj
                c.xxkbj = match.xxkbj
                Log.d(TAG, "📝 已更新队列课程: ${c.name}, classId=${c.classId}, doJxbId=${c.doJxbId?.length}字符")
                break
            }
        }
        SmartSelector.getInstance().saveCourseQueue()  // 保存更新后的队列
        
        // 保存 courseParams 供抢课使用
        courseParams = indexParams.toMap()
        
        updateNotification("正在抢课: ${match.name}")
        startGrabbing()
    }
    
    // 解析课程列表 JSON（与 CourseParser 一致）
    private fun parseCourseListFromJson(json: String): List<Course> {
        val courses = mutableListOf<Course>()
        try {
            // 尝试解析为对象（带 tmpList 字段）
            val arr = try {
                val obj = org.json.JSONObject(json)
                if (obj.has("tmpList")) {
                    obj.getJSONArray("tmpList")
                } else {
                    JSONArray(json)
                }
            } catch (e: Exception) {
                JSONArray(json)
            }
            
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val course = Course()
                course.courseId = obj.optString("kch_id", "")
                course.classId = obj.optString("jxb_id", "")
                course.name = obj.optString("kcmc", "")
                course.teacher = obj.optString("jsxm", obj.optString("xm", ""))
                course.doJxbId = obj.optString("do_jxb_id", "")
                course._rwlx = obj.optString("rwlx", "1")
                course._xklc = obj.optString("xklc", "2")
                course._xkkz_id = obj.optString("xkkz_id", "")
                course.njdm_id = obj.optString("njdm_id", "")
                course.zyh_id = obj.optString("zyh_id", "")
                course.kklxdm = obj.optString("kklxdm", "")
                course.jcxx_id = obj.optString("jcxx_id", "")
                courses.add(course)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析课程列表失败: ${e.message}")
        }
        return courses
    }
    
    private fun findBestMatch(courses: List<Course>, keywords: String): Course? {
        if (keywords.isBlank()) return courses.firstOrNull()
        
        val keywordList = keywords.split(",", "，", " ").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        
        var bestMatch: Course? = null
        var bestScore = 0
        
        for (course in courses) {
            val name = course.name?.lowercase() ?: ""
            val teacher = course.teacher?.lowercase() ?: ""
            
            var score = 0
            for (keyword in keywordList) {
                if (name.contains(keyword)) score += 10
                if (teacher.contains(keyword)) score += 5
            }
            
            if (score > bestScore) {
                bestScore = score
                bestMatch = course
            }
        }
        
        return if (bestScore > 0) bestMatch else null
    }
    
    /**
     * 规范化时间关键词，处理常见格式差异
     * 例如：「周一 1-2节」→「星期一1-2」
     */
    private fun normalizeTimeKeyword(input: String): String {
        return input
            .replace("周一", "星期一")
            .replace("周二", "星期二")
            .replace("周三", "星期三")
            .replace("周四", "星期四")
            .replace("周五", "星期五")
            .replace("周六", "星期六")
            .replace("周日", "星期日")
            .replace("周天", "星期日")
            .replace("第", "")
            .replace("节", "")
            .replace(" ", "")
            .replace("{", "")
            .replace("}", "")
    }

    // ============ 模糊匹配捡漏模式 ============

    /**
     * 获取隐藏参数并启动模糊匹配轮询
     */
    private fun fetchHiddenParamsAndStartFuzzyMatch(school: SchoolConfig) {
        broadcastLog("📌 正在获取选课参数...")
        
        Thread {
            val hiddenHtml = CourseApiClient.getInstance().fetchPageHiddenParamsSync(school)
            val hiddenParams = parseHiddenParams(hiddenHtml ?: "")
            
            Log.d(TAG, "模糊匹配: 获取隐藏参数完成，共 ${hiddenParams.size} 个")
            
            // 合并到 courseParams
            if (hiddenParams.isNotEmpty()) {
                val merged = (courseParams ?: emptyMap()).toMutableMap()
                hiddenParams.forEach { (key, value) ->
                    if (value.isNotEmpty() && merged[key].isNullOrEmpty()) {
                        merged[key] = value
                    }
                }
                courseParams = merged
            }
            
            handler.post {
                broadcastLog("✅ 参数就绪 (共 ${courseParams?.size ?: 0} 个)，开始轮询...")
                startFuzzyMatchPolling()
            }
        }.start()
    }

    /**
     * 启动模糊匹配轮询
     * 持续获取目标课程类别的详情，比较人数变化
     */
    private fun startFuzzyMatchPolling() {
        if (!isRunning || !isFuzzyMatchMode) return
        
        val school = currentSchool ?: return
        val targetCourseId = SmartSelector.getInstance().fuzzyMatchCourseId ?: return
        val targetCourseName = SmartSelector.getInstance().fuzzyMatchCourseName ?: "未知课程"
        
        fuzzyMatchPollingCount++
        
        // 达到最大轮询次数时停止
        if (fuzzyMatchPollingCount > maxRetry) {
            broadcastLog("⏹ 模糊匹配已达最大轮询次数 ($maxRetry)，停止监控")
            stopGrabbing()
            return
        }
        
        // 更新通知
        if (fuzzyMatchPollingCount % 10 == 0) {
            updateNotification("模糊匹配: 已轮询 $fuzzyMatchPollingCount 次")
            broadcastLog("📊 轮询中... ($fuzzyMatchPollingCount/$maxRetry)")
        }
        
        // 构建详情请求参数
        val postBody = buildFuzzyMatchDetailsBody(targetCourseId)
        
        CourseApiClient.getInstance().fetchCourseSelectionDetails(school, postBody, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "模糊匹配获取详情失败: ${e.message}")
                // 失败后继续轮询
                handler.postDelayed({ startFuzzyMatchPolling() }, interval.toLong())
            }
            
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                
                // 🔧 调试日志：输出响应内容（前100字符）
                Log.d(TAG, "模糊匹配响应: ${body.take(200)}")
                
                // 🔧 检查是否返回 "0"（课程不可查询）
                if (body.trim() == "0" || body.trim() == "null") {
                    Log.w(TAG, "模糊匹配: 服务器返回 '$body'，课程可能不在当前选课批次内")
                    broadcastLog("⚠️ 无法获取课程详情，可能不在当前选课批次")
                    // 继续轮询，但间隔稍长
                    handler.postDelayed({ startFuzzyMatchPolling() }, interval.toLong() * 2)
                    return
                }
                
                try {
                    val classes = JSONArray(body)
                    
                    // 🔧 如果是第一次，输出日志
                    if (fuzzyMatchPollingCount == 1) {
                        broadcastLog("📋 发现 ${classes.length()} 个教学班")
                    }
                    
                    var foundVacancy = false
                    var vacancyCourse: Course? = null
                    
                    for (i in 0 until classes.length()) {
                        val item = classes.getJSONObject(i)
                        val classId = item.optString("jxb_id", "")
                        val doJxbId = item.optString("do_jxb_id", "")
                        val currentSelected = item.optInt("yxzrs", 0)
                        val capacity = item.optInt("jxbrl", 0)
                        val teacher = item.optString("jsxm", "")
                        val time = item.optString("sksj", "")
                        
                        if (classId.isEmpty()) continue
                        
                        // 检查人数变化
                        val hasVacancy = SmartSelector.getInstance()
                            .updateSnapshotAndCheckChange(classId, currentSelected, capacity)
                        
                        if (hasVacancy && !foundVacancy) {
                            foundVacancy = true
                            // 构建课程对象用于抢课
                            vacancyCourse = Course().apply {
                                name = targetCourseName
                                courseId = targetCourseId
                                this.classId = classId
                                this.doJxbId = doJxbId
                                this.teacher = teacher
                                this.time = time
                                this.capacity = capacity
                                this.selected = currentSelected
                            }
                            
                            broadcastLog("🎯 检测到 $targetCourseName [$teacher] 有人退课!")
                            broadcastLog("   人数变化，剩余 ${capacity - currentSelected} 个名额，立即抢课!")
                        }
                    }
                    
                    if (foundVacancy && vacancyCourse != null) {
                        // 发现空位，立即抢课
                        isFuzzyMatchMode = false // 暂停轮询
                        targetCourse = vacancyCourse
                        executeFuzzyMatchSelection(school, vacancyCourse)
                    } else {
                        // 继续轮询
                        handler.postDelayed({ startFuzzyMatchPolling() }, interval.toLong())
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "模糊匹配解析失败: ${e.message}")
                    // 继续轮询
                    handler.postDelayed({ startFuzzyMatchPolling() }, interval.toLong())
                }
            }
        })
    }
    
    /**
     * 构建模糊匹配详情请求体（完整参数，参考 fetchSelectionDetails）
     */
    private fun buildFuzzyMatchDetailsBody(courseId: String): String {
        val xkkzId = SmartSelector.getInstance().fuzzyMatchXkkzId ?: courseParams?.get("xkkz_id") ?: ""
        val njdm_id = courseParams?.get("njdm_id") ?: "2024"
        val zyh_id = courseParams?.get("zyh_id") ?: ""
        // 🔧 优先使用保存的 kklxdm（课程类型代码）
        val kklxdm = SmartSelector.getInstance().fuzzyMatchKklxdm ?: courseParams?.get("kklxdm") ?: "01"
        val rwlx = courseParams?.get("rwlx") ?: "1"
        val xklc = courseParams?.get("xklc") ?: "2"
        
        // 辅助函数：从 courseParams 获取参数（支持后缀）
        fun getParam(baseName: String): String {
            courseParams?.get(baseName)?.takeIf { it.isNotEmpty() }?.let { return it }
            for (i in 1..5) {
                courseParams?.get("${baseName}_$i")?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return ""
        }
        
        val formData = mutableMapOf<String, String>()
        formData["rwlx"] = rwlx
        formData["xkly"] = courseParams?.get("xkly") ?: "0"
        formData["bklx_id"] = courseParams?.get("bklx_id") ?: "0"
        formData["sfkkjyxdxnxq"] = courseParams?.get("sfkkjyxdxnxq") ?: "0"
        formData["kzkcgs"] = courseParams?.get("kzkcgs") ?: "0"
        formData["xqh_id"] = getParam("xqh_id").ifEmpty { "1" }
        formData["jg_id"] = getParam("jg_id").ifEmpty { "05" }
        formData["zyh_id"] = zyh_id
        formData["zyfx_id"] = getParam("zyfx_id").ifEmpty { "wfx" }
        formData["txbsfrl"] = courseParams?.get("txbsfrl") ?: "0"
        formData["njdm_id"] = njdm_id
        formData["bh_id"] = getParam("bh_id")
        formData["xbm"] = getParam("xbm").ifEmpty { "1" }
        formData["xslbdm"] = getParam("xslbdm").ifEmpty { "421" }
        formData["mzm"] = getParam("mzm").ifEmpty { "01" }
        formData["xz"] = getParam("xz").ifEmpty { "4" }
        formData["ccdm"] = getParam("ccdm").ifEmpty { "3" }
        formData["xsbj"] = getParam("xsbj").ifEmpty { "0" }
        formData["sfkknj"] = courseParams?.get("sfkknj") ?: "0"
        formData["gnjkxdnj"] = courseParams?.get("gnjkxdnj") ?: "0"
        formData["sfkkzy"] = courseParams?.get("sfkkzy") ?: "0"
        formData["kzybkxy"] = courseParams?.get("kzybkxy") ?: "0"
        formData["sfznkx"] = courseParams?.get("sfznkx") ?: "0"
        formData["zdkxms"] = courseParams?.get("zdkxms") ?: "0"
        formData["sfkxq"] = courseParams?.get("sfkxq") ?: "0"
        formData["sfkcfx"] = courseParams?.get("sfkcfx") ?: "0"
        formData["bbhzxjxb"] = courseParams?.get("bbhzxjxb") ?: "0"
        formData["kkbk"] = courseParams?.get("kkbk") ?: "0"
        formData["kkbkdj"] = courseParams?.get("kkbkdj") ?: "0"
        formData["bklbkcj"] = courseParams?.get("bklbkcj") ?: "0"
        formData["xkxnm"] = getParam("xkxnm").ifEmpty { "2025" }
        formData["xkxqm"] = getParam("xkxqm").ifEmpty { "12" }
        formData["xkxskcgskg"] = courseParams?.get("xkxskcgskg") ?: "0"
        formData["rlkz"] = courseParams?.get("rlkz") ?: "0"
        formData["cdrlkz"] = courseParams?.get("cdrlkz") ?: "0"
        formData["rlzlkz"] = courseParams?.get("rlzlkz") ?: "1"
        formData["kklxdm"] = kklxdm
        formData["kch_id"] = courseId
        formData["jxbzcxskg"] = courseParams?.get("jxbzcxskg") ?: "0"
        formData["xklc"] = xklc
        formData["xkkz_id"] = xkkzId
        formData["cxbj"] = courseParams?.get("cxbj") ?: "0"
        formData["fxbj"] = courseParams?.get("fxbj") ?: "0"
        
        val body = formData.entries.joinToString("&") { "${it.key}=${it.value}" }
        Log.d(TAG, "模糊匹配详情请求参数数量: ${formData.size}")
        return body
    }
    
    /**
     * 执行模糊匹配抢课
     */
    private fun executeFuzzyMatchSelection(school: SchoolConfig, course: Course) {
        broadcastLog("🚀 开始抢课: ${course.name} [${course.teacher}]")
        successCount = 0
        failCount = 0
        retryCount = 0
        
        // 使用现有的选课流程
        targetCourse = course
        // 🔧 注意：不要覆盖 courseParams，保持之前获取的隐藏参数
        
        // 直接尝试选课
        executeDirectSelection(school, course)
    }
    
    /**
     * 直接发送选课请求（供模糊匹配模式使用）
     */
    private fun executeDirectSelection(school: SchoolConfig, course: Course) {
        if (!isRunning) return
        
        retryCount++
        if (retryCount > 10) { // 模糊匹配抢课最多尝试10次
            broadcastLog("⚠️ 抢课失败次数过多，恢复监控")
            isFuzzyMatchMode = true
            // 🔧 恢复监控时重新获取隐藏参数
            handler.postDelayed({ fetchHiddenParamsAndStartFuzzyMatch(school) }, interval.toLong())
            return
        }
        
        val postBody = StringBuilder()
        
        // 添加基础参数
        courseParams?.forEach { (key, value) ->
            if (postBody.isNotEmpty()) postBody.append("&")
            postBody.append("$key=$value")
        }
        
        // 添加课程参数
        if (postBody.isNotEmpty()) postBody.append("&")
        val jxbIds = course.doJxbId?.takeIf { it.isNotEmpty() } ?: course.classId
        postBody.append("jxb_ids=$jxbIds")
        postBody.append("&kch_id=${course.courseId}")
        postBody.append("&kcmc=${course.name ?: ""}")
        postBody.append("&rwlx=1")
        postBody.append("&rlkz=0")
        postBody.append("&rlzlkz=1")
        postBody.append("&sxbj=0")
        postBody.append("&xxkbj=0")
        postBody.append("&qz=0")
        
        CourseApiClient.getInstance().selectCourse(school, postBody.toString(), object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                failCount++
                broadcastLog("⚠️ 请求失败: ${e.message} [$retryCount/10]")
                handler.postDelayed({ executeDirectSelection(school, course) }, 500)
            }
            
            override fun onResponse(call: Call, response: Response) {
                val html = response.body?.string() ?: ""
                
                try {
                    var success = false
                    var msg = "选课失败"
                    
                    // 尝试解析 JSON
                    try {
                        val json = org.json.JSONObject(html)
                        val flag = json.optString("flag", "")
                        if (flag == "1") {
                            success = true
                            msg = "选课成功"
                        } else {
                            msg = json.optString("msg", json.optString("message", "选课失败"))
                        }
                    } catch (e: Exception) {
                        // 非 JSON 响应
                        if (html.contains("成功") || html.contains("选课成功")) {
                            success = true
                            msg = "选课成功"
                        } else if (html.contains("人数已满")) {
                            msg = "人数已满，继续重试"
                        }
                    }
                    
                    if (success) {
                        successCount++
                        broadcastLog("🎉 模糊匹配抢课成功: ${course.name}")
                        updateNotification("抢课成功: ${course.name}")
                        
                        // 清除模糊匹配目标，停止服务
                        SmartSelector.getInstance().clearFuzzyMatchTarget()
                        stopGrabbing()
                    } else {
                        failCount++
                        broadcastLog("❌ $msg [$retryCount/10]")
                        handler.postDelayed({ executeDirectSelection(school, course) }, 500)
                    }
                } catch (e: Exception) {
                    failCount++
                    Log.e(TAG, "解析选课响应失败: ${e.message}")
                    handler.postDelayed({ executeDirectSelection(school, course) }, 500)
                }
            }
        })
    }
}


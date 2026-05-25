package com.tyust.course.fragment

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.tyust.course.manager.SmartSelector
import com.tyust.course.manager.UserManager
import com.tyust.course.model.Course
import com.tyust.course.model.SchoolConfig
import com.tyust.course.service.GrabService
import com.tyust.course.ui.screen.GrabProScreen
import com.tyust.course.ui.theme.CourseSelectorTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class GrabProFragment : Fragment(), SmartSelector.OnStatusUpdateListener {

    companion object {
        private const val PREFS_NAME = "grab_pro_prefs"
        private const val KEY_KEYWORDS = "course_keywords"
        private const val KEY_DATETIME = "scheduled_datetime"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_MAX_RETRY = "max_retry"
        private const val KEY_SCHEDULED_MODE = "scheduled_mode"
        private const val KEY_LOG = "log_text"
        private const val KEY_HAS_TASK = "has_scheduled_task"
        private const val KEY_TASK_INFO = "scheduled_task_info"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    
    // Retry mechanism for scheduled grabbing
    private var fetchRetryCount = 0
    private val maxFetchRetry = 10 // Max retry attempts
    private val fetchRetryDelay = 3000L // 3 seconds between retries
    
    // UI State
    private var isRunning by mutableStateOf(false)
    private var successCount by mutableIntStateOf(0)
    private var failCount by mutableIntStateOf(0)
    private var retryCount by mutableIntStateOf(0)
    private var targetCourseName by mutableStateOf<String?>(null)
    private var targetCourseTeacher by mutableStateOf<String?>(null)
    private var logText by mutableStateOf("")
    private var interval by mutableStateOf("1500")
    private var maxRetry by mutableStateOf("100")
    
    // Scheduled grab state
    private var courseKeywords by mutableStateOf("")
    private var scheduledDateTime by mutableStateOf("")
    private var isScheduledMode by mutableStateOf(false)
    private var hasScheduledTask by mutableStateOf(false)
    private var scheduledTaskInfo by mutableStateOf("")
    private var scheduledHandler: Handler? = null
    private var scheduledRunnable: Runnable? = null
    
    // Broadcast receiver for service updates
    private val grabUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == GrabService.BROADCAST_UPDATE) {
                val logMessage = intent.getStringExtra(GrabService.EXTRA_LOG_MESSAGE) ?: ""
                successCount = intent.getIntExtra(GrabService.EXTRA_SUCCESS_COUNT, 0)
                failCount = intent.getIntExtra(GrabService.EXTRA_FAIL_COUNT, 0)
                retryCount = intent.getIntExtra(GrabService.EXTRA_RETRY_COUNT, 0)
                isRunning = intent.getBooleanExtra(GrabService.EXTRA_IS_RUNNING, false)
                
                if (logMessage.isNotEmpty()) {
                    appendLog(logMessage)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                CourseSelectorTheme {
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
                        onClearLog = { clearLog() },
                        // Scheduled grab parameters
                        schoolName = UserManager.getInstance().currentSchool?.name ?: "",
                        courseKeywords = courseKeywords,
                        onCourseKeywordsChange = { courseKeywords = it },
                        scheduledDateTime = scheduledDateTime,
                        isScheduledMode = isScheduledMode,
                        onScheduledModeChange = { isScheduledMode = it },
                        onScheduledStart = { createScheduledTask() },
                        hasScheduledTask = hasScheduledTask,
                        scheduledTaskInfo = scheduledTaskInfo,
                        onCancelScheduledTask = { cancelScheduledTask() },
                        onPickDateTime = { showDateTimePicker() }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        restoreState()
        SmartSelector.getInstance().setListener(this)
        updateTargetCourse()
    }

    override fun onResume() {
        super.onResume()
        // Register broadcast receiver
        val filter = IntentFilter(GrabService.BROADCAST_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(grabUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(grabUpdateReceiver, filter)
        }
        updateUI()
        updateTargetCourse()
    }

    override fun onPause() {
        super.onPause()
        saveState()
        try {
            requireContext().unregisterReceiver(grabUpdateReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }

    private fun updateUI() {
        isRunning = SmartSelector.getInstance().isRunning
    }

    private fun updateTargetCourse() {
        val course = SmartSelector.getInstance().targetCourse
        targetCourseName = course?.name
        targetCourseTeacher = course?.teacher
    }

    private fun startGrabbing() {
        val targetCourse = SmartSelector.getInstance().targetCourse
        if (targetCourse == null) {
            if (isAdded && context != null) {
                Toast.makeText(context, "请先在\"课程\"页面长按选择要抢的课程", Toast.LENGTH_LONG).show()
            }
            return
        }

        val school = UserManager.getInstance().currentSchool
        if (school == null) {
            if (isAdded && context != null) {
                Toast.makeText(context, "请先登录", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Reset counters
        successCount = 0
        failCount = 0
        retryCount = 0

        // Apply settings to SmartSelector (for service to access)
        try {
            SmartSelector.getInstance().setInterval(interval.toIntOrNull() ?: 1500)
        } catch (e: Exception) { }

        try {
            SmartSelector.getInstance().setMaxRetry(maxRetry.toIntOrNull() ?: 100)
        } catch (e: Exception) { }

        // Start foreground service for background execution
        val serviceIntent = Intent(requireContext(), GrabService::class.java).apply {
            action = GrabService.ACTION_START
            putExtra(GrabService.EXTRA_COURSE_NAME, targetCourse.name)
            putExtra(GrabService.EXTRA_COURSE_ID, targetCourse.courseId)
            putExtra(GrabService.EXTRA_INTERVAL, interval.toIntOrNull() ?: 1500)
            putExtra(GrabService.EXTRA_MAX_RETRY, maxRetry.toIntOrNull() ?: 100)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(serviceIntent)
        } else {
            requireContext().startService(serviceIntent)
        }

        appendLog("🚀 开始后台抢课: ${targetCourse.name}")
        isRunning = true
        
        if (isAdded && context != null) {
            Toast.makeText(context, "后台抢课已启动，可切换到其他应用", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopGrabbing() {
        // Stop foreground service
        val serviceIntent = Intent(requireContext(), GrabService::class.java).apply {
            action = GrabService.ACTION_STOP
        }
        requireContext().startService(serviceIntent)
        
        // Also stop SmartSelector
        SmartSelector.getInstance().stop()
        
        appendLog("⏹ 已停止抢课")
        isRunning = false
    }

    private fun clearLog() {
        logText = ""
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logLine = "[$timestamp] $message\n"
        logText += logLine
    }

    // SmartSelector.OnStatusUpdateListener callbacks (for foreground mode fallback)
    override fun onUpdate(message: String) {
        retryCount++
        if (message.contains("失败")) {
            failCount++
        }
        appendLog(message)
        handler.post { updateUI() }
    }

    override fun onSuccess(courseName: String) {
        successCount++
        appendLog("✅ 抢课成功: $courseName")
        handler.post {
            updateUI()
            Toast.makeText(context, "🎉 抢课成功: $courseName", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onQueueProgress(current: Int, total: Int, courseName: String) {
        appendLog("📋 队列进度: $current/$total - $courseName")
    }

    private fun createScheduledTask() {
        if (courseKeywords.isBlank()) {
            Toast.makeText(context, "请输入课程关键词", Toast.LENGTH_SHORT).show()
            return
        }
        if (scheduledDateTime.isBlank()) {
            Toast.makeText(context, "请输入开始时间", Toast.LENGTH_SHORT).show()
            return
        }

        // Parse the datetime (format: 2025/12/18 15:38)
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val targetTime: Date
        try {
            targetTime = dateFormat.parse(scheduledDateTime) ?: throw Exception("日期格式错误")
        } catch (e: Exception) {
            Toast.makeText(context, "日期格式错误，请使用: 2025/12/18 15:38", Toast.LENGTH_LONG).show()
            return
        }

        val now = Date()
        val delayMs = targetTime.time - now.time

        if (delayMs <= 0) {
            if (isAdded && context != null) {
                Toast.makeText(context, "开始时间必须在当前时间之后", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Max 24 hours
        if (delayMs > 24 * 60 * 60 * 1000) {
            if (isAdded && context != null) {
                Toast.makeText(context, "定时时间不能超过24小时", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Cancel any existing scheduled task
        cancelScheduledTask()

        // Create new scheduled task
        scheduledHandler = Handler(Looper.getMainLooper())
        scheduledRunnable = Runnable {
            appendLog("⏰ 定时任务开始执行...")
            hasScheduledTask = false
            scheduledTaskInfo = ""
            startScheduledGrabbing()
        }

        scheduledHandler?.postDelayed(scheduledRunnable!!, delayMs)

        hasScheduledTask = true
        scheduledTaskInfo = "关键词: $courseKeywords\n开始时间: $scheduledDateTime\n剩余等待: ${delayMs / 1000 / 60}分钟"
        
        appendLog("📅 定时任务已创建: $scheduledDateTime")
        appendLog("🔍 关键词: $courseKeywords")
        if (isAdded && context != null) {
            Toast.makeText(context, "定时任务已创建", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelScheduledTask() {
        scheduledRunnable?.let { scheduledHandler?.removeCallbacks(it) }
        scheduledHandler = null
        scheduledRunnable = null
        hasScheduledTask = false
        scheduledTaskInfo = ""
        appendLog("❌ 定时任务已取消")
    }

    private fun startScheduledGrabbing() {
        val school = UserManager.getInstance().currentSchool
        if (school == null) {
            appendLog("❌ 错误: 未登录")
            if (isAdded && context != null) {
                Toast.makeText(context, "请先登录", Toast.LENGTH_SHORT).show()
            }
            return
        }

        appendLog("🔍 开始搜索匹配课程...")
        appendLog("📝 关键词: $courseKeywords")

        // Parse keywords (comma or space separated)
        val keywords = courseKeywords.split(",", "，", " ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (keywords.isEmpty()) {
            appendLog("❌ 错误: 关键词为空")
            return
        }

        // Reset retry counter and start three-step fetch (like Web version)
        fetchRetryCount = 0
        startThreeStepFetch(school, keywords)
    }

    // ============ 三步获取流程 (与Web版一致) ============
    
    // 存储从各步骤提取的参数
    private var indexParams = mutableMapOf<String, String>()
    private var displayParams = mutableMapOf<String, String>()

    private fun startThreeStepFetch(school: SchoolConfig, keywords: List<String>) {
        fetchRetryCount++
        appendLog("📡 Step 1/3: 获取Index页面参数... (尝试 $fetchRetryCount/$maxFetchRetry)")
        
        // Step 1: Fetch Index page
        com.tyust.course.network.CourseApiClient.getInstance().fetchCourseParams(
            school,
            object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    handler.post {
                        appendLog("⚠️ Step 1 失败: ${e.message}")
                        retryThreeStepFetch(school, keywords)
                    }
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val html = response.body?.string() ?: ""
                    handler.post {
                        parseIndexParams(html)
                        if (indexParams.isEmpty()) {
                            appendLog("⚠️ Step 1: 未提取到参数")
                            retryThreeStepFetch(school, keywords)
                        } else {
                            appendLog("✅ Step 1 完成: 提取到 ${indexParams.size} 个参数")
                            fetchDisplayPage(school, keywords)
                        }
                    }
                }
            }
        )
    }

    private fun fetchDisplayPage(school: SchoolConfig, keywords: List<String>) {
        appendLog("📡 Step 2/3: 获取Display页面参数...")
        
        // Use tabParamsList if available, otherwise use firstXkkzId
        if (tabParamsList.isEmpty()) {
            val xkkz_id = indexParams["firstXkkzId"] ?: indexParams["xkkz_id"] ?: ""
            val kklxdm = indexParams["firstKklxdm"] ?: indexParams["kklxdm"] ?: "10"
            val njdm_id = indexParams["njdm_id"] ?: "2024"
            val zyh_id = indexParams["zyh_id"] ?: ""
            tabParamsList.add(TabParam(kklxdm, xkkz_id, njdm_id, zyh_id))
        }
        
        // Start fetching courses from all categories
        allCourses.clear()
        currentTabIndex = 0
        fetchNextCategory(school, keywords)
    }
    
    private var allCourses = mutableListOf<Course>()
    private var currentTabIndex = 0

    private fun fetchNextCategory(school: SchoolConfig, keywords: List<String>) {
        if (currentTabIndex >= tabParamsList.size) {
            // All categories fetched
            if (allCourses.isEmpty()) {
                appendLog("⚠️ 所有类别均无课程，可能选课未开放")
                retryThreeStepFetch(school, keywords)
            } else {
                appendLog("✅ Step 3 完成: 共获取到 ${allCourses.size} 门课程 (${tabParamsList.size}个类别)")
                matchAndSelectCourse(allCourses, keywords)
            }
            return
        }
        
        val tab = tabParamsList[currentTabIndex]
        currentTabIndex++
        
        appendLog("📡 获取类别 $currentTabIndex/${tabParamsList.size}: kklxdm=${tab.kklxdm}")
        
        // First fetch Display page for this category to get sfkxq, xkxskcgskg
        com.tyust.course.network.CourseApiClient.getInstance().fetchCourseDisplayParams(
            school, tab.xkkz_id, tab.kklxdm, tab.njdm_id, tab.zyh_id,
            object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    handler.post {
                        appendLog("  ⚠️ Display失败，尝试继续")
                        fetchCategoryList(school, keywords, tab)
                    }
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val html = response.body?.string() ?: ""
                    handler.post {
                        parseDisplayParams(html)
                        fetchCategoryList(school, keywords, tab)
                    }
                }
            }
        )
    }
    
    private fun fetchCategoryList(school: SchoolConfig, keywords: List<String>, tab: TabParam) {
        // Build complete postBody from merged params
        val mergedParams = mutableMapOf<String, String>()
        mergedParams.putAll(indexParams)
        mergedParams.putAll(displayParams)
        
        // Override with tab-specific params
        mergedParams["xkkz_id"] = tab.xkkz_id
        mergedParams["kklxdm"] = tab.kklxdm
        mergedParams["njdm_id"] = tab.njdm_id
        mergedParams["zyh_id"] = tab.zyh_id
        
        // Add required defaults
        mergedParams.putIfAbsent("rwlx", "1")
        mergedParams.putIfAbsent("xklc", "2")
        mergedParams.putIfAbsent("kspage", "1")
        mergedParams.putIfAbsent("jspage", "0")
        mergedParams.putIfAbsent("jxbzb", "")
        
        val postBody = mergedParams.entries.joinToString("&") { "${it.key}=${it.value}" }
        
        com.tyust.course.network.CourseApiClient.getInstance().fetchAvailableCourses(
            school, 
            postBody,
            object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    handler.post {
                        appendLog("  ⚠️ 类别获取失败: ${e.message}")
                        fetchNextCategory(school, keywords)
                    }
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val json = response.body?.string() ?: ""
                    handler.post {
                        val courses = parseCourseList(json)
                        if (courses.isNotEmpty()) {
                            // Save display params to each course
                            courses.forEach { course ->
                                course._xkkz_id = tab.xkkz_id
                                course.kklxdm = tab.kklxdm
                                course.njdm_id = tab.njdm_id
                                course.zyh_id = tab.zyh_id
                                course._rwlx = mergedParams["rwlx"] ?: "1"
                                course._xklc = mergedParams["xklc"] ?: "2"
                                course._sfkxq = displayParams["sfkxq"] ?: ""
                                course._xkxskcgskg = displayParams["xkxskcgskg"] ?: ""
                            }
                            allCourses.addAll(courses)
                            appendLog("  ✅ 获取到 ${courses.size} 门课程")
                        }
                        fetchNextCategory(school, keywords)
                    }
                }
            }
        )
    }

    private fun parseIndexParams(html: String) {
        indexParams.clear()
        tabParamsList.clear()
        try {
            // Extract hidden input values using regex (like Web version's cheerio parsing)
            val pattern = """<input[^>]*name="([^"]+)"[^>]*value="([^"]*)"[^>]*>""".toRegex()
            pattern.findAll(html).forEach { match ->
                val name = match.groupValues[1]
                val value = match.groupValues[2]
                indexParams[name] = value
            }
            
            // Also try reverse pattern (value before name)
            val pattern2 = """<input[^>]*value="([^"]*)"[^>]*name="([^"]+)"[^>]*>""".toRegex()
            pattern2.findAll(html).forEach { match ->
                val value = match.groupValues[1]
                val name = match.groupValues[2]
                if (!indexParams.containsKey(name)) {
                    indexParams[name] = value
                }
            }
            
            // Extract tabParams from queryCourse onclick (like Web version)
            // Example: onclick="queryCourse(this,'10','xkkz_id_value','2024','180101')"
            val queryCoursePattern = """queryCourse\s*\(\s*this\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*\)""".toRegex()
            queryCoursePattern.findAll(html).forEach { match ->
                val kklxdm = match.groupValues[1]
                val xkkz_id = match.groupValues[2]
                val njdm_id = match.groupValues[3]
                val zyh_id = match.groupValues[4]
                tabParamsList.add(TabParam(kklxdm, xkkz_id, njdm_id, zyh_id))
            }
            
            if (tabParamsList.isNotEmpty()) {
                appendLog("  → 找到 ${tabParamsList.size} 个课程类别")
            }
            
            // Log key params
            val rwlx = indexParams["rwlx"] ?: "未找到"
            val xklc = indexParams["xklc"] ?: "未找到"
            appendLog("  → rwlx=$rwlx, xklc=$xklc")
            
        } catch (e: Exception) {
            appendLog("⚠️ 解析Index页面异常: ${e.message}")
        }
    }
    
    // Data class for tab parameters
    data class TabParam(val kklxdm: String, val xkkz_id: String, val njdm_id: String, val zyh_id: String)
    private var tabParamsList = mutableListOf<TabParam>()

    private fun parseDisplayParams(html: String) {
        displayParams.clear()
        try {
            // Extract hidden input values
            val pattern = """<input[^>]*name="([^"]+)"[^>]*value="([^"]*)"[^>]*>""".toRegex()
            pattern.findAll(html).forEach { match ->
                val name = match.groupValues[1]
                val value = match.groupValues[2]
                displayParams[name] = value
            }
            
            val pattern2 = """<input[^>]*value="([^"]*)"[^>]*name="([^"]+)"[^>]*>""".toRegex()
            pattern2.findAll(html).forEach { match ->
                val value = match.groupValues[1]
                val name = match.groupValues[2]
                if (!displayParams.containsKey(name)) {
                    displayParams[name] = value
                }
            }
            
            // Log key params (sfkxq, xkxskcgskg are critical)
            val sfkxq = displayParams["sfkxq"] ?: "未找到"
            val xkxskcgskg = displayParams["xkxskcgskg"] ?: "未找到"
            appendLog("  → sfkxq=$sfkxq, xkxskcgskg=$xkxskcgskg")
            
        } catch (e: Exception) {
            appendLog("⚠️ 解析Display页面异常: ${e.message}")
        }
    }

    private fun retryThreeStepFetch(school: SchoolConfig, keywords: List<String>) {
        if (fetchRetryCount >= maxFetchRetry) {
            appendLog("❌ 已达最大重试次数 ($maxFetchRetry)")
            appendLog("💡 尝试使用已选课程...")
            fallbackToSelectedCourse()
            return
        }

        val delay = minOf(fetchRetryDelay * (1 + fetchRetryCount / 10), 30000L)
        appendLog("⏳ ${delay / 1000}秒后重试...")
        
        handler.postDelayed({
            if (isAdded) {
                startThreeStepFetch(school, keywords)
            }
        }, delay)
    }

    private fun parseCourseList(json: String): List<Course> {
        val courses = mutableListOf<Course>()
        try {
            // The response format is {"tmpList": [...], "sfxsjc": "1"}
            // Try parsing as object first
            val jsonObj = org.json.JSONObject(json)
            val array = jsonObj.optJSONArray("tmpList")
            
            if (array != null && array.length() > 0) {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val course = Course()
                    course.name = obj.optString("kcmc", "")
                    course.courseId = obj.optString("kch_id", "")
                    course.classId = obj.optString("jxb_id", "")
                    course.teacher = obj.optString("jsxm", obj.optString("jsxx", ""))
                    course._xkkz_id = obj.optString("xkkz_id", "")
                    // Also get do_jxb_id for course selection
                    course.doJxbId = obj.optString("do_jxb_id", "")
                    course.kklxdm = obj.optString("kklxdm", "")
                    courses.add(course)
                }
            } else {
                appendLog("⚠️ tmpList 为空或不存在")
            }
        } catch (e: org.json.JSONException) {
            // Maybe it's a direct array?
            try {
                val array = org.json.JSONArray(json)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val course = Course()
                    course.name = obj.optString("kcmc", "")
                    course.courseId = obj.optString("kch_id", "")
                    course.classId = obj.optString("jxb_id", "")
                    course.teacher = obj.optString("jsxm", "")
                    course._xkkz_id = obj.optString("xkkz_id", "")
                    course.doJxbId = obj.optString("do_jxb_id", "")
                    courses.add(course)
                }
            } catch (e2: Exception) {
                appendLog("⚠️ 解析课程列表异常: ${e2.message}")
            }
        } catch (e: Exception) {
            appendLog("⚠️ 解析课程列表异常: ${e.message}")
        }
        return courses
    }

    private fun matchAndSelectCourse(courses: List<Course>, keywords: List<String>) {
        appendLog("🔍 开始匹配课程...")

        // Calculate similarity for each course
        val scoredCourses = courses.mapNotNull { course ->
            val name = course.name ?: ""
            if (name.isEmpty()) return@mapNotNull null

            var maxScore = 0.0
            for (keyword in keywords) {
                val score = calculateSimilarity(name, keyword)
                if (score > maxScore) maxScore = score
            }
            Pair(course, maxScore)
        }.sortedByDescending { it.second }

        if (scoredCourses.isEmpty()) {
            appendLog("❌ 没有找到匹配的课程")
            fallbackToSelectedCourse()
            return
        }

        // Log top matches
        appendLog("📊 匹配结果 (前3名):")
        scoredCourses.take(3).forEachIndexed { index, (course, score) ->
            appendLog("  ${index + 1}. ${course.name} (匹配度: ${String.format("%.1f", score * 100)}%)")
        }

        // Select best match
        val bestMatch = scoredCourses.first()
        if (bestMatch.second < 0.3) {
            appendLog("⚠️ 最高匹配度仅 ${String.format("%.1f", bestMatch.second * 100)}%，可能不准确")
        }

        val course = bestMatch.first
        appendLog("✅ 选择课程: ${course.name}")
        appendLog("👨‍🏫 教师: ${course.teacher ?: "未知"}")

        // Set as target and start grabbing
        SmartSelector.getInstance().setTargetCourse(course)
        targetCourseName = course.name
        targetCourseTeacher = course.teacher

        startGrabbing()
    }

    private fun calculateSimilarity(text: String, keyword: String): Double {
        val t = text.lowercase()
        val k = keyword.lowercase()

        // Exact match
        if (t.contains(k)) return 1.0

        // Partial match - count matching characters
        var matches = 0
        var ki = 0
        for (c in t) {
            if (ki < k.length && c == k[ki]) {
                matches++
                ki++
            }
        }

        return matches.toDouble() / k.length.coerceAtLeast(1)
    }

    private fun fallbackToSelectedCourse() {
        val targetCourse = SmartSelector.getInstance().targetCourse
        if (targetCourse != null) {
            appendLog("📚 使用已选课程: ${targetCourse.name}")
            startGrabbing()
        } else {
            appendLog("❌ 无法找到匹配课程，请手动选择")
            if (isAdded && context != null) {
                Toast.makeText(context, "请先在课程页面长按选择目标课程", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDateTimePicker() {
        val calendar = Calendar.getInstance()
        
        // Use theme for better visibility
        val datePickerDialog = DatePickerDialog(
            requireContext(),
            android.R.style.Theme_Material_Light_Dialog_Alert,
            { _, year, month, dayOfMonth ->
                // Then show time picker
                val timePickerDialog = TimePickerDialog(
                    requireContext(),
                    android.R.style.Theme_Material_Light_Dialog_Alert,
                    { _, hourOfDay, minute ->
                        val formattedDate = String.format("%04d/%02d/%02d %02d:%02d", 
                            year, month + 1, dayOfMonth, hourOfDay, minute)
                        scheduledDateTime = formattedDate
                        appendLog("📅 已选择时间: $formattedDate")
                        saveState()
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true
                )
                timePickerDialog.setTitle("选择时间")
                timePickerDialog.show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        datePickerDialog.setTitle("选择日期")
        datePickerDialog.show()
    }

    private fun saveState() {
        if (!::prefs.isInitialized) return
        prefs.edit().apply {
            putString(KEY_KEYWORDS, courseKeywords)
            putString(KEY_DATETIME, scheduledDateTime)
            putString(KEY_INTERVAL, interval)
            putString(KEY_MAX_RETRY, maxRetry)
            putBoolean(KEY_SCHEDULED_MODE, isScheduledMode)
            putString(KEY_LOG, logText)
            putBoolean(KEY_HAS_TASK, hasScheduledTask)
            putString(KEY_TASK_INFO, scheduledTaskInfo)
            apply()
        }
    }

    private fun restoreState() {
        if (!::prefs.isInitialized) return
        courseKeywords = prefs.getString(KEY_KEYWORDS, "") ?: ""
        scheduledDateTime = prefs.getString(KEY_DATETIME, "") ?: ""
        interval = prefs.getString(KEY_INTERVAL, "1500") ?: "1500"
        maxRetry = prefs.getString(KEY_MAX_RETRY, "100") ?: "100"
        isScheduledMode = prefs.getBoolean(KEY_SCHEDULED_MODE, false)
        logText = prefs.getString(KEY_LOG, "") ?: ""
        
        // Restore scheduled task
        val hadTask = prefs.getBoolean(KEY_HAS_TASK, false)
        scheduledTaskInfo = prefs.getString(KEY_TASK_INFO, "") ?: ""
        
        if (hadTask && scheduledDateTime.isNotEmpty()) {
            // Try to reschedule the task
            rescheduleTask()
        }
    }

    private fun rescheduleTask() {
        try {
            val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            val targetTime = dateFormat.parse(scheduledDateTime) ?: return
            val now = Date()
            val delayMs = targetTime.time - now.time

            if (delayMs > 0) {
                // Task is still in the future, reschedule it
                scheduledHandler = Handler(Looper.getMainLooper())
                scheduledRunnable = Runnable {
                    appendLog("⏰ 定时任务开始执行...")
                    hasScheduledTask = false
                    scheduledTaskInfo = ""
                    saveState()
                    startScheduledGrabbing()
                }
                scheduledHandler?.postDelayed(scheduledRunnable!!, delayMs)
                
                hasScheduledTask = true
                scheduledTaskInfo = "关键词: $courseKeywords\n开始时间: $scheduledDateTime\n剩余等待: ${delayMs / 1000 / 60}分钟"
                appendLog("✅ 定时任务已恢复: $scheduledDateTime")
            } else {
                // Task time has passed
                hasScheduledTask = false
                scheduledTaskInfo = ""
                appendLog("⚠️ 定时任务时间已过，无法恢复")
            }
        } catch (e: Exception) {
            hasScheduledTask = false
            scheduledTaskInfo = ""
            appendLog("⚠️ 恢复定时任务失败: ${e.message}")
        }
    }
}

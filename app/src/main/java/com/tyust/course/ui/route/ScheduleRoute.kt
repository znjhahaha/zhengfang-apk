package com.tyust.course.ui.route

import android.widget.Toast
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
import com.tyust.course.manager.ScheduleSettingsManager
import com.tyust.course.manager.UserManager
import com.tyust.course.network.CourseApiClient
import com.tyust.course.ui.screen.PeriodTimeUi
import com.tyust.course.ui.screen.ScheduleCourseUi
import com.tyust.course.ui.screen.ScheduleScreen
import com.tyust.course.ui.screen.ScheduleSettingsScreen
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.theme.MotionDuration
import com.tyust.course.ui.theme.MotionEasing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar
import com.tyust.course.utils.ICalExporter

@Composable
fun ScheduleRoute() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // State
    var currentWeek by remember { mutableIntStateOf(1) }
    var courses by remember { mutableStateOf<List<ScheduleCourseUi>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var periodTimes by remember { mutableStateOf<List<PeriodTimeUi>>(emptyList()) }
    var periodCount by remember { mutableIntStateOf(12) }
    var isNextSemester by remember { mutableStateOf(false) }
    
    // Dialog State
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showCourseDetail by remember { mutableStateOf<ScheduleCourseUi?>(null) }
    
    // Managers
    val settingsManager = remember { ScheduleSettingsManager.getInstance().apply { init(context) } }
    
    // Colors
    val courseColors = remember {
        listOf(
            Color(0xFF5C6BC0), Color(0xFF42A5F5), Color(0xFF66BB6A), Color(0xFFFFA726),
            Color(0xFFAB47BC), Color(0xFFEF5350), Color(0xFF26C6DA), Color(0xFF8D6E63)
        )
    }

    // Load Settings & Cache
    fun loadScheduleFromCache(key: String): String? {
        return try {
            val prefs = context.getSharedPreferences("schedule_cache", android.content.Context.MODE_PRIVATE)
            prefs.getString(key, null)
        } catch (e: Exception) { null }
    }
    
    fun saveScheduleToCache(key: String, json: String) {
        try {
            val prefs = context.getSharedPreferences("schedule_cache", android.content.Context.MODE_PRIVATE)
            prefs.edit().putString(key, json).putLong("${key}_time", System.currentTimeMillis()).apply()
        } catch (e: Exception) { }
    }

    fun parseSchedule(json: String): List<ScheduleCourseUi> {
         // Copy parsing logic from Fragment
         val list = mutableListOf<ScheduleCourseUi>()
         try {
            val obj = JSONObject(json)
            var kbList = obj.optJSONArray("kbList")
            if (kbList == null && obj.has("data")) {
                val data = obj.get("data")
                if (data is JSONObject) kbList = data.optJSONArray("kbList")
                else if (data is JSONArray) kbList = data
            }
            if (kbList == null) return emptyList()

            for (i in 0 until kbList.length()) {
                val item = kbList.getJSONObject(i)
                var name = item.optString("kcmc", "").ifEmpty { item.optString("KCMC", "未知课程") }
                val teacher = item.optString("xm", item.optString("XM", ""))
                
                val campus = item.optString("xqmc", item.optString("cdxqmc", ""))
                val building = item.optString("cdlmc", item.optString("jxlmc", ""))
                val room = item.optString("cdmc", item.optString("CDMC", ""))
                val jxcd = item.optString("jxcdmc", item.optString("JXCDMC", ""))
                
                val location = when {
                    campus.isNotEmpty() && room.isNotEmpty() -> "$campus $room"
                    building.isNotEmpty() && room.isNotEmpty() -> "$building $room"
                    room.isNotEmpty() -> room
                    jxcd.isNotEmpty() -> jxcd
                    else -> ""
                }

                val day = item.optInt("xqj", item.optInt("XQJ", 1))
                val weeks = item.optString("zcd", item.optString("ZCD", "1-16周"))
                var jcs = item.optString("jcs", item.optString("JCS", "")).ifEmpty { item.optString("jcor", item.optString("JCOR", "1-2")) }
                
                var startPeriod = 1
                var endPeriod = 2
                val parts = jcs.replace(",", "-").split("-")
                if (parts.isNotEmpty()) {
                    try {
                        startPeriod = parts[0].trim().toInt()
                        endPeriod = if (parts.size > 1) parts.last().trim().toInt() else startPeriod
                    } catch (e: Exception) {}
                }

                list.add(ScheduleCourseUi(
                    name = name, teacher = teacher, location = location, day = day,
                    startPeriod = startPeriod, endPeriod = endPeriod, weeks = weeks,
                    color = courseColors[i % courseColors.size]
                ))
            }
        } catch (e: Exception) {}
        return list
    }

    fun reloadCustomCourses(currentList: List<ScheduleCourseUi>): List<ScheduleCourseUi> {
        val customCourses = settingsManager.getCustomCourses()
        val customUi = customCourses.map { cc ->
            ScheduleCourseUi(
                name = cc.name, teacher = cc.teacher, location = cc.location, day = cc.day,
                startPeriod = cc.startPeriod, endPeriod = cc.endPeriod, weeks = cc.weeks,
                color = Color(0xFF9C27B0), isCustom = true, customId = cc.id
            )
        }
        val nonCustom = currentList.filter { !it.isCustom }
        return nonCustom + customUi
    }

    // Load Schedule Function
    val loadSchedule = remember(isNextSemester) {
        fun(forceRefresh: Boolean) {
            val school = UserManager.getInstance().currentSchool
            if (school == null) return
            
            val calendar = Calendar.getInstance()
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH)
            var xnm = if (month >= 7) year.toString() else (year - 1).toString()
            var xqm = if (month >= 7 || month <= 0) "3" else "12"
            
            // 智能判断下一学期
            if (isNextSemester) {
                if (xqm == "3") {
                    xqm = "12" // 当前是第一学期，下学期是第二学期
                } else {
                    xqm = "3" // 当前是第二学期，下学期是下一年第一学期
                    xnm = (xnm.toInt() + 1).toString()
                }
            }
            
            val cacheKey = "schedule_${school.id}_${xnm}_${xqm}"

            if (!forceRefresh) {
                // Try cache
                loadScheduleFromCache(cacheKey)?.let { json ->
                    val cached = parseSchedule(json)
                    if (cached.isNotEmpty()) {
                        courses = reloadCustomCourses(cached)
                    }
                }
            }

            isLoading = true
            CourseApiClient.getInstance().fetchSchedule(school, "xnm=$xnm&xqm=$xqm", object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    scope.launch(Dispatchers.Main) {
                        isLoading = false
                        if (courses.isEmpty()) Toast.makeText(context, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val json = response.body?.string() ?: ""
                    if (json.contains("用户登录")) {
                         scope.launch(Dispatchers.Main) { 
                             isLoading = false
                             Toast.makeText(context, "请先登录", Toast.LENGTH_SHORT).show() 
                         }
                        return
                    }
                    val parsed = parseSchedule(json)
                    scope.launch(Dispatchers.Main) {
                        isLoading = false
                        if (parsed.isNotEmpty()) {
                            saveScheduleToCache(cacheKey, json)
                            courses = reloadCustomCourses(parsed)
                            if (forceRefresh) Toast.makeText(context, "已刷新", Toast.LENGTH_SHORT).show()
                        } else if (courses.isEmpty()) {
                            Toast.makeText(context, "暂无课程", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })
        }
    }

    // Init Effect
    LaunchedEffect(Unit) {
        periodCount = settingsManager.periodCount
        periodTimes = settingsManager.getPeriodTimes().map { PeriodTimeUi(it.period, it.startTime, it.endTime) }
        val calcWeek = settingsManager.calculateCurrentWeek()
        if (calcWeek > 0) currentWeek = calcWeek
    }
    
    // 监听学期切换并重新加载
    LaunchedEffect(isNextSemester) {
        loadSchedule(false)
    }

    // Refresh custom courses when dialogs close
    ScheduleScreen(
        currentWeek = currentWeek,
        courses = courses,
        isLoading = isLoading,
        periodTimes = periodTimes,
        periodCount = periodCount,
        onWeekChange = { currentWeek = it },
        onCourseClick = { showCourseDetail = it },
        onSettingsClick = { showSettingsDialog = true },
        onExportClick = {
            if (courses.isEmpty()) {
                Toast.makeText(context, "课表为空，无法导出", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    val semesterStart = settingsManager.getSemesterStartCalendar()
                    ICalExporter.exportAndShare(
                        context = context,
                        courses = courses,
                        semesterStartDate = semesterStart,
                        totalWeeks = 20
                    )
                    Toast.makeText(context, "课表已导出，可导入到系统日历中查看", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        },
        isNextSemester = isNextSemester,
        onToggleSemester = { isNextSemester = !isNextSemester }
    )
    
    // 设置弹窗（使用滑入动画）
    if (showSettingsDialog) {
        var animateTrigger by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { animateTrigger = true }

        fun dismiss() {
            animateTrigger = false
        }

        if (!animateTrigger) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(300)
                showSettingsDialog = false
            }
        }

        Dialog(
            onDismissRequest = { dismiss() },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            AnimatedVisibility(
                visible = animateTrigger,
                enter = slideInVertically(
                    initialOffsetY = { it / 4 },
                    animationSpec = tween(MotionDuration.DialogEnter, easing = MotionEasing.FastOutSlowIn)
                ) + fadeIn(animationSpec = tween(MotionDuration.Medium)),
                exit = slideOutVertically(
                    targetOffsetY = { it / 4 },
                    animationSpec = tween(MotionDuration.Medium, easing = MotionEasing.Accelerate)
                ) + fadeOut(animationSpec = tween(MotionDuration.Medium))
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    ScheduleSettingsScreen(
                        manager = settingsManager,
                        onClose = {
                            periodCount = settingsManager.periodCount
                            periodTimes = settingsManager.getPeriodTimes().map { PeriodTimeUi(it.period, it.startTime, it.endTime) }
                            val w = settingsManager.calculateCurrentWeek()
                            if (w > 0) currentWeek = w
                            
                            dismiss()
                        },
                        onShowDatePicker = {
                             (context as? FragmentActivity)?.supportFragmentManager?.let { fm ->
                                 val selection = if (settingsManager.semesterStartDate > 0) settingsManager.semesterStartDate else com.google.android.material.datepicker.MaterialDatePicker.todayInUtcMilliseconds()
                                 com.google.android.material.datepicker.MaterialDatePicker.Builder.datePicker()
                                     .setTitleText("选择第一周周一日期")
                                     .setSelection(selection)
                                     .build().apply {
                                         addOnPositiveButtonClickListener { 
                                             settingsManager.semesterStartDate = it 
                                         }
                                     }.show(fm, "date_picker")
                             }
                        }
                    )
                }
            }
        }
    }
    
    // 课程详情弹窗（统一设计语言）
    if (showCourseDetail != null) {
        val course = showCourseDetail!!
        var animateTrigger by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { animateTrigger = true }

        fun dismiss() {
            animateTrigger = false
        }

        if (!animateTrigger) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(300)
                showCourseDetail = null
            }
        }

        Dialog(onDismissRequest = { dismiss() }) {
            AnimatedVisibility(
                visible = animateTrigger,
                enter = slideInVertically(
                    initialOffsetY = { it / 3 },
                    animationSpec = tween(MotionDuration.DialogEnter, easing = MotionEasing.FastOutSlowIn)
                ) + fadeIn(animationSpec = tween(MotionDuration.Medium)),
                exit = slideOutVertically(
                    targetOffsetY = { it / 3 },
                    animationSpec = tween(MotionDuration.Medium, easing = MotionEasing.Accelerate)
                ) + fadeOut(animationSpec = tween(MotionDuration.Medium))
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 课程名
                        Text(
                            text = course.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // 信息行
                        CourseInfoRow(
                            icon = Icons.Default.AccessTime,
                            label = "时间",
                            value = "${course.weeks} 周${course.day} 第${course.startPeriod}-${course.endPeriod}节"
                        )
                        CourseInfoRow(
                            icon = Icons.Default.Place,
                            label = "教室",
                            value = course.location.ifEmpty { "未指定" }
                        )
                        CourseInfoRow(
                            icon = Icons.Default.Person,
                            label = "教师",
                            value = course.teacher.ifEmpty { "未指定" }
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        SystemPrimaryButton(
                            text = "确定",
                            onClick = { dismiss() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseInfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

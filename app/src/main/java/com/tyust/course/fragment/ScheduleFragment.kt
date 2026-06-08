package com.tyust.course.fragment

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.tyust.course.manager.ScheduleSettingsManager
import com.tyust.course.manager.UserManager
import com.tyust.course.network.CourseApiClient
import com.tyust.course.ui.screen.PeriodTimeUi
import com.tyust.course.ui.screen.ScheduleCourseUi
import com.tyust.course.ui.screen.ScheduleScreen
import com.tyust.course.ui.theme.CourseSelectorTheme
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar

class ScheduleFragment : Fragment() {

    private var currentWeek by mutableStateOf(1)
    private var courses by mutableStateOf<List<ScheduleCourseUi>>(emptyList())
    private var isLoading by mutableStateOf(false)
    private var periodTimes by mutableStateOf<List<PeriodTimeUi>>(emptyList())
    private var periodCount by mutableStateOf(12)
    private var showSettingsDialog by mutableStateOf(false)

    private val courseColors = listOf(
        Color(0xFF5C6BC0), // Indigo
        Color(0xFF42A5F5), // Blue
        Color(0xFF66BB6A), // Green
        Color(0xFFFFA726), // Orange
        Color(0xFFAB47BC), // Purple
        Color(0xFFEF5350), // Red
        Color(0xFF26C6DA), // Cyan
        Color(0xFF8D6E63)  // Brown
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Initialize settings manager
        ScheduleSettingsManager.getInstance().init(requireContext())
        loadSettings()
        
        return ComposeView(requireContext()).apply {
            setContent {
                CourseSelectorTheme {
                    ScheduleScreen(
                        currentWeek = currentWeek,
                        courses = courses,
                        isLoading = isLoading,
                        periodTimes = periodTimes,
                        periodCount = periodCount,
                        onWeekChange = { newWeek -> currentWeek = newWeek },
                        onCourseClick = { course ->
                            showCourseDetails(course)
                        },
                        onSettingsClick = {
                            showSettingsActivity()
                        }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSchedule()
    }
    
    override fun onResume() {
        super.onResume()
        // Reload settings in case they changed
        loadSettings()
        // Reload custom courses
        reloadCustomCourses()
    }
    
    private fun loadSettings() {
        val manager = ScheduleSettingsManager.getInstance()
        periodCount = manager.periodCount
        periodTimes = manager.getPeriodTimes().map { 
            PeriodTimeUi(it.period, it.startTime, it.endTime) 
        }
        // Auto calculate current week
        val calculatedWeek = manager.calculateCurrentWeek()
        if (calculatedWeek > 0 && manager.semesterStartDate > 0) {
            currentWeek = calculatedWeek
        }
    }
    
    private fun reloadCustomCourses() {
        val customCourses = ScheduleSettingsManager.getInstance().getCustomCourses()
        val customUi = customCourses.mapIndexed { index, cc ->
            ScheduleCourseUi(
                name = cc.name,
                teacher = cc.teacher,
                location = cc.location,
                day = cc.day,
                startPeriod = cc.startPeriod,
                endPeriod = cc.endPeriod,
                weeks = cc.weeks,
                color = Color(0xFF9C27B0), // Purple for custom courses
                isCustom = true,
                customId = cc.id
            )
        }
        // Merge with existing courses (remove old custom, add new)
        val nonCustom = courses.filter { !it.isCustom }
        courses = nonCustom + customUi
    }
    
    private fun showSettingsActivity() {
        // Show settings dialog
        val dialog = ScheduleSettingsDialog()
        dialog.show(parentFragmentManager, "settings")
    }

    private fun loadSchedule(forceRefresh: Boolean = false) {
        val school = UserManager.getInstance().currentSchool
        if (school == null) {
            Toast.makeText(context, "请先登录", Toast.LENGTH_SHORT).show()
            return
        }

        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)

        // Aug-Jan -> xqm=3, Feb-Jul -> xqm=12
        val xnm = if (month >= 7) year.toString() else (year - 1).toString()
        val xqm = if (month >= 7 || month <= 0) "3" else "12"
        
        val accountKey = UserManager.getInstance().currentAccountStorageKey
        val cacheKey = "schedule_${accountKey}_${school.id}_${xnm}_${xqm}"

        // 如果不是强制刷新，先尝试从缓存加载
        if (!forceRefresh) {
            val cachedJson = loadScheduleFromCache(cacheKey)
            if (cachedJson != null) {
                val cachedCourses = parseSchedule(cachedJson)
                if (cachedCourses.isNotEmpty()) {
                    courses = cachedCourses
                    reloadCustomCourses()
                    Log.d("ScheduleFragment", "从缓存加载了 ${cachedCourses.size} 门课程")
                    // 继续在后台获取最新数据
                }
            }
        }

        isLoading = true

        val postBody = "xnm=$xnm&xqm=$xqm"

        CourseApiClient.getInstance().fetchSchedule(school, postBody, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread {
                    isLoading = false
                    // 如果缓存有数据就不显示错误
                    if (courses.isEmpty()) {
                        Toast.makeText(context, "加载课表失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "网络请求失败，显示缓存数据", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string() ?: ""
                
                // 检测 Cookie 是否过期（返回登录页面）
                val isLoginPage = json.contains("用户登录") ||
                        json.contains("登 录") ||
                        json.contains("统一身份认证") ||
                        json.contains("请先登录") ||
                        json.contains("<!DOCTYPE html") && json.contains("login")
                
                if (isLoginPage) {
                    // Cookie 已过期，跳转到登录页面
                    activity?.runOnUiThread {
                        isLoading = false
                        Toast.makeText(context, "登录状态已过期，请重新登录", Toast.LENGTH_SHORT).show()
                        
                        // 清除登录状态
                        UserManager.getInstance().clearLoginState()
                        
                        // 跳转到登录页面
                        val intent = android.content.Intent(activity, com.tyust.course.LoginActivity::class.java)
                        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                    }
                    return
                }
                
                val parsedCourses = parseSchedule(json)
                
                activity?.runOnUiThread {
                    isLoading = false
                    if (parsedCourses.isNotEmpty()) {
                        // 保存到缓存
                        saveScheduleToCache(cacheKey, json)
                        courses = parsedCourses
                        reloadCustomCourses()
                        if (forceRefresh) {
                            Toast.makeText(context, "已刷新 ${parsedCourses.size} 门课程", Toast.LENGTH_SHORT).show()
                        } else {
                            Log.d("ScheduleFragment", "已从服务器加载 ${parsedCourses.size} 门课程并缓存")
                        }
                    } else if (courses.isEmpty()) {
                        Toast.makeText(context, "本学期暂无课程", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }


    // 保存课表到本地缓存
    private fun saveScheduleToCache(key: String, json: String) {
        try {
            val prefs = requireContext().getSharedPreferences("schedule_cache", android.content.Context.MODE_PRIVATE)
            prefs.edit()
                .putString(key, json)
                .putLong("${key}_time", System.currentTimeMillis())
                .apply()
            Log.d("ScheduleFragment", "课表已缓存: $key")
        } catch (e: Exception) {
            Log.e("ScheduleFragment", "保存缓存失败: ${e.message}")
        }
    }

    // 从本地缓存加载课表
    private fun loadScheduleFromCache(key: String): String? {
        return try {
            val prefs = requireContext().getSharedPreferences("schedule_cache", android.content.Context.MODE_PRIVATE)
            prefs.getString(key, null)
        } catch (e: Exception) {
            Log.e("ScheduleFragment", "加载缓存失败: ${e.message}")
            null
        }
    }

    private fun parseSchedule(json: String): List<ScheduleCourseUi> {
        val list = mutableListOf<ScheduleCourseUi>()
        try {
            val obj = JSONObject(json)
            var kbList = obj.optJSONArray("kbList")

            if (kbList == null && obj.has("data")) {
                val data = obj.get("data")
                if (data is JSONObject) {
                    kbList = data.optJSONArray("kbList")
                } else if (data is JSONArray) {
                    kbList = data
                }
            }

            if (kbList == null) return emptyList()

            for (i in 0 until kbList.length()) {
                val item = kbList.getJSONObject(i)
                
                var name = item.optString("kcmc", "")
                if (name.isEmpty()) name = item.optString("KCMC", "未知课程")

                val teacher = item.optString("xm", item.optString("XM", ""))
                
                // 获取详细教室信息
                val campus = item.optString("xqmc", item.optString("cdxqmc", "")) // 校区名
                val building = item.optString("cdlmc", item.optString("jxlmc", "")) // 楼名
                val room = item.optString("cdmc", item.optString("CDMC", "")) // 教室名
                val jxcd = item.optString("jxcdmc", item.optString("JXCDMC", "")) // 教学场地
                
                // 组合详细地址
                var location = when {
                    campus.isNotEmpty() && room.isNotEmpty() -> "$campus $room"
                    building.isNotEmpty() && room.isNotEmpty() -> "$building $room"
                    room.isNotEmpty() -> room
                    jxcd.isNotEmpty() -> jxcd
                    else -> ""
                }

                val day = item.optInt("xqj", item.optInt("XQJ", 1))
                val weeks = item.optString("zcd", item.optString("ZCD", "1-16周"))

                var jcs = item.optString("jcs", item.optString("JCS", ""))
                if (jcs.isEmpty()) jcs = item.optString("jcor", item.optString("JCOR", "1-2"))

                var startPeriod = 1
                var endPeriod = 2
                
                val parts = jcs.replace(",", "-").split("-")
                if (parts.isNotEmpty()) {
                    try {
                        startPeriod = parts[0].trim().toInt()
                        endPeriod = if (parts.size > 1) parts.last().trim().toInt() else startPeriod
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                val color = courseColors[i % courseColors.size]

                list.add(
                    ScheduleCourseUi(
                        name = name,
                        teacher = teacher,
                        location = location,
                        day = day,
                        startPeriod = startPeriod,
                        endPeriod = endPeriod,
                        weeks = weeks,
                        color = color
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("ScheduleFragment", "Parse error: ${e.message}")
        }
        return list
    }

    private fun showCourseDetails(course: ScheduleCourseUi) {
        val weekDay = when(course.day) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            7 -> "周日"
            else -> "周${course.day}"
        }
        
        val dialog = android.app.Dialog(requireContext())
        dialog.setContentView(
            android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(android.graphics.Color.WHITE)
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                minimumWidth = (280 * resources.displayMetrics.density).toInt()
                
                // Title
                addView(android.widget.TextView(context).apply {
                    text = course.name
                    textSize = 18f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.parseColor("#333333"))
                })
                
                // Divider
                addView(android.view.View(context).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#E0E0E0"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        (1 * resources.displayMetrics.density).toInt()
                    ).apply {
                        topMargin = (12 * resources.displayMetrics.density).toInt()
                        bottomMargin = (12 * resources.displayMetrics.density).toInt()
                    }
                })
                
                // Info items
                fun addInfoItem(label: String, value: String) {
                    addView(android.widget.TextView(context).apply {
                        text = "$label：$value"
                        textSize = 15f
                        setTextColor(android.graphics.Color.parseColor("#555555"))
                        val itemPadding = (4 * resources.displayMetrics.density).toInt()
                        setPadding(0, itemPadding, 0, itemPadding)
                    })
                }
                
                addInfoItem("时间", "${course.weeks} $weekDay 第${course.startPeriod}-${course.endPeriod}节")
                addInfoItem("教室", course.location.ifEmpty { "未指定" })
                addInfoItem("教师", course.teacher.ifEmpty { "未指定" })
                
                // Spacer
                addView(android.view.View(context).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        (16 * resources.displayMetrics.density).toInt()
                    )
                })
                
                // OK Button
                addView(android.widget.Button(context).apply {
                    text = "确定"
                    setBackgroundColor(android.graphics.Color.parseColor("#7C4DFF"))
                    setTextColor(android.graphics.Color.WHITE)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setOnClickListener { dialog.dismiss() }
                })
            }
        )
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()
    }
}

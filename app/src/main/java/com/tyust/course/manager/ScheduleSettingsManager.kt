package com.tyust.course.manager

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 课表设置管理器 - 管理节次时间、第一周日期、自定义课程
 */
class ScheduleSettingsManager private constructor() {
    
    private var prefs: SharedPreferences? = null
    
    companion object {
        private const val PREFS_NAME = "schedule_settings"
        private const val KEY_SEMESTER_START = "semester_start"
        private const val KEY_PERIOD_TIMES = "period_times"
        private const val KEY_CUSTOM_COURSES = "custom_courses"
        private const val KEY_PERIOD_COUNT = "period_count"
        
        @Volatile
        private var instance: ScheduleSettingsManager? = null
        
        fun getInstance(): ScheduleSettingsManager {
            return instance ?: synchronized(this) {
                instance ?: ScheduleSettingsManager().also { instance = it }
            }
        }
    }
    
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun accountStorageKey(): String {
        return UserManager.getInstance().currentAccountStorageKey.ifBlank { "default" }
    }

    private fun scopedKey(key: String): String {
        return "${key}_${accountStorageKey()}"
    }

    private fun getScopedInt(key: String, defaultValue: Int): Int {
        val scoped = scopedKey(key)
        val p = prefs ?: return defaultValue
        if (!p.contains(scoped) && p.contains(key)) {
            val value = p.getInt(key, defaultValue)
            p.edit().putInt(scoped, value).remove(key).apply()
            return value
        }
        return p.getInt(scoped, defaultValue)
    }

    private fun getScopedLong(key: String, defaultValue: Long): Long {
        val scoped = scopedKey(key)
        val p = prefs ?: return defaultValue
        if (!p.contains(scoped) && p.contains(key)) {
            val value = p.getLong(key, defaultValue)
            p.edit().putLong(scoped, value).remove(key).apply()
            return value
        }
        return p.getLong(scoped, defaultValue)
    }

    private fun getScopedString(key: String): String? {
        val scoped = scopedKey(key)
        val p = prefs ?: return null
        if (!p.contains(scoped) && p.contains(key)) {
            val value = p.getString(key, null)
            p.edit().putString(scoped, value).remove(key).apply()
            return value
        }
        return p.getString(scoped, null)
    }
    
    // ============ 节次数量 ============
    
    var periodCount: Int
        get() = getScopedInt(KEY_PERIOD_COUNT, 12)
        set(value) {
            prefs?.edit()?.putInt(scopedKey(KEY_PERIOD_COUNT), value)?.remove(KEY_PERIOD_COUNT)?.apply()
        }
    
    // ============ 第一周日期 ============
    
    var semesterStartDate: Long
        get() = getScopedLong(KEY_SEMESTER_START, 0L)
        set(value) {
            prefs?.edit()?.putLong(scopedKey(KEY_SEMESTER_START), value)?.remove(KEY_SEMESTER_START)?.apply()
        }
    
    /**
     * 根据第一周日期计算当前是第几周
     */
    fun calculateCurrentWeek(): Int {
        val startDate = semesterStartDate
        if (startDate == 0L) return 1
        
        val now = System.currentTimeMillis()
        val diffDays = (now - startDate) / (1000 * 60 * 60 * 24)
        val week = (diffDays / 7).toInt() + 1
        return week.coerceIn(1, 25)
    }
    
    /**
     * 获取学期开始日期的 Calendar 对象（用于 iCal 导出）
     */
    fun getSemesterStartCalendar(): Calendar {
        val calendar = Calendar.getInstance()
        val startDate = semesterStartDate
        if (startDate > 0L) {
            calendar.timeInMillis = startDate
        } else {
            // 默认使用当前学期的开始日期（假设9月第一个周一）
            calendar.set(Calendar.MONTH, Calendar.SEPTEMBER)
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            // 找到第一个周一
            while (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
            }
        }
        return calendar
    }
    
    // ============ 节次时间 ============
    
    data class PeriodTime(
        val period: Int,
        val startTime: String,
        val endTime: String
    )
    
    fun getPeriodTimes(): List<PeriodTime> {
        val json = getScopedString(KEY_PERIOD_TIMES)
        if (json != null) {
            try {
                val array = JSONArray(json)
                val list = mutableListOf<PeriodTime>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(PeriodTime(
                        period = obj.getInt("period"),
                        startTime = obj.getString("start"),
                        endTime = obj.getString("end")
                    ))
                }
                return list
            } catch (e: Exception) {
                // ignore
            }
        }
        return getDefaultPeriodTimes()
    }
    
    fun savePeriodTimes(times: List<PeriodTime>) {
        val array = JSONArray()
        times.forEach { pt ->
            val obj = JSONObject()
            obj.put("period", pt.period)
            obj.put("start", pt.startTime)
            obj.put("end", pt.endTime)
            array.put(obj)
        }
        prefs?.edit()
            ?.putString(scopedKey(KEY_PERIOD_TIMES), array.toString())
            ?.remove(KEY_PERIOD_TIMES)
            ?.apply()
    }
    
    fun getDefaultPeriodTimes(): List<PeriodTime> {
        return listOf(
            PeriodTime(1, "08:00", "08:45"),
            PeriodTime(2, "08:55", "09:40"),
            PeriodTime(3, "10:00", "10:45"),
            PeriodTime(4, "10:55", "11:40"),
            PeriodTime(5, "14:00", "14:45"),
            PeriodTime(6, "14:55", "15:40"),
            PeriodTime(7, "16:00", "16:45"),
            PeriodTime(8, "16:55", "17:40"),
            PeriodTime(9, "19:00", "19:45"),
            PeriodTime(10, "19:55", "20:40"),
            PeriodTime(11, "20:50", "21:35"),
            PeriodTime(12, "21:45", "22:30")
        )
    }
    
    // ============ 自定义课程 ============
    
    data class CustomCourse(
        val id: String,
        val name: String,
        val location: String,
        val teacher: String,
        val day: Int,
        val startPeriod: Int,
        val endPeriod: Int,
        val weeks: String
    )
    
    private fun customCoursesKey(): String {
        return scopedKey(KEY_CUSTOM_COURSES)
    }
    
    fun getCustomCourses(): List<CustomCourse> {
        val scoped = customCoursesKey()
        val json = prefs?.getString(scoped, null)
            ?: prefs?.getString(KEY_CUSTOM_COURSES, null)?.also { legacy ->
                prefs?.edit()
                    ?.putString(scoped, legacy)
                    ?.remove(KEY_CUSTOM_COURSES)
                    ?.apply()
            }
            ?: return emptyList()
        try {
            val array = JSONArray(json)
            val list = mutableListOf<CustomCourse>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(CustomCourse(
                    id = obj.optString("id", ""),
                    name = obj.getString("name"),
                    location = obj.optString("location", ""),
                    teacher = obj.optString("teacher", ""),
                    day = obj.getInt("day"),
                    startPeriod = obj.getInt("startPeriod"),
                    endPeriod = obj.getInt("endPeriod"),
                    weeks = obj.optString("weeks", "1-16周")
                ))
            }
            return list
        } catch (e: Exception) {
            return emptyList()
        }
    }
    
    fun addCustomCourse(course: CustomCourse) {
        val courses = getCustomCourses().toMutableList()
        courses.add(course)
        saveCustomCourses(courses)
    }
    
    fun removeCustomCourse(courseId: String) {
        val courses = getCustomCourses().filter { it.id != courseId }
        saveCustomCourses(courses)
    }
    
    private fun saveCustomCourses(courses: List<CustomCourse>) {
        val array = JSONArray()
        courses.forEach { c ->
            val obj = JSONObject()
            obj.put("id", c.id)
            obj.put("name", c.name)
            obj.put("location", c.location)
            obj.put("teacher", c.teacher)
            obj.put("day", c.day)
            obj.put("startPeriod", c.startPeriod)
            obj.put("endPeriod", c.endPeriod)
            obj.put("weeks", c.weeks)
            array.put(obj)
        }
        prefs?.edit()
            ?.putString(customCoursesKey(), array.toString())
            ?.remove(KEY_CUSTOM_COURSES)
            ?.apply()
    }
}

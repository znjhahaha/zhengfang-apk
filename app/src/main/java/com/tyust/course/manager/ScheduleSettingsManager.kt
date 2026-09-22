package com.tyust.course.manager

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 课表设置管理器 - 管理节次时间、第一周日期、自定义课程
 */
class ScheduleSettingsManager internal constructor(private var prefs: SharedPreferences? = null) {
    var revision by mutableIntStateOf(0)
        private set
    
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

    private fun getScopedLong(key: String, defaultValue: Long, accountKey: String = accountStorageKey()): Long {
        val scoped = "${key}_$accountKey"
        val p = prefs ?: return defaultValue
        if (!p.contains(scoped) && p.contains(key)) {
            val value = p.getLong(key, defaultValue)
            p.edit().putLong(scoped, value).remove(key).apply()
            return value
        }
        return p.getLong(scoped, defaultValue)
    }

    private fun getScopedString(key: String, accountKey: String = accountStorageKey()): String? {
        val scoped = "${key}_$accountKey"
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
            revision++
        }
    
    // ============ 第一周日期 ============
    
    var semesterStartDate: Long
        get() = getSemesterStartDate()
        set(value) {
            if (semesterStartDate == value) {
                prefs?.edit()?.remove(scopedKey(KEY_SEMESTER_START) + "_provider")?.apply()
                return
            }
            prefs?.edit()?.putLong(scopedKey(KEY_SEMESTER_START), value)?.remove(scopedKey(KEY_SEMESTER_START) + "_provider")?.remove(KEY_SEMESTER_START)?.apply()
            revision++
        }

    fun getSemesterStartDate(accountKey: String = accountStorageKey()): Long = getScopedLong(KEY_SEMESTER_START, 0L, accountKey)
    
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
    
    @JvmOverloads fun getPeriodTimes(accountKey: String = accountStorageKey()): List<PeriodTime> {
        val json = getScopedString(KEY_PERIOD_TIMES, accountKey)
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
            ?.remove(scopedKey(KEY_PERIOD_TIMES) + "_provider")
            ?.remove(KEY_PERIOD_TIMES)
            ?.apply()
        revision++
    }

    /** Existing saved values (including migrated legacy values) are user choices. */
    fun applyProviderCalendar(accountKey: String, calendar: JSONObject) {
        val p = prefs ?: return
        val edit = p.edit()
        val timesKey = "${KEY_PERIOD_TIMES}_$accountKey"
        if ((!p.contains(timesKey) || p.getBoolean(timesKey + "_provider", false)) && !p.contains(KEY_PERIOD_TIMES)) {
            val times = JSONArray()
            val source = calendar.getJSONArray("periods")
            for (i in 0 until source.length()) {
                val period = source.getJSONObject(i)
                times.put(JSONObject().put("period", period.getInt("number")).put("start", period.getString("start")).put("end", period.getString("end")))
            }
            if (times.length() > 0) edit.putString(timesKey, times.toString()).putBoolean(timesKey + "_provider", true)
        }
        val startKey = "${KEY_SEMESTER_START}_$accountKey"
        if ((!p.contains(startKey) || p.getBoolean(startKey + "_provider", false)) && !p.contains(KEY_SEMESTER_START)) {
            runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply { isLenient = false }.parse(calendar.getString("startDate"))?.time }
                .getOrNull()?.let { edit.putLong(startKey, it).putBoolean(startKey + "_provider", true) }
        }
        edit.apply()
        revision++
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
    
    private fun customCoursesKey(accountKey: String): String {
        return "${KEY_CUSTOM_COURSES}_$accountKey"
    }
    
    fun getCustomCourses(accountKey: String = accountStorageKey()): List<CustomCourse> {
        val scoped = customCoursesKey(accountKey)
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
            val seen = mutableSetOf<String>()
            val repaired = list.map { course ->
                if (course.id.isNotBlank() && seen.add(course.id)) course
                else course.copy(id = java.util.UUID.randomUUID().toString()).also { seen.add(it.id) }
            }
            if (repaired != list) saveCustomCourses(repaired, accountKey)
            return repaired
        } catch (e: Exception) {
            return emptyList()
        }
    }
    
    fun addCustomCourse(course: CustomCourse, accountKey: String = accountStorageKey()) {
        updateCustomCourse(course, accountKey)
    }
    
    fun updateCustomCourse(course: CustomCourse, accountKey: String = accountStorageKey()) {
        require(accountKey.isNotBlank() && course.id.isNotBlank())
        val existing = getCustomCourses(accountKey)
        val courses = if (existing.any { it.id == course.id }) existing.map { if (it.id == course.id) course else it }
            else existing + course
        saveCustomCourses(courses, accountKey)
    }
    
    fun removeCustomCourse(courseId: String, accountKey: String = accountStorageKey()) {
        saveCustomCourses(getCustomCourses(accountKey).filter { it.id != courseId }, accountKey)
    }

    private fun saveCustomCourses(courses: List<CustomCourse>, accountKey: String) {
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
            ?.putString(customCoursesKey(accountKey), array.toString())
            ?.remove(KEY_CUSTOM_COURSES)
            ?.apply()
        revision++
    }
}

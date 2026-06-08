package com.tyust.course.manager

import android.content.Context
import android.content.SharedPreferences
import com.tyust.course.model.Course
import org.json.JSONArray
import org.json.JSONObject

/**
 * 课程列表缓存管理器
 * - 缓存有效期为1小时
 * - 只有手动刷新才会重新加载
 */
object CourseCacheManager {
    private const val PREF_NAME = "course_cache"
    private const val KEY_COURSES = "cached_courses"
    private const val KEY_TIMESTAMP = "cache_timestamp"
    private const val CACHE_DURATION_MS = 60 * 60 * 1000L // 1小时
    
    private var cachedCourses: List<Course>? = null
    private var cacheTimestamp: Long = 0L
    private var cachedAccountKey: String = ""
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun normalizeAccountKey(accountKey: String): String {
        val key = accountKey.ifBlank { "default" }
        return key.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    }

    private fun currentAccountKey(): String = normalizeAccountKey(UserManager.getInstance().currentAccountStorageKey)

    private fun coursesKey(accountKey: String): String = "${KEY_COURSES}_${normalizeAccountKey(accountKey)}"

    private fun timestampKey(accountKey: String): String = "${KEY_TIMESTAMP}_${normalizeAccountKey(accountKey)}"

    private fun courseToJson(course: Course): JSONObject {
        return JSONObject().apply {
            put("courseId", course.courseId ?: "")
            put("classId", course.classId ?: "")
            put("doJxbId", course.doJxbId ?: "")
            put("name", course.name ?: "")
            put("teacher", course.teacher ?: "")
            put("jxbmc", course.jxbmc ?: "")
            put("time", course.time ?: "")
            put("location", course.location ?: "")
            put("capacity", course.capacity)
            put("selected", course.selected)
            put("isSelected", course.isSelected)
            put("credit", course.credit ?: "")
            put("kklxdm", course.kklxdm ?: "")
            put("_xkkz_id", course._xkkz_id ?: "")
            put("njdm_id", course.njdm_id ?: "")
            put("zyh_id", course.zyh_id ?: "")
            put("_rwlx", course._rwlx ?: "")
            put("_xklc", course._xklc ?: "")
            put("_sfkxq", course._sfkxq ?: "")
            put("_xkxskcgskg", course._xkxskcgskg ?: "")
        }
    }

    private fun courseFromJson(obj: JSONObject): Course {
        return Course().apply {
            courseId = obj.optString("courseId", "")
            classId = obj.optString("classId", "")
            doJxbId = obj.optString("doJxbId", "")
            name = obj.optString("name", "")
            teacher = obj.optString("teacher", "")
            jxbmc = obj.optString("jxbmc", "")
            time = obj.optString("time", "")
            location = obj.optString("location", "")
            capacity = obj.optInt("capacity", 0)
            selected = obj.optInt("selected", 0)
            isSelected = obj.optBoolean("isSelected", false)
            credit = obj.optString("credit", "")
            kklxdm = obj.optString("kklxdm", "")
            _xkkz_id = obj.optString("_xkkz_id", "")
            njdm_id = obj.optString("njdm_id", "")
            zyh_id = obj.optString("zyh_id", "")
            _rwlx = obj.optString("_rwlx", "")
            _xklc = obj.optString("_xklc", "")
            _sfkxq = obj.optString("_sfkxq", "")
            _xkxskcgskg = obj.optString("_xkxskcgskg", "")
        }
    }

    private fun isTimestampValid(timestamp: Long): Boolean {
        if (timestamp == 0L) return false
        val elapsed = System.currentTimeMillis() - timestamp
        return elapsed < CACHE_DURATION_MS
    }
    
    /**
     * 保存课程列表到当前账号缓存。
     */
    fun saveCourses(context: Context, courses: List<Course>) {
        saveCourses(context, courses, currentAccountKey())
    }

    /**
     * 保存课程列表到指定账号缓存。
     */
    fun saveCourses(context: Context, courses: List<Course>, accountKey: String) {
        val normalizedAccountKey = normalizeAccountKey(accountKey)
        val now = System.currentTimeMillis()
        cachedCourses = courses
        cacheTimestamp = now
        cachedAccountKey = normalizedAccountKey
        
        try {
            val prefs = getPrefs(context)
            val jsonArray = JSONArray()
            courses.forEach { course -> jsonArray.put(courseToJson(course)) }
            prefs.edit()
                .putString(coursesKey(normalizedAccountKey), jsonArray.toString())
                .putLong(timestampKey(normalizedAccountKey), now)
                .apply()
            android.util.Log.d("CourseCacheManager", "缓存了 ${courses.size} 门课程: $normalizedAccountKey")
        } catch (e: Exception) {
            android.util.Log.e("CourseCacheManager", "保存缓存失败: ${e.message}")
        }
    }
    
    /**
     * 获取当前账号缓存的课程列表（如果有效）。
     */
    fun getCachedCourses(context: Context): List<Course>? {
        return getCachedCourses(context, currentAccountKey())
    }

    /**
     * 获取指定账号缓存的课程列表（如果有效）。
     */
    fun getCachedCourses(context: Context, accountKey: String): List<Course>? {
        val normalizedAccountKey = normalizeAccountKey(accountKey)
        if (cachedAccountKey == normalizedAccountKey && cachedCourses != null && isTimestampValid(cacheTimestamp)) {
            android.util.Log.d("CourseCacheManager", "使用内存缓存: ${cachedCourses?.size} 门课程")
            return cachedCourses
        }
        
        try {
            val prefs = getPrefs(context)
            val timestamp = prefs.getLong(timestampKey(normalizedAccountKey), 0L)
            if (!isTimestampValid(timestamp)) {
                android.util.Log.d("CourseCacheManager", "缓存已过期或不存在: $normalizedAccountKey")
                return null
            }
            
            val json = prefs.getString(coursesKey(normalizedAccountKey), null) ?: return null
            val jsonArray = JSONArray(json)
            val courses = mutableListOf<Course>()
            for (i in 0 until jsonArray.length()) {
                courses.add(courseFromJson(jsonArray.getJSONObject(i)))
            }
            
            cachedCourses = courses
            cacheTimestamp = timestamp
            cachedAccountKey = normalizedAccountKey
            android.util.Log.d("CourseCacheManager", "从磁盘恢复缓存: ${courses.size} 门课程")
            return courses
        } catch (e: Exception) {
            android.util.Log.e("CourseCacheManager", "读取缓存失败: ${e.message}")
            return null
        }
    }
    
    /**
     * 检查当前账号缓存是否有效（未过期）。
     */
    fun isCacheValid(): Boolean {
        return isTimestampValid(cacheTimestamp)
    }

    /**
     * 检查指定账号缓存是否有效（未过期）。
     */
    fun isCacheValid(context: Context, accountKey: String): Boolean {
        val normalizedAccountKey = normalizeAccountKey(accountKey)
        val timestamp = if (cachedAccountKey == normalizedAccountKey && cacheTimestamp != 0L) {
            cacheTimestamp
        } else {
            getPrefs(context).getLong(timestampKey(normalizedAccountKey), 0L)
        }
        return isTimestampValid(timestamp)
    }
    
    /**
     * 获取当前账号缓存剩余有效时间（分钟）。
     */
    fun getRemainingMinutes(): Int {
        if (!isTimestampValid(cacheTimestamp)) return 0
        val remaining = CACHE_DURATION_MS - (System.currentTimeMillis() - cacheTimestamp)
        return (remaining / 1000 / 60).toInt()
    }

    /**
     * 获取指定账号缓存剩余有效时间（分钟）。
     */
    fun getRemainingMinutes(context: Context, accountKey: String): Int {
        val normalizedAccountKey = normalizeAccountKey(accountKey)
        val timestamp = if (cachedAccountKey == normalizedAccountKey && cacheTimestamp != 0L) {
            cacheTimestamp
        } else {
            getPrefs(context).getLong(timestampKey(normalizedAccountKey), 0L)
        }
        if (!isTimestampValid(timestamp)) return 0
        val remaining = CACHE_DURATION_MS - (System.currentTimeMillis() - timestamp)
        return (remaining / 1000 / 60).toInt()
    }
    
    /**
     * 清除全部课程缓存。
     */
    fun clearCache(context: Context) {
        cachedCourses = null
        cacheTimestamp = 0L
        cachedAccountKey = ""
        
        try {
            val prefs = getPrefs(context)
            prefs.edit().clear().apply()
            android.util.Log.d("CourseCacheManager", "全部课程缓存已清除")
        } catch (e: Exception) {
            android.util.Log.e("CourseCacheManager", "清除缓存失败: ${e.message}")
        }
    }

    /**
     * 清除当前账号课程缓存。
     */
    fun clearCurrentAccountCache(context: Context) {
        clearAccountCache(context, currentAccountKey())
    }

    /**
     * 清除指定账号课程缓存。
     */
    fun clearAccountCache(context: Context, accountKey: String) {
        val normalizedAccountKey = normalizeAccountKey(accountKey)
        if (cachedAccountKey == normalizedAccountKey) {
            cachedCourses = null
            cacheTimestamp = 0L
            cachedAccountKey = ""
        }

        try {
            val prefs = getPrefs(context)
            prefs.edit()
                .remove(coursesKey(normalizedAccountKey))
                .remove(timestampKey(normalizedAccountKey))
                .apply()
            android.util.Log.d("CourseCacheManager", "账号课程缓存已清除: $normalizedAccountKey")
        } catch (e: Exception) {
            android.util.Log.e("CourseCacheManager", "清除账号缓存失败: ${e.message}")
        }
    }
    
    /**
     * 更新当前账号内存缓存（不保存到磁盘）。
     */
    fun updateMemoryCache(courses: List<Course>) {
        updateMemoryCache(courses, currentAccountKey())
    }

    /**
     * 更新指定账号内存缓存（不保存到磁盘）。
     */
    fun updateMemoryCache(courses: List<Course>, accountKey: String) {
        cachedCourses = courses
        cachedAccountKey = normalizeAccountKey(accountKey)
        cacheTimestamp = System.currentTimeMillis()
    }
}

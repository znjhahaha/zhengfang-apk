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
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 保存课程列表到缓存
     */
    fun saveCourses(context: Context, courses: List<Course>) {
        cachedCourses = courses
        cacheTimestamp = System.currentTimeMillis()
        
        try {
            val prefs = getPrefs(context)
            val jsonArray = JSONArray()
            courses.forEach { course ->
                val obj = JSONObject()
                obj.put("courseId", course.courseId ?: "")
                obj.put("classId", course.classId ?: "")  // 🔧 修复：添加 classId
                obj.put("doJxbId", course.doJxbId ?: "") // 🔧 修复：添加 doJxbId
                obj.put("name", course.name ?: "")
                obj.put("teacher", course.teacher ?: "")
                obj.put("jxbmc", course.jxbmc ?: "") // 🔧 修复：保存 jxbmc
                obj.put("time", course.time ?: "")
                obj.put("location", course.location ?: "")
                obj.put("capacity", course.capacity)  // int
                obj.put("selected", course.selected)  // int
                obj.put("isSelected", course.isSelected) // 🔧 新增：保存选课状态
                obj.put("credit", course.credit ?: "")
                obj.put("kklxdm", course.kklxdm ?: "")
                obj.put("_xkkz_id", course._xkkz_id ?: "")
                obj.put("njdm_id", course.njdm_id ?: "")
                obj.put("zyh_id", course.zyh_id ?: "")
                obj.put("_rwlx", course._rwlx ?: "")
                obj.put("_xklc", course._xklc ?: "")
                obj.put("_sfkxq", course._sfkxq ?: "")
                obj.put("_xkxskcgskg", course._xkxskcgskg ?: "")
                jsonArray.put(obj)
            }
            prefs.edit()
                .putString(KEY_COURSES, jsonArray.toString())
                .putLong(KEY_TIMESTAMP, cacheTimestamp)
                .apply()
            android.util.Log.d("CourseCacheManager", "缓存了 ${courses.size} 门课程")
        } catch (e: Exception) {
            android.util.Log.e("CourseCacheManager", "保存缓存失败: ${e.message}")
        }
    }
    
    /**
     * 获取缓存的课程列表（如果有效）
     * @return 缓存的课程列表，如果缓存无效则返回null
     */
    fun getCachedCourses(context: Context): List<Course>? {
        // 先检查内存缓存
        if (cachedCourses != null && isCacheValid()) {
            android.util.Log.d("CourseCacheManager", "使用内存缓存: ${cachedCourses?.size} 门课程")
            return cachedCourses
        }
        
        // 尝试从 SharedPreferences 恢复
        try {
            val prefs = getPrefs(context)
            cacheTimestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
            
            if (!isCacheValid()) {
                android.util.Log.d("CourseCacheManager", "缓存已过期或不存在")
                return null
            }
            
            val json = prefs.getString(KEY_COURSES, null) ?: return null
            val jsonArray = JSONArray(json)
            val courses = mutableListOf<Course>()
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val course = Course()
                course.courseId = obj.optString("courseId", "")
                course.classId = obj.optString("classId", "")  // 🔧 修复：恢复 classId
                course.doJxbId = obj.optString("doJxbId", "") // 🔧 修复：恢复 doJxbId
                course.name = obj.optString("name", "")
                course.teacher = obj.optString("teacher", "")
                course.jxbmc = obj.optString("jxbmc", "") // 🔧 修复：恢复 jxbmc
                course.time = obj.optString("time", "")
                course.location = obj.optString("location", "")
                course.capacity = obj.optInt("capacity", 0)
                course.selected = obj.optInt("selected", 0)
                course.isSelected = obj.optBoolean("isSelected", false) // 🔧 新增：恢复选课状态
                course.credit = obj.optString("credit", "")
                course.kklxdm = obj.optString("kklxdm", "")
                course._xkkz_id = obj.optString("_xkkz_id", "")
                course.njdm_id = obj.optString("njdm_id", "")
                course.zyh_id = obj.optString("zyh_id", "")
                course._rwlx = obj.optString("_rwlx", "")
                course._xklc = obj.optString("_xklc", "")
                course._sfkxq = obj.optString("_sfkxq", "")
                course._xkxskcgskg = obj.optString("_xkxskcgskg", "")
                courses.add(course)
            }
            
            cachedCourses = courses
            android.util.Log.d("CourseCacheManager", "从磁盘恢复缓存: ${courses.size} 门课程")
            return courses
        } catch (e: Exception) {
            android.util.Log.e("CourseCacheManager", "读取缓存失败: ${e.message}")
            return null
        }
    }
    
    /**
     * 检查缓存是否有效（未过期）
     */
    fun isCacheValid(): Boolean {
        if (cacheTimestamp == 0L) return false
        val elapsed = System.currentTimeMillis() - cacheTimestamp
        return elapsed < CACHE_DURATION_MS
    }
    
    /**
     * 获取缓存剩余有效时间（分钟）
     */
    fun getRemainingMinutes(): Int {
        if (!isCacheValid()) return 0
        val elapsed = System.currentTimeMillis() - cacheTimestamp
        val remaining = CACHE_DURATION_MS - elapsed
        return (remaining / 1000 / 60).toInt()
    }
    
    /**
     * 清除缓存
     */
    fun clearCache(context: Context) {
        cachedCourses = null
        cacheTimestamp = 0L
        
        try {
            val prefs = getPrefs(context)
            prefs.edit().clear().apply()
            android.util.Log.d("CourseCacheManager", "缓存已清除")
        } catch (e: Exception) {
            android.util.Log.e("CourseCacheManager", "清除缓存失败: ${e.message}")
        }
    }
    
    /**
     * 更新内存缓存（不保存到磁盘）
     */
    fun updateMemoryCache(courses: List<Course>) {
        cachedCourses = courses
    }
}

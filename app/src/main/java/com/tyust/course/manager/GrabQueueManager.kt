package com.tyust.course.manager

import android.content.Context
import android.content.SharedPreferences
import com.tyust.course.model.Course
import org.json.JSONArray
import org.json.JSONObject

/**
 * 抢课队列管理器（单例模式）
 * 管理定时抢课的课程队列
 */
class GrabQueueManager private constructor() {
    
    companion object {
        private const val PREFS_NAME = "grab_queue_prefs"
        private const val KEY_QUEUE = "course_queue"
        
        @Volatile
        private var INSTANCE: GrabQueueManager? = null
        
        fun getInstance(): GrabQueueManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GrabQueueManager().also { INSTANCE = it }
            }
        }
    }
    
    // 内存中的队列
    private val queue = mutableListOf<Course>()
    
    // 队列变化监听器
    private var onQueueChangedListener: ((List<Course>) -> Unit)? = null
    
    /**
     * 初始化（从 SharedPreferences 加载队列）
     */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs(prefs)
    }
    
    /**
     * 添加课程到队列
     */
    fun addCourseToQueue(course: Course): Boolean {
        // 检查是否已存在（通过 classId 判断）
        if (queue.any { it.classId == course.classId }) {
            return false
        }
        queue.add(course)
        notifyQueueChanged()
        return true
    }
    
    /**
     * 从队列移除课程
     */
    fun removeCourseFromQueue(index: Int) {
        if (index in 0 until queue.size) {
            queue.removeAt(index)
            notifyQueueChanged()
        }
    }
    
    /**
     * 通过 classId 移除课程
     */
    fun removeCourseByClassId(classId: String) {
        queue.removeAll { it.classId == classId }
        notifyQueueChanged()
    }
    
    /**
     * 移动队列项
     */
    fun moveItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex in 0 until queue.size && toIndex in 0 until queue.size) {
            val item = queue.removeAt(fromIndex)
            queue.add(toIndex, item)
            notifyQueueChanged()
        }
    }
    
    /**
     * 清空队列
     */
    fun clearQueue() {
        queue.clear()
        notifyQueueChanged()
    }
    
    /**
     * 获取队列副本
     */
    fun getQueue(): List<Course> = queue.toList()
    
    /**
     * 获取队列大小
     */
    fun getQueueSize(): Int = queue.size
    
    /**
     * 设置队列变化监听器
     */
    fun setOnQueueChangedListener(listener: ((List<Course>) -> Unit)?) {
        onQueueChangedListener = listener
    }
    
    /**
     * 保存到 SharedPreferences
     */
    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        saveToPrefs(prefs)
    }
    
    private fun notifyQueueChanged() {
        onQueueChangedListener?.invoke(queue.toList())
    }
    
    private fun saveToPrefs(prefs: SharedPreferences) {
        try {
            val jsonArray = JSONArray()
            queue.forEach { course ->
                val obj = JSONObject()
                obj.put("courseId", course.courseId ?: "")
                obj.put("classId", course.classId ?: "")
                obj.put("doJxbId", course.doJxbId ?: "")
                obj.put("name", course.name ?: "")
                obj.put("teacher", course.teacher ?: "")
                obj.put("time", course.time ?: "")
                obj.put("location", course.location ?: "")
                obj.put("capacity", course.capacity)
                obj.put("selected", course.selected)
                obj.put("credit", course.credit ?: "")
                obj.put("kklxdm", course.kklxdm ?: "")
                obj.put("_xkkz_id", course._xkkz_id ?: "")
                obj.put("njdm_id", course.njdm_id ?: "")
                obj.put("zyh_id", course.zyh_id ?: "")
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_QUEUE, jsonArray.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("GrabQueueManager", "保存队列失败: ${e.message}")
        }
    }
    
    private fun loadFromPrefs(prefs: SharedPreferences) {
        try {
            val jsonStr = prefs.getString(KEY_QUEUE, null) ?: return
            val jsonArray = JSONArray(jsonStr)
            queue.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val course = Course()
                course.courseId = obj.optString("courseId", "")
                course.classId = obj.optString("classId", "")
                course.doJxbId = obj.optString("doJxbId", "")
                course.name = obj.optString("name", "")
                course.teacher = obj.optString("teacher", "")
                course.time = obj.optString("time", "")
                course.location = obj.optString("location", "")
                course.capacity = obj.optInt("capacity", 0)
                course.selected = obj.optInt("selected", 0)
                course.credit = obj.optString("credit", "")
                course.kklxdm = obj.optString("kklxdm", "")
                course._xkkz_id = obj.optString("_xkkz_id", "")
                course.njdm_id = obj.optString("njdm_id", "")
                course.zyh_id = obj.optString("zyh_id", "")
                queue.add(course)
            }
            android.util.Log.d("GrabQueueManager", "从磁盘加载队列: ${queue.size} 门课程")
        } catch (e: Exception) {
            android.util.Log.e("GrabQueueManager", "加载队列失败: ${e.message}")
        }
    }
}

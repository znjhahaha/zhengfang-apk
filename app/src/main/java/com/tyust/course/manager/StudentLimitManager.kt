package com.tyust.course.manager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 学生数量限制管理器
 * 本地记录该设备上使用过的学生姓名，用于限制一机多号
 */
object StudentLimitManager {
    private const val TAG = "StudentLimitManager"
    private const val PREFS_NAME = "student_limit_prefs"
    private const val KEY_USED_NAMES = "used_student_names"
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 获取已使用的学生姓名列表
     */
    fun getUsedStudentNames(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_USED_NAMES, emptySet()) ?: emptySet()
    }
    
    /**
     * 记录一个新的学生姓名
     */
    fun recordStudentName(context: Context, studentName: String) {
        if (studentName.isBlank()) return
        
        val currentNames = getUsedStudentNames(context).toMutableSet()
        if (currentNames.add(studentName)) {
            getPrefs(context).edit()
                .putStringSet(KEY_USED_NAMES, currentNames)
                .apply()
            Log.d(TAG, "已记录新学生: $studentName, 当前共 ${currentNames.size} 个")
        } else {
            Log.d(TAG, "学生 $studentName 已存在记录中")
        }
    }
    
    /**
     * 检查是否可以使用新的学生姓名
     * @param studentName 当前要使用的学生姓名
     * @param maxStudents 允许的最大学生数 (0 或负数表示无限制/超级账户)
     * @return true = 可以使用, false = 已达上限
     */
    fun canUseStudent(context: Context, studentName: String, maxStudents: Int): Boolean {
        // 超级账户无限制
        if (maxStudents <= 0) {
            Log.d(TAG, "超级账户，无数量限制")
            return true
        }
        
        val usedNames = getUsedStudentNames(context)
        
        // 如果这个姓名已经用过，允许继续使用
        if (usedNames.contains(studentName)) {
            Log.d(TAG, "学生 $studentName 已在记录中，允许使用")
            return true
        }
        
        // 检查是否超过限制
        if (usedNames.size >= maxStudents) {
            Log.w(TAG, "已达学生数量上限: ${usedNames.size}/$maxStudents")
            return false
        }
        
        Log.d(TAG, "新学生 $studentName，当前 ${usedNames.size}/$maxStudents")
        return true
    }
    
    /**
     * 获取已使用数量
     */
    fun getUsedCount(context: Context): Int {
        return getUsedStudentNames(context).size
    }
    
    /**
     * 清除记录（调试用）
     */
    fun clearRecords(context: Context) {
        getPrefs(context).edit().clear().apply()
        Log.d(TAG, "已清除所有学生记录")
    }
}

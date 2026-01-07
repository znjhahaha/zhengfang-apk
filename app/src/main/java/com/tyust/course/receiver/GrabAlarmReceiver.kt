package com.tyust.course.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tyust.course.manager.UserManager
import com.tyust.course.service.GrabService

/**
 * 定时抢课广播接收器
 * 使用 AlarmManager 触发
 * 🔧 修复说明：GrabService 已经修改为在匹配教学班时优先使用 SmartSelector.queue 中保存的 classId
 * 而不是只按关键词匹配老师/时间，这确保了精确模式能正确选择用户指定的教学班
 */
class GrabAlarmReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "GrabAlarmReceiver"
        const val ACTION_SCHEDULED_GRAB = "com.tyust.course.action.SCHEDULED_GRAB"
        const val EXTRA_COURSE_KEYWORDS = "course_keywords"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "⏰ 定时抢课触发!")
        
        if (intent.action != ACTION_SCHEDULED_GRAB) return
        
        val courseKeywords = intent.getStringExtra(EXTRA_COURSE_KEYWORDS) ?: ""
        Log.d(TAG, "关键词: $courseKeywords")
        
        // 检查登录状态
        val school = UserManager.getInstance().currentSchool
        if (school == null) {
            Log.e(TAG, "❌ 未登录，无法执行定时任务")
            appendLog(context, "❌ 未登录，无法执行定时任务")
            return
        }
        
        // 保存日志
        appendLog(context, "⏰ 定时任务触发! 关键词: $courseKeywords")
        
        // 获取抢课参数
        val prefs = context.getSharedPreferences("grab_pro_prefs", Context.MODE_PRIVATE)
        val interval = prefs.getString("interval", "1500")?.toIntOrNull() ?: 1500
        val maxRetry = prefs.getString("max_retry", "100")?.toIntOrNull() ?: 100
        val isParallelMode = prefs.getBoolean("parallel_mode", false)
        
        // 🔧 使用 GrabService 的关键词模式
        // GrabService.fetchCourseDetailsAndMatch 已修改为优先使用队列中保存的 classId
        val serviceIntent = Intent(context, GrabService::class.java).apply {
            action = GrabService.ACTION_START_KEYWORD
            putExtra(GrabService.EXTRA_COURSE_KEYWORDS, courseKeywords)
            putExtra(GrabService.EXTRA_INTERVAL, interval)
            putExtra(GrabService.EXTRA_MAX_RETRY, maxRetry)
            putExtra(GrabService.EXTRA_PARALLEL_MODE, isParallelMode)
        }
        
        Log.d(TAG, "🚀 启动关键词抢课服务...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        
        // 清除定时任务标志
        prefs.edit().putBoolean("has_scheduled_task", false).apply()
    }
    
    private fun appendLog(context: Context, message: String) {
        val prefs = context.getSharedPreferences("grab_pro_prefs", Context.MODE_PRIVATE)
        val currentLog = prefs.getString("log_text", "") ?: ""
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val newLog = "$currentLog[$timestamp] $message\n"
        prefs.edit().putString("log_text", newLog).apply()
    }
}


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
        const val EXTRA_ACCOUNT_KEY = "account_key"
        const val EXTRA_ACCOUNT_STORAGE_KEY = "account_storage_key"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_MAX_RETRY = "max_retry"
        const val EXTRA_PARALLEL_MODE = "parallel_mode"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "⏰ 定时抢课触发!")
        
        if (intent.action != ACTION_SCHEDULED_GRAB) return

        val userManager = UserManager.getInstance()
        val scheduledAccountKey = intent.getStringExtra(EXTRA_ACCOUNT_KEY).orEmpty()
        if (scheduledAccountKey.isNotBlank() && scheduledAccountKey != userManager.currentAccountKey) {
            val switched = userManager.switchToAccount(scheduledAccountKey)
            if (!switched) {
                val fallbackStorageKey = intent.getStringExtra(EXTRA_ACCOUNT_STORAGE_KEY).orEmpty()
                appendLog(context, fallbackStorageKey, "定时任务失败：找不到创建任务的账号，请重新登录")
                Log.e(TAG, "找不到定时任务账号: $scheduledAccountKey")
                return
            }
        }

        val accountStorageKey = intent.getStringExtra(EXTRA_ACCOUNT_STORAGE_KEY)
            ?: userManager.currentAccountStorageKey
        val courseKeywords = intent.getStringExtra(EXTRA_COURSE_KEYWORDS) ?: ""
        Log.d(TAG, "关键词: $courseKeywords")
        
        val school = userManager.currentSchool
        if (school == null) {
            Log.e(TAG, "❌ 未登录，无法执行定时任务")
            appendLog(context, accountStorageKey, "未登录，无法执行定时任务")
            return
        }
        
        appendLog(context, accountStorageKey, "定时任务触发，关键词: $courseKeywords")
        
        val prefs = context.getSharedPreferences("grab_pro_prefs", Context.MODE_PRIVATE)
        val interval = if (intent.hasExtra(EXTRA_INTERVAL)) {
            intent.getIntExtra(EXTRA_INTERVAL, 1500)
        } else {
            prefs.getString(scopedKey("interval", accountStorageKey), prefs.getString("interval", "1500"))?.toIntOrNull() ?: 1500
        }
        val maxRetry = if (intent.hasExtra(EXTRA_MAX_RETRY)) {
            intent.getIntExtra(EXTRA_MAX_RETRY, 100)
        } else {
            prefs.getString(scopedKey("max_retry", accountStorageKey), prefs.getString("max_retry", "100"))?.toIntOrNull() ?: 100
        }
        val isParallelMode = if (intent.hasExtra(EXTRA_PARALLEL_MODE)) {
            intent.getBooleanExtra(EXTRA_PARALLEL_MODE, false)
        } else {
            prefs.getBoolean(scopedKey("parallel_mode", accountStorageKey), prefs.getBoolean("parallel_mode", false))
        }
        
        // 🔧 使用 GrabService 的队列模式
        // 服务启动后会绑定定时任务创建账号，并读取该账号自己的队列槽
        val serviceIntent = Intent(context, GrabService::class.java).apply {
            action = GrabService.ACTION_START_QUEUE
            putExtra(GrabService.EXTRA_ACCOUNT_KEY, scheduledAccountKey)
            putExtra(GrabService.EXTRA_ACCOUNT_STORAGE_KEY, accountStorageKey)
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
        
        // 清除当前账号的定时任务标志
        prefs.edit()
            .putBoolean(scopedKey("has_scheduled_task", accountStorageKey), false)
            .remove("has_scheduled_task")
            .apply()
    }

    private fun scopedKey(key: String, accountStorageKey: String): String {
        return if (accountStorageKey.isBlank()) key else "${key}_${accountStorageKey}"
    }
    
    private fun appendLog(context: Context, accountStorageKey: String, message: String) {
        val prefs = context.getSharedPreferences("grab_pro_prefs", Context.MODE_PRIVATE)
        val logKey = scopedKey("log_text", accountStorageKey)
        val currentLog = prefs.getString(logKey, prefs.getString("log_text", "")) ?: ""
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val newLog = "$currentLog[$timestamp] $message\n"
        prefs.edit().putString(logKey, newLog).apply()
    }
}


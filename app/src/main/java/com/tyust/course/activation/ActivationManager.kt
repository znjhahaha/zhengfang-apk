package com.tyust.course.activation

import android.content.Context
import android.content.SharedPreferences

/**
 * 激活状态管理器 (开源版)
 * 已移除白名单限制，所有设备默认激活。
 * 仅保留本地设备标识符生成与学生数目配额控制功能。
 */
object ActivationManager {
    private const val PREFS_NAME = "activation_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 检查激活状态（主入口）
     * 开源版：永远返回 true
     */
    suspend fun checkActivation(context: Context): Boolean {
        // 保存设备 ID 供界面显示（虽然已不再用于白名单校验，但界面可能仍需展示）
        val deviceId = DeviceUtils.getDeviceId(context)
        getPrefs(context).edit().putString(KEY_DEVICE_ID, deviceId).apply()
        
        return true
    }
    
    /**
     * 获取保存的设备 ID（供界面显示）
     */
    fun getSavedDeviceId(context: Context): String {
        return getPrefs(context).getString(KEY_DEVICE_ID, null) 
            ?: DeviceUtils.getDeviceId(context)
    }
    
    /**
     * 清除激活状态（调试用）
     */
    fun clearActivation(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
    
    /**
     * 获取最大允许学生数
     * 开源版默认允许同一学校绑定 3 个账号。
     */
    fun getMaxStudents(context: Context): Int {
        return 3
    }
}

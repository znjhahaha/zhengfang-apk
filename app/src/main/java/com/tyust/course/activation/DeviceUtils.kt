package com.tyust.course.activation

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * 设备 ID 工具类
 * 生成唯一的设备标识符用于白名单验证
 */
object DeviceUtils {
    
    /**
     * 获取设备唯一 ID（8位哈希）
     * 基于 Android ID 生成，重置手机后会变化
     */
    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        
        return hashToShortId(androidId)
    }
    
    /**
     * 将字符串哈希为 8 位大写字母+数字的 ID
     */
    private fun hashToShortId(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray())
        
        // 取前 4 字节转换为 8 位十六进制
        return digest.take(4)
            .joinToString("") { "%02X".format(it) }
    }
}

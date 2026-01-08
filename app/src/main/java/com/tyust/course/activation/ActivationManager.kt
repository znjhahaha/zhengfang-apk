package com.tyust.course.activation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * 激活状态管理器
 * 负责验证设备是否在白名单中
 */
object ActivationManager {
    private const val TAG = "ActivationManager"
    private const val PREFS_NAME = "activation_prefs"
    private const val KEY_IS_ACTIVATED = "is_activated"
    private const val KEY_EXPIRE_DATE = "expire_date"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_MAX_STUDENTS = "max_students"
    private const val KEY_IS_SUPER = "is_super"
    
    // 白名单 JSON 地址（Gitee Raw）
    private const val WHITELIST_URL = "https://gitee.com/znj12345/zhengfang/raw/main/whitelist.json"
    
    // 缓存有效期（1天内不重复请求）
    private const val CACHE_VALID_DAYS = 1
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 检查激活状态（主入口）
     * 返回 true = 已激活，false = 未激活
     */
    suspend fun checkActivation(context: Context): Boolean {
        // 🔒 签名校验已禁用（开发测试中）
        // if (!com.tyust.course.utils.SignatureUtils.verifySignature(context)) {
        //     Log.e(TAG, "❌ 签名校验失败，疑似二次打包，激活被拒绝")
        //     return false
        // }
        
        val deviceId = DeviceUtils.getDeviceId(context)
        val prefs = getPrefs(context)
        
        // 保存设备 ID 供界面显示
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        
        // ⚠️ 已禁用缓存，每次都联网获取最新配置
        // if (isCacheValid(prefs, deviceId)) {
        //     Log.d(TAG, "使用本地缓存，设备已激活")
        //     return true
        // }
        
        // 联网验证
        return try {
            val result = verifyOnline(context, deviceId)
            if (result.first) {
                // 激活成功，保存到本地
                saveActivation(prefs, deviceId, result.second, result.third)
            }
            result.first
        } catch (e: Exception) {
            Log.e(TAG, "联网验证失败: ${e.message}")
            // 网络失败时，如果有之前的激活记录且未过期，仍然允许使用
            isLocalActivated(prefs, deviceId)
        }
    }
    
    /**
     * 检查本地缓存是否有效
     */
    private fun isCacheValid(prefs: SharedPreferences, deviceId: String): Boolean {
        val cachedDeviceId = prefs.getString(KEY_DEVICE_ID, null)
        val isActivated = prefs.getBoolean(KEY_IS_ACTIVATED, false)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
        val expireDate = prefs.getString(KEY_EXPIRE_DATE, null)
        
        if (cachedDeviceId != deviceId || !isActivated) return false
        
        // 检查是否过期
        if (expireDate != null && isExpired(expireDate)) {
            Log.d(TAG, "激活已过期: $expireDate")
            return false
        }
        
        // 检查缓存是否超过有效期
        val daysSinceLastCheck = (System.currentTimeMillis() - lastCheck) / (1000 * 60 * 60 * 24)
        if (daysSinceLastCheck > CACHE_VALID_DAYS) {
            Log.d(TAG, "缓存已过期，需要重新验证")
            return false
        }
        
        return true
    }
    
    /**
     * 联网验证设备 ID
     * 返回 Triple<是否激活, 过期日期, 最大学生数>
     */
    private suspend fun verifyOnline(context: Context, deviceId: String): Triple<Boolean, String?, Int> {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(WHITELIST_URL)
                .header("Cache-Control", "no-cache")
                .build()
            
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}")
            }
            
            val json = response.body?.string() ?: throw Exception("Empty response")
            parseWhitelist(json, deviceId)
        }
    }
    
    /**
     * 解析白名单 JSON，查找设备 ID
     * 返回 Triple<是否激活, 过期日期, 最大学生数>
     */
    private fun parseWhitelist(json: String, deviceId: String): Triple<Boolean, String?, Int> {
        val root = JSONObject(json)
        val devices = root.getJSONArray("devices")
        
        for (i in 0 until devices.length()) {
            val device = devices.getJSONObject(i)
            val id = device.getString("id")
            
            if (id.equals(deviceId, ignoreCase = true)) {
                val expire = device.optString("expire", null)
                
                // 检查是否过期
                if (expire != null && isExpired(expire)) {
                    Log.d(TAG, "设备 $deviceId 已过期: $expire")
                    return Triple(false, expire, 0)
                }
                
                // 读取超级账户和最大学生数
                val isSuper = device.optBoolean("is_super", false)
                val maxStudents = if (isSuper) 0 else device.optInt("max_students", 2)
                
                Log.d(TAG, "设备 $deviceId 验证通过，过期: $expire, 超级: $isSuper, 最大学生数: $maxStudents")
                return Triple(true, expire, maxStudents)
            }
        }
        
        Log.d(TAG, "设备 $deviceId 不在白名单中")
        return Triple(false, null, 0)
    }
    
    /**
     * 检查日期是否已过期
     */
    private fun isExpired(dateStr: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val expireDate = sdf.parse(dateStr) ?: return false
            expireDate.before(Date())
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 保存激活状态到本地
     */
    private fun saveActivation(prefs: SharedPreferences, deviceId: String, expireDate: String?, maxStudents: Int) {
        prefs.edit()
            .putBoolean(KEY_IS_ACTIVATED, true)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_EXPIRE_DATE, expireDate)
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .putInt(KEY_MAX_STUDENTS, maxStudents)
            .apply()
    }
    
    /**
     * 检查本地是否有激活记录且未过期
     */
    private fun isLocalActivated(prefs: SharedPreferences, deviceId: String): Boolean {
        val cachedDeviceId = prefs.getString(KEY_DEVICE_ID, null)
        val isActivated = prefs.getBoolean(KEY_IS_ACTIVATED, false)
        val expireDate = prefs.getString(KEY_EXPIRE_DATE, null)
        
        if (cachedDeviceId != deviceId || !isActivated) return false
        if (expireDate != null && isExpired(expireDate)) return false
        
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
     * 获取最大允许学生数（0 表示无限制/超级账户）
     */
    fun getMaxStudents(context: Context): Int {
        return getPrefs(context).getInt(KEY_MAX_STUDENTS, 2)
    }
}

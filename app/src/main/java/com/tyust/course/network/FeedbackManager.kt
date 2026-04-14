package com.tyust.course.network

import android.content.Context
import android.util.Log
import com.tyust.course.BuildConfig
import com.tyust.course.activation.ActivationManager
import com.tyust.course.manager.UserManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.*

/**
 * 用户反馈管理器
 */
object FeedbackManager {
    private const val TAG = "FeedbackManager"
    
    // 反馈接口地址
    // 生产环境: https://www.znj2006.cn/api/feedback
    // 开发环境: http://10.0.2.2:3000/api/feedback (模拟器) 或 http://YOUR_IP:3000/api/feedback (真机)
    private const val FEEDBACK_API_URL = "https://www.znj2006.cn/api/feedback"
    
    private const val PREFS_NAME = "feedback_prefs"
    private const val KEY_LAST_REPLY_ID = "last_reply_id"
    private const val KEY_HAS_NEW_REPLY = "has_new_reply"
    
    private val client = OkHttpClient()

    /**
     * 提交反馈
     */
    suspend fun submitFeedback(
        context: Context,
        content: String,
        contact: String = "",
        screenshotBase64: String? = null, // 新增图片支持
        includeLogs: Boolean = true // 新增日志开关
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val deviceId = com.tyust.course.activation.DeviceUtils.getDeviceId(context)
            val studentName = UserManager.getInstance().studentName ?: "未知用户"
            val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"

            // 根据开关自动读取最新日志 (直接从 logcat 获取)
            val logs = if (includeLogs) {
                var output = "[App Context Log Capture Header]\n"
                try {
                    val pid = android.os.Process.myPid()
                    // 简化命令，不使用 -v time 尝试兼容性
                    val process = Runtime.getRuntime().exec("logcat -d --pid=$pid")
                    val logcatText = process.inputStream.bufferedReader().use { it.readText() }
                    output += if (logcatText.isNotBlank()) {
                        logcatText.takeLast(15000)
                    } else {
                        "Warning: Logcat output is empty.\n"
                    }
                } catch (e: Exception) {
                    val errorMsg = "Error capturing logs: ${e.message}\n${Log.getStackTraceString(e)}"
                    Log.e(TAG, errorMsg)
                    output += errorMsg
                }
                output
            } else null

            val json = JSONObject().apply {
                put("deviceId", deviceId as String)
                put("studentName", studentName)
                put("content", content)
                put("contact", contact)
                put("version", appVersion)
                if (logs != null) put("logs", logs)
                if (screenshotBase64 != null) put("screenshot", screenshotBase64)
            }

            val jsonString = json.toString()
            Log.d(TAG, "正在提交反馈数据, 长度: ${jsonString.length}")

            val body = jsonString.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(FEEDBACK_API_URL)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Log.d(TAG, "反馈提交成功, 服务器响应: $responseBody")
                    Result.success("发送成功")
                } else {
                    Log.e(TAG, "反馈提交失败: HTTP ${response.code}, 响应: $responseBody")
                    Result.failure(Exception("HTTP ${response.code}: $responseBody"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "提交反馈失败: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 反馈历史数据类
     */
    data class FeedbackItem(
        val id: String,
        val content: String,
        val createdAt: String,
        val reply: ReplyInfo?
    )

    data class ReplyInfo(
        val content: String,
        val repliedAt: String
    )

    /**
     * 获取我的反馈历史
     */
    suspend fun getMyFeedbacks(context: Context): Result<List<FeedbackItem>> = withContext(Dispatchers.IO) {
        try {
            val deviceId = com.tyust.course.activation.DeviceUtils.getDeviceId(context)
            val timestamp = System.currentTimeMillis().toString()
            val signature = calculateSignature(deviceId, timestamp)

            if (signature.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Feedback signing secret is not configured")
                )
            }
            
            val url = "https://www.znj2006.cn/api/feedback/my?deviceId=$deviceId&t=$timestamp&s=$signature"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    val feedbacksArray = json.getJSONArray("feedbacks")
                    val feedbacks = mutableListOf<FeedbackItem>()

                    for (i in 0 until feedbacksArray.length()) {
                        val item = feedbacksArray.getJSONObject(i)
                        val replyObj = if (!item.isNull("reply")) item.getJSONObject("reply") else null

                        feedbacks.add(FeedbackItem(
                            id = item.getString("id"),
                            content = item.getString("content"),
                            createdAt = item.optString("createdAt", ""),
                            reply = replyObj?.let {
                                ReplyInfo(
                                    content = it.getString("content"),
                                    repliedAt = it.optString("repliedAt", "")
                                )
                            }
                        ))
                    }

                    Result.success(feedbacks)
                } else {
                    Result.failure(Exception("HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取反馈历史失败: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 检查是否有新回复
     */
    fun hasNewReply(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_HAS_NEW_REPLY, false)
    }

    /**
     * 标记所有回复为已读
     */
    fun markRepliesAsRead(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_HAS_NEW_REPLY, false).apply()
    }

    /**
     * 后台检测新异步回复 (可在进入设置页面时触发)
     */
    /**
     * 后台检测新异步回复 (可在进入设置页面时触发)
     * @return Boolean 是否有新回复
     */
    suspend fun checkForNewReplies(context: Context): Boolean {
        val result = getMyFeedbacks(context)
        var hasNew = false
        result.onSuccess { list ->
            if (list.isNotEmpty()) {
                val latest = list.first()
                if (latest.reply != null) {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val lastKnownId = prefs.getString(KEY_LAST_REPLY_ID, "")
                    
                    if (latest.id != lastKnownId) {
                        prefs.edit()
                            .putString(KEY_LAST_REPLY_ID, latest.id)
                            .putBoolean(KEY_HAS_NEW_REPLY, true)
                            .apply()
                        hasNew = true
                    } else {
                        // 如果 ID 一样，读取当前的红点状态 (可能用户还没点进去看)
                        hasNew = prefs.getBoolean(KEY_HAS_NEW_REPLY, false)
                    }
                }
            }
        }
        return hasNew
    }

    /**
     * 计算请求签名
     * 算法: SHA256(deviceId + timestamp + secretKey)
     */
    private fun calculateSignature(deviceId: String, timestamp: String): String {
        val secretKey = BuildConfig.FEEDBACK_SIGNING_SECRET
        if (secretKey.isBlank()) {
            Log.w(TAG, "Feedback signing secret is missing; signed history requests are disabled")
            return ""
        }
        val data = deviceId + timestamp + secretKey
        
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(data.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "签名计算失败", e)
            ""
        }
    }
}

package com.tyust.course.network

import android.content.Context
import android.util.Log
import com.tyust.course.activation.ActivationManager
import com.tyust.course.manager.UserManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 用户反馈管理器
 */
object FeedbackManager {
    private const val TAG = "FeedbackManager"
    
    // 反馈接口地址
    // 生产环境: https://www.znj2006.cn/api/feedback
    // 开发环境: http://10.0.2.2:3000/api/feedback (模拟器) 或 http://YOUR_IP:3000/api/feedback (真机)
    private const val FEEDBACK_API_URL = "https://www.znj2006.cn/api/feedback"
    
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
}

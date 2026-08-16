package com.tyust.course.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.network.CourseApiClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/**
 * 全局 Cookie 有效性定期检查器
 * 每隔固定间隔请求学生信息页，检测 Cookie 是否过期
 * 过期时发送 ACTION_COOKIE_EXPIRED 广播
 */
object CookieWatchdog {
    private const val TAG = "CookieWatchdog"
    private const val DEFAULT_INTERVAL_MS = 5 * 60 * 1000L // 5 分钟

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var intervalMs = DEFAULT_INTERVAL_MS
    private var context: Context? = null
    private var watchedSchool: SchoolConfig? = null
    private var watchedAccountStorageKey: String = ""

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val userManager = UserManager.getInstance()
            val school = watchedSchool ?: userManager.currentSchool
            val requestAccountStorageKey = watchedAccountStorageKey.ifBlank { userManager.currentAccountStorageKey }
            if (school != null) {
                check(school, requestAccountStorageKey)
            } else {
                Log.w(TAG, "No school configured, skipping check")
                scheduleNext()
            }
        }
    }

    @JvmStatic
    fun start(ctx: Context, intervalMs: Long = DEFAULT_INTERVAL_MS) {
        if (running) return
        this.context = ctx.applicationContext
        this.intervalMs = intervalMs
        val userManager = UserManager.getInstance()
        this.watchedSchool = userManager.currentSchool
        this.watchedAccountStorageKey = userManager.currentAccountStorageKey
        this.running = true
        Log.d(TAG, "Watchdog started, interval=${intervalMs}ms")
        // 首次检查延迟 30 秒（避免刚登录就检查）
        handler.postDelayed(checkRunnable, 30_000L)
    }

    @JvmStatic
    fun stop() {
        running = false
        handler.removeCallbacks(checkRunnable)
        watchedSchool = null
        watchedAccountStorageKey = ""
        Log.d(TAG, "Watchdog stopped")
    }

    private fun check(school: SchoolConfig, requestAccountStorageKey: String) {
        Log.d(TAG, "Checking cookie validity...")
        CourseApiClient.getInstance().validateCookie(school, requestAccountStorageKey, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Check failed (network error): ${e.message}")
                scheduleNext()
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val html = response.body?.string() ?: ""
                    val isExpired = html.contains("用户登录") ||
                            html.contains("登 录") ||
                            html.contains("slogin.html") ||
                            html.contains("notLogin") ||
                            html.contains("name=\"yhm\"")

                    if (isExpired) {
                        Log.w(TAG, "Cookie expired, trying silent renewal first")
                        val ctx = context
                        if (ctx != null && SessionRenewer.canRenew()) {
                            SessionRenewer.renew(ctx) { renewed ->
                                if (renewed) {
                                    // 续期成功就继续巡检 —— 原先这里把 running 永久关掉，
                                    // 一次过期之后整个看门狗就再也不工作了
                                    Log.d(TAG, "Cookie renewed, keep watching")
                                    scheduleNext()
                                } else {
                                    Log.e(TAG, "Renewal failed, notifying expiry")
                                    CourseApiClient.getInstance().notifyCookieExpired(requestAccountStorageKey)
                                    running = false
                                    handler.removeCallbacks(checkRunnable)
                                }
                            }
                        } else {
                            Log.e(TAG, "Cookie expired and cannot renew, notifying expiry")
                            CourseApiClient.getInstance().notifyCookieExpired(requestAccountStorageKey)
                            running = false
                        }
                        return
                    }
                    Log.d(TAG, "Cookie valid")
                } catch (e: Exception) {
                    Log.w(TAG, "Check response error: ${e.message}")
                }
                scheduleNext()
            }
        })
    }

    private fun scheduleNext() {
        if (running) {
            handler.postDelayed(checkRunnable, intervalMs)
        }
    }
}

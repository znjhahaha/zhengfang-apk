package com.tyust.course.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tyust.course.login.PasswordLoginCallback
import com.tyust.course.login.PasswordLoginGatewayFactory
import com.tyust.course.manager.UserManager
import com.tyust.course.network.CourseApiClient

/**
 * 会话续期的唯一入口。
 *
 * 登录状态失效有四个发现者：OkHttp 拦截器（`CourseApiClient.notifyCookieExpired`）、
 * [CookieWatchdog] 的轮询、成绩页的请求失败分支、以及抢课服务的飞行前检查。
 * 原先只有成绩页实现了自动重登，其余三条直接广播"已过期"，于是用户先看到的
 * 永远是横幅而不是"已经悄悄续上了"。这里把那段逻辑抽出来给四条路径共用。
 *
 * 两条必须守住的规则：
 * 1. **单飞**。四条路径可能在同一秒撞上 401，绝不能对教务系统连开四次登录
 *    —— 那会触发验证码，甚至把账号锁掉。同一时刻只允许一次登录在飞，
 *    其余调用方排进 [waiters] 等同一个结果。
 * 2. **账号切换护栏**。续期途中用户切了账号，这次结果就必须丢弃，
 *    否则会把 A 的新 Cookie 写到 B 的运行态上。
 */
object SessionRenewer {

    private const val TAG = "SessionRenewer"

    /**
     * 一次失败后的静默期。四条路径是串行发现失效的（成绩页先失败 → 它自己广播 →
     * MainActivity 收到广播），没有这个闸门就会对同一次失效连续登录两三遍。
     * 失败通常是密码变了或要验证码，60 秒内再试也不会有别的结果。
     */
    private const val FAILURE_COOLDOWN_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var inFlight = false
    private var lastFailureAt = 0L
    private val waiters = mutableListOf<(Boolean) -> Unit>()

    /** 当前账号是否具备静默续期的条件（密码模式 + 有已存密码 + 已选学校 + 不在静默期）。 */
    @JvmStatic
    fun canRenew(): Boolean {
        val since = android.os.SystemClock.elapsedRealtime() - lastFailureAt
        if (lastFailureAt != 0L && since < FAILURE_COOLDOWN_MS) return false
        return UserManager.getInstance().canAutoRelogin()
    }

    /**
     * 尝试静默换一张新 Cookie。[onDone] 一定会在主线程被调用一次。
     *
     * 成功时 Cookie 已经写进 [UserManager] 与 [CourseApiClient]，调用方可以直接重试请求。
     */
    @JvmStatic
    fun renew(context: Context, onDone: (Boolean) -> Unit) {
        val userManager = UserManager.getInstance()
        if (!canRenew()) {
            handler.post { onDone(false) }
            return
        }

        synchronized(lock) {
            waiters += onDone
            if (inFlight) {
                Log.d(TAG, "已有续期在进行，本次并入等待队列 (waiters=${waiters.size})")
                return
            }
            inFlight = true
        }

        val school = userManager.currentSchool
        if (school == null) {
            finish(false)
            return
        }
        val requestStorageKey = userManager.currentAccountStorageKey
        val requestSchoolId = school.id
        val requestUsername = userManager.username
        val requestPassword = userManager.accountPassword

        fun isRequestCurrent(): Boolean {
            val current = UserManager.getInstance()
            return current.currentAccountStorageKey == requestStorageKey &&
                current.currentSchool?.id == requestSchoolId &&
                current.username == requestUsername
        }

        Log.d(TAG, "开始静默续期: account=$requestStorageKey")
        val gateway = PasswordLoginGatewayFactory.create(school)
        gateway.login(school, requestUsername, requestPassword, object : PasswordLoginCallback {
            override fun onSuccess(cookie: String) {
                gateway.clearSensitiveState()
                handler.post {
                    if (!isRequestCurrent()) {
                        Log.d(TAG, "账号已切换，丢弃本次续期结果")
                        finish(false)
                        return@post
                    }
                    UserManager.getInstance().saveCookie(cookie)
                    CourseApiClient.getInstance().setCookie(school.baseUrl, cookie)
                    UserManager.getInstance().isLoggedIn = true
                    Log.d(TAG, "续期成功")
                    finish(true)
                }
            }

            override fun onCaptchaRequired(imageBytes: ByteArray) {
                gateway.clearSensitiveState()
                Log.d(TAG, "续期需要验证码，转人工")
                finish(false)
            }

            override fun onCaptchaInvalid() {
                gateway.clearSensitiveState()
                Log.d(TAG, "续期验证码校验失败")
                finish(false)
            }

            override fun onInvalidCredentials() {
                gateway.clearSensitiveState()
                Log.d(TAG, "续期失败：密码已失效")
                finish(false)
            }

            override fun onError(message: String) {
                gateway.clearSensitiveState()
                Log.w(TAG, "续期失败: $message")
                finish(false)
            }
        })
    }

    private fun finish(success: Boolean) {
        val pending: List<(Boolean) -> Unit>
        synchronized(lock) {
            pending = waiters.toList()
            waiters.clear()
            inFlight = false
            lastFailureAt = if (success) 0L else android.os.SystemClock.elapsedRealtime()
        }
        handler.post { pending.forEach { it(success) } }
    }
}

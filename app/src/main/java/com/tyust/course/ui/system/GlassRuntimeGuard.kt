package com.tyust.course.ui.system

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.tyust.course.BuildConfig

/**
 * Backdrop 兼容性由真实运行结果决定，不按设备品牌预先禁用。
 * 当前版本发生 crash、native crash 或 ANR 后，下次启动回退到 Material 实现；升级后重新尝试。
 */
object GlassRuntimeGuard {
    private const val TAG = "GlassRuntimeGuard"
    private const val PREFS_NAME = "glass_runtime_guard"
    private const val KEY_SESSION_USED_GLASS = "session_used_glass"
    private const val KEY_DISABLED_VERSION = "disabled_version"

    @Volatile
    private var initialized = false

    @Volatile
    private var enabled = true

    @Volatile
    private var sessionMarked = false

    private var appContext: Context? = null

    fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        sessionMarked = false

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enabled = false
            initialized = true
            return
        }

        val preferences = applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val previousSessionUsedGlass = preferences.getBoolean(
            KEY_SESSION_USED_GLASS,
            false
        )
        val disabledVersion = preferences.getInt(KEY_DISABLED_VERSION, -1)
        val previousProcessFailed = previousSessionUsedGlass &&
            didPreviousProcessFail(applicationContext)

        if (previousProcessFailed) {
            preferences.edit()
                .putInt(KEY_DISABLED_VERSION, BuildConfig.VERSION_CODE)
                .remove(KEY_SESSION_USED_GLASS)
                .apply()
            Log.w(TAG, "Backdrop disabled for this version after a failed process exit")
        } else {
            preferences.edit().remove(KEY_SESSION_USED_GLASS).apply()
        }

        enabled = !previousProcessFailed && disabledVersion != BuildConfig.VERSION_CODE
        initialized = true
    }

    fun isBackdropEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (initialized && !enabled) return false

        markGlassSession()
        return true
    }

    private fun markGlassSession() {
        if (sessionMarked) return

        synchronized(this) {
            if (sessionMarked) return
            val context = appContext ?: return
            sessionMarked = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SESSION_USED_GLASS, true)
                .commit()
        }
    }

    private fun didPreviousProcessFail(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        return runCatching {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val previousExit = activityManager
                .getHistoricalProcessExitReasons(null, 0, 1)
                .firstOrNull()
                ?: return@runCatching false

            previousExit.reason in failedExitReasons
        }.getOrElse { error ->
            Log.w(TAG, "Unable to inspect the previous process exit", error)
            false
        }
    }

    private val failedExitReasons = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR
    )
}
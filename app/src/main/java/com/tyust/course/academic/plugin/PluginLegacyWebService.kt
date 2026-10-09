package com.tyust.course.academic.plugin

import android.app.Service
import android.content.Intent
import android.os.*
import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Runs only in :plugin_legacy_web. No main-process WebView or account files are opened here. */
internal object LegacyWebRuntime {
    data class Page(val ticket: String, val policy: PluginWebPolicy, val title: String, val expires: Long)
    var identity: LegacyWebIdentity? = null
    val profile: String? get() = identity?.profile
    var page: Page? = null
    var activity: PluginLegacyWebActivity? = null
    fun hasPage() = activity != null || page?.let { it.expires > SystemClock.elapsedRealtime() } == true
}

class PluginLegacyWebService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(PluginLegacyWebSessions.DESCRIPTOR)
            if (getCallingUid() != applicationInfo.uid) throw SecurityException("Private browser service")
            val encoded = data.readString().orEmpty()
            val result = try {
                if (encoded.toByteArray().size > 400_000) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "网页请求过大")
                val request = JSONObject(encoded)
                val work = FutureTask { handle(request) }
                main.post(work)
                try { JSONObject().put("ok", true).put("data", work.get(10, TimeUnit.SECONDS)) }
                catch (e: Exception) { work.cancel(false); throw e.cause ?: e }
            } catch (e: Throwable) {
                JSONObject().put("ok", false).put("code", (e as? PluginException)?.code?.name ?: "UNSUPPORTED")
                    .put("message", e.message ?: "兼容网页未完成请求")
            }
            reply?.writeNoException()
            reply?.writeString(result.toString())
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onUnbind(intent: Intent?): Boolean { shutdown(); return false }

    private fun handle(request: JSONObject): JSONObject {
        val profile = request.getString("profile")
        val action = request.getString("action")
        val input = request.getJSONObject("input")
        if (!LegacyWebIdentity.validProfile(profile)) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "无效网页账号")
        if (action == "configure") {
            if (Build.VERSION.SDK_INT < 28) throw PluginException(PluginErrorCode.UNSUPPORTED, "兼容网页需要 Android 9 或更新版本")
            val identity = LegacyWebIdentity(profile, PluginJson.strings(input.getJSONArray("origins")).toSet())
            if (LegacyWebRuntime.identity != null && LegacyWebRuntime.identity != identity)
                throw PluginException(PluginErrorCode.CONFLICT, "另一个网页账号尚未关闭")
            if (LegacyWebRuntime.profile == null) {
                // This must precede every WebView/CookieManager feature probe in this process.
                WebView.setDataDirectorySuffix("legacy_$profile")
                LegacyWebRuntime.identity = identity
            }
            CookieManager.getInstance().setAcceptCookie(true)
            return JSONObject()
        }
        val identity = LegacyWebRuntime.identity ?: throw PluginException(PluginErrorCode.NOT_OPEN, "网页账号尚未打开")
        identity.requireProfile(profile)
        val cookies = CookieManager.getInstance()
        when (action) {
            "read", "write" -> {
                val url = input.getString("url")
                if (!identity.accepts(url)) throw PluginException(PluginErrorCode.UNTRUSTED_URL, "Cookie 来源未声明")
                if (action == "read") return JSONObject().put("header", cookies.getCookie(url).orEmpty())
                cookies.setCookie(url, input.getString("cookie")); cookies.flush()
            }
            "flush" -> cookies.flush()
            "page" -> {
                if (LegacyWebRuntime.hasPage()) throw PluginException(PluginErrorCode.CONFLICT, "请先关闭已打开的兼容网页")
                val origin = input.getString("origin")
                val policy = identity.browser(origin, input.getJSONObject("declaration"), input.getJSONObject("params"))
                val ticket = UUID.randomUUID().toString()
                LegacyWebRuntime.page = LegacyWebRuntime.Page(ticket, policy, input.optString("title"), SystemClock.elapsedRealtime() + 60_000)
                return JSONObject().put("ticket", ticket)
            }
            "shutdown" -> {
                if (LegacyWebRuntime.hasPage() && !input.optBoolean("force")) throw PluginException(PluginErrorCode.CONFLICT, "请先关闭另一个服务账号的兼容网页")
                shutdown()
            }
            else -> throw PluginException(PluginErrorCode.UNSUPPORTED, "兼容网页不支持此操作")
        }
        return JSONObject()
    }

    private fun shutdown() {
        LegacyWebRuntime.page = null
        LegacyWebRuntime.activity?.finish()
        if (LegacyWebRuntime.profile != null) runCatching { CookieManager.getInstance().flush() }
        stopSelf()
        // A new account requires a fresh WebView process to configure its directory.
        main.postDelayed({ if (ApplicationProcess.isLegacy()) Process.killProcess(Process.myPid()) }, 150)
    }

    private object ApplicationProcess {
        fun isLegacy() = Build.VERSION.SDK_INT >= 28 && android.app.Application.getProcessName().endsWith(":plugin_legacy_web")
    }
}

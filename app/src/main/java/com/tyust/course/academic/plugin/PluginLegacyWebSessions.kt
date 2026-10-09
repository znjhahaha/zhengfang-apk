package com.tyust.course.academic.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.tyust.course.academic.AcademicSession
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** A legacy WebView owns a separate process and data directory, never the app's default profile. */
internal object PluginLegacyWebSessions {
    const val DESCRIPTOR = "com.tyust.course.plugin.legacy.web.v1"
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var connection: Connection? = null

    private class Connection(val app: Context, val profile: String, val pluginId: String, val origins: Set<String>) : ServiceConnection {
        val ready = CompletableDeferred<IBinder>()
        val died = CompletableDeferred<Unit>()
        var binder: IBinder? = null
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            runCatching { service.linkToDeath({ died.complete(Unit) }, 0) }
            ready.complete(service)
        }
        override fun onServiceDisconnected(name: ComponentName) { died.complete(Unit) }
        override fun onBindingDied(name: ComponentName) { died.complete(Unit) }
        override fun onNullBinding(name: ComponentName) {
            ready.completeExceptionally(PluginException(PluginErrorCode.UNSUPPORTED, "兼容网页进程无法启动"))
        }
    }

    fun enabled(app: Context, profile: String): Boolean =
        app.getSharedPreferences("plugin-legacy-web", Context.MODE_PRIVATE).getBoolean(profile, false)

    private suspend fun connect(app: Context, profile: String, pluginId: String, origins: Set<String>): Connection = mutex.withLock {
        connection?.takeIf { it.profile == profile && it.origins == origins && it.binder?.isBinderAlive == true && !it.died.isCompleted }?.let { return@withLock it }
        connection?.let { old ->
            // A different account may read native data while a web page is open. It must not
            // replace that page's storage. Account revocation explicitly closes it instead.
            if (old.binder?.isBinderAlive == true) {
                transact(old, "shutdown", JSONObject())
                withTimeout(5000) { old.died.await() }
            }
            withContext(Dispatchers.Main) { runCatching { old.app.unbindService(old) } }
            connection = null
        }
        val next = Connection(app.applicationContext, profile, pluginId, origins)
        try {
            withContext(Dispatchers.Main) {
                if (!next.app.bindService(Intent(next.app, PluginLegacyWebService::class.java), next, Context.BIND_AUTO_CREATE))
                    throw PluginException(PluginErrorCode.UNSUPPORTED, "无法连接兼容网页进程")
            }
            withTimeout(10_000) { next.ready.await() }
            val config = JSONObject().put("origins", JSONArray(origins.toList())).put("pluginId", pluginId)
            transact(next, "configure", config)
            connection = next
            next
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.Main) { runCatching { next.app.unbindService(next) } }
            throw e
        }
    }

    private fun transact(owner: Connection, action: String, input: JSONObject): JSONObject {
        val binder = owner.binder ?: throw PluginException(PluginErrorCode.NOT_OPEN, "兼容网页进程已退出，请重试")
        val request = JSONObject().put("profile", owner.profile).put("action", action).put("input", input).toString()
        if (request.toByteArray().size > 400_000) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "网页会话数据过大")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(request)
            if (!binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
                throw PluginException(PluginErrorCode.UNSUPPORTED, "兼容网页进程不支持此请求")
            reply.readException()
            val result = JSONObject(reply.readString() ?: "{}")
            if (!result.optBoolean("ok")) throw PluginException(
                runCatching { PluginErrorCode.valueOf(result.optString("code")) }.getOrDefault(PluginErrorCode.UNSUPPORTED),
                result.optString("message", "兼容网页请求未完成"))
            return result.optJSONObject("data") ?: JSONObject()
        } finally { data.recycle(); reply.recycle() }
    }

    private fun cookieCall(app: Context, pkg: PluginPackage, profile: String, origins: Set<String>, active: () -> Boolean,
        action: String, input: JSONObject): JSONObject {
        if (!active()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "服务账号已改变")
        // CookieJar runs on the network dispatcher. Do not block the UI waiting for a bind callback.
        if (Looper.myLooper() == Looper.getMainLooper()) throw PluginException(PluginErrorCode.UNSUPPORTED, "网页会话读取需要网络线程")
        return runBlocking {
            val owner = connect(app, profile, pkg.manifest.id, origins)
            if (!active()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "服务账号已改变")
            val result = transact(owner, action, input)
            if (!active()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "服务账号已改变")
            result
        }
    }

    fun header(app: Context, pkg: PluginPackage, profile: String, origins: Set<String>, active: () -> Boolean, url: String): String =
        cookieCall(app, pkg, profile, origins, active, "read", JSONObject().put("url", url)).optString("header")

    fun set(app: Context, pkg: PluginPackage, profile: String, origins: Set<String>, active: () -> Boolean, url: String, value: String) {
        cookieCall(app, pkg, profile, origins, active, "write", JSONObject().put("url", url).put("cookie", value))
    }

    suspend fun open(app: Context, pkg: PluginPackage, session: AcademicSession, declaration: JSONObject,
        params: JSONObject, active: () -> Boolean): Intent = withContext(Dispatchers.IO) {
        val accounts = PluginServiceAccounts(app)
        val serverId = declaration.getString("serverId")
        val server = accounts.server(pkg, serverId)
        val origins = PluginJson.strings(server.optJSONArray("cookieOrigins")).toSet() + server.getString("origin")
        val policy = PluginWebPolicy(server.getString("origin"), declaration, params)
        if (!policy.browser || android.os.Build.VERSION.SDK_INT < 28)
            throw PluginException(PluginErrorCode.UNSUPPORTED, "此网页需要新版 WebView 的独立账号能力")
        val profile = accounts.profile(pkg, serverId, session.key.accountKey)
        val current = { active() && accounts.current(pkg, session) }
        if (!current()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "服务账号已改变")
        val owner = connect(app, profile, pkg.manifest.id, origins)
        val vault = NativePluginVault(app, PluginStorageScope.session(session, pkg.manifest.id, !pkg.official))
        NativeWebCookieStore(vault, origins, current).migrate(
            { url, cookie -> transact(owner, "write", JSONObject().put("url", url).put("cookie", cookie)) },
            {
                transact(owner, "flush", JSONObject())
                // Persist this before deleting the old native snapshot. A restarted host will
                // continue to use WebKit's original cookie domain/path/expiry metadata.
                check(app.getSharedPreferences("plugin-legacy-web", Context.MODE_PRIVATE).edit().putBoolean(profile, true).commit())
            })
        if (!current()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "服务账号已改变")
        check(app.getSharedPreferences("plugin-legacy-web", Context.MODE_PRIVATE).edit().putBoolean(profile, true).commit())
        val page = transact(owner, "page", JSONObject().put("origin", server.getString("origin"))
            .put("declaration", declaration).put("params", params).put("title", pkg.manifest.name))
        Intent(app, PluginLegacyWebActivity::class.java).putExtra("ticket", page.getString("ticket"))
    }

    /** Called before changing an account generation or disabling its plugin. */
    fun revoke(pluginId: String) {
        val old = connection?.takeIf { it.pluginId == pluginId } ?: return
        scope.launch {
            mutex.withLock {
                if (connection !== old) return@withLock
                withContext(Dispatchers.IO) { runCatching { transact(old, "shutdown", JSONObject().put("force", true)) } }
                runCatching { withTimeout(5000) { old.died.await() } }
                runCatching { old.app.unbindService(old) }
                connection = null
            }
        }
    }

    fun retire(app: Context, profiles: Set<String>) {
        val prefs = app.getSharedPreferences("plugin-legacy-web", Context.MODE_PRIVATE)
        profiles.forEach { profile ->
            if (!profile.matches(Regex("plugin_[a-f0-9]{64}"))) return@forEach
            val directory = java.io.File(app.applicationInfo.dataDir, "app_webview_legacy_$profile")
            if (!prefs.contains(profile) && !directory.exists() && connection?.profile != profile) return@forEach
            check(prefs.edit().remove(profile).commit())
            // The profile is never reused after logout. Cleanup can wait for its process to exit.
            scope.launch(Dispatchers.IO) {
                mutex.withLock {
                    connection?.takeIf { it.profile == profile }?.let { old ->
                        runCatching { transact(old, "shutdown", JSONObject().put("force", true)) }
                        runCatching { withTimeout(5000) { old.died.await() } }
                        if (!old.died.isCompleted) return@withLock
                        withContext(Dispatchers.Main) { runCatching { old.app.unbindService(old) } }
                        connection = null
                    }
                    if (directory.canonicalFile.parentFile == java.io.File(app.applicationInfo.dataDir).canonicalFile) directory.deleteRecursively()
                }
            }
        }
    }
}

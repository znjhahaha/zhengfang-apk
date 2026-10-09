package com.tyust.course.academic.plugin

import android.content.*
import android.os.*
import com.tyust.course.academic.AcademicSession
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Main-process owner of a private Gecko process. Account generations invalidate every handle. */
internal object PluginEmbeddedBrowser {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    @Volatile private var connection: Connection? = null
    fun enabled(pkg: PluginPackage, serverId: String) = PluginJson.objects(pkg.manifest.json.optJSONArray("servers") ?: JSONArray())
        .any { it.optString("id") == serverId && it.optJSONObject("browser")?.optString("engine") == "embedded" }
    fun enabled(pkg: PluginPackage, owner: AcademicSession) = enabled(pkg, owner.key.schoolId.removePrefix("service:${pkg.manifest.id}:"))
    fun requireSupport() { if (Build.VERSION.SDK_INT < 26) throw PluginException(PluginErrorCode.UNSUPPORTED, "内置浏览器需要 Android 8.0 或更新版本，App 其他功能可继续使用") }

    private class Connection(val app: Context, val pkg: PluginPackage, val session: AcademicSession, val profile: String, val server: JSONObject) : ServiceConnection {
        val ready = CompletableDeferred<IBinder>(); val died = CompletableDeferred<Unit>()
        val origins = PluginJson.strings(server.optJSONArray("cookieOrigins")).toSet() + server.getString("origin")
        val namespace = PluginStorageScope.session(session, pkg.manifest.id, !pkg.official)
        @Volatile var binder: IBinder? = null
        @Volatile var script: Script? = null
        val scriptLock = Mutex()
        var migrationMessage = ""
        fun valid() = !died.isCompleted && !session.retired && PluginServiceAccounts(app).current(pkg, session) &&
            AcademicProviderRegistry.isEnabled(pkg.manifest.id) && AcademicProviderRegistry.isCurrentPackage(pkg.manifest.id, pkg.digest)
        fun check() { if (!valid()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "浏览器账号或插件已改变") }
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            service.linkToDeath({ disconnected() }, 0)
            ready.complete(service)
        }
        fun disconnected() {
            died.complete(Unit)
            scope.launch { script?.let { stopScript(this@Connection, "interrupted", remote = false) } }
        }
        override fun onServiceDisconnected(name: ComponentName) = disconnected()
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(PluginException(PluginErrorCode.UNSUPPORTED, "内置浏览器进程无法启动")) }
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(PluginBrowserWire.DESCRIPTOR)
                if (getCallingUid() != app.applicationInfo.uid) throw SecurityException("Private browser callback")
                val result = try {
                    val event = PluginBrowserWire.read(data)
                    val value = runBlocking { withTimeout(125_000) { receive(this@Connection, event) } }
                    JSONObject().put("ok", true).put("data", value)
                } catch (e: Throwable) { PluginBrowserWire.failure(e) }
                reply?.writeNoException(); if (reply != null) PluginBrowserWire.write(reply, result)
                return true
            }
        }
    }
    private class Script(val owner: Connection, val handle: String, val store: PluginUserscriptStore, val selected: JSONObject, val url: String) {
        val policy get() = store.policy
        val operations = java.util.concurrent.ConcurrentHashMap<String, PluginOperation>()
        val documents = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
        val nativePrompts = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
        val openedPages = java.util.concurrent.ConcurrentHashMap<String, String>()
        val lease = PluginVersionLeases.acquire(owner.pkg.manifest.id)
        val vault = NativePluginVault(owner.app, owner.namespace, guard = owner::check)
        @Volatile var closed = false
        @Volatile var status = "loading"
        @Volatile var message = "正在加载内置浏览器与原脚本"
        @Volatile var logs = ""
        @Volatile var available = false
        @Volatile var lastUrl = url
        @Volatile var startRequested = true
        var startJob: Job? = null
        var preparationJob: Job? = null
        @Volatile var preparationAt = SystemClock.elapsedRealtime()
        val snapshots = java.util.concurrent.atomic.AtomicLong()
        var minute = 0L; var requests = 0
        fun save() { vault.put("userscript:${policy.id}:last", JSONObject().put("status", status).put("url", lastUrl).put("message", message).put("version", selected.getString("version"))) }
        fun describe(base: JSONObject): JSONObject {
            val settings = base.optJSONObject("settings") ?: JSONObject()
            val settingsByKey = PluginJson.objects(policy.declaration.getJSONObject("adapter").getJSONArray("settings")).associateBy { it.getString("key") }
            documents.values.filter { it.optBoolean("available") }.sortedBy { if (it.optBoolean("running")) 1 else 0 }.forEach { document ->
                val visible = document.optJSONObject("settings") ?: JSONObject()
                for (key in visible.keys()) settingsByKey[key]?.let { setting ->
                    PluginUserscriptStore.normalizedSetting(setting, visible.opt(key))?.let { settings.put(key, it) }
                }
            }
            return base.put("settings", settings).put("handle", handle).put("status", status).put("message", message).put("logs", logs)
            .put("interaction", nativePrompts.toSortedMap().values.firstOrNull() ?: documents.toSortedMap().values.firstNotNullOfOrNull { it.optJSONObject("interaction") })
            .put("actions", JSONArray(documents.values.flatMap { PluginJson.objects(it.optJSONArray("actions") ?: JSONArray()) }.distinctBy { it.optString("id") }))
            .put("progress", documents.values.firstNotNullOfOrNull { it.optJSONObject("progress") })
            .put("revision", snapshots.incrementAndGet())
            .put("lastUrl", lastUrl).put("runningVersion", selected.getString("version")).put("adapterAvailable", available)
            .put("startPending", startRequested || startJob?.isActive == true)
            .put("adapterVersion", policy.declaration.getJSONObject("adapter").getString("version")).put("engine", "GeckoView 157")
        }
        fun updateDocuments() = synchronized(this) {
            val states = documents.values.toList()
            val resolved = PluginScriptPresentation.resolve(states.map {
                PluginScriptPresentation.Document(it.optString("stage"), it.optBoolean("available"), it.optBoolean("running"))
            } + if (nativePrompts.isNotEmpty()) listOf(PluginScriptPresentation.Document("interaction", false, false)) else emptyList(), SystemClock.elapsedRealtime() - preparationAt)
            available = resolved.available
            status = resolved.status
            logs = states.joinToString("\n") { store.redact(it.optString("logs")) }.takeLast(12000)
            message = resolved.message
            if (status in setOf("error", "unavailable", "running")) startRequested = false
        }
    }
    private suspend fun rpc(owner: Connection, action: String, input: JSONObject = JSONObject(), check: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        if (check) owner.check()
        val binder = owner.binder ?: throw PluginException(PluginErrorCode.NOT_OPEN, "浏览器进程已关闭")
        val result = PluginBrowserWire.call(binder, JSONObject().put("profile", owner.profile).put("action", action).put("input", input), if (action == "configure") owner.callback else null)
        if (check) owner.check()
        result
    }
    private suspend fun disconnect(owner: Connection) {
        stopScript(owner, "interrupted")
        if (owner.binder?.isBinderAlive == true) {
            runCatching { rpc(owner, "shutdown", check = false) }
            withTimeout(10_000) { owner.died.await() }
        }
        withContext(Dispatchers.Main) { runCatching { owner.app.unbindService(owner) } }
        owner.session.retire()
        if (connection === owner) connection = null
    }
    private suspend fun connect(app: Context, pkg: PluginPackage, owner: AcademicSession): Connection = mutex.withLock {
        requireSupport()
        val accounts = PluginServiceAccounts(app)
        if (!accounts.current(pkg, owner)) throw PluginException(PluginErrorCode.STALE_CONTEXT, "服务账号已改变")
        val serverId = owner.key.schoolId.removePrefix("service:${pkg.manifest.id}:")
        if (!enabled(pkg, serverId)) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "服务器未声明嵌入浏览器")
        val profile = accounts.profile(pkg, serverId, owner.key.accountKey)
        connection?.takeIf { it.profile == profile && it.pkg.digest == pkg.digest && it.binder?.isBinderAlive == true && it.valid() }?.let { return@withLock it }
        connection?.let { disconnect(it) }
        val session = accounts.session(pkg, serverId)
        if (session.key.accountKey != owner.key.accountKey) { session.retire(); throw PluginException(PluginErrorCode.STALE_CONTEXT, "服务账号已切换") }
        val next = Connection(app.applicationContext, pkg, session, profile, accounts.server(pkg, serverId))
        try {
            withContext(Dispatchers.Main) {
                if (!next.app.bindService(Intent(next.app, PluginEmbeddedBrowserService::class.java), next, Context.BIND_AUTO_CREATE)) throw PluginException(PluginErrorCode.UNSUPPORTED, "无法连接内置浏览器")
            }
            withTimeout(30_000) { next.ready.await() }
            connection = next
            rpc(next, "configure", JSONObject().put("origins", JSONArray(next.origins.toList())).put("network", JSONArray(pkg.manifest.network)).put("title", pkg.manifest.name))
            migrate(next)
            next.check(); next
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { disconnect(next) } }
            throw e
        }
    }
    private suspend fun migrate(owner: Connection) = withContext(Dispatchers.IO) {
        val prefs = owner.app.getSharedPreferences("plugin-embedded-migration", Context.MODE_PRIVATE)
        if (prefs.getBoolean(owner.profile, false)) return@withContext
        val native = NativeWebCookieStore(NativePluginVault(owner.app, owner.namespace, guard = owner::check), owner.origins, owner::valid)
        val snapshot = native.migrationSnapshot()
        if (snapshot != null) {
            try {
                for (entry in PluginJson.objects(snapshot.getJSONArray("cookies"))) {
                    val url = entry.getString("url"); val value = entry.getString("value")
                    rpc(owner, "cookies.write", JSONObject().put("url", url).put("cookie", value))
                    val cookie = okhttp3.Cookie.parse(url.toHttpUrlOrNull()!!, value) ?: continue
                    if (cookie.expiresAt > System.currentTimeMillis() && rpc(owner, "cookies.read", JSONObject().put("url", url)).optString("header").split(';').none { it.trim() == "${cookie.name}=${cookie.value}" })
                        throw PluginException(PluginErrorCode.SESSION_EXPIRED, "旧登录状态迁移尚未确认")
                }
                native.finishMigration(snapshot) { check(prefs.edit().putBoolean(owner.profile, true).commit()) }
            } catch (_: Exception) { owner.migrationMessage = "旧登录数据已保留，迁移未确认；请在内置浏览器重新登录" }
        } else {
            // WebView's header-only API cannot faithfully export path/expiry/HttpOnly metadata.
            // Keep that original profile intact, and do not mark an unverified migration complete.
            val legacy = PluginLegacyWebSessions.enabled(owner.app, owner.profile)
            val modern = withContext(Dispatchers.Main) { runCatching {
                androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE) && androidx.webkit.ProfileStore.getInstance().allProfileNames.contains(owner.profile)
            }.getOrDefault(false) }
            if (legacy || modern) owner.migrationMessage = "旧网页登录数据已保留；内置浏览器请重新登录一次"
            else check(prefs.edit().putBoolean(owner.profile, true).commit())
        }
    }
    fun cookie(app: Context, pkg: PluginPackage, owner: AcademicSession, active: () -> Boolean, action: String, url: String, value: String = ""): String {
        check(Looper.myLooper() != Looper.getMainLooper())
        if (!active()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "服务账号已改变")
        return runBlocking {
            val connection = connect(app, pkg, owner)
            val result = rpc(connection, "cookies.$action", JSONObject().put("url", url).put("cookie", value))
            if (!active()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "服务账号已改变")
            result.optString("header")
        }
    }
    suspend fun preparePage(app: Context, pkg: PluginPackage, owner: AcademicSession, declaration: JSONObject, params: JSONObject): JSONObject {
        val c = connect(app, pkg, owner)
        val policy = PluginWebPolicy(c.server.getString("origin"), declaration, params)
        if (!policy.browser) throw PluginException(PluginErrorCode.UNSUPPORTED, "嵌入浏览器页面必须声明纯浏览模式")
        val origins = PluginJson.strings(declaration.optJSONObject("web")?.optJSONArray("navigationOrigins")).toSet() + c.server.getString("origin")
        val result = rpc(c, "page.open", JSONObject().put("url", policy.initialUrl).put("origins", JSONArray(origins.toList())))
        result.put("migrationMessage", c.migrationMessage)
        return result
    }
    suspend fun openUrl(app: Context, pkg: PluginPackage, owner: AcademicSession, input: JSONObject): JSONObject {
        val c = connect(app, pkg, owner)
        if (input.getString("serverId") != c.server.getString("id") || c.origins.none { PluginWebGate(it).owns(input.getString("url")) }) throw PluginException(PluginErrorCode.UNTRUSTED_URL, "页面不属于当前声明的服务账号")
        val result = rpc(c, "page.open", JSONObject().put("url", input.getString("url")).put("origins", JSONArray(c.origins.toList())))
        show(app, result.getString("handle")); return result
    }
    private suspend fun show(app: Context, handle: String) = withContext(Dispatchers.Main) {
        app.startActivity(Intent(app, PluginEmbeddedBrowserActivity::class.java).putExtra("handle", handle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    suspend fun pageControl(app: Context, pkg: PluginPackage, owner: AcademicSession, handle: String, close: Boolean): JSONObject {
        val c = connection?.takeIf { it.pkg.manifest.id == pkg.manifest.id && it.namespace == PluginStorageScope.session(owner, pkg.manifest.id, !pkg.official) } ?: throw PluginException(PluginErrorCode.NOT_OPEN, "浏览器会话已关闭")
        return rpc(c, if (close) "page.close" else "page.status", JSONObject().put("handle", handle))
    }
    suspend fun scriptStatus(app: Context, pkg: PluginPackage, owner: AcademicSession, id: String): JSONObject {
        val store = PluginUserscripts.store(app, pkg, owner, id)
        val status = withContext(Dispatchers.IO) { store.publicStatus() }
        val live = connection?.takeIf { it.valid() && it.namespace == PluginStorageScope.session(owner, pkg.manifest.id, !pkg.official) }?.script?.takeIf { !it.closed && it.policy.id == id }
        if (live != null) return live.describe(status)
        val saved = withContext(Dispatchers.IO) { NativePluginVault(app, PluginStorageScope.session(owner, pkg.manifest.id, !pkg.official)).get("userscript:$id:last") }
        return status.put("status", if (saved?.optString("status") in setOf("loading", "entry", "ready", "running", "needs_input", "unavailable", "error")) "interrupted" else saved?.optString("status") ?: "idle")
            .put("lastUrl", saved?.optString("url").orEmpty()).put("message", saved?.optString("message").orEmpty()).put("engine", "GeckoView 157")
    }
    suspend fun startScript(app: Context, pkg: PluginPackage, owner: AcademicSession, id: String, url: String): JSONObject {
        val store = PluginUserscripts.store(app, pkg, owner, id)
        if (!store.policy.executes(url)) throw PluginException(PluginErrorCode.UNTRUSTED_URL, "脚本未声明此课程页面")
        val c = connect(app, pkg, owner)
        return c.scriptLock.withLock {
        c.check()
        stopScriptLocked(c, "stopped")
        if (!store.metadata().optBoolean("subscribed") || System.currentTimeMillis() - store.metadata().optLong("checkedAt") > 86_400_000) store.update(true)
        val (selected, source) = store.activate()
        val live = Script(c, "g" + UUID.randomUUID().toString().replace("-", ""), PluginUserscripts.store(app, pkg, c.session, id), selected, url)
        c.script = live
        try {
            withContext(Dispatchers.Main) { PluginForegroundWork.add(app, live.handle, PluginForegroundWork.Entry(pkg.manifest.id, store.policy.declaration.getString("title")) { reason -> scope.launch { stopScript(c, reason) } }) }
            val metadata = UserscriptMetadata.parse(source)
            rpc(c, "script.start", JSONObject().put("handle", live.handle).put("source", source).put("declaration", live.policy.declaration).put("info", selected)
                .put("values", live.store.settings()).put("runAt", metadata.one("run-at")).put("includeGlobs", JSONArray(metadata.values["match"].orEmpty() + metadata.values["include"].orEmpty())))
            rpc(c, "page.open", JSONObject().put("url", url).put("origins", JSONArray(live.policy.origins().toList())).put("scriptHandle", live.handle))
            withContext(Dispatchers.IO) { live.save() }
            watchPreparation(c, live)
            scope.launch {
                while (c.script === live && c.valid()) {
                    delay(60_000)
                    if (c.script === live && c.valid() && System.currentTimeMillis() - live.store.metadata().optLong("checkedAt") > 86_400_000) runCatching { live.store.update() }
                }
            }
            live.describe(live.store.publicStatus())
        } catch (e: Throwable) { withContext(NonCancellable) { stopScriptLocked(c, "failed") }; throw e }
        }
    }
    suspend fun controlScript(app: Context, pkg: PluginPackage, owner: AcademicSession, input: JSONObject): JSONObject {
        val c = connection?.takeIf { it.namespace == PluginStorageScope.session(owner, pkg.manifest.id, !pkg.official) } ?: throw PluginException(PluginErrorCode.NOT_OPEN, "脚本已停止，请重新核对课程")
        c.check()
        val live = c.script?.takeIf { !it.closed && it.handle == input.getString("handle") } ?: throw PluginException(PluginErrorCode.NOT_OPEN, "脚本句柄已失效")
        when (input.getString("action")) {
            "stop" -> stopScript(c, "stopped")
            "open" -> show(app, rpc(c, "script.page", JSONObject().put("handle", live.handle)).getString("handle"))
            "settings" -> PluginUserscripts.configure(app, pkg, owner, JSONObject().put("scriptId", live.policy.id).put("values", input.optJSONObject("values") ?: JSONObject()))
            "start" -> {
                if (live.startJob?.isActive != true && live.status in setOf("entry", "ready")) {
                    live.startRequested = true
                    advanceStart(c, live)
                }
            }
        }
        return live.describe(live.store.publicStatus())
    }
    suspend fun interactScript(app: Context, pkg: PluginPackage, owner: AcademicSession, input: JSONObject, command: Boolean): JSONObject = withContext(Dispatchers.Main) {
        val c = connection?.takeIf { it.valid() && it.namespace == PluginStorageScope.session(owner, pkg.manifest.id, !pkg.official) }
            ?: throw PluginException(PluginErrorCode.NOT_OPEN, "任务已停止，请重新开始")
        val live = c.script?.takeIf { !it.closed && it.handle == input.getString("handle") }
            ?: throw PluginException(PluginErrorCode.STALE_CONTEXT, "任务已变化")
        val adapter = live.policy.declaration.getJSONObject("adapter")
        if (!command && input.getString("interactionId").startsWith("native:")) {
            val id = input.getString("interactionId")
            val prompt = live.nativePrompts[id] ?: throw PluginException(PluginErrorCode.STALE_CONTEXT, "提示已关闭或更新")
            val action = input.getString("actionId")
            if (action !in setOf("confirm", "cancel")) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "未知提示操作")
            val request = JSONObject().put("handle", live.handle).put("interactionId", id).put("actionId", action)
            if (prompt.optString("kind") == "input" && action == "confirm") {
                val credential = input.optJSONObject("credential") ?: throw PluginException(PluginErrorCode.VALIDATION_FAILED, "请填写提示所需内容")
                val value = withContext(Dispatchers.IO) { live.vault.get("credential:" + credential.getString("handle"))?.optString(credential.getString("field")) }
                    ?: throw PluginException(PluginErrorCode.STALE_CONTEXT, "输入凭据已失效")
                if (value.length > 4096) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "输入内容过长")
                request.put("text", value)
            } else if (input.has("credential")) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "此提示不接收输入")
            val result = rpc(c, "script.interact", request)
            if (!result.optBoolean("accepted")) throw PluginException(PluginErrorCode.STALE_CONTEXT, "提示操作未确认")
            live.nativePrompts.remove(id)
            live.preparationAt = SystemClock.elapsedRealtime()
            live.updateDocuments()
            advanceStart(c, live); watchPreparation(c, live)
            return@withContext live.describe(live.store.publicStatus())
        }
        if (input.has("credential")) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "此操作不接收输入凭据")
        val actions = if (command) adapter.optJSONArray("actions") else {
            val current = live.documents.values.mapNotNull { it.optJSONObject("interaction") }.firstOrNull { it.optString("id") == input.getString("interactionId") }
                ?: throw PluginException(PluginErrorCode.STALE_CONTEXT, "提示已关闭或更新，请确认当前提示")
            PluginJson.objects(adapter.optJSONArray("dialogs") ?: JSONArray()).firstOrNull { it.optString("id") == current.optString("ruleId") }?.optJSONArray("actions")
        }
        val action = PluginJson.objects(actions ?: JSONArray()).firstOrNull { it.optString("id") == input.getString("actionId") }
            ?: throw PluginException(PluginErrorCode.PERMISSION_DENIED, "任务没有声明此操作")
        val result = rpc(c, if (command) "script.action" else "script.interact", input)
        if (!result.optBoolean("accepted")) throw PluginException(PluginErrorCode.STALE_CONTEXT, "操作未确认，请刷新任务状态")
        if (!live.closed) {
            live.startRequested = action.optBoolean("resumeTask")
            live.preparationAt = SystemClock.elapsedRealtime()
            advanceStart(c, live); watchPreparation(c, live)
        }
        live.describe(live.store.publicStatus())
    }
    private fun advanceStart(c: Connection, live: Script) {
        if (live.closed || !live.startRequested || live.startJob?.isActive == true || live.status !in setOf("entry", "ready")) return
        // Never await a browser command inside its own native-message callback.
        // A click can synchronously issue a GM request which calls back into us.
        live.startJob = scope.launch {
            try {
                val result = rpc(c, "script.control", JSONObject().put("handle", live.handle).put("action", "start"))
                if (!live.closed) {
                    val stage = result.optString("stage")
                    if (live.status in setOf("running", "needs_input", "error")) {
                        // A newer page snapshot wins over a late acknowledgement.
                        if (live.status != "needs_input") live.startRequested = false
                    } else if (!result.optBoolean("accepted")) {
                        live.startRequested = stage == "interaction"
                        live.status = if (stage == "interaction") "needs_input" else "unavailable"
                        live.message = result.optString("message", "任务尚未确认启动，请查看任务提示或停止后重试")
                    } else if (stage == "opening") {
                        live.preparationAt = SystemClock.elapsedRealtime()
                        synchronized(live) {
                            // The acknowledgement can arrive before the opening snapshot.
                            // Advance only the clicked document, never a newer task page.
                            live.documents[result.optString("documentId")]?.takeIf { it.optString("stage") == "entry" }?.apply {
                                put("stage", "opening"); put("available", false)
                            }
                            live.updateDocuments()
                        }
                        watchPreparation(c, live)
                    } else {
                        live.startRequested = stage == "interaction"
                        live.status = if (stage == "running") "running" else if (stage == "interaction") "needs_input" else "unavailable"
                        live.message = if (stage == "running") "课程任务正在运行" else "启动操作已发送，正在核对任务状态"
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!live.closed && live.status !in setOf("running", "needs_input")) {
                    live.startRequested = false; live.status = "error"
                    live.message = PluginBrowserWire.failure(e, "script.control").optString("message")
                }
            } finally {
                live.startJob = null
                if (!live.closed) {
                    withContext(Dispatchers.IO) { runCatching { live.save() } }
                    PluginForegroundWork.changed()
                    advanceStart(c, live)
                }
            }
        }
    }
    private fun watchPreparation(c: Connection, live: Script) {
        if (live.closed || live.status != "loading" || live.preparationJob?.isActive == true) return
        live.preparationJob = scope.launch {
            // Recalculate after a navigation/reload resets the deadline. A one-shot
            // timer could otherwise leave a slow task page preparing indefinitely.
            while (!live.closed && c.script === live && c.valid() && live.status == "loading") {
                val remaining = PluginScriptPresentation.PREPARATION_TIMEOUT_MILLIS - (SystemClock.elapsedRealtime() - live.preparationAt)
                delay(remaining.coerceAtLeast(1))
                if (live.closed || c.script !== live || !c.valid() || live.status != "loading") break
                live.updateDocuments()
                withContext(Dispatchers.IO) { runCatching { live.save() } }
                PluginForegroundWork.changed()
            }
        }
    }
    suspend fun refreshScriptSettings(app: Context, pkg: PluginPackage, owner: AcademicSession, store: PluginUserscriptStore, credentialChanged: Boolean = false) {
        val c = connection?.takeIf { it.valid() && it.namespace == PluginStorageScope.session(owner, pkg.manifest.id, !pkg.official) } ?: return
        val live = c.script?.takeIf { !it.closed && it.policy.id == store.policy.id } ?: return
        rpc(c, "script.settings", JSONObject().put("handle", live.handle).put("values", store.settings()).put("apply", store.publicStatus().getJSONObject("settings")))
        if (credentialChanged && live.status in setOf("needs_input", "unavailable")) {
            live.startRequested = true
            live.preparationAt = SystemClock.elapsedRealtime()
            live.status = "loading"; live.message = "密钥已保存，正在重新加载原脚本"
            rpc(c, "script.reload", JSONObject().put("handle", live.handle))
            watchPreparation(c, live)
        }
    }
    fun hasScript(pkg: PluginPackage, owner: AcademicSession, id: String) = connection?.takeIf { it.namespace == PluginStorageScope.session(owner, pkg.manifest.id, !pkg.official) && it.valid() }?.script?.let { !it.closed && it.policy.id == id } == true
    private suspend fun stopScript(c: Connection, reason: String, remote: Boolean = true, expected: Script? = null) = c.scriptLock.withLock {
        if (expected == null || c.script === expected) stopScriptLocked(c, reason, remote)
    }
    private suspend fun stopScriptLocked(c: Connection, reason: String, remote: Boolean = true) {
        val live = c.script ?: return
        if (live.closed) return
        live.closed = true
        live.startRequested = false
        live.startJob?.cancel(); live.startJob = null
        live.preparationJob?.cancel(); live.preparationJob = null
        live.status = if (live.operations.values.any { it.mutationSent }) "result_unknown" else reason
        live.message = if (live.status == "result_unknown") "提交结果待核对，继续前请查看平台进度" else "任务已停止，继续时重新核对平台进度"
        withContext(Dispatchers.IO) { runCatching { live.save() } }
        live.operations.values.forEach(PluginOperation::close)
        if (remote) runCatching { rpc(c, "script.stop", JSONObject().put("handle", live.handle), check = false) }
        live.lease.close(); if (c.script === live) c.script = null
        withContext(Dispatchers.Main) { PluginForegroundWork.remove(c.app, live.handle) }
    }
    private suspend fun receive(c: Connection, event: JSONObject): JSONObject {
        c.check()
        val live = c.script?.takeIf { !it.closed && it.handle == event.optString("handle") } ?: throw PluginException(PluginErrorCode.STALE_CONTEXT, "脚本任务已失效")
        val href = event.optString("href")
        if (!live.policy.executes(href)) throw PluginException(PluginErrorCode.UNTRUSTED_URL, "脚本消息页面未声明")
        when (event.getString("kind")) {
            "browser-prompt", "browser-prompt-closed" -> {
                if (!live.policy.declaration.getJSONObject("adapter").optBoolean("browserPrompts"))
                    throw PluginException(PluginErrorCode.PERMISSION_DENIED, "未声明原生脚本提示")
                val id = event.getString("interactionId")
                if (!id.matches(Regex("native:[a-f0-9-]{36}"))) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "提示标识无效")
                if (event.getString("kind") == "browser-prompt-closed") live.nativePrompts.remove(id)
                else {
                    if (live.nativePrompts.size >= 8) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "待处理提示过多")
                    live.nativePrompts[id] = JSONObject().put("id", id).put("kind", if (event.optString("promptKind") == "input") "input" else "notice")
                        .put("title", live.store.redact(event.optString("title")).ifBlank { "任务提示" }.take(160))
                        .put("message", live.store.redact(event.optString("message")).take(6000))
                        .put("actions", JSONArray().put(JSONObject().put("id", "confirm").put("label", "确定"))
                            .put(JSONObject().put("id", "cancel").put("label", "取消")))
                }
                live.updateDocuments()
            }
            "hello" -> {
                if (live.documents.isEmpty() || live.status == "unavailable") live.preparationAt = SystemClock.elapsedRealtime()
                live.documents.putIfAbsent(event.getString("documentId"), JSONObject()); live.updateDocuments()
                if (live.lastUrl.isEmpty()) live.lastUrl = href
            }
            "storage" -> live.store.putSetting(event.getString("key"), event.get("value"))
            "status" -> {
                live.documents[event.getString("documentId")] = JSONObject().put("stage", event.optString("stage"))
                    .put("available", event.optBoolean("available")).put("running", event.optBoolean("running"))
                    .put("settings", event.optJSONObject("settings") ?: JSONObject()).put("logs", live.store.redact(event.optString("logs")).takeLast(12000))
                    .apply {
                        val adapter = live.policy.declaration.getJSONObject("adapter")
                        event.optJSONObject("interaction")?.let { incoming ->
                            val rule = PluginJson.objects(adapter.optJSONArray("dialogs") ?: JSONArray()).firstOrNull { it.optString("id") == incoming.optString("ruleId") }
                            if (rule != null && incoming.optString("id").matches(Regex("[a-zA-Z0-9_.:-]{1,128}"))) {
                                val offered = PluginJson.objects(incoming.optJSONArray("actions") ?: JSONArray()).map { it.optString("id") }.toSet()
                                put("interaction", JSONObject().put("id", incoming.getString("id")).put("ruleId", rule.getString("id")).put("kind", rule.getString("kind"))
                                    .put("title", live.store.redact(incoming.optString("title", rule.getString("title"))).take(160))
                                    .put("message", live.store.redact(incoming.optString("message")).take(6000))
                                    .put("actions", JSONArray(PluginJson.objects(rule.getJSONArray("actions")).filter { it.getString("id") in offered }.map { JSONObject().put("id", it.getString("id")).put("label", it.getString("label")) })))
                            }
                        }
                        val offered = PluginJson.objects(event.optJSONArray("actions") ?: JSONArray()).map { it.optString("id") }.toSet()
                        put("actions", JSONArray(PluginJson.objects(adapter.optJSONArray("actions") ?: JSONArray()).filter { it.getString("id") in offered }.map { JSONObject().put("id", it.getString("id")).put("label", it.getString("label")) }))
                        event.optJSONObject("progress")?.let { progress ->
                            val total = progress.optInt("total", -1); val remaining = progress.optInt("remaining", -1)
                            if (total in 1..100000 && remaining in 0..total) put("progress", JSONObject().put("total", total).put("remaining", remaining))
                        }
                    }
                live.updateDocuments()
            }
            "closed" -> {
                val document = event.getString("documentId")
                if (live.documents.remove(document)?.optBoolean("available") == true) live.preparationAt = SystemClock.elapsedRealtime()
                live.operations.filterKeys { it.startsWith("$document:") }.values.forEach(PluginOperation::close)
                live.updateDocuments()
            }
            "error" -> { live.startRequested = false; live.status = "error"; live.message = "脚本运行未完成，请检查更新或回退到上一版本" }
            "request" -> return request(c, live, event)
            "abort" -> live.operations[event.optString("documentId") + ":" + event.optString("id")]?.close()
            "open" -> {
                val url = event.getString("url")
                if (!live.policy.resource(url)) throw PluginException(PluginErrorCode.UNTRUSTED_URL, "脚本开页来源未声明")
                val page = rpc(c, "page.open", JSONObject().put("url", url).put("origins", JSONArray(live.policy.origins().toList())).put("scriptHandle", live.handle).put("active", event.optBoolean("active", true)))
                live.openedPages[page.getString("handle")] = event.getString("documentId")
                return page
            }
            "close" -> {
                val page = event.getString("pageHandle")
                if (live.openedPages[page] != event.getString("documentId")) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "页面不属于此脚本文档")
                rpc(c, "page.close", JSONObject().put("handle", page))
                live.openedPages.remove(page)
            }
        }
        if (!live.closed) { live.save(); withContext(Dispatchers.Main) { PluginForegroundWork.changed(); watchPreparation(c, live); advanceStart(c, live) } }
        return JSONObject()
    }
    private suspend fun request(c: Connection, live: Script, event: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val key = event.getString("documentId") + ":" + event.getString("id")
        val operation = PluginOperation(c.session, c.pkg.manifest, "host.effect", !c.pkg.official, confirmed = true, packageDigest = c.pkg.digest, scopeStillActive = { c.valid() && !live.closed })
        try { synchronized(live) {
            if (SystemClock.elapsedRealtime() - live.minute > 60_000) { live.minute = SystemClock.elapsedRealtime(); live.requests = 0 }
            if (live.operations.size >= 8 || ++live.requests > 180 || live.operations.containsKey(key)) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "脚本请求频率过高")
            live.operations[key] = operation
        } } catch (e: Throwable) { operation.close(); throw e }
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val timeout = event.getJSONObject("request").optLong("timeout", 30000).coerceIn(1, 120000)
        val timer = scope.launch { delay(timeout); timedOut.set(true); operation.close() }
        try {
            val request = event.getJSONObject("request")
            val url = request.getString("url").toHttpUrlOrNull() ?: throw PluginException(PluginErrorCode.UNTRUSTED_URL, "请求地址无效")
            val method = request.optString("method", "GET")
            val purpose = if (runCatching { live.policy.request(url, method, "mutation", null) }.isSuccess) "mutation" else "query"
            val guard = PluginDataGuard(c.app, c.pkg)
            live.policy.request(url, method, purpose, null); guard.requireNetwork(url)
            val headers = request.optJSONObject("headers") ?: JSONObject()
            for (name in headers.keys()) if (name.lowercase() in setOf("referer", "origin") && !live.policy.resource(headers.getString(name))) throw PluginException(PluginErrorCode.UNTRUSTED_URL, "请求头来源未声明")
            val input = JSONObject().put("url", url.toString()).put("method", method).put("purpose", purpose).put("headers", headers)
            if (method in setOf("POST", "PUT") && !request.isNull("body")) input.put("body", request.getString("body"))
            if (request.optString("responseType") == "arraybuffer") input.put("responseType", "base64")
            val host = PluginHost(operation, File(c.app.filesDir, "academic-plugin-storage"), PluginWebSessionCookies.jar(c.app, c.pkg, c.session) { c.valid() && !live.closed }, dataGuard = guard,
                requestGuard = { target, verb, intent, form -> live.policy.request(target, verb, intent, form); guard.requireNetwork(target) }, userscriptHeaders = true)
            JSONObject().put("ok", true).put("response", host.call("http", input).getJSONObject("data"))
        } catch (e: Throwable) {
            val code = operation.failure(if (timedOut.get()) PluginErrorCode.TIMEOUT else (e as? PluginException)?.code ?: PluginErrorCode.NETWORK_RETRYABLE, "脚本请求未完成").code
            if (com.tyust.course.BuildConfig.DEBUG) {
                val request = event.optJSONObject("request") ?: JSONObject()
                val target = request.optString("url").toHttpUrlOrNull()
                val headers = request.optJSONObject("headers")?.keys()?.asSequence()?.toList().orEmpty()
                val reason = (e as? PluginException)?.message ?: e.javaClass.simpleName
                android.util.Log.w("PluginScript", "request failed code=${code.name} method=${request.optString("method")} " +
                    "endpoint=${target?.host}${target?.encodedPath} headers=$headers reason=${live.store.redact(reason)}")
            }
            if (code == PluginErrorCode.RESULT_UNKNOWN) stopScript(c, "result_unknown", expected = live)
            JSONObject().put("ok", false).put("error", code.name)
        } finally { timer.cancel(); operation.close(); live.operations.remove(key, operation) }
    }
    fun revoke(pluginId: String) {
        val old = connection?.takeIf { it.pkg.manifest.id == pluginId } ?: return
        scope.launch { mutex.withLock { if (connection === old) runCatching { disconnect(old) } } }
    }
    fun retire(app: Context, profiles: Set<String>) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                for (profile in profiles.filter { it.matches(Regex("plugin_[a-f0-9]{64}")) }) {
                    connection?.takeIf { it.profile == profile }?.let { runCatching { disconnect(it) }; if (!it.died.isCompleted) return@withLock }
                    val root = File(app.noBackupFilesDir, "embedded-browser")
                    val directory = File(root, profile)
                    if (directory.canonicalFile.parentFile == root.canonicalFile) directory.deleteRecursively()
                    app.getSharedPreferences("plugin-embedded-migration", Context.MODE_PRIVATE).edit().remove(profile).commit()
                }
            }
        }
    }
}

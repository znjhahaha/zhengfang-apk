package com.tyust.course.academic.plugin

import android.app.Service
import android.content.Intent
import android.os.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.gecko.util.GeckoBundle
import org.mozilla.geckoview.*
import org.mozilla.geckoview.GeckoSession.PromptDelegate.*
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun <T : Any> GeckoResult<T>.awaitBrowser(): T = suspendCancellableCoroutine { c ->
    accept({ if (c.isActive) { if (it != null) c.resume(it) else c.resumeWithException(PluginException(PluginErrorCode.UNSUPPORTED, "浏览器未返回运行环境")) } },
        { if (c.isActive) c.resumeWithException(it ?: PluginException(PluginErrorCode.UNSUPPORTED, "浏览器环境初始化失败")) })
}

/** Nothing in this process initializes the App registry, keystore or the default WebView. */
internal object EmbeddedBrowserRuntime {
    data class Page(val handle: String, val session: GeckoSession, val origins: Set<String>, val scriptHandle: String?, var url: String, var back: Boolean = false, var loading: Boolean = true)
    var profile: String? = null
    var runtime: GeckoRuntime? = null
    var extension: WebExtension? = null
    var activity: PluginEmbeddedBrowserActivity? = null
    var service: PluginEmbeddedBrowserService? = null
    var title = "学习通"
    val pages = linkedMapOf<String, Page>()
    var visible: String? = null
    fun page(handle: String) = pages[handle]
    fun close(handle: String) {
        val p = pages.remove(handle) ?: return
        service?.closePrompts(handle)
        p.session.stop(); p.session.close()
        if (visible == handle) visible = pages.keys.lastOrNull()
        activity?.render()
    }
}

class PluginEmbeddedBrowserService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var callback: IBinder? = null
    private var nativePort: WebExtension.Port? = null
    private var bootstrapSession: GeckoSession? = null
    private val ready = CompletableDeferred<WebExtension.Port>()
    private val replies = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private var config = JSONObject()
    private var script: JSONObject? = null
    private var closing = false
    private data class PendingPrompt(val page: EmbeddedBrowserRuntime.Page, val prompt: BasePrompt,
        val result: GeckoResult<PromptResponse>, val expiresAt: Long)
    private val prompts = linkedMapOf<String, PendingPrompt>()
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(PluginBrowserWire.DESCRIPTOR)
            if (getCallingUid() != applicationInfo.uid) throw SecurityException("Private embedded browser")
            var action = ""
            val result = try {
                val request = PluginBrowserWire.read(data); val incoming = data.readStrongBinder()
                action = request.optString("action")
                val response = runBlocking { withTimeout(60_000) { withContext(Dispatchers.Main) { handle(request, incoming) } } }
                JSONObject().put("ok", true).put("data", response)
            } catch (e: Throwable) { PluginBrowserWire.failure(e, action) }
            reply?.writeNoException(); if (reply != null) PluginBrowserWire.write(reply, result)
            return true
        }
    }
    override fun onBind(intent: Intent?): IBinder { EmbeddedBrowserRuntime.service = this; return binder }
    override fun onUnbind(intent: Intent?): Boolean { shutdown(); return false }
    private suspend fun command(action: String, input: JSONObject): JSONObject {
        val port = nativePort ?: try { withTimeout(30_000) { ready.await() } }
            catch (e: TimeoutCancellationException) { throw PluginException(PluginErrorCode.TIMEOUT, "浏览器扩展连接超时，请重新打开课程", e) }
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<JSONObject>(); replies[id] = result
        try {
            if (action in setOf("script.control", "script.interact", "script.action")) input.put("expiresAt", System.currentTimeMillis() + 25_000)
            port.postMessage(JSONObject().put("id", id).put("action", action).put("input", input))
            val response = try { withTimeout(30_000) { result.await() } }
                catch (e: TimeoutCancellationException) { throw PluginException(PluginErrorCode.TIMEOUT, if (action == "script.control") "尚未收到任务启动确认，请查看任务提示与日志" else "任务引擎处理超时，请停止后重试", e) }
            if (!response.optBoolean("ok")) throw PluginException(runCatching { PluginErrorCode.valueOf(response.optString("code")) }.getOrDefault(PluginErrorCode.UNSUPPORTED), response.optString("message", "浏览器运行环境未就绪"))
            return response.optJSONObject("data") ?: JSONObject()
        } finally { replies.remove(id); result.cancel() }
    }
    private suspend fun initialize(profile: String, input: JSONObject, host: IBinder?) {
        PluginEmbeddedBrowser.requireSupport()
        if (!profile.matches(Regex("plugin_[a-f0-9]{64}"))) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "浏览器 profile 无效")
        if (EmbeddedBrowserRuntime.profile != null) {
            if (EmbeddedBrowserRuntime.profile != profile) throw PluginException(PluginErrorCode.CONFLICT, "旧浏览器账号尚未退出")
            return
        }
        callback = host ?: throw PluginException(PluginErrorCode.PERMISSION_DENIED, "缺少宿主会话")
        callback!!.linkToDeath({ Handler(Looper.getMainLooper()).post { shutdown() } }, 0)
        val directory = File(noBackupFilesDir, "embedded-browser/$profile")
        directory.mkdirs()
        EmbeddedBrowserRuntime.profile = profile
        EmbeddedBrowserRuntime.title = input.optString("title", "插件浏览器")
        config = input
        val settings = GeckoRuntimeSettings.Builder().arguments(arrayOf("-profile", directory.absolutePath))
            .javaScriptEnabled(true).remoteDebuggingEnabled(false).consoleOutput(false).build()
        val runtime = GeckoRuntime.create(this, settings)
        EmbeddedBrowserRuntime.runtime = runtime
        // Restored extensions wait for the first Gecko window's startup event.
        // Open an unexposed blank session before waiting for the native port.
        val startup = GeckoSession()
        try {
            startup.open(runtime)
            bootstrapSession = startup
            connectExtension(runtime, input)
        } catch (error: Throwable) {
            if (startup.isOpen) startup.close()
            bootstrapSession = null
            throw error
        }
        // The extension also serves native cookie calls without a visible page.
        // Keep one window alive so closing the last page cannot tear down its port.
    }
    private suspend fun connectExtension(runtime: GeckoRuntime, input: JSONObject) {
        val extension = runtime.webExtensionController.ensureBuiltIn("resource://android/assets/academic-plugin/gecko/", "userscripts@zhengfang.local").awaitBrowser()
        EmbeddedBrowserRuntime.extension = extension
        extension.setMessageDelegate(object : WebExtension.MessageDelegate {
            private fun trusted(sender: WebExtension.MessageSender) = sender.session == null && sender.webExtension.id == "userscripts@zhengfang.local" && sender.environmentType == WebExtension.MessageSender.ENV_TYPE_EXTENSION
            override fun onConnect(port: WebExtension.Port) {
                if (!trusted(port.sender)) { port.disconnect(); return }
                nativePort = port
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, port: WebExtension.Port) {
                        if (nativePort !== port || closing) return
                        val value = message as? JSONObject ?: return
                        replies[value.optString("replyTo")]?.complete(value)
                    }
                    override fun onDisconnect(port: WebExtension.Port) {
                        if (nativePort !== port || closing) return
                        nativePort = null
                        replies.values.forEach { it.completeExceptionally(PluginException(PluginErrorCode.NOT_OPEN, "浏览器扩展连接已中断")) }
                        shutdown()
                    }
                })
                ready.complete(port)
            }
            override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                if (!trusted(sender) || message !is JSONObject) return null
                val result = GeckoResult<Any>()
                scope.launch {
                    try {
                        val handle = script?.optString("handle")
                        if (handle.isNullOrEmpty() || message.optString("handle") != handle) throw PluginException(PluginErrorCode.STALE_CONTEXT, "脚本已停止")
                        val value = withContext(Dispatchers.IO) { PluginBrowserWire.call(callback ?: throw PluginException(PluginErrorCode.NOT_OPEN, "宿主连接已关闭"), message) }
                        // Gecko's native callback marshaller accepts GeckoBundle objects.
                        // Returning JSONObject leaves sendNativeMessage unresolved in Gecko.
                        result.complete(GeckoBundle.fromJSONObject(value))
                    } catch (_: Exception) {
                        result.complete(GeckoBundle.fromJSONObject(JSONObject().put("ok", false).put("error", "SESSION_EXPIRED")))
                    }
                }
                return result
            }
        }, "zfBrowser")
        extension.setTabDelegate(object : WebExtension.TabDelegate {
            override fun onNewTab(source: WebExtension, createDetails: WebExtension.CreateTabDetails): GeckoResult<GeckoSession>? {
                val parent = EmbeddedBrowserRuntime.pages[EmbeddedBrowserRuntime.visible] ?: return null
                val url = createDetails.url ?: "about:blank"
                if (url != "about:blank" && !allows(url, parent.origins)) return null
                val child = page(url, parent.origins, parent.scriptHandle, createDetails.active != false, load = false)
                return GeckoResult.fromValue(child.session)
            }
        })
        command("configure", input)
    }
    private suspend fun handle(request: JSONObject, host: IBinder?): JSONObject {
        val profile = request.getString("profile"); val action = request.getString("action"); val input = request.getJSONObject("input")
        if (action == "configure") { initialize(profile, input, host); return JSONObject() }
        if (profile != EmbeddedBrowserRuntime.profile || closing) throw PluginException(PluginErrorCode.STALE_CONTEXT, "浏览器账号已失效")
        when (action) {
            "cookies.read" -> return command(action, input)
            "cookies.write" -> {
                val url = input.getString("url").toHttpUrlOrNull() ?: throw PluginException(PluginErrorCode.UNTRUSTED_URL, "Cookie 地址无效")
                val cookie = Cookie.parse(url, input.getString("cookie")) ?: throw PluginException(PluginErrorCode.VALIDATION_FAILED, "Cookie 格式无效")
                val value = JSONObject().put("url", url.toString()).put("name", cookie.name).put("value", cookie.value).put("path", cookie.path).put("secure", cookie.secure).put("httpOnly", cookie.httpOnly)
                if (!cookie.hostOnly) value.put("domain", cookie.domain)
                if (cookie.persistent) value.put("expirationDate", cookie.expiresAt / 1000.0)
                return command(action, JSONObject().put("cookie", value))
            }
            "page.open" -> {
                val origins = PluginJson.strings(input.getJSONArray("origins")).toSet()
                val url = input.getString("url")
                if (!allows(url, origins)) throw PluginException(PluginErrorCode.UNTRUSTED_URL, "浏览器地址未声明")
                val handle = input.optString("scriptHandle").takeIf { it.isNotEmpty() }
                if (handle != null && handle != script?.optString("handle")) throw PluginException(PluginErrorCode.STALE_CONTEXT, "脚本句柄已失效")
                return describe(page(url, origins, handle, input.optBoolean("active", true)))
            }
            "page.status", "page.close" -> {
                val page = EmbeddedBrowserRuntime.page(input.getString("handle")) ?: throw PluginException(PluginErrorCode.NOT_OPEN, "页面已关闭")
                val result = describe(page)
                if (action == "page.close") EmbeddedBrowserRuntime.close(page.handle)
                return result
            }
            "script.page" -> {
                val page = EmbeddedBrowserRuntime.pages.values.lastOrNull { it.scriptHandle == input.getString("handle") } ?: throw PluginException(PluginErrorCode.NOT_OPEN, "脚本页面已关闭")
                return describe(page)
            }
            "script.reload" -> {
                val page = EmbeddedBrowserRuntime.pages.values.lastOrNull { it.scriptHandle == input.getString("handle") }
                    ?: throw PluginException(PluginErrorCode.NOT_OPEN, "脚本页面已关闭")
                page.session.reload()
                return describe(page)
            }
            "script.start" -> { command(action, input); script = input; return JSONObject() }
            "script.interact" -> {
                val id = input.getString("interactionId")
                if (!id.startsWith("native:")) return command(action, input)
                val pending = prompts[id] ?: throw PluginException(PluginErrorCode.STALE_CONTEXT, "提示已关闭或过期")
                if (input.optString("handle") != script?.optString("handle") ||
                    pending.page.scriptHandle != input.optString("handle") ||
                    EmbeddedBrowserRuntime.page(pending.page.handle) !== pending.page || System.currentTimeMillis() >= pending.expiresAt)
                    throw PluginException(PluginErrorCode.STALE_CONTEXT, "提示所属任务已失效")
                val choice = input.getString("actionId")
                if (choice !in setOf("confirm", "cancel")) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "未知提示操作")
                val prompt = pending.prompt
                val answer = if (choice == "cancel") prompt.dismiss() else when (prompt) {
                    is ButtonPrompt -> prompt.confirm(ButtonPrompt.Type.POSITIVE)
                    is TextPrompt -> {
                        val value = input.optString("text")
                        if (!input.has("text") || value.length > 4096) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "请输入提示所需的内容")
                        prompt.confirm(value)
                    }
                    else -> prompt.dismiss()
                }
                prompts.remove(id)
                pending.result.complete(answer)
                return JSONObject().put("accepted", true)
            }
            "script.settings", "script.control", "script.action" -> return command(action, input)
            "script.stop" -> {
                // A registered script can also enter a normally opened course page after
                // navigation. Close its timers immediately, even if the extension is busy.
                script = null
                EmbeddedBrowserRuntime.pages.keys.toList().forEach(EmbeddedBrowserRuntime::close)
                command(action, input)
                return JSONObject()
            }
            "shutdown" -> { shutdown(); return JSONObject() }
            else -> throw PluginException(PluginErrorCode.UNSUPPORTED, "未知浏览器操作")
        }
    }
    private fun allows(url: String, origins: Set<String>) = url.toHttpUrlOrNull()?.let { target ->
        target.isHttps && target.username.isEmpty() && target.password.isEmpty() && !Regex("(?i)%2f|%5c|%00").containsMatchIn(target.encodedPath) &&
            origins.any { PluginWebGate(it).owns(url) } && PluginJson.objects(config.optJSONArray("network") ?: JSONArray()).any { PluginWebGate(it.getString("origin")).owns(url) }
    } == true
    private fun describe(page: EmbeddedBrowserRuntime.Page) = JSONObject().put("handle", page.handle).put("url", page.url).put("loading", page.loading).put("engine", "GeckoView 157")
    private fun page(url: String, origins: Set<String>, scriptHandle: String?, active: Boolean, load: Boolean = true): EmbeddedBrowserRuntime.Page {
        if (EmbeddedBrowserRuntime.pages.size >= 8) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "最多打开 8 个浏览器页面")
        val settings = GeckoSessionSettings.Builder().allowJavascript(true)
        // Script controls target the desktop course markup. Login pages keep their mobile layout.
        if (scriptHandle != null) settings.userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
        val session = GeckoSession(settings.build())
        val p = EmbeddedBrowserRuntime.Page("b" + UUID.randomUUID().toString().replace("-", ""), session, origins, scriptHandle, url)
        EmbeddedBrowserRuntime.pages[p.handle] = p
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                if (p.scriptHandle != null) {
                    val secure = PluginBrowserNavigation.httpsEquivalent(request.uri) { allows(it, p.origins) }
                    if (secure != null) {
                        session.loadUri(secure)
                        return GeckoResult.deny()
                    }
                }
                return GeckoResult.fromValue(if (request.uri == "about:blank" || allows(request.uri, p.origins)) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }
            override fun onSubframeLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> =
                GeckoResult.fromValue(if (request.uri == "about:blank" || allows(request.uri, PluginJson.objects(config.getJSONArray("network")).map { it.getString("origin") }.toSet())) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (url != null) p.url = url
                EmbeddedBrowserRuntime.activity?.updateBar()
            }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { p.back = canGoBack }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (uri != "about:blank" && !allows(uri, p.origins)) return null
                return GeckoResult.fromValue(page(uri, p.origins, p.scriptHandle, true, load = false).session)
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) { closePrompts(p.handle); p.loading = true; EmbeddedBrowserRuntime.activity?.updateBar() }
            override fun onPageStop(session: GeckoSession, success: Boolean) { p.loading = false; EmbeddedBrowserRuntime.activity?.updateBar() }
        }
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.AlertPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
                nativePrompt(p, prompt, prompt.message) ?: EmbeddedBrowserRuntime.activity?.alert(prompt) ?: GeckoResult.fromValue(prompt.dismiss())
            override fun onButtonPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ButtonPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
                nativePrompt(p, prompt, prompt.message) ?: EmbeddedBrowserRuntime.activity?.confirm(prompt) ?: GeckoResult.fromValue(prompt.dismiss())
            override fun onTextPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.TextPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
                nativePrompt(p, prompt, prompt.message) ?: EmbeddedBrowserRuntime.activity?.input(prompt) ?: GeckoResult.fromValue(prompt.dismiss())
        }
        EmbeddedBrowserRuntime.extension?.let { extension ->
            session.webExtensionController.setTabDelegate(extension, object : WebExtension.SessionTabDelegate {
                override fun onCloseTab(source: WebExtension?, session: GeckoSession): GeckoResult<AllowOrDeny> { EmbeddedBrowserRuntime.close(p.handle); return GeckoResult.allow() }
                override fun onUpdateTab(extension: WebExtension, session: GeckoSession, details: WebExtension.UpdateTabDetails): GeckoResult<AllowOrDeny> {
                    if (details.url != null && !allows(details.url!!, p.origins)) return GeckoResult.deny()
                    if (details.active == true) { EmbeddedBrowserRuntime.visible = p.handle; EmbeddedBrowserRuntime.activity?.render() }
                    return GeckoResult.allow()
                }
            })
        }
        session.open(EmbeddedBrowserRuntime.runtime!!)
        if (active || EmbeddedBrowserRuntime.visible == null) EmbeddedBrowserRuntime.visible = p.handle
        if (load) session.loadUri(url)
        EmbeddedBrowserRuntime.activity?.render()
        return p
    }
    private fun promptEvent(p: EmbeddedBrowserRuntime.Page, kind: String, id: String, data: JSONObject = JSONObject()) {
        val host = callback ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { PluginBrowserWire.call(host, data.put("kind", kind)
                .put("handle", p.scriptHandle).put("href", p.url).put("interactionId", id)) } }
                .onFailure { dismissPrompt(id) }
        }
    }
    private fun nativePrompt(page: EmbeddedBrowserRuntime.Page, prompt: BasePrompt, message: String?): GeckoResult<PromptResponse>? {
        if (page.scriptHandle == null || script?.optJSONObject("declaration")?.optJSONObject("adapter")?.optBoolean("browserPrompts") != true) return null
        if (prompts.size >= 8) return GeckoResult.fromValue(prompt.dismiss())
        val id = "native:" + UUID.randomUUID().toString()
        val result = GeckoResult<PromptResponse>()
        prompts[id] = PendingPrompt(page, prompt, result, System.currentTimeMillis() + 300_000)
        prompt.setDelegate(object : PromptInstanceDelegate {
            override fun onPromptDismiss(prompt: BasePrompt) { dismissPrompt(id) }
            override fun onPromptUpdate(prompt: BasePrompt) { dismissPrompt(id) }
        })
        promptEvent(page, "browser-prompt", id, JSONObject().put("title", prompt.title.orEmpty().take(160))
            .put("message", message.orEmpty().take(6000)).put("promptKind", if (prompt is TextPrompt) "input" else "notice"))
        scope.launch { delay(300_000); dismissPrompt(id) }
        return result
    }
    private fun dismissPrompt(id: String) {
        val pending = prompts.remove(id) ?: return
        if (!pending.prompt.isComplete) pending.result.complete(pending.prompt.dismiss())
        promptEvent(pending.page, "browser-prompt-closed", id)
    }
    internal fun closePrompts(page: String) { prompts.filterValues { it.page.handle == page }.keys.toList().forEach(::dismissPrompt) }
    private fun shutdown() {
        if (closing) return
        closing = true
        EmbeddedBrowserRuntime.activity?.finish()
        EmbeddedBrowserRuntime.pages.keys.toList().forEach(EmbeddedBrowserRuntime::close)
        bootstrapSession?.let { if (it.isOpen) it.close() }; bootstrapSession = null
        scope.cancel(); stopSelf()
        runCatching { EmbeddedBrowserRuntime.runtime?.shutdown() }
        // GeckoRuntime is process-singleton. Account replacement always creates a fresh process.
        Handler(Looper.getMainLooper()).postDelayed({
            val name = if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() else File("/proc/self/cmdline").readText().substringBefore('\u0000')
            if (name.endsWith(":plugin_embedded_browser")) Process.killProcess(Process.myPid())
        }, 300)
    }
}

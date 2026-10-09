package com.tyust.course.academic.plugin

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Message
import android.webkit.*
import androidx.core.app.NotificationCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.tyust.course.academic.AcademicSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object PluginUserscripts {
    private val sessions = mutableMapOf<String, UserscriptSession>()
    fun requireSupport() {
        if (!listOf(WebViewFeature.MULTI_PROFILE, WebViewFeature.WEB_MESSAGE_LISTENER, WebViewFeature.DOCUMENT_START_SCRIPT).all(WebViewFeature::isFeatureSupported))
            throw PluginException(PluginErrorCode.UNSUPPORTED, "请更新 Android System WebView 后使用脚本会话")
    }
    internal fun store(app: Context, pkg: PluginPackage, session: AcademicSession, id: String): PluginUserscriptStore {
        val policy = PluginUserscriptPolicy(PluginUserscriptPolicy.declaration(pkg.manifest, id), pkg.manifest)
        if (session.key.schoolId != "service:${pkg.manifest.id}:${policy.serverId}") throw PluginException(PluginErrorCode.PERMISSION_DENIED, "脚本不属于这个服务账号")
        return PluginUserscriptStore(app, policy, PluginStorageScope.session(session, pkg.manifest.id, !pkg.official)) {
            !session.retired && PluginServiceAccounts(app).current(pkg, session) && AcademicProviderRegistry.isCurrentPackage(pkg.manifest.id, pkg.digest) && AcademicProviderRegistry.isEnabled(pkg.manifest.id)
        }
    }
    suspend fun configure(app: Context, pkg: PluginPackage, owner: AcademicSession, input: JSONObject): JSONObject {
        val store = store(app, pkg, owner, input.getString("scriptId"))
        withContext(Dispatchers.IO) {
            val declared = PluginJson.objects(store.policy.declaration.getJSONObject("adapter").getJSONArray("settings")).associateBy { it.getString("key") }
            val values = input.optJSONObject("values") ?: JSONObject()
            val normalized = values.keys().asSequence().associateWith { key ->
                val setting = declared[key] ?: throw PluginException(PluginErrorCode.VALIDATION_FAILED, "没有这个原脚本设置")
                PluginUserscriptStore.normalizedSetting(setting, values.get(key)) ?: throw PluginException(PluginErrorCode.VALIDATION_FAILED, "脚本设置超出范围")
            }
            val credential = input.optJSONObject("credential")
            val secret = credential?.let {
                if (it.getString("key") !in PluginJson.strings(store.policy.declaration.optJSONArray("sensitiveKeys"))) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "未声明这个敏感 GM 键")
                val vault = NativePluginVault(app, PluginStorageScope.session(owner, pkg.manifest.id, !pkg.official))
                val value = vault.get("credential:" + it.getString("handle"))?.optString(it.getString("field"))
                if (value.isNullOrBlank() || value.length > 4096) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "凭据已失效或内容无效，请重新输入")
                value
            }
            normalized.forEach { (key, value) -> store.putSetting(key, value) }
            if (credential != null && secret != null) store.putSetting(credential.getString("key"), secret)
        }
        PluginEmbeddedBrowser.refreshScriptSettings(app, pkg, owner, store, credentialChanged = input.has("credential"))
        return store.publicStatus()
    }
    suspend fun status(app: Context, pkg: PluginPackage, session: AcademicSession, id: String): JSONObject = withContext(Dispatchers.Main) {
        if (PluginEmbeddedBrowser.enabled(pkg, session)) return@withContext PluginEmbeddedBrowser.scriptStatus(app, pkg, session, id)
        val namespace = PluginStorageScope.session(session, pkg.manifest.id, !pkg.official)
        val live = sessions.values.firstOrNull { it.namespace == namespace && it.policy.id == id && it.valid() }
        val status = withContext(Dispatchers.IO) { store(app, pkg, session, id).publicStatus() }
        if (live != null) live.describe(status) else {
            val saved = withContext(Dispatchers.IO) { NativePluginVault(app, namespace).get("userscript:$id:last") }
            status.put("status", if (saved?.optString("status") in setOf("loading", "ready", "running")) "interrupted" else saved?.optString("status") ?: "idle")
                .put("lastUrl", saved?.optString("url").orEmpty()).put("message", saved?.optString("message").orEmpty())
        }
    }
    suspend fun update(app: Context, pkg: PluginPackage, session: AcademicSession, id: String) = store(app, pkg, session, id).update(true, retryRolledBack = true)
    suspend fun rollback(app: Context, pkg: PluginPackage, session: AcademicSession, id: String): JSONObject = withContext(Dispatchers.Main) {
        if (PluginEmbeddedBrowser.hasScript(pkg, session, id)) throw PluginException(PluginErrorCode.CONFLICT, "请先停止当前任务，再回退脚本和设置")
        val namespace = PluginStorageScope.session(session, pkg.manifest.id, !pkg.official)
        if (sessions.values.any { it.namespace == namespace && it.policy.id == id && it.valid() }) throw PluginException(PluginErrorCode.CONFLICT, "请先停止当前任务，再回退脚本和设置")
        store(app, pkg, session, id).rollback()
    }
    suspend fun start(app: Context, pkg: PluginPackage, owner: AcademicSession, id: String, url: String): JSONObject = withContext(Dispatchers.Main) {
        if (PluginEmbeddedBrowser.enabled(pkg, owner)) return@withContext PluginEmbeddedBrowser.startScript(app, pkg, owner, id, url)
        requireSupport()
        val ownerStore = store(app, pkg, owner, id)
        if (!ownerStore.policy.executes(url)) throw PluginException(PluginErrorCode.UNTRUSTED_URL, "这不是脚本声明的课程页面")
        val namespace = PluginStorageScope.session(owner, pkg.manifest.id, !pkg.official)
        sessions.values.firstOrNull { it.namespace == namespace && it.policy.id == id }?.let { old -> old.stop("stopped") }
        if (sessions.size >= 4) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "最多同时运行 4 个脚本会话")
        val metadata = ownerStore.metadata()
        if (!metadata.optBoolean("subscribed") || System.currentTimeMillis() - metadata.optLong("checkedAt") > 24 * 60 * 60 * 1000L) ownerStore.update(true)
        val (selected, source) = ownerStore.activate()
        val session = PluginServiceAccounts(app).session(pkg, ownerStore.policy.serverId)
        if (session.key.accountKey != owner.key.accountKey) { session.retire(); throw PluginException(PluginErrorCode.STALE_CONTEXT, "服务账号已切换") }
        val handle = "s" + UUID.randomUUID().toString().replace("-", "")
        val live = UserscriptSession(app.applicationContext, pkg, session, namespace, handle, ownerStore.policy, selected, source) { sessions.remove(handle) }
        sessions[handle] = live
        try {
            PluginForegroundWork.add(app, handle, PluginForegroundWork.Entry(pkg.manifest.id, ownerStore.policy.declaration.getString("title"), live::stop))
            live.open(url, true)
        } catch (e: Exception) { live.stop("failed"); throw e }
        live.describe(ownerStore.publicStatus())
    }
    suspend fun control(app: Context, pkg: PluginPackage, owner: AcademicSession, input: JSONObject): JSONObject = withContext(Dispatchers.Main) {
        if (PluginEmbeddedBrowser.enabled(pkg, owner)) return@withContext PluginEmbeddedBrowser.controlScript(app, pkg, owner, input)
        val live = sessions[input.getString("handle")] ?: throw PluginException(PluginErrorCode.NOT_OPEN, "脚本已停止，请重新核对课程后继续")
        val namespace = PluginStorageScope.session(owner, pkg.manifest.id, !pkg.official)
        if (live.namespace != namespace || !live.valid()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "脚本不属于当前账号")
        when (input.getString("action")) {
            "stop" -> live.stop("stopped")
            "open" -> app.startActivity(Intent(app, PluginUserscriptActivity::class.java).putExtra("handle", live.handle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "start" -> live.command("start")
            "settings" -> live.command("settings", input.optJSONObject("values") ?: JSONObject())
        }
        live.describe(JSONObject())
    }
    fun get(handle: String) = sessions[handle]?.takeIf { it.valid() }
}

/** Owns WebViews and an account session independently of any Activity or native page. */
@SuppressLint("SetJavaScriptEnabled")
internal class UserscriptSession(
    private val app: Context, val pkg: PluginPackage, private val session: AcademicSession,
    val namespace: String, val handle: String, val policy: PluginUserscriptPolicy,
    private val selected: JSONObject, private val source: String, private val onStopped: () -> Unit
) {
    private data class Document(val view: WebView, val generation: Int, val href: String, val reply: JavaScriptReplyProxy, val mainFrame: Boolean, var available: Boolean = false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val accounts = PluginServiceAccounts(app)
    private val views = mutableListOf<WebView>()
    private val generations = mutableMapOf<WebView, Int>()
    private val documents = mutableMapOf<String, Document>()
    private val requests = mutableMapOf<String, Job>()
    private val lease = PluginVersionLeases.acquire(pkg.manifest.id)
    private val store = PluginUserscriptStore(app, policy, namespace, active = ::valid)
    private val vault = NativePluginVault(app, namespace, guard = {
        if (!valid()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "脚本账号或插件已改变")
    })
    private val dataGuard = PluginDataGuard(app, pkg)
    @Volatile private var closed = false
    private val operations = java.util.concurrent.ConcurrentHashMap.newKeySet<PluginOperation>()
    private var currentStatus = "loading"
    private var lastUrl = ""
    private var message = "正在加载原脚本"
    private var logs = ""
    private var adapterAvailable = false
    private val reportedSettings = JSONObject()
    private var minuteStarted = 0L
    private var minuteRequests = 0
    private var pendingDialog: (() -> Unit)? = null
    var activity: Activity? = null
        set(value) { field = value; if (value != null) pendingDialog?.let { pendingDialog = null; it() } }
    val visible = MutableStateFlow<WebView?>(null)
    val state = MutableStateFlow(0L)
    init {
        scope.launch { accountsRevision() }
        scope.launch {
            while (isActive && valid()) { delay(60_000); if (!valid()) { stop("interrupted"); break }
                if (System.currentTimeMillis() - store.metadata().optLong("checkedAt") > 24 * 60 * 60 * 1000L) runCatching { store.update() }.also { changed() }
            }
        }
    }
    private suspend fun accountsRevision() { PluginServiceAccounts.revision.collect { if (!valid()) stop("interrupted") } }
    fun valid(): Boolean = !closed && !session.retired && accounts.current(pkg, session) &&
        AcademicProviderRegistry.isCurrentPackage(pkg.manifest.id, pkg.digest) && AcademicProviderRegistry.isEnabled(pkg.manifest.id)
    private fun save() { vault.put("userscript:${policy.id}:last", JSONObject().put("url", lastUrl).put("status", currentStatus).put("message", message).put("version", selected.getString("version"))) }
    private fun changed() { state.value++; PluginForegroundWork.changed() }
    fun describe(status: JSONObject): JSONObject = status.put("handle", handle).put("scriptId", policy.id).put("status", currentStatus)
        .put("runningVersion", selected.getString("version")).put("adapterVersion", policy.declaration.getJSONObject("adapter").getString("version"))
        .put("adapterAvailable", adapterAvailable).put("message", message).put("logs", logs).put("lastUrl", lastUrl)
        .put("settings", JSONObject(status.optJSONObject("settings")?.toString() ?: "{}").apply { reportedSettings.keys().forEach { put(it, reportedSettings.get(it)) } })
    fun command(action: String, values: JSONObject = JSONObject()) {
        if (!valid()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "脚本会话已失效")
        if (action == "settings") {
            val settings = PluginJson.objects(policy.declaration.getJSONObject("adapter").getJSONArray("settings")).associateBy { it.getString("key") }
            values.keys().forEach { key ->
                val setting = settings[key] ?: throw PluginException(PluginErrorCode.VALIDATION_FAILED, "没有这个原脚本设置")
                val value = values.get(key)
                if (setting.getString("type") == "boolean" && value !is Boolean || setting.getString("type") == "number" &&
                    (value !is Number || value.toDouble() !in setting.getDouble("min")..setting.getDouble("max"))) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "设置值超出范围")
                store.putSetting(key, value)
            }
        }
        if (action == "start" && !adapterAvailable) { message = "当前页面的原生按钮映射未就绪，请打开原脚本面板"; changed(); return }
        val targets = if (action == "start") documents.entries.filter { it.value.available }.sortedByDescending { it.value.mainFrame && it.value.view === visible.value }.take(1)
            else documents.entries.toList()
        targets.forEach { (id, document) -> reply(id, document, JSONObject().put("kind", "command").put("action", action).put("values", values)) }
        changed()
    }
    fun open(url: String, show: Boolean = true): WebView {
        if (!valid() || !policy.resource(url)) throw PluginException(PluginErrorCode.UNTRUSTED_URL, "页面不在脚本声明的站点内")
        if (views.size >= 8) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "脚本打开的页面过多")
        val view = createView()
        if (show) visible.value = view
        view.loadUrl(url); return view
    }
    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun createView(): WebView {
        val view = WebView(app)
        WebViewCompat.setProfile(view, PluginWebSessionCookies.profile(app, pkg, session, ::valid).name)
        WebViewCompat.getProfile(view).cookieManager.setAcceptThirdPartyCookies(view, false)
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = true
            userAgentString = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        }
        val config = JSONObject().put("matches", policy.declaration.getJSONArray("matches")).put("exclude", policy.declaration.optJSONArray("exclude") ?: JSONArray())
        val bootstrap = app.assets.open("academic-plugin/userscript-bootstrap.js").bufferedReader().use { it.readText() }.replace("__ZF_CONFIG__", config.toString())
        val networkConfig = JSONObject().put("origins", JSONArray(policy.origins().toList())).put("exclude", policy.declaration.optJSONArray("exclude") ?: JSONArray())
        val networkGuard = app.assets.open("academic-plugin/userscript-network-guard.js").bufferedReader().use { it.readText() }.replace("__ZF_NETWORK_CONFIG__", networkConfig.toString())
        // This guard carries no host bridge or data. It also covers inherited blank frames;
        // Chromium's request interceptor does not observe WebSocket handshakes.
        WebViewCompat.addDocumentStartJavaScript(view, networkGuard, setOf("*"))
        WebViewCompat.addWebMessageListener(view, "zfUserscript", policy.origins()) { sender, payload, origin, isMainFrame, reply ->
            if (!valid() || payload.data == null || payload.data!!.toByteArray().size > 524288) return@addWebMessageListener
            try {
                val event = JSONObject(payload.data!!)
                val href = event.getString("href")
                val url = href.toHttpUrlOrNull() ?: return@addWebMessageListener
                if (PluginAuthScope.origin(url) != origin.toString().trimEnd('/') || !policy.executes(href)) return@addWebMessageListener
                receive(sender, event, reply, isMainFrame)
            } catch (_: Exception) { message = "脚本通信未完成，请打开原页面检查"; changed() }
        }
        WebViewCompat.addDocumentStartJavaScript(view, bootstrap, policy.origins())
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http://")) {
                    val https = "https://" + url.removePrefix("http://")
                    if (policy.resource(https)) { view.loadUrl(https); return true }
                }
                if (!policy.resource(url)) { message = "该链接超出运行范围，请从插件对应页面打开"; changed(); return true }
                return false
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (valid() && policy.resource(request.url.toString())) null else blocked()
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                generations[view] = (generations[view] ?: 0) + 1
                documents.entries.removeAll { it.value.view === view }
                if (url != null && policy.executes(url)) { lastUrl = url; currentStatus = "loading"; adapterAvailable = false; save(); changed() }
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { stop("interrupted"); return true }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { message = "课程页面加载失败，请检查网络后重新继续"; currentStatus = "error"; save(); changed() }
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!valid() || views.size >= 8) return false
                val child = createView(); visible.value = child
                (resultMsg.obj as WebView.WebViewTransport).webView = child; resultMsg.sendToTarget(); return true
            }
            override fun onCloseWindow(window: WebView) { removeView(window) }
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean = dialog(message, result, false)
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean = dialog(message, result, true)
        }
        views.add(view); generations[view] = 0; view.onResume(); return view
    }
    private fun dialog(text: String, result: JsResult, confirm: Boolean): Boolean {
        val show = {
            val owner = activity
            if (owner != null && !owner.isFinishing && valid()) AlertDialog.Builder(owner).setTitle("${pkg.manifest.name} · 原脚本")
                .setMessage(text.take(4000)).setPositiveButton("确认") { _, _ -> result.confirm() }
                .apply { if (confirm) setNegativeButton("取消") { _, _ -> result.cancel() } }.setOnCancelListener { result.cancel() }.show()
            else result.cancel()
            Unit
        }
        if (activity != null) show() else { pendingDialog = show; message = "原脚本需要确认，请打开原页面继续"; notifyInput(); changed() }
        return true
    }
    private fun notifyInput() {
        val manager = app.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("plugin-script-input", "脚本需要操作", NotificationManager.IMPORTANCE_DEFAULT))
        val pending = PendingIntent.getActivity(app, handle.hashCode(), Intent(app, PluginUserscriptActivity::class.java).putExtra("handle", handle), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { manager.notify(handle.hashCode(), NotificationCompat.Builder(app, "plugin-script-input").setSmallIcon(com.tyust.course.R.drawable.ic_course_reminder)
            .setContentTitle(pkg.manifest.name).setContentText("原脚本需要操作，点击继续").setContentIntent(pending).setAutoCancel(true).build()) }
    }
    private fun reply(id: String, document: Document, value: JSONObject) {
        if (valid() && generations[document.view] == document.generation) document.reply.postMessage(value.put("documentId", id).toString())
    }
    private fun receive(view: WebView, event: JSONObject, reply: JavaScriptReplyProxy, mainFrame: Boolean) {
        val id = event.getString("documentId")
        if (!id.matches(Regex("[a-z0-9]{1,64}"))) return
        val kind = event.getString("kind")
        if (kind == "hello") {
            if (documents.size >= 64) return
            val document = Document(view, generations[view] ?: 0, event.getString("href"), reply, mainFrame)
            documents[id] = document
            reply(id, document, JSONObject().put("kind", "init").put("source", source).put("info", selected)
                .put("values", store.settings()).put("adapter", policy.declaration.getJSONObject("adapter")))
            return
        }
        val document = documents[id]?.takeIf { it.view === view && it.href == event.getString("href") && it.generation == generations[view] } ?: return
        when (kind) {
            "installed" -> { currentStatus = "ready"; message = "原脚本已就绪，可以开始课程任务"; save(); changed() }
            "error" -> { currentStatus = "error"; message = "原脚本未能启动，可打开原页面或回退版本"; save(); changed() }
            "adapter" -> { adapterAvailable = false; message = "原生映射暂不可用，可以使用原脚本面板"; changed() }
            "status" -> {
                document.available = event.optBoolean("available")
                adapterAvailable = documents.values.any { it.available }
                val values = event.optJSONObject("settings") ?: JSONObject()
                PluginJson.objects(policy.declaration.getJSONObject("adapter").getJSONArray("settings")).forEach { setting ->
                    PluginUserscriptStore.normalizedSetting(setting, values.opt(setting.getString("key")))?.let { reportedSettings.put(setting.getString("key"), it) }
                }
                if (event.optBoolean("running")) currentStatus = "running"
                val nextLogs = redact(event.optString("logs"))
                if (nextLogs.isNotBlank()) logs = nextLogs.takeLast(12000)
                if (!adapterAvailable) message = "页面正在加载或原生映射不可用，可打开原脚本面板"
                else if (currentStatus == "running") message = "课程任务正在运行"
                save(); changed()
            }
            "storage" -> {
                store.putSetting(event.getString("key"), event.get("value"))
                val snapshot = store.settings()
                documents.forEach { (otherId, other) -> if (otherId != id) reply(otherId, other, JSONObject().put("kind", "values").put("values", snapshot)) }
                changed()
            }
            "request" -> request(id, document, event)
            "abort" -> requests.remove("$id:${event.getString("id")}")?.cancel()
            "open" -> open(event.getString("url"), event.optBoolean("active", true))
            "closed" -> { documents.remove(id); requests.keys.filter { it.startsWith("$id:") }.toList().forEach { requests.remove(it)?.cancel() } }
        }
    }
    private fun request(documentId: String, document: Document, event: JSONObject) {
        val id = event.getString("id"); if (!id.matches(Regex("r[0-9]{1,12}"))) return
        val key = "$documentId:$id"
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - minuteStarted > 60_000) { minuteStarted = now; minuteRequests = 0 }
        if (requests.size >= 8 || ++minuteRequests > 180 || requests.containsKey(key)) {
            reply(documentId, document, JSONObject().put("kind", "response").put("id", id).put("ok", false).put("error", "RESOURCE_LIMIT")); return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val request = event.getJSONObject("request")
            val operation = PluginOperation(session, pkg.manifest, "host.effect", !pkg.official, confirmed = true, packageDigest = pkg.digest, scopeStillActive = ::valid)
            try {
                operations.add(operation)
                val response = withTimeout(request.optLong("timeout", 30000).coerceIn(1000, 120000)) { suspendCancellableCoroutine<JSONObject> { continuation ->
                    val worker = scope.launch(Dispatchers.IO) {
                    try {
                    val url = request.getString("url").toHttpUrlOrNull() ?: throw PluginException(PluginErrorCode.UNTRUSTED_URL, "无效请求地址")
                    val method = request.optString("method", "GET")
                    val purpose = if (runCatching { policy.request(url, method, "mutation", null) }.isSuccess) "mutation" else "query"
                    policy.request(url, method, purpose, null); dataGuard.requireNetwork(url)
                    val headers = request.optJSONObject("headers") ?: JSONObject()
                    for (key in headers.keys()) if (key.lowercase() in setOf("referer", "origin") && !policy.resource(headers.getString(key))) throw PluginException(PluginErrorCode.UNTRUSTED_URL, "脚本请求头引用了未声明的来源")
                    val input = JSONObject().put("url", url.toString()).put("method", method).put("purpose", purpose).put("headers", headers)
                    if (method == "POST" && !request.isNull("body")) input.put("body", request.getString("body"))
                    if (request.optString("responseType") == "arraybuffer") input.put("responseType", "base64")
                    val host = PluginHost(operation, File(app.filesDir, "academic-plugin-storage"), PluginWebSessionCookies.jar(app, pkg, session, ::valid), dataGuard = dataGuard,
                        requestGuard = { target, verb, intent, form -> policy.request(target, verb, intent, form); dataGuard.requireNetwork(target) }, userscriptHeaders = true)
                    val result = host.call("http", input).getJSONObject("data")
                    if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                    }
                    continuation.invokeOnCancellation { operation.close(); worker.cancel() }
                } }
                reply(documentId, document, JSONObject().put("kind", "response").put("id", id).put("ok", true).put("response", response))
            } catch (e: Exception) {
                val code = operation.failure(when (e) { is TimeoutCancellationException -> PluginErrorCode.TIMEOUT; is CancellationException -> PluginErrorCode.CANCELLED; is PluginException -> e.code; else -> PluginErrorCode.NETWORK_RETRYABLE }, "脚本请求未完成").code
                reply(documentId, document, JSONObject().put("kind", "response").put("id", id).put("ok", false).put("error", code.name))
                if (code == PluginErrorCode.RESULT_UNKNOWN) { message = "提交结果不确定，请核对平台进度后继续"; stop("result_unknown") }
            } finally { operation.close(); operations.remove(operation); requests.remove(key) }
        }
        requests[key] = job; job.start()
    }
    private fun removeView(view: WebView) {
        documents.entries.removeAll { it.value.view === view }; views.remove(view); generations.remove(view)
        (view.parent as? android.view.ViewGroup)?.removeView(view); view.stopLoading(); view.destroy()
        if (visible.value === view) visible.value = views.lastOrNull()
        if (views.isEmpty()) stop("stopped")
    }
    fun stop(reason: String) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { android.os.Handler(android.os.Looper.getMainLooper()).post { stop(reason) }; return }
        if (closed) return
        currentStatus = if (operations.any { it.mutationSent }) "result_unknown" else reason
        if (reason == "interrupted") message = "任务被中断，继续前请核对平台进度"
        else if (reason == "stopped") message = "已停止，继续时会重新核对课程页面"
        if (currentStatus == "result_unknown") message = "提交结果不确定，请核对平台进度后继续"
        // A removed account must never be recreated by a late WebView callback.
        runCatching { save() }; closed = true; operations.forEach(PluginOperation::close); scope.cancel(); documents.clear(); requests.clear(); pendingDialog = null
        views.toList().forEach { view -> (view.parent as? android.view.ViewGroup)?.removeView(view); view.stopLoading(); view.destroy() }
        views.clear(); visible.value = null; session.retire(); lease.close(); onStopped(); PluginForegroundWork.remove(app, handle)
        app.getSystemService(NotificationManager::class.java).cancel(handle.hashCode())
        accounts.cleanRetiredProfiles(); changed()
    }
    private fun redact(value: String) = value.replace(Regex("(?i)(token|password|cookie|authorization)\\s*[:=]\\s*[^\\s,;]+"), "$1=[已隐藏]")
        .replace(Regex("(?i)\\b[a-f0-9]{32,}\\b"), "[已隐藏]").replace(Regex("(?<![0-9])1[3-9][0-9]{9}(?![0-9])"), "[账号]")
}

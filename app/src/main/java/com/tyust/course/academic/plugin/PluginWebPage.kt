package com.tyust.course.academic.plugin

import com.tyust.course.ui.system.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.tyust.course.academic.AcademicSession
import kotlinx.coroutines.*
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable fun PluginWebPage(pkg: PluginPackage, page: PluginPage, session: AcademicSession, interaction: NativePluginInteraction, active: () -> Boolean, onClose: () -> Unit, scopeKey: String) {
    val app = LocalContext.current
    val bridgeCapabilities = remember { PluginJson.objects(org.json.JSONArray(app.assets.open("academic-plugin/host-capabilities.json").bufferedReader().use { it.readText() })).filter { it.optBoolean("web", false) }.map { it.getString("name") }.toSet() }
    val declaration = NativePluginContract.page(pkg.manifest, page.templateId)
    val serverId = declaration.getString("serverId")
    val accounts = remember { PluginServiceAccounts(app) }
    val origin = accounts.server(pkg, serverId).getString("origin")
    val policy = remember(scopeKey) { PluginWebPolicy(origin, declaration) }
    val initialUrl = policy.initialUrl
    val owner: PluginWebRetainer = androidx.lifecycle.viewmodel.compose.viewModel()
    val state = remember(scopeKey) { owner.obtain(scopeKey) }
    // A fresh process has no retained WebView history; never silently replay a submitted document.
    var opened by rememberSaveable(scopeKey) { mutableStateOf(false) }
    var requireResume by remember { mutableStateOf(opened && state.history == null) }
    val supported = remember(policy.browser) { policy.supported(
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE),
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) }
    fun external(url: String) {
        if (!policy.external(url)) { state.fail("此链接需要其他应用，请在浏览器中继续"); return }
        runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { state.fail("没有可打开此网页的浏览器") }
    }
    val scope = rememberCoroutineScope()
    val gate = remember { PluginWebGate(origin) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var webGeneration by remember { mutableIntStateOf(0) }
    var loading by state::loading
    var problem by state::problem
    var canGoBack by state::back
    fun ownerValid() = AcademicProviderRegistry.isCurrentPackage(pkg.manifest.id, pkg.digest) &&
        AcademicProviderRegistry.isEnabled(pkg.manifest.id) && accounts.selected(pkg.manifest.id, serverId) == session.key.accountKey
    fun saveHistory() {
        if (ownerValid() && state.canReplay && web != null) {
            state.history = android.os.Bundle().also { web?.saveState(it) }
            state.scrollX = web?.scrollX ?: 0; state.scrollY = web?.scrollY ?: 0
        } else state.history = null
    }
    var replace by remember { mutableStateOf(false) }
    val reduced = rememberGlassAccessibilityMode().reduceMotion
    val contentAlpha by animateFloatAsState(if (state.firstContent) 1f else 0f,
        if (reduced) snap() else tween(180), label = "webFirstContent")
    var host: NativeCapabilityHost? by remember { mutableStateOf(null) }
    val jobs = remember { mutableSetOf<Job>() }
    var gestureAt by remember { mutableLongStateOf(0L) }
    var documentEpoch by remember { mutableLongStateOf(0L) }
    fun revoke() { documentEpoch++; gate.revoke(); host?.close(); host = null; jobs.toList().forEach(Job::cancel); jobs.clear() }
    fun bindDocument(view: WebView, url: String) {
        if (policy.browser || web !== view || !active() || problem.isNotBlank() || !gate.owns(url) || view.url != url) return
        revoke()
        val instance = gate.bind(url)
        host = NativeCapabilityHost(app, pkg, session, interaction, disclosureOrigin = origin) { active() && gate.current(instance) }
        val pageInfo = JSONObject().put("pageId", page.id).put("templateId", page.templateId).put("params", page.params)
        val js = """(function(){const instance=${JSONObject.quote(instance)};let n=0;const pending=new Map();zfBridge.onmessage=e=>{const r=JSON.parse(e.data);if(r.instance!==instance)return;const p=pending.get(r.id);if(p){pending.delete(r.id);r.ok?p.resolve(r.data):p.reject(r.error)}};window.zf={page:Object.freeze($pageInfo),call:(capability,input={},version=1)=>new Promise((resolve,reject)=>{const id='web_'+instance+'_'+(++n);pending.set(id,{resolve,reject});zfBridge.postMessage(JSON.stringify({id,instance,capability,version,input}));setTimeout(()=>{if(pending.delete(id))reject({code:'TIMEOUT',message:'宿主调用超时'})},120000)})};window.dispatchEvent(new Event('zf-ready'));})();"""
        view.evaluateJavascript(js, null)
    }
    DisposableEffect(Unit) {
        val lease = PluginVersionLeases.acquire(pkg.manifest.id)
        accounts.cleanRetiredProfiles()
        onDispose {
            val view = web
            saveHistory()
            if (!ownerValid()) owner.remove(scopeKey)
            revoke(); view?.stopLoading(); view?.destroy(); web = null; lease.close(); accounts.cleanRetiredProfiles()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_RESUME -> web?.onResume()
            Lifecycle.Event.ON_PAUSE -> { saveHistory(); web?.onPause() }
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun back() { if (web?.canGoBack() == true) { revoke(); web?.goBack() } else onClose() }
    fun home() { requireResume = false; problem = ""; state.canReplay = true; if (web == null) webGeneration++ else web?.loadUrl(initialUrl) }
    fun pin() {
        val pinned = PluginPages.registry.pinned()
        if (page.id in pinned) GlassToaster.show("已固定到主导航")
        else if (pinned.size < 5) PluginPages.registry.customize(pinned + page.id) else replace = true
    }
    LaunchedEffect(loading, state.url, webGeneration) {
        if (loading && supported && !requireResume) {
            delay(30_000)
            if (loading) { web?.stopLoading(); state.fail("网页加载超时，请检查网络后重试") }
        }
    }
    BackHandler { back() }
    GlassPageScaffold(title = page.title, topBar = {
        SystemTopBar(title = page.title, collapseFraction = 1f,
            navigationIcon = { SystemIconButton(Icons.Outlined.ArrowBack, "返回", { back() }) }, actions = {
        SystemActionMenu("网页选项", listOf(
            SystemMenuAction("前进", Icons.AutoMirrored.Outlined.ArrowForward, { web?.goForward() }, enabled = state.forward),
            SystemMenuAction("刷新", Icons.Outlined.Refresh, { if (state.canReplay) { problem = ""; web?.reload() ?: home() } else state.fail("上次页面包含提交操作，请返回入口继续，避免重复提交") }, enabled = supported),
            SystemMenuAction("回到入口", Icons.Outlined.Home, { home() }),
            SystemMenuAction("固定到主导航", Icons.Outlined.PushPin, { pin() }),
            SystemMenuAction("在浏览器打开", Icons.Outlined.OpenInBrowser, { external(state.url.takeIf(policy::external) ?: initialUrl) }),
            SystemMenuAction("清理此网站账号数据", Icons.Outlined.DeleteOutline, { scope.launch {
                if (interaction.confirm("清理此网站账号数据", "退出当前网站账号并清理它在本机的登录、存储和历史记录。其他服务账号和学校数据不受影响。")) {
                    if (!active() || !ownerValid()) return@launch
                    runCatching { accounts.clearCurrent(pkg, serverId, session.key.accountKey) }
                        .onSuccess { state.history = null; owner.remove(scopeKey); revoke(); onClose() }
                        .onFailure { state.fail(it.message ?: "网站数据清理失败") }
                }
            } }),
            SystemMenuAction("关闭网页", Icons.Outlined.Close, onClose)
        ))
        })
    }) { padding ->
    Column(Modifier.pluginWebViewport(padding)) {
        if (!supported || requireResume) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (!supported) "当前网页组件暂不支持独立账号空间，可以在浏览器中继续使用。" else "网页已恢复，请重新打开入口继续。上次提交操作不会自动重发。")
                if (supported) SystemDialogButton(primary = true, onClick = { home() }) { Text("打开网页入口") }
                SystemDialogButton(onClick = { external(initialUrl) }) { Text("在浏览器打开") }
            }
        } else {
        if (loading && state.firstContent) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.externalUrl?.let { target ->
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("此页面需在浏览器中继续", Modifier.weight(1f))
                SystemDialogButton(onClick = { external(target) }) { Text("打开") }
                SystemDialogButton(onClick = { state.externalUrl = null }) { Text("关闭") }
            }
        }
        if (problem.isNotBlank()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(problem)
                SystemDialogButton(primary = true, onClick = { problem = ""; loading = true; if (web == null) webGeneration++ else if (state.canReplay) web?.reload() else home() }) { Text(if (state.canReplay) "重试" else "回到入口") }
                SystemDialogButton(onClick = { external(initialUrl) }) { Text("在浏览器打开") }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        key(webGeneration) {
        AndroidView(modifier = Modifier.fillMaxSize().alpha(contentAlpha).then(
            if (!state.firstContent || problem.isNotBlank()) Modifier.clearAndSetSemantics {} else Modifier), factory = { context ->
            WebView(context).also { view ->
                web = view
                val profile = ProfileStore.getInstance().getOrCreateProfile(accounts.profile(pkg, serverId))
                WebViewCompat.setProfile(view, profile.name)
                profile.cookieManager.setAcceptThirdPartyCookies(view, false)
                view.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; allowFileAccess = false; allowContentAccess = false; mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; setSupportMultipleWindows(false); mediaPlaybackRequiresUserGesture = true }
                view.setOnTouchListener { _, event ->
                    if (!state.firstContent || problem.isNotBlank() || !active()) true else {
                        if (event.actionMasked == MotionEvent.ACTION_UP) gestureAt = SystemClock.elapsedRealtime()
                        false
                    }
                }
                if (!policy.browser) WebViewCompat.addWebMessageListener(view, "zfBridge", setOf(origin)) { _, message, sourceOrigin, isMainFrame, reply ->
                    var requestId = ""
                    var requestInstance = ""
                    try {
                        val raw = message.data ?: throw PluginException(PluginErrorCode.VALIDATION_FAILED, "只接受 JSON 消息")
                        if (raw.toByteArray().size > 262144) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "网页消息过大")
                        val request = PluginJson.parse(raw); requestId = request.getString("id")
                        val instance = request.getString("instance")
                        requestInstance = instance
                        gate.accept(sourceOrigin.toString(), isMainFrame, instance, requestId)
                        val capability = request.getString("capability")
                        if (capability !in bridgeCapabilities || !active()) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "网页未获此宿主能力")
                        val currentHost = host ?: throw PluginException(PluginErrorCode.STALE_CONTEXT, "网页尚未就绪")
                        val gesture = SystemClock.elapsedRealtime() - gestureAt in 0..1500
                        gestureAt = 0
                        val id = requestId
                        val job = scope.launch(start = CoroutineStart.LAZY) {
                            val result = try {
                                val effect = JSONObject().put("id", id).put("capability", capability).put("version", request.optInt("version", 1)).put("input", request.optJSONObject("input") ?: JSONObject())
                                PluginJson.success(currentHost.execute(effect, NativeFlow(gesture)))
                            } catch (e: Exception) { PluginJson.error((e as? PluginException)?.code ?: PluginErrorCode.CANCELLED, e.message ?: "调用已取消") }
                            if (gate.current(instance) && active()) reply.postMessage(result.put("id", id).put("instance", instance).toString())
                        }
                        jobs.add(job); job.invokeOnCompletion { jobs.remove(job) }; job.start()
                    } catch (e: Exception) { reply.postMessage(PluginJson.error((e as? PluginException)?.code ?: PluginErrorCode.VALIDATION_FAILED, e.message ?: "无效网页请求").put("id", requestId).put("instance", requestInstance).toString()) }
                }
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        // shouldOverrideUrlLoading is not called for POST. Observe method only;
                        // never intercept bodies, cookies or headers, and never replay a POST on restore.
                        if (request.isForMainFrame && !policy.allows(request.url.toString())) {
                            // Enforce the same origin scope before a form request reaches
                            // the network; onPageStarted alone can arrive too late.
                            return WebResourceResponse("text/plain", "UTF-8", 403, "Navigation outside declared origins",
                                mapOf("Cache-Control" to "no-store"),
                                "此页面需在浏览器中继续，请通过网页选项打开。".byteInputStream())
                        }
                        if (request.isForMainFrame && web === view) state.canReplay = request.method.equals("GET", true)
                        return null
                    }
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { if (web !== view) return; revoke(); loading = true; problem = ""; canGoBack = view.canGoBack(); state.url = url
                        if (!policy.allows(url)) { view.stopLoading(); loading = false; state.firstContent = true; state.externalUrl = url } }
                    override fun onPageFinished(view: WebView, url: String) {
                        if (web !== view || !active() || !policy.allows(url)) return
                        if (problem.isBlank()) state.ready(); canGoBack = view.canGoBack(); state.forward = view.canGoForward()
                        view.scrollTo(state.scrollX, state.scrollY); state.scrollX = 0; state.scrollY = 0
                        bindDocument(view, url)
                    }
                    override fun onPageCommitVisible(view: WebView, url: String) { if (web === view && policy.allows(url) && problem.isBlank()) state.firstContent = true }
                    override fun onFormResubmission(view: WebView, dontResend: android.os.Message, resend: android.os.Message) {
                        dontResend.sendToTarget(); state.canReplay = false; state.fail("上次页面包含提交操作，请返回入口继续，避免重复提交")
                    }
                    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                        if (web !== view) return
                        canGoBack = view.canGoBack(); state.forward = view.canGoForward(); state.url = url
                        if (gate.documentUrl()?.let { it != url } == true) {
                            revoke()
                            bindDocument(view, url)
                        }
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return false
                        if (policy.allows(request.url.toString())) {
                            state.navigation(request.url.toString(), request.method)
                            return false
                        }
                        revoke()
                        loading = false
                        state.firstContent = true
                        if (policy.external(request.url.toString())) state.externalUrl = request.url.toString()
                        else state.fail("网站请求打开其他应用，请使用浏览器继续")
                        return true
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (web === view && request.isForMainFrame) { revoke(); state.fail("网页暂时无法加载，请检查网络后重试") } }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) { if (web === view && request.isForMainFrame && error.statusCode >= 400) { revoke(); state.fail("网站返回 ${error.statusCode}") } }
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) { handler.cancel(); if (web === view) { revoke(); state.fail("网站证书验证失败") } }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { if (web === view) { revoke(); state.history = null; canGoBack = false; state.fail("网页进程已退出，请重试"); web = null }; view.destroy(); return true }
                }
                view.webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                        val document = if (policy.browser) view.url else gate.document()
                        val epoch = documentEpoch
                        val job = scope.launch {
                            val uri = try { if (active() && policy.allows(view.url.orEmpty()) && interaction.confirm("选择文件", "允许 ${pkg.manifest.name} 向当前网页提供你选择的文件？")) interaction.pick(params.acceptTypes.filter { it.isNotBlank() }.take(8).toTypedArray().ifEmpty { arrayOf("*/*") }) else null } catch (_: Exception) { null }
                            val stillCurrent = epoch == documentEpoch && document != null && (if (policy.browser) view.url == document && web === view else gate.current(document)) && active() && policy.allows(view.url.orEmpty())
                            callback.onReceiveValue(if (stillCurrent) uri?.let { arrayOf(it) } else null)
                        }; jobs.add(job); job.invokeOnCompletion { jobs.remove(job) }; return true
                    }
                }
                opened = true
                val restored = state.history?.takeIf { state.canReplay }?.let { runCatching { view.restoreState(it) }.getOrNull() }
                if (restored == null) view.loadUrl(initialUrl)
                else { state.ready(); state.back = view.canGoBack(); state.forward = view.canGoForward() }
            }
        }, update = { view ->
            val visible = state.firstContent && problem.isBlank()
            view.updatePluginWebInput(visible)
        })
        }
        if (!state.firstContent) Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("正在打开网页…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            repeat(4) { Box(Modifier.fillMaxWidth(if (it == 3) .65f else 1f).height(22.dp).background(MaterialTheme.colorScheme.surfaceVariant)) }
        }
        }
        }
    }
    }
    if (replace) SystemDialog(onDismissRequest = { replace = false }, title = { Text("替换哪个导航入口") }, confirmButton = { SystemDialogButton(onClick = { replace = false }) { Text("取消") } }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PluginPages.registry.pinned().filter { it != PluginPageRegistry.SETTINGS }.forEach { id ->
                SystemDialogButton(onClick = { PluginPages.registry.customize(PluginPages.registry.pinned().map { if (it == id) page.id else it }); replace = false }) { Text(PluginPages.registry.page(id)?.title ?: id) }
            }
        }
    }
}

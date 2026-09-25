package com.tyust.course.academic.plugin

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
@Composable fun PluginWebPage(pkg: PluginPackage, page: PluginPage, session: AcademicSession, interaction: NativePluginInteraction, active: () -> Boolean) {
    val app = LocalContext.current
    val declaration = NativePluginContract.page(pkg.manifest, page.templateId)
    val serverId = declaration.getString("serverId")
    val accounts = remember { PluginServiceAccounts(app) }
    val origin = accounts.server(pkg, serverId).getString("origin")
    val initialUrl = origin + declaration.optString("path", "/")
    val supported = remember { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }
    if (!supported) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("此设备的网页组件暂不支持独立账号空间，请在系统浏览器中使用此服务。")
            Button(onClick = { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(initialUrl))) }) { Text("在浏览器打开") }
        }; return
    }
    val scope = rememberCoroutineScope()
    val gate = remember { PluginWebGate(origin) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var webGeneration by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var problem by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var host: NativeCapabilityHost? by remember { mutableStateOf(null) }
    val jobs = remember { mutableSetOf<Job>() }
    var gestureAt by remember { mutableLongStateOf(0L) }
    fun revoke() { gate.revoke(); host?.close(); host = null; jobs.toList().forEach(Job::cancel); jobs.clear() }
    fun bindDocument(view: WebView, url: String) {
        if (web !== view || !active() || problem.isNotBlank() || !gate.owns(url) || view.url != url) return
        revoke()
        val instance = gate.bind(url)
        host = NativeCapabilityHost(app, pkg, session, interaction) { active() && gate.current(instance) }
        val pageInfo = JSONObject().put("pageId", page.id).put("templateId", page.templateId).put("params", page.params)
        val js = """(function(){const instance=${JSONObject.quote(instance)};let n=0;const pending=new Map();zfBridge.onmessage=e=>{const r=JSON.parse(e.data);if(r.instance!==instance)return;const p=pending.get(r.id);if(p){pending.delete(r.id);r.ok?p.resolve(r.data):p.reject(r.error)}};window.zf={page:Object.freeze($pageInfo),call:(capability,input={},version=1)=>new Promise((resolve,reject)=>{const id='web_'+instance+'_'+(++n);pending.set(id,{resolve,reject});zfBridge.postMessage(JSON.stringify({id,instance,capability,version,input}));setTimeout(()=>{if(pending.delete(id))reject({code:'TIMEOUT',message:'宿主调用超时'})},120000)})};window.dispatchEvent(new Event('zf-ready'));})();"""
        view.evaluateJavascript(js, null)
    }
    DisposableEffect(Unit) {
        val lease = PluginVersionLeases.acquire(pkg.manifest.id)
        accounts.cleanRetiredProfiles()
        onDispose { revoke(); web?.stopLoading(); web?.destroy(); web = null; lease.close(); accounts.cleanRetiredProfiles() }
    }
    BackHandler(canGoBack) { revoke(); web?.goBack() }
    Column(Modifier.fillMaxSize()) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (problem.isNotBlank()) Row(Modifier.padding(12.dp)) { Text(problem, modifier = Modifier.weight(1f)); TextButton(onClick = { problem = ""; loading = true; if (web == null) webGeneration++ else web?.reload() }) { Text("重试") } }
        key(webGeneration) {
        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
            WebView(context).also { view ->
                web = view
                val profile = ProfileStore.getInstance().getOrCreateProfile(accounts.profile(pkg, serverId))
                WebViewCompat.setProfile(view, profile.name)
                profile.cookieManager.setAcceptThirdPartyCookies(view, false)
                view.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; allowFileAccess = false; allowContentAccess = false; mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; setSupportMultipleWindows(false); mediaPlaybackRequiresUserGesture = true }
                view.setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_UP) gestureAt = SystemClock.elapsedRealtime(); false }
                WebViewCompat.addWebMessageListener(view, "zfBridge", setOf(origin)) { _, message, sourceOrigin, isMainFrame, reply ->
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
                        if (capability !in BRIDGE_CAPABILITIES || !active()) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "网页未获此宿主能力")
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
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { if (web !== view) return; revoke(); loading = true; problem = ""; canGoBack = view.canGoBack() }
                    override fun onPageFinished(view: WebView, url: String) {
                        if (web !== view) return
                        loading = false; canGoBack = view.canGoBack()
                        bindDocument(view, url)
                    }
                    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                        canGoBack = view.canGoBack()
                        if (gate.documentUrl()?.let { it != url } == true) {
                            revoke()
                            bindDocument(view, url)
                        }
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return false
                        if (gate.owns(request.url.toString())) return false
                        revoke()
                        if (request.hasGesture() && request.url.scheme in setOf("https", "http")) app.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        return true
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) { revoke(); loading = false; problem = "网页暂时无法加载，请检查网络后重试" } }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) { if (request.isForMainFrame && error.statusCode >= 400) { revoke(); loading = false; problem = "服务返回 ${error.statusCode}" } }
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) { handler.cancel(); revoke(); loading = false; problem = "服务证书验证失败" }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { if (web === view) { revoke(); loading = false; canGoBack = false; problem = "网页进程已退出，请重试"; web = null }; view.destroy(); return true }
                }
                view.webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                        val document = gate.document()
                        val job = scope.launch {
                            val uri = try { if (active() && gate.owns(view.url.orEmpty()) && interaction.confirm("选择文件", "允许 ${pkg.manifest.name} 向当前网页提供你选择的文件？")) interaction.pick(params.acceptTypes.filter { it.isNotBlank() }.take(8).toTypedArray().ifEmpty { arrayOf("*/*") }) else null } catch (_: Exception) { null }
                            val stillCurrent = document != null && gate.current(document) && active() && gate.owns(view.url.orEmpty())
                            callback.onReceiveValue(if (stillCurrent) uri?.let { arrayOf(it) } else null)
                        }; jobs.add(job); job.invokeOnCompletion { jobs.remove(job) }; return true
                    }
                }
                view.loadUrl(initialUrl)
            }
        })
        }
    }
}

private val BRIDGE_CAPABILITIES = setOf("pages.register", "pages.open", "pages.close", "pages.unregister", "navigation.page", "navigation.back", "navigation.url", "files.pick", "files.read", "files.create", "files.write", "files.open", "files.share", "academic.study.snapshot", "academic.study.refresh", "academic.schedule.preview", "academic.schedule.confirm", "services.discover", "services.call", "workflow.prepare", "workflow.step", "workflow.reconcile", "workflow.cancel", "workflow.list")

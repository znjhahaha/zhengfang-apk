package com.tyust.course.academic.plugin

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tyust.course.ui.theme.CourseSelectorTheme

/** Legacy browsing shares cookies with native requests through the private service, never through JS. */
class PluginLegacyWebActivity : ComponentActivity() {
    private var browser: WebView? = null
    private var currentUrl by mutableStateOf("")
    private var progress by mutableFloatStateOf(0f)
    private var error by mutableStateOf("")
    private var external by mutableStateOf("")
    @Volatile private var canReplay = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ticket = intent.getStringExtra("ticket")
        val page = LegacyWebRuntime.page?.takeIf { it.ticket == ticket && it.expires > SystemClock.elapsedRealtime() }
        if (page == null || LegacyWebRuntime.activity != null) { finish(); return }
        LegacyWebRuntime.page = null
        LegacyWebRuntime.activity = this
        val policy = page.policy
        currentUrl = policy.initialUrl
        browser = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                allowFileAccess = false; allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = true
                setSupportMultipleWindows(false)
                builtInZoomControls = true; displayZoomControls = false
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { this@PluginLegacyWebActivity.progress = newProgress / 100f }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    if (policy.allows(target)) return false
                    external = target.takeIf(policy::external).orEmpty()
                    if (external.isBlank()) error = "此链接需要在学习通官方应用中打开"
                    return true
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.isForMainFrame && !policy.allows(request.url.toString())) return WebResourceResponse(
                        "text/plain", "UTF-8", 403, "Outside declared origins", emptyMap(), "此网页不在插件声明范围内".byteInputStream())
                    if (request.isForMainFrame) canReplay = request.method.equals("GET", true)
                    return null
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (!policy.allows(url)) { view.stopLoading(); external = url.takeIf(policy::external).orEmpty(); return }
                    currentUrl = url; error = ""
                }
                override fun onPageFinished(view: WebView, url: String) { CookieManager.getInstance().flush() }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                    if (request.isForMainFrame) { error = "网页加载失败，请检查网络后重试"; this@PluginLegacyWebActivity.progress = 1f }
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, failure: android.net.http.SslError) {
                    handler.cancel(); error = "网站证书校验失败"
                }
                override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
                    dontResend.sendToTarget(); canReplay = false; error = "请回到入口继续，上次提交不会自动重发"
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    finish(); return true
                }
            }
        }
        setResult(RESULT_OK)
        setContent {
            CourseSelectorTheme {
                BackHandler { back() }
                Surface {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton({ back() }) { Text("返回") }
                            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                Text(page.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text(Uri.parse(currentUrl).host.orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                            TextButton({ error = ""; browser?.loadUrl(policy.initialUrl) }) { Text("入口") }
                            TextButton({ if (canReplay) browser?.reload() else error = "请回到入口继续，上次提交不会自动重发" }) { Text("刷新") }
                        }
                        if (progress < 1f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        if (error.isNotBlank()) Text(error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                        if (external.isNotBlank()) Row(Modifier.padding(horizontal = 12.dp)) {
                            Text("此页面需在浏览器中继续", Modifier.weight(1f))
                            TextButton({ runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(external))) }; external = "" }) { Text("打开") }
                            TextButton({ external = "" }) { Text("关闭") }
                        }
                        browser?.let { web -> AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth()) }
                    }
                }
            }
        }
        // A process recreation has no page ticket. Never replay a submitted document.
        browser?.loadUrl(policy.initialUrl)
    }

    private fun back() { if (browser?.canGoBack() == true) browser?.goBack() else finish() }
    override fun onPause() { browser?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); browser?.onResume() }
    override fun onDestroy() {
        browser?.let { it.stopLoading(); (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() }
        browser = null
        if (LegacyWebRuntime.activity === this) LegacyWebRuntime.activity = null
        super.onDestroy()
    }
}

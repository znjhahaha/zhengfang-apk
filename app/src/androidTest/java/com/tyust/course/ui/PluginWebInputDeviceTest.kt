package com.tyust.course.ui

import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.webkit.WebViewFeature
import com.tyust.course.BuildConfig
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionKey
import com.tyust.course.academic.plugin.*
import com.tyust.course.ui.system.GlassPageScaffold
import com.tyust.course.ui.system.GlassWindowHost
import com.tyust.course.ui.system.LocalAppOverlayBottomInset
import com.tyust.course.ui.theme.CourseSelectorTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real IME and Chromium tests, using only an offline form in the isolated demo app. */
@RunWith(AndroidJUnit4::class)
class PluginWebInputDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue(message, condition())
    }
    private fun findWeb(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWeb(view.getChildAt(it)) }
        else -> null
    }
    private fun web(): WebView? {
        var result: WebView? = null
        onMain { result = findWeb(compose.activity.window.decorView) }
        return result
    }
    private fun js(view: WebView, expression: String): String {
        val done = CountDownLatch(1)
        var result = ""
        onMain { view.evaluateJavascript(expression) { result = it; done.countDown() } }
        assertTrue("JavaScript callback timed out", done.await(5, TimeUnit.SECONDS))
        return result
    }
    private fun imeVisible(): Boolean {
        var shown = false
        onMain { shown = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        return shown
    }
    private fun assertBottom(view: WebView, embedded: Boolean) {
        // Root insets report the final IME height at once, while Compose follows the
        // IME animation frame by frame. Slow software-rendered emulators can still be
        // mid-animation here, so wait for the layout to settle instead of one sample.
        val started = SystemClock.uptimeMillis()
        var actual = 0
        var expected = 0
        do {
            compose.waitForIdle()
            onMain {
                val root = compose.activity.window.decorView
                val insets = requireNotNull(ViewCompat.getRootWindowInsets(root))
                val origin = IntArray(2).also(root::getLocationOnScreen)
                val webOrigin = IntArray(2).also(view::getLocationOnScreen)
                val overlay = if (embedded) (96 * root.resources.displayMetrics.density).toInt() else 0
                val bottom = maxOf(insets.getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars()).bottom, overlay)
                expected = origin[1] + root.height - bottom
                actual = webOrigin[1] + view.height
            }
            if (kotlin.math.abs(expected - actual) <= 3) break
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() - started < 3_000)
        val waited = SystemClock.uptimeMillis() - started
        if (waited > 0) android.util.Log.i("PluginWebInputTest", "viewport settled after ${waited}ms")
        assertEquals("WebView does not end at the visible viewport boundary after ${waited}ms", expected.toDouble(), actual.toDouble(), 3.0)
    }
    private fun tapField(view: WebView, id: String) {
        js(view, "document.getElementById('$id').scrollIntoView({block:'center'});true")
        SystemClock.sleep(100)
        val rect = JSONObject(js(view, "(function(){const r=document.getElementById('$id').getBoundingClientRect();return {x:(r.x+r.width/2)*devicePixelRatio,y:(r.y+r.height/2)*devicePixelRatio}})()"))
        val location = IntArray(2)
        onMain { view.getLocationOnScreen(location) }
        device.click(location[0] + rect.getDouble("x").toInt(), location[1] + rect.getDouble("y").toInt())
    }

    @Test fun standaloneViewportAndFocusSurviveTwentyKeyboardCycles() = exercise(false, false)
    @Test fun navigationViewportAndFocusSurviveTwentyKeyboardCycles() = exercise(true, false)
    @Test fun productionStandaloneWebPageKeepsItsDocumentAndInputConnection() = exercise(false, true)
    @Test fun productionNavigationWebPageKeepsItsDocumentAndInputConnection() = exercise(true, true)

    private fun exercise(embedded: Boolean, production: Boolean) {
        assumeTrue("Use the isolated demo variant", BuildConfig.UI_PREVIEW)
        if (production) assumeTrue("Production web plugins require a MULTI_PROFILE capable WebView", WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val oldIme = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        val loads = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path !in setOf("/", "/next")) return MockResponse().setResponseCode(204)
                loads.incrementAndGet()
                return MockResponse().setHeader("Content-Type", "text/html;charset=UTF-8").setBody("""
                    <!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>
                    <style>body{margin:0;padding:20px;font:18px sans-serif;background:white}input{display:block;box-sizing:border-box;width:100%;height:48px;margin:16px 0;font:20px sans-serif}p{margin:0}</style>
                    <p>离线网页输入测试</p><input id='first' placeholder='输入文字'><input id='second' inputmode='numeric' placeholder='输入数字'>
                    <div style='height:800px'>仅用于输入法回归，不连接真实学校。</div>
                    <script>window.fixtureToken=Date.now()+':'+Math.random();</script>
                """).setBodyDelay(300, TimeUnit.MILLISECONDS)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        var revision by mutableIntStateOf(0)
        val origin = "http://127.0.0.1:${server.port}"
        val pkg = PluginPackage(PluginManifest(JSONObject("""{"id":"fixture.web.ime","name":"离线网页","version":"1.0.0","kind":"native","apiVersion":3,"capabilities":[],"permissions":[],"network":[],"servers":[{"id":"site","origin":"$origin"}],"contributes":{"pages":[{"id":"home","title":"网页输入测试","renderer":"web","serverId":"site","path":"/","web":{"mode":"browser"}}],"entries":[]}}""")), "", "fixture", false)
        val session = AcademicSession(AcademicSessionKey("fixture", "default"), origin)
        try {
            onMain { compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
            compose.setContent {
                CourseSelectorTheme {
                    GlassWindowHost(capturePage = false) {
                        CompositionLocalProvider(LocalAppOverlayBottomInset provides if (embedded) 96.dp else 0.dp) {
                            Box(Modifier.fillMaxSize().testTag("revision-$revision")) {
                                if (production) PluginWebPage(pkg, PluginPage("fixture.web.ime/home", "网页输入测试", "fixture.web.ime", "home", "web"), session, NoInteraction, { true }, {}, "fixture-$origin")
                                else GlassPageScaffold(title = "网页输入测试") { padding ->
                                    var ready by remember { mutableStateOf(false) }
                                    var view by remember { mutableStateOf<WebView?>(null) }
                                    DisposableEffect(Unit) { onDispose { view?.destroy() } }
                                    AndroidView(modifier = Modifier.pluginWebViewport(padding), factory = { context ->
                                        WebView(context).also { browser ->
                                            view = browser
                                            browser.settings.javaScriptEnabled = true
                                            browser.webViewClient = object : WebViewClient() {
                                                override fun onPageFinished(view: WebView, url: String) { ready = true }
                                            }
                                            browser.loadUrl("$origin/")
                                        }
                                    }, update = { it.updatePluginWebInput(ready) })
                                }
                            }
                        }
                    }
                }
            }
            await("WebView did not load") { web()?.let { js(it, "!!window.fixtureToken") == "true" } == true }
            val original = requireNotNull(web())
            await("Touch focus was not restored") { var ready = false; onMain { ready = original.isEnabled && original.isFocusableInTouchMode }; ready }
            val token = js(original, "window.fixtureToken")
            assertBottom(original, embedded)
            repeat(20) { cycle ->
                val field = if (cycle % 2 == 0) "first" else "second"
                tapField(original, field)
                await("IME did not open on cycle $cycle") { imeVisible() }
                SystemClock.sleep(350)
                assertBottom(original, embedded)
                assertEquals("\"$field\"", js(original, "document.activeElement.id"))
                device.pressKeyCode(android.view.KeyEvent.KEYCODE_1)
                assertTrue(js(original, "document.getElementById('$field').value.length").toInt() > 0)
                onMain { revision++ }
                assertSame("Recomposition replaced the WebView", original, web())
                if (cycle == 0 || cycle == 19) {
                    val file = File(instrumentation.targetContext.getExternalFilesDir(null), "flow-validation/ime-${if (production) "production" else "viewport"}-${if (embedded) "navigation" else "standalone"}-$cycle.png")
                    file.parentFile!!.mkdirs(); assertTrue(device.takeScreenshot(file))
                }
                device.pressBack()
                await("IME did not close") { !imeVisible() }
                SystemClock.sleep(250)
                assertBottom(original, embedded)
                if (cycle == 9) {
                    compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
                    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                }
            }
            assertEquals("Document was reloaded during input", token, js(original, "window.fixtureToken"))
            assertEquals(1, loads.get())
            if (production) {
                js(original, "location.href='/next';true")
                await("Fixture did not navigate") { js(original, "location.pathname") == "\"/next\"" && js(original, "document.readyState") == "\"complete\"" }
                compose.waitForIdle()
                device.pressBack()
                await("Browser back did not restore the previous page") { js(original, "location.pathname") == "\"/\"" }
                compose.waitForIdle()
                assertSame(original, web())
                tapField(original, "first")
                await("IME did not reopen after browser back") { imeVisible() }
                assertBottom(original, embedded)
                device.pressBack()
                await("IME did not close after browser back") { !imeVisible() }
            }
        } finally {
            compose.activityRule.scenario.close()
            server.shutdown()
            device.executeShellCommand(if (oldIme == "null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $oldIme")
        }
    }

    private object NoInteraction : NativePluginInteraction {
        override suspend fun confirm(title: String, message: String) = false
        override suspend fun authenticate(challenge: JSONObject, image: File?): JSONObject? = null
        override suspend fun pick(types: Array<String>): Uri? = null
        override suspend fun notificationPermission() = false
        override fun haptic() = Unit
        override fun navigate(pageId: String, params: JSONObject) = Unit
        override fun back() = Unit
    }
}

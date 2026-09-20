package com.tyust.course.ui

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.uiautomator.UiObject2
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.tyust.course.AcademicWebViewActivity
import com.tyust.course.BuildConfig
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class AcademicWebLoginDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun appWindow(): AccessibilityWindowInfo {
        val windows = InstrumentationRegistry.getInstrumentation().uiAutomation.windowsOnAllDisplays
        return (0 until windows.size()).asSequence().flatMap { windows.valueAt(it).asSequence() }
            .first { it.root?.packageName?.toString() == context.packageName }
    }
    private fun tap(node: UiObject2) {
        val bounds = node.visibleBounds
        device.executeShellCommand("input -d ${appWindow().displayId} tap ${bounds.centerX()} ${bounds.centerY()}")
        SystemClock.sleep(500)
    }
    private fun back() {
        device.executeShellCommand("input -d ${appWindow().displayId} keyevent 4")
        SystemClock.sleep(500)
    }
    private fun open(url: String) {
        context.startActivity(Intent(context, AcademicWebViewActivity::class.java)
            .putExtra(AcademicWebViewActivity.EXTRA_START_URL, url)
            .putExtra(AcademicWebViewActivity.EXTRA_COOKIE_URL, url)
            .putStringArrayListExtra(AcademicWebViewActivity.EXTRA_ALLOWED_HOSTS, arrayListOf(android.net.Uri.parse(url).authority!!))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
    }
    private fun capture(name: String) {
        val directory = File(context.getExternalFilesDir(null), "flow-validation").apply { mkdirs() }
        val displayId = appWindow().displayId
        val info = device.executeShellCommand("dumpsys display").lineSequence()
            .first { it.contains("mBaseDisplayInfo=DisplayInfo") && Regex("displayId $displayId[\\\",]").containsMatchIn(it) }
        val physical = Regex("uniqueId \\\"local:(\\d+)\\\"").find(info)!!.groupValues[1]
        val file = File(directory, "$name.png")
        device.executeShellCommand("screencap -p -d $physical ${file.absolutePath}")
        assertTrue("No screenshot for display $displayId", file.length() > 0)
    }
    private fun waitText(text: String) = assertTrue("Missing $text", device.wait(Until.hasObject(By.text(text)), 20_000))

    @Test fun loginFieldsSurviveLargeFontsAndActivityRecreation() {
        assumeTrue(BuildConfig.UI_PREVIEW)
        val oldIdle = Configurator.getInstance().waitForIdleTimeout
        val oldFont = device.executeShellCommand("settings get system font_scale").trim()
        val oldIme = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        Configurator.getInstance().waitForIdleTimeout = 0
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = if (request.path.orEmpty().startsWith("/next")) "<h2>模拟教务主页</h2>" else """
                    <form action='/next'><h2>测试登录页</h2><label>测试账号<input id='username' placeholder='学工号' name='username'></label>
                    <label>测试密码<input id='password' type='password' placeholder='密码'></label><button>测试登录</button></form>
                    <p id='viewport'></p><script>document.querySelector('#viewport').textContent=innerHeight>100&&innerWidth>150?'视口就绪':'视口异常';</script>
                """
                return MockResponse().setHeader("Content-Type", "text/html;charset=UTF-8").setBody("""
                    <!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><style>
                    body{margin:0;padding:16px;box-sizing:border-box;font:18px sans-serif}label{display:block;margin:12px 0}
                    input,button{box-sizing:border-box;display:block;max-width:100%;font:18px sans-serif;min-height:44px}
                    </style>$body
                """)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            open("http://127.0.0.1:${server.port}/")
            waitText("视口就绪")
            val web = device.findObject(By.clazz("android.webkit.WebView"))
            val inputs = web.findObjects(By.clazz("android.widget.EditText"))
            assertEquals(2, inputs.size)
            assertTrue(inputs.all { it.visibleBounds.height() >= 30 })
            tap(inputs.first())
            inputs.first().text = "fixture-user"
            SystemClock.sleep(750)
            assertTrue(device.findObject(By.clazz("android.webkit.WebView")).visibleBounds.height() > 100)
            capture("web-login-filled")
            back()
            waitText("测试登录")
            tap(device.findObject(By.text("测试登录")))
            waitText("模拟教务主页")
            device.executeShellCommand("settings put system font_scale 2.0")
            SystemClock.sleep(1600)
            waitText("模拟教务主页")
            capture("web-login-large-font-restored")
            tap(device.findObject(By.desc("刷新网页")))
            waitText("模拟教务主页")
        } finally {
            device.executeShellCommand("settings put system font_scale " + oldFont.takeUnless { it == "null" }.orEmpty().ifBlank { "1.0" })
            device.executeShellCommand(if (oldIme == "null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $oldIme")
            Configurator.getInstance().waitForIdleTimeout = oldIdle
            server.shutdown()
        }
    }

    @Test fun visibleSoftwareKeyboardKeepsTheLoginBrowserUsable() {
        assumeTrue(BuildConfig.UI_PREVIEW)
        val oldIme = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        val oldIdle = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout = 0
        try {
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            open("https://i.wzut.edu.cn/cas/login")
            val web = device.wait(Until.findObject(By.clazz("android.webkit.WebView")
                .hasDescendant(By.clazz("android.widget.EditText"))), 30_000)
            assertNotNull("Login fields did not finish loading", web)
            val field = web.findObject(By.clazz("android.widget.EditText"))
            tap(field)
            // MuMu may route input to the host keyboard with zero Android IME insets.
            val visibleIme = device.wait(Until.hasObject(By.text("完成")), 5_000)
            assumeTrue("Device provides no visible Android software keyboard; host input cannot validate IME insets", visibleIme)
            assertTrue(device.findObject(By.clazz("android.webkit.WebView")).visibleBounds.height() > 100)
            capture("web-login-keyboard")
            back()
            waitText("完成登录")
        } finally {
            device.executeShellCommand(if (oldIme == "null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $oldIme")
            Configurator.getInstance().waitForIdleTimeout = oldIdle
        }
    }

    @Test fun wzutLoginFieldsAreReachableInsideTheRealBrowser() {
        assumeTrue(BuildConfig.UI_PREVIEW)
        val oldIdle = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout = 0
        try {
            open("https://i.wzut.edu.cn/cas/login")
            assertTrue(device.wait(Until.hasObject(By.clazz("android.webkit.WebView")), 20_000))
            val end = SystemClock.uptimeMillis() + 30_000
            var count = 0
            while (SystemClock.uptimeMillis() < end) {
                val web = device.findObject(By.clazz("android.webkit.WebView"))
                count = web?.findObjects(By.clazz("android.widget.EditText"))?.count { it.visibleBounds.height() > 10 } ?: 0
                if (count >= 2) break
                SystemClock.sleep(500)
            }
            capture("wzut-login-live")
            assertTrue("Expected visible account and password fields, got $count", count >= 2)
        } finally { device.pressHome(); Configurator.getInstance().waitForIdleTimeout = oldIdle }
    }
}

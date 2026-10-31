package com.tyust.course.login

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.tyust.course.AcademicWebViewActivity
import com.tyust.course.BuildConfig
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** AHUT's portal opens teaching in a new window; the host must retain its monitored WebView. */
@RunWith(AndroidJUnit4::class)
class AcademicWebPopupDeviceTest {
    @Test fun portalWindowOpenReachesTeachingAndAllowsLoginCompletion() {
        assumeTrue(BuildConfig.UI_PREVIEW)
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                    .setHeader("Content-Type", "text/html;charset=UTF-8")
                    .setHeader("Set-Cookie", "JSESSIONID=synthetic; Path=/")
                    .setBody(if (request.path == "/portal") {
                        "<html><body><button onclick=\"window.open('/jsxsd/framework/xsMain.jsp','_blank')\">Open teaching</button></body></html>"
                    } else "<html><body><h1>Synthetic teaching home</h1></body></html>")
            }
            server.start()
            val completion = server.url("/jsxsd/framework/xsMain.jsp").toString()
            val intent = Intent(app, AcademicWebViewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AcademicWebViewActivity.EXTRA_START_URL, server.url("/portal").toString())
                .putExtra(AcademicWebViewActivity.EXTRA_COOKIE_URL, completion)
                .putExtra(AcademicWebViewActivity.EXTRA_COMPLETION_URL, completion)
                .putStringArrayListExtra(AcademicWebViewActivity.EXTRA_ALLOWED_HOSTS, arrayListOf("localhost:${server.port}"))
            app.startActivity(intent)
            try {
                val open = device.wait(Until.findObject(By.text("Open teaching")), 30_000)
                assertNotNull("Synthetic portal did not render", open)
                open.click()
                assertNotNull("New-window teaching page did not reach the monitored browser", device.wait(Until.findObject(By.text("Synthetic teaching home")), 20_000))
                device.wait(Until.findObject(By.text("完成登录")), 5_000).click()
                assertTrue("Completion stayed in the browser instead of returning", device.wait(Until.gone(By.text("Synthetic teaching home")), 5_000))
            } finally { if (device.hasObject(By.text("Synthetic teaching home")) || device.hasObject(By.text("Open teaching"))) device.pressBack() }
        }
    }
}

package com.tyust.course.academic.plugin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Local synthetic cookies only: does not sign in to any website or touch installed plugin accounts. */
@RunWith(AndroidJUnit4::class)
class NativeWebLoginDeviceTest {
    @Test fun nativeSessionWorksWithoutWebViewProfilesAndPersistsCookieUpdates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        var profiles = true
        instrumentation.runOnMainSync { profiles = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }
        assumeFalse("This regression exercises the legacy WebView backend", profiles)
        val pkg = PluginPackage(PluginManifest(JSONObject("""{
            "id":"test.device.native.web.cookies","kind":"native","version":"1.0.0","apiVersion":3,
            "servers":[{"id":"site","origin":"https://login.example.test","authentication":"web",
            "cookieOrigins":["https://login.example.test","https://api.example.test"]}]
        }""")), "", "synthetic", false)
        val accounts = PluginServiceAccounts(app)
        val login = "https://login.example.test/login".toHttpUrl()
        val api = "https://api.example.test/courses".toHttpUrl()
        fun jar() = PluginWebSessionCookies.jar(app, pkg, accounts.session(pkg, "site")) { true }
        fun cookie(value: String) = Cookie.parse(login, value)!!
        try {
            accounts.select(pkg, "site", "Synthetic first")
            val stale = jar()
            stale.saveFromResponse(login, listOf(cookie("SESSION=synthetic-one; Domain=example.test; Path=/; Secure; HttpOnly")))
            assertEquals("synthetic-one", jar().loadForRequest(api).single().value)
            jar().saveFromResponse(login, listOf(cookie("TOKEN=synthetic-two; Domain=example.test; Path=/; Secure")))
            assertEquals(setOf("SESSION", "TOKEN"), jar().loadForRequest(api).map { it.name }.toSet())
            jar().saveFromResponse(login, listOf(cookie("SESSION=gone; Domain=example.test; Path=/; Max-Age=0; Secure")))
            assertEquals("TOKEN", jar().loadForRequest(api).single().name)
            assertTrue(jar().loadForRequest("https://unlisted.example.test/courses".toHttpUrl()).isEmpty())
            accounts.select(pkg, "site", "Synthetic other")
            assertTrue(jar().loadForRequest(api).isEmpty())
            assertThrows(PluginException::class.java) { stale.loadForRequest(api) }
            val original = accounts.select(pkg, "site", "Synthetic first")
            assertEquals("synthetic-two", jar().loadForRequest(api).single().value)
            accounts.clearCurrent(pkg, "site", original)
            assertTrue(jar().loadForRequest(api).isEmpty())
        } finally {
            accounts.clearPlugin(pkg)
        }
    }
}

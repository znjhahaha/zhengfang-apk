package com.tyust.course.academic.plugin

import android.app.Application
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real local HTTP fixtures exercise navigation decisions; this is not WebView/device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class PluginWebMockSiteTest {
    @Test fun localWebsiteAndCrossOriginAuthenticationDoNotGrantBridgeOrAutoFollowExternal() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","text/html").setBody("<html><a href='/login'>Login</a></html>"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","https://login.example.test/auth"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","https://unlisted.example.test/"))
            server.start()
            val origin=server.url("/").toString().removeSuffix("/")
            val policy=PluginWebPolicy(origin,JSONObject("""{"web":{"mode":"browser","navigationOrigins":["https://login.example.test"]}}"""))
            val client=OkHttpClient.Builder().followRedirects(false).build()
            try {
                client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { assertEquals(200,it.code);assertTrue(it.body!!.string().contains("Login")) }
                client.newCall(Request.Builder().url(server.url("/login")).build()).execute().use { assertTrue(policy.allows(it.header("Location")!!));assertFalse(policy.bridges(it.header("Location")!!)) }
                client.newCall(Request.Builder().url(server.url("/outside")).build()).execute().use { assertFalse(policy.allows(it.header("Location")!!));assertTrue(policy.external(it.header("Location")!!)) }
                assertEquals(3,server.requestCount)
            }finally {client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}
        }
    }
}

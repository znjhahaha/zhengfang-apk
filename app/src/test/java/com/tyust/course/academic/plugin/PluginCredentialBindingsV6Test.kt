package com.tyust.course.academic.plugin

import android.app.Application
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionKey
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PluginCredentialBindingsV6Test {
    private fun request(url: String = "https://school.test/auth/login?service=campus") = JSONObject().put("url", url).put("purpose", "auth")
        .put("credential", "opaque").put("bindings", JSONObject().put("query", JSONObject().put("username", JSONObject().put("from", "username")
            .put("transform", JSONObject().put("type", "text").put("trim", true)))))
    private val username = "\u00a0\u3000学生 +&/#?=\ufeff"
    @Test fun trimMatchesJavascriptIncludingNbspFullwidthBomAndExcludingNelControlBytes() {
        for ((value, expected) in listOf("\u00a0\u3000\ufeff用户\u2028\u2029" to "用户", "\u0085user\u0085" to "\u0085user\u0085", "\u001cuser\u001c" to "\u001cuser\u001c", "\u180euser\u200b" to "\u180euser\u200b")) {
            val output = PluginCredentialBindings.apply(request(), JSONObject().put("username", value))
            assertEquals(expected, output.getString("url").toHttpUrl().queryParameter("username"))
        }
    }
    @Test fun aesPrefixUsesTheSameFixedUtf8VectorAsNode() {
        val input = JSONObject("""{"bindings":{"form":{"password":{"from":"password","transform":{"type":"aes-cbc","keyBase64":"MDEyMzQ1Njc4OWFiY2RlZg==","ivBase64":"ZmVkY2JhOTg3NjU0MzIxMA==","prefix":"salt-前缀","trim":true}}}}}""")
        assertEquals("Qx1lk0sieRCZQvR/0BKgZg2Xdqu/k256oLmF7RhQYg8=", PluginCredentialBindings.apply(input, JSONObject().put("password", "\u00a0 密码\u3000")).getJSONObject("form").getString("password"))
    }
    @Test fun previewUsesSyntheticQueryAndRejectsCollisionsFragmentsAndPasswordText() {
        val input = request()
        val preview = PluginCredentialBindings.validateAliases(input)
        assertEquals("validation", preview.getString("url").toHttpUrl().queryParameter("username"))
        assertFalse(preview.toString().contains(username))
        for (suffix in listOf("&username=old", "&%75sername=old", "&username=a&username=b", "#fragment", "#"))
            assertThrows(PluginException::class.java) { PluginCredentialBindings.validateAliases(request(input.getString("url") + suffix)) }
        val bad = request().apply { getJSONObject("bindings").getJSONObject("query").getJSONObject("username").put("from", "password") }
        assertThrows(PluginException::class.java) { PluginCredentialBindings.apply(bad, JSONObject().put("password", "secret")) }
    }
    private fun manifest(origin: String, version: Int = 6, auth: Boolean = true) = PluginManifest(JSONObject().put("id", "test.credential.v6").put("version", "1.0.0")
        .put("kind", "native").put("apiVersion", 3).put("minAppVersionCode", 133).put("permissions", JSONArray(if (auth) listOf("network", "auth") else listOf("network")))
        .put("capabilities", JSONArray(listOf("serviceAuth.start"))).put("requires", JSONArray().put(JSONObject().put("name", "network.request").put("version", version)))
        .put("network", JSONArray().put(JSONObject().put("origin", origin).put("pathPrefix", "/auth").put("methods", JSONArray(listOf("GET", "POST"))).put("purposes", JSONArray(listOf("auth"))))))
    @Test fun hostBindsOnlyAfterPreflightEncodesQueryAndPreservesPrivateResponseUrlWithoutLoggingIt() {
        MockWebServer().use { server ->
            server.start(); val origin = server.url("/").toString().trimEnd('/')
            val session = AcademicSession(AcademicSessionKey("test.credential.v6", "account"), "$origin/")
            val operation = PluginOperation(session, manifest(origin), "serviceAuth.start")
            var reads = 0
            val host = PluginHost(operation, File("build/credential-v6"), credentialResolver = { reads++; JSONObject().put("username", username) })
            try {
                for (url in listOf("$origin/outside", "http://different.test/auth/login", "$origin/auth/login?username=old", "$origin/auth/login#fragment"))
                    assertThrows(PluginException::class.java) { host.call("http", request(url)) }
                assertEquals(0, reads)
                server.enqueue(MockResponse().setBody("ok"))
                val input = request("$origin/auth/login?service=campus")
                val result = host.call("http", input).getJSONObject("data")
                val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("学生 +&/#?=", sent.requestUrl!!.queryParameter("username")); assertEquals("campus", sent.requestUrl!!.queryParameter("service"))
                assertEquals(sent.requestUrl.toString(), result.getString("url")); assertEquals(1, reads)
                assertFalse(host.report().toString().contains("学生"))
            } finally { operation.close(); session.retire() }
        }
    }
    @Test fun hostRejectsOldVersionsUnauthorizedHandlersAndExpiredCredentials() {
        for ((version, method, auth) in listOf(Triple(5, "serviceAuth.start", true), Triple(6, "services.invoke", true), Triple(6, "serviceAuth.start", false), Triple(6, "auth.start", true))) {
            val session = AcademicSession(AcademicSessionKey("test.credential.v6", "account"), "https://school.test/")
            val operation = PluginOperation(session, manifest("https://school.test", version, auth), method)
            var reads = 0
            try { assertThrows(PluginException::class.java) { PluginHost(operation, File("build/credential-v6"), credentialResolver = { reads++; JSONObject().put("username", username) }).call("http", request()) }; assertEquals(0, reads) }
            finally { operation.close(); session.retire() }
        }
        val session = AcademicSession(AcademicSessionKey("test.credential.v6", "account"), "https://school.test/")
        val operation = PluginOperation(session, manifest("https://school.test"), "serviceAuth.start")
        try {
            val host = PluginHost(operation, File("build/credential-v6"), credentialResolver = { throw PluginException(PluginErrorCode.SESSION_EXPIRED, "expired") })
            assertEquals(PluginErrorCode.SESSION_EXPIRED, assertThrows(PluginException::class.java) { host.call("http", request()) }.code)
        } finally { operation.close(); session.retire() }
    }
    @Test fun invalidPrefixTrimAndTextFieldsNeverDowngradeToPlaintext() {
        for (transform in listOf("""{"type":"text","prefix":"bad"}""", """{"type":"text","trim":"yes"}""",
            """{"type":"aes-ecb","keyBase64":"MDEyMzQ1Njc4OWFiY2RlZg==","prefix":"bad"}""")) {
            val input = request(); input.getJSONObject("bindings").getJSONObject("query").getJSONObject("username").put("transform", JSONObject(transform))
            assertThrows(PluginException::class.java) { PluginCredentialBindings.validateAliases(input) }
        }
        val input = JSONObject("""{"credential":"opaque","bindings":{"form":{"password":{"from":"password","transform":{"type":"aes-cbc","keyBase64":"MDEyMzQ1Njc4OWFiY2RlZg==","ivBase64":"ZmVkY2JhOTg3NjU0MzIxMA=="}}}}}""")
        input.getJSONObject("bindings").getJSONObject("form").getJSONObject("password").getJSONObject("transform").put("prefix", "x".repeat(4097))
        assertThrows(PluginException::class.java) { PluginCredentialBindings.validateAliases(input) }
    }
    @Test fun queryEchoesAndRedirectsDoNotEscapeThroughErrorsOrSendAnotherRequest() {
        MockWebServer().use { server ->
            server.start(); val origin = server.url("/").toString().trimEnd('/')
            val session = AcademicSession(AcademicSessionKey("test.credential.v6", "account"), "$origin/")
            val operation = PluginOperation(session, manifest(origin), "serviceAuth.start")
            try {
                val host = PluginHost(operation, File("build/credential-v6"), credentialResolver = { JSONObject().put("username", username) })
                val responses = listOf(MockResponse().setBody("学生 +&/#?="), MockResponse().setResponseCode(302).setHeader("Location", "$origin/auth/next?username=synthetic"))
                for (response in responses) {
                    server.enqueue(response)
                    val error = assertThrows(PluginException::class.java) { host.call("http", request("$origin/auth/login")) }
                    assertFalse(error.message.orEmpty().contains("学生")); assertFalse(error.message.orEmpty().contains("username="))
                    assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                }
                assertEquals(2, server.requestCount)
            } finally { operation.close(); session.retire() }
        }
    }
}

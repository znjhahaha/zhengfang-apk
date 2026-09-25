package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicSessionStore
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class PluginServiceTokenTest {
    private val token = "synthetic-service-token"
    private fun origin(server: MockWebServer) = "http://127.0.0.1:${server.port}"
    private fun start(server: MockWebServer) = server.start(InetAddress.getByName("127.0.0.1"), 0)
    private fun rule(server: MockWebServer, path: String = "/api", allow: Boolean = true) = JSONObject()
        .put("origin", origin(server)).put("pathPrefix", path).put("methods", JSONArray(listOf("GET")))
        .put("purposes", JSONArray(listOf("query"))).apply { if (allow) put("authHeader", "X-Token") }
    private fun manifest(rules: List<JSONObject>, kind: String = "service", api: Int = 3, required: Int = 3) = PluginManifest(JSONObject()
        .put("id", "test.service-token").put("name", "Synthetic service").put("version", "1.0.0")
        .put("kind", kind).put("apiVersion", api).put("capabilities", JSONArray()).put("network", JSONArray(rules))
        .put("contributes", JSONObject().put("academic", true))
        .put("requires", JSONArray().apply { if (required > 0) put(JSONObject().put("name", "network.request").put("version", required)) }))
    private fun host(server: MockWebServer, manifest: PluginManifest): PluginHost {
        val session = AcademicSessionStore().session("synthetic-school", "synthetic-account", origin(server))
        return PluginHost(PluginOperation(session, manifest, if (manifest.isNative) "host.effect" else "service.page"), File("build/service-token-store"))
    }
    private fun request(server: MockWebServer, path: String = "/api/me", headers: JSONObject = JSONObject().put("X-Token", token)) =
        JSONObject().put("url", origin(server) + path).put("purpose", "query").put("headers", headers)
    private fun rejected(code: PluginErrorCode? = null, run: () -> Unit) {
        try { run(); fail("Request must be rejected before sending credentials") }
        catch (error: PluginException) { if (code != null) assertEquals(code, error.code); assertFalse(error.message.orEmpty().contains(token)) }
    }

    @Test fun serviceAndNativePagesSendDeclaredCaseInsensitiveTokens() {
        MockWebServer().use { server ->
            start(server)
            for (kind in listOf("service", "native")) for (name in listOf("X-Token", "x-token", "x-ToKeN")) {
                server.enqueue(MockResponse().setBody("synthetic result"))
                val host = host(server, manifest(listOf(rule(server)), kind))
                val result = host.call("http", request(server, headers = JSONObject().put(name, token))).getJSONObject("data")
                assertEquals("synthetic result", result.getString("body"))
                val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals(token, sent.getHeader("X-Token")); assertNull(sent.getHeader("Authorization"))
                assertFalse(host.report().any { it.toString().contains(token) })
            }
        }
    }

    @Test fun malformedTokensDuplicatesAndMissingDeclarationsSendNothing() {
        MockWebServer().use { server ->
            start(server)
            for (value in listOf("", " ", "bad token", "bad\n", "bad\r\nX-Other: value", "bad\t", "令牌", "x".repeat(8193)))
                rejected(PluginErrorCode.VALIDATION_FAILED) { host(server, manifest(listOf(rule(server)))).call("http", request(server, headers = JSONObject().put("X-Token", value))) }
            rejected(PluginErrorCode.VALIDATION_FAILED) {
                host(server, manifest(listOf(rule(server)))).call("http", request(server, headers = JSONObject().put("X-Token", token).put("x-token", "other")))
            }
            for (m in listOf(manifest(listOf(rule(server, allow = false))), manifest(listOf(rule(server)), api = 2), manifest(listOf(rule(server)), required = 2), manifest(listOf(rule(server)), required = 0)))
                rejected { host(server, m).call("http", request(server)) }
            rejected(PluginErrorCode.VALIDATION_FAILED) {
                host(server, manifest(listOf(rule(server)))).call("http", request(server).put("cookieHeader", JSONObject().put("cookie", "school-token").put("header", "X-Token")))
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun visibleAsciiTokenLengthBoundariesAreAccepted() {
        MockWebServer().use { server ->
            start(server)
            for (value in listOf("!", "x".repeat(8192))) {
                server.enqueue(MockResponse().setBody("ok"))
                host(server, manifest(listOf(rule(server)))).call("http", request(server, headers = JSONObject().put("X-Token", value)))
                assertEquals(value, server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("X-Token"))
            }
        }
    }

    @Test fun tokenPermissionRespectsAllRequestConstraintsAndMostSpecificRules() {
        MockWebServer().use { server ->
            start(server)
            val scoped = rule(server).put("requiredQuery", JSONObject().put("view", "self"))
            for (path in listOf("/api/me", "/apix/me?view=self", "/api/me?view=other", "/api/me?view=self&view=self"))
                rejected(PluginErrorCode.UNTRUSTED_URL) { host(server, manifest(listOf(scoped))).call("http", request(server, path)) }
            rejected(PluginErrorCode.UNTRUSTED_URL) { host(server, manifest(listOf(rule(server)))).call("http", request(server).put("method", "POST")) }
            rejected(PluginErrorCode.UNTRUSTED_URL) { host(server, manifest(listOf(rule(server)))).call("http", request(server).put("purpose", "mutation")) }
            val broad = rule(server, "/")
            val narrow = rule(server, "/api/public", false)
            for (rules in listOf(listOf(broad, narrow), listOf(narrow, broad)))
                rejected(PluginErrorCode.UNTRUSTED_URL) { host(server, manifest(rules)).call("http", request(server, "/api/public/me")) }
            for (rules in listOf(listOf(rule(server), rule(server, allow = false)), listOf(rule(server, allow = false), rule(server))))
                rejected(PluginErrorCode.UNTRUSTED_URL) { host(server, manifest(rules)).call("http", request(server)) }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody("ok"))
            host(server, manifest(listOf(scoped))).call("http", request(server, "/api/me?view=self"))
            assertEquals(token, server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("X-Token"))
        }
    }

    @Test fun redirectsRecheckTokenScopeBeforeSendingToAnotherPath() {
        MockWebServer().use { server ->
            start(server)
            val rules = listOf(rule(server), rule(server, "/api/public", false), rule(server, "/public", false))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/api/result"))
            server.enqueue(MockResponse().setBody("ok"))
            assertEquals(200, host(server, manifest(rules)).call("http", request(server)).getJSONObject("data").getInt("status"))
            repeat(2) { assertEquals(token, server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("X-Token")) }
            for (target in listOf("/api/public/result", "/public/result", "/undeclared")) {
                val before = server.requestCount
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target))
                rejected(PluginErrorCode.UNTRUSTED_URL) { host(server, manifest(rules)).call("http", request(server)) }
                assertEquals(token, server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("X-Token"))
                assertEquals(before + 1, server.requestCount)
            }
        }
    }

    @Test fun redirectsCannotCarryTokenToAnotherDeclaredOrigin() {
        MockWebServer().use { source -> MockWebServer().use { target ->
            start(source); start(target)
            for (status in listOf(301, 302, 303, 307, 308)) {
                source.enqueue(MockResponse().setResponseCode(status).setHeader("Location", origin(target) + "/api/me"))
                rejected(PluginErrorCode.UNTRUSTED_URL) { host(source, manifest(listOf(rule(source), rule(target)))).call("http", request(source)) }
                assertEquals(token, source.takeRequest(1, TimeUnit.SECONDS)?.getHeader("X-Token"))
            }
            assertEquals(0, target.requestCount)
        } }
    }

    @Test fun manifestValidationAndCompatibilityPreventOldHostInstallation() {
        MockWebServer().use { server ->
            start(server)
            for (kind in listOf("service", "native")) {
                val m = manifest(listOf(rule(server)), kind)
                // Validate the authentication feature gate independently of native page contributions.
                rejected(PluginErrorCode.VALIDATION_FAILED) { PluginPlatformContract.validate(manifest(listOf(rule(server)), kind, api = 2)) }
                rejected(PluginErrorCode.VALIDATION_FAILED) { PluginPlatformContract.validate(manifest(listOf(rule(server)), kind, required = 2)) }
                rejected(PluginErrorCode.UNSUPPORTED) { PluginPlatformContract.requireCompatible(m, 1000, mapOf("network.request" to 2)) }
                PluginPlatformContract.requireCompatible(m, 1000, mapOf("network.request" to 3))
            }
        }
    }
}

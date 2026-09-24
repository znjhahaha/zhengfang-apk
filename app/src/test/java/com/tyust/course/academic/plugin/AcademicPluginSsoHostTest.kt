package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicSessionStore
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class AcademicPluginSsoHostTest {
    @Test fun redirectsRefreshRefererAndConfiguredUserAgentForEachTarget() {
        MockWebServer().use { source -> MockWebServer().use { target ->
            source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/api/end?ticket=fictional")))
            target.enqueue(MockResponse().setBody("{}"))
            val input = request(source, null).put("url", source.url("/api/start?token=fictional").toString()).put("sameOriginReferer", true)
            host(source, target, userAgents = mapOf(source to "SchoolDesktop/1", target to "SchoolPortal/2")).call("http", input)
            val first = source.takeRequest(1, TimeUnit.SECONDS)
            val second = target.takeRequest(1, TimeUnit.SECONDS)
            assertEquals(source.url("/").toString(), first?.getHeader("Referer"))
            assertEquals(target.url("/").toString(), second?.getHeader("Referer"))
            assertEquals("SchoolDesktop/1", first?.getHeader("User-Agent"))
            assertEquals("SchoolPortal/2", second?.getHeader("User-Agent"))
        } }
    }

    @Test fun refererIsOptInAndCannotBeSuppliedOrUsedByOtherKinds() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            host(server).call("http", request(server, null))
            assertNull(server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Referer"))
            val cases = listOf(host(server, version = 2) to true, host(server, kind = "service") to true,
                host(server, kind = "native") to true, host(server, kind = "configuration") to true,
                host(server) to "true", host(server) to JSONObject.NULL)
            for ((h, value) in cases) {
                try { h.call("http", request(server, null).put("sameOriginReferer", value)); fail("Must reject") }
                catch (e: PluginException) { assertEquals(PluginErrorCode.VALIDATION_FAILED, e.code) }
            }
            try { host(server).call("http", request(server, null).put("headers", JSONObject().put("Referer", "https://another.test"))); fail("Must reject") }
            catch (e: PluginException) { assertEquals(PluginErrorCode.VALIDATION_FAILED, e.code) }
            assertEquals(1, server.requestCount)
        }
    }

    private fun host(vararg servers: MockWebServer, version: Int = 3, kind: String = "independent", auth: Boolean = false,
                     cookies: List<okhttp3.Cookie> = emptyList(), userAgents: Map<MockWebServer, String> = emptyMap()): PluginHost {
        val rules = servers.map { server -> JSONObject().put("origin", server.url("/").toString().trimEnd('/'))
            .put("pathPrefix", "/api").put("methods", JSONArray(listOf("GET")))
            .put("purposes", JSONArray(listOf("auth", "query")))
            .apply { userAgents[server]?.let { put("userAgent", it) } } }
        val manifest = PluginManifest(JSONObject().put("id", "test.academic.sso").put("version", "1.0.0")
            .put("kind", kind).put("apiVersion", version).put("network", JSONArray(rules)))
        val session = AcademicSessionStore().session("school", "account", servers[0].url("/").toString())
        session.cookies.saveFromResponse(servers[0].url("/"), cookies)
        return PluginHost(PluginOperation(session, manifest, if (auth) "auth.start" else "study.terms"), File("build/sso-host-test"))
    }

    private fun request(server: MockWebServer, token: String? = "synthetic-token", purpose: String = "query") =
        JSONObject().put("url", server.url("/api/start").toString()).put("purpose", purpose)
            .put("headers", JSONObject().apply { token?.let { put("X-Token", it) } })

    @Test fun academicTokenIsSentAndKeptOutOfLogs() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/api/result"))
            server.enqueue(MockResponse().setBody("{}"))
            val host = host(server)
            assertEquals(200, host.call("http", request(server)).getJSONObject("data").getInt("status"))
            repeat(2) { assertEquals("synthetic-token", server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("X-Token")) }
            assertFalse(host.report().any { it.toString().contains("synthetic-token") })
        }
    }

    @Test fun tokenDoesNotCrossDeclaredOrigins() {
        MockWebServer().use { source -> MockWebServer().use { target ->
            source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/api/result")))
            try { host(source, target).call("http", request(source)); fail("Token must not cross origins") }
            catch (e: PluginException) { assertEquals(PluginErrorCode.UNTRUSTED_URL, e.code) }
            assertEquals(0, target.requestCount)
        } }
    }

    @Test fun invalidOrUnsupportedTokenHeadersNeverReachNetwork() {
        MockWebServer().use { server ->
            val cases = listOf(host(server, version = 2) to "token", host(server, kind = "configuration") to "token",
                host(server) to "", host(server) to "bad\nvalue", host(server) to "x".repeat(8193))
            for ((host, value) in cases) {
                try { host.call("http", request(server, value)); fail("Header must be rejected") }
                catch (e: PluginException) { assertEquals(PluginErrorCode.VALIDATION_FAILED, e.code) }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun authCallbackFragmentIsReturnedButNeverSentOrLogged() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/api/callback#/casLogin?token=synthetic%2Btoken"))
            server.enqueue(MockResponse().setBody("<html>SPA</html>"))
            val host = host(server, auth = true)
            val result = host.call("http", request(server, null, "auth")).getJSONObject("data")
            assertTrue(result.getString("url").endsWith("#/casLogin?token=synthetic%2Btoken"))
            server.takeRequest(1, TimeUnit.SECONDS)
            assertEquals("/api/callback", server.takeRequest(1, TimeUnit.SECONDS)?.path)
            assertFalse(host.report().any { it.toString().contains("synthetic") })
        }
    }

    @Test fun callbackFragmentCannotBypassNetworkRules() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/outside#/casLogin?token=synthetic"))
            try { host(server, auth = true).call("http", request(server, null, "auth")); fail("Path must still be checked") }
            catch (e: PluginException) { assertEquals(PluginErrorCode.UNTRUSTED_URL, e.code) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun academicAuthorizationSupportsRawAndBearerTokensOnSameOrigin() {
        MockWebServer().use { server ->
            for (kind in listOf("independent", "extension")) for (value in listOf("synthetic-token", "Bearer synthetic-token")) {
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/api/result"))
                server.enqueue(MockResponse().setBody("{}"))
                val host = host(server, kind = kind)
                val input = request(server, null).put("headers", JSONObject().put("Authorization", value))
                assertEquals(200, host.call("http", input).getJSONObject("data").getInt("status"))
                repeat(2) { assertEquals(value, server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Authorization")) }
                assertFalse(host.report().any { it.toString().contains("synthetic-token") })
            }
        }
    }

    @Test fun authorizationNeverCrossesEvenDeclaredOrigins() {
        for (name in listOf("Authorization", "authorization")) {
            MockWebServer().use { source -> MockWebServer().use { target ->
                source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/api/result")))
                val input = request(source, null).put("headers", JSONObject().put(name, "synthetic-token"))
                try { host(source, target).call("http", input); fail("Token must not cross origins") }
                catch (e: PluginException) { assertEquals(PluginErrorCode.UNTRUSTED_URL, e.code) }
                assertEquals(0, target.requestCount)
            } }
        }
    }

    @Test fun malformedDuplicateAndLegacyAcademicAuthorizationAreRejected() {
        MockWebServer().use { server ->
            val invalidValues = listOf("", "Bearer ", "Basic synthetic", "bad\r\nvalue", "bad value", "令牌", "x".repeat(8193))
            for (value in invalidValues) {
                val input = request(server, null).put("headers", JSONObject().put("Authorization", value))
                try { host(server).call("http", input); fail("Invalid token must be rejected") }
                catch (e: PluginException) { assertEquals(PluginErrorCode.VALIDATION_FAILED, e.code) }
            }
            val duplicates = JSONObject().put("Authorization", "synthetic-token").put("authorization", "different-token")
            val inputs = listOf(
                host(server) to request(server, null).put("headers", duplicates),
                host(server, version = 2) to request(server, null).put("headers", JSONObject().put("Authorization", "Bearer synthetic-token")),
                host(server, kind = "configuration") to request(server, null).put("headers", JSONObject().put("Authorization", "synthetic-token")),
                host(server) to request(server, null).put("headers", JSONObject().put("csrfToken", "0".repeat(32)))
            )
            for ((host, input) in inputs) {
                try { host.call("http", input); fail("Unsupported header must be rejected") }
                catch (e: PluginException) { assertEquals(PluginErrorCode.VALIDATION_FAILED, e.code) }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun aesEcbUsesSharedSdkPaddingAndUnicodeVectors() {
        MockWebServer().use { server ->
            val host = host(server)
            val vectors = JSONObject(File("src/androidTest/assets/academic-plugin/crypto-vectors.json").readText()).getJSONArray("calls")
            var checked = 0
            for (i in 0 until vectors.length()) {
                val vector = vectors.getJSONObject(i)
                if (!vector.has("expected")) continue
                assertEquals(vector.getString("method"), vector.getString("expected"),
                    host.call("crypto." + vector.getString("method"), vector.getJSONObject("args")).getString("data"))
                if (vector.getString("method") == "aesEcbEncrypt") checked++
            }
            assertTrue(checked >= 3)
            for (key in listOf("", "invalid", "YWJj", "!!!!")) {
                try { host.call("crypto.aesEcbEncrypt", JSONObject().put("keyBase64", key).put("text", "synthetic")); fail("Invalid AES key must be rejected") }
                catch (e: PluginException) { assertEquals(PluginErrorCode.VALIDATION_FAILED, e.code) }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun importedCookieCanAuthenticateWithoutExposingItsValueToPluginState() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/api/result"))
            server.enqueue(MockResponse().setBody("{}"))
            val cookie = okhttp3.Cookie.Builder().name("Authorization").value("synthetic+token%3D")
                .hostOnlyDomain(server.hostName).path("/api").build()
            val host = host(server, cookies = listOf(cookie))
            val input = request(server, null).put("cookieHeader", JSONObject().put("cookie", "Authorization").put("header", "Authorization"))
            val result = host.call("http", input).getJSONObject("data")
            assertEquals(200, result.getInt("status"))
            repeat(2) { assertEquals("synthetic+token=", server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Authorization")) }
            assertFalse(result.toString().contains("synthetic"))
            assertFalse(host.report().any { it.toString().contains("synthetic") })
        }
    }

    @Test fun cookieBindingRejectsMissingOutOfScopeAndAmbiguousCredentials() {
        MockWebServer().use { server ->
            fun cookie(path: String, domain: String = server.hostName) = okhttp3.Cookie.Builder()
                .name("Authorization").value("synthetic-token").hostOnlyDomain(domain).path(path).build()
            val input = request(server, null).put("cookieHeader", JSONObject().put("cookie", "Authorization").put("header", "Authorization"))
            for (cookies in listOf(emptyList(), listOf(cookie("/outside")), listOf(cookie("/", "other.test")))) {
                try { host(server, cookies = cookies).call("http", input); fail("Out of scope cookie must be rejected") }
                catch (e: PluginException) { assertEquals(PluginErrorCode.SESSION_EXPIRED, e.code) }
            }
            try { host(server, cookies = listOf(cookie("/"), cookie("/api"))).call("http", input); fail("Ambiguous cookie must be rejected") }
            catch (e: PluginException) { assertEquals(PluginErrorCode.VALIDATION_FAILED, e.code) }
            try { host(server, version = 2, cookies = listOf(cookie("/"))).call("http", input); fail("Legacy plugin cannot bind cookies") }
            catch (e: PluginException) { assertEquals(PluginErrorCode.VALIDATION_FAILED, e.code) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun boundCookieCannotEscapeItsPathOrOriginThroughRedirects() {
        MockWebServer().use { source -> MockWebServer().use { target ->
            fun input() = request(source, null).put("cookieHeader", JSONObject().put("cookie", "Authorization").put("header", "Authorization"))
            val cookie = okhttp3.Cookie.Builder().name("Authorization").value("synthetic-token")
                .hostOnlyDomain(source.hostName).path("/api/start").build()
            source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/api/result"))
            try { host(source, cookies = listOf(cookie)).call("http", input()); fail("Cookie scope must not expand") }
            catch (e: PluginException) { assertEquals(PluginErrorCode.SESSION_EXPIRED, e.code) }
            source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/api/start")))
            try { host(source, target, cookies = listOf(cookie)).call("http", input()); fail("Cookie token must not cross origins") }
            catch (e: PluginException) { assertEquals(PluginErrorCode.UNTRUSTED_URL, e.code) }
            assertEquals(2, source.requestCount)
            assertEquals(0, target.requestCount)
        } }
    }
}

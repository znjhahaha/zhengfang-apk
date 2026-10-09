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

class PluginUserscriptRequestTest {
    private fun host(server: MockWebServer, script: Boolean): PluginHost {
        val origin = server.url("/").toString().trimEnd('/')
        val manifest = PluginManifest(JSONObject().put("id", "fixture.script").put("version", "1.0.0").put("apiVersion", 3)
            .put("kind", "native").put("permissions", JSONArray(listOf("network", "userscripts")))
            .put("network", JSONArray().put(JSONObject().put("origin", origin).put("pathPrefix", "/api/register")
                .put("methods", JSONArray(listOf("GET", "PUT", "HEAD"))).put("purposes", JSONArray(listOf("query", "mutation"))))))
        val session = AcademicSessionStore().session("fixture", "account", origin)
        return PluginHost(PluginOperation(session, manifest, "host.effect", confirmed = true), File("build/script-request-test"), userscriptHeaders = script)
    }
    @Test fun putSendsTheOriginalJsonOnlyThroughTheScriptHost() {
        MockWebServer().use { server ->
            val input = JSONObject().put("url", server.url("/api/register").toString()).put("method", "PUT").put("purpose", "mutation")
                .put("headers", JSONObject().put("Content-Type", "application/json")).put("body", """{"token":"synthetic"}""")
            assertThrows(PluginException::class.java) { host(server, false).call("http", input) }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody("{}"))
            host(server, true).call("http", input)
            val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("PUT", sent.method)
            assertEquals(input.getString("body"), sent.body.readUtf8())
            assertTrue(sent.getHeader("Content-Type")!!.startsWith("application/json"))
        }
    }
    @Test fun putRedirectCannotReplayARegistration() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/api/register/retry"))
            val failure = assertThrows(PluginException::class.java) { host(server, true).call("http", JSONObject()
                .put("url", server.url("/api/register").toString()).put("method", "PUT").put("purpose", "mutation").put("body", "synthetic")) }
            assertEquals(1, server.requestCount)
            assertEquals(PluginErrorCode.RESULT_UNKNOWN, failure.code)
        }
    }

    @Test fun headKeepsItsMethodAcrossReadRedirectsAndReturnsTheFinalAddress() {
        MockWebServer().use { server ->
            val input = JSONObject().put("url", server.url("/api/register").toString()).put("method", "HEAD").put("purpose", "query")
            assertThrows(PluginException::class.java) { host(server, false).call("http", input) }
            assertThrows(PluginException::class.java) { host(server, true).call("http", JSONObject(input.toString()).put("body", "not allowed")) }
            assertThrows(PluginException::class.java) { host(server, true).call("http", JSONObject(input.toString()).put("purpose", "mutation")) }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/api/register/next"))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/api/register/final?encryTaskUserId=synthetic"))
            server.enqueue(MockResponse().setHeader("Content-Length", "4096"))
            val response = host(server, true).call("http", input).getJSONObject("data")
            assertEquals(server.url("/api/register/final?encryTaskUserId=synthetic").toString(), response.getString("url"))
            assertEquals("", response.getString("body"))
            repeat(3) {
                val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("HEAD", sent.method)
                assertEquals(0L, sent.bodySize)
            }
        }
    }

    @Test fun headRedirectRechecksTheDeclaredPath() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/outside"))
            assertThrows(PluginException::class.java) { host(server, true).call("http", JSONObject()
                .put("url", server.url("/api/register").toString()).put("method", "HEAD").put("purpose", "query")) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun upstreamLanguageHeaderReachesTheServerOnlyThroughTheScriptHost() {
        MockWebServer().use { server ->
            val input = JSONObject().put("url", server.url("/api/register").toString()).put("method", "GET").put("purpose", "query")
                .put("headers", JSONObject().put("Accept-Language", "zh-CN,zh;q=0.9"))
            assertThrows(PluginException::class.java) { host(server, false).call("http", input) }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody("course directory"))
            assertEquals("course directory", host(server, true).call("http", input).getJSONObject("data").getString("body"))
            assertEquals("zh-CN,zh;q=0.9", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Accept-Language"))
            for (name in listOf("Cookie", "Host", "Proxy-Authorization")) {
                input.put("headers", JSONObject().put(name, "synthetic"))
                assertThrows(PluginException::class.java) { host(server, true).call("http", input) }
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun upstreamBrowserMetadataCanCheckUpdatesWithoutChangingNativeRequestPermissions() {
        MockWebServer().use { server ->
            val headers = JSONObject().put("DNT", "1").put("Upgrade-Insecure-Requests", "1")
                .put("Sec-CH-UA", "\"Chromium\";v=\"142\"").put("Sec-CH-UA-Mobile", "?0")
                .put("Sec-CH-UA-Platform", "\"Windows\"").put("Sec-CH-UA-Arch", "\"x86\"")
                .put("Sec-CH-UA-Bitness", "\"64\"").put("Sec-CH-UA-Full-Version", "\"142.0\"")
                .put("Sec-CH-UA-Full-Version-List", "\"Chromium\";v=\"142.0\"")
                .put("Sec-CH-UA-Model", "\"\"").put("Sec-CH-UA-Platform-Version", "\"10.0\"")
                .put("Sec-Fetch-Site", "same-origin")
            val input = JSONObject().put("url", server.url("/api/register").toString()).put("method", "GET")
                .put("purpose", "query").put("headers", headers)
            assertThrows(PluginException::class.java) { host(server, false).call("http", input) }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody("// @version 3.0.1"))
            assertEquals("// @version 3.0.1", host(server, true).call("http", input).getJSONObject("data").getString("body"))
            val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
            headers.keys().forEach { name -> assertEquals(headers.getString(name), sent.getHeader(name)) }
        }
    }
}

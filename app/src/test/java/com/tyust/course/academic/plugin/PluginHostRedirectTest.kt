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

class PluginHostRedirectTest {
    private val bearer = "Bearer synthetic-service-token"

    private fun host(vararg servers: MockWebServer): PluginHost {
        val rules = servers.map { server ->
            JSONObject().put("origin", server.url("/").toString().trimEnd('/'))
                .put("pathPrefix", "/api").put("methods", JSONArray(listOf("GET")))
                .put("purposes", JSONArray(listOf("query")))
        }
        val manifest = PluginManifest(JSONObject().put("id", "test.redirect.service")
            .put("kind", "service").put("apiVersion", 2).put("version", "1.0.0").put("network", JSONArray(rules)))
        val session = AcademicSessionStore().session("synthetic-school", "synthetic-account", servers[0].url("/").toString())
        return PluginHost(PluginOperation(session, manifest, "service.page"), File("build/plugin-redirect-test-store"))
    }

    private fun request(server: MockWebServer, header: String = "Authorization") =
        JSONObject().put("url", server.url("/api/start").toString()).put("purpose", "query")
            .put("headers", JSONObject().put(header, bearer))

    @Test fun sameOriginRedirectPreservesServiceBearer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/api/result"))
            server.enqueue(MockResponse().setBody("synthetic result"))
            val host = host(server)
            val result = host.call("http", request(server)).getJSONObject("data")
            assertEquals(200, result.getInt("status"))
            assertEquals("synthetic result", result.getString("body"))
            assertEquals(bearer, server.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Authorization"))
            val redirected = server.takeRequest(1, TimeUnit.SECONDS)
            assertEquals("/api/result", redirected?.path)
            assertEquals(bearer, redirected?.getHeader("Authorization"))
            assertFalse(host.report().any { it.toString().contains(bearer) })
        }
    }

    @Test fun serviceBearerRejectsRedirectEvenWhenBothOriginsAreDeclared() {
        MockWebServer().use { source ->
            MockWebServer().use { target ->
                target.enqueue(MockResponse().setBody("must never be requested"))
                for (status in listOf(301, 302, 303, 307, 308)) {
                    source.enqueue(MockResponse().setResponseCode(status).setHeader("Location", target.url("/api/result")))
                    try {
                        host(source, target).call("http", request(source, "authorization"))
                        fail("Bearer redirect to another declared origin must be rejected")
                    } catch (error: PluginException) {
                        assertEquals(PluginErrorCode.UNTRUSTED_URL, error.code)
                    }
                    assertEquals(bearer, source.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Authorization"))
                    assertEquals("Redirect target must receive no request", 0, target.requestCount)
                }
            }
        }
    }
}

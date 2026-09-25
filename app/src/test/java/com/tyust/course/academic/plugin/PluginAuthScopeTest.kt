package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicSessionStore
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PluginAuthScopeTest {
    private val login = "https://login.school.test/cas/login".toHttpUrl()
    private val service = "http://academic.school.test/jsxsd/sso.jsp".toHttpUrl()
    private fun declaration() = JSONObject().put("loginUrl", login.toString()).put("serviceUrl", service.toString())
    @Test fun mixedNativePackageAllowsAcademicTransportOnlyFromAcademicOperations() {
        MockWebServer().use { server ->
            server.start()
            val origin = server.url("/").toString().trimEnd('/')
            val manifest = PluginManifest(JSONObject().put("id", "test.mixed-school").put("version", "3.2.0")
                .put("apiVersion", 3).put("kind", "native").put("contributes", JSONObject().put("academic", true))
                .put("network", JSONArray().put(JSONObject().put("origin", origin).put("pathPrefix", "/")
                    .put("methods", JSONArray(listOf("GET"))).put("purposes", JSONArray(listOf("query"))))))
            val session = AcademicSessionStore().session("synthetic", "alice", origin)
            val request = JSONObject().put("url", "$origin/study").put("purpose", "query").put("sameOriginReferer", true)
                .put("headers", JSONObject().put("X-Token", "synthetic-academic-token"))
            for (method in listOf("ui.init", "services.invoke", "workflow.prepare")) {
                assertThrows(PluginException::class.java) {
                    PluginHost(PluginOperation(session, manifest, method), File("build/mixed-scope-test")).call("http", request)
                }
            }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody("synthetic grades"))
            val response = PluginHost(PluginOperation(session, manifest, "study.grades"), File("build/mixed-scope-test")).call("http", request)
            assertEquals("synthetic grades", response.getJSONObject("data").getString("body"))
            val sent = server.takeRequest()
            assertEquals("$origin/", sent.getHeader("Referer"))
            assertEquals("synthetic-academic-token", sent.getHeader("X-Token"))
        }
    }
    @Test fun registeredCallbackPinsServiceAndTicketPathWithoutAllowingGeneralDowngrades() {
        val scope = PluginAuthScope(declaration())
        scope.requireAllowed(login.newBuilder().addQueryParameter("service", service.toString()).build(), "POST")
        scope.requireAllowed(service.newBuilder().addQueryParameter("ticket", "ST-synthetic").build(), "GET")
        assertTrue(scope.registeredHttpCallback(login, service))
        for (url in listOf(login.newBuilder().addQueryParameter("service", "https://foreign.test/").build(),
            login.newBuilder().addQueryParameter("service", service.toString()).addQueryParameter("service", service.toString()).build(),
            service.newBuilder().encodedPath("/jsxsd/other").addQueryParameter("ticket", "ST-synthetic").build())) {
            assertThrows(PluginException::class.java) { scope.requireAllowed(url, "GET") }
        }
        assertFalse(scope.registeredHttpCallback(login, "http://academic.school.test/jsxsd/other".toHttpUrl()))
        assertFalse(scope.registeredHttpCallback(login, "http://unrelated.test/jsxsd/sso.jsp".toHttpUrl()))
        assertThrows(PluginException::class.java) { scope.requireAllowed(service, "POST") }
    }
    @Test fun parentDomainIdentityCookiesNeverJoinTeachingCookiesAndExpireWithSession() {
        val session = AcademicSessionStore().session("synthetic", "alice", service.toString())
        val scope = PluginAuthScope(declaration())
        val jar = PluginAcademicCookies(session, "synthetic.plugin", scope) { session.requireActive() }
        jar.saveFromResponse(login, listOf(Cookie.parse(login, "CAS_ONLY=synthetic; Domain=school.test; Path=/")!!))
        assertEquals(listOf("CAS_ONLY"), jar.loadForRequest(login).map { it.name })
        assertTrue(jar.loadForRequest(service).isEmpty())
        jar.saveFromResponse(service, listOf(Cookie.parse(service, "TEACHING=synthetic; Domain=school.test; Path=/")!!))
        assertEquals(listOf("TEACHING"), session.cookies.loadForRequest(service).map { it.name })
        assertEquals(listOf("CAS_ONLY"), jar.loadForRequest(login).map { it.name })
        assertTrue(PluginAcademicCookies(session, "other.plugin", scope) { session.requireActive() }.loadForRequest(login).isEmpty())
        session.invalidate()
        assertTrue(jar.loadForRequest(login).isEmpty()); assertTrue(jar.loadForRequest(service).isEmpty())
        session.retire()
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { jar.saveFromResponse(login, emptyList()) }
    }
    @Test fun hostRejectsChangedServiceAtRedirectBeforeIssuingTheNextRequest() {
        MockWebServer().use { cas -> MockWebServer().use { academic ->
            cas.start(); academic.start()
            val login = cas.url("/cas/login")
            val service = academic.url("/jsxsd/sso.jsp")
            cas.enqueue(MockResponse().setResponseCode(302).setHeader("Location", login.newBuilder().addQueryParameter("service", "https://unrelated.test/").build()))
            val rules = listOf(cas, academic).map { JSONObject().put("origin", it.url("/").toString().trimEnd('/')).put("pathPrefix", "/").put("methods", JSONArray(listOf("GET", "POST"))).put("purposes", JSONArray(listOf("auth"))) }
            val manifest = PluginManifest(JSONObject().put("id", "test.auth-scope").put("version", "3.2.0").put("kind", "independent").put("apiVersion", 3).put("network", JSONArray(rules)))
            val session = AcademicSessionStore().session("synthetic", "alice", service.toString())
            val host = PluginHost(PluginOperation(session, manifest, "auth.start"), File("build/auth-scope-test"))
            val request = JSONObject().put("url", login.newBuilder().addQueryParameter("service", service.toString()).build().toString()).put("purpose", "auth")
                .put("authScope", JSONObject().put("loginUrl", login.toString()).put("serviceUrl", service.toString()))
            val error = assertThrows(PluginException::class.java) { host.call("http", request) }
            assertEquals(PluginErrorCode.UNTRUSTED_URL, error.code)
            assertEquals(1, cas.requestCount); assertEquals(0, academic.requestCount)
        } }
    }
}

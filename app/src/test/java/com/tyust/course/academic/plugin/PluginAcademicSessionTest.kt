package com.tyust.course.academic.plugin

import android.app.Application
import com.tyust.course.academic.AcademicGatewayFactory
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class PluginAcademicSessionTest {
    private lateinit var app: Application
    private lateinit var user: UserManager
    private lateinit var school: SchoolConfig
    private lateinit var server: MockWebServer
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication(); user = UserManager.getInstance(); user.init(app)
        AcademicProviderRegistry.initialize(app)
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        school = SchoolConfig("synthetic-sharing", "Synthetic school", "127.0.0.1:${server.port}", "http").apply { academicSystem = "zf"; basePath = "/jw" }
        user.currentSchool = school; user.studentId = "synthetic-student"; user.saveCookieLogin("SYNTHETIC=existing-login")
    }
    @After fun cleanup() { server.shutdown() }
    private fun pkg(id: String = "test.author.one", author: String = "Author One") = PluginPackage(PluginManifest(JSONObject()
        .put("id", id).put("author", author).put("name", "Synthetic tool").put("kind", "native").put("version", "1.0.0").put("apiVersion", 3)
        .put("permissions", JSONArray(listOf("academic.session", "network"))).put("capabilities", JSONArray())
        .put("contributes", JSONObject().put("pages", JSONArray()).put("entries", JSONArray()))
        .put("matches", JSONArray().put(JSONObject().put("host", "127.0.0.1").put("port", server.port).put("pathPrefix", "/jw")))
        .put("network", JSONArray().put(JSONObject().put("origin", "http://127.0.0.1:${server.port}").put("pathPrefix", "/")
            .put("methods", JSONArray(listOf("GET", "POST"))).put("purposes", JSONArray(listOf("query", "mutation", "auth")))))), "", id.hashCode().toString(), true)
    private fun access(caller: PluginPackage = pkg(), active: () -> Boolean = { true }) = PluginAcademicSession(app, caller, active, callerCurrent = { true })
    private fun request(path: String = "/jw/read", purpose: String = "query") = JSONObject().put("url", "http://127.0.0.1:${server.port}$path").put("purpose", purpose)
        .put("method", if (purpose == "mutation") "POST" else "GET")
    private fun host(access: PluginAcademicSession, caller: PluginPackage, grant: String, confirmed: Boolean = false): Pair<PluginOperation, PluginHost> {
        val op = PluginOperation(access.session, caller.manifest, "host.effect", confirmed = confirmed, scopeStillActive = { access.requireGrant(grant); true })
        return op to PluginHost(op, app.cacheDir, access.cookies(grant)) { url, method, purpose, form -> access.requireRequest(grant, url, method, purpose, form) }
    }

    @Test fun differentAuthorsReuseOneLoginWithSeparateRevocableGrants() {
        val first = pkg(); val second = pkg("test.author.two", "Unrelated Author")
        val a = access(first); val b = access(second)
        val grantA = a.authorize().getString("grant"); val resultB = b.authorize(); val grantB = resultB.getString("grant")
        assertNotEquals(grantA, grantB); assertSame(a.session, b.session)
        assertFalse(resultB.toString().contains("existing-login"))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, assertThrows(PluginException::class.java) { b.requireGrant(grantA) }.code)
        for ((caller, access, grant) in listOf(Triple(first, a, grantA), Triple(second, b, grantB))) {
            server.enqueue(MockResponse().setBody("synthetic response").addHeader("Set-Cookie", "rotated=fixture; Path=/jw"))
            val (op, host) = host(access, caller, grant)
            val response = host.call("http", request()).getJSONObject("data")
            assertEquals("synthetic response", response.getString("body")); assertFalse(response.getJSONObject("headers").has("Set-Cookie"))
            assertTrue(server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Cookie")!!.contains("SYNTHETIC=existing-login")); op.close()
        }
        PluginAcademicSession.revoke(app, first.manifest.id)
        assertThrows(PluginException::class.java) { a.requireGrant(grantA) }
        b.requireGrant(grantB)
        assertTrue(b.session.cookieHeader().contains("rotated=fixture"))
    }

    @Test fun missingGrantOtherSchoolAndOutOfScopeRedirectNeverReceiveTheLogin() {
        val caller = pkg(); val access = access(caller)
        assertThrows(PluginException::class.java) { access.requireGrant("ungranted") }
        val grant = access.authorize().getString("grant")
        val (op, host) = host(access, caller, grant)
        for (path in listOf("/outside", "/jw-other", "/jw/%2foutside")) {
            assertEquals(PluginErrorCode.UNTRUSTED_URL, assertThrows(PluginException::class.java) { host.call("http", request(path)) }.code)
        }
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/other-system"))
        assertEquals(PluginErrorCode.UNTRUSTED_URL, assertThrows(PluginException::class.java) { host.call("http", request()) }.code)
        assertEquals(1, server.requestCount); op.close()
        val other = caller.copy(manifest = PluginManifest(JSONObject(caller.manifest.json.toString()).put("matches", JSONArray()
            .put(JSONObject().put("host", "other-school.test").put("pathPrefix", "/")))))
        assertThrows(PluginException::class.java) { access(other).authorize() }
    }

    @Test fun accountPluginAndSchoolProviderChangesInvalidateCapturedAuthorization() {
        val caller = pkg(); val a = access(caller); val grant = a.authorize().getString("grant")
        val updated = access(caller.copy(digest = "updated"))
        assertThrows(PluginException::class.java) { updated.requireGrant(grant) }
        AcademicProviderRegistry.choose(school, "builtin.qz")
        assertThrows(PluginException::class.java) { a.requireGrant(grant) }
        AcademicProviderRegistry.choose(school, null)
        user.sessionState.replace("another-synthetic-account")
        assertThrows(PluginException::class.java) { a.authorize() }
    }

    @Test fun sessionExpiryAndActivityCancellationCannotReuseOrCommitAGrant() {
        var live = true
        val a = access(active = { live }); val grant = a.authorize().getString("grant")
        live = false; assertThrows(PluginException::class.java) { a.authorize() }
        live = true
        user.sessionState.expire(user.sessionState.token)
        assertThrows(PluginException::class.java) { a.requireGrant(grant) }
        AcademicGatewayFactory.invalidate(school, user.currentAccountStorageKey)
        assertThrows(PluginException::class.java) { a.requireGrant(grant) }
    }

    @Test fun mutationsStillNeedConfirmationAndRevokingACallPreservesUnknownResult() {
        val caller = pkg(); val a = access(caller); val grant = a.authorize().getString("grant")
        val (unconfirmed, host) = host(a, caller, grant)
        assertThrows(PluginException::class.java) { host.call("http", request(purpose = "mutation")) }
        assertEquals(0, server.requestCount); unconfirmed.close()
        val (confirmed, _) = host(a, caller, grant, confirmed = true)
        a.track(grant, confirmed); confirmed.markMutation()
        PluginAcademicSession.revoke(app, caller.manifest.id)
        val error = assertThrows(PluginException::class.java) { confirmed.requireActive() }
        assertEquals(PluginErrorCode.RESULT_UNKNOWN, confirmed.failure(error.code, error.message.orEmpty()).code)
        a.untrack(confirmed)
    }

    @Test fun cookieTokenBindingStaysInTheHostAndRequiresNetworkPermission() {
        user.saveCookieLogin("token=synthetic-token")
        val caller = pkg(); val a = access(caller); val grant = a.authorize().getString("grant")
        server.enqueue(MockResponse().setBody("done"))
        val (op, host) = host(a, caller, grant)
        host.call("http", request().put("cookieHeader", JSONObject().put("cookie", "token").put("header", "X-Token")))
        assertEquals("synthetic-token", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("X-Token")); op.close()
        val undeclared = caller.copy(manifest = PluginManifest(JSONObject(caller.manifest.json.toString()).put("permissions", JSONArray(listOf("academic.session")))))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, assertThrows(PluginException::class.java) { access(undeclared).authorize() }.code)
    }
}

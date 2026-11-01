package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionKey
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PluginNetworkScopesTest {
    private val pluginId = "test.dynamic.network"
    private val origin = "https://school.example"
    private val url = "$origin/api/login".toHttpUrl()
    private val sessions = mutableListOf<AcademicSession>()

    @After fun cleanup() { PluginNetworkScopes.revokePlugin(pluginId); sessions.forEach { it.retire() } }

    private fun session(account: String = "account") = AcademicSession(
        AcademicSessionKey("service:$pluginId:school", account), "$origin/"
    ).also { sessions += it }

    private fun manifest() = PluginManifest(JSONObject().put("id", pluginId).put("version", "1.0.0")
        .put("apiVersion", 3).put("kind", "native").put("dynamicNetworkScopes", true)
        .put("permissions", JSONArray(listOf("network", "auth")))
        .put("capabilities", JSONArray(listOf("serviceAuth.start")))
        .put("requires", JSONArray().put(JSONObject().put("name", "network.scope.authorize").put("version", 1)))
        .put("network", JSONArray()))

    private fun operation(session: AcademicSession = session(), digest: String = "digest-a",
        method: String = "services.invoke", active: () -> Boolean = { true }, manifest: PluginManifest = manifest()) =
        PluginOperation(session, manifest, method, packageDigest = digest, scopeStillActive = active)

    private fun rule(path: String = "/api", host: String = origin) = JSONObject().put("origin", host)
        .put("pathPrefix", path).put("methods", JSONArray(listOf("GET", "POST")))
        .put("purposes", JSONArray(listOf("auth", "query")))

    private fun grant(operation: PluginOperation, tenant: String = "tenant", rules: List<JSONObject> = listOf(rule())) =
        PluginNetworkScopes.authorize(operation, tenant, rules)

    private fun rejected(block: () -> Unit) { assertThrows(PluginException::class.java) { block() } }
    private fun cookie(value: String = "synthetic", path: String? = null) = JSONObject()
        .put("name", "sid").put("value", value).apply { if (path != null) put("path", path) }
    private fun set(scope: PluginNetworkScopes.Scope, value: String = "synthetic") =
        scope.setCookies(url, JSONArray().put(cookie(value)))

    @Test fun grantIsOpaqueAndSharedAcrossOperationsForTheSameStableSession() {
        val session = session()
        val first = operation(session)
        val token = grant(first)
        assertFalse(token.contains(pluginId)); assertFalse(token.contains("account")); assertFalse(token.contains("tenant"))
        assertNotEquals(token, grant(first))
        first.close()
        val scope = PluginNetworkScopes.requireGrant(operation(session), token)
        assertEquals("tenant", scope.tenantId)
        scope.policy.requireAllowed(url, "POST", "auth", null)
        assertTrue(first.manifest.network.isEmpty())
    }

    @Test fun grantRejectsOtherAccountsSessionInstancesPluginVersionsDigestsAndTrustModes() {
        val session = session()
        val first = operation(session)
        val token = grant(first)
        rejected { PluginNetworkScopes.requireGrant(operation(session("other")), token) }
        rejected { PluginNetworkScopes.requireGrant(operation(session()), token) }
        rejected { PluginNetworkScopes.requireGrant(operation(session, digest = "digest-b"), token) }
        rejected { PluginNetworkScopes.requireGrant(operation(session, manifest = PluginManifest(
            JSONObject(manifest().json.toString()).put("version", "2.0.0"))), token) }
        rejected { PluginNetworkScopes.requireGrant(operation(session, manifest = PluginManifest(
            JSONObject(manifest().json.toString()).put("id", "other.plugin"))), token) }
        rejected { PluginNetworkScopes.requireGrant(PluginOperation(session, manifest(), "services.invoke",
            development = true, packageDigest = "digest-a"), token) }
        PluginNetworkScopes.requireGrant(first, token)
    }

    @Test fun sessionInvalidationRevocationAndAccountSwitchInvalidatePreviouslyReturnedJars() {
        val session = session()
        var active = true
        val first = operation(session, method = "serviceAuth.start", active = { active })
        val token = grant(first)
        val scope = PluginNetworkScopes.requireGrant(first, token)
        set(scope)
        active = false
        rejected { scope.cookies.loadForRequest(url) }
        active = true
        session.invalidate()
        rejected { scope.cookies.loadForRequest(url) }
        rejected { PluginNetworkScopes.requireGrant(operation(session), token) }
        val next = operation(session, method = "serviceAuth.start")
        val nextScope = PluginNetworkScopes.requireGrant(next, grant(next))
        assertTrue(nextScope.cookies.loadForRequest(url).isEmpty())
        set(nextScope)
        PluginNetworkScopes.revokePlugin(pluginId)
        rejected { nextScope.cookies.loadForRequest(url) }
    }

    @Test fun operationAndSessionRevocationAlsoRemoveCookies() {
        val session = session()
        val auth = operation(session, method = "serviceAuth.resume")
        val token = grant(auth)
        val scope = PluginNetworkScopes.requireGrant(auth, token)
        set(scope)
        PluginNetworkScopes.revoke(auth)
        rejected { PluginNetworkScopes.requireGrant(auth, token) }
        val next = PluginNetworkScopes.requireGrant(auth, grant(auth))
        assertTrue(next.cookies.loadForRequest(url).isEmpty())
        PluginNetworkScopes.revoke(session)
        rejected { next.cookies.loadForRequest(url) }
    }

    @Test fun authorizationRequiresNativeOptInNetworkPermissionAndDeclaredEffect() {
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("kind", "service") }, { it.put("dynamicNetworkScopes", false) },
            { it.put("permissions", JSONArray()) }, { it.put("requires", JSONArray()) }
        )) {
            val value = manifest().json; change(value)
            rejected { grant(operation(manifest = PluginManifest(value))) }
        }
        val op = operation()
        rejected { grant(op, tenant = "") }; rejected { grant(op, tenant = " tenant") }
        rejected { grant(op, rules = emptyList()) }; rejected { grant(op, rules = List(33) { rule() }) }
        grant(op, rules = List(32) { rule("/api/$it") })
        op.close(); rejected { grant(op) }
    }

    @Test fun malformedWildcardAndUnknownRuleFieldsAreRejectedBeforeGrantCreation() {
        val op = operation()
        for (host in listOf("https://*.example", "$origin/api", "$origin/?q=1", "https://user@school.example", "$origin/#f"))
            rejected { grant(op, rules = listOf(rule(host = host))) }
        for (path in listOf("/api/*", "/api?x=1", "/api#x", "/api/../admin", "/api/%2f", "/api\\admin", "/api\n",
            "//api", "/api//login", "/api/学校"))
            rejected { grant(op, rules = listOf(rule(path))) }
        for (bad in listOf(rule().put("methods", JSONArray(listOf("*"))),
            rule().put("methods", JSONArray(listOf(1))), rule().put("purposes", JSONArray(listOf("*"))),
            rule().put("unexpected", true))) rejected { grant(op, rules = listOf(bad)) }
    }

    @Test fun scopeOriginsMustBeHttpsAndAlreadyCanonical() {
        val op = operation()
        for (host in listOf("http://school.example", "$origin/", "$origin:443", "https://SCHOOL.example",
            "HTTPS://school.example", "https://school.example:0443"))
            rejected { grant(op, rules = listOf(rule(host = host))) }
        for (host in listOf(origin, "$origin:444", "https://[::1]:444")) {
            val scope = PluginNetworkScopes.requireGrant(op, grant(op, rules = listOf(rule(host = host))))
            scope.policy.requireAllowed("$host/api/result".toHttpUrl(), "GET", "query", null)
        }
    }

    @Test fun policyChecksEachRedirectOriginPortPathMethodPurposeAndQueryWithoutWideningManifest() {
        val op = operation()
        val input = rule().put("requiredQuery", JSONObject().put("school", "one"))
        val token = grant(op, rules = listOf(input))
        input.put("pathPrefix", "/").put("origin", "https://evil.example")
        val policy = PluginNetworkScopes.requireGrant(op, token).policy
        policy.requireAllowed("$origin/api/result?school=one".toHttpUrl(), "GET", "query", null)
        for (target in listOf("https://evil.example/api/result?school=one", "$origin:444/api/result?school=one",
            "$origin/api2/result?school=one", "$origin/private?school=one", "$origin/api/result?school=two",
            "$origin/api/%2fprivate?school=one", "http://school.example/api/result?school=one"))
            rejected { policy.requireAllowed(target.toHttpUrl(), "GET", "query", null) }
        rejected { policy.requireAllowed("$origin/api/result?school=one".toHttpUrl(), "PUT", "query", null) }
        rejected { policy.requireAllowed("$origin/api/result?school=one".toHttpUrl(), "POST", "mutation", null) }
        assertTrue(op.manifest.network.isEmpty())
    }

    @Test fun dynamicRequestsRejectEncodedAndDoubleSlashPathsBeforeResolvingCredentials() {
        val manifest = manifest().apply {
            json.getJSONArray("requires").put(JSONObject().put("name", "network.request").put("version", 5))
        }
        val auth = operation(method = "serviceAuth.start", manifest = manifest)
        val token = grant(auth, rules = listOf(rule("/auth")))
        val scope = PluginNetworkScopes.requireGrant(auth, token)
        val authUrl = "$origin/auth/login".toHttpUrl()
        scope.setCookies(authUrl, JSONArray().put(cookie(path = "/")))
        assertEquals("synthetic", scope.cookies.loadForRequest(authUrl).single().value)
        scope.policy.requireAllowed("$origin/auth/login?service=https%3A%2F%2Fschool.example%2Fapi".toHttpUrl(),
            "POST", "auth", null)
        var resolutions = 0
        val host = PluginHost(auth, File("build/plugin-dynamic-scopes-test-store"), credentialResolver = {
            resolutions++
            throw AssertionError("Noncanonical dynamic paths must fail before credential resolution")
        })
        for (path in listOf("/auth/%252e%252e/private", "/auth/%252fprivate", "/auth//login",
            "//auth/login", "/auth/%41")) {
            val target = "$origin$path".toHttpUrl()
            val policyError = assertThrows(PluginException::class.java) {
                scope.policy.requireAllowed(target, "POST", "auth", null)
            }
            assertEquals(PluginErrorCode.UNTRUSTED_URL, policyError.code)
            assertTrue(scope.cookies.loadForRequest(target).isEmpty())
            val request = JSONObject().put("url", target.toString()).put("scopeGrant", token)
                .put("method", "POST").put("purpose", "auth").put("body", "{\"a\":\"\"}")
                .put("credentials", JSONObject().put("mobile", "synthetic-handle"))
                .put("bindings", JSONObject().put("body", JSONObject().put("a", JSONObject().put("from", "mobile.mobile"))))
            val requestError = assertThrows(PluginException::class.java) { host.call("http", request) }
            assertEquals(PluginErrorCode.UNTRUSTED_URL, requestError.code)
        }
        assertEquals(0, resolutions)
        // Strict paths are opt-in; the existing static policy keeps its compatibility behavior.
        PluginNetworkPolicy(listOf(rule("/auth"))).requireAllowed(
            "$origin/auth/%252e%252e/private".toHttpUrl(), "POST", "auth", null)
    }

    @Test fun cookiesAreSharedAcrossGrantsOnlyForSamePluginAccountTenantAndSession() {
        val session = session()
        val auth = operation(session, method = "serviceAuth.start")
        val scope = PluginNetworkScopes.requireGrant(auth, grant(auth))
        set(scope)
        val reader = operation(session)
        val same = PluginNetworkScopes.requireGrant(reader, grant(reader))
        assertEquals("synthetic", same.cookies.loadForRequest(url).single().value)
        val otherAccount = operation(session("other"))
        assertTrue(PluginNetworkScopes.requireGrant(otherAccount, grant(otherAccount)).cookies.loadForRequest(url).isEmpty())
        assertTrue(session.cookies.loadForRequest(url).isEmpty())
        session.cookies.saveFromResponse(url, listOf(Cookie.Builder().name("academic").value("private")
            .hostOnlyDomain(url.host).path("/api").secure().build()))
        assertEquals(listOf("sid"), same.cookies.loadForRequest(url).map { it.name })
        val otherTenant = PluginNetworkScopes.requireGrant(reader, grant(reader, "other-tenant"))
        assertTrue(otherTenant.cookies.loadForRequest(url).isEmpty())
        rejected { same.cookies.loadForRequest(url) }
    }

    @Test fun tenantSwitchRevokesOldGrantsAndLateResponsesWithoutTouchingAcademicCookies() {
        val session = session()
        val auth = operation(session, method = "serviceAuth.start")
        val academicCookie = Cookie.Builder().name("academic").value("private")
            .hostOnlyDomain(url.host).path("/api").secure().build()
        session.cookies.saveFromResponse(url, listOf(academicCookie))
        val epoch = session.epoch
        val tenantAGrant = grant(auth, "tenant-a")
        val tenantA = PluginNetworkScopes.requireGrant(auth, tenantAGrant)
        set(tenantA)
        val repeatedAGrant = grant(auth, "tenant-a")
        val repeatedA = PluginNetworkScopes.requireGrant(auth, repeatedAGrant)
        assertEquals("synthetic", tenantA.cookies.loadForRequest(url).single().value)
        assertEquals("synthetic", repeatedA.cookies.loadForRequest(url).single().value)

        val otherAuth = operation(session("other"), method = "serviceAuth.start")
        val otherScope = PluginNetworkScopes.requireGrant(otherAuth, grant(otherAuth, "tenant-a"))
        set(otherScope, "other-account")

        val tenantBGrant = grant(auth, "tenant-b")
        val tenantB = PluginNetworkScopes.requireGrant(auth, tenantBGrant)
        assertTrue(tenantB.cookies.loadForRequest(url).isEmpty())
        for (oldGrant in listOf(tenantAGrant, repeatedAGrant))
            rejected { PluginNetworkScopes.requireGrant(auth, oldGrant) }
        for (oldScope in listOf(tenantA, repeatedA)) {
            rejected { oldScope.cookies.loadForRequest(url) }
            rejected { oldScope.cookies.saveFromResponse(url, listOf(Cookie.parse(url, "sid=late; Path=/")!!)) }
            rejected { set(oldScope, "late") }
        }
        assertTrue(tenantB.cookies.loadForRequest(url).isEmpty())
        assertEquals("other-account", otherScope.cookies.loadForRequest(url).single().value)
        assertEquals(listOf(academicCookie), session.cookies.loadForRequest(url))
        assertEquals(epoch, session.epoch)
        val returningA = PluginNetworkScopes.requireGrant(auth, grant(auth, "tenant-a"))
        assertTrue(returningA.cookies.loadForRequest(url).isEmpty())
        rejected { PluginNetworkScopes.requireGrant(auth, tenantBGrant) }
        assertEquals(listOf(academicCookie), session.cookies.loadForRequest(url))
    }

    @Test fun onlyProtectedServiceAuthenticationCanInstallCookiesAndValidationIsAtomic() {
        val session = session()
        val data = operation(session)
        val token = grant(data)
        rejected { set(PluginNetworkScopes.requireGrant(data, token)) }
        val genericAuth = operation(session, method = "auth.start")
        rejected { set(PluginNetworkScopes.requireGrant(genericAuth, token)) }
        val effect = operation(session, method = "host.effect")
        rejected { set(PluginNetworkScopes.requireGrant(effect, token)) }
        val protectedScope = PluginNetworkScopes.requireGrant(effect, token, protectedAuth = true)
        for (bad in listOf(cookie(path = "/outside"), cookie().put("domain", ".example"), cookie().put("value", "x; admin=y")))
            rejected { protectedScope.setCookies(url, JSONArray().put(cookie("first")).put(bad)) }
        assertTrue(protectedScope.cookies.loadForRequest(url).isEmpty())
        rejected { protectedScope.setCookies("http://school.example/api/login", JSONArray().put(cookie())) }
        rejected { protectedScope.setCookies("https://other.example/api/login", JSONArray().put(cookie())) }
        set(protectedScope)
        val actual = protectedScope.cookies.loadForRequest(url).single()
        assertTrue(actual.secure); assertTrue(actual.hostOnly); assertEquals("/api", actual.path)
    }

    @Test fun protectedRootCookieAtAuthEndpointIsIntersectedWithSameOriginGrantedPaths() {
        val auth = operation(method = "serviceAuth.start")
        val authUrl = "$origin/auth/login".toHttpUrl()
        val rules = listOf(
            rule("/auth/login").put("methods", JSONArray(listOf("POST"))).put("purposes", JSONArray(listOf("auth"))),
            rule("/api").put("methods", JSONArray(listOf("GET"))).put("purposes", JSONArray(listOf("query"))),
            rule("/api", "https://other.example")
        )
        val scope = PluginNetworkScopes.requireGrant(auth, grant(auth, rules = rules))
        rejected { scope.setCookies("$origin/", JSONArray().put(cookie(path = "/"))) }
        rejected { scope.setCookies(url, JSONArray().put(cookie(path = "/"))) }
        assertTrue(scope.cookies.loadForRequest(authUrl).isEmpty())
        scope.setCookies(authUrl, JSONArray().put(cookie(path = "/")))
        val authCookie = scope.cookies.loadForRequest(authUrl).single()
        val queryCookie = scope.cookies.loadForRequest(url).single()
        assertEquals("/auth/login", authCookie.path)
        assertEquals("/api", queryCookie.path)
        assertTrue(queryCookie.secure); assertTrue(queryCookie.hostOnly)
        assertEquals("synthetic", queryCookie.value)
        for (target in listOf("$origin/", "$origin/auth", "$origin/api2", "$origin/private",
            "https://other.example/api/login", "$origin:444/api/login"))
            assertTrue(scope.cookies.loadForRequest(target.toHttpUrl()).isEmpty())
    }

    @Test fun realResponseCookiesAreHostOnlySecureOriginBoundAndConfinedToApprovedPaths() {
        val op = operation()
        val scope = PluginNetworkScopes.requireGrant(op, grant(op))
        val response = Cookie.parse(url, "sid=synthetic; Domain=school.example; Path=/; HttpOnly")!!
        scope.cookies.saveFromResponse(url, listOf(response))
        val saved = scope.cookies.loadForRequest(url).single()
        assertTrue(saved.hostOnly); assertTrue(saved.secure); assertTrue(saved.httpOnly)
        assertEquals("/api", saved.path)
        for (target in listOf("$origin/private", "$origin/api2", "$origin:444/api/login",
            "https://sub.school.example/api/login", "http://school.example/api/login"))
            assertTrue(scope.cookies.loadForRequest(target.toHttpUrl()).isEmpty())
        scope.cookies.saveFromResponse("https://evil.example/api/login".toHttpUrl(), listOf(response))
        assertEquals(1, scope.cookies.loadForRequest(url).size)
        scope.cookies.saveFromResponse(url, listOf(Cookie.parse(url, "sid=; Path=/; Max-Age=0")!!))
        assertTrue(scope.cookies.loadForRequest(url).isEmpty())
    }

    @Test fun closedOperationJarRejectsLateResponsesButNextOperationCanUseTheGrant() {
        val session = session()
        val op = operation(session)
        val token = grant(op)
        val scope = PluginNetworkScopes.requireGrant(op, token)
        op.close()
        rejected { scope.cookies.saveFromResponse(url, listOf(Cookie.parse(url, "sid=late; Path=/")!!)) }
        val next = PluginNetworkScopes.requireGrant(operation(session), token)
        assertTrue(next.cookies.loadForRequest(url).isEmpty())
        rejected { PluginNetworkScopes.requireGrant(operation(session), "unknown-grant") }
    }
}

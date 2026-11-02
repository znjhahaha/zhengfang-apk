package com.tyust.course.academic.plugin

import android.app.Application
import android.content.Context
import android.net.Uri
import com.tyust.course.academic.AcademicSession
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PluginSchoolNetworkConsentTest {
    private val app = RuntimeEnvironment.getApplication()
    private val pluginId = "test.school.consent"
    private val origin = "https://school.example.test"
    private val prefs get() = app.getSharedPreferences("plugin-school-network-consent", Context.MODE_PRIVATE)
    private val hosts = mutableListOf<Pair<NativeCapabilityHost, Page>>()
    private val sessions = mutableListOf<AcademicSession>()
    private var prompts = 0
    private class Page { var active = true }

    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        prefs.edit().clear().commit()
    }

    @After fun cleanup() {
        hosts.forEach { (_, page) -> page.active = false }
        sessions.forEach { it.retire() }
        hosts.forEach { (host, _) -> host.close() }
        PluginSchoolNetworkConsent.revoke(app, pluginId)
        Dispatchers.resetMain()
    }

    private fun newPackage(digest: String = "a".repeat(64), id: String = pluginId) = PluginPackage(
        PluginManifest(JSONObject("""{
          "id":"$id","name":"Synthetic school","version":"1.0.0","kind":"native","apiVersion":3,
          "dynamicNetworkScopes":true,"permissions":["network","auth","academic.read"],
          "requires":[{"name":"network.scope.authorize","version":1}],
          "servers":[{"id":"platform","title":"Synthetic platform","origin":"https://platform.example.test","authentication":"native"},
                     {"id":"other-platform","title":"Other platform","origin":"https://other-platform.example.test","authentication":"native"}],
          "network":[],"contributes":{"pages":[],"entries":[]}
        }""")), "", digest, false)

    private fun newSession(pkg: PluginPackage, serverId: String = "platform") =
        PluginServiceAccounts(app).session(pkg, serverId).also { sessions += it }

    private fun newHost(pkg: PluginPackage, session: AcademicSession, page: Page = Page(),
        onConfirm: suspend (String, String) -> Boolean = { _, _ -> true }): NativeCapabilityHost {
        val interaction = object : NativePluginInteraction {
            override suspend fun confirm(title: String, message: String): Boolean {
                prompts++
                assertEquals("授权学校网络", title)
                assertTrue(message.contains("撤销学校网络授权"))
                return onConfirm(title, message)
            }
            override suspend fun authenticate(challenge: JSONObject, image: File?): JSONObject? = null
            override suspend fun pick(types: Array<String>): Uri? = null
            override suspend fun notificationPermission() = false
            override fun haptic() {}
            override fun navigate(pageId: String, params: JSONObject) {}
            override fun back() {}
        }
        return NativeCapabilityHost(app, pkg, session, interaction) { page.active }.also { hosts += it to page }
    }

    private fun rule(path: String = "/api", site: String = origin) = JSONObject().put("origin", site)
        .put("pathPrefix", path).put("methods", JSONArray(listOf("GET", "POST")))
        .put("purposes", JSONArray(listOf("auth", "query")))

    private fun effect(name: String, input: JSONObject) = JSONObject().put("id", "synthetic")
        .put("capability", name).put("version", 1).put("input", input)

    private suspend fun authorize(host: NativeCapabilityHost, rules: JSONArray = JSONArray().put(rule()),
        tenant: String = "synthetic-school", server: String = "platform", gesture: Boolean = true): String =
        (host.execute(effect("network.scope.authorize", JSONObject().put("serverId", server)
            .put("tenantId", tenant).put("rules", rules)), NativeFlow(gesture)) as JSONObject).getString("grant")

    private fun operation(pkg: PluginPackage, session: AcademicSession) =
        PluginOperation(session, pkg.manifest, "host.effect", development = !pkg.official, packageDigest = pkg.digest)

    private fun storedApprovals() = prefs.getString("approvals", "[]")!!

    @Test fun repeatedAndNarrowerScopePromptsOnceAndAlwaysIssuesFreshGrants(): Unit = runBlocking {
        val pkg = newPackage(); val session = newSession(pkg); val host = newHost(pkg, session)
        val first = authorize(host)
        val reordered = rule().put("methods", JSONArray(listOf("POST", "GET")))
            .put("purposes", JSONArray(listOf("query", "auth")))
        val second = authorize(host, JSONArray().put(reordered), gesture = false)
        val narrow = rule("/api/tasks").put("methods", JSONArray(listOf("GET")))
            .put("purposes", JSONArray(listOf("query")))
        val third = authorize(host, JSONArray().put(narrow), gesture = false)
        assertEquals(1, prompts)
        assertEquals(3, setOf(first, second, third).size)
        val op = operation(pkg, session)
        try {
            val scope = PluginNetworkScopes.requireGrant(op, third)
            scope.policy.requireAllowed("$origin/api/tasks/list".toHttpUrl(), "GET", "query", null)
            assertThrows(PluginException::class.java) { scope.policy.requireAllowed("$origin/api/login".toHttpUrl(), "GET", "query", null) }
            assertThrows(PluginException::class.java) { scope.policy.requireAllowed("$origin/api/tasks".toHttpUrl(), "POST", "auth", null) }
        } finally { op.close() }
    }

    @Test fun unapprovedScopeCannotUseTheSilentOrHeadlessPath(): Unit = runBlocking {
        val pkg = newPackage(); val session = newSession(pkg); val host = newHost(pkg, session)
        assertTrue(runCatching { authorize(host, gesture = false) }.exceptionOrNull() is PluginException)
        assertEquals(0, prompts); assertEquals("[]", storedApprovals())
        authorize(host)
        var headlessActive = true
        val headless = NativeCapabilityHost(app, pkg, session, null) { headlessActive }
        try {
            authorize(headless, gesture = false)
            assertTrue(runCatching { authorize(headless, JSONArray().put(rule("/private")), gesture = false) }.exceptionOrNull() is PluginException)
        } finally { headlessActive = false; headless.close() }
        assertEquals(1, prompts)
    }

    @Test fun everyChangedBoundaryOrConstraintNeedsAnotherPrompt(): Unit = runBlocking {
        val pkg = newPackage(); val session = newSession(pkg); val host = newHost(pkg, session)
        val base = rule().put("requiredQuery", JSONObject().put("campus", "synthetic-campus"))
            .put("requiredForm", JSONObject().put("kind", "synthetic-read"))
            .put("authHeader", "X-Token").put("userAgent", "Synthetic/1")
        authorize(host, JSONArray().put(base))
        val changes = listOf<(JSONObject) -> Unit>(
            { it.put("origin", "https://other-school.example.test") },
            { it.put("pathPrefix", "/api2") },
            { it.put("pathPrefix", "/") },
            { it.put("methods", JSONArray(listOf("PUT"))) },
            { it.put("purposes", JSONArray(listOf("mutation"))) },
            { it.put("requiredQuery", JSONObject().put("campus", "other-campus")) },
            { it.remove("requiredQuery") },
            { it.put("requiredForm", JSONObject().put("kind", "other-read")) },
            { it.remove("requiredForm") },
            { it.put("userAgent", "Synthetic/2") },
            { it.remove("authHeader") }
        )
        changes.forEachIndexed { index, change ->
            val changed = JSONObject(base.toString()).also(change)
            authorize(host, JSONArray().put(changed))
            assertEquals("change $index reused consent", index + 2, prompts)
        }
    }

    @Test fun removingASpecificHeaderRestrictionIsNotAReusableSubset(): Unit = runBlocking {
        val pkg = newPackage(); val session = newSession(pkg); val host = newHost(pkg, session)
        val broad = rule().put("authHeader", "X-Token")
        val privateRule = rule("/api/private")
        val original = JSONArray().put(broad).put(privateRule)
        val first = authorize(host, original)
        val op = operation(pkg, session)
        try {
            assertThrows(PluginException::class.java) {
                PluginNetworkScopes.requireGrant(op, first).policy.requireAllowed("$origin/api/private".toHttpUrl(), "GET", "query", null, "X-Token")
            }
            authorize(host, original, gesture = false)
            assertEquals(1, prompts)
            authorize(host, JSONArray().put(broad))
            assertEquals(2, prompts)
        } finally { op.close() }
    }

    @Test fun conflictingUserAgentOrderChangesNeedAnotherPrompt(): Unit = runBlocking {
        val pkg = newPackage(); val session = newSession(pkg); val host = newHost(pkg, session)
        val a = rule().put("userAgent", "Synthetic/1")
        val b = rule().put("userAgent", "Synthetic/2")
        authorize(host, JSONArray().put(a).put(b))
        authorize(host, JSONArray().put(b).put(a))
        assertEquals(2, prompts)
    }

    @Test fun promptCannotMutateTheValidatedScopeThatIsGrantedOrRemembered(): Unit = runBlocking {
        val pkg = newPackage(); val session = newSession(pkg)
        val request = JSONArray().put(rule().put("requiredQuery", JSONObject().put("campus", "original")))
        val host = newHost(pkg, session) { _, _ ->
            request.getJSONObject(0).put("origin", "https://unapproved.example.test")
                .getJSONObject("requiredQuery").put("campus", "changed")
            true
        }
        val grant = authorize(host, request)
        val op = operation(pkg, session)
        try {
            val policy = PluginNetworkScopes.requireGrant(op, grant).policy
            policy.requireAllowed("$origin/api?campus=original".toHttpUrl(), "GET", "query", null)
            assertThrows(PluginException::class.java) { policy.requireAllowed("$origin/api?campus=changed".toHttpUrl(), "GET", "query", null) }
            assertThrows(PluginException::class.java) { policy.requireAllowed("https://unapproved.example.test/api?campus=changed".toHttpUrl(), "GET", "query", null) }
            authorize(host, JSONArray().put(rule().put("requiredQuery", JSONObject().put("campus", "original"))), gesture = false)
            assertEquals(1, prompts)
        } finally { op.close() }
    }

    @Test fun queryAndFormKeyOrderingIsNormalizedWithoutPersistingTheirValues(): Unit = runBlocking {
        val pkg = newPackage(); val host = newHost(pkg, newSession(pkg))
        val secret = "synthetic-key-never-store"
        val original = rule().put("requiredQuery", JSONObject().put("b", "2").put("a", secret))
            .put("requiredForm", JSONObject().put("y", "4").put("x", secret))
        val grant = authorize(host, JSONArray().put(original))
        val reordered = rule().put("requiredQuery", JSONObject().put("a", secret).put("b", "2"))
            .put("requiredForm", JSONObject().put("x", secret).put("y", "4"))
        authorize(host, JSONArray().put(reordered))
        assertEquals(1, prompts)
        assertFalse(prefs.all.toString().contains(secret))
        assertFalse(prefs.all.toString().contains(grant))
        assertFalse(prefs.all.toString().contains(pkg.digest))
        assertFalse(prefs.all.toString().contains(host.session.key.accountKey))
    }

    @Test fun accountSwitchDoesNotLeakConsentAndSwitchingBackUsesOnlyThatAccountsApproval(): Unit = runBlocking {
        val pkg = newPackage(); val accounts = PluginServiceAccounts(app)
        accounts.select(pkg, "platform", "first-synthetic-account")
        val firstSession = newSession(pkg); val first = newHost(pkg, firstSession)
        val firstGrant = authorize(first)
        accounts.select(pkg, "platform", "second-synthetic-account")
        val secondSession = newSession(pkg); authorize(newHost(pkg, secondSession))
        assertEquals(2, prompts)
        val op = operation(pkg, secondSession)
        try { assertThrows(PluginException::class.java) { PluginNetworkScopes.requireGrant(op, firstGrant) } } finally { op.close() }
        accounts.select(pkg, "platform", "first-synthetic-account")
        authorize(newHost(pkg, newSession(pkg)), gesture = false)
        assertEquals(2, prompts)
    }

    @Test fun digestSchoolAndServiceServerArePartOfThePersistedIdentity(): Unit = runBlocking {
        val pkg = newPackage(); val original = newHost(pkg, newSession(pkg))
        authorize(original)
        val update = newPackage(digest = "b".repeat(64))
        authorize(newHost(update, newSession(update)))
        authorize(original, tenant = "other-synthetic-school")
        authorize(newHost(pkg, newSession(pkg, "other-platform")), server = "other-platform")
        assertEquals(4, prompts)
        authorize(original, gesture = false)
        assertEquals(4, prompts)
    }

    @Test fun anotherPluginCannotReuseTheApproval(): Unit = runBlocking {
        val pkg = newPackage(); authorize(newHost(pkg, newSession(pkg)))
        val other = newPackage(id = "$pluginId.other")
        try {
            authorize(newHost(other, newSession(other)))
            assertEquals(2, prompts)
        } finally { PluginSchoolNetworkConsent.revoke(app, other.manifest.id) }
    }

    @Test fun aNewHostAndSessionReadDurableApprovalButCannotReuseTheOldSessionGrant(): Unit = runBlocking {
        val pkg = newPackage(); val oldSession = newSession(pkg); val oldHost = newHost(pkg, oldSession)
        val oldGrant = authorize(oldHost)
        val stored = prefs.all.toMap()
        oldSession.retire(); PluginNetworkScopes.revokePlugin(pluginId)
        // Reconstruct the persisted state; no live grant or host-local consent is carried over.
        prefs.edit().clear().commit()
        val edit = prefs.edit()
        stored.forEach { (key, value) -> when (value) { is String -> edit.putString(key, value); is Long -> edit.putLong(key, value) } }
        assertTrue(edit.commit())
        val freshSession = newSession(pkg); val freshHost = newHost(pkg, freshSession)
        val freshGrant = authorize(freshHost, gesture = false)
        assertEquals(1, prompts); assertNotEquals(oldGrant, freshGrant)
        val op = operation(pkg, freshSession)
        try {
            assertThrows(PluginException::class.java) { PluginNetworkScopes.requireGrant(op, oldGrant) }
            assertTrue(PluginNetworkScopes.requireGrant(op, freshGrant).cookies.loadForRequest("$origin/api".toHttpUrl()).isEmpty())
        } finally { op.close() }
    }

    @Test fun declinedPromptIsNotRecordedAndTheNextAttemptPromptsAgain(): Unit = runBlocking {
        val pkg = newPackage(); val session = newSession(pkg)
        val refused = newHost(pkg, session) { _, _ -> false }
        assertTrue(runCatching { authorize(refused) }.exceptionOrNull() is PluginException)
        assertEquals("[]", storedApprovals())
        authorize(newHost(pkg, session))
        assertEquals(2, prompts)
    }

    @Test fun strictRuntimeValidationHappensBeforePromptingOrRecording(): Unit = runBlocking {
        val pkg = newPackage(); val host = newHost(pkg, newSession(pkg))
        val invalid = listOf(
            rule(site = "http://school.example.test"), rule(site = "$origin/"),
            rule("/api*"), rule("/api//login"), rule("/api/../private"),
            rule().put("requiredQuery", JSONObject().put("tooLong", "x".repeat(2049))),
            rule().put("requiredForm", JSONObject().apply { repeat(33) { put("field$it", "value") } }),
            rule().put("userAgent", "Synthetic\r\nInjected: header")
        )
        invalid.forEach { value -> assertTrue(runCatching { authorize(host, JSONArray().put(value)) }.exceptionOrNull() is PluginException) }
        assertTrue(runCatching { authorize(host, tenant = " whitespace ") }.exceptionOrNull() is PluginException)
        assertTrue(runCatching { authorize(host, server = "undeclared") }.exceptionOrNull() is PluginException)
        assertEquals(0, prompts); assertEquals("[]", storedApprovals())
        authorize(host)
        // An existing approval must not bypass the same strict validation on reuse.
        assertTrue(runCatching { authorize(host, JSONArray().put(rule().put("requiredQuery", JSONObject().put("tooLong", "x".repeat(2049))))) }.exceptionOrNull() is PluginException)
        assertEquals(1, prompts)
    }

    @Test fun changingTheSessionOrAccountOrPageDuringPromptCannotRecordConsent(): Unit = runBlocking {
        val pkg = newPackage(); val accounts = PluginServiceAccounts(app)
        for (change in listOf("epoch", "account", "page", "revoke", "close")) {
            val session = newSession(pkg); val page = Page(); lateinit var host: NativeCapabilityHost
            host = newHost(pkg, session, page) { _, _ ->
                when (change) {
                    "epoch" -> session.invalidate()
                    "account" -> accounts.select(pkg, "platform", "changed-in-prompt")
                    "page" -> page.active = false
                    "revoke" -> PluginSchoolNetworkConsent.revoke(app, pluginId)
                    "close" -> { page.active = false; host.close() }
                }
                true
            }
            assertTrue("$change accepted", runCatching { authorize(host) }.exceptionOrNull() is PluginException)
            assertEquals("$change recorded", "[]", storedApprovals())
        }
    }

    @Test fun coroutineCancellationDuringPromptCannotRecordConsent(): Unit = runBlocking {
        val pkg = newPackage(); val shown = CompletableDeferred<Unit>(); val answer = CompletableDeferred<Boolean>()
        val host = newHost(pkg, newSession(pkg)) { _, _ -> shown.complete(Unit); answer.await() }
        val work = async { authorize(host) }
        shown.await(); work.cancelAndJoin(); answer.complete(true)
        assertEquals("[]", storedApprovals())
        authorize(newHost(pkg, host.session))
        assertEquals(2, prompts)
    }

    @Test fun sessionInvalidationAtCommitRollsBackTheApproval(): Unit = runBlocking {
        val pkg = newPackage(); val session = newSession(pkg); val op = operation(pkg, session)
        try {
            val consent = PluginSchoolNetworkConsent(app, pkg)
            val error = runCatching {
                consent.authorize(op, "platform", "synthetic-school", JSONArray().put(rule()), active = {
                    if (storedApprovals() != "[]") session.invalidate()
                }) { prompts++ }
            }.exceptionOrNull()
            assertTrue(error is PluginException)
            assertEquals("[]", storedApprovals()); assertEquals(1, prompts)
        } finally { op.close() }
    }

    @Test fun failedCommitGuardPreservesAnEarlierGrantAndApproval(): Unit = runBlocking {
        val pkg = newPackage(); val session = newSession(pkg)
        val earlierGrant = authorize(newHost(pkg, session))
        val previous = storedApprovals()
        val consent = PluginSchoolNetworkConsent(app, pkg); val op = operation(pkg, session)
        try {
            val error = runCatching {
                consent.authorize(op, "platform", "synthetic-school", JSONArray().put(rule("/other")), active = {
                    if (JSONArray(storedApprovals()).length() == 2)
                        throw PluginException(PluginErrorCode.STALE_CONTEXT, "Synthetic page changed at commit")
                }) { prompts++ }
            }.exceptionOrNull()
            assertTrue(error is PluginException); assertEquals(previous, storedApprovals())
            PluginNetworkScopes.requireGrant(op, earlierGrant)
        } finally { op.close() }
    }

    @Test fun revocationAtCommitCannotRestoreAnyEarlierApproval(): Unit = runBlocking {
        val pkg = newPackage(); val session = newSession(pkg)
        authorize(newHost(pkg, session))
        val consent = PluginSchoolNetworkConsent(app, pkg); val op = operation(pkg, session)
        try {
            var revoked = false
            val error = runCatching {
                consent.authorize(op, "platform", "synthetic-school", JSONArray().put(rule("/other")), active = {
                    if (!revoked && JSONArray(storedApprovals()).length() == 2) {
                        revoked = true; PluginSchoolNetworkConsent.revoke(app, pluginId)
                    }
                }) { prompts++ }
            }.exceptionOrNull()
            assertTrue(error is PluginException); assertTrue(revoked)
            assertEquals("[]", storedApprovals())
            authorize(newHost(pkg, session))
            assertEquals(3, prompts)
        } finally { op.close() }
    }

    @Test fun concurrentConsumersShareOnePromptAndReceiveSeparateGrants(): Unit = runBlocking {
        val pkg = newPackage(); val session = newSession(pkg)
        val shown = CompletableDeferred<Unit>(); val answer = CompletableDeferred<Boolean>()
        val first = newHost(pkg, session) { _, _ -> shown.complete(Unit); answer.await() }
        val second = newHost(pkg, session)
        val a = async(start = CoroutineStart.UNDISPATCHED) { authorize(first) }
        shown.await()
        val b = async(start = CoroutineStart.UNDISPATCHED) { authorize(second) }
        yield(); answer.complete(true)
        assertNotEquals(a.await(), b.await()); assertEquals(1, prompts)
    }

    @Test fun explicitRevocationClearsDurableApprovalAndRejectsOldGrantsAndLateCookies(): Unit = runBlocking {
        val pkg = newPackage(); val session = newSession(pkg); val host = newHost(pkg, session)
        val grant = authorize(host); val op = operation(pkg, session)
        try {
            val scope = PluginNetworkScopes.requireGrant(op, grant)
            val url = "$origin/api".toHttpUrl()
            val cookie = Cookie.parse(url, "sid=synthetic-cookie; Path=/")!!
            scope.cookies.saveFromResponse(url, listOf(cookie))
            assertFalse(prefs.all.toString().contains("synthetic-cookie"))
            assertTrue(PluginSchoolNetworkConsent.remembered(app, pluginId))
            PluginSchoolNetworkConsent.revoke(app, pluginId)
            assertFalse(PluginSchoolNetworkConsent.remembered(app, pluginId)); assertEquals("[]", storedApprovals())
            assertThrows(PluginException::class.java) { PluginNetworkScopes.requireGrant(op, grant) }
            assertThrows(PluginException::class.java) { scope.cookies.saveFromResponse(url, listOf(cookie)) }
            assertThrows(PluginException::class.java) { scope.cookies.loadForRequest(url) }
            authorize(newHost(pkg, session))
            assertEquals(2, prompts)
        } finally { op.close() }
    }

    @Test fun privacyRevocationAlsoClearsSchoolConsent(): Unit = runBlocking {
        val pkg = newPackage(); val session = newSession(pkg); val host = newHost(pkg, session)
        authorize(host)
        host.execute(effect("privacy.revoke", JSONObject()), NativeFlow(true))
        assertEquals("[]", storedApprovals())
        authorize(newHost(pkg, session))
        assertEquals(2, prompts)
    }

    @Test fun malformedDurableRecordsCannotAuthorizeAnything(): Unit = runBlocking {
        prefs.edit().putString("approvals", "[{\"owner\":\"untrusted\",\"rules\":[]}] ").commit()
        val pkg = newPackage(); authorize(newHost(pkg, newSession(pkg)))
        assertEquals(1, prompts)
    }

    @Test fun approvalHistoryHasAnEntryLimitAndEvictedScopesNeedConsentAgain(): Unit = runBlocking {
        val pkg = newPackage(); val host = newHost(pkg, newSession(pkg))
        repeat(140) { authorize(host, JSONArray().put(rule("/scope$it"))) }
        assertEquals(140, prompts)
        assertEquals(128, JSONArray(storedApprovals()).length())
        assertTrue(storedApprovals().toByteArray(Charsets.UTF_8).size <= 256 * 1024)
        authorize(host, JSONArray().put(rule("/scope139")), gesture = false)
        assertTrue(runCatching { authorize(host, JSONArray().put(rule("/scope0")), gesture = false) }.exceptionOrNull() is PluginException)
        assertEquals(140, prompts)
    }

    @Test fun approvalHistoryAlsoHasATotalByteLimit(): Unit = runBlocking {
        val pkg = newPackage(); val host = newHost(pkg, newSession(pkg))
        repeat(12) { batch ->
            val rules = JSONArray()
            repeat(32) { index -> rules.put(rule("/batch$batch/item$index/" + "x".repeat(980))) }
            authorize(host, rules)
        }
        assertEquals(12, prompts)
        assertTrue(JSONArray(storedApprovals()).length() < 12)
        assertTrue(storedApprovals().toByteArray(Charsets.UTF_8).size <= 256 * 1024)
    }

    @Test fun deletingAnAccountClearsOnlyItsPersistedApproval(): Unit = runBlocking {
        val pkg = newPackage(); val accounts = PluginServiceAccounts(app)
        val firstId = accounts.select(pkg, "platform", "removable-synthetic-account")
        authorize(newHost(pkg, newSession(pkg)))
        accounts.select(pkg, "platform", "retained-synthetic-account")
        authorize(newHost(pkg, newSession(pkg)))
        accounts.remove(pkg, "platform", firstId)
        authorize(newHost(pkg, newSession(pkg)), gesture = false)
        assertEquals(2, prompts)
        assertEquals(1, JSONArray(storedApprovals()).length())
    }

    @Test fun clearingPluginDataAlsoClearsRememberedSchoolConsent(): Unit = runBlocking {
        val pkg = newPackage(); authorize(newHost(pkg, newSession(pkg)))
        PluginServiceAccounts(app).clearPlugin(pkg)
        assertEquals("[]", storedApprovals())
        authorize(newHost(pkg, newSession(pkg)))
        assertEquals(2, prompts)
    }
}

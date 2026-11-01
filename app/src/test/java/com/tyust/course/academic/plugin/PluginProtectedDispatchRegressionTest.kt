package com.tyust.course.academic.plugin

import android.app.Application
import android.net.Uri
import com.tyust.course.academic.AcademicSession
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PluginProtectedDispatchRegressionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val sessions = mutableListOf<AcademicSession>()
    private val hosts = mutableListOf<NativeCapabilityHost>()
    private var pageActive = true
    private val interaction = object : NativePluginInteraction {
        override suspend fun confirm(title: String, message: String) = true
        override suspend fun authenticate(challenge: JSONObject, image: File?) = JSONObject().put("values", JSONObject().put("otp", "synthetic-otp"))
        override suspend fun pick(types: Array<String>): Uri? = null
        override suspend fun notificationPermission() = false
        override fun haptic() {}
        override fun navigate(pageId: String, params: JSONObject) {}
        override fun back() {}
    }
    @Before fun setup() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun cleanup() {
        pageActive = false
        hosts.forEach { it.close() }
        sessions.forEach { it.retire() }
        PluginNetworkScopes.revokePlugin("test.protected.dispatch")
        PluginHttpClients.clearPlugin("test.protected.dispatch")
        Dispatchers.resetMain()
    }
    private fun pkg(protected: Boolean = true, official: Boolean = false, origin: String = "https://platform.example.test") = PluginPackage(
        PluginManifest(JSONObject("""{"id":"test.protected.dispatch","name":"Synthetic","version":"1.0.0","kind":"native","apiVersion":3,
        "capabilities":[],"permissions":["network","auth","credentials"],
        "requires":[{"name":"network.request","version":5},{"name":"network.scope.authorize","version":1}],
        "servers":[{"id":"platform","title":"Synthetic","origin":"$origin","authentication":"native"}],
        "network":[{"origin":"$origin","pathPrefix":"/api","methods":["GET","POST"],"purposes":["auth","query"]}],
        "contributes":{"pages":[],"entries":[]}}""").apply { if (protected) put("dynamicNetworkScopes", true) }), "", "d".repeat(64), official)
    private fun session(pkg: PluginPackage) = PluginServiceAccounts(app).session(pkg, "platform").also { sessions += it }
    private fun host(pkg: PluginPackage, session: AcademicSession) = NativeCapabilityHost(app, pkg, session, interaction) { pageActive }.also { hosts += it }
    private fun effect(name: String, input: JSONObject, version: Int = 1) = JSONObject().put("id", "test").put("capability", name).put("version", version).put("input", input)
    private fun operation(pkg: PluginPackage, session: AcademicSession, method: String = "serviceAuth.validate") =
        PluginOperation(session, pkg.manifest, method, development = !pkg.official, packageDigest = pkg.digest)
    private fun rules() = JSONArray().put(JSONObject("""{"origin":"https://school.example.test","pathPrefix":"/api","methods":["GET","POST"],"purposes":["auth","query"]}"""))
    private fun grant(pkg: PluginPackage, session: AcademicSession): String = operation(pkg, session).let { op ->
        try { PluginNetworkScopes.authorize(op, "school", rules()) } finally { op.close() }
    }

    @Test fun nativeAuthorizationUsesPackageTrustModeAndDestinationChecksRecognizeTheGrant() = runBlocking {
        for (official in listOf(false, true)) {
            val pkg = pkg(official = official); val session = session(pkg); val host = host(pkg, session)
            val result = host.execute(effect("network.scope.authorize", JSONObject().put("serverId", "platform").put("tenantId", "school").put("rules", rules())), NativeFlow(true)) as JSONObject
            val grant = result.getString("grant")
            val op = operation(pkg, session)
            try { assertEquals("school", PluginNetworkScopes.requireGrant(op, grant).tenantId) } finally { op.close() }
            try {
                host.execute(effect("network.request", JSONObject().put("url", "https://school.example.test/outside").put("purpose", "query").put("scopeGrant", grant), 5), NativeFlow(false))
                fail("outside path accepted")
            } catch (error: PluginException) { assertEquals(PluginErrorCode.UNTRUSTED_URL, error.code) }
            try {
                PluginServiceAuthentication.execute(app, pkg, session, JSONObject().put("action", "validate").put("serverId", "platform").put("tenantId", "school").put("scopeGrant", grant)) { true }
                fail("missing hook accepted")
            } catch (error: PluginException) { assertEquals("grant must pass before missing hook", PluginErrorCode.UNSUPPORTED, error.code) }
        }
    }

    @Test fun closingPageAndUiConsumerPreservesSharedSessionStateAndGrantsUntilOwnerSwitch() {
        val pkg = pkg(); val accounts = PluginServiceAccounts(app); val session = session(pkg)
        val token = grant(pkg, session)
        val op = operation(pkg, session)
        val privateHost = PluginHost(op, app.cacheDir)
        privateHost.call("state.set", JSONObject().put("key", "private").put("value", "retained"))
        val lifetime = PluginPageLifetime(app, pkg, session) { pageActive }
        val ui = NativeUiSession(app, pkg, session, host(pkg, session)) { pageActive }
        pageActive = false // Consumer close must not persist encrypted cookies in this JVM test.
        lifetime.close(); ui.close()
        assertFalse(session.retired)
        assertSame(session, accounts.session(pkg, "platform"))
        assertEquals("retained", privateHost.call("state.get", JSONObject().put("key", "private")).getString("data"))
        PluginNetworkScopes.requireGrant(op, token)
        accounts.select(pkg, "platform", "second-account")
        assertTrue(session.retired)
        assertThrows(PluginException::class.java) { PluginNetworkScopes.requireGrant(op, token) }
        op.close()
    }

    @Test fun oldCallerOwnedSessionStillRetiresOnPageClose() {
        val pkg = pkg(protected = false); val session = session(pkg)
        val lifetime = PluginPageLifetime(app, pkg, session) { pageActive }
        pageActive = false; lifetime.close()
        assertTrue(session.retired)
        assertNotSame(session, session(pkg))
    }

    @Test fun failingLogoutHookStillInvalidatesEpochRevokesGrantAndClearsPrivateAndOtpState() = runBlocking {
        val pkg = pkg(); val session = session(pkg); val token = grant(pkg, session)
        val epoch = session.epoch
        val vault = NativePluginVault(app, PluginStorageScope.session(session, pkg.manifest.id, !pkg.official), sharedEphemeral = true)
        val handle = vault.saveCredential("otp", JSONObject().put("otp", "synthetic"), false)
        val oldOp = operation(pkg, session)
        val privateHost = PluginHost(oldOp, app.cacheDir)
        privateHost.call("state.set", JSONObject().put("key", "token").put("value", "private-token"))
        try {
            PluginServiceAuthentication.execute(app, pkg, session, JSONObject().put("action", "logout").put("serverId", "platform").put("tenantId", "school")) { true }
            fail("missing logout hook accepted")
        } catch (error: PluginException) { assertEquals(PluginErrorCode.UNSUPPORTED, error.code) }
        assertTrue(session.epoch > epoch)
        assertFalse(session.retired)
        assertNull(vault.get("credential:$handle"))
        assertThrows(PluginException::class.java) { PluginNetworkScopes.requireGrant(oldOp, token) }
        val next = operation(pkg, session)
        assertEquals(JSONObject.NULL, PluginHost(next, app.cacheDir).call("state.get", JSONObject().put("key", "token")).get("data"))
        oldOp.close(); next.close(); vault.clear()
    }

    @Test fun newSingleHandleBodyBindingConsumesOtpAndCannotReplayButOldBindingRemainsReusable() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            repeat(4) { server.enqueue(MockResponse().setBody("safe-result")) }
            val pkg = pkg(protected = false, origin = server.url("/").toString().trimEnd('/'))
            val session = session(pkg); val host = host(pkg, session)
            val prompt = JSONObject("""{"title":"Synthetic OTP","key":"otp","fields":[{"id":"otp","label":"OTP","type":"otp"}]}""")
            val handle = (host.execute(effect("auth.prompt", prompt), NativeFlow(true)) as JSONObject).getString("credential")
            val request = JSONObject().put("url", server.url("/api/verify").toString()).put("method", "POST").put("purpose", "query")
                .put("body", "{}").put("credential", handle).put("bindings", JSONObject("""{"body":{"a":{"from":"otp","encoding":"base64"}}}"""))
            assertEquals("safe-result", (host.execute(effect("network.request", request, 5), NativeFlow(false)) as JSONObject).getString("body"))
            assertEquals("c3ludGhldGljLW90cA==", JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).getString("a"))
            try { host.execute(effect("network.request", request, 5), NativeFlow(false)); fail("OTP replay accepted") }
            catch (error: PluginException) { assertEquals(PluginErrorCode.SESSION_EXPIRED, error.code) }
            val oldHandle = (host.execute(effect("auth.prompt", prompt), NativeFlow(true)) as JSONObject).getString("credential")
            val legacy = JSONObject().put("url", server.url("/api/verify").toString()).put("method", "POST").put("purpose", "query")
                .put("credential", oldHandle).put("bindings", JSONObject("""{"form":{"code":"otp"}}"""))
            repeat(2) { host.execute(effect("network.request", legacy), NativeFlow(false)); assertEquals("code=synthetic-otp", server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()) }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun cancelledFailedOrInvalidLogoutAlwaysCleansLocalStateAndRejectsLateAuth() = runBlocking {
        for (failure in listOf("network", "cancel", "invalid")) {
            val pkg = pkg(); val session = session(pkg); val token = grant(pkg, session)
            val old = operation(pkg, session)
            val scope = PluginNetworkScopes.requireGrant(old, token)
            scope.setCookies("https://school.example.test/api/login", JSONArray().put(JSONObject().put("name", "sid").put("value", "synthetic")))
            val vault = NativePluginVault(app, PluginStorageScope.session(session, pkg.manifest.id, !pkg.official), sharedEphemeral = true)
            val handle = vault.saveCredential("otp", JSONObject().put("otp", "pending"), false)
            val input = JSONObject().put("action", "logout").put("serverId", "platform").put("tenantId", "school")
            try {
                PluginServiceAuthentication.executeWithHook(app, pkg, session, input, { true }) { _, _ ->
                    when (failure) {
                        "network" -> throw PluginException(PluginErrorCode.NETWORK_RETRYABLE, "synthetic network failure")
                        "cancel" -> throw CancellationException("synthetic cancellation")
                        else -> JSONObject().put("state", "ready").put("rawToken", "forbidden")
                    }
                }
                fail("logout failure swallowed")
            } catch (error: Exception) {
                if (failure == "cancel") assertTrue(error is CancellationException)
                else assertEquals(if (failure == "invalid") PluginErrorCode.VALIDATION_FAILED else PluginErrorCode.NETWORK_RETRYABLE, (error as PluginException).code)
            }
            assertNull(vault.get("credential:$handle"))
            assertThrows(PluginException::class.java) { scope.cookies.loadForRequest("https://school.example.test/api/me".toHttpUrl()) }
            assertThrows(PluginException::class.java) { PluginHost(old, app.cacheDir).call("state.set", JSONObject().put("key", "late").put("value", "old-auth")) }
            assertFalse(session.retired)
            old.close(); vault.clear()
        }
    }

    @Test fun lateLogoutFromOlderEpochCannotClearANewerLogin() = runBlocking {
        val pkg = pkg(); val session = session(pkg)
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val pending = async {
            PluginServiceAuthentication.executeWithHook(app, pkg, session,
                JSONObject().put("action", "logout").put("serverId", "platform").put("tenantId", "school"), { true }) { _, _ ->
                entered.complete(Unit); finish.await(); JSONObject().put("state", "signed_out")
            }
        }
        entered.await()
        session.invalidate()
        val next = operation(pkg, session)
        val token = grant(pkg, session)
        val privateHost = PluginHost(next, app.cacheDir)
        privateHost.call("state.set", JSONObject().put("key", "new-login").put("value", "keep"))
        finish.complete(Unit); pending.await()
        next.requireActive()
        assertEquals("keep", privateHost.call("state.get", JSONObject().put("key", "new-login")).getString("data"))
        PluginNetworkScopes.requireGrant(next, token)
        next.close()
    }

    @Test fun expiredNewSingleHandleBodyBindingIsRejectedBeforeHttp() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody("must not be requested"))
            val pkg = pkg(origin = server.url("/").toString().trimEnd('/'))
            val session = session(pkg); val host = host(pkg, session)
            val vault = NativePluginVault(app, host.namespace, sharedEphemeral = true)
            val handle = vault.saveCredential("expired", JSONObject().put("otp", "expired"), false)
            vault.put("otp-expiry:$handle", JSONObject().put("expiresAt", 1), false)
            val request = JSONObject().put("url", server.url("/api/verify").toString()).put("method", "POST").put("purpose", "query")
                .put("body", "{}").put("credential", handle).put("bindings", JSONObject("""{"body":{"a":{"from":"otp","encoding":"base64"}}}"""))
            try { host.execute(effect("network.request", request, 5), NativeFlow(false)); fail("expired OTP accepted") }
            catch (error: PluginException) { assertEquals(PluginErrorCode.SESSION_EXPIRED, error.code) }
            assertEquals(0, server.requestCount)
            assertNull(vault.get("credential:$handle")); vault.clear()
        }
    }
}

package com.tyust.course.academic.plugin

import android.app.Application
import com.tyust.course.academic.AcademicSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class NativeSchoolNavigationTest {
    @Before fun setup() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private val app get() = RuntimeEnvironment.getApplication()
    private val id = "test.school.navigation"
    private fun pkg(digest: String = "a".repeat(64)) = PluginPackage(PluginManifest(JSONObject("""{
        "id":"$id","name":"Synthetic","version":"1.0.0","apiVersion":3,"kind":"native","minAppVersionCode":133,
        "dynamicNetworkScopes":true,"permissions":["network","auth","navigation"],
        "capabilities":["serviceAuth.start","serviceAuth.resume","serviceAuth.validate","serviceAuth.logout"],
        "requires":[{"name":"network.scope.authorize","version":1},{"name":"navigation.url","version":1}],
        "servers":[{"id":"school","title":"Synthetic","origin":"https://platform.example.test","authentication":"password"}],
        "network":[],"contributes":{"pages":[],"entries":[]}}""")), "", digest, false)
    private fun rule() = JSONObject("""{"origin":"https://ids.example.test","pathPrefix":"/authserver","methods":["GET"],"purposes":["auth"],"requiredQuery":{"service":"https://platform.example.test/callback"}}""")
    private class Interaction : NativePluginInteraction {
        var confirmations = 0
        override suspend fun confirm(title: String, message: String): Boolean { confirmations++; return true }
        override suspend fun authenticate(challenge: JSONObject, image: java.io.File?) = null
        override suspend fun pick(types: Array<String>): android.net.Uri? = null
        override suspend fun notificationPermission() = true
        override fun haptic() {}
        override fun navigate(pageId: String, params: JSONObject) {}
        override fun back() {}
    }
    private fun effect(url: String) = JSONObject().put("id", "open").put("capability", "navigation.url").put("version", 1)
        .put("input", JSONObject().put("url", url))
    private val entry = "https://ids.example.test/authserver/login?service=https%3A%2F%2Fplatform.example.test%2Fcallback"
    @Test fun approvedSchoolEntryOpensWithoutRepeatingNetworkConsentOrInjectingCookies(): Unit = runBlocking {
        val pkg = pkg(); val session = PluginServiceAccounts(app).session(pkg, "school"); val interaction = Interaction()
        val op = PluginOperation(session, pkg.manifest, "host.effect", development = true, packageDigest = pkg.digest)
        val host = NativeCapabilityHost(app, pkg, session, interaction) { true }
        try {
            PluginNetworkScopes.authorize(op, "tenant", listOf(rule()))
            host.execute(effect(entry), NativeFlow(true))
            assertEquals(0, interaction.confirmations)
            val intent = Shadows.shadowOf(app).nextStartedActivity
            assertEquals(entry, intent.dataString)
            assertFalse(intent.hasExtra("headers")); assertFalse(intent.hasExtra("cookie"))
        } finally { session.retire(); host.close(); op.close(); PluginNetworkScopes.revokePlugin(id) }
    }
    @Test fun missingRevokedOrDifferentAccountSchoolScopeCannotOpen(): Unit = runBlocking {
        val pkg = pkg(); val session = PluginServiceAccounts(app).session(pkg, "school")
        val op = PluginOperation(session, pkg.manifest, "host.effect", development = true, packageDigest = pkg.digest)
        val host = NativeCapabilityHost(app, pkg, session, Interaction()) { true }
        try {
            val unapproved = listOf(entry, "https://evil.example.test/authserver/login", entry.replace("authserver/login", "outside/login"))
            for (url in unapproved) {
                try { host.execute(effect(url), NativeFlow(true)); fail("unapproved URL opened") } catch (_: PluginException) {}
            }
            PluginNetworkScopes.authorize(op, "tenant", listOf(rule())); PluginNetworkScopes.revoke(session)
            try { host.execute(effect(entry), NativeFlow(true)); fail("revoked scope opened") } catch (_: PluginException) {}
        } finally { session.retire(); host.close(); op.close(); PluginNetworkScopes.revokePlugin(id) }
    }
    @Test fun approvedOriginStillRequiresExactPathQueryAndCanonicalUrl(): Unit = runBlocking {
        val pkg = pkg(); val session = PluginServiceAccounts(app).session(pkg, "school")
        val op = PluginOperation(session, pkg.manifest, "host.effect", development = true, packageDigest = pkg.digest)
        val host = NativeCapabilityHost(app, pkg, session, Interaction()) { true }
        try {
            PluginNetworkScopes.authorize(op, "tenant", listOf(rule()))
            for (url in listOf(entry + "#secret", entry.replace("callback", "other"), entry.replace("authserver/login", "authserver/%2e%2e/login"), entry.replace("authserver/login", "authserver//login"))) {
                try { host.execute(effect(url), NativeFlow(true)); fail("invalid URL opened") } catch (_: PluginException) {}
            }
            assertNull(Shadows.shadowOf(app).nextStartedActivity)
        } finally { session.retire(); host.close(); op.close(); PluginNetworkScopes.revokePlugin(id) }
    }
}

package com.tyust.course.academic.plugin

import android.app.Application
import com.tyust.course.academic.AcademicSession
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NativeCredentialBindingsV6Test {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun pkg(authVersion: Int = 2) = PluginPackage(PluginManifest(JSONObject("""{"id":"test.native.credential.v6","name":"Synthetic","version":"1.0.0","apiVersion":3,"minAppVersionCode":133,"kind":"native",
        "permissions":["auth","network"],"capabilities":["serviceAuth.start","serviceAuth.resume","serviceAuth.validate","serviceAuth.logout"],
        "requires":[{"name":"auth.service","version":$authVersion},{"name":"network.request","version":6}],
        "servers":[{"id":"school","title":"Synthetic","origin":"https://school.test","authentication":"password"}],
        "network":[],"contributes":{"pages":[],"entries":[]}}""")), "", "e".repeat(64), false)
    private fun effect(name: String, input: JSONObject, version: Int) = JSONObject().put("id", "v6").put("capability", name).put("version", version).put("input", input)
    @Test fun oldAuthEffectOrDeclarationCannotAcceptMethod() = runBlocking {
        for ((required, version) in listOf(2 to 1, 1 to 2)) {
            val pkg = pkg(required); val session = PluginServiceAccounts(app).session(pkg, "school")
            var live = true
            val host = NativeCapabilityHost(app, pkg, session, null) { live }
            try {
                val input = JSONObject("""{"action":"validate","serverId":"school","tenantId":"tenant","method":"password"}""")
                val error = try { host.execute(effect("auth.service", input, version), NativeFlow(false)); null } catch (e: PluginException) { e }
                assertEquals(PluginErrorCode.UNSUPPORTED, error?.code)
            } finally { live = false; host.close(); session.retire() }
        }
    }
    @Test fun methodReachesPrivateHandlerWhileOnlyStatusCanReturn() = runBlocking {
        val pkg = pkg(); val session = PluginServiceAccounts(app).session(pkg, "school")
        try {
            val input = JSONObject("""{"action":"validate","serverId":"school","tenantId":"tenant","method":"password"}""")
            val result = PluginServiceAuthentication.executeWithHook(app, pkg, session, input, { true }) { operation, args ->
                assertEquals("serviceAuth.validate", operation); assertEquals("password", args.getString("method")); assertFalse(args.has("action"))
                JSONObject().put("state", "ready")
            }
            assertEquals("ready", result.getString("state"))
            try {
                PluginServiceAuthentication.executeWithHook(app, pkg, session, input, { true }) { _, _ -> JSONObject().put("state", "ready").put("url", "https://school.test/?username=synthetic") }
                fail("Private URL escaped through public auth state")
            } catch (error: PluginException) { assertEquals(PluginErrorCode.VALIDATION_FAILED, error.code) }
        } finally { session.retire() }
    }
    @Test fun newQueryAndTransformsAreUnavailableToOrdinaryNetworkEffectsEvenAtVersionSix() = runBlocking {
        val pkg = pkg(); val session = PluginServiceAccounts(app).session(pkg, "school")
        var live = true
        val host = NativeCapabilityHost(app, pkg, session, null) { live }
        try {
            for (version in listOf(1, 5, 6)) {
                val input = JSONObject("""{"url":"https://school.test/auth/login","purpose":"query","credential":"missing","bindings":{"query":{"username":"username"}}}""")
                try { host.execute(effect("network.request", input, version), NativeFlow(false)); fail() }
                catch (error: PluginException) { assertTrue(error.code in setOf(PluginErrorCode.UNSUPPORTED, PluginErrorCode.VALIDATION_FAILED)) }
            }
        } finally { live = false; host.close(); session.retire() }
    }
    @Test fun newCapabilityDeclarationsCannotClaimCompatibilityWithApp132() {
        for (cap in listOf("auth.service", "network.request")) {
            val pkg = pkg(); pkg.manifest.json.put("minAppVersionCode", 132)
            pkg.manifest.json.put("requires", JSONArray().put(JSONObject().put("name", cap).put("version", if (cap == "auth.service") 2 else 6)))
            assertThrows(PluginException::class.java) { PluginPlatformContract.validate(pkg.manifest) }
        }
    }
}

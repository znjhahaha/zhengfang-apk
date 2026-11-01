package com.tyust.course.academic.plugin

import android.app.Application
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionKey
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PluginServiceSessionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun pkg() = PluginPackage(PluginManifest(JSONObject("""{"id":"test.stable.service","name":"Synthetic","version":"0.1.0","kind":"native","apiVersion":3,"dynamicNetworkScopes":true,"capabilities":[],"network":[],"permissions":[],"servers":[{"id":"platform","title":"Synthetic","origin":"https://platform.example.test"}],"contributes":{"pages":[],"entries":[]}}""")), "", "a".repeat(64), false)

    @Test fun protectedStateSurvivesSeparateOperationsAndClearsOnInvalidation() {
        val session = AcademicSession(AcademicSessionKey("service:test.stable.service:platform", "account"), "https://platform.example.test")
        val pkg = pkg()
        val root = File(app.filesDir,"test-service-state")
        PluginHost(PluginOperation(session,pkg.manifest,"serviceAuth.start"),root).call("state.set",JSONObject().put("key","secret").put("value","synthetic-private-key"))
        val value = PluginHost(PluginOperation(session,pkg.manifest,"services.invoke"),root).call("state.get",JSONObject().put("key","secret")).get("data")
        assertEquals("synthetic-private-key",value)
        session.invalidate()
        assertEquals(JSONObject.NULL,PluginHost(PluginOperation(session,pkg.manifest,"serviceAuth.validate"),root).call("state.get",JSONObject().put("key","secret")).get("data"))
        session.retire()
    }

    @Test fun stableServiceSessionsShareIdentityUntilInvalidated() {
        val accounts = PluginServiceAccounts(app); val pkg=pkg()
        val first=accounts.session(pkg,"platform"); val second=accounts.session(pkg,"platform")
        assertSame(first,second)
        first.retire()
        val replacement=accounts.session(pkg,"platform")
        assertNotSame(first,replacement); assertTrue(accounts.current(pkg,replacement))
        replacement.retire()
    }

    @Test fun otpIsEphemeralSharedAcrossProtectedDispatchAndConsumedOnce() {
        val scope="synthetic-otp-scope"
        val promptVault=NativePluginVault(app,scope,sharedEphemeral=true)
        val handle=promptVault.saveCredential("otp",JSONObject().put("otp","synthetic-otp"),true)
        val handlerVault=NativePluginVault(app,scope,sharedEphemeral=true)
        assertEquals("synthetic-otp",handlerVault.consumeCredential(handle).getString("otp"))
        assertThrows(PluginException::class.java) { handlerVault.consumeCredential(handle) }
        assertNull(NativePluginVault(app,"different-account").get("credential:$handle"))
        val expired=promptVault.saveCredential("otp",JSONObject().put("otp","synthetic-expired"),false)
        promptVault.put("otp-expiry:$expired",JSONObject().put("expiresAt",1),false)
        assertThrows(PluginException::class.java) { handlerVault.consumeCredential(expired) }
        assertNull(promptVault.get("credential:$expired"))
        promptVault.clear()
    }

    @Test fun clearingSharedVaultKeepsExistingAndNewConsumersOnTheSameBank() {
        val scope = "synthetic-shared-clear"
        val page = NativePluginVault(app, scope, sharedEphemeral = true)
        val handler = NativePluginVault(app, scope, sharedEphemeral = true)
        page.put("old", JSONObject().put("value", "before-clear"), false)
        handler.clearEphemeral()
        assertNull(page.get("old"))
        val fresh = NativePluginVault(app, scope, sharedEphemeral = true)
        val handle = page.saveCredential("otp", JSONObject().put("otp", "after-clear"), false)
        assertEquals("after-clear", fresh.consumeCredential(handle).getString("otp"))
        handler.clear()
        fresh.put("new", JSONObject().put("value", "after-full-clear"), false)
        assertEquals("after-full-clear", NativePluginVault(app, scope, sharedEphemeral = true).get("new")!!.getString("value"))
        page.clear()
    }
}

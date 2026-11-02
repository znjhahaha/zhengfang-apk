package com.tyust.course.academic.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.BuildConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real Android persistence and session grants; synthetic scope, no server requests. */
@RunWith(AndroidJUnit4::class)
class SchoolConsentDeviceTest {
    @Test fun reopenedSessionReusesConsentAndRevocationRequiresANewPrompt(): Unit = runBlocking {
        assumeTrue("Requires UI_PREVIEW", BuildConfig.UI_PREVIEW)
        val app = ApplicationProvider.getApplicationContext<Context>()
        val id = "device.test.school.consent." + UUID.randomUUID().toString().replace("-", "")
        val pkg = PluginPackage(PluginManifest(JSONObject("""{
          "id":"$id","name":"Synthetic consent","version":"1.0.0","apiVersion":3,"kind":"native",
          "dynamicNetworkScopes":true,"permissions":["network","auth"],
          "requires":[{"name":"network.scope.authorize","version":1}],
          "servers":[{"id":"service","title":"Synthetic","origin":"https://consent.example.invalid","authentication":"none"}],
          "network":[],"capabilities":[],"contributes":{"pages":[],"entries":[]}
        }""")), "", "a".repeat(64), false)
        val accounts = PluginServiceAccounts(app)
        var prompts = 0
        val interaction = object : NativePluginInteraction {
            override suspend fun confirm(title: String, message: String): Boolean { prompts++; return true }
            override suspend fun authenticate(challenge: JSONObject, image: java.io.File?): JSONObject? = error("No login")
            override suspend fun pick(types: Array<String>): android.net.Uri? = error("No file access")
            override suspend fun notificationPermission() = false
            override fun haptic() {}
            override fun navigate(pageId: String, params: JSONObject) {}
            override fun back() {}
        }
        val input = JSONObject().put("serverId", "service").put("tenantId", "synthetic-tenant")
            .put("rules", JSONArray().put(JSONObject().put("origin", "https://school.example.invalid")
                .put("pathPrefix", "/portal").put("methods", JSONArray(listOf("GET")))
                .put("purposes", JSONArray(listOf("query")))))
        val effect = JSONObject().put("id", "consent").put("capability", "network.scope.authorize").put("version", 1).put("input", input)
        val grants = mutableSetOf<String>()
        suspend fun open(gesture: Boolean) {
            val session = accounts.session(pkg, "service")
            val host = NativeCapabilityHost(app, pkg, session, interaction) { true }
            try { grants += (host.execute(effect, NativeFlow(gesture)) as JSONObject).getString("grant") }
            finally { host.close(); session.retire() }
        }
        try {
            open(true); open(false)
            assertEquals(1, prompts); assertEquals(2, grants.size)
            assertTrue(PluginSchoolNetworkConsent.remembered(app, id))
            PluginSchoolNetworkConsent.revoke(app, id)
            assertFalse(PluginSchoolNetworkConsent.remembered(app, id))
            open(true)
            assertEquals(2, prompts); assertEquals(3, grants.size)
        } finally { accounts.clear(id); PluginSchoolNetworkConsent.revoke(app, id) }
    }
}

package com.tyust.course.academic.plugin

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LegacyWebIdentityTest {
    private val login = "https://login.example.test"
    private val api = "https://api.example.test"
    private val profile = "plugin_" + "a".repeat(64)
    private val identity get() = LegacyWebIdentity(profile, setOf(login, api))
    private fun page(mode: String = "browser") = JSONObject().put("serverId", "site").put("path", "/login")
        .put("web", JSONObject().put("mode", mode).put("urlParam", "url").put("navigationOrigins", JSONArray(listOf(api))))

    @Test fun directoryIdentityCannotBeReplacedWithAPathOrAnotherAccount() {
        for (name in listOf("../other", "plugin_../account", "plugin_" + "a".repeat(63), "plugin_" + "z".repeat(64)))
            assertThrows(PluginException::class.java) { LegacyWebIdentity(name, setOf(login)) }
        identity.requireProfile(profile)
        assertThrows(PluginException::class.java) { identity.requireProfile("plugin_" + "b".repeat(64)) }
        assertNotEquals(identity, LegacyWebIdentity(profile, setOf(login)))
    }

    @Test fun nativeCookiesOnlyCrossExactDeclaredHttpsOrigins() {
        assertTrue(identity.accepts("$login/login?next=courses"))
        assertTrue(identity.accepts("$api/course/1"))
        for (url in listOf("http://api.example.test/course/1", "$api:8443/course/1", "https://outside.example.test/", "https://api.example.test.evil.test/"))
            assertFalse(url, identity.accepts(url))
        for (origin in listOf("http://api.example.test", "$api/path", "$api?query=1", "https://user:pass@api.example.test", "https://*.example.test"))
            assertThrows(PluginException::class.java) { LegacyWebIdentity(profile, setOf(origin)) }
    }

    @Test fun legacyBrowserAcceptsDeclaredNavigationButNeverEnablesAHostBridge() {
        val policy = identity.browser(login, page(), JSONObject().put("url", "$api/course/1"))
        assertEquals("$api/course/1", policy.initialUrl)
        assertFalse(policy.bridges(login)); assertFalse(policy.bridges(api))
        assertThrows(PluginException::class.java) { identity.browser(login, page("bridge"), JSONObject()) }
        assertThrows(PluginException::class.java) { identity.browser("https://outside.test", page(), JSONObject()) }
        assertThrows(PluginException::class.java) { identity.browser(login, page(), JSONObject().put("url", "https://outside.test")) }
    }

    @Test fun newComponentInventoryMatchesTheGeneratedSchema() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val schema = JSONObject(app.assets.open("academic-plugin/contract.schema.json").bufferedReader().use { it.readText() })
        val definitions = schema.optJSONObject("\$defs") ?: schema.getJSONObject("definitions")
        val types = PluginJson.objects(definitions.getJSONObject("UiNode").getJSONArray("anyOf"))
            .map { it.getJSONObject("properties").getJSONObject("type").getString("const") }.toSet()
        assertEquals(types, PluginJson.strings(NativePluginContract.components().getJSONArray("types")).toSet())
        assertTrue("ui.components" in NativeCapabilityHost.IMPLEMENTED)
    }

    @Test fun segmentedNavigationRejectsAmbiguousOrMissingSelections() {
        fun result(value: String, options: List<String>) = JSONObject().put("state", JSONObject()).put("effects", JSONArray())
            .put("view", JSONObject().put("id", "tabs").put("type", "segmented").put("value", value)
                .put("options", JSONArray(options.map { JSONObject().put("value", it).put("label", it) })))
        NativePluginContract.validateResult(result("courses", listOf("courses", "tasks")))
        assertThrows(PluginException::class.java) { NativePluginContract.validateResult(result("missing", listOf("courses", "tasks"))) }
        assertThrows(PluginException::class.java) { NativePluginContract.validateResult(result("courses", listOf("courses", "courses"))) }
    }
}

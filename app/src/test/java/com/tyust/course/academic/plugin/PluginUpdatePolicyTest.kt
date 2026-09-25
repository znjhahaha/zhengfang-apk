package com.tyust.course.academic.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PluginUpdatePolicyTest {
    private fun release(version: String) = JSONObject().put("id", "demo.provider").put("apiVersion", 3).put("version", version)
    private fun offers(version: Int) = JSONArray().put(JSONObject().put("name", "campus.card").put("version", version))
    private fun manifest(id: String, service: Int? = null, dependency: Int? = null) = PluginManifest(JSONObject()
        .put("id", id).put("version", "1.0.0").put("permissions", JSONArray()).put("network", JSONArray()).apply {
            service?.let { put("services", offers(it)) }; dependency?.let { put("serviceDependencies", offers(it)) }
        })
    @Test fun selectsHighestCompatibleStableHistoryWithoutInheritingLatestRequirements() {
        val entry = JSONObject().put("id", "demo.provider").put("minAppVersionCode", 1000).put("releases", JSONArray()
            .put(release("1.9.0")).put(release("1.10.0"))
            .put(release("2.0.0").put("minAppVersionCode", 88))
            .put(release("3.0.0-beta.1")).put(release("4.0.0").put("withdrawn", true)))
        assertEquals("1.10.0", PluginUpdatePolicy.select(entry, 87, emptyMap(), emptyList())!!.getString("version"))
    }
    @Test fun rejectsMissingHostCapabilityAndFutureApi() {
        val next = release("2.0.0").put("requires", JSONArray().put(JSONObject().put("name", "pages.open").put("version", 1)))
        assertFalse(PluginUpdatePolicy.compatible(next, 87, emptyMap(), emptyList()))
        assertTrue(PluginUpdatePolicy.compatible(next, 87, mapOf("pages.open" to 1), emptyList()))
        assertFalse(PluginUpdatePolicy.compatible(next.put("apiVersion", 4), 87, mapOf("pages.open" to 1), emptyList()))
    }
    @Test fun checksDependenciesAgainstProjectedVersionAndOtherProviders() {
        val installed = listOf(manifest("demo.provider", 1), manifest("tool.card", dependency = 1))
        val next = release("2.0.0").put("services", offers(2))
        assertFalse(PluginUpdatePolicy.compatible(next, 87, emptyMap(), installed))
        assertTrue(PluginUpdatePolicy.compatible(next, 87, emptyMap(), installed + manifest("backup.provider", 1)))
        next.put("serviceDependencies", offers(1).apply { getJSONObject(0).put("required", true) })
        assertFalse(PluginUpdatePolicy.compatible(next, 87, emptyMap(), installed.take(1)))
    }
    @Test fun newPluginCanSatisfyItsOwnDeclaredService() {
        val next = release("1.0.0").put("services", offers(1)).put("serviceDependencies", offers(1).apply { getJSONObject(0).put("required", true) })
        assertTrue(PluginUpdatePolicy.compatible(next, 87, emptyMap(), emptyList()))
    }
    @Test fun permissionAndOriginChangesRequireApproval() {
        val old = manifest("demo.provider")
        val next = PluginManifest(JSONObject(old.json.toString()).put("permissions", JSONArray().put("academic.read"))
            .put("servers", JSONArray().put(JSONObject().put("id", "card").put("origin", "https://card.example.test"))))
        assertEquals(setOf("academic.read", "服务来源"), PluginUpdatePolicy.expanded(old, next).toSet())
        assertTrue(PluginUpdatePolicy.expanded(next, next).isEmpty())
    }
    @Test fun duplicateHistoryCannotChooseAnAmbiguousPackage() {
        val entry = release("1.0.0").put("releases", JSONArray().put(release("1.0.0")).put(release("1.0.0")))
        assertThrows(PluginException::class.java) { PluginUpdatePolicy.select(entry, 87, emptyMap(), emptyList()) }
    }
    @Test fun leasesProtectSwitchAndClosingTwiceCannotReleaseAnotherTask() {
        val first = PluginVersionLeases.acquire("lease.test"); val second = PluginVersionLeases.acquire("lease.test")
        first.close(); first.close()
        assertNull(PluginVersionLeases.whenIdle("lease.test") { "switched" })
        second.close(); assertEquals("switched", PluginVersionLeases.whenIdle("lease.test") { "switched" })
    }
}

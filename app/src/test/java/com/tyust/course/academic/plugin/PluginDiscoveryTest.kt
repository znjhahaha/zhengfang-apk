package com.tyust.course.academic.plugin

import com.tyust.course.model.SchoolConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PluginDiscoveryTest {
    private fun school(id: String, host: String) = SchoolConfig.fromJson(JSONObject()
        .put("id", id).put("name", "$id 大学").put("protocol", "https").put("domain", host).put("basePath", "/"))
    private fun entry(id: String, name: String, host: String? = null) = JSONObject()
        .put("id", id).put("name", name).put("kind", "configuration").put("description", "课表与成绩查询")
        .apply { if (host != null) put("matches", JSONArray().put(JSONObject().put("host", host).put("pathPrefix", "/"))) }

    @Test fun discoveryShowsOtherSchoolsWhilePrioritizingTheSelectedSchool() {
        val ours = entry("ours", "本校教务", "ours.test")
        val other = entry("other", "其他学校教务", "other.test")
        val target = school("本校", "ours.test")
        assertEquals(listOf(ours, other), PluginDiscovery.filter(listOf(other, ours), target, "", false))
        assertEquals(listOf(ours), PluginDiscovery.filter(listOf(other, ours), target, "", true))
        assertEquals(2, PluginDiscovery.filter(listOf(other, ours), null, "", false).size)
    }

    @Test fun searchesSchoolAliasesDescriptionsAndMultipleWordsWithoutChangingSelection() {
        val adapter = entry("adapter", "教务连接", "campus.test")
        val campus = school("清河", "campus.test")
        val other = entry("other", "教务连接", "other.test")
        val all = listOf(other, adapter)
        assertEquals(listOf(adapter), PluginDiscovery.filter(all, null, "  清河   成绩  ", false, listOf(campus)))
        assertEquals(listOf(adapter), PluginDiscovery.filter(all, null, "CAMPUS.TEST", false))
        assertEquals(2, all.size)
        assertTrue(PluginDiscovery.filter(all, null, "不存在", false).isEmpty())
    }

    @Test fun nativeAndServiceCompatibilityUseDeclaredSchoolScopes() {
        val target = school("campus", "campus.test")
        val universal = JSONObject().put("id", "native").put("kind", "native")
        val scoped = JSONObject().put("id", "service").put("kind", "service")
            .put("service", JSONObject().put("schoolIds", JSONArray().put(target.id)))
        assertTrue(PluginDiscovery.matches(universal, target))
        assertTrue(PluginDiscovery.matches(scoped, target))
        assertFalse(PluginDiscovery.matches(scoped, school("other", "other.test")))
        assertTrue(PluginDiscovery.scope(universal, target).contains("所有学校"))
    }

    @Test fun endpointMatchingPreservesPortAndPathBoundaries() {
        val adapter = entry("adapter", "教务").put("matches", JSONArray().put(JSONObject()
            .put("host", "campus.test").put("port", 8443).put("pathPrefix", "/academic")))
        val target = school("campus", "campus.test:8443").apply { basePath = "/academic" }
        assertTrue(PluginDiscovery.matches(adapter, target))
        assertFalse(PluginDiscovery.matches(adapter, school("campus", "campus.test")))
        target.basePath = "/academic-other"
        assertFalse(PluginDiscovery.matches(adapter, target))
    }
}

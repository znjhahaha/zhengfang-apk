package com.tyust.course.academic.plugin

import com.tyust.course.model.SchoolConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PluginSchoolSearchTest {
    private fun school(id: String = "sample", path: String = "/jw") = SchoolConfig.fromJson(JSONObject()
        .put("id", id).put("name", "示例大学").put("protocol", "https").put("domain", "jw.example.test").put("basePath", path))
    private fun release(version: String = "1.0.0", minApp: Int = 0) = JSONObject().put("version", version)
        .put("apiVersion", 3).put("kind", "configuration").put("minAppVersionCode", minApp)
        .put("school", school().toJson())
    @Test fun selectsCompatibleHistoryButKeepsIncompatibleSchoolAndExcludesTools() {
        val good = JSONObject().put("id", "test.good").put("name", "学校适配").put("releases", JSONArray().put(release()).put(release("2.0.0", 999)))
        val future = JSONObject().put("id", "test.future").put("releases", JSONArray().put(release("1.0.0", 999)))
        val tool = release().put("id", "test.tool").put("kind", "native")
        val result = PluginSchoolSearch.catalog(listOf(good, future, tool), 88, emptyMap(), emptyList())
        assertEquals(2, result.size); assertEquals("1.0.0", result[0].version)
        assertNull(result[0].incompatibleReason); assertTrue(result[1].incompatibleReason!!.contains("999"))
    }
    @Test fun deduplicatesSchoolsWithoutLosingProviderChoiceOrCustomEndpoint() {
        val local = school(path = "/custom")
        val providers = listOf("a", "b").map { SchoolSearchProvider("test.$it", it, "1.0.0", school()) }
        val results = PluginSchoolSearch.merge("示例", listOf(local), providers)
        assertEquals(1, results.size); assertSame(local, results.single().school)
        assertEquals(2, results.single().providers.size); assertTrue(results.single().configured)
        assertSame(local, PluginSchoolSearch.existing(listOf(local), school()))
        assertEquals("/custom", local.basePath)
    }
    @Test fun endpointAliasesMergeAndActiveVersionsRemainSelectableOffline() {
        val active = SchoolSearchProvider("test.school", "适配", "1.0.0", school("alias"), installed = true)
        val result = PluginSchoolSearch.merge("示 例", listOf(school()), listOf(active.copy(incompatibleReason = "future")), listOf(active))
        assertEquals(1, result.size); assertTrue(result.single().providers.single().installed)
        assertNull(result.single().providers.single().incompatibleReason)
    }
    @Test fun emptyResultsOnlyOfferAddAfterExplicitSuccessfulSearch() {
        assertFalse(PluginSchoolSearch.shouldOfferAdd("学校", false, true, emptyList()))
        assertFalse(PluginSchoolSearch.shouldOfferAdd("学校", true, false, emptyList()))
        assertFalse(PluginSchoolSearch.shouldOfferAdd("", true, true, emptyList()))
        assertTrue(PluginSchoolSearch.shouldOfferAdd("学校", true, true, emptyList()))
    }
    @Test fun duplicateClicksAndCancelledConsentCannotAcceptOldRequest() {
        val session = SchoolSearchSession(); val old = session.begin()!!
        assertNull(session.begin()); session.cancel(); assertFalse(session.current(old))
        val next = session.begin()!!; session.finish(old); assertTrue(session.current(next))
        session.cancel(); assertFalse(session.current(next)); assertNotNull(session.begin())
    }
}

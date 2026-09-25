package com.tyust.course.academic.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PluginServiceDirectoryTest {
    private fun provider(version: Int = 1, kind: String = "read") = PluginPackage(PluginManifest(JSONObject("""
        {"id":"provider.card","version":"1.0.0","kind":"native","apiVersion":3,"services":[
        {"name":"campus.card","version":$version,"title":"Card","kind":"$kind",
        "input":{"type":"object","required":["limit"],"properties":{"limit":{"type":"integer","minimum":1,"maximum":50}},"additionalProperties":false},
        "output":{"type":"object","required":["balance"],"properties":{"balance":{"type":"number"}},"additionalProperties":false}}]}
    """)), "", "digest", true)
    private val caller = PluginManifest(JSONObject("""{"id":"tool.card","serviceDependencies":[{"name":"campus.card","version":1}]}"""))
    @Test fun servicesRequireAnExplicitVersionedDependency() {
        assertEquals(1, PluginServiceDirectory.discover(caller, listOf(provider(), provider(2)), "campus.card", 1).size)
        assertThrows(PluginException::class.java) { PluginServiceDirectory.discover(caller, listOf(provider()), "campus.other", 1) }
        assertThrows(PluginException::class.java) { PluginServiceDirectory.discover(caller, listOf(provider()), "campus.card", 2) }
    }
    @Test fun providerSchemasValidateBothSidesAndDoNotExposeCredentials() {
        val service = PluginServiceDirectory.discover(caller, listOf(provider()), "campus.card", 1).single()
        PluginServiceDirectory.validateInput(service, JSONObject().put("limit", 10))
        PluginServiceDirectory.validateOutput(service, JSONObject().put("balance", 25.5))
        assertThrows(PluginException::class.java) { PluginServiceDirectory.validateInput(service, JSONObject().put("limit", 100)) }
        assertThrows(PluginException::class.java) { PluginServiceDirectory.validateOutput(service, JSONObject().put("balance", 5).put("password", "secret")) }
        assertEquals(setOf("providerId", "providerVersion", "digest", "name", "version", "kind", "title"), service.identity().keys().asSequence().toSet())
    }
    @Test fun scheduleImportDeduplicatesDifferentIdsAndWeekOrderWithoutRemovingExistingCourses() {
        val original = JSONObject("""{"id":"server-1","name":"Math","teacher":"A","location":"101","day":1,"startPeriod":1,"endPeriod":2,"weeks":[1,2]}""")
        val duplicate = JSONObject(original.toString()).put("id", "file-7").put("weeks", JSONArray(listOf(2,1,2)))
        val added = JSONObject(original.toString()).put("id", "file-8").put("day", 2)
        val (rows, duplicates) = PluginScheduleImport.merge(JSONArray().put(original), JSONArray().put(duplicate).put(added).put(added))
        assertEquals(2, rows.length()); assertEquals(2, duplicates); assertEquals("server-1", rows.getJSONObject(0).getString("id"))
        val (again, secondDuplicates) = PluginScheduleImport.merge(rows, JSONArray().put(duplicate).put(added))
        assertEquals(2, again.length()); assertEquals(2, secondDuplicates)
    }
    @Test fun confirmedWorkflowValuesMustMatchThePinnedServiceOutput() {
        val service = PluginServiceDirectory.discover(caller, listOf(provider(kind = "write")), "campus.card", 1).single()
        PluginServiceDirectory.validateReceipt(service, JSONObject().put("status", "confirmed").put("value", JSONObject().put("balance", 25)))
        for (receipt in listOf(JSONObject().put("status", "confirmed"), JSONObject().put("status", "confirmed").put("value", JSONObject().put("balance", "invalid"))))
            assertThrows(PluginException::class.java) { PluginServiceDirectory.validateReceipt(service, receipt) }
        PluginServiceDirectory.validateReceipt(service, JSONObject().put("status", "unknown"))
    }
}

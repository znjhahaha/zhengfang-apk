package com.tyust.course.academic.plugin

import com.tyust.course.model.SchoolConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class NativeServiceContractTest {
    private val schema = PluginSchema(JSONObject(File("../academic-plugin-api/assets/academic-plugin/manifest.schema.json").readText()))
    private fun manifest() = PluginManifest(JSONObject(File("src/androidTest/assets/academic-plugin/native-extension-manifest.json").readText()))
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (e: PluginException) { assertEquals(PluginErrorCode.VALIDATION_FAILED, e.code) }
    }
    private fun link(id: String, params: JSONObject = JSONObject()) = JSONObject().put("type", "native").put("operationId", id).put("params", params)
    private fun file(size: Int = 2) = JSONObject().put("operationId", "file").put("status", "success").put("name", "sample.txt")
        .put("mimeType", "text/plain").put("size", size).put("base64", Base64.getEncoder().encodeToString(ByteArray(size)))
    @Test fun v3IsExplicitAndNativeCallbacksCannotAcquireMutationAuthority() {
        manifest().validate(schema)
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("apiVersion", 2) },
            { it.getJSONObject("service").getJSONArray("actions").getJSONObject(0).put("kind", "mutation").put("confirmation", "Confirm") },
            { it.getJSONObject("service").getJSONArray("nativeOperations").getJSONObject(0).put("resultActionId", "missing") },
            { it.getJSONObject("service").getJSONArray("nativeOperations").getJSONObject(1).put("mimeTypes", JSONArray(listOf("*/*"))) }
        )) { val m = manifest(); change(m.json); rejects { m.validate(schema) } }
        PluginManifest(JSONObject(File("src/androidTest/assets/academic-plugin/campus-service-manifest.json").readText())).validate(schema)
    }
    @Test fun nativeRequestsRejectUndeclaredOperationsAndHiddenParameters() {
        val m = manifest()
        ServiceNativePolicy.request(m, link("scan"))
        rejects { ServiceNativePolicy.request(m, link("missing")) }
        rejects { ServiceNativePolicy.request(m, link("scan", JSONObject().put("url", "https://outside.example"))) }
        rejects { ServiceNativePolicy.request(m, link("notify", JSONObject().put("title", "Title").put("body", " "))) }
        rejects { ServiceNativePolicy.request(m, link("notify", JSONObject().put("title", "Title").put("body", true))) }
    }
    @Test fun calendarRequiresRealBoundedDatesAndExplicitTimezone() {
        fun calendar(start: String, end: String) = ServiceNativePolicy.request(manifest(), link("event", JSONObject().put("title", "Example").put("startAt", start).put("endAt", end)))
        calendar("2026-10-01T09:00:00+08:00", "2026-10-01T10:00:00+08:00")
        for (start in listOf("2026-02-30T09:00:00Z", "2026-10-01T24:00:00Z", "2026-10-01T09:00:00", "+999999999-01-01T00:00:00Z")) rejects { calendar(start, "2026-10-01T10:00:00Z") }
        rejects { calendar("2026-10-01T10:00:00Z", "2026-10-01T09:00:00Z") }
        rejects { calendar("2026-10-01T09:00:00Z", "2026-12-01T10:00:00Z") }
    }
    @Test fun selectedFileMustHaveExactTypeSizeAndCanonicalEncoding() {
        val m = manifest()
        for (size in listOf(0, 2, 65536)) ServiceNativePolicy.validateResult(m, "native-result", file(size))
        for (invalid in listOf(file(65537), file().put("size", 1.5), file().put("size", 1), file().put("base64", "AA?="),
            file().put("base64", "AAA"), file().put("mimeType", "application/zip"), file().put("name", true), file().put("uri", "content://secret"))) {
            rejects { ServiceNativePolicy.validateResult(m, "native-result", invalid) }
        }
    }
    @Test fun deniedOrMismatchedCallbacksCannotSupplyDataOrClaimCalendarSaved() {
        val m = manifest()
        ServiceNativePolicy.validateResult(m, "native-result", JSONObject("""{"operationId":"event","status":"opened"}"""))
        for (json in listOf(
            """{"operationId":"scan","status":"cancelled","text":"secret"}""",
            """{"operationId":"scan","status":"success"}""",
            """{"operationId":"scan","status":"success","text":12}""",
            """{"operationId":"notify","status":"opened"}""",
            """{"operationId":"event","status":"success"}""",
            """{"operationId":"file","status":"success"}"""
        )) rejects { ServiceNativePolicy.validateResult(m, "native-result", JSONObject(json)) }
        rejects { ServiceNativePolicy.validateResult(m, "save-note", file()) }
    }
    @Test fun v3TablesHaveStableRowsAndMatchColumnsWhileV2CannotRenderThem() {
        val m = manifest()
        val page = JSONObject("""{"pageId":"report","title":"Report","blocks":[{"id":"table","type":"table","columns":["A","B"],"rows":[{"id":"one","cells":["1","2"]}]}]}""")
        ServicePluginContract.validatePage(m, page)
        m.json.put("apiVersion", 2); rejects { ServicePluginContract.validatePage(m, page) }; m.json.put("apiVersion", 3)
        val rows = page.getJSONArray("blocks").getJSONObject(0).getJSONArray("rows")
        rows.getJSONObject(0).getJSONArray("cells").put("3"); rejects { ServicePluginContract.validatePage(m, page) }
        rows.getJSONObject(0).getJSONArray("cells").remove(2); rows.put(rows.getJSONObject(0)); rejects { ServicePluginContract.validatePage(m, page) }
    }
    @Test fun onlySchoolMatchedOfficialPackagesContributeEntryPlacements() {
        val m = manifest(); val school = SchoolConfig.fromJson(JSONObject(m.school.toString()))
        val official = PluginPackage(m, "", "digest", true)
        assertEquals(1, ServiceEntryPreferences.declared(listOf(official), school, "home").size)
        assertTrue(ServiceEntryPreferences.declared(listOf(official.copy(official = false)), school, "home").isEmpty())
        assertTrue(ServiceEntryPreferences.declared(listOf(official), school, "unknown").isEmpty())
        school.id = "another-school"; assertTrue(ServiceEntryPreferences.declared(listOf(official), school, "home").isEmpty())
    }
}

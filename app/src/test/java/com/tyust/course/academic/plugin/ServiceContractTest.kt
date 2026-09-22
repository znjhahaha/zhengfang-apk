package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicSessionStore
import com.tyust.course.model.SchoolConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ServiceContractTest {
    private val schema = PluginSchema(JSONObject(File("src/main/assets/academic-plugin/manifest.schema.json").readText()))
    private fun manifest() = PluginManifest(JSONObject(File("src/androidTest/assets/academic-plugin/campus-service-manifest.json").readText()))
    private fun rejects(code: PluginErrorCode = PluginErrorCode.VALIDATION_FAILED, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: PluginException) { assertEquals(code, error.code) }
    }
    @Test fun manifestMustSeparateServiceAuthenticationAndDeclaredPages() {
        manifest().validate(schema)
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("apiVersion", 1) }, { it.put("extends", "builtin.zf") },
            { it.getJSONObject("service").getJSONObject("authentication").put("mode", "none") },
            { it.getJSONObject("service").getJSONArray("entries").getJSONObject(0).put("pageId", "undeclared") },
            { it.getJSONObject("service").getJSONArray("actions").getJSONObject(1).remove("confirmation") }
        )) { val m = manifest(); change(m.json); rejects { m.validate(schema) } }
    }
    @Test fun serviceMatchesOnlyTheNamedSchoolOrAnExactHostAlias() {
        val m = manifest()
        val school = SchoolConfig.fromJson(JSONObject(m.school.toString()))
        assertTrue(ServicePluginContract.matches(m, school))
        school.id = "custom-school"
        assertFalse(ServicePluginContract.matches(m, school))
        m.service!!.put("academicHosts", JSONArray(listOf("jw.school.test")))
        school.domain = "jw.school.test"
        assertTrue(ServicePluginContract.matches(m, school))
        school.domain = "jw.school.test.evil"
        assertFalse(ServicePluginContract.matches(m, school))
    }
    @Test fun queryCannotGainMutationAuthorityAndConfirmedWriteCannotReplay() {
        val m = manifest()
        val session = AcademicSessionStore().session("school", "account", "https://campus.example/")
        val request = JSONObject().put("actionId", "register")
        rejects { ServicePluginContract.requireRequest(m, "service.action", request, false) }
        ServicePluginContract.requireRequest(m, "service.action", request, true)
        rejects { PluginOperation(session, m, "service.action", confirmed = true, actionId = "filter").markMutation() }
        val operation = PluginOperation(session, m, "service.action", confirmed = true, actionId = "register")
        operation.markMutation()
        rejects(PluginErrorCode.RESULT_UNKNOWN) { operation.markMutation() }
        assertEquals(PluginErrorCode.RESULT_UNKNOWN, operation.failure(PluginErrorCode.NETWORK_RETRYABLE, "offline").code)
    }
    @Test fun disablingTheServiceRevokesFurtherHostCalls() {
        var enabled = true
        val session = AcademicSessionStore().session("school", "account", "https://campus.example/")
        val op = PluginOperation(session, manifest(), "service.page", scopeStillActive = { enabled })
        op.requireActive(); enabled = false
        rejects(PluginErrorCode.SESSION_EXPIRED) { op.requireActive() }
    }
    @Test fun pageCannotLinkOutsideDeclaredAuthorityAndLayoutKeepsNewModules() {
        val m = manifest()
        val blocks = listOf("profile", "summary", "new-module").map { JSONObject().put("id", it).put("type", "notice").put("text", it) }
        val page = JSONObject().put("pageId", "overview").put("title", "Overview").put("blocks", JSONArray(blocks))
        ServicePluginContract.validatePage(m, page)
        assertEquals(listOf("summary", "profile", "new-module"), ServicePageLayout.ordered(blocks, listOf("removed", "summary", "profile")).map { it.getString("id") })
        page.getJSONArray("blocks").put(blocks.first())
        rejects { ServicePluginContract.validatePage(m, page) }
        rejects { ServicePluginContract.validateLink(m, JSONObject("""{"type":"page","pageId":"missing"}""")) }
        rejects(PluginErrorCode.UNTRUSTED_URL) { ServicePluginContract.validateLink(m, JSONObject("""{"type":"url","url":"https://outside.example/"}""")) }
    }
}

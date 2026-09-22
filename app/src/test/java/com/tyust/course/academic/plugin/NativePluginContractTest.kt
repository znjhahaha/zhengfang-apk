package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicSessionStore
import com.tyust.course.model.SchoolConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = android.app.Application::class)
class NativePluginContractTest {
    private fun manifest() = PluginManifest(JSONObject(File("src/androidTest/assets/academic-plugin/native-components-manifest.json").readText()))
    private val schema = PluginSchema(JSONObject(File("../academic-plugin-api/assets/academic-plugin/manifest.schema.json").readText()))
    private fun rejects(code: PluginErrorCode = PluginErrorCode.VALIDATION_FAILED, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: PluginException) { assertEquals(code, error.code) }
    }
    @Test fun nativePagesNeedNoAcademicSchoolAndContributionsMatchImplementedHandlers() {
        val m = manifest(); m.validate(schema)
        assertTrue(m.isNative); assertFalse(m.isAcademic); assertFalse(m.json.has("school"))
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("apiVersion", 2) },
            { it.getJSONArray("capabilities").put("study.grades") },
            { it.getJSONObject("contributes").getJSONArray("entries").getJSONObject(0).put("pageId", "missing") },
            { it.getJSONObject("contributes").getJSONArray("pages").put(JSONObject().put("id", "overview").put("title", "duplicate")) }
        )) { val changed = manifest(); change(changed.json); rejects { changed.validate(schema) } }
    }
    @Test fun nativeTreeRejectsDuplicateIdsInvalidSelectionAndDeepNesting() {
        fun result(view: JSONObject) = JSONObject().put("state", JSONObject()).put("view", view).put("effects", JSONArray())
        val text = JSONObject().put("id", "text").put("type", "text").put("text", "ok")
        NativePluginContract.validateResult(result(text))
        val duplicate = JSONObject().put("id", "root").put("type", "column").put("children", JSONArray().put(text).put(text))
        rejects { NativePluginContract.validateResult(result(duplicate)) }
        var deep = text
        repeat(18) { deep = JSONObject().put("id", "level$it").put("type", "column").put("children", JSONArray().put(deep)) }
        rejects { NativePluginContract.validateResult(result(deep)) }
        rejects { NativePluginContract.validateResult(result(JSONObject("""{"id":"select","type":"select","value":"unknown","options":[{"label":"A","value":"a"}]}"""))) }
    }
    @Test fun flowsHaveBoundedStepsAndUnknownWritesCannotBeReplayed() {
        val flow = NativeFlow(true); flow.accept("first")
        rejects(PluginErrorCode.RESULT_UNKNOWN) { flow.accept("first") }
        flow.markUnknown()
        assertEquals(PluginErrorCode.RESULT_UNKNOWN, flow.failureCode(PluginErrorCode.CANCELLED))
        assertEquals(PluginErrorCode.RESULT_UNKNOWN, flow.failureCode(PluginErrorCode.TIMEOUT))
        rejects(PluginErrorCode.RESULT_UNKNOWN) { flow.accept("second") }
        val many = NativeFlow(false); repeat(32) { many.accept("effect-$it") }
        rejects(PluginErrorCode.RESOURCE_LIMIT) { many.accept("one-more") }
        val session = AcademicSessionStore().session("school", "account", "https://example.test/")
        rejects { PluginOperation(session, manifest(), "host.effect").markMutation() }
        val operation = PluginOperation(session, manifest(), "host.effect", confirmed = true)
        operation.markMutation(); assertEquals(PluginErrorCode.RESULT_UNKNOWN, operation.failure(PluginErrorCode.TIMEOUT, "timeout").code)
    }
    @Test fun v3StorageDoesNotCrossSchoolAccountOrPackageDigest() {
        val root = kotlin.io.path.createTempDirectory("native-storage").toFile()
        try {
            fun host(school: String = "s", account: String = "a", digest: String = "package-one") = PluginHost(
                PluginOperation(AcademicSessionStore().session(school, account, "https://example.test/"), manifest(), "host.effect", packageDigest = digest), root)
            host().call("storage.set", JSONObject().put("key", "note").put("value", "private-state"))
            assertEquals("private-state", host().call("storage.get", JSONObject().put("key", "note")).getString("data"))
            for (other in listOf(host(school = "other"), host(account = "other"), host(digest = "different-code")))
                assertTrue(other.call("storage.get", JSONObject().put("key", "note")).isNull("data"))
        } finally { root.deleteRecursively() }
    }
    @Test fun schoolMatchingNormalizesHostsAndKeepsPortAndPathBoundaries() {
        fun school(domain: String, path: String = "/campus-a", protocol: String = "https") = SchoolConfig.fromJson(JSONObject().put("id", "school").put("name", "Example").put("domain", domain).put("protocol", protocol).put("basePath", path))
        val rule = JSONObject().put("host", "JW.Example.EDU.").put("pathPrefix", "/campus-a")
        assertTrue(PluginSchoolMatcher.matches(rule, school("jw.example.edu")))
        assertTrue(PluginSchoolMatcher.matches(rule, school("jw.example.edu.", "/campus-a/portal")))
        assertFalse(PluginSchoolMatcher.matches(rule, school("jw.example.edu", "/campus-ab")))
        assertFalse(PluginSchoolMatcher.matches(rule, school("jw.example.edu.evil")))
        assertFalse(PluginSchoolMatcher.matches(rule, school("jw.example.edu:8443")))
        rule.put("port", 8443); assertTrue(PluginSchoolMatcher.matches(rule, school("jw.example.edu:8443")))
        rule.put("host", "学校.example").remove("port")
        assertTrue(PluginSchoolMatcher.matches(rule, school(PluginSchoolMatcher.host("学校.example"))))
        rule.put("pathPrefix", "/campus-a/../b"); assertFalse(PluginSchoolMatcher.matches(rule, school("学校.example")))
    }
}

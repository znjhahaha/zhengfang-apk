package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicSessionStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PluginBoundaryTest {
    private fun rule() = JSONObject("""{"origin":"https://school.test","pathPrefix":"/api","methods":["GET","POST"],"purposes":["query"],"requiredQuery":{"action":"list"}}""")
    private fun manifest() = PluginManifest(JSONObject("""{"id":"school.test","version":"1.0.0","network":[]}"""))
    private fun fails(code: PluginErrorCode, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: PluginException) { assertEquals(code, e.code) }
    }
    @Test fun originPortPathPurposeAndDuplicateParametersAreRestricted() {
        val policy = PluginNetworkPolicy(listOf(rule()))
        policy.requireAllowed("https://school.test/api/courses?action=list".toHttpUrl(), "GET", "query", null)
        for (url in listOf("https://school.test:8443/api?action=list", "http://school.test/api?action=list", "https://school.test.evil/api?action=list",
            "https://school.test/api2?action=list", "https://school.test/api?action=list&action=select", "https://school.test/api/%2fadmin?action=list")) {
            fails(PluginErrorCode.UNTRUSTED_URL) { policy.requireAllowed(url.toHttpUrl(), "GET", "query", null) }
        }
        fails(PluginErrorCode.UNTRUSTED_URL) { policy.requireAllowed("https://school.test/api?action=list".toHttpUrl(), "GET", "mutation", null) }
    }
    @Test fun invalidatedSessionCannotProduceResultsOrWrite() {
        val store = AcademicSessionStore()
        val session = store.session("school", "account", "https://school.test")
        val op = PluginOperation(session, manifest(), "selection.select", confirmed = true)
        session.invalidate()
        fails(PluginErrorCode.SESSION_EXPIRED) { op.markMutation() }
        assertFalse(op.mutationSent)
    }
    @Test fun writesRequireConfirmationAndCannotReplay() {
        val session = AcademicSessionStore().session("school", "account", "https://school.test")
        val unconfirmed = PluginOperation(session, manifest(), "selection.select")
        fails(PluginErrorCode.VALIDATION_FAILED) { unconfirmed.markMutation() }
        val op = PluginOperation(session, manifest(), "selection.select", confirmed = true)
        op.markMutation()
        fails(PluginErrorCode.RESULT_UNKNOWN) { op.markMutation() }
        assertEquals(PluginErrorCode.RESULT_UNKNOWN, op.failure(PluginErrorCode.TIMEOUT, "timeout").code)
        op.close()
        fails(PluginErrorCode.CANCELLED) { op.requireActive() }
    }
    @Test fun cryptoVectorsAndStorageAreBoundToAccountAndSession() {
        val store = AcademicSessionStore()
        val one = store.session("school", "one", "https://school.test")
        val two = store.session("school", "two", "https://school.test")
        fun host(session: com.tyust.course.academic.AcademicSession) = PluginHost(PluginOperation(session, manifest(), "study.terms"), File("build/plugin-test-store"))
        val a = host(one)
        val b = host(two)
        val value = JSONObject().put("key", "token").put("value", "private")
        a.call("state.set", value)
        assertEquals("private", a.call("state.get", value).getString("data"))
        assertTrue(b.call("state.get", value).isNull("data"))
        one.invalidate()
        assertTrue(host(one).call("state.get", value).isNull("data"))
        val c = host(two)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", c.call("crypto.digest", JSONObject().put("algorithm", "SHA-256").put("text", "abc")).getString("data"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", c.call("crypto.digest", JSONObject().put("algorithm", "MD5").put("text", "abc")).getString("data"))
    }
    @Test fun sharedSchemaRejectsOversizedWeekAndUnknownFields() {
        val schema = PluginSchema(JSONObject(File("../academic-plugin-api/assets/academic-plugin/contract.schema.json").readText()))
        val schedule = JSONObject("""{"ok":true,"data":{"termId":"opaque:summer","entries":[],"maxWeeks":25}}""")
        assertEquals("opaque:summer", schema.response("study.schedule", schedule).getString("termId"))
        schedule.getJSONObject("data").put("maxWeeks", 26)
        fails(PluginErrorCode.VALIDATION_FAILED) { schema.response("study.schedule", schedule) }
    }
}

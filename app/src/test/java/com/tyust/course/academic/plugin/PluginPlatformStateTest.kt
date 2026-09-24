package com.tyust.course.academic.plugin

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PluginPlatformStateTest {
    private fun manifest(id: String = "demo.plugin") = PluginManifest(JSONObject("""{"id":"$id","name":"Demo","version":"1.0.0","kind":"native","apiVersion":3,"capabilities":["ui.init","ui.reduce"],"permissions":[],"network":[],"contributes":{"pages":[{"id":"home","title":"Home"},{"id":"detail","title":"Detail","dynamic":true}],"entries":[]}}"""))
    @Test fun pageIdentitySurvivesReorderDisableRestartAndUnregister() {
        var saved = JSONObject(); val registry = PluginPageRegistry(persist = { saved = it })
        registry.synchronize(listOf(manifest()), setOf("demo.plugin"), emptyMap())
        val page = registry.register("demo.plugin", "detail", "room1")
        assertFalse(page.id in registry.pinned())
        registry.customize(listOf(PluginPageRegistry.SETTINGS, page.id, "app.grades"))
        val restored = PluginPageRegistry(saved); restored.synchronize(listOf(manifest()), setOf("demo.plugin"), emptyMap())
        assertEquals(registry.pinned(), restored.pinned())
        restored.synchronize(listOf(manifest()), emptySet(), emptyMap()); assertNull(restored.page(page.id)); assertTrue(PluginPageRegistry.SETTINGS in restored.pinned())
        restored.synchronize(listOf(manifest()), setOf("demo.plugin"), emptyMap()); assertNotNull(restored.page(page.id))
        assertThrows(PluginException::class.java) { restored.unregister("other.plugin", page.id) }
        assertThrows(PluginException::class.java) { restored.customize(listOf("app.schedule")) }
        restored.unregister("demo.plugin", page.id); assertNull(restored.page(page.id)); assertNotNull(restored.page(restored.fallback()))
    }
    @Test fun webBridgeRejectsSubframesOtherOriginsReplayAndOldDocuments() {
        val gate = PluginWebGate("https://forum.example.test"); val token = gate.bind("https://forum.example.test/topics")
        assertThrows(PluginException::class.java) { gate.accept("https://forum.example.test", false, token, "one") }
        assertThrows(PluginException::class.java) { gate.accept("https://forum.example.test.evil", true, token, "one") }
        gate.accept("https://forum.example.test", true, token, "one")
        assertThrows(PluginException::class.java) { gate.accept("https://forum.example.test", true, token, "one") }
        gate.revoke(); assertFalse(gate.current(token)); assertThrows(PluginException::class.java) { gate.accept("https://forum.example.test", true, token, "two") }
    }
    private class MemoryStore : PluginWorkflowStore {
        val records = mutableMapOf<String, String>()
        override fun read(id: String) = records[id]?.let(::JSONObject)
        override fun write(id: String, record: JSONObject) { records[id] = record.toString() }
        override fun list() = records.values.map(::JSONObject)
    }
    private fun identity() = JSONObject().put("callerId", "tool.evaluation").put("callerDigest", "a").put("providerId", "demo.school").put("providerDigest", "b").put("scope", "account1")
    private fun plan() = JSONObject().put("title", "Evaluation").put("summary", "Two questionnaires").put("steps", JSONArray((1..2).map { JSONObject().put("id", "s$it").put("target", "q$it").put("label", "Questionnaire $it").put("input", JSONObject()) }))
    @Test fun unknownOutcomeSurvivesRestartAndIsNeverBlindlyRetried() = runTest {
        val store = MemoryStore(); val journal = PluginWorkflowJournal(store)
        val id = journal.prepare(identity(), JSONObject(), plan()).getString("workflowId"); journal.approve(id)
        var writes = 0
        journal.run(id, { true }) { _, _ -> writes++; assertEquals("sending", store.read(id)!!.getJSONArray("steps").getJSONObject(0).getString("status")); throw java.io.IOException("lost response") }
        val restarted = PluginWorkflowJournal(store)
        restarted.run(id, { true }) { _, _ -> writes++; JSONObject().put("status", "confirmed") }
        assertEquals(1, writes)
        restarted.reconcile(id, { true }) { _, _ -> JSONObject().put("status", "confirmed").put("message", "Found remote receipt") }
        val result = restarted.run(id, { true }) { _, _ -> writes++; JSONObject().put("status", "confirmed").put("message", "ok") }
        assertEquals(2, writes); assertTrue(PluginJson.objects(result.getJSONArray("steps")).all { it.getString("status") == "confirmed" })
    }
    @Test fun concurrentRunAndCancellationKeepCommittedStepAndStopRemainingSteps() = runTest {
        val store = MemoryStore(); val journal = PluginWorkflowJournal(store)
        val id = journal.prepare(identity(), JSONObject(), plan()).getString("workflowId"); journal.approve(id)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var writes = 0
        val first = async { journal.run(id, { true }) { _, _ -> writes++; entered.complete(Unit); release.await(); JSONObject().put("status", "confirmed").put("message", "ok") } }
        entered.await(); journal.cancel(id)
        val repeated = async { journal.run(id, { true }) { _, _ -> writes++; JSONObject().put("status", "confirmed") } }
        release.complete(Unit); first.await(); val final = repeated.await()
        assertEquals(1, writes); assertTrue(final.getBoolean("cancelled")); assertEquals("confirmed", final.getJSONArray("steps").getJSONObject(0).getString("status")); assertEquals("ready", final.getJSONArray("steps").getJSONObject(1).getString("status"))
        assertThrows(PluginException::class.java) { journal.owned(id, "another.tool", "account1") }
        assertThrows(PluginException::class.java) { journal.owned(id, "tool.evaluation", "account2") }
    }
    @Test fun persistedSendingStateRecoversAsUnknownAndRetainsBothPackageVersions() = runTest {
        val store = MemoryStore(); val journal = PluginWorkflowJournal(store)
        val id = journal.prepare(identity(), JSONObject(), plan()).getString("workflowId"); journal.approve(id)
        val crashed = store.read(id)!!; crashed.getJSONArray("steps").getJSONObject(0).put("status", "sending"); store.write(id, crashed)
        var calls = 0
        val result = PluginWorkflowJournal(store).run(id, { true }) { _, _ -> calls++; JSONObject().put("status", "confirmed") }
        assertEquals(0, calls); assertEquals("unknown", result.getJSONArray("steps").getJSONObject(0).getString("status"))
        assertEquals(setOf("a", "b"), journal.references())
        journal.cancel(id); assertEquals(setOf("a", "b"), journal.references())
        journal.reconcile(id, { true }) { _, _ -> JSONObject().put("status", "confirmed") }
        assertTrue(journal.references().isEmpty())
    }
    @Test fun anExplicitNotAppliedResultAllowsRetryButAnUnknownResultDoesNot() = runTest {
        val store = MemoryStore(); val journal = PluginWorkflowJournal(store)
        val id = journal.prepare(identity(), JSONObject(), plan()).getString("workflowId"); journal.approve(id)
        var writes = 0
        journal.run(id, { true }) { _, _ -> writes++; JSONObject().put("status", "unknown") }
        journal.reconcile(id, { true }) { _, _ -> JSONObject().put("status", "unknown") }
        journal.run(id, { true }) { _, _ -> writes++; JSONObject().put("status", "confirmed") }
        assertEquals(1, writes)
        journal.reconcile(id, { true }) { _, _ -> JSONObject().put("status", "notApplied") }
        journal.run(id, { true }) { _, _ -> writes++; JSONObject().put("status", "confirmed") }
        assertEquals(3, writes)
    }
}

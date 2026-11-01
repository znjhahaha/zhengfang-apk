package com.tyust.course.academic.plugin

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PluginSingleTargetWorkflowTest {
    private class Store: PluginWorkflowStore {
        val values=mutableMapOf<String,JSONObject>()
        override fun read(id:String)=values[id]?.let { JSONObject(it.toString()) }
        override fun write(id:String,record:JSONObject) { values[id]=JSONObject(record.toString()) }
        override fun list()=values.values.map { JSONObject(it.toString()) }
    }
    private fun identity(account:String="one")=JSONObject().put("singleTarget",true).put("callerId","cpdaily").put("scope","service-scope").put("providerId","cpdaily")
        .put("providerAccount",account).put("serverId","platform").put("name","cpdaily.submit").put("callerDigest","a").put("providerDigest","b")
    private fun plan(target:String)=JSONObject().put("title","Synthetic").put("summary","Synthetic task")
        .put("steps",JSONArray().put(JSONObject().put("id","one").put("target",target).put("label","Synthetic")))
    @Test fun cancelledUnknownSurvivesRestartAndBlocksNewWorkflowForSameAccountAndTask()=runTest {
        val store=Store(); val journal=PluginWorkflowJournal(store)
        val id=journal.prepare(identity(),JSONObject(),plan("tenant/dorm/instance")).getString("workflowId"); journal.approve(id)
        var writes=0
        journal.run(id,{true}) { _,_ -> writes++; JSONObject().put("status","unknown") }
        journal.cancel(id)
        val restarted=PluginWorkflowJournal(store)
        assertThrows(PluginException::class.java) { restarted.prepare(identity(),JSONObject(),plan("tenant/dorm/instance")) }
        restarted.prepare(identity("another"),JSONObject(),plan("tenant/dorm/instance"))
        restarted.prepare(identity(),JSONObject(),plan("other-tenant/dorm/instance"))
        assertEquals(1,writes)
        restarted.reconcile(id,{true}) { _,_ -> JSONObject().put("status","confirmed") }
        restarted.prepare(identity(),JSONObject(),plan("tenant/dorm/instance"))
    }

    @Test fun prepreparedWorkflowCannotSendAfterCompetingUnknownEvenFromDifferentCaller() = runTest {
        val journal = PluginWorkflowJournal(Store())
        val first = journal.prepare(identity(), null, plan("tenant/task")).getString("workflowId")
        val otherCaller = identity().put("callerId", "other.consumer").put("scope", "other-scope").put("name", "other.submit")
        val second = journal.prepare(otherCaller, null, plan("tenant/task")).getString("workflowId")
        journal.approve(first); journal.approve(second)
        journal.run(first, { true }) { _, _ -> JSONObject().put("status", "unknown") }
        var sends = 0
        try { journal.run(second, { true }) { _, _ -> sends++; JSONObject().put("status", "confirmed") }; fail("must block unknown target") }
        catch (error: PluginException) { assertEquals(PluginErrorCode.RESULT_UNKNOWN, error.code) }
        assertEquals(0, sends)
        assertThrows(PluginException::class.java) { journal.prepare(otherCaller, null, plan("tenant/task")) }
        journal.reconcile(first, { true }) { _, _ -> JSONObject().put("status", "notApplied") }
        journal.run(second, { true }) { _, _ -> sends++; JSONObject().put("status", "confirmed") }
        assertEquals(1, sends)
    }

    @Test fun competingSendingTransitionIsAtomicAcrossWorkflowInstances() = runTest {
        val store = Store()
        val journal = PluginWorkflowJournal(store)
        val first = journal.prepare(identity(), null, plan("tenant/task")).getString("workflowId")
        val second = journal.prepare(identity(), null, plan("tenant/task")).getString("workflowId")
        journal.approve(first); journal.approve(second)
        val sending = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val running = async { journal.run(first, { true }) { _, _ -> sending.complete(Unit); finish.await(); JSONObject().put("status", "unknown") } }
        sending.await()
        var sends = 0
        try { PluginWorkflowJournal(store).run(second, { true }) { _, _ -> sends++; JSONObject().put("status", "confirmed") }; fail("must block sending target") }
        catch (error: PluginException) { assertEquals(PluginErrorCode.RESULT_UNKNOWN, error.code) }
        finally { finish.complete(Unit) }
        running.await()
        assertEquals(0, sends)
    }
}

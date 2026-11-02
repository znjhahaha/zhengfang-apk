package com.tyust.course.academic.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PluginForegroundWorkflowGrantTest {
    private fun declaration() = JSONObject("""{"serverId":"platform","intervalSeconds":60,"mutations":[],"workflow":{"name":"cpdaily.submit","version":1,"inputList":"items","tenantInputKey":"tenantId"}}""")
    private fun input() = JSONObject("""{"tenantId":"t","items":[{"family":"dorm","identity":["s","i"],"materials":{"location":{"latitude":30,"longitude":114,"accuracy":10,"timestamp":1},"profile":{"installationId":"real-device"},"photoId":"fixed-upload","reason":"selected"}}]}""")
    private fun grant(value: JSONObject = input(), active: () -> Boolean = { true }) = PluginForegroundWorkflowGrant(declaration(), value, "cpdaily", "digest", "account", active)
    private fun gps() = JSONObject().put("latitude", 30.1).put("longitude", 114.1).put("accuracy", 8).put("timestamp", System.currentTimeMillis())
    private fun invocation(value: JSONObject = input()) = JSONObject().put("name", "cpdaily.submit").put("version", 1).put("providerId", "cpdaily").put("input",
        JSONObject(value.getJSONArray("items").getJSONObject(0).getJSONObject("materials").toString()).put("tenantId", "t").put("family", "dorm").put("identity", JSONArray("[\"s\",\"i\"]")))
    private fun record(args: JSONObject, id: String = "wf-owned") = JSONObject().put("workflowId", id).put("callerId", "cpdaily").put("providerId", "cpdaily")
        .put("callerDigest", "digest").put("providerDigest", "digest").put("serverId", "platform").put("providerAccount", "account")
        .put("name", "cpdaily.submit").put("version", 1).put("input", args.getJSONObject("input"))
    private fun ready(grant: PluginForegroundWorkflowGrant): JSONObject {
        val location = gps(); grant.rememberLocation(location); grant.rememberProfile(invocation().getJSONObject("input").getJSONObject("profile"))
        return invocation().also { it.getJSONObject("input").put("location", location) }
    }
    @Test fun snapshotsAllMaterialsAndOnlyAcceptsHostLocation() {
        val selected = input(); val g = grant(selected); selected.getJSONArray("items").getJSONObject(0).getJSONObject("materials").put("reason", "changed")
        assertThrows(PluginException::class.java) { g.requirePrepare(invocation()) }
        val args = ready(g); g.requirePrepare(args)
        for (key in listOf("tenantId", "family", "identity", "profile", "photoId", "reason", "location")) {
            val changed = JSONObject(args.toString()); changed.getJSONObject("input").put(key, "forged")
            assertThrows(PluginException::class.java) { g.requirePrepare(changed) }
        }
        for (key in listOf("name", "version", "providerId")) {
            val changed = JSONObject(args.toString()).put(key, if (key == "version") 2 else "foreign")
            assertThrows(PluginException::class.java) { g.requirePrepare(changed) }
        }
    }
    @Test fun onlyOwnedExactRecordsCanBeUsedAndCancellationRevokesLateResults() {
        var active = true; val g = grant(active = { active }); val args = ready(g); g.requirePrepare(args)
        val owned = record(args); assertThrows(PluginException::class.java) { g.requireRecord(owned) }
        g.remember(owned); g.requireRecord(owned)
        for (key in listOf("workflowId", "callerDigest", "providerDigest", "providerId", "serverId", "providerAccount", "input")) {
            assertThrows(PluginException::class.java) { g.requireRecord(JSONObject(owned.toString()).put(key, "foreign")) }
        }
        assertEquals(setOf("wf-owned"), g.handles())
        active = false
        assertThrows(PluginException::class.java) { g.requireRecord(owned) }
        assertThrows(PluginException::class.java) { g.remember(record(args, "late")) }
        assertThrows(PluginException::class.java) { g.rememberLocation(gps()) }
    }
    @Test fun rejectsEmptyOversizedDuplicateAndMalformedSelection() {
        for (count in listOf(0, 21)) {
            val selected = input(); val item = selected.getJSONArray("items").getJSONObject(0)
            selected.put("items", JSONArray((0 until count).map { JSONObject(item.toString()).put("identity", "item-$it") }))
            assertThrows(PluginException::class.java) { grant(selected) }
        }
        val duplicate = input(); duplicate.getJSONArray("items").put(duplicate.getJSONArray("items").getJSONObject(0))
        assertThrows(PluginException::class.java) { grant(duplicate) }
        val reserved = input(); reserved.getJSONArray("items").getJSONObject(0).getJSONObject("materials").put("tenantId", "other")
        assertThrows(PluginException::class.java) { grant(reserved) }
        val malformed = input().put("items", JSONArray().put("not-an-item"))
        assertThrows(PluginException::class.java) { grant(malformed) }
    }
    @Test fun gestureExemptionIsLimitedToWorkflowAndLocation() {
        val g = grant()
        for (name in listOf("workflow.prepare", "workflow.step", "workflow.reconcile", "workflow.list", "workflow.cancel", "device.location", "device.profile")) assertTrue(g.supports(name))
        for (name in listOf("auth.prompt", "auth.service", "device.photo", "device.scan", "files.pick", "network.request")) assertFalse(g.supports(name))
    }
    @Test fun eachPreparedItemNeedsNewHostDeviceEvidence() {
        val g = grant(); val args = ready(g); g.requirePrepare(args); g.remember(record(args))
        assertThrows(PluginException::class.java) { g.requirePrepare(args) }
        g.rememberLocation(args.getJSONObject("input").getJSONObject("location")); g.rememberProfile(args.getJSONObject("input").getJSONObject("profile"))
        g.requirePrepare(args)
    }
}

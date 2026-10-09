package com.tyust.course.academic.plugin

import android.app.Application
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NativePluginRecordsTest {
    // Native SQLite on Windows has a shorter path limit than java.io.File.
    @get:Rule val directory = TemporaryFolder()
    private val app get() = object : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
        override fun getNoBackupFilesDir(): File = directory.root
    }
    private val key = { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
    private fun store(namespace: String = "records-account-a", guard: () -> Unit = {}) = NativePluginRecords(app, namespace, key, guard)
    private fun upsert(records: NativePluginRecords, start: Int, count: Int, status: String = "unknown", ifAbsent: Boolean = false) = records.upsert(JSONObject()
        .put("collection", "attendance").put("ifAbsent", ifAbsent).put("items", JSONArray((start until start + count).map {
            JSONObject().put("key", "course:$it").put("value", JSONObject().put("status", status).put("private", "synthetic-private-content-$it"))
        })))
    private fun query(cursor: String = "", limit: Int = 25) = JSONObject().put("collection", "attendance").put("cursor", cursor).put("limit", limit)

    @Test fun recordsBeyondOldLimitsSurviveReopenAndPageWithoutDuplicates() {
        val original = store()
        upsert(original, 0, 100); upsert(original, 100, 100); upsert(original, 200, 45)
        val first = original.query(query())
        assertEquals(245, first.getInt("total"))
        upsert(original, 245, 1) // New records stay outside this cursor's snapshot.
        val reopened = store()
        var page = first
        val ids = mutableSetOf<String>()
        do {
            for (row in PluginJson.objects(page.getJSONArray("items"))) assertTrue(ids.add(row.getString("key")))
            val cursor = page.getString("nextCursor")
            if (cursor.isEmpty()) break
            page = reopened.query(query(cursor))
            assertEquals(245, page.getInt("total"))
        } while (true)
        assertEquals(245, ids.size)
        assertFalse(ids.contains("course:245"))
        assertEquals(246, reopened.query(query()).getInt("total"))
        val bytes = File(app.noBackupFilesDir, "native-plugin-records").walkTopDown().filter { it.isFile }.flatMap { it.readBytes().toList() }.toList().toByteArray()
        assertFalse(String(bytes).contains("synthetic-private-content"))
    }

    @Test fun migrationIsIdempotentAndAnAccountCannotUseAnotherAccountsCursor() {
        val records = store()
        upsert(records, 0, 60, "confirmed")
        assertEquals(0, upsert(records, 0, 60, "waiting_input", true).getInt("written"))
        val exact = records.query(JSONObject().put("collection", "attendance").put("keys", JSONArray().put("course:44")))
        assertEquals("confirmed", exact.getJSONArray("items").getJSONObject(0).getJSONObject("value").getString("status"))
        val other = store("records-account-b")
        assertEquals(0, other.query(query()).getInt("total"))
        assertThrows(PluginException::class.java) { other.query(query(records.query(query()).getString("nextCursor"))) }
    }

    @Test fun failedBatchRollsBackAndRevokedWritesCannotRecreateAccountData() {
        var active = true
        val records = store { if (!active) throw PluginException(PluginErrorCode.STALE_CONTEXT, "synthetic revoked") }
        val input = JSONObject().put("collection", "attendance").put("items", JSONArray()
            .put(JSONObject().put("key", "valid").put("value", true))
            .put(JSONObject().put("key", "oversized").put("value", "x".repeat(9000))))
        assertThrows(PluginException::class.java) { records.upsert(input) }
        assertEquals(0, records.query(query()).getInt("total"))
        upsert(records, 1, 1); active = false; NativePluginRecords.clear(app, "records-account-a")
        assertThrows(PluginException::class.java) { upsert(records, 2, 1) }
        assertEquals(0, store().query(query()).getInt("total"))
    }
}

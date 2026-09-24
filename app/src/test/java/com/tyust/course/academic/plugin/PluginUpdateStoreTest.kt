package com.tyust.course.academic.plugin

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class PluginUpdateStoreTest {
    private lateinit var app: Application
    private lateinit var store: PluginPackageStore
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        AcademicProviderRegistry.initialize(app)
        store = AcademicProviderRegistry.packages()
    }
    private fun archive(version: String): ByteArray {
        val manifest = JSONObject("""{"id":"test.updates","name":"Synthetic","version":"$version","apiVersion":3,"kind":"configuration","extends":"builtin.zf","capabilities":[],"network":[],"school":{"id":"synthetic","name":"Synthetic","domain":"school.test","protocol":"https","basePath":"/"},"files":{}}""")
        return ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
        } }.toByteArray()
    }
    @Test fun stagingDoesNotChangeActiveAndAnActiveLeaseBlocksActivationAndRollback() = runBlocking {
        val first = store.install(archive("1.0.0"), true)
        val next = store.install(archive("2.0.0"), true, true)
        assertEquals(first.digest, store.active("test.updates")!!.digest)
        val lease = PluginVersionLeases.acquire("test.updates")
        try { assertNull(store.activateStaged("test.updates")) } finally { lease.close() }
        assertEquals(next.digest, store.activateStaged("test.updates")!!.digest)
        val busy = PluginVersionLeases.acquire("test.updates")
        try { assertThrows(PluginException::class.java) { store.rollback("test.updates") } } finally { busy.close() }
        assertEquals(first.digest, store.rollback("test.updates").digest)
    }
    @Test fun corruptedStagedPackageLeavesVerifiedActivePackageIntact() = runBlocking {
        val first = store.install(archive("1.0.0"), true)
        val staged = store.install(archive("2.0.0"), true, true)
        File(app.filesDir, "academic-plugins/${staged.digest}.zfplugin").appendText("tampered")
        assertThrows(PluginException::class.java) { store.activateStaged("test.updates") }
        assertEquals(first.digest, store.active("test.updates")!!.digest)
        assertTrue(store.staged().isEmpty())
    }
    @Test fun approvalIsBoundToTheDisplayedDigest() = runBlocking {
        store.install(archive("1.0.0"), true)
        val shown = store.install(archive("2.0.0"), true, true)
        store.install(archive("3.0.0"), true, true)
        assertThrows(PluginException::class.java) { store.activateStaged("test.updates", true, shown.digest) }
        assertEquals("1.0.0", store.active("test.updates")!!.manifest.version)
    }
    @Test fun unfinishedWorkflowBlocksSwitchAndCancellationReleasesOnlyResolvedVersions() = runBlocking {
        val first = store.install(archive("1.0.0"), true)
        val journal = PluginWorkflowJournal(PluginWorkflowFiles(app))
        val identity = JSONObject().put("callerId", "test.updates").put("callerDigest", first.digest)
            .put("providerId", "test.updates").put("providerDigest", first.digest).put("scope", "synthetic")
        val plan = JSONObject().put("title", "Synthetic").put("summary", "One mock operation").put("steps", JSONArray()
            .put(JSONObject().put("id", "step").put("target", "mock").put("label", "Mock").put("input", JSONObject())))
        val id = journal.prepare(identity, JSONObject(), plan).getString("workflowId")
        store.install(archive("2.0.0"), true, true)
        assertNull(store.activateStaged("test.updates"))
        journal.approve(id); journal.run(id, { true }) { _, _ -> JSONObject().put("status", "unknown") }
        journal.cancel(id); assertNull(store.activateStaged("test.updates"))
        journal.reconcile(id, { true }) { _, _ -> JSONObject().put("status", "confirmed") }
        assertEquals("2.0.0", store.activateStaged("test.updates")!!.manifest.version)
        assertEquals(first.digest, store.readDigest(first.digest).digest)
    }
    @Test fun directUpdateAlsoStagesWhileAPluginIsBusy() = runBlocking {
        store.install(archive("1.0.0"), true)
        val lease = PluginVersionLeases.acquire("test.updates")
        try { store.install(archive("2.0.0"), true) } finally { lease.close() }
        assertEquals("1.0.0", store.active("test.updates")!!.manifest.version)
        assertEquals("2.0.0", store.staged().single().manifest.version)
    }
}

package com.tyust.course.academic.plugin

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises real disk/encrypted setting transitions with synthetic downloads; no WebView is started. */
@RunWith(RobolectricTestRunner::class)
// API 30+ AtomicFile relies on POSIX rename-over-existing, unavailable on the Windows test host.
@Config(sdk = [28], application = Application::class)
class PluginUserscriptStoreTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private var version = "1.0.0"
    private var grant = "GM_getValue"
    private var offline = false
    private var active = true
    private val namespace = "synthetic-script-account"
    private fun source() = """
        // ==UserScript==
        // @name Synthetic original
        // @version $version
        // @grant $grant
        // @author Synthetic author
        // @license MIT
        // @updateURL https://updates.test/scripts/1/original.meta.js
        // @downloadURL https://updates.test/scripts/1/original.user.js
        // ==/UserScript==
        void 0;
    """.trimIndent()
    private fun store(syntax: suspend (String) -> Unit = {}) = PluginUserscriptStore(app,
        PluginUserscriptPolicy(JSONObject("""{"id":"original","serverId":"site","sourcePrefix":"https://updates.test/scripts/1/",
            "updateUrl":"https://updates.test/scripts/1/original.meta.js","downloadUrl":"https://updates.test/scripts/1/original.user.js",
            "matches":[{"host":"course.test","pathPrefix":"/course"}],"connect":["course.test"],
            "adapter":{"settings":[{"key":"video","selector":"#video","type":"boolean"}]}}"""),
            PluginManifest(JSONObject("""{"id":"test.script","version":"1.0.0","apiVersion":3,"kind":"native","network":[{"origin":"https://course.test","pathPrefix":"/","methods":["GET"],"purposes":["query"]}]}"""))),
        namespace, downloadOverride = { if (offline) throw IOException("synthetic offline") else source() },
        syntaxOverride = syntax, keyProvider = { SecretKeySpec(ByteArray(32) { 3 }, "AES") }, active = { active })

    @Test fun updatesStayPendingAndRollbackRestoresSettingsWithoutImmediatelyReinstallingTheSameVersion() = runBlocking {
        val store = store()
        store.update(true)
        val running = store.activate()
        store.putSetting("video", true)
        version = "2.0.0"; store.update()
        assertEquals("1.0.0", store.publicStatus().getString("version"))
        assertEquals("2.0.0", store.publicStatus().getString("pendingVersion"))
        assertEquals("1.0.0", running.first.getString("version"))
        assertTrue(running.second.contains("@version 1.0.0"))
        store.activate(); store.putSetting("video", false)
        store.rollback()
        assertEquals("1.0.0", store.publicStatus().getString("version"))
        assertTrue(store.settings().getBoolean("video"))
        store.update()
        assertEquals("", store.publicStatus().getString("pendingVersion"))
        store.update(retryRolledBack = true)
        assertEquals("2.0.0", store.publicStatus().getString("pendingVersion"))
    }

    @Test fun networkOrUnsupportedCapabilityFailuresPreserveTheInstalledOriginal() = runBlocking {
        val store = store(); store.update(true); val original = store.activate()
        offline = true; store.update()
        assertEquals(original.second, store.activate().second)
        offline = false; version = "2.0.0"; grant = "GM_download"; store.update()
        assertEquals("", store.publicStatus().getString("pendingVersion"))
        assertEquals("1.0.0", store.publicStatus().getString("version"))
        assertTrue(store.publicStatus().getString("updateMessage").contains("尚未支持"))
    }

    @Test fun damagedStagedSourceCannotReplaceTheCurrentVersion() = runBlocking {
        val store = store(); store.update(true); store.activate()
        version = "2.0.0"; store.update()
        val digest = store.metadata().getJSONObject("pending").getString("digest")
        File(app.noBackupFilesDir, "plugin-userscripts/${PluginStorageScope.hash(namespace)}/${PluginStorageScope.hash("original")}/$digest.user.js").writeText("corrupted")
        val failure = runCatching { store.activate() }.exceptionOrNull()
        assertEquals(PluginErrorCode.BAD_SIGNATURE, (failure as PluginException).code)
        assertEquals("1.0.0", store.publicStatus().getString("version"))
    }

    @Test fun accountRevocationWhileDownloadingCannotRecreateItsDeletedFiles() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val store = store { entered.complete(Unit); release.await() }
        val work = async { runCatching { store.update(true) } }
        entered.await(); active = false; PluginUserscriptStore.clear(app, namespace); release.complete(Unit)
        assertTrue(work.await().isFailure)
        assertFalse(File(app.noBackupFilesDir, "plugin-userscripts/${PluginStorageScope.hash(namespace)}").exists())
    }

    @Test fun answerKeyPersistsWithoutStartingAScriptAndNeverAppearsInPublicStatusOrLogs() {
        val key = "synthetic-answer-key-with-private-content"
        store().putSetting("shenchanranToken", key)
        store().putSetting("tkLeft", 23)
        val restored = store()
        assertEquals(key, restored.settings().getString("shenchanranToken"))
        assertTrue(restored.publicStatus().getBoolean("questionBankConfigured"))
        assertFalse(restored.publicStatus().toString().contains(key))
        assertFalse(restored.redact("key=$key token=private-value").contains(key))
        assertFalse(restored.redact("key=$key token=private-value").contains("private-value"))
        val saved = File(app.noBackupFilesDir, "native-plugin-vault").walkTopDown().filter { it.isFile }.toList()
        assertTrue(saved.all { !it.readText().contains(key) })
    }
    @Test fun upstreamNumericBooleansAreRestoredWithoutChangingTheirMeaning() {
        val setting = JSONObject().put("type", "boolean")
        for (value in listOf(true, 1, 1.0, "1", "true")) assertEquals(true, PluginUserscriptStore.normalizedSetting(setting, value))
        for (value in listOf(false, 0, 0.0, "0", "false")) assertEquals(false, PluginUserscriptStore.normalizedSetting(setting, value))
        for (value in listOf(2, "yes", JSONObject.NULL)) assertNull(PluginUserscriptStore.normalizedSetting(setting, value))
        store().putSetting("video", 1)
        assertTrue(store().publicStatus().getJSONObject("settings").getBoolean("video"))
        store().putSetting("video", 0)
        assertFalse(store().publicStatus().getJSONObject("settings").getBoolean("video"))
    }
}

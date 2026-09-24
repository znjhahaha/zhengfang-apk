package com.tyust.course.academic.plugin

import android.app.Application
import android.content.Context
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionKey
import com.tyust.course.manager.UserManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class PluginLifecycleTest {
    private lateinit var app: Application
    @Before fun setup() { app = RuntimeEnvironment.getApplication(); UserManager.getInstance().init(app); AcademicProviderRegistry.initialize(app) }
    private fun pkg(id: String = "test.lifecycle", official: Boolean = true) = PluginPackage(PluginManifest(JSONObject("""
        {"id":"$id","name":"Synthetic","kind":"native","version":"1.0.0","apiVersion":3,"permissions":[],"network":[],"capabilities":[],
         "servers":[{"id":"card","origin":"https://card.example.test","authentication":"password","title":"Card"},{"id":"forum","origin":"https://forum.example.test","authentication":"web","title":"Forum"}],
         "contributes":{"pages":[],"entries":[],"settings":[{"id":"theme","title":"Theme","type":"string","scope":"plugin","default":"light"},{"id":"limit","title":"Limit","type":"number","scope":"service","serverId":"card","default":3}]}}
    """)), "", "a".repeat(64), official)
    @Test fun serviceSwitchAwayAndBackRevokesOldSessionEvenWhenAccountIdMatches() {
        val accounts = PluginServiceAccounts(app); val pkg = pkg()
        val firstId = accounts.select(pkg, "card", "Synthetic A")
        val first = accounts.session(pkg, "card")
        assertTrue(accounts.current(pkg, first))
        accounts.select(pkg, "card", "Synthetic B"); accounts.select(pkg, "card", "Synthetic A")
        assertEquals(firstId, accounts.selected(pkg.manifest.id, "card")); assertFalse(accounts.current(pkg, first))
        assertTrue(accounts.current(pkg, accounts.session(pkg, "card")))
    }
    @Test fun uninstallAndAnonymousAccountRemovalCannotResaveOldCookies() {
        val accounts = PluginServiceAccounts(app); val pkg = pkg(); val old = accounts.session(pkg, "card")
        val profile = accounts.profile(pkg, "card")
        accounts.clearPlugin(pkg)
        assertFalse(accounts.current(pkg, old))
        PluginSessionCookies.save(app, pkg, old)
        assertFalse(File(app.noBackupFilesDir, "native-plugin-vault").listFiles().orEmpty().any { it.isDirectory })
        assertTrue(app.getSharedPreferences("plugin-service-accounts", Context.MODE_PRIVATE).getStringSet("retiredProfiles", emptySet())!!.contains(profile))
        assertNotEquals(profile, accounts.profile(pkg, "card"))
    }
    @Test fun storageAndProfilesAreIsolatedByPluginServerAccountOriginAndTrust() {
        val accounts = PluginServiceAccounts(app); val first = pkg(); val s = accounts.session(first, "card")
        val namespace = PluginStorageScope.session(s, first.manifest.id, false)
        assertNotEquals(namespace, PluginStorageScope.session(accounts.session(first, "forum"), first.manifest.id, false))
        assertNotEquals(namespace, PluginStorageScope.session(s, "other.plugin", false))
        assertNotEquals(namespace, PluginStorageScope.session(s, first.manifest.id, true))
        val changed = AcademicSession(s.key, "https://new.example.test")
        assertNotEquals(namespace, PluginStorageScope.session(changed, first.manifest.id, false))
        assertNotEquals(accounts.profile(first, "card"), accounts.profile(pkg(official = false), "card"))
    }
    @Test fun serviceSettingsFollowServiceAccountAndPluginSettingsSurviveSwitchAndVersion() {
        val pkg = pkg(); val accounts = PluginServiceAccounts(app)
        val declarations = PluginJson.objects(pkg.manifest.contributes.getJSONArray("settings"))
        fun keys() = declarations.associate { it.getString("id") to PluginSettings.key(app, pkg, it) }
        val first = keys()
        PluginSettings.write(app, pkg, first, mapOf("theme" to "dark", "limit" to "7"))
        accounts.select(pkg, "card", "Synthetic B")
        assertEquals("dark", PluginSettings.values(app, pkg).getString("theme"))
        assertEquals(3, PluginSettings.values(app, pkg).getInt("limit"))
        assertThrows(PluginException::class.java) { PluginSettings.write(app, pkg, first, mapOf("limit" to "9")) }
        assertThrows(PluginException::class.java) { PluginSettings.write(app, pkg, keys(), mapOf("limit" to "NaN")) }
        val updated = pkg.copy(digest = "b".repeat(64))
        assertEquals("dark", PluginSettings.values(app, updated).getString("theme"))
    }
    @Test fun nativePersistentStorageMigratesDigestNamespaceAndSurvivesUpdates() {
        val pkg = pkg(); val session = AcademicSession(AcademicSessionKey("plugin:test.lifecycle", "default"), "https://invalid.example/")
        val dir = File(app.filesDir, "test-storage").apply { mkdirs() }
        val old = PluginStorageScope.hash(PluginStorageScope.base(session.key.schoolId, session.key.accountKey, pkg.manifest.id, false) + "\u0000" + pkg.digest)
        File(dir, "$old.json").writeText("{\"counter\":9}")
        fun host(digest: String) = PluginHost(PluginOperation(session, pkg.manifest, "host.effect", packageDigest = digest), dir)
        assertEquals(9, host(pkg.digest).call("storage.get", JSONObject().put("key", "counter")).getInt("data"))
        host(pkg.digest).call("storage.set", JSONObject().put("key", "counter").put("value", 10))
        assertEquals(10, host("b".repeat(64)).call("storage.get", JSONObject().put("key", "counter")).getInt("data"))
    }
    @Test fun serviceReadPrepareReconcileAndCancelCannotAcquireWriteAuthority() {
        val pkg = pkg().copy(manifest = PluginManifest(JSONObject(pkg().manifest.json.toString()).put("services", JSONArray()
            .put(JSONObject().put("name", "campus.eval").put("kind", "write").put("version", 1)))))
        val session = AcademicSession(AcademicSessionKey("plugin:test", "default"), "https://invalid.example/")
        for (method in listOf("services.invoke", "workflow.prepare", "workflow.reconcile", "workflow.cancel")) {
            val op = PluginOperation(session, pkg.manifest, method, confirmed = true, actionId = "campus.eval")
            assertThrows(PluginException::class.java) { op.markMutation() }
        }
        val write = PluginOperation(session, pkg.manifest, "workflow.step", confirmed = true, actionId = "campus.eval")
        write.markMutation(); assertThrows(PluginException::class.java) { write.markMutation() }
    }
    @Test fun serviceDigestStorageMigratesOnlyForItsDeclaredOrigin() {
        val pkg = pkg(); val accounts = PluginServiceAccounts(app)
        val session = accounts.session(pkg, "card")
        val dir = File(app.filesDir, "migration-origin").apply { mkdirs() }
        val old = PluginStorageScope.hash(PluginStorageScope.base(session.key.schoolId, session.key.accountKey, pkg.manifest.id, false) + "\u0000" + pkg.digest)
        File(dir, "$old.json").writeText("{\"counter\":5}")
        val foreign = AcademicSession(session.key, "https://new.example.test")
        val foreignHost = PluginHost(PluginOperation(foreign, pkg.manifest, "host.effect", packageDigest = pkg.digest), dir)
        assertEquals(JSONObject.NULL, foreignHost.call("storage.get", JSONObject().put("key", "counter")).get("data"))
        val host = PluginHost(PluginOperation(session, pkg.manifest, "host.effect", packageDigest = pkg.digest), dir)
        assertEquals(5, host.call("storage.get", JSONObject().put("key", "counter")).getInt("data"))
    }
    @Test fun explicitCleanupPreservesOtherPluginsAndNavigation() {
        val pkg = pkg(); val session = AcademicSession(AcademicSessionKey("plugin:test.lifecycle", "default"), "https://invalid.example/")
        val root = File(app.filesDir, "academic-plugin-storage")
        val host = PluginHost(PluginOperation(session, pkg.manifest, "host.effect", packageDigest = pkg.digest), root)
        host.call("storage.set", JSONObject().put("key", "counter").put("value", 8))
        val other = File(root, "${"b".repeat(64)}.json").apply { writeText("{}") }
        val prefs = app.getSharedPreferences("plugin-settings", Context.MODE_PRIVATE)
        prefs.edit().putString("test.other/theme/plugin", "dark").commit()
        PluginLocalData.clear(app, pkg)
        assertTrue(other.exists()); assertEquals("dark", prefs.getString("test.other/theme/plugin", null))
        assertFalse(File(root, PluginStorageScope.hash(PluginStorageScope.session(session, pkg.manifest.id, false)) + ".json").exists())
    }
}

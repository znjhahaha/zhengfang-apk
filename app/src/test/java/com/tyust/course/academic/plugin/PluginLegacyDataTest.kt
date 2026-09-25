package com.tyust.course.academic.plugin

import android.app.Application
import com.tyust.course.model.SchoolConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class PluginLegacyDataTest {
    private lateinit var app: Application
    private val legacy = PluginPackage(PluginManifest(JSONObject("""{"id":"legacy.native","apiVersion":3,"version":"1.0.0","kind":"native","contributes":{"pages":[{"id":"main","title":"Main"}],"entries":[]}}""")), "", "a".repeat(64), true)
    private val school = SchoolConfig("synthetic", "Synthetic", "school.test", "https")
    private val fixtureKey = { SecretKeySpec(ByteArray(16) { 7 }, "AES") }
    @Before fun setup() { app = RuntimeEnvironment.getApplication(); AcademicProviderRegistry.initialize(app) }
    @Test fun oldNativeAccountScopeAndFileHandlesSurviveAdoptingPlatformMetadata() {
        val first = PluginLegacyData.session(app, legacy, school, "account-a")
        assertEquals(PluginSchoolMatcher.key(school) + ":account-a", first.key.accountKey)
        val oldNamespace = PluginLegacyData.namespaces(app, legacy, first).single()
        val old = NativePluginFiles(app, oldNamespace)
        val handle = old.create("sample.txt", "text/plain", "synthetic file".toByteArray()).getString("handle")
        val updated = legacy.copy(manifest = PluginManifest(JSONObject(legacy.manifest.json.toString()).put("category", "fun")), digest = "b".repeat(64))
        val current = PluginLegacyData.session(app, updated, school, "account-a")
        assertEquals(first.key, current.key)
        val scopes = PluginLegacyData.namespaces(app, updated, current)
        assertTrue(oldNamespace in scopes)
        val migrated = NativePluginFiles(app, PluginStorageScope.session(current, updated.manifest.id, false), scopes)
        assertEquals("synthetic file", migrated.file(handle).readText())
        val other = PluginLegacyData.session(app, updated, school, "account-b")
        val otherFiles = NativePluginFiles(app, PluginStorageScope.session(other, updated.manifest.id, false), PluginLegacyData.namespaces(app, updated, other))
        assertThrows(PluginException::class.java) { otherFiles.file(handle) }
        migrated.remove(handle)
        assertThrows(PluginException::class.java) { migrated.file(handle) }
        assertThrows(PluginException::class.java) { old.file(handle) }
    }
    @Test fun oldVaultIsReencryptedForTheSameAccountAndRemovedHandlesNeverReappear() {
        val session = PluginLegacyData.session(app, legacy, school, "account-a")
        val namespaces = PluginLegacyData.namespaces(app, legacy, session)
        val old = NativePluginVault(app, namespaces.single(), keyProvider = fixtureKey)
        val handle = old.saveCredential("login", JSONObject().put("password", "synthetic-only"), true)
        val current = NativePluginVault(app, PluginStorageScope.session(session, legacy.manifest.id, false), namespaces, keyProvider = fixtureKey)
        assertEquals(handle, current.get("credential-key:login")!!.getString("handle"))
        assertEquals("synthetic-only", current.get("credential:$handle")!!.getString("password"))
        val other = NativePluginVault(app, "another-account", keyProvider = fixtureKey)
        assertNull(other.get("credential:$handle"))
        current.remove("credential:$handle")
        assertNull(current.get("credential:$handle")); assertNull(old.get("credential:$handle"))
        val revoked = NativePluginVault(app, "revoked", namespaces, keyProvider = fixtureKey) { throw PluginException(PluginErrorCode.STALE_CONTEXT, "changed") }
        assertThrows(PluginException::class.java) { revoked.get("credential-key:login") }
    }
}

package com.tyust.course.academic.plugin

import android.content.Context
import android.util.Base64
import com.tyust.course.academic.AcademicException
import com.tyust.course.academic.AcademicStatus
import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

abstract class PluginSchoolBindingChecks {
    protected abstract fun testContext(): Context
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private lateinit var context: Context
    private val fixtureIds = mutableSetOf<String>()
    private var previousKey: String? = null
    private var previousCatalog: ByteArray? = null
    private var previousBindings: Map<String, *> = emptyMap<String, Any>()
    private val store get() = AcademicProviderRegistry.packages()

    @Before fun prepareIsolatedRegistry() {
        context = testContext()
        val preferences = context.getSharedPreferences("plugin-local-catalog", Context.MODE_PRIVATE)
        previousKey = preferences.getString("key", null)
        val catalog = File(context.filesDir, "academic-plugins/catalog-api3.json")
        previousCatalog = catalog.takeIf { it.exists() }?.readBytes()
        val bindings = context.getSharedPreferences("plugin-school-bindings", Context.MODE_PRIVATE)
        previousBindings = bindings.all.toMap()
        bindings.edit().clear().commit()
        AcademicProviderRegistry.initialize(context)
        val public = JSONObject().put("keyId", "binding-test").put("spki", Base64.encodeToString(key.public.encoded, Base64.NO_WRAP))
        AcademicProviderRegistry.configureLocalCatalog("http://127.0.0.1:8799/catalog.json", public.toString())
    }
    @After fun restoreRegistry() {
        if (!::context.isInitialized) return
        fixtureIds.forEach { store.deactivate(it) }
        context.getSharedPreferences("plugin-local-catalog", Context.MODE_PRIVATE).edit().apply {
            if (previousKey == null) remove("key") else putString("key", previousKey)
        }.commit()
        context.getSharedPreferences("plugin-school-bindings", Context.MODE_PRIVATE).edit().clear().apply {
            previousBindings.forEach { (key, value) -> when (value) { is Boolean -> putBoolean(key, value); is String -> putString(key, value) } }
        }.commit()
        val catalog = File(context.filesDir, "academic-plugins/catalog-api3.json")
        previousCatalog?.let { catalog.parentFile!!.mkdirs(); catalog.writeBytes(it) } ?: catalog.delete()
        AcademicProviderRegistry.initialize(context)
    }
    private fun school(path: String = "/campus-a", domain: String = "jw.example.test") = SchoolConfig.fromJson(
        JSONObject().put("id", "sample-$path").put("name", "虚构学校").put("protocol", "https").put("domain", domain).put("basePath", path))
    private fun signature(payload: JSONObject) = JSONObject().put("keyId", "binding-test").put("signature", Base64.encodeToString(
        Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(PluginJson.canonical(payload).toByteArray()); sign() }, Base64.NO_WRAP))
    private suspend fun install(id: String, path: String = "/campus-a", version: String = "1.0.0", official: Boolean = true): PluginPackage {
        val manifest = JSONObject().put("id", id).put("name", "虚构适配").put("version", version).put("apiVersion", 2)
            .put("kind", "configuration").put("extends", "builtin.zf").put("capabilities", JSONArray())
            .put("network", JSONArray()).put("files", JSONObject()).put("school", JSONObject()
                .put("id", "sample").put("name", "虚构学校").put("domain", "jw.example.test").put("protocol", "https").put("basePath", path))
        val bytes = ByteArrayOutputStream().also { buffer -> ZipOutputStream(buffer).use { zip ->
            val files = linkedMapOf("manifest.json" to manifest)
            if (official) files["signature.json"] = signature(manifest)
            files.forEach { (name, json) -> zip.putNextEntry(ZipEntry(name)); zip.write(json.toString().toByteArray()); zip.closeEntry() }
        } }.toByteArray()
        return store.install(bytes, allowDevelopment = !official).also {
            fixtureIds += id
            AcademicProviderRegistry.reload()
        }
    }
    private fun aliases(id: String): JSONObject {
        val payload = JSONObject().put("apiVersion", 3).put("entries", JSONArray().put(JSONObject().put("id", id)
            .put("aliases", JSONArray().put(JSONObject().put("host", "alias.example.test").put("pathPrefix", "/portal")))))
        return signature(payload).put("payload", payload)
    }

    @Test fun aliasesRequireSignedCatalogAndDoNotExtendDevelopmentPackages() = runBlocking {
        val otherCurve = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        val payload = JSONObject().put("curve", "unsupported")
        val otherSignature = JSONObject().put("keyId", "other-curve").put("signature", Base64.encodeToString(
            Signature.getInstance("SHA256withECDSA").run { initSign(otherCurve.private); update(PluginJson.canonical(payload).toByteArray()); sign() }, Base64.NO_WRAP))
        try { PluginPackageVerifier.verifySignature(payload, otherSignature, mapOf("other-curve" to otherCurve.public)); fail("A non-P-256 key was accepted") }
        catch (error: PluginException) { assertEquals(PluginErrorCode.BAD_SIGNATURE, error.code) }
        val pkg = install("test.binding.official")
        val alias = school("/portal", "ALIAS.EXAMPLE.TEST.")
        assertEquals("org.zf.protocol.zf", AcademicProviderRegistry.resolve(alias)?.manifest?.id)
        val catalog = aliases(pkg.manifest.id)
        store.rememberCatalog(catalog)
        assertEquals(pkg.digest, AcademicProviderRegistry.resolve(alias)?.digest)
        assertEquals("org.zf.protocol.zf", AcademicProviderRegistry.resolve(school("/portal-extra", "alias.example.test"))?.manifest?.id)
        catalog.getJSONObject("payload").getJSONArray("entries").getJSONObject(0).getJSONArray("aliases")
            .getJSONObject(0).put("host", "tampered.example.test")
        try { store.rememberCatalog(catalog); fail("Unsigned alias change was accepted") }
        catch (error: PluginException) { assertEquals(PluginErrorCode.BAD_SIGNATURE, error.code) }
        assertEquals("org.zf.protocol.zf", AcademicProviderRegistry.resolve(school("/portal", "tampered.example.test"))?.manifest?.id)
        val dev = install("test.binding.development", official = false)
        store.rememberCatalog(aliases(dev.manifest.id))
        assertFalse(AcademicProviderRegistry.matches(dev, alias))
    }

    @Test fun conflictsRequireChoiceAndRememberManualBuiltinAndDisabledOverrides() = runBlocking {
        val a = install("test.binding.a")
        val b = install("test.binding.b")
        val school = school()
        assertEquals(2, AcademicProviderRegistry.candidates(school).size)
        try { AcademicProviderRegistry.resolve(school); fail("Ambiguous provider selected silently") }
        catch (error: AcademicException) { assertEquals(AcademicStatus.UNSUPPORTED, error.status) }
        AcademicProviderRegistry.choose(school, b.manifest.id)
        AcademicProviderRegistry.initialize(context)
        assertEquals(b.digest, AcademicProviderRegistry.resolve(school)?.digest)
        AcademicProviderRegistry.setSchoolEnabled(b.manifest.id, school, false)
        assertNull(AcademicProviderRegistry.resolve(school))
        assertNotNull(store.active(b.manifest.id))
        AcademicProviderRegistry.setSchoolEnabled(b.manifest.id, school, true)
        assertEquals(b.digest, AcademicProviderRegistry.resolve(school)?.digest)
        AcademicProviderRegistry.choose(school, "builtin.zf")
        // Built-in now resolves to the updatable TypeScript package.
        assertEquals("org.zf.protocol.zf", AcademicProviderRegistry.resolve(school)?.manifest?.id)
        AcademicProviderRegistry.choose(school, a.manifest.id)
        assertEquals(a.digest, AcademicProviderRegistry.resolve(school)?.digest)
    }

    @Test fun pathsAndSchoolSwitchesKeepPreferencesSeparate() = runBlocking {
        val a = install("test.binding.campus-a")
        val b = install("test.binding.campus-b", "/campus-b")
        val campusA = school()
        val campusB = school("/campus-b")
        assertEquals(a.digest, AcademicProviderRegistry.resolve(campusA)?.digest)
        assertEquals(b.digest, AcademicProviderRegistry.resolve(campusB)?.digest)
        AcademicProviderRegistry.setSchoolEnabled(a.manifest.id, campusA, false)
        assertEquals("org.zf.protocol.zf", AcademicProviderRegistry.resolve(campusA)?.manifest?.id)
        assertEquals(b.digest, AcademicProviderRegistry.resolve(campusB)?.digest)
        AcademicProviderRegistry.choose(campusB, "builtin.zf")
        AcademicProviderRegistry.setSchoolEnabled(a.manifest.id, campusA, true)
        assertEquals(a.digest, AcademicProviderRegistry.resolve(campusA)?.digest)
        assertEquals("org.zf.protocol.zf", AcademicProviderRegistry.resolve(campusB)?.manifest?.id)
        assertEquals("org.zf.protocol.zf", AcademicProviderRegistry.resolve(school("/campus-ab"))?.manifest?.id)
    }

    @Test fun updateAndRollbackKeepPreviouslyPinnedPackageReadable() = runBlocking {
        val first = install("test.binding.version")
        val pinned = AcademicProviderRegistry.resolve(school())!!
        val second = install(first.manifest.id, version = "2.0.0")
        assertNotEquals(first.digest, second.digest)
        assertEquals(second.digest, AcademicProviderRegistry.resolve(school())?.digest)
        assertEquals("1.0.0", pinned.manifest.version)
        assertEquals(first.digest, store.readDigest(pinned.digest).digest)
        store.rollback(first.manifest.id); AcademicProviderRegistry.reload()
        assertEquals(first.digest, AcademicProviderRegistry.resolve(school())?.digest)
        assertEquals(second.digest, store.readDigest(second.digest).digest)
    }
}

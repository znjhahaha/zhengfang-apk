package com.tyust.course.academic.plugin

import android.app.Application
import android.content.Context
import android.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class PluginSchoolCatalogTest {
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val id = "test.search.school"
    private lateinit var context: Context
    @Before fun setup() { context = RuntimeEnvironment.getApplication(); AcademicProviderRegistry.initialize(context) }
    @After fun cleanup() { AcademicProviderRegistry.packages().deactivate(id); AcademicProviderRegistry.restoreOfficialCatalog() }
    private fun signed(payload: JSONObject) = JSONObject().put("keyId", "search-test").put("signature", Base64.encodeToString(
        Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(PluginJson.canonical(payload).toByteArray()); sign() }, Base64.NO_WRAP))
    private fun manifest() = JSONObject().put("id", id).put("name", "示例大学适配").put("version", "1.0.0").put("apiVersion", 2)
        .put("kind", "configuration").put("extends", "builtin.zf").put("capabilities", JSONArray()).put("files", JSONObject())
        .put("network", JSONArray().put(JSONObject().put("origin", "https://jw.example.test").put("pathPrefix", "/")
            .put("methods", JSONArray().put("GET")).put("purposes", JSONArray().put("query"))))
        .put("school", JSONObject().put("id", "sample").put("name", "示例大学").put("protocol", "https").put("domain", "jw.example.test").put("basePath", "/jw"))
    private fun envelope(entry: JSONObject): JSONObject {
        val payload = JSONObject().put("apiVersion", 3).put("catalogVersion", 2).put("entries", JSONArray().put(entry))
        return signed(payload).put("payload", payload)
    }
    private fun client(server: MockWebServer): PluginCatalogClient {
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        AcademicProviderRegistry.configureLocalCatalog("http://127.0.0.1:${server.port}/catalog.json", JSONObject().put("keyId", "search-test")
            .put("spki", Base64.encodeToString(key.public.encoded, Base64.NO_WRAP)).toString())
        return AcademicProviderRegistry.catalog()
    }
    @Test fun searchVerifiesSignatureAndPreservesIncompatibleRows() = runBlocking {
        MockWebServer().use { server ->
            val client = client(server)
            val entry = manifest().put("minAppVersionCode", 999999)
            server.enqueue(MockResponse().setBody(envelope(entry).toString()))
            assertNotNull(client.schoolEntries().single().incompatibleReason)
            server.enqueue(MockResponse().setBody(envelope(entry).toString()))
            assertTrue(client.check().isEmpty())
            val tampered = envelope(entry); tampered.getJSONObject("payload").getJSONArray("entries").getJSONObject(0).put("name", "tampered")
            server.enqueue(MockResponse().setBody(tampered.toString()))
            try { client.schoolEntries(); fail("Unsigned result accepted") } catch (error: PluginException) { assertEquals(PluginErrorCode.BAD_SIGNATURE, error.code) }
            server.enqueue(MockResponse().setResponseCode(503))
            try { client.schoolEntries(); fail("Offline reported as no schools") } catch (_: java.io.IOException) {} catch (error: PluginException) { assertEquals(PluginErrorCode.NETWORK_RETRYABLE, error.code) }
        }
    }
    @Test fun downloadedSchoolDoesNotActivateUntilConsentAndDigestMustStillMatch() = runBlocking {
        MockWebServer().use { server ->
            val client = client(server); val manifest = manifest()
            val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
                mapOf("manifest.json" to manifest, "signature.json" to signed(manifest)).forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(value.toString().toByteArray()); zip.closeEntry()
                }
            } }.toByteArray()
            val entry = JSONObject(manifest.toString()).put("url", "/school.eduplugin").put("sha256", PluginJson.sha256(bytes))
            server.enqueue(MockResponse().setBody(envelope(entry).toString())); server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val downloaded = client.update(id, stageOnly = true); val store = AcademicProviderRegistry.packages()
            assertNull(store.active(id)); assertNull(store.activateStaged(id))
            assertNull(store.active(id)) // Cancelling the permission UI leaves the school unbound and package inactive.
            try { store.activateStaged(id, expectedDigest = "0".repeat(64)); fail("Stale candidate accepted") }
            catch (error: PluginException) { assertEquals(PluginErrorCode.CONFLICT, error.code) }
            assertEquals(downloaded.digest, store.activateStaged(id, true, downloaded.digest)?.digest)
        }
    }
}

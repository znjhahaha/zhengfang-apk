package com.tyust.course.academic.plugin

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import okio.ByteString.Companion.toByteString

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PluginCatalogCacheTest {
    @Test fun savedCatalogIsScopedAndReverifiedBeforeOfflineDiscovery() {
        val context = RuntimeEnvironment.getApplication()
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val source = "https://catalog.test/plugins.json"
        val payload = JSONObject().put("apiVersion", 1).put("entries", JSONArray().put(JSONObject().put("id", "school.plugin")))
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(PluginJson.canonical(payload).toByteArray()); sign()
        }.toByteString().base64()
        val envelope = JSONObject().put("payload", payload).put("keyId", "test").put("signature", signature)
        val store = PluginPackageStore(context, mapOf("test" to pair.public))
        store.rememberCatalog(envelope, source)
        assertEquals("school.plugin", store.cachedCatalog(source).single().getString("id"))
        assertTrue(store.cachedCatalog("https://another.test/plugins.json").isEmpty())
        assertTrue(PluginPackageStore(context, emptyMap()).cachedCatalog(source).isEmpty())
        payload.getJSONArray("entries").getJSONObject(0).put("id", "tampered.plugin")
        File(context.filesDir, "academic-plugins/catalog-source-${PluginJson.sha256(source.toByteArray())}.json").writeText(envelope.toString())
        assertTrue(store.cachedCatalog(source).isEmpty())
    }
}

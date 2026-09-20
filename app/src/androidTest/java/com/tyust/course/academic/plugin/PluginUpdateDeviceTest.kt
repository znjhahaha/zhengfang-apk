package com.tyust.course.academic.plugin

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class PluginUpdateDeviceTest {
    @Test fun signedLocalUpdateInterruptedDownloadAndRollbackKeepTheRightVersion() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(app) { override fun getFilesDir() = File(app.filesDir, "update-test").apply { mkdirs() } }
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val store = PluginPackageStore(context, mapOf("test" to pair.public))
        fun sign(json: JSONObject): String = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(PluginJson.canonical(json).toByteArray()); sign().toByteString().base64() }
        fun pack(version: String): ByteArray {
            val manifest = JSONObject("""{"id":"update.test","name":"更新测试","version":"$version","apiVersion":1,"kind":"configuration","extends":"builtin.zf","capabilities":[],"network":[],"school":{"id":"update.test","name":"更新测试","domain":"example.test","protocol":"https","basePath":"/"},"files":{}}""")
            val signature = JSONObject().put("keyId", "test").put("signature", sign(manifest))
            return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
                for ((name, value) in mapOf("manifest.json" to manifest, "signature.json" to signature)) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(value.toString().toByteArray()); zip.closeEntry()
                }
            } }.toByteArray()
        }
        store.deactivate("update.test")
        val first = store.install(pack("1.0.0"))
        val second = pack("1.0.1")
        val third = pack("1.0.2")
        MockWebServer().use { server ->
            server.start()
            fun catalog(version: String, bytes: ByteArray): String {
                val payload = JSONObject().put("apiVersion", 1).put("entries", JSONArray().put(JSONObject().put("id", "update.test").put("version", version).put("url", "package.zfplugin").put("sha256", PluginJson.sha256(bytes))))
                return JSONObject().put("payload", payload).put("keyId", "test").put("signature", sign(payload)).toString()
            }
            val client = PluginCatalogClient(server.url("/catalog.json").toString(), mapOf("test" to pair.public), store)
            server.enqueue(MockResponse().setBody(catalog("1.0.1", second)))
            server.enqueue(MockResponse().setBody(Buffer().write(second)))
            assertEquals("1.0.1", client.update("update.test").manifest.version)
            assertEquals("1.0.0", first.manifest.version)
            server.enqueue(MockResponse().setBody(catalog("1.0.2", third)))
            server.enqueue(MockResponse().setBody(Buffer().write(third)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            assertTrue(runCatching { client.update("update.test") }.isFailure)
            assertEquals("1.0.1", store.active("update.test")!!.manifest.version)
            assertEquals("1.0.0", store.rollback("update.test").manifest.version)
            server.enqueue(MockResponse().setBody(catalog("1.0.2", third).replace("1.0.2", "9.0.0")))
            assertTrue(runCatching { client.update("update.test") }.isFailure)
            assertEquals("1.0.0", store.active("update.test")!!.manifest.version)
            server.enqueue(MockResponse().setBody(catalog("1.0.2", third)))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val before = server.requestCount
            val update = launch(Dispatchers.IO) { client.update("update.test") }
            withTimeout(10_000) { while (server.requestCount < before + 2) delay(20) }
            withTimeout(3_000) { update.cancelAndJoin() }
            assertEquals("1.0.0", store.active("update.test")!!.manifest.version)
        }
    }
}

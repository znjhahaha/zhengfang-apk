package com.tyust.course.academic.plugin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okio.ByteString.Companion.toByteString

class PluginPackageTest {
    private val schema = PluginSchema(JSONObject(File("src/main/assets/academic-plugin/manifest.schema.json").readText()))
    private fun manifest() = JSONObject("""{"id":"test.school","name":"测试学校","version":"1.0.0","apiVersion":1,"kind":"configuration","extends":"builtin.zf","capabilities":[],"network":[],"school":{"id":"test","name":"测试学校","domain":"school.test","protocol":"https","basePath":"/"},"files":{}}""")
    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
    private fun rejected(code: PluginErrorCode, block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (e: PluginException) { assertEquals(code, e.code) }
    }
    @Test fun developmentImportIsExplicitAndOfficialSignatureIsRequired() {
        val bytes = zip(mapOf("manifest.json" to manifest().toString().toByteArray()))
        assertFalse(PluginPackageVerifier.read(bytes, schema, emptyMap(), true).official)
        rejected(PluginErrorCode.BAD_SIGNATURE) { PluginPackageVerifier.read(bytes, schema, emptyMap(), false) }
    }
    @Test fun signatureCoversEveryManifestField() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val manifest = manifest()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(PluginJson.canonical(manifest).toByteArray()); sign()
        }
        val signed = JSONObject().put("keyId", "test").put("signature", signature.toByteString().base64())
        fun pack() = zip(mapOf("manifest.json" to manifest.toString().toByteArray(), "signature.json" to signed.toString().toByteArray()))
        assertTrue(PluginPackageVerifier.read(pack(), schema, mapOf("test" to pair.public), false).official)
        manifest.getJSONObject("school").put("domain", "changed.test")
        rejected(PluginErrorCode.BAD_SIGNATURE) { PluginPackageVerifier.read(pack(), schema, mapOf("test" to pair.public), true) }
    }
    @Test fun traversalUndeclaredFilesAndZipBombsAreRejected() {
        val manifest = manifest().toString().toByteArray()
        for (files in listOf(mapOf("manifest.json" to manifest, "../outside" to byteArrayOf(1)),
            mapOf("manifest.json" to manifest, "index.js" to byteArrayOf(1)),
            mapOf("manifest.json" to manifest, "index.js" to ByteArray(8 * 1024 * 1024 + 1)))) {
            rejected(PluginErrorCode.VALIDATION_FAILED) { PluginPackageVerifier.read(zip(files), schema, emptyMap(), true) }
        }
    }
    @Test fun changedExecutableFailsDigestBeforeLoading() {
        val manifest = manifest().put("kind", "extension").put("entry", "index.js")
            .put("capabilities", org.json.JSONArray(listOf("study.schedule")))
            .put("files", JSONObject().put("index.js", PluginJson.sha256("original".toByteArray())))
        rejected(PluginErrorCode.VALIDATION_FAILED) {
            PluginPackageVerifier.read(zip(mapOf("manifest.json" to manifest.toString().toByteArray(), "index.js" to "tampered".toByteArray())), schema, emptyMap(), true)
        }
    }
}

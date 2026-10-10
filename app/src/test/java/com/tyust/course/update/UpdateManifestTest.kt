package com.tyust.course.update

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

internal object UpdateFixtures {
    val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val keys = mapOf("test-key" to pair.public)
    fun payload() = JSONObject().put("channel", "stable").put("revision", 100).put("packageName", "com.tyust.course")
        .put("versionCode", 98).put("versionName", "1.0.98").put("minSdk", 24).put("releaseNotes", "更新：国内下载")
        .put("forceUpdate", false).put("size", 6).put("sha256", "a".repeat(64)).put("sourceSha", "b".repeat(40))
        .put("buildId", "36717097766").put("publishedAt", "2026-10-01T00:00:00Z")
        .put("mirrors", JSONArray().put(JSONObject().put("id", "cf").put("name", "自有镜像").put("url", "https://dl.hidisiwa.xyz/releases/98/a.apk")))
    fun envelope(payload: String = payload().toString()): String {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(bytes); sign() }
        return JSONObject().put("schemaVersion", 2).put("keyId", "test-key")
            .put("payload", Base64.getEncoder().encodeToString(bytes)).put("signature", Base64.getEncoder().encodeToString(signature)).toString()
    }
    fun manifest(p: JSONObject = payload()) = UpdateManifestVerifier.verify(envelope(p.toString()), keys, p.getString("channel"))
    fun splitPayload() = payload().put("artifacts", JSONObject().apply {
        for ((abi, marker) in listOf("arm64-v8a" to "c", "armeabi-v7a" to "d")) put(abi, JSONObject()
            .put("size", if (abi == "arm64-v8a") 4 else 3).put("sha256", marker.repeat(64))
            .put("mirrors", JSONArray().put(JSONObject().put("id", "cf").put("name", abi)
                .put("url", "https://dl.hidisiwa.xyz/releases/98/$abi.apk"))))
    })
}

class UpdateManifestTest {
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: UpdateFailure) {} }
    @Test fun verifiesExactUtf8WithoutReserialization() {
        val original = "  " + UpdateFixtures.payload().toString(2) + "\n"
        val m = UpdateManifestVerifier.verify(UpdateFixtures.envelope(original), UpdateFixtures.keys, "stable")
        assertEquals("更新：国内下载", m.releaseNotes); assertEquals(98, m.versionCode); assertFalse(m.forceUpdate)
    }
    @Test fun payloadTamperingRejected() {
        val e = JSONObject(UpdateFixtures.envelope()); e.put("payload", Base64.getEncoder().encodeToString(UpdateFixtures.payload().put("versionCode", 999).toString().toByteArray()))
        rejected { UpdateManifestVerifier.verify(e.toString(), UpdateFixtures.keys, "stable") }
    }
    @Test fun unknownKeyRejected() { rejected { UpdateManifestVerifier.verify(UpdateFixtures.envelope(), emptyMap(), "stable") } }
    @Test fun testCannotEnterStableChannel() { rejected { UpdateManifestVerifier.verify(UpdateFixtures.envelope(UpdateFixtures.payload().put("channel", "test").toString()), UpdateFixtures.keys, "stable") } }
    @Test fun duplicateSignedFieldRejected() {
        rejected { UpdateManifestVerifier.verify(UpdateFixtures.envelope(UpdateFixtures.payload().toString().dropLast(1) + ",\"versionCode\":999}"), UpdateFixtures.keys, "stable") }
    }
    @Test fun escapedDuplicateFieldRejected() { rejected { UpdateJson.parse("{\"a\":1,\"\\u0061\":2}") } }
    @Test fun duplicateNestedMirrorRejected() { rejected { UpdateJson.parse("{\"mirrors\":[{\"url\":\"a\",\"url\":\"b\"}]}") } }
    @Test fun jsonExtensionsRejected() {
        listOf("{'a':1}", "{\"a\":01}", "{\"a\":NaN}", "{\"a\":1,}", "{} garbage", "{/*comment*/\"a\":1}", "{\"a\":1}#comment", "{\"a\":[1,]}").forEach { value -> rejected { UpdateJson.parse(value) } }
    }
    @Test fun sizeAndDepthBounded() {
        rejected { UpdateJson.parse("{\"a\":\"" + "a".repeat(128 * 1024) + "\"}") }
        rejected { UpdateJson.parse("{\"a\":" + "[".repeat(18) + "0" + "]".repeat(18) + "}") }
    }
    @Test fun invalidContractValuesRejected() {
        listOf("versionCode" to 0, "revision" to -1, "size" to 0, "size" to Long.MAX_VALUE, "minSdk" to 1,
            "sha256" to "bad", "sourceSha" to "bad", "packageName" to "other", "versionCode" to "98", "forceUpdate" to "false").forEach { (key, value) ->
            rejected { UpdateFixtures.manifest(UpdateFixtures.payload().put(key, value)) }
        }
    }
    @Test fun destinationBoundaries() {
        listOf("http://dl.hidisiwa.xyz/a", "https://dl.hidisiwa.xyz.evil.test/a", "https://dl.hidisiwa.xyz:444/a",
            "https://user:password@dl.hidisiwa.xyz/a", "https://127.0.0.1/a", "file:///a", "https://dl.hidisiwa.xyz/a#x").forEach { assertFalse(it, UpdateManifestVerifier.validUrl(it)) }
        assertTrue(UpdateManifestVerifier.validUrl("https://gh-proxy.com/https://github.com/znjhahaha/zhengfang-apk/releases/download/v1.0.98/app-release.apk"))
    }
    @Test fun mirrorIdentityIsUnique() {
        val p = UpdateFixtures.payload(); p.getJSONArray("mirrors").put(p.getJSONArray("mirrors").getJSONObject(0))
        rejected { UpdateFixtures.manifest(p) }
    }
    @Test fun choosesHighestRevisionAcrossSources() {
        val old = UpdateFixtures.manifest(); val newer = UpdateFixtures.manifest(UpdateFixtures.payload().put("revision", 101))
        assertEquals(newer, UpdateManifestVerifier.choose(listOf(newer, old), old))
    }
    @Test fun preventsDowngradeAndReplacedSameVersion() {
        val previous = UpdateFixtures.manifest()
        assertNull(UpdateManifestVerifier.choose(listOf(UpdateFixtures.manifest(UpdateFixtures.payload().put("revision", 99))), previous))
        assertNull(UpdateManifestVerifier.choose(listOf(UpdateFixtures.manifest(UpdateFixtures.payload().put("revision", 101).put("versionCode", 97))), previous))
        assertNull(UpdateManifestVerifier.choose(listOf(UpdateFixtures.manifest(UpdateFixtures.payload().put("revision", 101).put("sha256", "c".repeat(64)))), previous))
    }
    @Test fun signedArtifactsPreferArm64AndKeepOldClientUniversalFields() {
        val original = UpdateFixtures.manifest(UpdateFixtures.splitPayload())
        val arm64 = original.forDevice(listOf("armeabi-v7a", "arm64-v8a"))
        assertEquals("arm64-v8a", arm64.selectedAbi); assertEquals(4L, arm64.size)
        assertEquals("c".repeat(64), arm64.sha256)
        assertTrue(arm64.mirrors.single().url.endsWith("/arm64-v8a.apk"))
        assertEquals(original.envelope, arm64.envelope)
        assertEquals("armeabi-v7a", original.forDevice(listOf("armeabi-v7a")).selectedAbi)
        assertNull(original.selectedAbi); assertEquals(6L, original.size); assertEquals("a".repeat(64), original.sha256)
        assertNotEquals(original.identity, arm64.identity)
        rejected { original.forDevice(listOf("x86_64")) }
        val legacy = UpdateFixtures.manifest()
        assertSame(legacy, legacy.forDevice(listOf("arm64-v8a")))
    }
    @Test fun artifactTamperingAndInvalidArchitectureCannotEnterVerifiedManifest() {
        val payload = UpdateFixtures.splitPayload()
        val signed = JSONObject(UpdateFixtures.envelope(payload.toString()))
        payload.getJSONObject("artifacts").getJSONObject("arm64-v8a").put("sha256", "f".repeat(64))
        signed.put("payload", Base64.getEncoder().encodeToString(payload.toString().toByteArray()))
        rejected { UpdateManifestVerifier.verify(signed.toString(), UpdateFixtures.keys, "stable") }
        for ((key, value) in listOf("size" to 0, "sha256" to "invalid", "mirrors" to JSONArray())) {
            val bad = UpdateFixtures.splitPayload()
            bad.getJSONObject("artifacts").getJSONObject("arm64-v8a").put(key, value)
            rejected { UpdateFixtures.manifest(bad) }
        }
        val bad = UpdateFixtures.splitPayload()
        bad.getJSONObject("artifacts").put("x86_64", bad.getJSONObject("artifacts").getJSONObject("arm64-v8a"))
        rejected { UpdateFixtures.manifest(bad) }
    }
    @Test fun sameVersionCannotReplaceOrRemoveSplitArtifactButCanRefreshMirrors() {
        val previous = UpdateFixtures.manifest(UpdateFixtures.splitPayload())
        val changed = UpdateFixtures.splitPayload().put("revision", 101)
        changed.getJSONObject("artifacts").getJSONObject("armeabi-v7a").put("sha256", "f".repeat(64))
        assertNull(UpdateManifestVerifier.choose(listOf(UpdateFixtures.manifest(changed)), previous))
        assertNull(UpdateManifestVerifier.choose(listOf(UpdateFixtures.manifest(UpdateFixtures.payload().put("revision", 101))), previous))
        val mirrors = UpdateFixtures.splitPayload().put("revision", 101)
        mirrors.getJSONObject("artifacts").getJSONObject("arm64-v8a").getJSONArray("mirrors").getJSONObject(0)
            .put("url", "https://dl.hidisiwa.xyz/new/arm64.apk")
        assertNotNull(UpdateManifestVerifier.choose(listOf(UpdateFixtures.manifest(mirrors)), previous))
    }
}

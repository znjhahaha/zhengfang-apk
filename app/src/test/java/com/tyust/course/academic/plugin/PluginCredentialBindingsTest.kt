package com.tyust.course.academic.plugin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

class PluginCredentialBindingsTest {
    private val values = JSONObject().put("username", "synthetic-student").put("password", "mock-password-中文")
    private fun request(transform: JSONObject) = JSONObject().put("credential", "opaque-fixture")
        .put("url", "https://school.example.test/xtgl/login_slogin.html").put("purpose", "auth").put("method", "POST")
        .put("form", JSONObject().put("mmsfjm", "1"))
        .put("bindings", JSONObject().put("form", JSONObject().put("yhm", "username").put("mm", JSONObject().put("from", "password").put("transform", transform))))
    @Test fun zhengfangRsaBindsEncryptedPasswordAndPreservesEncryptionFlag() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val public = keys.public as RSAPublicKey
        val formats = listOf(JSONObject().put("type", "rsa-pkcs1").put("publicKeySpkiBase64", public.encoded.toByteString().base64()),
            JSONObject().put("type", "rsa-pkcs1").put("encoding", "base64").put("modulus", public.modulus.toByteArray().toByteString().base64()).put("exponent", public.publicExponent.toByteArray().toByteString().base64()),
            JSONObject().put("type", "rsa-pkcs1").put("encoding", "hex").put("modulus", public.modulus.toString(16)).put("exponent", public.publicExponent.toString(16)))
        for (transform in formats) {
            val input = request(transform); val output = PluginCredentialBindings.apply(input, values)
            val form = output.getJSONObject("form")
            assertEquals("1", form.getString("mmsfjm")); assertEquals("synthetic-student", form.getString("yhm"))
            val decrypted = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, keys.private) }.doFinal(form.getString("mm").decodeBase64()!!.toByteArray())
            assertEquals(values.getString("password"), String(decrypted, Charsets.UTF_8))
            assertFalse(output.has("credential")); assertFalse(output.has("bindings")); assertTrue(input.has("bindings"))
            assertFalse(output.toString().contains(values.getString("password")))
        }
    }
    @Test fun invalidTransformsAndMissingHandlesNeverFallBackToPlaintext() {
        for (transform in listOf(JSONObject().put("type", "unknown"), JSONObject().put("type", "rsa-pkcs1").put("publicKeySpkiBase64", "bad"), JSONObject().put("type", "aes-cbc").put("keyBase64", "bad").put("ivBase64", "bad"))) {
            try { PluginCredentialBindings.apply(request(transform), values); fail() } catch (e: PluginException) { assertEquals(PluginErrorCode.VALIDATION_FAILED, e.code); assertFalse(e.message.orEmpty().contains("mock-password")) }
        }
        try { PluginCredentialBindings.apply(request(JSONObject().put("type", "base64")), null); fail() } catch (e: PluginException) { assertEquals(PluginErrorCode.VALIDATION_FAILED, e.code) }
    }
    @Test fun legacyStringBindingAndStructuredPlainBindingStayCompatible() {
        val input = JSONObject("""{"credential":"opaque","headers":{"authorization":"old"},"bindings":{"headers":{"Authorization":"username"},"form":{"mm":{"from":"password"}}}}""")
        val output = PluginCredentialBindings.apply(input, values)
        assertEquals("Bearer synthetic-student", output.getJSONObject("headers").getString("Authorization"))
        assertEquals(1, output.getJSONObject("headers").length())
        assertEquals(values.getString("password"), output.getJSONObject("form").getString("mm"))
    }
    @Test fun otherSchoolEncryptionFormatsUseUtf8AndBoundedKeys() {
        val digest = PluginCredentialBindings.apply(request(JSONObject().put("type", "digest").put("algorithm", "SHA-256")), values).getJSONObject("form").getString("mm")
        assertEquals(64, digest.length)
        val base64 = PluginCredentialBindings.apply(request(JSONObject().put("type", "base64")), values).getJSONObject("form").getString("mm")
        assertEquals(values.getString("password"), base64.decodeBase64()!!.utf8())
        for (type in listOf("aes-cbc", "aes-ecb")) {
            val key = ByteArray(16) { it.toByte() }; val iv = ByteArray(16)
            val spec = JSONObject().put("type", type).put("keyBase64", key.toByteString().base64()).put("ivBase64", iv.toByteString().base64())
            val encrypted = PluginCredentialBindings.apply(request(spec), values).getJSONObject("form").getString("mm")
            val cipher = Cipher.getInstance(if (type == "aes-cbc") "AES/CBC/PKCS5Padding" else "AES/ECB/PKCS5Padding")
            val secret = javax.crypto.spec.SecretKeySpec(key, "AES")
            if (type == "aes-cbc") cipher.init(Cipher.DECRYPT_MODE, secret, javax.crypto.spec.IvParameterSpec(iv)) else cipher.init(Cipher.DECRYPT_MODE, secret)
            assertEquals(values.getString("password"), String(cipher.doFinal(encrypted.decodeBase64()!!.toByteArray()), Charsets.UTF_8))
        }
    }
}

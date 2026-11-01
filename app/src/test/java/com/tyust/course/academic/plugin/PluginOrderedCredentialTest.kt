package com.tyust.course.academic.plugin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class PluginOrderedCredentialTest {
    @Test fun orderedMobileAndOtpAreEncryptedAsOneBodyAndNeverReturned() {
        val key = ByteArray(16) { it.toByte() }; val iv = ByteArray(16)
        val request = JSONObject("""{"body":"{\"a\":\"\",\"b\":\"first_v4\"}","credentials":{"mobile":"opaque1","otp":"opaque2"},"bindings":{"body":{"a":{"fields":[{"key":"c","from":"mobile.mobile"},{"key":"d","from":"otp.otp"},{"key":"e","value":"CPDAILY"},{"key":"f","value":"86"}],"encoding":"tc2"}}}}""")
        request.getJSONObject("bindings").getJSONObject("body").getJSONObject("a").put("transform", JSONObject().put("type", "aes-cbc").put("keyBase64", key.toByteString().base64()).put("ivBase64", iv.toByteString().base64()))
        val output = PluginCredentialBindings.apply(request, JSONObject().put("mobile.mobile", "synthetic-mobile").put("otp.otp", "synthetic-otp"))
        val encrypted = JSONObject(output.getString("body")).getString("a")
        val decrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key,"AES"), IvParameterSpec(iv)) }.doFinal(encrypted.replace(" ", "").decodeBase64()!!.toByteArray())
        assertEquals("""{"c":"synthetic-mobile","d":"synthetic-otp","e":"CPDAILY","f":"86"}""", String(decrypted, Charsets.UTF_8))
        assertFalse(output.has("credentials")); assertFalse(output.has("bindings")); assertFalse(output.toString().contains("synthetic-otp"))
    }
    @Test fun tc2AddsSpaceAtExactFortyFiveByteBoundary() {
        val request = JSONObject("""{"body":"{}","bindings":{"body":{"a":{"from":"phone","encoding":"tc2"}}}}""")
        val b64 = JSONObject(PluginCredentialBindings.apply(request, JSONObject().put("phone", "x".repeat(45))).getString("body")).getString("a")
        assertEquals(61, b64.length); assertTrue(b64.endsWith(" "))
        val short = JSONObject(PluginCredentialBindings.apply(request, JSONObject().put("phone", "x".repeat(44))).getString("body")).getString("a")
        assertEquals(60, short.length); assertFalse(short.endsWith(" "))
    }
    @Test fun duplicateFieldsAndMissingAliasFailBeforeSending() {
        for (fields in listOf("""[{"key":"a","from":"missing"}]""", """[{"key":"a","value":1},{"key":"a","value":2}]""")) {
            val request = JSONObject("""{"body":"{}","bindings":{"body":{"a":{"fields":$fields}}}}""")
            try { PluginCredentialBindings.apply(request, JSONObject()); fail() } catch(e: PluginException) { assertEquals(PluginErrorCode.VALIDATION_FAILED,e.code) }
        }
    }
}

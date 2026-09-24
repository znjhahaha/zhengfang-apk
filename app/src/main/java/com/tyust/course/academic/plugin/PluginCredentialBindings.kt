package com.tyust.course.academic.plugin

import org.json.JSONObject
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Credential-derived values stay within the host request; never returned to the reducer. */
object PluginCredentialBindings {
    fun structured(request: JSONObject): Boolean = listOf("form", "headers").any { area ->
        request.optJSONObject("bindings")?.optJSONObject(area)?.let { map -> map.keys().asSequence().any { map.opt(it) is JSONObject } } == true
    }
    fun apply(request: JSONObject, values: JSONObject?): JSONObject {
        val result = JSONObject(request.toString())
        val bindings = result.optJSONObject("bindings") ?: JSONObject()
        for (area in listOf("form", "headers")) bindings.optJSONObject(area)?.let { mapping ->
            if (mapping.length() > 24) invalid()
            val target = result.optJSONObject(area) ?: JSONObject()
            mapping.keys().forEach { field ->
                val descriptor = mapping.opt(field)
                val from = if (descriptor is String) descriptor else (descriptor as? JSONObject)?.optString("from").orEmpty()
                if (from.isBlank() || values?.opt(from) !is String) invalid()
                val value = values.getString(from)
                val transformed = (descriptor as? JSONObject)?.optJSONObject("transform")?.let { transform(value, it) } ?: value
                if (area == "headers") target.keys().asSequence().filter { it.equals(field, true) }.toList().forEach(target::remove)
                target.put(field, if (area == "headers" && field.equals("Authorization", true)) "Bearer $transformed" else transformed)
            }
            result.put(area, target)
        }
        result.remove("credential"); result.remove("bindings")
        return result
    }
    private fun transform(value: String, spec: JSONObject): String {
        fun decode(name: String) = spec.getString(name).decodeBase64()?.toByteArray() ?: invalid()
        val bytes = value.toByteArray(Charsets.UTF_8)
        try {
            return when (spec.getString("type")) {
                "rsa-pkcs1" -> {
                    val factory = KeyFactory.getInstance("RSA")
                    val key = if (spec.has("publicKeySpkiBase64")) factory.generatePublic(X509EncodedKeySpec(decode("publicKeySpkiBase64"))) else {
                        fun number(name: String): BigInteger = when (spec.getString("encoding")) {
                            "base64" -> BigInteger(1, decode(name))
                            "hex" -> { val text = spec.getString(name); if (!text.matches(Regex("[a-fA-F0-9]+"))) invalid(); BigInteger(text, 16) }
                            else -> invalid()
                        }
                        factory.generatePublic(RSAPublicKeySpec(number("modulus"), number("exponent")))
                    } as RSAPublicKey
                    if (key.modulus.bitLength() !in 512..8192 || key.publicExponent < BigInteger.valueOf(3)) invalid()
                    Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, key) }.doFinal(bytes).toByteString().base64()
                }
                "aes-cbc", "aes-ecb" -> {
                    val key = decode("keyBase64"); if (key.size !in setOf(16, 24, 32)) invalid()
                    val cbc = spec.getString("type") == "aes-cbc"
                    val cipher = Cipher.getInstance(if (cbc) "AES/CBC/PKCS5Padding" else "AES/ECB/PKCS5Padding")
                    if (cbc) { val iv = decode("ivBase64"); if (iv.size != 16) invalid(); cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
                    else cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
                    cipher.doFinal(bytes).toByteString().base64()
                }
                "digest" -> { val algorithm = spec.getString("algorithm"); if (algorithm !in setOf("MD5", "SHA-256")) invalid(); MessageDigest.getInstance(algorithm).digest(bytes).toByteString().hex() }
                "base64" -> bytes.toByteString().base64()
                else -> invalid()
            }
        } catch (_: Exception) { invalid() }
    }
    private fun invalid(): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, "凭据字段或加密参数无效，未发送请求")
}

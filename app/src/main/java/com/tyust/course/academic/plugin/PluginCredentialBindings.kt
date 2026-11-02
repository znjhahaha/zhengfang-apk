package com.tyust.course.academic.plugin

import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    fun v6(request: JSONObject): Boolean = request.optJSONObject("bindings")?.has("query") == true || listOf("form", "headers", "body").any { area ->
        request.optJSONObject("bindings")?.optJSONObject(area)?.let { map -> map.keys().asSequence().any { key ->
            map.optJSONObject(key)?.optJSONObject("transform")?.let { it.optString("type") == "text" || it.has("prefix") || it.has("trim") } == true
        } } == true
    }
    fun requireV6(request: JSONObject, manifest: PluginManifest, operation: String) {
        if (!v6(request)) return
        if (!manifest.isNative || "auth" !in manifest.permissions || request.optString("purpose") != "auth" ||
            operation !in setOf("serviceAuth.start", "serviceAuth.resume", "serviceAuth.validate", "serviceAuth.logout") ||
            manifest.json.optInt("minAppVersionCode") < 133 || manifest.json.optJSONArray("requires")?.let(PluginJson::objects).orEmpty()
                .none { it.optString("name") == "network.request" && it.optInt("version") >= 6 }) invalid()
        validateTransforms(request)
    }
    private fun validateTransforms(request: JSONObject) {
        request.optJSONObject("bindings")?.let { if (it.has("query") && it.optJSONObject("query") == null) invalid() }
        for (area in listOf("query", "form", "headers", "body")) request.optJSONObject("bindings")?.optJSONObject(area)?.let { map -> map.keys().forEach { key ->
            val binding = map.optJSONObject(key) ?: return@forEach
            val spec = binding.optJSONObject("transform") ?: return@forEach
            val type = spec.optString("type")
            if (spec.has("trim") && (spec.opt("trim") !is Boolean || type !in setOf("aes-cbc", "text")) ||
                spec.has("prefix") && (type != "aes-cbc" || spec.opt("prefix") !is String || spec.getString("prefix").length > 4096) ||
                type == "text" && (binding.optString("from").substringAfterLast('.') != "username" || spec.keys().asSequence().any { it !in setOf("type", "trim") })) invalid()
            if ((spec.has("trim") || spec.has("prefix")) && type == "aes-cbc" && spec.keys().asSequence().any { it !in setOf("type", "keyBase64", "ivBase64", "trim", "prefix") }) invalid()
        } }
    }
    /** ECMA-262 WhiteSpace + LineTerminator, intentionally excluding NEL/U+001C/U+180E. */
    private fun jsTrim(value: String): String = value.trim { it in "\u0009\u000a\u000b\u000c\u000d\u0020\u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff" }
    fun structured(request: JSONObject): Boolean = listOf("form", "headers", "body").any { area ->
        request.optJSONObject("bindings")?.optJSONObject(area)?.let { map -> map.keys().asSequence().any { map.opt(it) is JSONObject } } == true
    }
    /** Materialize a non-secret preview so the complete request can be checked before vault access. */
    fun validateAliases(request: JSONObject): JSONObject {
        if (request.has("credentials") && request.optJSONObject("credentials") == null) invalid()
        val aliases = request.optJSONObject("credentials") ?: JSONObject()
        aliases.keys().forEach { alias ->
            if (!alias.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,31}")) || aliases.opt(alias) !is String || aliases.getString(alias).length !in 1..128) invalid()
        }
        val single = aliases.length() == 0 && request.optString("credential").isNotBlank()
        val fake = JSONObject()
        val bindings = request.optJSONObject("bindings") ?: invalid()
        if (bindings.keys().asSequence().any { it !in setOf("body", "form", "headers", "query") }) invalid()
        fun reference(from: String) {
            val parts = from.split('.', limit = 2)
            if (single) { if (parts.size != 1 || !from.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}"))) invalid() }
            else if (parts.size != 2 || !aliases.has(parts[0]) || !parts[1].matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}"))) invalid()
            fake.put(from, "validation")
        }
        for (area in listOf("body", "form", "headers", "query")) bindings.optJSONObject(area)?.let { map ->
            map.keys().forEach { key ->
                when(val descriptor = map.get(key)) {
                    is String -> reference(descriptor)
                    is JSONObject -> {
                        if (descriptor.has("from")) reference(descriptor.getString("from"))
                        descriptor.optJSONArray("fields")?.let { fields -> (0 until fields.length()).forEach { index ->
                            val field = fields.optJSONObject(index) ?: invalid()
                            if (field.has("from")) reference(field.getString("from"))
                        } }
                    }
                    else -> invalid()
                }
            }
        }
        return apply(request, fake, preview = true)
    }
    fun apply(request: JSONObject, values: JSONObject?, preview: Boolean = false): JSONObject {
        validateTransforms(request)
        val result = JSONObject(request.toString())
        val bindings = result.optJSONObject("bindings") ?: JSONObject()
        var queryUrl = if (bindings.has("query")) result.optString("url").toHttpUrlOrNull() ?: invalid() else null
        if (queryUrl != null && (queryUrl.username.isNotEmpty() || queryUrl.password.isNotEmpty() || queryUrl.fragment != null || '%' in queryUrl.encodedPath || '\\' in queryUrl.encodedPath)) invalid()
        for (area in listOf("form", "headers", "query")) bindings.optJSONObject(area)?.let { mapping ->
            if (mapping.length() > 24) invalid()
            if (area == "query" && mapping.length() == 0) invalid()
            val target = result.optJSONObject(area) ?: JSONObject()
            mapping.keys().forEach { field ->
                val descriptor = mapping.opt(field)
                if (descriptor is JSONObject && (descriptor.keys().asSequence().any { it !in setOf("from", "transform") } || descriptor.has("transform") && descriptor.optJSONObject("transform") == null)) invalid()
                val from = if (descriptor is String) descriptor else (descriptor as? JSONObject)?.optString("from").orEmpty()
                if (from.isBlank() || values?.opt(from) !is String) invalid()
                val value = values.getString(from)
                val transformedValue = (descriptor as? JSONObject)?.optJSONObject("transform")?.let { transform(value, it) } ?: value
                val transformed = if (preview && area == "query") "validation" else transformedValue
                if (area == "query") {
                    val current = queryUrl ?: invalid()
                    if (field.length !in 1..128 || field in current.queryParameterNames) invalid()
                    val next = current.newBuilder().addQueryParameter(field, transformed).build()
                    if (next.scheme != current.scheme || next.host != current.host || next.port != current.port || next.encodedPath != current.encodedPath || next.toString().length > 16384) invalid()
                    queryUrl = next
                }
                if (area == "headers") target.keys().asSequence().filter { it.equals(field, true) }.toList().forEach(target::remove)
                target.put(field, if (area == "headers" && field.equals("Authorization", true)) "Bearer $transformed" else transformed)
            }
            if (area != "query") result.put(area, target)
        }
        queryUrl?.let { result.put("url", it.toString()) }
        bindings.optJSONObject("body")?.let { mapping ->
            if (mapping.length() !in 1..24 || result.has("form") || result.opt("body") !is String) invalid()
            val body = try { JSONObject(result.getString("body")) } catch (_: Exception) { invalid() }
            mapping.keys().forEach { key ->
                val descriptor = mapping.optJSONObject(key) ?: invalid()
                if (descriptor.keys().asSequence().any { it !in setOf("from", "fields", "transform", "encoding") } || descriptor.has("from") == descriptor.has("fields")) invalid()
                val text = if (descriptor.has("from")) resolve(values, descriptor.getString("from")) else {
                    val fields = descriptor.optJSONArray("fields") ?: invalid()
                    if (fields.length() !in 1..24) invalid()
                    val seen = mutableSetOf<String>()
                    // JSONObject's iteration order differs between JVM and Android. Serialize the
                    // protocol's ordered fields explicitly before encrypting inside the host.
                    (0 until fields.length()).joinToString(",", "{", "}") { index ->
                        val item = fields.optJSONObject(index) ?: invalid()
                        val name = item.optString("key")
                        if (name.isBlank() || name.length > 128 || !seen.add(name) || item.has("from") == item.has("value") ||
                            item.keys().asSequence().any { it !in setOf("key", "from", "value") }) invalid()
                        val value = if (item.has("from")) resolve(values, item.getString("from")) else item.get("value")
                        val literal = JSONObject().put("v", value).toString().let { it.substring(it.indexOf(':') + 1, it.length - 1) }
                        JSONObject.quote(name) + ":" + literal
                    }
                }
                val spec = descriptor.optJSONObject("transform")
                val transformed = spec?.let { transform(text, it) } ?: text
                val encoded = when (descriptor.optString("encoding")) {
                    "" -> transformed
                    "base64", "tc2" -> {
                        val b64 = if (spec == null) text.toByteArray(Charsets.UTF_8).toByteString().base64() else {
                            if (spec.optString("type") !in setOf("aes-cbc", "aes-ecb", "rsa-pkcs1", "base64")) invalid()
                            transformed
                        }
                        if (descriptor.optString("encoding") == "tc2") {
                            val bytes = b64.decodeBase64()?.size ?: invalid()
                            val complete = bytes / 45 * 60
                            b64.take(complete).chunked(60).joinToString("") { "$it " } + b64.drop(complete)
                        } else b64
                    }
                    else -> invalid()
                }
                body.put(key, encoded)
            }
            result.put("body", body.toString())
        }
        result.remove("credential"); result.remove("credentials"); result.remove("bindings")
        return result
    }
    private fun resolve(values: JSONObject?, from: String): String {
        if (from.isBlank() || values?.opt(from) !is String) invalid()
        return values.getString(from)
    }
    private fun transform(value: String, spec: JSONObject): String {
        fun decode(name: String) = spec.getString(name).decodeBase64()?.toByteArray() ?: invalid()
        val plain = if (spec.optString("type") == "aes-cbc") spec.optString("prefix") + (if (spec.optBoolean("trim")) jsTrim(value) else value) else value
        val bytes = plain.toByteArray(Charsets.UTF_8)
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
                "text" -> if (spec.optBoolean("trim")) jsTrim(value) else value
                else -> invalid()
            }
        } catch (_: Exception) { invalid() }
    }
    private fun invalid(): Nothing = throw PluginException(PluginErrorCode.VALIDATION_FAILED, "凭据字段或加密参数无效，未发送请求")
}

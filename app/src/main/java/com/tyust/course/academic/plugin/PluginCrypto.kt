package com.tyust.course.academic.plugin

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Byte-oriented crypto for plugin protocols. No credentials or protocol constants are stored here. */
object PluginCrypto {
    fun execute(method: String, p: JSONObject): String = try {
        when (method) {
            "symmetric" -> symmetric(p)
            "rsaPkcs1Decrypt" -> rsaPkcs1Decrypt(p)
            else -> invalid()
        }.toByteString().base64()
    } catch (_: Exception) {
        // Provider messages and causes can contain caller data or key details.
        throw PluginException(PluginErrorCode.VALIDATION_FAILED, "Crypto operation failed")
    }

    private fun symmetric(p: JSONObject): ByteArray {
        val algorithm = string(p, "algorithm")
        val blockSize = when (algorithm) {
            "AES" -> 16
            "DES", "DESede" -> 8
            else -> invalid()
        }
        val mode = string(p, "mode")
        if (mode != "CBC" && mode != "ECB") invalid()
        val operation = when (string(p, "operation")) {
            "encrypt" -> Cipher.ENCRYPT_MODE
            "decrypt" -> Cipher.DECRYPT_MODE
            else -> invalid()
        }
        var key = decode(p, "keyBase64", 32)
        when (algorithm) {
            "AES" -> if (key.size !in setOf(16, 24, 32)) invalid()
            "DES" -> if (key.size != 8) invalid()
            "DESede" -> when (key.size) {
                16 -> key += key.copyOfRange(0, 8) // K1 K2 K1 (two-key 3DES).
                24 -> Unit
                else -> invalid()
            }
        }
        val iv = if (mode == "CBC") decode(p, "ivBase64", blockSize).also {
            if (it.size != blockSize) invalid()
        } else {
            if (p.has("ivBase64")) invalid()
            null
        }
        val input = decode(p, "inputBase64", PluginLimits.RESPONSE_BYTES)
        if (operation == Cipher.DECRYPT_MODE && (input.isEmpty() || input.size % blockSize != 0)) invalid()
        // PKCS5Padding is the JCA name for PKCS#7 padding on these block ciphers.
        return Cipher.getInstance("$algorithm/$mode/PKCS5Padding").apply {
            val secret = SecretKeySpec(key, algorithm)
            if (iv == null) init(operation, secret) else init(operation, secret, IvParameterSpec(iv))
        }.doFinal(input)
    }

    private fun rsaPkcs1Decrypt(p: JSONObject): ByteArray {
        val encoded = decode(p, "privateKeyPkcs8Base64", 16 * 1024)
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(encoded)) as? RSAPrivateKey
            ?: invalid()
        val blockSize = (key.modulus.bitLength() + 7) / 8
        // Bound provider work and require space for the type-2 header and padding.
        if (blockSize !in 64..1024) invalid()
        val input = decode(p, "ciphertextBase64", PluginLimits.RESPONSE_BYTES)
        if (input.isEmpty() || input.size % blockSize != 0) invalid()
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, key) }
        val plaintext = ByteArrayOutputStream()
        for (offset in input.indices step blockSize) {
            val block = cipher.doFinal(input, offset, blockSize)
            // Explicitly require encryption block type 2; never accept type-1 signature padding.
            if (block.size != blockSize || block[0] != 0.toByte() || block[1] != 2.toByte()) invalid()
            var separator = 2
            while (separator < block.size && block[separator] != 0.toByte()) separator++
            if (separator < 10 || separator == block.size) invalid()
            plaintext.write(block, separator + 1, block.size - separator - 1)
        }
        // No partial plaintext is returned if a later block is malformed.
        return plaintext.toByteArray()
    }

    private fun string(p: JSONObject, name: String): String = p.opt(name) as? String ?: invalid()

    private fun decode(p: JSONObject, name: String, maxBytes: Int): ByteArray {
        val value = string(p, name)
        if (value.length > 4 * ((maxBytes + 2) / 3)) invalid()
        // Accept standard padded or unpadded Base64, without ignored junk or whitespace.
        val end = value.indexOf('=').let { if (it < 0) value.length else it }
        if (value.length - end > 2 || (end < value.length && value.length % 4 != 0) || end % 4 == 1) invalid()
        for (i in value.indices) {
            val c = value[i]
            if (i >= end) {
                if (c != '=') invalid()
            } else if (c !in 'A'..'Z' && c !in 'a'..'z' && c !in '0'..'9' && c != '+' && c != '/') invalid()
        }
        return value.decodeBase64()?.toByteArray()?.also { if (it.size > maxBytes) invalid() } ?: invalid()
    }

    private fun invalid(): Nothing = throw IllegalArgumentException()
}

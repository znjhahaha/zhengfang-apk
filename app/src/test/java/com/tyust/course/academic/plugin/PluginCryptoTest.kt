package com.tyust.course.academic.plugin

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class PluginCryptoTest {
    private fun b64(bytes: ByteArray) = bytes.toByteString().base64()
    private fun bytes(value: String) = value.decodeBase64()!!.toByteArray()
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun request(algorithm: String = "AES", mode: String = "CBC", keySize: Int = 16,
                        input: ByteArray = byteArrayOf(0, 1, -1, -128, 42)) = JSONObject()
        .put("algorithm", algorithm).put("mode", mode).put("operation", "encrypt")
        .put("keyBase64", b64(ByteArray(keySize) { (it + 1).toByte() }))
        .put("inputBase64", b64(input)).apply {
            if (mode == "CBC") put("ivBase64", b64(ByteArray(if (algorithm == "AES") 16 else 8) { it.toByte() }))
        }
    private fun copy(p: JSONObject) = JSONObject(p.toString())
    private fun execute(p: JSONObject) = PluginCrypto.execute("symmetric", p)
    private fun rejected(method: String = "symmetric", p: JSONObject): PluginException {
        val error = assertThrows(PluginException::class.java) { PluginCrypto.execute(method, p) }
        assertEquals(PluginErrorCode.VALIDATION_FAILED, error.code)
        assertEquals("Crypto operation failed", error.message)
        assertNull("Provider exceptions must not escape through the cause", error.cause)
        assertEquals(0, error.suppressed.size)
        return error
    }

    @Test fun aesCbcAndEcbMatchPublishedFirstBlockVectors() {
        // Public NIST SP 800-38A examples; PKCS padding adds a second block.
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val input = hex("6bc1bee22e409f96e93d7e117393172a")
        for ((mode, expected) in listOf("CBC" to "7649abac8119b246cee98e9b12e9197d",
                                       "ECB" to "3ad77bb40d7a3660a89ecaf32466ef97")) {
            val p = request(mode = mode, input = input).put("keyBase64", b64(key))
            assertArrayEquals(hex(expected), bytes(execute(p)).copyOfRange(0, 16))
        }
    }

    @Test fun desEcbMatchesPublicKnownAnswerVector() {
        val p = request("DES", "ECB", 8, hex("0123456789abcdef"))
            .put("keyBase64", b64(hex("133457799bbcdff1")))
        assertArrayEquals(hex("85e813540f0ab405"), bytes(execute(p)).copyOfRange(0, 8))
    }

    @Test fun allAlgorithmsAndKeyLengthsUsePkcs7AndPreserveBinaryData() {
        for ((algorithm, keySizes) in listOf("AES" to listOf(16, 24, 32), "DES" to listOf(8), "DESede" to listOf(16, 24))) {
            val blockSize = if (algorithm == "AES") 16 else 8
            for (mode in listOf("CBC", "ECB")) for (keySize in keySizes) {
                for (size in listOf(0, 1, blockSize - 1, blockSize, blockSize + 1, 3 * blockSize)) {
                    val input = ByteArray(size) { (it * 91).toByte() }
                    val p = request(algorithm, mode, keySize, input)
                    var key = bytes(p.getString("keyBase64"))
                    if (algorithm == "DESede" && key.size == 16) key += key.copyOfRange(0, 8)
                    val padding = blockSize - size % blockSize
                    val padded = input + ByteArray(padding) { padding.toByte() }
                    val oracle = Cipher.getInstance("$algorithm/$mode/NoPadding").apply {
                        if (mode == "CBC") init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, algorithm), IvParameterSpec(bytes(p.getString("ivBase64"))))
                        else init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, algorithm))
                    }.doFinal(padded)
                    val encrypted = execute(p)
                    assertArrayEquals("$algorithm/$mode/$keySize/$size", oracle, bytes(encrypted))
                    assertEquals(b64(input), execute(copy(p).put("operation", "decrypt").put("inputBase64", encrypted)))
                }
            }
        }
    }

    @Test fun twoKeyTripleDesEqualsExplicitK1K2K1() {
        for (mode in listOf("CBC", "ECB")) {
            val p = request("DESede", mode, 16)
            val key = bytes(p.getString("keyBase64"))
            assertEquals(execute(p), execute(copy(p).put("keyBase64", b64(key + key.copyOfRange(0, 8)))))
        }
    }

    @Test fun validatesEnumsAndRequiresActualJsonStrings() {
        for ((field, badValues) in mapOf(
            "algorithm" to listOf("aes", "RSA", "AES/CBC/NoPadding", "", 1, JSONObject.NULL),
            "mode" to listOf("cbc", "GCM", "", false, JSONObject.NULL),
            "operation" to listOf("ENCRYPT", "sign", "", 1, JSONObject.NULL),
            "keyBase64" to listOf(1234, JSONObject.NULL),
            "inputBase64" to listOf(1234, JSONObject.NULL),
            "ivBase64" to listOf(1234, JSONObject.NULL))) {
            for (value in badValues) rejected(p = request().put(field, value))
            rejected(p = request().apply { remove(field) })
        }
        rejected("unknown-secret-method", request())
    }

    @Test fun rejectsMalformedBase64AndIncorrectKeyAndIvSizes() {
        for (field in listOf("keyBase64", "ivBase64", "inputBase64")) {
            for (bad in listOf("!secret!", "A", "====", "AA=A", "AA==trailing")) {
                rejected(p = request().put(field, bad))
            }
        }
        for ((algorithm, goodSizes) in listOf("AES" to setOf(16, 24, 32), "DES" to setOf(8), "DESede" to setOf(16, 24))) {
            for (size in listOf(0, 7, 8, 15, 16, 17, 23, 24, 25, 31, 32, 33).filter { it !in goodSizes }) {
                rejected(p = request(algorithm, keySize = size))
            }
            val blockSize = if (algorithm == "AES") 16 else 8
            for (size in listOf(0, blockSize - 1, blockSize + 1)) {
                rejected(p = request(algorithm, keySize = goodSizes.first()).put("ivBase64", b64(ByteArray(size))))
            }
        }
        // ECB has no IV; supplied values must not be silently ignored.
        rejected(p = request(mode = "ECB").put("ivBase64", b64(ByteArray(16))))
    }

    @Test fun rejectsTruncatedCiphertextAndInvalidPkcsPadding() {
        for ((algorithm, keySize) in listOf("AES" to 16, "DES" to 8, "DESede" to 24)) for (mode in listOf("CBC", "ECB")) {
            val p = request(algorithm, mode, keySize).put("operation", "decrypt")
            val blockSize = if (algorithm == "AES") 16 else 8
            for (size in listOf(0, 1, blockSize - 1, blockSize + 1)) rejected(p = copy(p).put("inputBase64", b64(ByteArray(size))))
            for (last in listOf(0, blockSize + 1, 2)) {
                val unpadded = ByteArray(blockSize) { 0x41 }.apply { this[lastIndex] = last.toByte() }
                val cipher = Cipher.getInstance("$algorithm/$mode/NoPadding").apply {
                    val key = SecretKeySpec(bytes(p.getString("keyBase64")), algorithm)
                    if (mode == "CBC") init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(bytes(p.getString("ivBase64"))))
                    else init(Cipher.ENCRYPT_MODE, key)
                }
                rejected(p = copy(p).put("inputBase64", b64(cipher.doFinal(unpadded))))
            }
        }
    }

    private fun rsaRequest(keys: KeyPair, ciphertext: ByteArray) = JSONObject()
        .put("privateKeyPkcs8Base64", b64(keys.private.encoded)).put("ciphertextBase64", b64(ciphertext))
    private fun rsaEncrypt(keys: KeyPair, input: ByteArray, padding: String = "PKCS1Padding") =
        Cipher.getInstance("RSA/ECB/$padding").apply { init(Cipher.ENCRYPT_MODE, keys.public) }.doFinal(input)

    @Test fun rsaDecryptsSingleAndMultipleCompleteBlocksInOrder() {
        for (bits in listOf(1024, 2048)) {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()
            val chunks = listOf(byteArrayOf(0, -1, -128), ByteArray(bits / 8 - 11) { it.toByte() }, ByteArray(0))
            for (parts in listOf(chunks.take(1), chunks)) {
                val ciphertext = parts.fold(ByteArray(0)) { acc, part -> acc + rsaEncrypt(keys, part) }
                assertEquals(b64(parts.fold(ByteArray(0)) { acc, part -> acc + part }),
                    PluginCrypto.execute("rsaPkcs1Decrypt", rsaRequest(keys, ciphertext)))
            }
        }
    }

    @Test fun rsaRejectsMissingWrongTypeMalformedAndNonPrivateKeys() {
        val keys = rsaKeys
        val p = rsaRequest(keys, rsaEncrypt(keys, byteArrayOf(42)))
        for (field in listOf("privateKeyPkcs8Base64", "ciphertextBase64")) {
            rejected("rsaPkcs1Decrypt", copy(p).apply { remove(field) })
            for (bad in listOf(JSONObject.NULL, 1234, "!secret!", "A", "")) rejected("rsaPkcs1Decrypt", copy(p).put(field, bad))
        }
        rejected("rsaPkcs1Decrypt", copy(p).put("privateKeyPkcs8Base64", b64(keys.public.encoded)))
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        rejected("rsaPkcs1Decrypt", copy(p).put("privateKeyPkcs8Base64", b64(ec.private.encoded)))
        rejected("rsaPkcs1Decrypt", copy(p).put("privateKeyPkcs8Base64", b64(ByteArray(16385))))
    }

    @Test fun rsaRejectsPartialAndOutOfRangeBlocks() {
        val keys = rsaKeys
        val good = rsaEncrypt(keys, byteArrayOf(42))
        for (ciphertext in listOf(ByteArray(0), good.dropLast(1).toByteArray(), good + byteArrayOf(0),
                                  ByteArray(good.size), ByteArray(good.size) { -1 })) {
            rejected("rsaPkcs1Decrypt", rsaRequest(keys, ciphertext))
        }
    }

    @Test fun rsaRequiresType2EightByteNonzeroPaddingAndASeparatorInEveryBlock() {
        val keys = rsaKeys
        val size = ((keys.public as RSAPublicKey).modulus.bitLength() + 7) / 8
        val valid = ByteArray(size) { 0x55 }.apply { this[0] = 0; this[1] = 2; this[10] = 0 }
        val validCiphertext = rsaEncrypt(keys, valid, "NoPadding")
        assertEquals(b64(valid.copyOfRange(11, size)), PluginCrypto.execute("rsaPkcs1Decrypt", rsaRequest(keys, validCiphertext)))
        val malformed = listOf(
            valid.clone().apply { this[1] = 1 },
            valid.clone().apply { this[1] = 0 },
            valid.clone().apply { this[0] = 1 },
            valid.clone().apply { this[9] = 0 },
            valid.clone().apply { this[2] = 0 },
            valid.clone().apply { this[10] = 0x55 }
        )
        for (block in malformed) {
            val bad = rsaEncrypt(keys, block, "NoPadding")
            rejected("rsaPkcs1Decrypt", rsaRequest(keys, bad))
            rejected("rsaPkcs1Decrypt", rsaRequest(keys, validCiphertext + bad))
        }
    }

    @Test fun payloadSizesAreBoundedBeforeCryptoWork() {
        val oversized = b64(ByteArray(PluginLimits.RESPONSE_BYTES + 1))
        rejected(p = request().put("inputBase64", oversized))
        rejected("rsaPkcs1Decrypt", rsaRequest(rsaKeys, byteArrayOf(0)).put("ciphertextBase64", oversized))
    }

    companion object {
        // Ephemeral synthetic key material; never persisted or copied from private evidence.
        private val rsaKeys: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair() }
    }
}

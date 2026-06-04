package com.tyust.course.utils

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

object RSAUtils {

    fun encrypt(modulus: String, exponent: String, data: String): String {
        val mod = BigInteger(1, Base64.decode(modulus, Base64.DEFAULT))
        val exp = BigInteger(1, Base64.decode(exponent, Base64.DEFAULT))
        val spec = RSAPublicKeySpec(mod, exp)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(spec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }
}
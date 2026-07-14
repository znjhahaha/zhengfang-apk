package com.tyust.course.login

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.jsoup.Jsoup
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal object TyustSsoProtocol {
    class ProtocolException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    data class LoginPage(
        val execution: String,
        val cryptoKey: String,
        val captchaRequired: Boolean,
        val captchaUrl: String?,
        val formAction: String?
    )

    fun parseLoginPage(html: String): LoginPage {
        val document = Jsoup.parse(html)
        val execution = document.getElementById("login-page-flowkey")
            ?.let { element -> element.attr("value").ifBlank { element.text() } }
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw ProtocolException("SSO login page is missing execution")
        val cryptoKey = document.getElementById("login-croypto")
            ?.let { element -> element.attr("value").ifBlank { element.text() } }
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw ProtocolException("SSO login page is missing crypto key")
        val captchaInvisible = document.getElementById("recaptcha-invisible")
            ?.let { element -> element.attr("value").ifBlank { element.text() } }
            ?.trim()
            ?.toBooleanStrictOrNull()
            ?: true
        val captchaUrl = document.getElementById("captcha-url")
            ?.let { element -> element.attr("value").ifBlank { element.text() } }
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val formAction = document.select("form")
            .firstOrNull { it.attr("method").equals("post", ignoreCase = true) }
            ?.attr("action")
            ?.trim()

        return LoginPage(
            execution = execution,
            cryptoKey = cryptoKey,
            captchaRequired = !captchaInvisible,
            captchaUrl = captchaUrl,
            formAction = formAction
        )
    }

    fun encryptPassword(plaintext: String, base64Key: String): String {
        val keyBytes = base64Key.decodeBase64()?.toByteArray()
            ?: throw ProtocolException("SSO crypto key is not valid Base64")
        if (keyBytes.size != DES_KEY_SIZE_BYTES) {
            throw ProtocolException("SSO crypto key has an invalid length")
        }

        return try {
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "DES"))
            cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)).toByteString().base64()
        } catch (error: Exception) {
            throw ProtocolException("Unable to encrypt SSO password", error)
        } finally {
            keyBytes.fill(0)
        }
    }

    private const val DES_KEY_SIZE_BYTES = 8
}

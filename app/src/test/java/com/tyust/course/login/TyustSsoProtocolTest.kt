package com.tyust.course.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TyustSsoProtocolTest {
    @Test
    fun parseLoginPage_readsExecutionAndCryptoKey() {
        val html = """
            <html><body>
              <form method="post" action="">
                <input id="login-page-flowkey" value="flow-123" />
                <input id="login-croypto" value="MTIzNDU2Nzg=" />
                <input id="recaptcha-invisible" value="true" />
                <input id="captcha-url" value="/captcha?id=7" />
              </form>
            </body></html>
        """.trimIndent()

        val page = TyustSsoProtocol.parseLoginPage(html)

        assertEquals("flow-123", page.execution)
        assertEquals("MTIzNDU2Nzg=", page.cryptoKey)
        assertFalse(page.captchaRequired)
        assertEquals("/captcha?id=7", page.captchaUrl)
        assertEquals("", page.formAction)
    }

    @Test
    fun parseLoginPage_marksVisibleCaptchaAsRequired() {
        val html = """
            <input id="login-page-flowkey" value="flow-456" />
            <input id="login-croypto" value="MTIzNDU2Nzg=" />
            <input id="recaptcha-invisible" value="false" />
            <input id="captcha-url" value="/captcha?id=8" />
        """.trimIndent()

        assertTrue(TyustSsoProtocol.parseLoginPage(html).captchaRequired)
    }

    @Test
    fun parseLoginPage_supportsHiddenTextNodesUsedByProductionPage() {
        val html = """
            <p id="login-page-flowkey">flow-text</p>
            <p id="login-croypto">MTIzNDU2Nzg=</p>
            <p id="recaptcha-invisible">true</p>
            <p id="captcha-url">/captcha/text</p>
        """.trimIndent()

        val page = TyustSsoProtocol.parseLoginPage(html)

        assertEquals("flow-text", page.execution)
        assertEquals("MTIzNDU2Nzg=", page.cryptoKey)
        assertFalse(page.captchaRequired)
        assertEquals("/captcha/text", page.captchaUrl)
    }

    @Test
    fun parseLoginPage_rejectsMissingRequiredFields() {
        assertThrows(TyustSsoProtocol.ProtocolException::class.java) {
            TyustSsoProtocol.parseLoginPage("<html><body>login failed</body></html>")
        }
    }

    @Test
    fun encryptPassword_matchesBrowserAesEcbPkcs7() {
        assertEquals(
            "P697UH6hDBNHWmqOLa2FZA==",
            TyustSsoProtocol.encryptPassword(
                plaintext = "protocol-test",
                base64Key = "MTIzNDU2Nzg5MDEyMzQ1Ng=="
            )
        )
    }

    @Test
    fun encryptPassword_keepsLegacyDesSupportForEightByteKeys() {
        assertEquals(
            "RpEpIH9dSgIJYLKpHvn7aQ==",
            TyustSsoProtocol.encryptPassword(
                plaintext = "protocol-test",
                base64Key = "MTIzNDU2Nzg="
            )
        )
    }

    @Test
    fun encryptPassword_rejectsUnsupportedKeyLengths() {
        assertThrows(TyustSsoProtocol.ProtocolException::class.java) {
            TyustSsoProtocol.encryptPassword(
                plaintext = "protocol-test",
                base64Key = "MTIzNDU2Nzg5MA=="
            )
        }
    }

    @Test
    fun encryptCaptchaPayload_encryptsEmptyJsonObject() {
        assertEquals(
            "EH234SPWsbAVCbva63T5XQ==",
            TyustSsoProtocol.encryptCaptchaPayload(base64Key = "MTIzNDU2Nzg5MDEyMzQ1Ng==")
        )
    }
}

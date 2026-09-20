package com.tyust.course.academic

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.security.KeyPairGenerator
import javax.crypto.Cipher

class QzCasProtocolTest {
    private fun loginPage(execution: String = "fixture-execution", captcha: Boolean = false, mfa: Boolean = false) = """
        <el-form id="fm1Input"><input id="username"><input id="password" type="password"></el-form>
        <el-form id="fm1" action="login" method="post">
        <input name="username" type="hidden"><input name="password" type="hidden"><input name="captcha">
        <input name="execution" value="$execution"><input name="_eventId" value="submit"><input name="currentMenu" value="1">
        </el-form><script>var encryptEnabled = true; var casServerCaptchaEnabled = true;
        var casServerCaptchaSkipN = true; var captchaSkipN = "5"; var casServerCaptchaShow = $captcha;
        var mfaEnabled = "$mfa" == "true";</script>
    """.trimIndent()

    @Test fun vueFormAndConditionalFlagsAreParsedWithoutExecutingJavascript() {
        val normal = QzCasProtocol.page(loginPage())
        assertEquals("fixture-execution", normal.fields["execution"])
        assertFalse(normal.captchaRequired); assertFalse(normal.mfaEnabled)
        assertTrue(QzCasProtocol.page(loginPage(mfa = true)).mfaEnabled)
        assertTrue(QzCasProtocol.page(loginPage(), failedAttempts = 5).captchaRequired)
        assertEquals(AcademicStatus.INVALID_CREDENTIALS, QzCasProtocol.failure("<div id='msg'>用户名或密码不正确</div>"))
        assertEquals(AcademicStatus.CAPTCHA_REQUIRED, QzCasProtocol.failure("<div id='msg'>验证码错误</div>"))
        assertEquals(AcademicStatus.SESSION_EXPIRED, QzCasProtocol.failure("<div id='msg'>Invalid flow execution key</div>"))
        assertNull(QzCasProtocol.failure(loginPage()))
    }

    @Test fun cancellationClearsPendingCredentialsAndCannotSubmitAgain() = runBlocking {
        MockWebServer().use { cas -> MockWebServer().use { academic ->
            cas.start(); academic.start()
            academic.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val client = QzCasSsoClient(QzCasEntry(cas.url("/cas/login"), academic.url("/jsxsd/sso.jsp")))
            val request = async(kotlinx.coroutines.Dispatchers.Default) {
                client.begin(Credentials("synthetic-user", "synthetic-password"))
            }
            assertNotNull(academic.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS))
            request.cancel()
            request.join()
            assertEquals(AcademicStatus.SESSION_EXPIRED, client.submit("unused").status)
            assertTrue(client.teachingCookies().isEmpty())
        } }
    }

    @Test fun encryptedPasswordUsesPkcs1Utf8AndRsaPrefix() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PUBLIC KEY-----\n" + pair.public.encoded.toByteString().base64() + "\n-----END PUBLIC KEY-----"
        val encoded = QzCasProtocol.encrypt("synthetic密碼+@", pem)
        assertTrue(encoded.startsWith("__RSA__"))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, pair.private) }
        assertEquals("synthetic密碼+@", cipher.doFinal(encoded.removePrefix("__RSA__").decodeBase64()!!.toByteArray()).toString(Charsets.UTF_8))
    }

    @Test fun multiFactorRequirementReturnsToWebLoginWithoutPostingTheLoginForm() = runBlocking {
        MockWebServer().use { cas -> MockWebServer().use { academic ->
            cas.start(); academic.start()
            val entry = QzCasEntry(cas.url("/cas/login"), academic.url("/jsxsd/sso.jsp"))
            val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            academic.enqueue(MockResponse().setResponseCode(302).addHeader("Location", entry.loginWithService))
            cas.enqueue(MockResponse().setBody(loginPage(mfa = true)))
            cas.enqueue(MockResponse().setBody(key.public.encoded.toByteString().base64()))
            cas.enqueue(MockResponse().setBody("""{"code":0,"data":{"need":true}}"""))
            val client = QzCasSsoClient(entry)
            try {
                assertEquals(AcademicStatus.HUMAN_VERIFICATION_REQUIRED,
                    client.begin(Credentials("fixture", "fixture")).status)
                assertEquals(3, cas.requestCount)
                cas.takeRequest(); cas.takeRequest()
                assertEquals("/cas/mfa/detect", cas.takeRequest().path)
                assertTrue(client.teachingCookies().isEmpty())
            } finally { client.clear() }
        } }
    }

    @Test fun separateLoginAttemptsNeverShareCasCookies() = runBlocking {
        MockWebServer().use { cas -> MockWebServer().use { academic ->
            cas.start(); academic.start()
            val entry = QzCasEntry(cas.url("/cas/login"), academic.url("/jsxsd/sso.jsp"))
            val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            repeat(2) { index ->
                academic.enqueue(MockResponse().setResponseCode(302).addHeader("Location", entry.loginWithService))
                cas.enqueue(MockResponse().setBody(loginPage()).addHeader("Set-Cookie", "CAS_ACCOUNT=$index; Path=/cas"))
                cas.enqueue(MockResponse().setBody(key.public.encoded.toByteString().base64()))
                cas.enqueue(MockResponse().setBody(loginPage() + "<div id='msg'>用户名或密码错误</div>"))
                val client = QzCasSsoClient(entry)
                try {
                    assertEquals(AcademicStatus.INVALID_CREDENTIALS, client.begin(Credentials("fixture-$index", "fixture")).status)
                    assertNull(cas.takeRequest().getHeader("Cookie"))
                    assertEquals("CAS_ACCOUNT=$index", cas.takeRequest().getHeader("Cookie"))
                    assertEquals("CAS_ACCOUNT=$index", cas.takeRequest().getHeader("Cookie"))
                } finally { client.clear() }
            }
        } }
    }

    @Test fun ticketExchangeImportsOnlyTeachingCookiesAndRetainsService() = runBlocking {
        MockWebServer().use { cas -> MockWebServer().use { academic ->
            cas.start(); academic.start()
            val entry = QzCasEntry(cas.url("/cas/login"), academic.url("/jsxsd/sso.jsp"))
            val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            academic.enqueue(MockResponse().setBody("<script>window.location.href='${entry.loginWithService}';</script>"))
            cas.enqueue(MockResponse().addHeader("Set-Cookie", "CAS_ONLY=private; Path=/").setBody(loginPage()))
            cas.enqueue(MockResponse().setBody(key.public.encoded.toByteString().base64()))
            cas.enqueue(MockResponse().setResponseCode(302).addHeader("Location", entry.service.newBuilder().addQueryParameter("ticket", "ST-fixture").build()))
            academic.enqueue(MockResponse().addHeader("Set-Cookie", "JSESSIONID=teaching; Path=/jsxsd").setBody("<html>Academic home</html>"))
            val client = QzCasSsoClient(entry)
            assertEquals(AcademicStatus.SUCCESS, client.begin(Credentials("fixture-user", "fixture-password")).status)
            assertEquals(listOf("JSESSIONID"), client.teachingCookies().map { it.name })
            cas.takeRequest(); cas.takeRequest()
            val post = cas.takeRequest()
            assertEquals(entry.service.toString(), post.requestUrl!!.queryParameter("service"))
            val fields = post.body.readUtf8().split('&').associate { part -> part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='), "UTF-8") }
            assertEquals("fixture-execution", fields["execution"])
            assertTrue(fields["password"]!!.startsWith("__RSA__"))
            academic.takeRequest()
            assertFalse(academic.takeRequest().getHeader("Cookie").orEmpty().contains("CAS_ONLY"))
            client.clear(); assertTrue(client.teachingCookies().isEmpty())
        } }
    }

    @Test fun captchaContinuationUsesReturnedExecutionAndRejectsWrongService() = runBlocking {
        MockWebServer().use { cas -> MockWebServer().use { academic ->
            cas.start(); academic.start()
            val entry = QzCasEntry(cas.url("/cas/login"), academic.url("/jsxsd/sso.jsp"))
            academic.enqueue(MockResponse().setBody("<script>window.location.href='${entry.loginWithService}';</script>"))
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            cas.enqueue(MockResponse().setBody(loginPage(captcha = true)))
            cas.enqueue(MockResponse().setBody(pair.public.encoded.toByteString().base64()))
            cas.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(-1, -40, -1, 0, 1))))
            cas.enqueue(MockResponse().setBody(loginPage("next-execution", true) + "<div id='msg'>验证码错误</div>"))
            cas.enqueue(MockResponse().setResponseCode(302).addHeader("Location", cas.url("/cas/login?service=https://untrusted.invalid/")))
            val client = QzCasSsoClient(entry)
            assertEquals(AcademicStatus.CAPTCHA_REQUIRED, client.begin(Credentials("fixture", "fixture")).status)
            assertEquals(AcademicStatus.CAPTCHA_REQUIRED, client.submit("wrong").status)
            try { client.submit("next"); fail("unexpected redirect accepted") }
            catch (e: AcademicException) { assertEquals(AcademicStatus.UNTRUSTED_URL, e.status) }
            repeat(4) { cas.takeRequest() }
            assertTrue(cas.takeRequest().body.readUtf8().contains("execution=next-execution"))
            client.clear()
        } }
    }
}

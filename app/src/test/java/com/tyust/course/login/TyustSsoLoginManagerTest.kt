package com.tyust.course.login

import com.tyust.course.model.SchoolConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TyustSsoLoginManagerTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun postsEncryptedBrowserFormAndReturnsOnlyTeachingCookies() {
        enqueueSuccessfulLoginChain()
        val callback = RecordingCallback()
        val manager = TyustSsoLoginManager(testEndpoints())

        manager.login(tyustSchool(), "student-001", "protocol-test", callback)

        assertTrue("login callback timed out", callback.await())
        assertNull(callback.error)
        assertEquals("JSESSIONID=jw; route=node-a", callback.cookie)
        assertFalse(callback.cookie.orEmpty().contains("SESSION=sso"))

        val loginPageRequest = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("GET", loginPageRequest?.method)
        assertEquals(testEndpoints().teachingService.toString(), loginPageRequest?.requestUrl?.queryParameter("service"))

        val postRequest = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("POST", postRequest?.method)
        assertEquals(
            testEndpoints().teachingService.toString(),
            postRequest?.requestUrl?.queryParameter("service")
        )
        assertEquals(loginPageRequest?.requestUrl.toString(), postRequest?.getHeader("Referer"))
        val postBody = postRequest?.body?.readUtf8().orEmpty()
        val fields = decodeForm(postBody)
        assertEquals(
            setOf(
                "username",
                "password",
                "croypto",
                "type",
                "_eventId",
                "geolocation",
                "execution",
                "captcha_code"
            ),
            fields.keys
        )
        assertEquals("student-001", fields["username"])
        assertEquals("RpEpIH9dSgIJYLKpHvn7aQ==", fields["password"])
        assertEquals("MTIzNDU2Nzg=", fields["croypto"])
        assertEquals("UsernamePassword", fields["type"])
        assertEquals("submit", fields["_eventId"])
        assertEquals("flow-123", fields["execution"])
        assertEquals("", fields["captcha_code"])
        assertFalse(postBody.contains("protocol-test"))
    }

    @Test
    fun followsTheBoundedCasRedirectChainToAuthenticatedIndex() {
        enqueueSuccessfulLoginChain()
        val callback = RecordingCallback()

        TyustSsoLoginManager(testEndpoints()).login(
            tyustSchool(),
            "student-001",
            "protocol-test",
            callback
        )

        assertTrue("login callback timed out", callback.await())
        assertEquals("JSESSIONID=jw; route=node-a", callback.cookie)
        assertEquals(7, server.requestCount)
        val paths = buildList {
            repeat(7) { add(server.takeRequest().requestUrl!!.encodedPath) }
        }
        assertEquals(
            listOf(
                "/login",
                "/login",
                "/sso/jasiglogin/jwglxt",
                "/sso/jasiglogin/jwglxt",
                "/jwglxt/ticketlogin",
                "/jwglxt/xtgl/login_slogin.html",
                "/jwglxt/xtgl/index_initMenu.html"
            ),
            paths
        )
    }

    @Test
    fun resubmitsOnceWhenFirstPostRefreshesTheLoginForm() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginPageHtml()))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(loginPageHtml(execution = "flow-456"))
        )
        enqueueSuccessfulRedirectChain()
        val callback = RecordingCallback()

        TyustSsoLoginManager(testEndpoints()).login(
            tyustSchool(), "student-001", "protocol-test", callback
        )

        assertTrue("login callback timed out", callback.await())
        assertNull(callback.error)
        assertEquals("JSESSIONID=jw; route=node-a", callback.cookie)
        assertEquals(8, server.requestCount)

        val loginGet = server.takeRequest()
        val firstPost = server.takeRequest()
        val refreshedPost = server.takeRequest()
        assertEquals("GET", loginGet.method)
        assertEquals("POST", firstPost.method)
        assertEquals("POST", refreshedPost.method)
        assertEquals("flow-123", decodeForm(firstPost.body.readUtf8())["execution"])
        assertEquals("flow-456", decodeForm(refreshedPost.body.readUtf8())["execution"])
        assertEquals(
            testEndpoints().teachingService.toString(),
            refreshedPost.requestUrl!!.queryParameter("service")
        )
    }

    @Test
    fun stopsAfterOneRefreshedFormResubmission() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginPageHtml()))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(loginPageHtml(execution = "flow-456"))
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(loginPageHtml(execution = "flow-789"))
        )
        val callback = RecordingCallback()

        TyustSsoLoginManager(testEndpoints()).login(
            tyustSchool(), "student-001", "protocol-test", callback
        )

        assertTrue("login callback timed out", callback.await())
        assertNull(callback.cookie)
        assertEquals("统一认证登录失败，请检查账号密码或稍后重试", callback.error)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun mapsChineseCredentialErrorToInvalidCredentials() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginPageHtml()))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                loginPageHtml() + "<div class='error'>用户名或密码不正确</div>"
            )
        )
        val callback = RecordingCallback()

        TyustSsoLoginManager(testEndpoints()).login(
            tyustSchool(), "student-001", "wrong-password", callback
        )

        assertTrue(callback.await())
        assertTrue(callback.invalidCredentials)
        assertNull(callback.error)
        assertNull(callback.cookie)
    }

    @Test
    fun fetchesCaptchaWhenPostResponseIntroducesChallenge() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginPageHtml()))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                loginPageHtml(captchaInvisible = false) + "<div>请输入验证码</div>"
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(okio.Buffer().write(byteArrayOf(1, 2, 3, 4)))
        )
        val callback = RecordingCallback()

        TyustSsoLoginManager(testEndpoints()).login(
            tyustSchool(), "student-001", "protocol-test", callback
        )

        assertTrue(callback.await())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), callback.captchaBytes)
        assertNull(callback.error)
        server.takeRequest()
        server.takeRequest()
        assertEquals("/captcha", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun submitsCaptchaOnceAndMapsCaptchaError() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(loginPageHtml(captchaInvisible = false))
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(okio.Buffer().write(byteArrayOf(9, 8, 7)))
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(loginPageHtml(captchaInvisible = false) + "<div>验证码错误</div>")
        )
        val initialCallback = CaptchaFlowCallback()
        val submitCallback = CaptchaFlowCallback()
        val manager = TyustSsoLoginManager(testEndpoints())

        manager.login(tyustSchool(), "student-001", "protocol-test", initialCallback)
        assertTrue(initialCallback.captchaRequired.await(5, TimeUnit.SECONDS))
        manager.submitCaptcha("A7B9", submitCallback)
        assertTrue(submitCallback.captchaInvalid.await(5, TimeUnit.SECONDS))
        assertEquals(1L, initialCallback.captchaInvalid.count)

        val loginGet = server.takeRequest()
        val captchaGet = server.takeRequest()
        val captchaPost = server.takeRequest()
        assertEquals("GET", loginGet.method)
        assertEquals("/captcha", captchaGet.requestUrl!!.encodedPath)
        assertEquals("POST", captchaPost.method)
        assertEquals("A7B9", decodeForm(captchaPost.body.readUtf8())["captcha_code"])
        assertEquals(3, server.requestCount)
    }

    private fun enqueueSuccessfulLoginChain() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "SESSION=sso; Path=/login; HttpOnly")
                .setBody(loginPageHtml())
        )
        enqueueSuccessfulRedirectChain()
    }

    private fun enqueueSuccessfulRedirectChain() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", "/sso/jasiglogin/jwglxt?ticket=redacted-ticket")
        )
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", "/sso/jasiglogin/jwglxt")
        )
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", "/jwglxt/ticketlogin?uid=redacted&verify=redacted")
        )
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", "/jwglxt/xtgl/login_slogin.html")
        )
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", "/jwglxt/xtgl/index_initMenu.html")
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Set-Cookie", "JSESSIONID=jw; Path=/jwglxt; HttpOnly")
                .addHeader("Set-Cookie", "route=node-a; Path=/; HttpOnly")
                .setBody("<html><title>教务系统</title><body>authenticated dashboard</body></html>")
        )
    }

    private fun loginPageHtml(
        captchaInvisible: Boolean = true,
        execution: String = "flow-123"
    ): String = """
        <html><body>
          <form method="post" action="">
            <input id="login-page-flowkey" value="$execution" />
            <input id="login-croypto" value="MTIzNDU2Nzg=" />
            <input id="recaptcha-invisible" value="$captchaInvisible" />
            <input id="captcha-url" value="/captcha" />
          </form>
        </body></html>
    """.trimIndent()

    private fun testEndpoints() = TyustSsoEndpoints(
        ssoLogin = server.url("/login"),
        teachingService = server.url("/sso/jasiglogin/jwglxt"),
        teachingBase = server.url("/"),
        enforceHttps = false
    )

    private fun tyustSchool() = SchoolConfig(
        "tyust",
        "TYUST",
        "newjwc.tyust.edu.cn",
        "https"
    )

    private fun decodeForm(body: String): Map<String, String> = body
        .split('&')
        .associate { pair ->
            val parts = pair.split('=', limit = 2)
            URLDecoder.decode(parts[0], Charsets.UTF_8.name()) to
                URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8.name())
        }

    private class RecordingCallback : PasswordLoginCallback {
        private val latch = CountDownLatch(1)
        var cookie: String? = null
        var error: String? = null
        var captchaBytes: ByteArray? = null
        var invalidCredentials = false

        override fun onSuccess(cookie: String) {
            this.cookie = cookie
            latch.countDown()
        }

        override fun onCaptchaRequired(imageBytes: ByteArray) {
            captchaBytes = imageBytes
            latch.countDown()
        }

        override fun onCaptchaInvalid() {
            error = "unexpected invalid captcha"
            latch.countDown()
        }

        override fun onInvalidCredentials() {
            invalidCredentials = true
            latch.countDown()
        }

        override fun onError(message: String) {
            error = message
            latch.countDown()
        }

        fun await(): Boolean = latch.await(5, TimeUnit.SECONDS)
    }

    private class CaptchaFlowCallback : PasswordLoginCallback {
        val captchaRequired = CountDownLatch(1)
        val captchaInvalid = CountDownLatch(1)

        override fun onSuccess(cookie: String) = Unit

        override fun onCaptchaRequired(imageBytes: ByteArray) {
            captchaRequired.countDown()
        }

        override fun onCaptchaInvalid() {
            captchaInvalid.countDown()
        }

        override fun onInvalidCredentials() = Unit

        override fun onError(message: String) = Unit
    }
}

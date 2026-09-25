package com.tyust.course.academic

import com.tyust.course.login.PasswordLoginCallback
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

class AcademicPasswordLoginTest {
    @Test fun oldQzCaptchaRefreshAndRetryKeepTheLoginSessionAndUpdatedFields() = withGateway(AcademicSystem.QZ_OLD) { server, gateway, events ->
        server.enqueue(html(qzForm("first")).addHeader("Set-Cookie", "sid=password-attempt; Path=/jsxsd"))
        server.enqueue(captcha(1))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.QZ_OLD), "student", "secret", events)
        assertArrayEquals(image(1), events.next("captcha").image)
        assertEquals("/jsxsd/", server.next().path)
        assertEquals("sid=password-attempt", server.next().getHeader("Cookie"))

        server.enqueue(captcha(2))
        assertArrayEquals(image(2), refreshed(gateway))
        val refresh = server.next()
        assertEquals("/jsxsd/verifycode.servlet", refresh.requestUrl!!.encodedPath)
        assertEquals("sid=password-attempt", refresh.getHeader("Cookie"))

        server.enqueue(html(qzForm("after-error", "验证码不正确，请重新输入")))
        gateway.submitCaptcha("wrong", events)
        events.next("invalid-captcha")
        val rejected = server.next()
        val rejectedFields = fields(rejected)
        assertEquals("POST", rejected.method)
        assertEquals("wrong", rejectedFields["RANDOMCODE"])
        assertEquals("first", rejectedFields["csrf"])
        assertEquals("", rejectedFields["userPassword"])
        assertEquals("c3R1ZGVudA==%%%c2VjcmV0", rejectedFields["encoded"])

        server.enqueue(captcha(3))
        assertArrayEquals(image(3), refreshed(gateway))
        server.next()
        server.enqueue(html(home()).addHeader("Set-Cookie", "auth=direct; Path=/jsxsd"))
        server.enqueue(html(home()))
        gateway.submitCaptcha("aB39", events)
        val success = events.next("success")
        assertTrue(success.cookie.contains("auth=direct"))
        val accepted = server.next()
        assertEquals("after-error", fields(accepted)["csrf"])
        assertEquals("aB39", fields(accepted)["RANDOMCODE"])
        assertEquals("sid=password-attempt", accepted.getHeader("Cookie"))
        assertEquals("/jsxsd/framework/xsMain.jsp", server.next().path)
        assertEquals("student", gateway.studentId)
    }

    @Test fun oldZfRefreshPostsOnlyRefreshControlAndLoginUsesTheNewKeyAndViewstate() = withGateway(AcademicSystem.ZF_OLD) { server, gateway, events ->
        val firstKey = rsaKey()
        val nextKey = rsaKey()
        server.enqueue(html(zfForm("first", firstKey)).addHeader("Set-Cookie", "ASP.NET_SessionId=direct; Path=/jsxsd"))
        server.enqueue(captcha(1))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.ZF_OLD), "student", "Secret-9", events)
        events.next("captcha")
        server.next()
        assertEquals("first", server.next().requestUrl!!.queryParameter("SafeKey"))

        server.enqueue(html(zfForm("refreshed", nextKey)))
        server.enqueue(captcha(2))
        assertArrayEquals(image(2), refreshed(gateway))
        val refreshPost = server.next()
        val refreshFields = fields(refreshPost)
        assertEquals("POST", refreshPost.method)
        assertEquals("刷新验证码", refreshFields["Button2"])
        assertFalse(refreshFields.containsKey("Button1"))
        assertEquals("", refreshFields["TextBox2"])
        assertEquals("first", refreshFields["__VIEWSTATE"])
        assertEquals("refreshed", server.next().requestUrl!!.queryParameter("SafeKey"))

        server.enqueue(html(home()))
        server.enqueue(html(home()))
        gateway.submitCaptcha("7bR2", events)
        events.next("success")
        val login = server.next()
        val submitted = fields(login)
        assertEquals("ASP.NET_SessionId=direct", login.getHeader("Cookie"))
        assertEquals("refreshed", submitted["__VIEWSTATE"])
        assertEquals("event-refreshed", submitted["__EVENTVALIDATION"])
        assertEquals("7bR2", submitted["txtSecretCode"])
        assertEquals("学生", submitted["RadioButtonList1"])
        assertEquals("登录", submitted["Button1"])
        assertFalse(submitted.containsKey("Button2"))
        val decrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, nextKey.private) }
        val encrypted = submitted.getValue("TextBox2").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals("Secret-9", decrypt.doFinal(encrypted).toString(Charsets.UTF_8))
        assertEquals("/jsxsd/xs_main.aspx?xh=student", server.next().path)
    }

    @Test fun passwordErrorInsideReturnedLoginFormIsReportedAsInvalidCredentials() = withGateway(AcademicSystem.QZ_OLD) { server, gateway, events ->
        server.enqueue(html(qzForm("token", withCaptcha = false)))
        server.enqueue(html(qzForm("rejected", "用户名或密码错误", withCaptcha = false)))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.QZ_OLD), "student", "incorrect", events)
        events.next("invalid-credentials")
        assertEquals(2, server.requestCount)
    }

    @Test fun zfCaptchaErrorInServerAlertCanBeRetriedInTheSameSession() = withGateway(AcademicSystem.ZF_OLD) { server, gateway, events ->
        val key = rsaKey()
        server.enqueue(html(zfForm("token", key)).addHeader("Set-Cookie", "sid=zf; Path=/jsxsd"))
        server.enqueue(captcha(1))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.ZF_OLD), "student", "secret", events)
        events.next("captcha")
        server.enqueue(html(zfForm("rejected", key) + "<script>alert('验证码错误！！');</script>"))
        gateway.submitCaptcha("bad", events)
        events.next("invalid-captcha")
        server.enqueue(html(zfForm("again", key)))
        server.enqueue(captcha(2))
        assertArrayEquals(image(2), refreshed(gateway))
        server.enqueue(html(home()))
        server.enqueue(html(home()))
        gateway.submitCaptcha("7bR2", events)
        events.next("success")
    }

    @Test fun clearingLoginDiscardsTheCaptchaAttemptWithoutMakingAnotherRequest() = withGateway(AcademicSystem.QZ_OLD) { server, gateway, events ->
        server.enqueue(html(qzForm("token")))
        server.enqueue(captcha(1))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.QZ_OLD), "student", "secret", events)
        events.next("captcha")
        gateway.clearSensitiveState()
        gateway.submitCaptcha("aB39", events)
        events.next("error")
        assertNull(refreshed(gateway))
        assertEquals(2, server.requestCount)
    }

    @Test fun captchaRedirectCannotSendCredentialsOrRequestsToAnUnapprovedHost() = withGateway(AcademicSystem.QZ_OLD) { server, gateway, events ->
        server.enqueue(html(qzForm("token")))
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://untrusted.invalid/captcha"))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.QZ_OLD), "student", "secret", events)
        events.next("error")
        assertEquals(2, server.requestCount)
        assertEquals("GET", server.next().method)
        assertEquals("GET", server.next().method)
    }

    @Test fun htmlInsteadOfCaptchaIsAnErrorRatherThanABrokenImageOrLoginAttempt() = withGateway(AcademicSystem.QZ_OLD) { server, gateway, events ->
        server.enqueue(html(qzForm("token")))
        server.enqueue(html("<html>学校服务暂不可用</html>"))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.QZ_OLD), "student", "secret", events)
        events.next("error")
        assertEquals(2, server.requestCount)
    }

    @Test fun oldQzShiftEncodingFetchesTheSessionTokenAfterTheCaptcha() = withGateway(AcademicSystem.QZ_OLD) { server, gateway, events ->
        server.enqueue(html("""<form action="xk/LoginToXk" method="post"><input name="USERNAME"><input name="PASSWORD" type="password">
            <input name="RANDOMCODE"><img src="verifycode.servlet"><input name="encoded" type="hidden"></form>
            <script>var endpoint = 'xk/LoginToXk?flag=sess'; document.Form1.PASSWORD.value = "";</script>""")
            .addHeader("Set-Cookie", "sid=shift; Path=/jsxsd"))
        server.enqueue(captcha(1))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.QZ_OLD), "u", "p", events)
        events.next("captcha")
        assertEquals(2, server.requestCount)
        server.next(); server.next()
        server.enqueue(MockResponse().setBody("""{"data":"ABCD#22000"}"""))
        server.enqueue(html(home()))
        server.enqueue(html(home()))
        gateway.submitCaptcha("Ab12", events)
        events.next("success")
        assertEquals("/jsxsd/xk/LoginToXk?flag=sess", server.next().path)
        val post = server.next()
        assertEquals("sid=shift", post.getHeader("Cookie"))
        assertEquals("uAB%CD%%p", fields(post)["encoded"])
        assertEquals("Ab12", fields(post)["RANDOMCODE"])
        assertEquals("", fields(post)["PASSWORD"])
    }

    @Test fun oldZfWithoutAKnownEncryptionKeyDoesNotSubmitThePassword() = withGateway(AcademicSystem.ZF_OLD) { server, gateway, events ->
        server.enqueue(html("""<form action="default2.aspx"><input name="txtUserName"><input name="TextBox2" type="password"></form>"""))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.ZF_OLD), "student", "secret", events)
        events.next("web")
        assertEquals(1, server.requestCount)
    }

    @Test fun oldQzUsesActiveBase64HandlerInsteadOfAnUnusedShiftLogin() = withGateway(AcademicSystem.QZ_OLD) { server, gateway, events ->
        val page = qzForm("sanya").replace("</form>", "<a onclick=\"submitForm1()\">登录</a></form>") +
            """<script>function login() { var url = '/jsxsd/Logon.do?method=logon&flag=sess'; }</script>"""
        server.enqueue(html(page).addHeader("Set-Cookie", "sid=active; Path=/jsxsd"))
        server.enqueue(captcha(1))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.QZ_OLD), "student", "secret", events)
        events.next("captcha")
        server.next(); server.next()
        server.enqueue(html(home()))
        server.enqueue(html(home()))
        gateway.submitCaptcha("aB12", events)
        events.next("success")
        val login = server.next()
        assertEquals("/jsxsd/xk/LoginToXk", login.path)
        assertEquals("sid=active", login.getHeader("Cookie"))
        assertEquals("c3R1ZGVudA==%%%c2VjcmV0", fields(login)["encoded"])
        assertEquals("", fields(login)["userPassword"])
        assertEquals("aB12", fields(login)["RANDOMCODE"])
        assertEquals(4, server.requestCount)
    }

    @Test fun oldQzUsesThePagesLogonEndpointAndUserAccountFields() = withGateway(AcademicSystem.QZ_OLD) { server, gateway, events ->
        server.enqueue(html(sanyaForm("/jsxsd/Logon.do?method=logon&flag=sess"))
            .addHeader("Set-Cookie", "sid=sanya; Path=/jsxsd"))
        server.enqueue(captcha(1))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.QZ_OLD), "u", "p", events)
        events.next("captcha")
        server.next(); server.next()
        server.enqueue(MockResponse().setBody("ABCD#22000"))
        server.enqueue(html(home()))
        server.enqueue(html(home()))
        gateway.submitCaptcha("aB12", events)
        events.next("success")
        val handshake = server.next()
        assertEquals("/jsxsd/Logon.do?method=logon&flag=sess", handshake.path)
        assertEquals("POST", handshake.method)
        assertEquals("XMLHttpRequest", handshake.getHeader("X-Requested-With"))
        assertEquals("sid=sanya", handshake.getHeader("Cookie"))
        assertEquals(0L, handshake.bodySize)
        val login = server.next()
        assertEquals("/jsxsd/xk/LoginToXk", login.path)
        assertEquals(mapOf("loginMethod" to "LoginToXk", "userAccount" to "u", "userPassword" to "",
            "encoded" to "uAB%CD%%p", "RANDOMCODE" to "aB12"), fields(login))
    }

    @Test fun oldQzRejectsACrossOriginEncodingEndpointBeforeSendingTheHandshake() = withGateway(AcademicSystem.QZ_OLD) { server, gateway, events ->
        server.enqueue(html(sanyaForm("https://untrusted.invalid/Logon.do?method=logon&flag=sess")))
        server.enqueue(captcha(1))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.QZ_OLD), "student", "secret", events)
        events.next("captcha")
        gateway.submitCaptcha("aB12", events)
        events.next("error")
        assertEquals(2, server.requestCount)
    }

    @Test fun oldQzRejectsMalformedEncodingParametersWithoutSubmittingCredentials() = withGateway(AcademicSystem.QZ_OLD) { server, gateway, events ->
        server.enqueue(html(sanyaForm("Logon.do?method=logon&flag=sess")))
        server.enqueue(captcha(1))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.QZ_OLD), "student", "secret", events)
        events.next("captcha")
        server.enqueue(MockResponse().setBody("ABCD#invalid"))
        gateway.submitCaptcha("aB12", events)
        events.next("error")
        assertEquals(3, server.requestCount)
        server.next(); server.next()
        assertEquals(0L, server.next().bodySize)
    }

    private fun sanyaForm(endpoint: String) = """<form id="loginForm" action="/jsxsd/xk/LoginToXk" method="post">
        <input name="loginMethod" value="LoginToXk" type="hidden">
        <input id="userAccount" name="userAccount"><input id="userPassword" name="userPassword" type="password">
        <input name="RANDOMCODE"><img src="verifycode.servlet"><input name="encoded" type="hidden"></form>
        <script>var strUrl = '$endpoint'; document.getElementById("userPassword").value="";</script>"""

    @Test fun beginningANewLoginDiscardsThePreviousCaptchaAndUsesFreshCookies() = withGateway(AcademicSystem.QZ_OLD) { server, gateway, events ->
        server.enqueue(html(qzForm("previous")).addHeader("Set-Cookie", "sid=previous; Path=/jsxsd"))
        server.enqueue(captcha(1))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.QZ_OLD), "student", "secret", events)
        events.next("captcha")
        server.next(); server.next()
        server.enqueue(html(qzForm("new")).addHeader("Set-Cookie", "sid=new; Path=/jsxsd"))
        server.enqueue(captcha(2))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.QZ_OLD), "student", "replacement", events)
        events.next("captcha")
        assertNull(server.next().getHeader("Cookie"))
        assertEquals("sid=new", server.next().getHeader("Cookie"))
        server.enqueue(html(home()))
        server.enqueue(html(home()))
        gateway.submitCaptcha("aB39", events)
        events.next("success")
        val post = server.next()
        assertEquals("sid=new", post.getHeader("Cookie"))
        assertEquals("new", fields(post)["csrf"])
        assertEquals("c3R1ZGVudA==%%%cmVwbGFjZW1lbnQ=", fields(post)["encoded"])
    }

    @Test fun newQzCaptchaUsesTheSameSessionAndFreshFormTokensAfterAnError() = withGateway(AcademicSystem.QZ) { server, gateway, events ->
        server.enqueue(html(newQzForm("first", "ABCD")).addHeader("Set-Cookie", "sid=new-qz; Path=/jsxsd"))
        server.enqueue(captcha(1))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.QZ), "u", "p", events)
        assertArrayEquals(image(1), events.next("captcha").image)
        assertEquals("/jsxsd/", server.next().path)
        assertEquals("sid=new-qz", server.next().getHeader("Cookie"))
        assertEquals(2, server.requestCount)

        server.enqueue(html(newQzForm("retry", "WXYZ", "验证码不正确，请重新输入")))
        gateway.submitCaptcha("bad", events)
        events.next("invalid-captcha")
        val first = server.next()
        assertEquals("/jsxsd/xk/LoginToXk", first.path)
        assertEquals("sid=new-qz", first.getHeader("Cookie"))
        assertEquals("bad", fields(first)["RANDOMCODE"])
        assertEquals("dABQCD==%%%cA==%%%IA==", fields(first)["encoded"])
        assertEquals("", fields(first)["userPassword"])

        server.enqueue(captcha(2))
        assertArrayEquals(image(2), refreshed(gateway))
        assertEquals("sid=new-qz", server.next().getHeader("Cookie"))
        server.enqueue(html(home()))
        server.enqueue(html(home()))
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        gateway.submitCaptcha("aB39", events)
        events.next("success")
        val retry = server.next()
        assertEquals("sid=new-qz", retry.getHeader("Cookie"))
        assertEquals("retry", fields(retry)["csrf"])
        assertEquals("aB39", fields(retry)["RANDOMCODE"])
        assertEquals("dWXQYZ==%%%cA==%%%IA==", fields(retry)["encoded"])
    }

    @Test fun newQzJsonCaptchaAndPasswordFailuresAreNotReportedAsExpiredSessions() = withGateway(AcademicSystem.QZ) { server, gateway, events ->
        server.enqueue(html(newQzForm("first", "ABCD")))
        server.enqueue(captcha(1))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.QZ), "u", "p", events)
        events.next("captcha")
        server.next(); server.next()
        server.enqueue(MockResponse().setBody("""{"flag1":2,"msgContent":"验证码错误"}"""))
        gateway.submitCaptcha("bad", events)
        events.next("invalid-captcha")
        server.next()
        server.enqueue(MockResponse().setBody("""{"flag1":2,"msgContent":"用户名或密码错误"}"""))
        gateway.submitCaptcha("good", events)
        events.next("invalid-credentials")
        server.next()
        assertEquals(4, server.requestCount)
    }

    @Test fun newQzWithoutCaptchaStillLogsInAndClearingAPendingCaptchaPreventsSubmission() = withGateway(AcademicSystem.QZ) { server, gateway, events ->
        server.enqueue(html(newQzForm("first", "ABCD", withCaptcha = false)))
        server.enqueue(html(home()))
        server.enqueue(html(home()))
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.QZ), "u", "p", events)
        events.next("success")
        repeat(4) { server.next() }
        server.enqueue(html(newQzForm("pending", "WXYZ")))
        server.enqueue(captcha(1))
        gateway.login(AcademicCoreTest.testSchool(server, AcademicSystem.QZ), "u", "p", events)
        events.next("captcha")
        gateway.clearSensitiveState()
        gateway.submitCaptcha("must-not-submit", events)
        events.next("error")
        assertEquals(6, server.requestCount)
    }

    @Test fun invalidConfigurationReportsAnErrorWithoutCrashingTheLoginCoroutine() = withGateway(AcademicSystem.QZ) { server, gateway, events ->
        val school = AcademicCoreTest.testSchool(server, AcademicSystem.QZ).apply { academicSystem = "unknown" }
        gateway.login(school, "u", "p", events)
        events.next("error")
        assertEquals(0, server.requestCount)
    }

    private fun withGateway(system: AcademicSystem, block: (MockWebServer, AcademicPasswordLoginGateway, Events) -> Unit) {
        val server = MockWebServer()
        server.start()
        val gateway = LegacyProtocolFixtures.gateway(AcademicCoreTest.testSchool(server, system))
        try { block(server, gateway, Events()) } finally { gateway.clearSensitiveState(); server.shutdown() }
    }

    private fun refreshed(gateway: AcademicPasswordLoginGateway): ByteArray? {
        val result = LinkedBlockingQueue<Event>()
        gateway.refreshCaptcha { result.offer(Event("refresh", image = it)) }
        return requireNotNull(result.poll(5, TimeUnit.SECONDS)) { "Captcha refresh did not call back" }.image
    }

    private class Event(val kind: String, val image: ByteArray? = null, val cookie: String = "")
    private class Events : PasswordLoginCallback {
        private val events = LinkedBlockingQueue<Event>()
        fun next(kind: String): Event {
            val event = requireNotNull(events.poll(5, TimeUnit.SECONDS)) { "Missing login callback: $kind" }
            assertEquals(kind, event.kind)
            return event
        }
        override fun onSuccess(cookie: String) { events.offer(Event("success", cookie = cookie)) }
        override fun onCaptchaRequired(imageBytes: ByteArray) { events.offer(Event("captcha", imageBytes)) }
        override fun onCaptchaInvalid() { events.offer(Event("invalid-captcha")) }
        override fun onInvalidCredentials() { events.offer(Event("invalid-credentials")) }
        override fun onError(message: String) { events.offer(Event("error")) }
        override fun onWebLoginRequired(message: String) { events.offer(Event("web")) }
    }

    companion object {
        private fun newQzForm(token: String, scode: String, error: String = "", withCaptcha: Boolean = true) = """
            <form method="post" action="/jsxsd/xk/LoginToXk">
                <input name="csrf" type="hidden" value="$token"><input name="userAccount"><input type="password" name="userPassword">
                <input type="hidden" name="encoded"><span id="showMsg">$error</span>
                ${if (withCaptcha) "<input name='RANDOMCODE'><img id='SafeCodeImg' src='/jsxsd/verifycode.servlet'>" else ""}
            </form><script>var scode = '$scode'; var sxh = '2200000';</script>
        """.trimIndent()

        private fun MockWebServer.next(): RecordedRequest = requireNotNull(takeRequest(5, TimeUnit.SECONDS))
        private fun fields(request: RecordedRequest): Map<String, String> = request.body.clone().readUtf8().split('&').associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        private fun html(value: String) = MockResponse().addHeader("Content-Type", "text/html; charset=UTF-8").setBody(value)
        private fun image(version: Int) = "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7".decodeBase64()!!.toByteArray() + version.toByte()
        private fun captcha(version: Int) = MockResponse().addHeader("Content-Type", "image/gif").setBody(Buffer().write(image(version)))
        private fun home() = """<input name="xh" value="student"><input name="xm" value="测试学生"><a href="xsxk.aspx">学生选课</a>"""
        private fun qzForm(token: String, error: String = "", withCaptcha: Boolean = true) = """
            <form method="post" action="/jsxsd/xk/LoginToXk">
              <input name="csrf" type="hidden" value="$token"><input name="userAccount"><input name="userPassword" type="password">
              ${if (withCaptcha) "<input name='RANDOMCODE'><img src='/jsxsd/verifycode.servlet'>" else ""}
              <span id="showMsg">$error</span><input name="encoded" type="hidden">
            </form><script>function submitForm1() { var encoded = encodeInp(userAccount) + '%%%' + encodeInp(userPassword); }</script>
        """.trimIndent()
        private fun rsaKey() = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        private fun zfForm(token: String, key: KeyPair): String {
            val public = key.public as RSAPublicKey
            return """<form action="default2.aspx" method="post">
                <input name="__VIEWSTATE" type="hidden" value="$token"><input name="__EVENTVALIDATION" type="hidden" value="event-$token">
                <input name="txtKeyModulus" id="txtKeyModulus" value="${public.modulus.toString(16)}">
                <input name="txtKeyExponent" id="txtKeyExponent" value="${public.publicExponent.toString(16)}">
                <input name="txtUserName"><input name="TextBox2" type="password"><input name="txtSecretCode">
                <img id="icode" src="CheckCode.aspx?SafeKey=$token">
                <input type="radio" name="RadioButtonList1" value="教师" checked><input type="radio" name="RadioButtonList1" value="学生">
                <input type="submit" name="Button1" value="登录"><input type="submit" name="Button2" value="刷新验证码" style="display:none">
              </form>""".trimIndent()
        }
    }
}

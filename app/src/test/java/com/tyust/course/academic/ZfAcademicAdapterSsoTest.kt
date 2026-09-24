package com.tyust.course.academic

import com.tyust.course.login.PasswordLoginCallback
import com.tyust.course.model.SchoolConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 正方 CAS 统一身份认证（SSO）登录全链路。
 * 用两个 MockWebServer 分别扮演教务系统与 CAS，覆盖:
 * 无验证码直登、验证码提交、密码错误、验证码错误重试、无 SSO 入口回退直登表单。
 */
class ZfAcademicAdapterSsoTest {
    private val modulus = "b1d2af160ebaa47adfef6e4f98a6f1045bd6dc5bfba4bf427c9a653307424d955bf9bbe79a6f5444b7bb04dc8fd4d4c36063d67fbab5c6732b6a2ed6bbf6f45d"

    @Test
    fun ssoLoginWithoutCaptchaBuildsTeachingSessionAndIdentity() {
        withServers { teaching, cas, gateway, events, school ->
            val casBase = cas.url("/cas/login").toString()
            val service = teaching.url("/sso/zfiotlogin").toString()
            // 教务: 探测入口 → 票据交换 → 落地 initMenu → 身份页 → initMenu 学号回退
            teaching.enqueue(redirect(casBase + "?service=" + java.net.URLEncoder.encode(service, "UTF-8")))
            teaching.enqueue(redirect("/xtgl/index_initMenu.html"))
            teaching.enqueue(html(AcademicCoreTest.fixture("zf-initmenu.html")).addHeader("Set-Cookie", "JSESSIONID=jwxt-session; Path=/"))
            teaching.enqueue(html(AcademicCoreTest.fixture("zf-cxyhxx-variant.html")))
            teaching.enqueue(html(AcademicCoreTest.fixture("zf-initmenu.html")))
            // CAS: 登录页 → 公钥 → 验证码关 → POST 账密 → 票据
            cas.enqueue(html(casLoginPage("exec-1")))
            cas.enqueue(html("""{"modulus":"$modulus","exponent":"10001"}"""))
            cas.enqueue(html("false"))
            cas.enqueue(redirect(casBase).addHeader("Set-Cookie", "iPlanetDirectoryPro=cas-sso-token; Path=/cas"))
            cas.enqueue(redirect(teaching.url("/sso/zfiotlogin?ticket=ST-123").toString()))

            gateway.login(school, "student", "secret", events)
            val success = events.next("success")
            assertTrue("教务会话 Cookie 应已导入: ${success.cookie}", success.cookie.contains("JSESSIONID=jwxt-session"))
            assertEquals("张三", gateway.studentName)
            assertEquals("20250001", gateway.studentId)

            assertEquals("/sso/zfiotlogin", teaching.next().path)
            assertEquals("/cas/login", cas.next().path)
            assertEquals("/cas/v2/getPubKey", cas.next().path)
            assertEquals("/cas/v2/getKaptchaStatus", cas.next().path)
            val post = cas.next()
            assertEquals("POST", post.method)
            val fields = fields(post)
            assertEquals("student", fields["username"])
            assertEquals("exec-1", fields["execution"])
            assertEquals("submit", fields["_eventId"])
            assertFalse("密码必须加密后提交", fields["password"] == "secret")
            assertFalse(fields.containsKey("authcode"))
            val serviceRequest = cas.next()
            assertEquals(service, serviceRequest.requestUrl!!.queryParameter("service"))
            assertEquals("/sso/zfiotlogin?ticket=ST-123", teaching.next().path)
            assertEquals("/xtgl/index_initMenu.html", teaching.next().path)
            assertEquals("/xtgl/index_cxYhxxIndex.html?gnmkdm=index", teaching.next().path)
            // CAS 域名已记入白名单（修复 WebView 跳转拦截）
            assertTrue(school.allowedAcademicHosts.contains(cas.hostName + ":" + cas.port))
        }
    }

    @Test
    fun ssoLoginSubmitsCaptchaWhenRequired() {
        withServers { teaching, cas, gateway, events, school ->
            val casBase = cas.url("/cas/login").toString()
            val service = teaching.url("/sso/zfiotlogin").toString()
            teaching.enqueue(redirect(casBase + "?service=" + java.net.URLEncoder.encode(service, "UTF-8")))
            teaching.enqueue(redirect("/xtgl/index_initMenu.html"))
            teaching.enqueue(html(AcademicCoreTest.fixture("zf-initmenu.html")).addHeader("Set-Cookie", "JSESSIONID=jwxt-session; Path=/"))
            teaching.enqueue(html(AcademicCoreTest.fixture("zf-cxyhxx-variant.html")))
            teaching.enqueue(html(AcademicCoreTest.fixture("zf-initmenu.html")))
            cas.enqueue(html(casLoginPage("exec-2")))
            cas.enqueue(html("""{"modulus":"$modulus","exponent":"10001"}"""))
            cas.enqueue(html("true"))
            cas.enqueue(captcha(1))
            cas.enqueue(redirect(casBase).addHeader("Set-Cookie", "iPlanetDirectoryPro=cas-sso-token; Path=/cas"))
            cas.enqueue(redirect(teaching.url("/sso/zfiotlogin?ticket=ST-456").toString()))

            gateway.login(school, "student", "secret", events)
            assertArrayEquals(image(1), events.next("captcha").image)
            teaching.next() // 探测
            cas.next(); cas.next(); cas.next(); cas.next() // 登录页/公钥/验证码开关/验证码图

            gateway.submitCaptcha("8848", events)
            events.next("success")
            val post = cas.next()
            assertEquals("8848", fields(post)["authcode"])
            assertEquals("exec-2", fields(post)["execution"])
            assertEquals("20250001", gateway.studentId)
        }
    }

    @Test
    fun ssoLoginReportsInvalidCredentials() {
        withServers { teaching, cas, gateway, events, school ->
            val casBase = cas.url("/cas/login").toString()
            val service = teaching.url("/sso/zfiotlogin").toString()
            teaching.enqueue(redirect(casBase + "?service=" + java.net.URLEncoder.encode(service, "UTF-8")))
            cas.enqueue(html(casLoginPage("exec-3")))
            cas.enqueue(html("""{"modulus":"$modulus","exponent":"10001"}"""))
            cas.enqueue(html("false"))
            cas.enqueue(html(casLoginPage("exec-4", "用户名或密码不正确")))
            cas.enqueue(html(casLoginPage("exec-4b")))

            gateway.login(school, "student", "wrong-pass", events)
            events.next("invalid-credentials")
            teaching.next()
            cas.next(); cas.next(); cas.next()
            assertEquals("POST", cas.next().method)
            // 失败后刷新 execution 供下次使用
            assertEquals("/cas/login", cas.next().path)
        }
    }

    @Test
    fun ssoLoginWrongCaptchaRefreshesChallengeThenSucceeds() {
        withServers { teaching, cas, gateway, events, school ->
            val casBase = cas.url("/cas/login").toString()
            val service = teaching.url("/sso/zfiotlogin").toString()
            teaching.enqueue(redirect(casBase + "?service=" + java.net.URLEncoder.encode(service, "UTF-8")))
            teaching.enqueue(redirect("/xtgl/index_initMenu.html"))
            teaching.enqueue(html(AcademicCoreTest.fixture("zf-initmenu.html")).addHeader("Set-Cookie", "JSESSIONID=jwxt-session; Path=/"))
            teaching.enqueue(html(AcademicCoreTest.fixture("zf-cxyhxx-variant.html")))
            teaching.enqueue(html(AcademicCoreTest.fixture("zf-initmenu.html")))
            cas.enqueue(html(casLoginPage("exec-5")))
            cas.enqueue(html("""{"modulus":"$modulus","exponent":"10001"}"""))
            cas.enqueue(html("true"))
            cas.enqueue(captcha(1))
            cas.enqueue(html(casLoginPage("exec-6", "验证码不正确，请重新输入")))
            cas.enqueue(html(casLoginPage("exec-7")))
            cas.enqueue(captcha(2))
            cas.enqueue(redirect(casBase).addHeader("Set-Cookie", "iPlanetDirectoryPro=cas-sso-token; Path=/cas"))
            cas.enqueue(redirect(teaching.url("/sso/zfiotlogin?ticket=ST-789").toString()))

            gateway.login(school, "student", "secret", events)
            events.next("captcha")
            teaching.next() // 探测
            cas.next(); cas.next(); cas.next(); cas.next() // 登录页/公钥/开关/验证码图

            gateway.submitCaptcha("bad-code", events)
            events.next("invalid-captcha")
            assertEquals("bad-code", fields(cas.next())["authcode"])
            cas.next() // 拒绝页后刷新 execution
            assertNotNull(refreshed(gateway)) // 新验证码图
            cas.next() // 消费刷新验证码请求

            gateway.submitCaptcha("3951", events)
            events.next("success")
            val accepted = fields(cas.next())
            assertEquals("3951", accepted["authcode"])
            assertEquals("exec-7", accepted["execution"])
        }
    }

    @Test
    fun withoutSsoEntryFallsBackToDirectLoginForm() {
        withServers { teaching, cas, gateway, events, school ->
            // 探测 404 → 无 SSO 入口
            teaching.enqueue(MockResponse().setResponseCode(404))
            teaching.enqueue(html("""
                <form action="/xtgl/login_slogin.html" method="post">
                  <input type="hidden" name="csrftoken" value="csrf-token-1"/>
                  <input type="hidden" name="language" value="zh_CN"/>
                  <input type="hidden" name="mmsfjm" value="0"/>
                  <input name="yhm"/><input name="mm"/>
                </form>
            """.trimIndent()))
            teaching.enqueue(html("1"))
            teaching.enqueue(html(AcademicCoreTest.fixture("zf-initmenu.html")))
            teaching.enqueue(html("""<input name="xh" value="20250009"><input name="xm" value="测试学生">"""))

            gateway.login(school, "student", "secret", events)
            events.next("success")
            assertEquals("20250009", gateway.studentId)
            assertEquals("测试学生", gateway.studentName)
            // 探测请求 → 无 SSO 入口 → 回退直登表单
            assertEquals("/sso/zfiotlogin", teaching.next().path)
            assertTrue(teaching.next().path!!.startsWith("/xtgl/login_slogin.html"))
            val directPost = teaching.next()
            assertEquals("POST", directPost.method)
            assertEquals("student", fields(directPost)["yhm"])
            assertEquals("secret", fields(directPost)["mm"])
        }
    }

    // ---- 基础设施 ----

    private fun withServers(block: (MockWebServer, MockWebServer, AcademicPasswordLoginGateway, Events, SchoolConfig) -> Unit) {
        val teaching = MockWebServer(); teaching.start()
        val cas = MockWebServer(); cas.start()
        val school = SchoolConfig("test", "Test", teaching.url("/").host + ":" + teaching.url("/").port, "http").apply {
            basePath = ""
            academicSystem = AcademicSystem.ZF.id
            allowedAcademicHosts.add(domain)
        }
        val gateway = LegacyProtocolFixtures.gateway(school)
        try {
            block(teaching, cas, gateway, Events(), school)
        } finally {
            gateway.clearSensitiveState()
            AcademicGatewayFactory.invalidate(school, AcademicGatewayFactory.accountKey(school, "student"))
            teaching.shutdown()
            cas.shutdown()
        }
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

    private fun casLoginPage(execution: String, error: String = ""): String = """
        <html><body>
        <form id="fm1" method="post" action="login?v=0.1">
          <input id="username" name="username" type="text"/>
          <input id="password" name="password" type="password"/>
          <input name="authcode" type="text"/>
          <input type="hidden" name="execution" value="$execution"/>
          <input type="hidden" name="_eventId" value="submit"/>
          <span id="errormsg">$error</span>
        </form></body></html>
    """.trimIndent()

    companion object {
        private fun MockWebServer.next(): RecordedRequest = requireNotNull(takeRequest(5, TimeUnit.SECONDS))
        private fun fields(request: RecordedRequest): Map<String, String> = request.body.clone().readUtf8().split('&').associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        private fun html(value: String) = MockResponse().addHeader("Content-Type", "text/html; charset=UTF-8").setBody(value)
        private fun redirect(location: String) = MockResponse().setResponseCode(302).addHeader("Location", location)
        private fun image(version: Int) = "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7".decodeBase64()!!.toByteArray() + version.toByte()
        private fun captcha(version: Int) = MockResponse().addHeader("Content-Type", "image/gif").setBody(Buffer().write(image(version)))
    }
}

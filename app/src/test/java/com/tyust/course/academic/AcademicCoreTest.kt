package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AcademicCoreTest {
    @Test fun oldSchoolJsonKeepsLegacyAliasForPreinstalledProtocolRouting() {
        val school = SchoolConfig.fromJson(JSONObject("""{"id":"existing","domain":"jw.example.edu.cn"}"""))
        assertEquals("legacy_zf", school.academicSystem)
        assertTrue(AcademicGatewayFactory.supports(school))
        school.academicSystem = "qz_old"
        school.pageCharset = "GBK"
        school.allowedAcademicHosts = arrayListOf("sso.example.edu.cn")
        val restored = SchoolConfig.fromJson(school.toJson())
        assertEquals("qz_old", restored.academicSystem)
        assertEquals("GBK", restored.pageCharset)
        assertEquals(school.allowedAcademicHosts, restored.allowedAcademicHosts)
    }

    @Test fun detectorDistinguishesFourLoginPagesAndRejectsGenericJsxsd() {
        for ((fixtureName, expected) in mapOf("zf" to AcademicSystem.ZF, "zf_old" to AcademicSystem.ZF_OLD,
            "qz" to AcademicSystem.QZ, "qz_old" to AcademicSystem.QZ_OLD)) {
            assertEquals(expected, SystemDetector.classify(fixture("$fixtureName-login.html")))
        }
        assertNull(SystemDetector.classify("<a href='/jsxsd/'>Welcome</a>"))
    }

    @Test fun qzEncodingsDoNotMixTheirThreeDifferentInputs() {
        assertEquals("dQ==%%%cA==%%%IA==", LoginEncoding.qzNew("u", "p", "unused", "000"))
        assertEquals("uAB%CD%%p", LoginEncoding.qzOldShift("u", "p", "ABCD", "22000"))
        assertEquals("dQ==%%%cA==", LoginEncoding.qzOldBase64("u", "p"))
        assertEquals("u%%%p", LoginEncoding.qzOldShift("u", "p", "unused", "00000"))
    }

    @Test fun cookiesMatchDomainPathSecureExpiryAndStayAccountScoped() {
        val store = AcademicSessionStore()
        val a = store.session("school", "a", "https://jw.example.edu.cn/jsxsd")
        val b = store.session("school", "b", "https://jw.example.edu.cn/jsxsd")
        val url = "https://jw.example.edu.cn/jsxsd/index".toHttpUrl()
        a.cookies.saveFromResponse(url, listOf(Cookie.parse(url, "sid=A; Path=/jsxsd; Secure")!!,
            Cookie.parse(url, "root=ROOT; Path=/; Secure")!!))
        assertEquals(2, a.cookies.loadForRequest(url).size)
        assertTrue(b.cookies.loadForRequest(url).isEmpty())
        assertTrue(a.cookies.loadForRequest("http://jw.example.edu.cn/jsxsd/index".toHttpUrl()).isEmpty())
        assertEquals(1, a.cookies.loadForRequest("https://jw.example.edu.cn/other".toHttpUrl()).size)
        assertTrue(a.cookies.loadForRequest("https://other.example.edu.cn/jsxsd/index".toHttpUrl()).isEmpty())
        val epoch = a.epoch
        a.invalidate()
        assertNotEquals(epoch, a.epoch)
        assertTrue(a.cookies.loadForRequest(url).isEmpty())
    }

    @Test fun aspNetPostIncludesOnlyClickedSubmitAndCheckedControls() {
        val page = AcademicHtml.parse(fixture("zf_old-form.html"), "https://jw.example.edu.cn/xf_xsqxxxk.aspx")
        val fields = AcademicHtml.formFields(page.selectFirst("form")!!, "Button1" to " Submit ")
        assertTrue(fields.contains("__VIEWSTATE" to "fresh&state"))
        assertTrue(fields.contains("Button1" to " Submit "))
        assertFalse(fields.any { it.first == "Button2" || it.first == "row1" })
        assertTrue(fields.contains("mode" to "2"))
    }

    @Test fun redirectCookiesAreKeptButUnapprovedHostsAreNeverRequested() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val school = testSchool(server, AcademicSystem.QZ)
            val session = AcademicSessionStore().session(school.id, "a", school.fullBasePath)
            val http = AcademicHttpTransport(school, session)
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/jsxsd/home")
                .addHeader("Set-Cookie", "sid=step1; Path=/jsxsd"))
            server.enqueue(MockResponse().setBody("ready"))
            assertEquals("ready", http.get(http.appUrl("start")).text)
            server.takeRequest()
            assertEquals("sid=step1", server.takeRequest().getHeader("Cookie"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://untrusted.invalid/login"))
            try {
                http.get(http.appUrl("start"))
                fail("An unapproved redirect must stop before credentials leave the school")
            } catch (e: AcademicException) { assertEquals(AcademicStatus.UNTRUSTED_URL, e.status) }
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    companion object {
        fun fixture(name: String): String = AcademicCoreTest::class.java.getResource("/academic/$name")!!.readText()
        fun testSchool(server: MockWebServer, system: AcademicSystem): SchoolConfig =
            SchoolConfig("test", "Test", server.url("/").host + ":" + server.url("/").port, "http").apply {
                basePath = "/jsxsd"
                academicSystem = system.id
            }
    }
}

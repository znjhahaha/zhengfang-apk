package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class ZfLoginFailureTest {
    @Test fun rejectedPasswordIsReportedBeforeAnUnauthenticatedMenuRedirect(): Unit = runBlocking {
        MockWebServer().use { server ->
            val form = """<form action="/jwglxt/xtgl/login_slogin.html" method="post"><input name="csrftoken" type="hidden" value="fixture-csrf">
                <input name="mmsfjm" type="hidden" value="0"><input name="yhm"><input name="mm" type="password"></form>"""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/jwglxt/sso/zfiotlogin") -> MockResponse().setResponseCode(404)
                    request.path!!.startsWith("/jwglxt/xtgl/login_slogin.html") && request.method == "POST" ->
                        MockResponse().setBody(form + "<div id='tips'>用户名或密码不正确，请重新输入！</div>")
                    request.path!!.startsWith("/jwglxt/xtgl/login_slogin.html") -> MockResponse().setBody(form)
                    else -> MockResponse().setResponseCode(302).setHeader("Location", "http://untrusted.test/login")
                }
            }
            val address = server.url("/")
            val school = SchoolConfig("fixture-zf", "fixture", "${address.host}:${address.port}", "http").apply {
                academicSystem = AcademicSystem.ZF.id; basePath = "/jwglxt"
            }
            val session = AcademicSession(AcademicSessionKey(school.id, "fixture"), school.fullBasePath)
            try {
                val adapter = ZfAcademicAdapter(school, session, AcademicHttpTransport(school, session))
                val result = adapter.login(Credentials("fictional-student", "fictional-password"))
                assertEquals(AcademicStatus.INVALID_CREDENTIALS, result.status)
                assertEquals(3, server.requestCount)
            } finally { session.retire() }
        }
    }
}

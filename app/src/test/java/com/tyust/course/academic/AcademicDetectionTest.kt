package com.tyust.course.academic

import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class AcademicDetectionTest {
    @Test fun suppliedAddressesPreserveTheirApplicationRoots() {
        val cases = mapOf(
            "http://jwxt.hut.edu.cn/jsxsd/framework/xsMainV.htmlx" to "/jsxsd",
            "http://jw.hljit.edu.cn/default2.aspx" to "",
            "https://jw.educationgroup.cn/gzstzyxy_jsxsd/" to "/gzstzyxy_jsxsd")
        cases.forEach { (url, root) -> assertEquals(root, AcademicAddress.parse(url)!!.basePath) }
        assertNull(AcademicAddress.parse("http://user:password@example.edu.cn/"))
        assertNull(AcademicAddress.parse("https://jw.example.edu.cn:99999/"))
    }

    @Test fun fullUrlAndCustomContextAreDetectedOnFirstRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            listOf("qz-hut-login.html" to AcademicSystem.QZ, "qz-educationgroup-login.html" to AcademicSystem.QZ_OLD).forEach { (fixture, type) ->
                server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=UTF-8").setBody(AcademicCoreTest.fixture(fixture)))
                val result = AcademicDetection.detect(server.url("/jsxsd/framework/xsMainV.htmlx").toString())
                assertEquals(type, result.system)
                assertEquals(if (type == AcademicSystem.QZ) "/jsxsd" else "/gzstzyxy_jsxsd", result.address!!.basePath)
                assertEquals("/jsxsd/framework/xsMainV.htmlx", server.takeRequest().path)
                assertTrue(result.address!!.domain.endsWith(":" + server.port))
            }
        }
    }

    @Test fun redirectRootAndOldAspPageUseTheEffectiveApplicationAddress() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/custom/default2.aspx"))
            server.enqueue(MockResponse().setBody("<form action='default2.aspx'><input name='CheckCode'></form>"))
            val result = AcademicDetection.detect(server.url("/").toString())
            assertEquals(AcademicSystem.ZF_OLD, result.system)
            assertEquals("/custom", result.address!!.basePath)
        }
    }

    @Test fun gbkPagesAreDecodedBeforeClassifyingTheOldZhengfangForm() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val html = "<meta charset='GBK'><title>教务管理系统</title><form action='default2.aspx'><input name='CheckCode'></form>"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=GBK")
                .setBody(okio.Buffer().write(html.toByteArray(charset("GBK")))))
            val result = AcademicDetection.detect(server.url("/default2.aspx").toString())
            assertEquals(AcademicSystem.ZF_OLD, result.system)
            assertEquals("", result.address?.basePath)
        }
    }

    @Test fun invalidUnknownAndUnavailableAreDifferentResults() = runBlocking {
        assertEquals(AcademicDetectionStatus.INVALID_ADDRESS, AcademicDetection.detect("https:///").status)
        MockWebServer().use { server ->
            server.start()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html>Welcome</html>")
            }
            assertEquals(AcademicDetectionStatus.UNKNOWN_SYSTEM, AcademicDetection.detect(server.url("/").toString()).status)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            assertEquals(AcademicDetectionStatus.NETWORK_ERROR, AcademicDetection.detect(server.url("/").toString(), timeoutMillis = 100).status)
            val cancelled = async { AcademicDetection.detect(server.url("/").toString()) }
            delay(40); cancelled.cancelAndJoin()
            assertTrue(cancelled.isCancelled)
        }
    }
}

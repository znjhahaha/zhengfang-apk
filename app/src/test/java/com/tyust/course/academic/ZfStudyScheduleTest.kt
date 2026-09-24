package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ZfStudyScheduleTest {
    private val form = """<form><select name="xnm"><option value="2026" selected>2026-2027</option></select><select name="xqm"><option value="3" selected>1</option></select></form>"""
    private val menu = """<a onclick="clickMenu('N2151','/kbcx/xskbcx_cxXskbcxIndex.html','个人课表查询','null');return false;">课表</a>"""
    private val schedule = """{"kbList":[{"kcmc":"模拟课程","xqj":"2","jcs":"3-4","zcd":"1-16周"}]}"""

    private fun fixture(block: suspend (MockWebServer, AcademicStudyReader) -> Unit): Unit = runBlocking {
        MockWebServer().use { server ->
            val address = server.url("/")
            val school = SchoolConfig("fixture", "模拟学校", "${address.host}:${address.port}", "http").apply {
                academicSystem = AcademicSystem.ZF.id; basePath = "/jwglxt"
            }
            val session = AcademicSession(AcademicSessionKey(school.id, "fixture"), school.fullBasePath)
            try { block(server, AcademicStudyReader(school, session, AcademicHttpTransport(school, session))) }
            finally { session.retire() }
        }
    }

    @Test fun discoversPersonalTimetableOnlyAfterTheDefaultMenuIsDenied() = fixture { server, reader ->
        listOf("\"没有访问权限!\"", menu, form, schedule).forEach { server.enqueue(MockResponse().setBody(it)) }
        assertEquals("2026-2027-1", reader.catalog().currentTerm?.id)
        assertEquals("模拟课程", reader.schedule(AcademicTerm("2026-2027-1")).single().name)
        val requests = (1..4).map { server.takeRequest() }
        assertEquals("/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N253508", requests[0].path)
        assertEquals("/jwglxt/xtgl/index_initMenu.html", requests[1].path)
        assertEquals("/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151", requests[2].path)
        assertEquals("/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151", requests[3].path)
        assertTrue(requests[3].body.readUtf8().contains("kzlx=ck"))
    }

    @Test fun alsoDiscoversWhenThePageLoadsButTheDefaultQueryIsDenied() = fixture { server, reader ->
        listOf(form, "\"没有访问权限!\"", menu, form, schedule).forEach { server.enqueue(MockResponse().setBody(it)) }
        reader.catalog()
        assertEquals(1, reader.schedule(AcademicTerm("2026-2027-1")).size)
        assertEquals(5, server.requestCount)
    }

    @Test fun retainsConfiguredMenuIdentityWhenTheSchoolListsTwoPersonalTimetableEntries() = fixture { server, reader ->
        listOf("\"没有访问权限!\"", menu + menu.replace("N2151", "N253508"), form, schedule)
            .forEach { server.enqueue(MockResponse().setBody(it)) }
        reader.catalog()
        assertEquals(1, reader.schedule(AcademicTerm("2026-2027-1")).size)
        val requests = (1..4).map { server.takeRequest() }
        assertEquals("/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N253508", requests[2].path)
        assertEquals("/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N253508", requests[3].path)
    }

    @Test fun rejectsAmbiguousOrForeignMenuTargets() = fixture { server, reader ->
        for (html in listOf(menu + menu.replace("N2151", "N2152"), menu.replace("/kbcx/", "https://foreign.test/kbcx/"))) {
            server.enqueue(MockResponse().setBody("\"没有访问权限!\"")); server.enqueue(MockResponse().setBody(html))
            try { reader.catalog(); fail("An ambiguous or foreign route must not be queried") }
            catch (error: AcademicException) { assertEquals(AcademicStatus.PAGE_CHANGED, error.status) }
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun doesNotRetryMalformedOrEmptySuccessfulTimetables() = fixture { server, reader ->
        server.enqueue(MockResponse().setBody("{}"))
        try { reader.schedule(AcademicTerm("2026-2027-1")); fail("Unknown data must not become an empty timetable") }
        catch (error: AcademicException) { assertEquals(AcademicStatus.PAGE_CHANGED, error.status) }
        server.enqueue(MockResponse().setBody("{\"kbList\":[]}"))
        assertTrue(reader.schedule(AcademicTerm("2026-2027-1")).isEmpty())
        assertEquals(2, server.requestCount)
    }
}

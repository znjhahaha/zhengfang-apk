package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ZfClosedRoundTest {
    @Test fun currentStageClosedStillAllowsTheSelectedListQuery() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""<html><input id="iskxk" name="iskxk" value="0"><span>对不起，当前不属于选课阶段，如有需要，请与管理员联系！</span></html>"""))
            server.enqueue(MockResponse().setBody("[]").setHeader("Content-Type", "application/json"))
            val origin = server.url("/")
            val school = SchoolConfig("fixture", "fixture", "${origin.host}:${origin.port}", "http").apply {
                basePath = "/jwglxt"; academicSystem = "zf"
            }
            val session = AcademicSession(AcademicSessionKey(school.id, "fixture"), school.fullBasePath)
            try {
                val adapter = ZfAcademicAdapter(school, session, AcademicHttpTransport(school, session))
                val context = adapter.loadCourseContext()
                assertTrue(context.scopes.isEmpty())
                assertTrue(adapter.selected(context).isEmpty())
                assertTrue(server.takeRequest(1, TimeUnit.SECONDS)!!.path!!.contains("zzxkyzb_cxZzxkYzbIndex.html"))
                val selected = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertTrue(selected.path!!.contains("zzxkyzb_cxZzxkYzbChoosedDisplay.html"))
                assertEquals(2, server.requestCount)
            } finally { session.retire() }
        }
    }

    @Test fun anUnknownOrOpenStageIsNotMarkedClosed() {
        assertNotEquals(AcademicStatus.ROUND_CLOSED, AcademicJson.status("<p>当前属于选课阶段</p>"))
        assertNotEquals(AcademicStatus.ROUND_CLOSED, AcademicJson.status("<p>选课页面发生未知错误</p>"))
    }
}

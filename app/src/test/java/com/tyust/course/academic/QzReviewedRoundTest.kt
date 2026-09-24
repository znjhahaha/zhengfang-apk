package com.tyust.course.academic

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class QzReviewedRoundTest {
    private val list = """<a onclick="toxk('fixture-round')">进入选课</a><script>
        function toxk(id) { $.ajax({url: '/jsxsd/xsxk/mzlist.do'}); }
        // 其他轮次未开放，不能用脚本中的提示判定当前入口关闭。
        </script>"""
    private val overview = """<input type="button" value="进入选课" onclick="xsxkOpen('fixture-round','0')">
        <script>function xsxkOpen(jx0502zbid,sfxkxm) { window.open('/jsxsd/xsxk/xsxk_index?jx0502zbid='+jx0502zbid); }</script>"""
    private fun fixture(block: suspend (MockWebServer, QzOldAcademicAdapter) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            val school = AcademicCoreTest.testSchool(server, AcademicSystem.QZ_OLD)
            val session = AcademicSessionStore().session(school.id, "reviewed-round", school.fullBasePath)
            try { block(server, QzOldAcademicAdapter(school, session, AcademicHttpTransport(school, session))) }
            finally { session.retire() }
        }
    }

    @Test fun checksTheDeclarationAndFollowsOnlyTheRenderedMatchingRound() = fixture { server, adapter ->
        listOf(list, """{"success":true,"istc":false}""", overview,
            """<a href='/jsxsd/xsxkkc/getGgxxk'>公共选修</a>""").forEach { server.enqueue(MockResponse().setBody(it)) }
        val scope = adapter.loadCourseContext().scopes.single()
        assertTrue(scope.listUrl.endsWith("/xsxkkc/getGgxxk"))
        assertTrue(scope.params.getValue("roundPage").endsWith("/xsxk/xsxk_index?jx0502zbid=fixture-round"))
        val requests = (1..4).map { server.takeRequest() }
        assertEquals("POST", requests[1].method)
        assertEquals("/jsxsd/xsxk/mzlist.do", requests[1].path)
        assertEquals("/jsxsd/xsxk/xklc_view?jx0502zbid=fixture-round", requests[2].path)
    }

    @Test fun anExplicitClosedRoundReturnsNoCoursesWithoutEnteringAnyMutation() = fixture { server, adapter ->
        listOf(list, """{"success":true,"istc":false}""", overview,
            "<p>当前未开放选课，具体请查看学校选课通知！</p>").forEach { server.enqueue(MockResponse().setBody(it)) }
        assertTrue(adapter.loadCourseContext().scopes.isEmpty())
        assertEquals(4, server.requestCount)
    }

    @Test fun requiredDeclarationsRemainForTheUserToReadAndConfirm() = fixture { server, adapter ->
        server.enqueue(MockResponse().setBody(list))
        server.enqueue(MockResponse().setBody("""{"success":true,"istc":true}"""))
        try { adapter.loadCourseContext(); fail() }
        catch (e: AcademicException) { assertEquals(AcademicStatus.HUMAN_VERIFICATION_REQUIRED, e.status) }
        assertEquals(2, server.requestCount)
    }

    @Test fun aDifferentRoundButtonCannotBeFollowed() = fixture { server, adapter ->
        listOf(list, """{"success":true,"istc":false}""", overview.replace("'fixture-round'", "'other-round'"))
            .forEach { server.enqueue(MockResponse().setBody(it)) }
        try { adapter.loadCourseContext(); fail() }
        catch (e: AcademicException) { assertEquals(AcademicStatus.PAGE_CHANGED, e.status) }
        assertEquals(3, server.requestCount)
    }

    @Test fun aMissingDeclarationResultIsNotTreatedAsConsent() = fixture { server, adapter ->
        server.enqueue(MockResponse().setBody(list))
        server.enqueue(MockResponse().setBody("""{"success":false}"""))
        try { adapter.loadCourseContext(); fail() }
        catch (e: AcademicException) { assertEquals(AcademicStatus.PAGE_CHANGED, e.status) }
        assertEquals(2, server.requestCount)
    }

    @Test fun aDirectOverviewLinkPreservesTheRoundWithoutInventingADeclarationRequest() = fixture { server, adapter ->
        listOf("""<a href='/jsxsd/xsxk/xklc_view?jx0502zbid=fixture-round'>进入选课</a>""",
            overview.replace(",'0'", ""), """<a href='/jsxsd/xsxkkc/comeInXxxk'>选修选课</a>""")
            .forEach { server.enqueue(MockResponse().setBody(it)) }
        val scope = adapter.loadCourseContext().scopes.single()
        assertEquals("fixture-round", scope.params["roundId"])
        assertTrue(scope.listUrl.endsWith("/xsxkkc/comeInXxxk"))
        val requests = (1..3).map { server.takeRequest() }
        assertTrue(requests.all { it.method == "GET" })
        assertEquals("/jsxsd/xsxk/xklc_view?jx0502zbid=fixture-round", requests[1].path)
        assertEquals("/jsxsd/xsxk/xsxk_index?jx0502zbid=fixture-round", requests[2].path)
    }

    @Test fun aDirectOverviewCannotSwitchToADifferentRound() = fixture { server, adapter ->
        listOf("""<a href='/jsxsd/xsxk/xklc_view?jx0502zbid=fixture-round'>进入选课</a>""",
            overview.replace("'fixture-round'", "'other-round'"))
            .forEach { server.enqueue(MockResponse().setBody(it)) }
        try { adapter.loadCourseContext(); fail() }
        catch (e: AcademicException) { assertEquals(AcademicStatus.PAGE_CHANGED, e.status) }
        assertEquals(2, server.requestCount)
    }
}

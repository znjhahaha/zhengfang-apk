package com.tyust.course.academic

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AcademicStudyTest {
    @Test fun separatesNonConsecutivePeriodsAndKeepsTheDayAndWeeks() {
        val result = AcademicStudyParser.jsonSchedule("""{"kbList":[{"kcmc":"课程甲","xqj":"3","jcs":"1-2,5-6","zcd":"1-16周(单)"}]}""")
        assertEquals(listOf(1 to 2, 5 to 6), result.map { it.startPeriod to it.endPeriod })
        assertTrue(result.all { it.day == 3 && it.weeks == "1-16周(单)" })
    }

    @Test fun parsesModernQzScheduleOnceWithoutTooltipDuplicates() {
        val result = AcademicStudyParser.htmlSchedule(qzSchedule)
        assertEquals(1, result.size)
        val course = result.single()
        assertEquals("课程甲", course.name)
        assertEquals("教师甲", course.teacher)
        assertEquals("教室甲", course.location)
        assertEquals(1, course.day)
        assertEquals(1 to 2, course.startPeriod to course.endPeriod)
        assertEquals("1-8周", course.weeks)
    }

    @Test fun parsesOldQzScheduleAndItsWeekNotation() {
        val cell = """<div class="kbcontent">课程乙<br><font title="老师">教师乙</font><br><font title="周次(节次)">1-16(周)[03-04节]</font><br><font title="教室">教室乙</font></div>"""
        val result = AcademicStudyParser.htmlSchedule(grid(cell)).single()
        assertEquals("课程乙", result.name)
        assertEquals("教师乙", result.teacher)
        assertEquals("教室乙", result.location)
        assertEquals("1-16周", result.weeks)
        assertEquals(3 to 4, result.startPeriod to result.endPeriod)
    }

    @Test fun parsesMultipleOldZfCoursesSharingTheSameCell() {
        val cell = "课程甲<br>周一第1,2节{第1-8周}<br>教师甲<br>教室甲<br><br>课程乙<br>周一第1,2节{第9-16周}<br>教师乙<br>教室乙"
        val result = AcademicStudyParser.htmlSchedule(grid(cell))
        assertEquals(listOf("课程甲", "课程乙"), result.map { it.name })
        assertEquals(listOf("1-8周", "9-16周"), result.map { it.weeks })
        assertEquals(listOf("教师甲", "教师乙"), result.map { it.teacher })
    }

    @Test fun followsRowspanColumnsWhenParsingTheSchedule() {
        val html = """<table><tr><th colspan="2">节次</th><th>星期一</th><th>星期二</th></tr>
            <tr><td rowspan="2">上午</td><td>1</td><td></td><td></td></tr>
            <tr><td>2</td><td>课程甲<br>周一第2节{第1-16周}<br>教师甲<br>教室甲</td><td></td></tr></table>"""
        assertEquals(1, AcademicStudyParser.htmlSchedule(html).single().day)
    }

    @Test fun retainsAllWeekRangesAndTheirOddEvenConstraints() {
        val cell = "课程甲<br>周一第1,2节{第1-4周,6-14周(双),15-16周}<br>教师甲<br>教室甲"
        assertEquals("1-4周,6-14周(双),15-16周", AcademicStudyParser.htmlSchedule(grid(cell)).single().weeks)
    }

    @Test fun rejectsMalformedScheduleInsteadOfAnEmptySuccess() {
        try { AcademicStudyParser.htmlSchedule(grid("""<div class="kbcontent">课程甲<br>新格式</div>""")); fail() }
        catch (e: AcademicException) { assertEquals(AcademicStatus.PAGE_CHANGED, e.status) }
        try { AcademicStudyParser.jsonSchedule("""{"kbList":[{"kcmc":"课程甲"}]}"""); fail() }
        catch (e: AcademicException) { assertEquals(AcademicStatus.PAGE_CHANGED, e.status) }
    }

    @Test fun gradeAliasesSkipNullAndKeepDecimalScores() {
        val row = AcademicStudyParser.jsonGrades("""{"data":[{"kc_mc":"课程甲","zcjstr":null,"zcj":"80.0","xf":"2.0","jd":"3.0","xnxqid":"2025-2026-2"}]}""").single()
        assertEquals("80.0", row.score)
        assertEquals("2025-2026-2", row.term)
        val stats = AcademicStudyBridge.stats(AcademicGradeReport(listOf(row)))
        assertEquals(0, stats.excellent)
        assertEquals(1, stats.good)
        assertEquals("3.00", stats.gpa)
        assertEquals("2.0", stats.credits)
    }

    @Test fun gradeComponentsKeepZeroAndUseTheSharedDisplayFormat() {
        val row = AcademicStudyParser.jsonGrades("""{"data":[{"kcmc":"Course","cj":"80","pscj":0,"qmcj":"90","sycj":null,"ksxz":"正常考试"}]}""").single()
        assertEquals("平时: 0 | 期末: 90 | 正常考试", row.detail)
        val html = "<table><tr><th>课程名称</th><th>成绩</th><th>平时成绩</th><th>期末成绩</th><th>实验成绩</th></tr><tr><td>Course</td><td>80</td><td>0</td><td>90</td><td>85</td></tr></table>"
        assertEquals("平时: 0 | 期末: 90 | 实验: 85", AcademicStudyParser.htmlGrades(html, "https://school.example/grades").single().detail)
    }

    @Test fun semesterCatalogUsesOnlyPublishedOptionsAndRecognizesOldZfSummer() {
        val html = """<select name='ctl${'$'}ddlXN'><option value='2025-2026'>2025</option></select><select name='ctl${'$'}ddlXQ'><option value='1'>1</option><option value='2' disabled>2</option><option value='3' selected>3</option></select>"""
        assertEquals(listOf("2025-2026-1", "2025-2026-3"), AcademicStudyParser.terms(html).map { it.id })
        assertEquals("2025-2026-3", AcademicStudyParser.selectedTerm(html)?.id)
        assertTrue(AcademicStudyParser.terms("<select name='xnd'><option value='2025-2026'>2025</option></select>").isEmpty())
    }

    @Test fun oldZfHistoricalGradesAggregatePublishedSemestersWithFreshForms() = runBlocking {
        server(AcademicSystem.ZF_OLD) { server, reader ->
            enqueue(server, "<a href='xscjcx.aspx'>成绩</a>", oldZfForm("catalog"), oldZfForm("second-term"),
                oldZfForm("second-submit").replace("value='1' selected", "value='1'").replace("value='2'", "value='2' selected"), htmlGrades,
                oldZfForm("first-submit"), htmlGrades.replace("<td>2</td>", "<td>1</td>"))
            val grades = reader.grades().grades
            assertEquals(listOf("2025-2026-2", "2025-2026-1"), grades.map { it.term })
            repeat(3) { server.takeRequest() }
            assertTrue(server.takeRequest().body.readUtf8().contains("__VIEWSTATE=second-term"))
            assertTrue(server.takeRequest().body.readUtf8().contains("__VIEWSTATE=second-submit"))
            assertEquals("GET", server.takeRequest().method)
            assertTrue(server.takeRequest().body.readUtf8().contains("__VIEWSTATE=first-submit"))
        }
    }

    @Test fun aSchoolIgnoringTheSemesterQueryCannotYieldACompleteHistoricalReport() = runBlocking {
        server(AcademicSystem.ZF_OLD) { server, reader ->
            val onlyFirst = oldZfForm("first").replace("<option value='2'>2</option>", "")
            enqueue(server, "<a href='xscjcx.aspx'>成绩</a>", onlyFirst, onlyFirst, htmlGrades)
            try { reader.grades(); fail() } catch (e: AcademicException) { assertEquals(AcademicStatus.PAGE_CHANGED, e.status) }
        }
    }

    @Test fun usesSchoolStatisticsAndDefaultsToLatestGradedTerm() {
        val rows = AcademicStudyParser.jsonGrades("""{"data":[
            {"kc_mc":"课程甲","zcjstr":"<span>良好</span>","xf":"2","jd":"3","xnxqid":"2025-2026-1"},
            {"kc_mc":"课程乙","zcjstr":"90","xf":"3","jd":"4","xnxqid":"2025-2026-2"}]}""")
        assertEquals("良好", rows.first().score)
        assertEquals(listOf("2025-2026-2", "2025-2026-1"), AcademicStudyBridge.semesters(rows))
        val stats = AcademicStudyBridge.stats(AcademicGradeReport(rows, "3.76", "115.5"))
        assertEquals("3.76", stats.gpa)
        assertEquals("115.5", stats.credits)
    }

    @Test fun understandsCombinedAndSeparateSemesterSelectors() {
        assertEquals("2026-2027-1", AcademicStudyParser.selectedTerm(qzSchedule)?.id)
        val zf = """<select name="xnm"><option selected value="2025">2025-2026</option></select><select name="xqm"><option value="3">一</option><option value="12" selected>二</option></select>"""
        assertEquals("2025-2026-2", AcademicStudyParser.selectedTerm(zf)?.id)
        assertEquals(2, AcademicStudyParser.terms(zf).size)
        assertEquals("2026-2027-1", AcademicTerm("2025-2026-2").next().id)
    }

    @Test fun readsModernQzGradesWithGetAndAllReportedPages() = runBlocking {
        server(AcademicSystem.QZ) { server, reader ->
            enqueue(server, "<li data-src='/jsxsd/kscj/cjcx_frm'>成绩</li>", modernGradeForm,
                """{"code":0,"count":2,"pjxfjd":"3.5","sxzxf":"15","data":[{"kc_mc":"课程甲","zcj":"80","xnxqid":"2025-2026-1"}]}""",
                """{"code":0,"count":2,"data":[{"kc_mc":"课程乙","zcj":"90","xnxqid":"2025-2026-2"}]}""")
            val result = reader.grades()
            assertEquals(2, result.grades.size)
            assertEquals("3.5", result.gradePointAverage)
            server.takeRequest(); server.takeRequest()
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("GET", first.method)
            assertEquals("1", first.requestUrl!!.queryParameter("pageNum"))
            assertEquals("200", first.requestUrl!!.queryParameter("pageSize"))
            assertEquals("", first.requestUrl!!.queryParameter("kksj"))
            assertEquals("1", first.requestUrl!!.queryParameter("sfxsbcxq"))
            assertEquals("2", second.requestUrl!!.queryParameter("pageNum"))
        }
    }

    @Test fun incompleteGradePaginationIsNotReportedAsComplete() = runBlocking {
        server(AcademicSystem.QZ) { server, reader ->
            enqueue(server, "<li data-src='/jsxsd/kscj/cjcx_frm'>成绩</li>", modernGradeForm,
                """{"code":0,"count":2,"data":[{"kc_mc":"课程甲"}]}""", """{"code":0,"count":2,"data":[]}""")
            try { reader.grades(); fail() } catch (e: AcademicException) { assertEquals(AcademicStatus.PAGE_CHANGED, e.status) }
        }
    }

    @Test fun qzSchedulePreservesFormDefaultsAndFollowsOnlyTheScheduleFrame() = runBlocking {
        server(AcademicSystem.QZ) { server, reader ->
            enqueue(server, "<li data-src='/jsxsd/xskb/xskb_list.do'>课表</li>",
                "<iframe src='/jsxsd/xskb/xskb_list.do?viweType=0'></iframe>", qzSchedule, qzSchedule)
            assertEquals(1, reader.schedule(AcademicTerm("2026-2027-1")).size)
            repeat(3) { server.takeRequest() }
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("0", request.requestUrl!!.queryParameter("viweType"))
            assertEquals("2026-2027-1", request.requestUrl!!.queryParameter("xnxq01id"))
            assertEquals("mode1", request.requestUrl!!.queryParameter("kbjcmsid"))
        }
    }

    @Test fun oldQzGradesFollowTheFrameAndSubmitItsActualListForm() = runBlocking {
        server(AcademicSystem.QZ_OLD) { server, reader ->
            enqueue(server,
                "<li data-url='/kscj/cjcx_frm'>课程成绩</li>",
                "<iframe src='/jsxsd/kscj/cjcx_query'></iframe><iframe src='/jsxsd/kscj/cjcx_list?kksj=2026-2027-1'></iframe>",
                """<form name='kscjQueryForm' method='post'><input type='hidden' name='token' value='fresh'>
                    <select name='kksj'><option selected value='2026-2027-1'>当前学期</option></select></form>
                    <script>document.forms["kscjQueryForm"].action = "/jsxsd/kscj/cjcx_list";</script>""", htmlGrades)
            assertEquals("课程甲", reader.grades(AcademicTerm("2025-2026-2")).grades.single().name)
            server.takeRequest()
            assertEquals("/jsxsd/kscj/cjcx_frm", server.takeRequest().path)
            assertEquals("/jsxsd/kscj/cjcx_query", server.takeRequest().path)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/jsxsd/kscj/cjcx_list", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("kksj=2025-2026-2"))
            assertTrue(body.contains("token=fresh"))
        }
    }

    @Test fun oldQzScheduleUsesTheSemesterFormAfterSsoAndPrintForms() = runBlocking {
        server(AcademicSystem.QZ_OLD) { server, reader ->
            val unrelated = """<form name='loginForm1' method='post' action='/legacy/Logon.do'>
                <input name='ticket' value='sso-only'><input name='useraccount' value='another-service'></form>
                <form name='FormPrint' method='post'><input name='printOnly' value='yes'></form>"""
            val page = unrelated + qzSchedule.replace("<form action", "<form method='post' action")
            enqueue(server, "<a href='/jsxsd/xskb/xskb_list.do'>课表</a>", page, page)
            assertEquals(1, reader.schedule(AcademicTerm("2026-2027-1")).size)
            server.takeRequest(); server.takeRequest()
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/jsxsd/xskb/xskb_list.do?viweType=0", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("xnxq01id=2026-2027-1"))
            assertTrue(body.contains("kbjcmsid=mode1"))
            assertFalse(body.contains("ticket"))
            assertFalse(body.contains("useraccount"))
            assertFalse(body.contains("printOnly"))
        }
    }

    @Test fun ambiguousQzSemesterFormsDoNotSubmitAnyQuery() = runBlocking {
        server(AcademicSystem.QZ_OLD) { server, reader ->
            enqueue(server, "<a href='/jsxsd/xskb/xskb_list.do'>课表</a>", qzSchedule + qzSchedule)
            try { reader.schedule(AcademicTerm("2026-2027-1")); fail() }
            catch (e: AcademicException) { assertEquals(AcademicStatus.PAGE_CHANGED, e.status) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun oldQzGradesUseTheHtmlQueryWithItsHiddenFields() = runBlocking {
        server(AcademicSystem.QZ_OLD) { server, reader ->
            enqueue(server, "<a href='/jsxsd/kscj/cjcx_frm'>成绩</a>",
                "<form action='/jsxsd/kscj/cjcx_query'><input type='hidden' name='token' value='fresh'></form>", htmlGrades)
            assertEquals("课程甲", reader.grades(AcademicTerm("2025-2026-2")).grades.single().name)
            server.takeRequest(); server.takeRequest()
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/jsxsd/kscj/cjcx_query", request.path)
            assertTrue(request.body.readUtf8().contains("token=fresh"))
        }
    }

    @Test fun oldZfSemesterPostbacksRefreshViewstateBeforeEachQuery() = runBlocking {
        server(AcademicSystem.ZF_OLD) { server, reader ->
            enqueue(server, "<a href='xscjcx.aspx?xh=student'>成绩</a>",
                oldZfForm("first").replace("<select name='xnd'>", "<select name='xnd'><option value='2024-2025' selected>2024-2025</option>")
                    .replace("value='2025-2026' selected", "value='2025-2026'"),
                oldZfForm("second"),
                oldZfForm("third").replace("value='1' selected", "value='1'").replace("value='2'", "value='2' selected"), htmlGrades)
            val result = reader.grades(AcademicTerm("2025-2026-2"))
            assertEquals("2025-2026-2", result.grades.single().term)
            server.takeRequest(); server.takeRequest()
            val year = server.takeRequest().body.readUtf8()
            val semester = server.takeRequest().body.readUtf8()
            val submit = server.takeRequest().body.readUtf8()
            assertTrue(year.contains("__VIEWSTATE=first") && year.contains("__EVENTTARGET=xnd"))
            assertTrue(semester.contains("__VIEWSTATE=second") && semester.contains("xqd=2"))
            assertTrue(submit.contains("__VIEWSTATE=third") && submit.contains("btnCx="))
        }
    }

    @Test fun oldZfCurrentScheduleDoesNotPostBackUnchangedSelectors() = runBlocking {
        server(AcademicSystem.ZF_OLD) { server, reader ->
            val page = """<form><select name='xnd'><option value='2026-2027' selected>2026-2027</option></select>
                <select name='xqd'><option value='1' selected>1</option></select></form>""" +
                grid("课程甲<br>专业<br>周一第1,2节{第1-8周}<br>教师甲<br>教室甲")
            enqueue(server, "<a href='xskbcx.aspx'>学生个人课表</a>", page)
            assertEquals(1, reader.schedule(AcademicTerm("2026-2027-1")).size)
            assertEquals(2, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test fun aChangedStudyFormCannotSubmitToAnEnrollmentEndpoint() = runBlocking {
        server(AcademicSystem.ZF_OLD) { server, reader ->
            enqueue(server, "<a href='xscjcx.aspx'>成绩</a>", oldZfForm("fresh").replace("xscjcx.aspx", "xsxk.aspx"))
            try { reader.grades(AcademicTerm("2025-2026-2")); fail() }
            catch (e: AcademicException) { assertEquals(AcademicStatus.PAGE_CHANGED, e.status) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun newZfQueriesKeepTheCustomRootAndCorrectSemesterCode() = runBlocking {
        server(AcademicSystem.ZF) { server, reader ->
            enqueue(server, """{"items":[{"kcmc":"课程甲","cj":"85.0","xnm":"2025","xqm":"12"}],"totalCount":1}""")
            assertEquals("2025-2026-2", reader.grades(AcademicTerm("2025-2026-2")).grades.single().term)
            val request = server.takeRequest()
            assertTrue(request.path!!.startsWith("/jsxsd/cjcx/"))
            assertTrue(request.body.readUtf8().contains("xqm=12"))
        }
    }

    @Test fun aValidEmptyExamListDiffersFromARejectedQuery() {
        assertTrue(AcademicStudyParser.jsonExams("""{"code":0,"data":[],"count":0}""").isEmpty())
        try { AcademicStudyParser.jsonExams("""{"code":500,"data":[],"msg":"请刷新"}"""); fail() }
        catch (e: AcademicException) { assertNotEquals(AcademicStatus.SUCCESS, e.status) }
    }

    private suspend fun server(system: AcademicSystem, block: suspend (MockWebServer, AcademicStudyAdapter) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            val school = AcademicCoreTest.testSchool(server, system)
            val session = AcademicSessionStore().session(school.id, "study", school.fullBasePath)
            block(server, AcademicStudyReader(school, session, AcademicHttpTransport(school, session)))
        } finally { server.shutdown() }
    }

    private fun enqueue(server: MockWebServer, vararg bodies: String) = bodies.forEach { server.enqueue(MockResponse().setBody(it)) }

    companion object {
        private fun grid(cell: String) = "<table><tr><th>节次</th><th>星期一</th><th>星期二</th></tr><tr><td>1-2</td><td>$cell</td><td></td></tr></table>"
        private val qzSchedule = """<form action='/jsxsd/xskb/xskb_list.do?viweType=0'>
            <select name='xnxq01id'><option value='2026-2027-1' selected>2026-2027-1</option></select>
            <select name='kbjcmsid'><option value='mode1' selected>默认</option></select></form>""" + grid("""
            <li class='courselists-item'><div class='qz-hasCourse-title'>课程甲</div>
            <span class='qz-hasCourse-abbrinfo'>老师:教师甲未定义;时间:1-8周[1-2节];地点:教室甲</span>
            <span class='qz-hasCourse-fullinfo'>课程甲 老师:教师甲;时间:1-8周[1-2节];地点:教室甲</span></li>""")
        private val modernGradeForm = """<script src='/assets_newL/js/qzTable.js'></script><form><input name='sfxsbcxq' type='checkbox' checked value='1'></form>"""
        private val htmlGrades = """<table><tr><td>学年</td><td>学期</td><td>课程名称</td><td>成绩</td><td>学分</td><td>绩点</td></tr>
            <tr><td>2025-2026</td><td>2</td><td>课程甲</td><td>80.0</td><td>2</td><td>3</td></tr></table>"""
        private fun oldZfForm(state: String) = """<form action='xscjcx.aspx'><input type='hidden' name='__VIEWSTATE' value='$state'>
            <select name='xnd'><option value='2025-2026' selected>2025-2026</option></select>
            <select name='xqd'><option value='1' selected>1</option><option value='2'>2</option></select>
            <input type='submit' name='btnCx' value='按学期查询'></form>"""
    }
}

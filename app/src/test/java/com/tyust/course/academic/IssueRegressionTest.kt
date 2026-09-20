package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class IssueRegressionTest {
    @Test fun encodedNoCapacityRemainsRetryableAndNeverCountsAsSuccess() {
        for (flag in listOf("-1", "0")) {
            assertEquals(AcademicStatus.NO_CAPACITY, AcademicJson.zfStatus("""{"flag":"$flag","msg":"0,ABC123,0,"}"""))
        }
        assertEquals(AcademicStatus.VALIDATION_FAILED, AcademicJson.zfStatus("""{"flag":"-1","msg":"参数错误"}"""))
        assertEquals(AcademicStatus.RESULT_UNKNOWN, AcademicJson.zfStatus("""{"flag":"0","msg":"0,ABC123,0,"}""", 500))
    }

    private fun school(server: MockWebServer, root: String = "") = SchoolConfig("fixture", "Fixture",
        server.url("/").host + ":" + server.port, "http").apply { basePath = root; academicSystem = "zf" }
    private fun html(body: String) = MockResponse().setHeader("Content-Type", "text/html;charset=UTF-8").setBody(body)
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json;charset=UTF-8").setBody(body)
    private val index = """<input type="hidden" name="xkxnm" value="2026"><input type="hidden" name="xkxqm" value="3">"""

    @Test fun allConfiguredRootsSurviveSerializationAndBothRequestPaths() = runBlocking {
        for (root in listOf("", "/jwglxt", "/custom/academic/")) {
            val server = MockWebServer(); server.start()
            try {
                val saved = school(server, root)
                val config = SchoolConfig.fromJson(saved.toJson())!!
                val prefix = root.trimEnd('/')
                val standard = "/xsxk/zzxkyzb_cxZzxkYzbPartDisplay.html"
                assertEquals(server.url(prefix + standard).toString() + "?gnmkdm=N253512", config.availableCoursesUrl)
                config.courseListPath = prefix + standard
                assertEquals(server.url(prefix + standard).toString() + "?gnmkdm=N253512", config.availableCoursesUrl)
                val session = AcademicSession(AcademicSessionKey("fixture", "account"), config.fullBasePath)
                val adapter = ZfAcademicAdapter(config, session, AcademicHttpTransport(config, session))
                server.enqueue(html(index)); server.enqueue(json("""{"tmpList":[]} """))
                assertTrue(adapter.listCourses(adapter.loadCourseContext(), CourseQuery()).isEmpty())
                assertEquals(prefix + "/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&layout=default", server.takeRequest().path)
                val list = server.takeRequest()
                assertEquals(prefix + standard + "?gnmkdm=N253512", list.path)
                assertEquals("XMLHttpRequest", list.getHeader("X-Requested-With"))
                assertEquals(config.courseReferer, list.getHeader("Referer"))
                assertEquals(config.baseUrl, list.getHeader("Origin"))
            } finally { server.shutdown() }
        }
    }

    @Test fun stableClassIdentityUsesTheFreshOperationTokenForSubmission() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val config = school(server)
            val session = AcademicSession(AcademicSessionKey("fixture", "account"), config.fullBasePath)
            val adapter = ZfAcademicAdapter(config, session, AcademicHttpTransport(config, session))
            val offer = CourseOffer("COURSE", "课程", scopeId = "round", raw = mapOf("sessionEpoch" to session.epoch.toString(), "kklxdm" to "10", "xkkz_id" to "ROUND", "secret" to "discard"))
            server.enqueue(json("""[{"jxb_id":"CLASS","do_jxb_id":"fresh+token/==","jxbmc":"教学班一"}]"""))
            val section = adapter.listSections(offer).single()
            assertEquals("CLASS", section.stableId)
            assertEquals("fresh+token/==", section.selectionId)
            val bridged = AcademicCourseBridge.toCourse(offer.copy(raw = offer.raw + ("academic_system" to "zf")), section)
            assertEquals("CLASS", bridged.classId)
            assertEquals("fresh+token/==", bridged.doJxbId)
            assertEquals("true", bridged.completeParams["academic_stable_section"])
            server.enqueue(json("""{"flag":"1"}"""))
            assertEquals(AcademicStatus.SUCCESS, adapter.select(SelectionTarget(offer, section, true)).status)
            server.takeRequest()
            val submit = server.takeRequest()
            val fields = submit.body.readUtf8().split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            assertEquals("fresh+token/==", fields["jxb_ids"])
            assertEquals("COURSE", fields["kch_id"])
            assertEquals("ROUND", fields["xkkz_id"])
            assertFalse(fields.containsKey("secret")); assertFalse(fields.containsKey("sessionEpoch"))
            assertEquals("XMLHttpRequest", submit.getHeader("X-Requested-With"))
            assertEquals(config.courseReferer, submit.getHeader("Referer"))
            server.enqueue(json("""[{"kch_id":"COURSE","jxb_id":"CLASS","do_jxb_id":"another-token"}]"""))
            assertEquals("CLASS", adapter.selected(CourseContext(session.epoch, emptyList())).single().sectionId)
        } finally { server.shutdown() }
    }

    @Test fun courseOnlyRowsNeverPretendToHaveATeachingClass() {
        val course = AcademicCourseBridge.toCourse(CourseOffer("C", "课程", scopeId = "S", raw = mapOf("academic_system" to "zf")))
        assertEquals("", course.classId); assertEquals("", course.doJxbId)
        assertEquals("false", course.completeParams["academic_stable_section"])
    }

    @Test fun closedRoundStopsBeforeSendingAListRequest() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val config = school(server); val session = AcademicSession(AcademicSessionKey("fixture", "account"), config.fullBasePath)
            val adapter = ZfAcademicAdapter(config, session, AcademicHttpTransport(config, session))
            server.enqueue(html("<p>当前选课未开放</p>"))
            assertTrue(adapter.loadCourseContext().scopes.isEmpty()); assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun emptyListsLoginPagesAndServerErrorsRemainDifferent() {
        assertTrue(ZfResponses.list(AcademicResponse(200, "", "\uFEFF{\"tmpList\":[]}"), "tmpList").isEmpty())
        val responses = listOf(
            AcademicResponse(200, "", "<input type='password'>") to AcademicStatus.SESSION_EXPIRED,
            AcademicResponse(200, "", "<p>当前选课未开放</p>") to AcademicStatus.ROUND_CLOSED,
            AcademicResponse(200, "", "<div class='error_v5'><p class='error_text0 hidden'>系统运行异常，请稍后再试</p></div>") to AcademicStatus.PAGE_CHANGED,
            AcademicResponse(503, "", "Unavailable") to AcademicStatus.NETWORK_RETRYABLE)
        for ((response, expected) in responses) {
            try { ZfResponses.list(response, "tmpList"); fail("Expected $expected") }
            catch (e: AcademicException) {
                assertEquals(expected, e.status)
                assertFalse(e.message.orEmpty().contains("无法识别的列表"))
            }
        }
    }

    private open class FixtureAdapter : AcademicProtocolAdapter {
        var sections = listOf(CourseSection("CLASS", "COURSE", "班一", "教师甲", "周一", raw = mapOf("jxb_id" to "CLASS"), selectionId = "new-token"))
        val writes = mutableListOf<String>()
        override suspend fun login(credentials: Credentials) = LoginResult(AcademicStatus.SUCCESS)
        override suspend fun validateSession() = LoginResult(AcademicStatus.SUCCESS)
        override suspend fun loadCourseContext() = CourseContext(0, listOf(CourseScope("round", "选课")))
        override suspend fun listCourses(context: CourseContext, query: CourseQuery) = listOf(CourseOffer("COURSE", "课程", scopeId = "round", raw = mapOf("academic_system" to "zf")))
        override suspend fun listSections(course: CourseOffer) = sections
        override suspend fun select(target: SelectionTarget): SelectionResult { writes += target.section.selectionId; return SelectionResult(AcademicStatus.SUCCESS) }
        override suspend fun selected(context: CourseContext) = emptyList<SelectedCourse>()
        override suspend fun drop(target: SelectionTarget) = OperationResult(AcademicStatus.SUCCESS)
    }
    private val saved = AcademicGrabItem("account", "fixture", "课程", "教师甲", "星期一", "COURSE", "old-token", "round", useExactMatch = true, sectionName = "班一")

    @Test fun oldTokenRebindsOnlyToOneMatchingClassWhileStableTargetsNeverRebind() = runBlocking {
        val adapter = FixtureAdapter()
        assertTrue(ProtocolGrabRunner(adapter).runOnce(saved, true) is GrabRunEvent.Success)
        assertEquals(listOf("new-token"), adapter.writes)
        adapter.writes.clear()
        assertTrue(ProtocolGrabRunner(adapter).runOnce(saved.copy(sectionIdentityKnown = true), true) is GrabRunEvent.Paused)
        assertTrue(adapter.writes.isEmpty())
        adapter.sections = adapter.sections + adapter.sections[0].copy(stableId = "OTHER", selectionId = "other-token")
        val result = ProtocolGrabRunner(adapter).runOnce(saved, true)
        assertTrue(result is GrabRunEvent.Paused && result.message.contains("多个"))
        assertTrue(adapter.writes.isEmpty())
    }

    @Test fun duplicatesDoNotMakeTheSameClassAmbiguous() = runBlocking {
        val adapter = FixtureAdapter().apply { sections = sections + sections }
        assertTrue(ProtocolGrabRunner(adapter).runOnce(saved.copy(stableSectionId = "CLASS", sectionIdentityKnown = true), true) is GrabRunEvent.Success)
        assertEquals(1, adapter.writes.size)
    }

    @Test fun refreshedTokensDoNotChangeTheSavedClassAndSameNamesDoNotChooseAnotherCourse() = runBlocking {
        val adapter = FixtureAdapter()
        val item = saved.copy(stableSectionId = "CLASS", sectionIdentityKnown = true)
        ProtocolGrabRunner(adapter).runOnce(item, true)
        adapter.sections = adapter.sections.map { it.copy(selectionId = "refreshed-token") }
        ProtocolGrabRunner(adapter).runOnce(item, true)
        assertEquals(listOf("new-token", "refreshed-token"), adapter.writes)
        val ambiguous = object : FixtureAdapter() {
            override suspend fun listCourses(context: CourseContext, query: CourseQuery) =
                super.listCourses(context, query) + CourseOffer("OTHER", "课程", scopeId = "round")
        }
        val result = ProtocolGrabRunner(ambiguous).runOnce(item.copy(stableCourseId = ""), true)
        assertTrue(result is GrabRunEvent.Paused && result.message.contains("同名"))
        assertTrue(ambiguous.writes.isEmpty())
    }

    @Test fun targetOnSecondPageIsFoundWithoutChangingTheCourseOrRound() = runBlocking {
        val starts = mutableListOf<Int>()
        val adapter = object : FixtureAdapter() {
            override suspend fun listCourses(context: CourseContext, query: CourseQuery): List<CourseOffer> {
                starts += query.start
                return if (query.start == 0) (1..50).map { CourseOffer("OTHER-$it", "课程", scopeId = "round") }
                    else super.listCourses(context, query)
            }
        }
        assertTrue(ProtocolGrabRunner(adapter).runOnce(saved.copy(stableSectionId = "CLASS"), true) is GrabRunEvent.Success)
        assertEquals(listOf(0, 50), starts)
    }

    @Test fun fiveCoursesAllGetATurnWithOneOrTwoWorkersAndKeepIndividualLimits() = runTest {
        for (workers in 1..2) {
            val gate = Semaphore(workers)
            val calls = mutableListOf<Int>()
            var active = 0; var maximum = 0
            coroutineScope {
                (1..5).map { id -> launch {
                    val adapter = object : FixtureAdapter() {
                        override suspend fun select(target: SelectionTarget): SelectionResult {
                            calls += id; active++; maximum = maxOf(maximum, active)
                            delay(100); active--
                            return SelectionResult(AcademicStatus.NO_CAPACITY)
                        }
                    }
                    ProtocolGrabRunner(adapter).runUntilDone(saved.copy(stableSectionId = "CLASS"),
                        GrabRunPolicy(500, 3), withAttempt = { attempt -> gate.withPermit { attempt() } }) { }
                } }.joinAll()
            }
            assertEquals(setOf(1,2,3,4,5), calls.take(5).toSet())
            assertEquals((1..5).associateWith { 3 }, calls.groupingBy { it }.eachCount())
            assertTrue(maximum <= workers)
        }
    }

    @Test fun blockingFailureStopsWaitingCoursesBeforeTheyMakeRequests() = runTest {
        val gate = Semaphore(1); var halted = false; var writes = 0
        coroutineScope {
            (1..5).map { launch {
                val adapter = object : FixtureAdapter() {
                    override suspend fun select(target: SelectionTarget): SelectionResult { writes++; return SelectionResult(AcademicStatus.CAPTCHA_REQUIRED) }
                }
                ProtocolGrabRunner(adapter, canContinue = { !halted }).runUntilDone(saved.copy(stableSectionId = "CLASS"),
                    GrabRunPolicy(500, 3), withAttempt = { attempt -> gate.withPermit { attempt() } }) {
                    if (it is GrabRunEvent.Paused) halted = true
                }
            } }.joinAll()
        }
        assertEquals(1, writes)
    }
}

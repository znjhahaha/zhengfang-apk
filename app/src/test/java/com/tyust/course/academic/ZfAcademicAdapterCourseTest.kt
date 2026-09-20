package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 正方新版选课（zzxk）列表获取：
 * 1. 入口页无 queryCourse 调用的 first* 变体（如河北传媒学院）也能构造出分类 scope；
 * 2. 列表请求只提交 zzxkYzb.js requestMap 的字段——全量隐藏域会被这类学校判为异常请求，
 *    返回"教务返回了无法识别的列表"；
 * 3. 既有 queryCourse 入口页行为保持不变。
 */
class ZfAcademicAdapterCourseTest {
    private lateinit var server: MockWebServer
    private lateinit var adapter: ZfAcademicAdapter
    private lateinit var school: SchoolConfig

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        school = SchoolConfig("test", "Test", server.url("/").host + ":" + server.url("/").port, "http").apply {
            basePath = ""
            academicSystem = AcademicSystem.ZF.id
        }
        val session = AcademicSession(AcademicSessionKey("test", "course-account"), school.getFullBasePath())
        adapter = ZfAcademicAdapter(school, session, AcademicHttpTransport(school, session))
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun firstFieldIndexBuildsCategoryScopeAndWhitelistedListRequest() = runBlocking {
        server.enqueue(html(AcademicCoreTest.fixture("zf-xsxk-first-index.html")))
        server.enqueue(html(AcademicCoreTest.fixture("zf-xsxk-first-display.html")))
        server.enqueue(json("""{"flag":"1","tmpList":[
            {"kch_id":"AS0000013","kch":"AS0000013","kcmc":"体育（一）","jxb_id":"5B042FBD95424FC9E0633A00FE0A9AEB",
             "jxbmc":"26-篮球0003","xf":"1.0","yxzrs":"41","kklxdm":"06","kcrow":"1"}],"sfxsjc":"0"}"""))

        val context = adapter.loadCourseContext()
        val scope = context.scopes.singleOrNull() ?: error("expected exactly one scope: ${context.scopes}")
        assertEquals("zf-0-06", scope.id)
        assertEquals("主修课程", scope.name)
        assertEquals("06", scope.params["kklxdm"])
        assertEquals("5B6CA02A897EDFC8E0633A00FE0A6C0E", scope.params["xkkz_id"])
        assertEquals("2026", scope.params["njdm_id"])
        assertEquals("0249", scope.params["zyh_id"])
        assertEquals("249b4a112db99482028f868eedddfa854cf1349249e72c7c9b8d8a78230f47308", scope.params["xkkz_xh"])
        assertEquals("1", scope.params["xklc"])

        // 入口页 GET
        assertEquals("/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&layout=default", server.next().path)
        // Display 请求带 first* 分类参数
        val display = server.next()
        assertEquals("/xsxk/zzxkyzb_cxZzxkYzbDisplay.html?gnmkdm=N253512", display.path)
        val displayFields = fields(display)
        assertEquals("06", displayFields["kklxdm"])
        assertEquals("5B6CA02A897EDFC8E0633A00FE0A6C0E", displayFields["xkkz_id"])
        assertEquals("2026", displayFields["njdm_id"])
        assertEquals("0249", displayFields["zyh_id"])

        val courses = adapter.listCourses(context, CourseQuery())
        assertEquals(1, courses.size)
        val course = courses.single()
        assertEquals("AS0000013", course.stableId)
        assertEquals("体育（一）", course.name)
        assertEquals("1.0", course.credit)
        assertEquals(41, course.selected)

        val list = server.next()
        assertEquals("/xsxk/zzxkyzb_cxZzxkYzbPartDisplay.html?gnmkdm=N253512", list.path)
        val listFields = fields(list)
        // 控制字段齐全
        assertEquals("06", listFields["kklxdm"])
        assertEquals("5B6CA02A897EDFC8E0633A00FE0A6C0E", listFields["xkkz_id"])
        assertEquals("249b4a112db99482028f868eedddfa854cf1349249e72c7c9b8d8a78230f47308", listFields["xkkz_xh"])
        assertEquals("3", listFields["rwlx"])
        assertEquals("1", listFields["xklc"])
        assertEquals("1", listFields["kspage"])
        assertEquals("50", listFields["jspage"])
        // jxbzb 开关开启时才提交
        assertEquals("1", listFields["jxbzb"])
        // 全量隐藏域中的非 requestMap 字段不允许出现
        for (banned in listOf("firstKklxdm", "firstXkkzId", "firstXkkzXh", "isEnd", "kch_id", "filter_list[0]", "csrftoken", "sessionUserKey")) {
            assertFalse("列表请求不允许提交 $banned", listFields.containsKey(banned))
        }
    }

    @Test fun listCoursesForwardsKeywordAsFilterList() = runBlocking {
        server.enqueue(html(AcademicCoreTest.fixture("zf-xsxk-first-index.html")))
        server.enqueue(html(AcademicCoreTest.fixture("zf-xsxk-first-display.html")))
        server.enqueue(json("""{"flag":"1","tmpList":[],"sfxsjc":"0"}"""))
        val context = adapter.loadCourseContext()
        server.next(); server.next() // 入口页 + Display

        adapter.listCourses(context, CourseQuery(keyword = "体育"))
        val list = server.next()
        assertEquals("体育", fields(list)["filter_list[0]"])
    }

    @Test fun listSectionsSendsWhitelistedParamsWithCourseId() = runBlocking {
        server.enqueue(json("""[
            {"jxb_id":"5B042FBD95424FC9E0633A00FE0A9AEB","do_jxb_id":"5B042FBD95424FC9E0633A00FE0A9AEB",
             "jxbmc":"26-篮球0003","kch_id":"AS0000013","kcmc":"体育（一）","xf":"1.0","yxzrs":"41"}]"""))

        val raw = mapOf(
            "kklxdm" to "06", "xkkz_id" to "5B6CA02A897EDFC8E0633A00FE0A6C0E", "xklc" to "1", "rwlx" to "3",
            "xkxnm" to "2026", "xkxqm" to "3", "xkkz_xh" to "249b4a112db99482028f868eedddfa854",
            "firstKklxdm" to "06", "sessionEpoch" to "1",
            "kch_id" to "AS0000013", "kcmc" to "体育（一）", "jxb_id" to "5B042FBD95424FC9E0633A00FE0A9AEB")
        val sections = adapter.listSections(CourseOffer("AS0000013", "体育（一）", scopeId = "zf-0-06", raw = raw))

        assertEquals(1, sections.size)
        assertEquals("5B042FBD95424FC9E0633A00FE0A9AEB", sections.single().stableId)
        val request = server.next()
        assertEquals("/xsxk/zzxkyzbjk_cxJxbWithKchZzxkYzb.html?gnmkdm=N253512", request.path)
        val sent = fields(request)
        assertEquals("AS0000013", sent["kch_id"])
        assertEquals("06", sent["kklxdm"])
        assertFalse(sent.containsKey("kspage"))
        for (banned in listOf("firstKklxdm", "sessionEpoch", "kcmc", "jxb_id")) {
            assertFalse("教学班请求不允许提交 $banned", sent.containsKey(banned))
        }
    }

    @Test fun queryCourseIndexStillParsesCategories() = runBlocking {
        val index = """
            <html><body><form>
            <input type="hidden" name="xkxnm" value="2026"/>
            <input type="hidden" name="xkxqm" value="1"/>
            <input type="hidden" name="jg_id_1" value="JG-1"/>
            </form>
            <script>queryCourse('01','XKKZ-REG','2025','ZY-01');</script>
            </body></html>
        """.trimIndent()
        server.enqueue(html(index))
        server.enqueue(html(AcademicCoreTest.fixture("zf-xsxk-first-display.html")))

        val context = adapter.loadCourseContext()
        val scope = context.scopes.single()
        assertEquals("zf-0-01", scope.id)
        assertEquals("01", scope.params["kklxdm"])
        assertEquals("XKKZ-REG", scope.params["xkkz_id"])
        assertEquals("2025", scope.params["njdm_id"])
        assertEquals("ZY-01", scope.params["zyh_id"])

        server.next() // 入口页
        val display = server.next()
        val displayFields = fields(display)
        assertEquals("01", displayFields["kklxdm"])
        assertEquals("XKKZ-REG", displayFields["xkkz_id"])
    }

    @Test fun selectedAcceptsEmptyRootArray() = runBlocking {
        server.enqueue(json("[]"))
        val context = CourseContext(1, listOf(CourseScope("zf-0-06", "主修课程",
            params = mapOf("kklxdm" to "06", "xkkz_id" to "CONTROL-ID", "xkkz_xh" to "CONTROL-XH",
                "xklc" to "1", "xkxnm" to "2025", "xkxqm" to "12"))))

        val selected = adapter.selected(context)
        assertTrue(selected.isEmpty())
        val request = server.next()
        assertEquals("/xsxk/zzxkyzb_cxZzxkYzbChoosedDisplay.html?gnmkdm=N253512", request.path)
        assertEquals(mapOf("xkxnm" to "2025", "xkxqm" to "12"), fields(request))
    }

    companion object {
        private fun MockWebServer.next(): RecordedRequest = requireNotNull(takeRequest(5, TimeUnit.SECONDS))
        private fun fields(request: RecordedRequest): Map<String, String> = request.body.clone().readUtf8().split('&').filter(String::isNotBlank).associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        private fun html(value: String) = MockResponse().addHeader("Content-Type", "text/html; charset=UTF-8").setBody(value)
        private fun json(value: String) = MockResponse().addHeader("Content-Type", "application/json; charset=UTF-8").setBody(value)
    }
}

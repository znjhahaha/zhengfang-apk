package com.tyust.course.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CourseNameKit 单元测试：
 * 1. 全半角括号归一化（中文输入法全角"（三）"应能匹配教务库半角"(三)"）
 * 2. xkkz 参数名自适应（旧版 xkkz_id / 正方 V9 xkkz_xh）
 */
class CourseNameKitTest {

    // ===== normalizeBrackets =====

    @Test
    fun normalizeBrackets_fullWidthToHalfWidth() {
        assertEquals("大学体育(三)", CourseNameKit.normalizeBrackets("大学体育（三）"))
    }

    @Test
    fun normalizeBrackets_halfWidthUnchanged() {
        assertEquals("大学体育(三)", CourseNameKit.normalizeBrackets("大学体育(三)"))
    }

    @Test
    fun normalizeBrackets_mixedBrackets() {
        assertEquals("(一)(二)", CourseNameKit.normalizeBrackets("（一）（二）"))
        assertEquals("(a)(b))", CourseNameKit.normalizeBrackets("(a)（b）)"))
    }

    @Test
    fun normalizeBrackets_noBracketsUnchanged() {
        assertEquals("高等数学", CourseNameKit.normalizeBrackets("高等数学"))
        assertEquals("C++ 程序设计", CourseNameKit.normalizeBrackets("C++ 程序设计"))
    }

    @Test
    fun normalizeBrackets_emptyAndNull() {
        assertEquals("", CourseNameKit.normalizeBrackets(""))
        assertEquals("", CourseNameKit.normalizeBrackets(null))
    }

    @Test
    fun normalizeBrackets_usedForEquality() {
        // 场景验证：用户全角输入 vs 教务库半角课程名
        val userInput = "大学体育（三）"
        val courseFromServer = "大学体育(三)"
        assertEquals(
            CourseNameKit.normalizeBrackets(courseFromServer),
            CourseNameKit.normalizeBrackets(userInput)
        )
    }

    // ===== detectXkkzKey =====

    @Test
    fun detectXkkzKey_legacySchoolWithFirstXkkzId() {
        val params = mapOf("firstXkkzId" to "abc123", "kklxdm" to "01")
        assertEquals("xkkz_id", CourseNameKit.detectXkkzKey(params))
    }

    @Test
    fun detectXkkzKey_legacySchoolWithXkkzId() {
        val params = mapOf("xkkz_id" to "abc123")
        assertEquals("xkkz_id", CourseNameKit.detectXkkzKey(params))
    }

    @Test
    fun detectXkkzKey_v9SchoolWithFirstXkkzXh() {
        // mnust（正方 V9）：Index 页面 hidden input 为 firstXkkzXh
        val params = mapOf("firstXkkzXh" to "460314b1hash", "firstKklxdm" to "05")
        assertEquals("xkkz_xh", CourseNameKit.detectXkkzKey(params))
    }

    @Test
    fun detectXkkzKey_v9SchoolWithXkkzXh() {
        val params = mapOf("xkkz_xh" to "460314b1hash")
        assertEquals("xkkz_xh", CourseNameKit.detectXkkzKey(params))
    }

    @Test
    fun detectXkkzKey_noXkkzKeys_defaultsToLegacy() {
        assertEquals("xkkz_id", CourseNameKit.detectXkkzKey(mapOf("kklxdm" to "01")))
        assertEquals("xkkz_id", CourseNameKit.detectXkkzKey(emptyMap()))
        assertEquals("xkkz_id", CourseNameKit.detectXkkzKey(null))
    }

    @Test
    fun detectXkkzKey_bothKeysWithValues_legacyWins() {
        // 值优先：旧版参数有有效值时永远走 xkkz_id，即使混入 V9 空字段
        val params = mapOf("firstXkkzId" to "abc123", "xkkz_xh" to "")
        assertEquals("xkkz_id", CourseNameKit.detectXkkzKey(params))
    }

    @Test
    fun detectXkkzKey_v9KeyPresentButBlank_fallsToV9() {
        // V9 学校 Display 响应中 xkkz_xh 字段值常为空，但键存在即应判定为 V9
        val params = mapOf("rwlx" to "1", "xkkz_xh" to "")
        assertEquals("xkkz_xh", CourseNameKit.detectXkkzKey(params))
    }

    // ===== resolveIndexXkkz =====

    @Test
    fun resolveIndexXkkz_prefersFirstXkkzId() {
        val params = mapOf("firstXkkzId" to "id1", "firstXkkzXh" to "xh1", "xkkz_id" to "id2")
        assertEquals("id1", CourseNameKit.resolveIndexXkkz(params))
    }

    @Test
    fun resolveIndexXkkz_fallsBackToFirstXkkzXh() {
        // mnust 场景：Index 只有 firstXkkzXh 有值
        val params = mapOf("firstXkkzXh" to "460314b1hash", "kklxdm" to "05")
        assertEquals("460314b1hash", CourseNameKit.resolveIndexXkkz(params))
    }

    @Test
    fun resolveIndexXkkz_fallsBackThroughAllKeys() {
        assertEquals("id", CourseNameKit.resolveIndexXkkz(mapOf("xkkz_id" to "id")))
        assertEquals("xh", CourseNameKit.resolveIndexXkkz(mapOf("xkkz_xh" to "xh")))
    }

    @Test
    fun resolveIndexXkkz_skipsBlankValues() {
        val params = mapOf("firstXkkzId" to "  ", "firstXkkzXh" to "xh1")
        assertEquals("xh1", CourseNameKit.resolveIndexXkkz(params))
    }

    @Test
    fun resolveIndexXkkz_trimsValue() {
        assertEquals("v", CourseNameKit.resolveIndexXkkz(mapOf("firstXkkzId" to " v ")))
    }

    @Test
    fun resolveIndexXkkz_emptyWhenNothingAvailable() {
        assertEquals("", CourseNameKit.resolveIndexXkkz(emptyMap()))
        assertEquals("", CourseNameKit.resolveIndexXkkz(null))
        assertEquals("", CourseNameKit.resolveIndexXkkz(mapOf("firstXkkzId" to "")))
    }

    // ===== CourseParser 的 xkkz_xh 回退 =====

    @Test
    fun parseCourseList_jsonXkkzXhFallback() {
        // V9 学校课程 JSON 中字段名为 xkkz_xh
        val json = """
            [{"kcmc": "大学体育(三)", "kch_id": "K001", "jxb_id": "J001",
              "jsxm": "张三", "sksj": "周一1-2", "jxdd": "操场",
              "xkkz_xh": "460314b1hash", "kklxdm": "05"}]
        """.trimIndent()

        val courses = CourseParser.parseCourseListFromJson(json)
        assertEquals(1, courses.size)
        assertEquals("460314b1hash", courses[0]._xkkz_id)
        assertEquals("大学体育(三)", courses[0].name)
    }

    @Test
    fun parseCourseList_jsonXkkzIdPreferredOverXh() {
        // 旧版学校 JSON 同时带两字段时 xkkz_id 优先
        val json = """
            [{"kcmc": "高等数学", "kch_id": "K002", "xkkz_id": "legacy", "xkkz_xh": "v9value"}]
        """.trimIndent()

        val courses = CourseParser.parseCourseListFromJson(json)
        assertEquals(1, courses.size)
        assertEquals("legacy", courses[0]._xkkz_id)
    }

    @Test
    fun parseCourseList_formParamsXkkzXhFallback() {
        // V9 学校查询表单参数键为 xkkz_xh，应回退读取
        val json = """[{"kcmc": "大学体育(三)", "kch_id": "K001"}]""".trimIndent()
        val formParams = mapOf("rwlx" to "2", "xklc" to "4", "xkkz_xh" to "hashFromForm")

        val courses = CourseParser.parseCourseListFromJson(json, formParams, null)
        assertEquals(1, courses.size)
        assertEquals("hashFromForm", courses[0]._xkkz_id)
    }

    @Test
    fun parseCourseList_formParamsXkkzIdStillWorks() {
        // 回归：旧版 xkkz_id 行为不变
        val json = """[{"kcmc": "高等数学", "kch_id": "K002"}]""".trimIndent()
        val formParams = mapOf("xkkz_id" to "legacyForm")

        val courses = CourseParser.parseCourseListFromJson(json, formParams, null)
        assertEquals(1, courses.size)
        assertEquals("legacyForm", courses[0]._xkkz_id)
    }

    @Test
    fun parseCourseList_fullWidthNameMatchScenario() {
        // 端到端场景：教务库半角课程名 + 用户全角输入 → 归一化后可匹配
        val json = """
            [{"kcmc": "大学体育(三)", "kch_id": "K001", "xkkz_xh": "hash"}]
        """.trimIndent()
        val courses = CourseParser.parseCourseListFromJson(json)
        val userInput = "大学体育（三）"
        val matched = courses.any {
            CourseNameKit.normalizeBrackets(it.name) == CourseNameKit.normalizeBrackets(userInput)
        }
        assertTrue("全角输入应能匹配半角课程名", matched)
    }
}

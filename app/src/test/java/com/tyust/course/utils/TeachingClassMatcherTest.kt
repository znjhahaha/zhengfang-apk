package com.tyust.course.utils

import com.tyust.course.model.Course
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TeachingClassMatcherTest {
    private val rows get() = JSONArray("""[
        {"jxb_id":"football","do_jxb_id":"enc-football","jxbmc":"足球0003","jsxm":"张老师","sksj":"星期一 1-2节"},
        {"jxb_id":"basketball","do_jxb_id":"enc-basketball","jxbmc":"篮球0003","jsxx":"T001/李老师/讲师","sksj":"星期一 3-4节"},
        {"jxb_id":"basketball-4","do_jxb_id":"enc-basketball-4","jxbmc":"篮球0004","jsxm":"王老师","sksj":"星期二 1-2节"}
    ]""")

    private fun target(section: String = "") = Course().apply {
        name = "体育（一）"; teachingClassFilter = section; jxbmc = section; useExactMatch = false
    }

    @Test fun classNameSelectsTheRequestedClassEvenWhenItIsNotFirst() {
        val selected = TeachingClassMatcher.selectRow(rows, target("篮球0003"))
        assertEquals("enc-basketball", selected?.optString("do_jxb_id"))
    }

    @Test fun missingClassNeverFallsBackToFirstRowOrSavedIds() {
        val target = target("排球0003").apply {
            useExactMatch = true; classId = "football"; doJxbId = "enc-football"
        }
        assertNull(TeachingClassMatcher.selectRow(rows, target))
        assertFalse(TeachingClassMatcher.canUseSavedClass(target))
    }

    @Test fun exactModeStillHonorsTheExplicitClassName() {
        val target = target("篮球0003").apply { useExactMatch = true; classId = "football" }
        assertEquals("basketball", TeachingClassMatcher.selectRow(rows, target)?.optString("jxb_id"))
    }

    @Test fun manualTeacherAndTimeMustAlsoMatchTheClass() {
        val target = target("篮球").apply { teacher = "李老师"; time = "周一 3-4节" }
        assertEquals("basketball", TeachingClassMatcher.selectRow(rows, target)?.optString("jxb_id"))
        target.time = "周二"
        assertNull(TeachingClassMatcher.selectRow(rows, target))
        target.time = ""
        target.teacher = "张老师"
        assertNull(TeachingClassMatcher.selectRow(rows, target))
    }

    @Test fun classNamesNormalizeBracketsAndLetterCase() {
        assertTrue(TeachingClassMatcher.matchesName(
            JSONObject("""{"jxbmc":"Basketball(01)"}"""), "basketball（01）"))
    }

    @Test fun displayNameAloneDoesNotRestrictExistingSmartCourses() {
        val target = target().apply { jxbmc = "篮球0003" }
        assertEquals("football", TeachingClassMatcher.selectRow(rows, target)?.optString("jxb_id"))
        assertTrue(TeachingClassMatcher.canUseSavedClass(target))
        target.useExactMatch = true
        target.classId = "basketball"
        assertEquals("basketball", TeachingClassMatcher.selectRow(rows, target)?.optString("jxb_id"))
    }

    @Test fun missingClassNamesDoNotSatisfyAManualFilter() {
        assertNull(TeachingClassMatcher.selectRow(JSONArray("""[{"jxb_id":"unknown"}]"""), target("篮球")))
        assertNull(TeachingClassMatcher.selectRow(JSONArray(), target("篮球")))
    }

    @Test fun differentClassesHaveIndependentQueueIdentityAndCopiesKeepIt() {
        val basketball = target("篮球0003")
        val football = target("足球0003")
        assertNotEquals(basketball, football)
        assertEquals(2, setOf(basketball, football).size)
        assertEquals(basketball, target("篮球0003"))
        assertNotEquals(basketball.queueStatusKey, football.queueStatusKey)
        val matched = basketball.copy().apply { courseId = "actual-course"; teacher = "李老师"; time = "星期一" }
        assertEquals("篮球0003", matched.teachingClassFilter)
        assertEquals("篮球0003", matched.jxbmc)
        assertEquals(basketball.queueStatusKey, matched.queueStatusKey)
    }

    @Test fun blankClassKeepsExistingStatusKeysAndEquality() {
        assertEquals("体育（一）__", target().queueStatusKey)
        assertEquals(target(), target().apply { teachingClassFilter = null })
        assertEquals(target().hashCode(), target().apply { teachingClassFilter = null }.hashCode())
    }

    @Test fun exactQueueNeverFallsBackToAnotherClassOrASavedOperationToken() {
        val target = target().apply { useExactMatch = true; classId = "gone"; teacher = "张老师" }
        assertNull(TeachingClassMatcher.selectRow(rows, target))
        assertFalse(TeachingClassMatcher.canUseSavedClass(target))
        target.jxbmc = "足球0003"
        assertEquals("football", TeachingClassMatcher.selectRow(rows, target)?.optString("jxb_id"))
        target.completeParams["academic_stable_section"] = "true"
        assertNull(TeachingClassMatcher.selectRow(rows, target))
    }

    @Test fun duplicateRowsAreOneClassButAmbiguousManualExactTargetsStop() {
        val target = target("篮球").apply { useExactMatch = true }
        assertNull(TeachingClassMatcher.selectRow(rows, target))
        target.teachingClassFilter = "篮球0003"
        val duplicates = JSONArray().put(rows.getJSONObject(1)).put(rows.getJSONObject(1))
        assertEquals("basketball", TeachingClassMatcher.selectRow(duplicates, target)?.optString("jxb_id"))
    }
}

package com.tyust.course.academic

import com.tyust.course.model.Course
import com.tyust.course.utils.CourseNameKit
import com.tyust.course.utils.CourseParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ZfSelectionControlTest {
    private val dual = mapOf("xkkz_id" to "ID-A", "xkkz_xh" to "XH-A", "kklxdm" to "05")

    @Test fun sanitizedHarFieldSetsSurviveTheirOwnEndpointAllowlists() {
        val fixture = JSONObject(AcademicCoreTest.fixture("zf-issue10-request-shapes.json"))
        val requests = fixture.getJSONArray("requests")
        for (i in 0 until requests.length()) {
            val request = requests.getJSONObject(i)
            val variants = request.getJSONArray("fieldSets")
            for (j in 0 until variants.length()) {
                val fields = variants.getJSONArray(j)
                val keys = (0 until fields.length()).map(fields::getString)
                val body = keys.joinToString("&") { "$it=fixture" }
                val filtered = ZfRequestParams.filterBody("$body&unrelated_secret=discard", request.getString("path"))
                assertEquals(request.getString("path"), keys.toSet(), filtered.split('&').map { it.substringBefore('=') }.toSet())
            }
        }
    }

    @Test fun endpointContractsMatchBothCaptureVariants() {
        for (source in listOf(dual, dual - "xkkz_id", dual - "xkkz_xh")) {
            val list = ZfRequestParams.build(ZfRequestKind.COURSES, source)
            assertEquals(source.filterKeys { it.startsWith("xkkz_") }, list.filterKeys { it.startsWith("xkkz_") })
            val primary = ZfSelectionControl.from(source)
            for (kind in listOf(ZfRequestKind.DISPLAY, ZfRequestKind.SECTIONS, ZfRequestKind.SELECTION)) {
                val request = ZfRequestParams.build(kind, source)
                assertEquals(primary.primaryValue, request[primary.primaryKey])
                if (primary.id.isNotBlank()) assertFalse(request.containsKey("xkkz_xh"))
            }
            assertEquals(list, ZfRequestParams.build(ZfRequestKind.SECTIONS_LEGACY, source))
        }
        val normalized = ZfRequestParams.filterBody("xkkz_id=ID-A&xkkz_xh=XH-A&kch_id=K1&execution=secret&sessionEpoch=5", "/xsxk/zzxkyzbjk_cxJxbWithKchZzxkYzb.html")
        assertEquals("xkkz_id=ID-A&kch_id=K1", normalized)
    }

    @Test fun categoriesNeverReuseAnotherCategorysFirstXh() {
        val index = mapOf("firstXkkzId" to "ID-A", "firstXkkzXh" to "XH-A", "firstKklxdm" to "05")
        val html = """<a onclick="queryCourse(this,'05','ID-A','2026','M1','XH-A')">A</a>
            <a onclick="queryCourse(this,'06','ID-B','2026','M2','XH-B')">B</a>
            <script>queryCourse('07','ID-C','2026','M3');</script>"""
        val categories = ZfCourseCategories.parse(html, index)
        assertEquals(3, categories.size)
        assertEquals("XH-B", categories[1].merge(index, mapOf("xkkz_xh" to ""))["xkkz_xh"])
        assertFalse(categories[2].merge(index).containsKey("xkkz_xh"))
        assertEquals("XH-C", categories[2].merge(index, mapOf("xkkz_xh" to "XH-C"))["xkkz_xh"])
        assertTrue(categories.all { !it.merge(index).containsKey("firstXkkzXh") })
    }

    @Test fun missingControlHalvesCannotLeakFromAnotherCategoryOrFirstFields() {
        val mixed = dual + mapOf("firstXkkzId" to "ID-B", "firstXkkzXh" to "XH-B")
        assertEquals(ZfSelectionControl("ID-A"), ZfSelectionControl.from(mixed - "xkkz_xh"))
        assertEquals(ZfSelectionControl(xh = "XH-A"), ZfSelectionControl.from(mixed - "xkkz_id"))
        val course = Course().apply { kklxdm = "06"; _xkkz_id = "ID-B" }
        assertEquals(ZfSelectionControl("ID-B"), ZfSelectionControl.forCourse(course, dual))
        course.completeParams.putAll(mapOf("xkkz_id" to "ID-B", "xkkz_xh" to "XH-B"))
        assertEquals(ZfSelectionControl("ID-B", "XH-B"), ZfSelectionControl.forCourse(course, dual))
    }

    @Test fun displayResponsesWithConflictingControlsCannotFillAnotherCategorysMissingHalf() {
        val category = ZfCourseCategory("06", ZfSelectionControl("ID-B"), "2026", "MB")
        val first = mapOf("firstXkkzId" to "ID-A", "firstXkkzXh" to "XH-A")
        assertEquals(ZfSelectionControl("ID-B"), category.control.withReturned(dual))
        assertEquals(ZfSelectionControl("ID-B"), ZfSelectionControl.from(category.merge(first, first)))
        assertEquals(ZfSelectionControl(xh = "XH-B"), ZfSelectionControl(xh = "XH-B").withReturned(dual))
        assertEquals(ZfSelectionControl("ID-B", "XH-B"), category.control.withReturned(mapOf("xkkz_xh" to "XH-B")))
    }

    @Test fun requestFilteringKeepsOnlyTheLastExplicitFieldAndPreservesEncoding() {
        val body = "kch_id=old&kch_id=new%2Bcourse&xkkz_id=ID&xkkz_xh=A%2BB%2FC%3D"
        assertEquals("kch_id=new%2Bcourse&xkkz_id=ID&xkkz_xh=A%2BB%2FC%3D",
            ZfRequestParams.filterBody(body, "/xsxk/zzxkyzb_cxJxbWithKchZzxkYzb.html"))
    }

    @Test fun xhOnlyCategoriesAndFirstFieldsRemainSupported() {
        val index = mapOf("firstXkkzXh" to "XH-FIRST", "firstKklxdm" to "05", "firstNjdmId" to "2026", "firstZyhId" to "M1")
        assertEquals(ZfSelectionControl(xh = "XH-FIRST"), ZfCourseCategories.parse("", index).single().control)
        assertEquals(ZfSelectionControl(xh = "XH-B"), ZfCourseCategories.parse("<a onclick=\"queryCourse(this,'06','XH-B','2026','M2')\">B</a>", index).single().control)
    }

    @Test fun courseParsingCopyAndQueuePersistenceRetainTheIndependentPair() {
        val course = CourseParser.parseCourseListFromJson("""[{"kcmc":"体育","kch_id":"K1"}]""", dual, emptyMap()).single()
        assertEquals("ID-A", course._xkkz_id); assertEquals("XH-A", course._xkkz_xh)
        val copied = course.copy()
        assertEquals("XH-A", copied._xkkz_xh)
        val json = JSONObject().put("_xkkz_id", copied._xkkz_id)
        CourseNameKit.saveControls(json, copied)
        val restored = Course().apply { _xkkz_id = json.getString("_xkkz_id") }
        CourseNameKit.restoreControls(json, restored)
        assertEquals(ZfSelectionControl("ID-A", "XH-A"), ZfSelectionControl.forCourse(restored, emptyMap()))
        val old = Course().apply { _xkkz_id = "unknown-old-value" }
        CourseNameKit.restoreControls(JSONObject().put("_xkkz_id", old._xkkz_id), old)
        assertTrue(ZfSelectionControl.forCourse(old, emptyMap()).isEmpty)
        assertEquals("true", old.completeParams["zf_refresh_controls"])
        ZfSelectionControl.forCourse(old, dual).applyTo(old)
        assertEquals("ID-A", old._xkkz_id); assertEquals("XH-A", old._xkkz_xh)
        assertFalse(old.completeParams.containsKey("zf_refresh_controls"))
    }

    @Test fun legacyRefreshRequiresOneMatchingCategoryAndKeepsTheExactTeachingClass() {
        val index = mapOf("firstXkkzId" to "ID-A", "firstXkkzXh" to "XH-A")
        val html = """<a onclick="queryCourse(this,'05','ID-A','2026','MA','XH-A')"></a>
            <a onclick="queryCourse(this,'06','ID-B','2026','MB','XH-B')"></a>"""
        assertNull(ZfCourseCategories.forSavedCategory(html, index, ""))
        assertNull(ZfCourseCategories.forSavedCategory(html, index, "99"))
        val course = Course().apply {
            kklxdm = "06"; courseId = "saved-course"; classId = "saved-class"; doJxbId = "saved-encrypted-class"
            _xkkz_id = "ambiguous"; completeParams["zf_refresh_controls"] = "true"
        }
        val category = requireNotNull(ZfCourseCategories.forSavedCategory(html, index, course.kklxdm))
        ZfSelectionContext.restore(course, category, index, mapOf("xklc" to "2"))
        assertEquals("ID-B", course._xkkz_id)
        assertEquals("XH-B", course._xkkz_xh)
        assertEquals("saved-class", course.classId)
        assertEquals("saved-encrypted-class", course.doJxbId)
        assertFalse(course.completeParams.containsKey("zf_refresh_controls"))
        assertFalse(course.completeParams.containsKey("firstXkkzXh"))
    }
}

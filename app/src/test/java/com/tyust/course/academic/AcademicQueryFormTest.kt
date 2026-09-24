package com.tyust.course.academic

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class AcademicQueryFormTest {
    private val base = "https://school.test/jsxsd/kscj/cjcx_query"
    private val form = Jsoup.parse("<form name='kscjQueryForm'></form>").selectFirst("form")!!
    private fun action(script: String) = AcademicHtml.queryFormAction(form, script, base)

    @Test fun readsAnAdjacentLocalLiteralWithoutExecutingJavascript() {
        val script = """function queryKscj() {
            var actionUrl = "/jsxsd/kscj/cjcx_list";
            document.forms["kscjQueryForm"].action = actionUrl;
            document.forms["kscjQueryForm"].submit();
        }"""
        assertEquals("https://school.test/jsxsd/kscj/cjcx_list", action(script))
        assertEquals("https://school.test/jsxsd/kscj/cjcx_list", action("""document.forms['kscjQueryForm'].action = '/jsxsd/kscj/cjcx_list';"""))
    }

    @Test fun rejectsExpressionsReassignmentsAndOtherForms() {
        for (script in listOf(
            """var actionUrl = computeUrl(); document.forms['kscjQueryForm'].action = actionUrl;""",
            """var actionUrl = '/first'; actionUrl = '/second'; document.forms['kscjQueryForm'].action = actionUrl;""",
            """var actionUrl = '/first'; document.forms['other'].action = actionUrl;"""
        )) assertNull(action(script))
    }

    @Test fun rejectsMultipleDifferentActionsForTheSameForm() {
        assertNull(action("""document.forms['kscjQueryForm'].action = '/first';
            var queryUrl = '/second'; document.forms['kscjQueryForm'].action = queryUrl;"""))
    }
}

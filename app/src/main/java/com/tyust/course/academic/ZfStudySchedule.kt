package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import org.jsoup.Jsoup

/** Some ZF installations expose the personal timetable under N2151 and cxXsgrkb. */
internal class ZfStudySchedule(
    private val school: SchoolConfig,
    private val http: AcademicHttpTransport,
    private val checked: (AcademicResponse) -> AcademicResponse
) {
    private data class Route(val page: String, val query: String, val menuCode: String, val personal: Boolean = false)
    private var route = Route("kbcx/xskbcx_cxXsKb.html", school.schedulePath, school.scheduleGnmkdm)
    private var discovered = false
    private fun url(path: String) = http.appUrl(path) + "?gnmkdm=${route.menuCode}"

    suspend fun catalogPage(): AcademicResponse {
        val page = checked(http.get(url(route.page)))
        return if (denied(page.text) && !discovered) {
            discover()
            available(checked(http.get(url(route.page))))
        } else available(page)
    }

    suspend fun schedule(fields: Map<String, String>): AcademicResponse {
        suspend fun query(): AcademicResponse {
            val params = if (route.personal) fields + mapOf("kzlx" to "ck", "xsdm" to "", "kclbdm" to "", "kclxdm" to "") else fields
            return checked(http.postForm(url(route.query), params.toList(), url(route.page), ajax = true))
        }
        val response = query()
        return if (denied(response.text) && !discovered) {
            discover()
            available(checked(http.get(url(route.page))))
            available(query())
        } else available(response)
    }

    private suspend fun discover() {
        val menu = checked(http.get(http.appUrl("xtgl/index_initMenu.html")))
        val known = mapOf(
            "/kbcx/xskbcx_cxXskbcxIndex.html" to "kbcx/xskbcx_cxXsgrkb.html",
            "/kbcx/xskbcx_cxXsKb.html" to "kbcx/xskbcx_cxXsKb.html"
        )
        val pattern = Regex("""^\s*clickMenu\(\s*['"](N\d+)['"]\s*,\s*['"]([^'"]+)['"]\s*,""")
        val candidates = Jsoup.parse(menu.text).select("[onclick]").mapNotNull { element ->
            val match = pattern.find(element.attr("onclick")) ?: return@mapNotNull null
            val page = match.groupValues[2]
            val query = known[page] ?: return@mapNotNull null
            Route(page.removePrefix("/"), query, match.groupValues[1], page.endsWith("cxXskbcxIndex.html"))
        }.distinct()
        // Schools can expose the same personal timetable twice. Keep the configured
        // menu identity when it is explicitly present; never guess among other codes.
        val configured = candidates.filter { it.menuCode == school.scheduleGnmkdm }
        route = configured.singleOrNull() ?: candidates.singleOrNull()
            ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "无法确定学校可用的个人课表入口")
        discovered = true
    }

    private fun available(response: AcademicResponse): AcademicResponse {
        if (denied(response.text)) throw AcademicException(AcademicStatus.UNSUPPORTED, "学校未开放当前账号的课表查询权限")
        return response
    }

    private fun denied(body: String): Boolean = body.trim().removeSurrounding("\"")
        .trimEnd('!', '！', '。').trim() in setOf("没有访问权限", "无访问权限")
}

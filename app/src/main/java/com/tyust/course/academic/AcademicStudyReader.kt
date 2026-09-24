package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Calendar

/** Read-only study queries share the login jar and the protocol's session lock. */
internal class AcademicStudyReader(
    private val school: SchoolConfig,
    private val session: AcademicSession,
    private val http: AcademicHttpTransport
) : AcademicStudyAdapter {
    private enum class Page(val label: String, val qz: String, val oldZf: String) {
        SCHEDULE("课表", "xskb/xskb_list.do", "xskbcx.aspx"),
        GRADES("成绩", "kscj/cjcx_frm", "xscjcx.aspx"),
        EXAMS("考试", "xsks/xsksap_query", "xskscx.aspx")
    }

    private var menu: AcademicResponse? = null
    private val zfSchedule by lazy { ZfStudySchedule(school, http, ::checked) }

    override suspend fun catalog(): AcademicStudyCatalog = session.withProtocolLock {
        val page = when (school.academicType()) {
            AcademicSystem.ZF -> zfSchedule.catalogPage()
            AcademicSystem.ZF_OLD -> studyPage(Page.SCHEDULE)
            else -> qzSchedulePage()
        }
        val terms = AcademicStudyParser.terms(page.text)
        val current = AcademicStudyParser.selectedTerm(page.text) ?: calendarTerm()
        AcademicStudyCatalog((terms + current).distinctBy { it.id }.sortedByDescending { it.id }, current)
    }

    override suspend fun schedule(term: AcademicTerm): List<AcademicScheduleEntry> = session.withProtocolLock {
        when (school.academicType()) {
            AcademicSystem.ZF -> {
                val response = zfSchedule.schedule(zfTerm(term))
                AcademicStudyParser.jsonSchedule(response.text)
            }
            AcademicSystem.ZF_OLD -> AcademicStudyParser.htmlSchedule(oldZfQuery(Page.SCHEDULE, term).text)
            else -> {
                val page = qzSchedulePage()
                val form = qzScheduleForm(page)
                val fields = (form?.let { AcademicHtml.formFields(it).toMap().toMutableMap() } ?: mutableMapOf()).apply {
                    put("xnxq01id", term.id)
                    put("zc", "")
                }
                val url = form?.let { AcademicHtml.action(it, page.url) } ?: page.url
                requireStudyUrl(url, Page.SCHEDULE)
                val response = checked(if (form?.attr("method").equals("post", true)) http.postForm(url, fields.toList(), page.url)
                    else http.get(queryUrl(url, fields), page.url))
                val returnedTerm = AcademicStudyParser.selectedTerm(response.text)
                if (returnedTerm != null && returnedTerm.id != term.id)
                    throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校未返回所选学期的课表，请刷新重试")
                AcademicStudyParser.htmlSchedule(response.text)
            }
        }
    }

    override suspend fun grades(term: AcademicTerm?): AcademicGradeReport = session.withProtocolLock {
        when (school.academicType()) {
            AcademicSystem.ZF -> {
                val url = http.appUrl(school.gradesPath) + "?doType=query&gnmkdm=${school.gradeGnmkdm}"
                val (rows, _) = jsonPages { page ->
                    http.postForm(url, (zfTerm(term) + mapOf(
                        "queryModel.showCount" to PAGE_SIZE.toString(), "queryModel.currentPage" to page.toString(),
                        "queryModel.sortName" to "", "queryModel.sortOrder" to "asc", "time" to "0"
                    )).toList(), ajax = true)
                }
                AcademicGradeReport(AcademicStudyParser.jsonGrades(asList(rows)))
            }
            AcademicSystem.ZF_OLD -> oldZfGrades(term)
            else -> {
                val page = studyPage(Page.GRADES)
                val fields = formFields(page).apply {
                    put("kksj", term?.id.orEmpty())
                    put("kcmc", ""); put("kcxz", ""); put("kcsx", "")
                    put("xsfs", "all")
                }
                if (isModernQz(page)) {
                    val (rows, summary) = jsonPages { number ->
                        http.get(queryUrl(http.appUrl("kscj/cjcx_list"), fields + mapOf(
                            "pageNum" to number.toString(), "pageSize" to PAGE_SIZE.toString()
                        )), page.url, ajax = true)
                    }
                    AcademicGradeReport(AcademicStudyParser.jsonGrades(asList(rows)),
                        AcademicJson.string(summary, "pjxfjd"), AcademicJson.string(summary, "sxzxf"))
                } else {
                    val response = oldQzGrades(page, term)
                    AcademicGradeReport(AcademicStudyParser.htmlGrades(response.text, response.url))
                }
            }
        }
    }

    override suspend fun exams(term: AcademicTerm): List<AcademicExam> = session.withProtocolLock {
        when (school.academicType()) {
            AcademicSystem.ZF -> {
                val url = http.appUrl("kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105")
                val (rows, _) = jsonPages { page ->
                    http.postForm(url, (zfTerm(term) + mapOf("queryModel.showCount" to PAGE_SIZE.toString(),
                        "queryModel.currentPage" to page.toString())).toList(), ajax = true)
                }
                AcademicStudyParser.jsonExams(asList(rows))
            }
            AcademicSystem.ZF_OLD -> {
                val page = oldZfQuery(Page.EXAMS, term)
                AcademicStudyParser.htmlExams(page.text, page.url)
            }
            else -> {
                val page = studyPage(Page.EXAMS)
                val fields = formFields(page).apply { put("xnxqid", term.id); put("xqlb", "") }
                val url = http.appUrl("xsks/xsksap_list")
                if (isModernQz(page)) {
                    val (rows, _) = jsonPages { number ->
                        http.get(queryUrl(url, fields + mapOf("pageNum" to number.toString(), "pageSize" to PAGE_SIZE.toString())), page.url, ajax = true)
                    }
                    AcademicStudyParser.jsonExams(asList(rows))
                } else {
                    val response = checked(http.postForm(url, fields.toList(), page.url))
                    AcademicStudyParser.htmlExams(response.text, response.url)
                }
            }
        }
    }

    private suspend fun studyPage(kind: Page): AcademicResponse {
        val home = menu ?: checked(http.get(http.appUrl(when (school.academicType()) {
            AcademicSystem.ZF_OLD -> queryUrl(http.appUrl("xs_main.aspx"), mapOf("xh" to session.username))
            AcademicSystem.QZ_OLD -> "framework/xsMain.jsp"
            else -> "framework/xsMainV.htmlx"
        }))).also { menu = it }
        val document = Jsoup.parse(home.text, home.url)
        val pathName = if (school.academicType() == AcademicSystem.ZF_OLD) kind.oldZf else kind.qz
        val discovered = document.select("a[href], [data-src], [data-url]").firstNotNullOfOrNull { element ->
            val href = element.attr("data-url").ifBlank { element.attr("data-src") }.ifBlank { element.attr("href") }
            if (!href.substringBefore('?').endsWith(pathName, ignoreCase = true)) null
            else if (element.hasAttr("data-url") && href.startsWith("/") && !href.startsWith(school.basePath.trimEnd('/') + "/")) http.appUrl(href)
            else URI(home.url).resolve(href).toString()
        }
        val url = discovered ?: if (school.academicType() == AcademicSystem.ZF_OLD) {
            throw AcademicException(AcademicStatus.UNSUPPORTED, "学校未提供${kind.label}查询入口")
        } else http.appUrl(kind.qz)
        requireStudyUrl(url, kind)
        return checked(http.get(url, home.url))
    }

    private suspend fun oldQzGrades(wrapper: AcademicResponse, term: AcademicTerm?): AcademicResponse {
        val frame = Jsoup.parse(wrapper.text, wrapper.url).select("iframe[src]").firstOrNull {
            it.attr("src").substringBefore('?').endsWith("/kscj/cjcx_query")
        }
        val page = if (frame != null) checked(http.get(frame.absUrl("src"), wrapper.url)) else wrapper
        val document = Jsoup.parse(page.text, page.url)
        val form = document.selectFirst("form[name=kscjQueryForm]") ?: document.selectFirst("form")
            ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "未找到学校成绩查询表单")
        val url = AcademicHtml.queryFormAction(form, page.text, page.url)
            ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校成绩查询地址已变化")
        if (URI(url).path.let { !it.endsWith("/kscj/cjcx_list") && !it.endsWith("/kscj/cjcx_query") })
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校成绩查询地址已变化")
        val fields = AcademicHtml.formFields(form).toMap().toMutableMap().apply {
            put("kksj", term?.id.orEmpty())
            put("kcmc", ""); put("kcxz", ""); put("kcsx", ""); put("xsfs", "all")
        }
        return checked(http.postForm(url, fields.toList(), page.url))
    }

    private suspend fun qzSchedulePage(): AcademicResponse {
        var page = studyPage(Page.SCHEDULE)
        repeat(3) {
            val frame = Jsoup.parse(page.text, page.url).select("iframe[src]").firstOrNull {
                it.attr("src").substringBefore('?').endsWith("xskb_list.do")
            } ?: return page
            val url = frame.absUrl("src")
            requireStudyUrl(url, Page.SCHEDULE)
            page = checked(http.get(url, page.url))
        }
        throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校课表页面嵌套异常")
    }

    /** WebForms queries always obtain fresh hidden fields and submit only known query controls. */
    private suspend fun oldZfGrades(term: AcademicTerm?): AcademicGradeReport {
        val initial = studyPage(Page.GRADES)
        if (term != null || gradeQueryButton(initial, historical = true) != null) {
            val page = oldZfQuery(Page.GRADES, term, initial)
            return AcademicGradeReport(AcademicStudyParser.htmlGrades(page.text, page.url).map {
                if (it.term.isBlank() && term != null) it.copy(term = term.id) else it
            })
        }
        val terms = AcademicStudyParser.terms(initial.text).sortedByDescending { it.id }
        if (terms.isEmpty() || gradeQueryButton(initial, historical = false) == null)
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "未识别到历年成绩入口或可汇总的学期查询，请核对学校成绩页面")
        if (terms.size > 100) throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校返回的成绩学期过多，暂时无法完整汇总")
        val grades = mutableListOf<AcademicGrade>()
        // Each semester starts with a fresh form; ASP.NET viewstate is not reusable across requests.
        for (selectedTerm in terms) {
            val page = oldZfQuery(Page.GRADES, selectedTerm)
            val rows = AcademicStudyParser.htmlGrades(page.text, page.url)
            if (rows.any { it.term.isNotBlank() && it.term != selectedTerm.id })
                throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校返回的成绩学期与查询不一致，历年成绩尚未完整汇总")
            grades += rows.map { if (it.term.isBlank()) it.copy(term = selectedTerm.id) else it }
        }
        return AcademicGradeReport(grades.distinct())
    }

    private fun gradeQueryButton(page: AcademicResponse, historical: Boolean): Element? {
        val labels = if (historical) setOf("历年成绩", "在校学习成绩查询", "查询历年成绩")
            else setOf("按学期查询", "学期成绩", "查询", "成绩查询")
        return Jsoup.parse(page.text, page.url).selectFirst("form")?.select("input[type=submit],button")?.firstOrNull {
            it.attr("value").ifBlank { it.text() }.trim() in labels && !it.hasAttr("disabled")
        }
    }

    private suspend fun oldZfQuery(kind: Page, term: AcademicTerm?, initial: AcademicResponse? = null): AcademicResponse {
        var page = initial ?: studyPage(kind)
        fun form() = Jsoup.parse(page.text, page.url).selectFirst("form")
            ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "未找到${kind.label}查询表单")
        if (term != null) {
            for ((names, wanted) in listOf(setOf("xnd", "ddlXN", "xn") to "${term.year}-${term.year + 1}",
                setOf("xqd", "ddlXQ", "xq") to term.semester.toString())) {
                val current = form()
                val select = current.select("select[name]").firstOrNull { it.attr("name").substringAfterLast('$') in names }
                    ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校缺少学年或学期选项")
                val option = select.select("option").firstOrNull { it.attr("value") == wanted || it.text().trim() == wanted }
                    ?: throw AcademicException(AcademicStatus.UNSUPPORTED, "学校尚未开放该学期的${kind.label}")
                val selected = select.selectFirst("option[selected]") ?: select.selectFirst("option")
                if (selected?.attr("value") == option.attr("value")) continue
                val fields = AcademicHtml.formFields(current).toMap().toMutableMap()
                fields[select.attr("name")] = option.attr("value")
                fields["__EVENTTARGET"] = select.attr("name")
                fields["__EVENTARGUMENT"] = ""
                page = submitStudyForm(page, current, fields, kind)
            }
        }
        if (kind != Page.SCHEDULE) {
            val current = form()
            val labels = if (kind == Page.GRADES && term == null) setOf("历年成绩", "在校学习成绩查询", "查询历年成绩")
                else if (kind == Page.GRADES) setOf("按学期查询", "学期成绩", "查询", "成绩查询")
                else setOf("查询", "考试查询", "确定")
            val button = current.select("input[type=submit], button").firstOrNull {
                it.attr("value").ifBlank { it.text() }.trim() in labels && !it.hasAttr("disabled")
            }
            if (button != null) {
                val fields = AcademicHtml.formFields(current, button.attr("name") to button.attr("value")).toMap().toMutableMap()
                fields["__EVENTTARGET"] = ""; fields["__EVENTARGUMENT"] = ""
                page = submitStudyForm(page, current, fields, kind)
            } else if (kind == Page.GRADES && term == null) {
                throw AcademicException(AcademicStatus.PAGE_CHANGED, "未识别到学校历年成绩查询按钮")
            }
        }
        return page
    }

    private suspend fun submitStudyForm(page: AcademicResponse, form: Element, fields: Map<String, String>, kind: Page): AcademicResponse {
        val url = AcademicHtml.action(form, page.url)
        requireStudyUrl(url, kind)
        return checked(http.postForm(url, fields.toList(), page.url))
    }

    private fun requireStudyUrl(url: String, kind: Page) {
        val path = URI(url).path
        val accepted = if (school.academicType() == AcademicSystem.ZF_OLD) path.endsWith("/" + kind.oldZf, true)
            else path.endsWith("/" + kind.qz, true)
        if (!accepted) throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校${kind.label}入口已变化")
    }

    private fun formFields(page: AcademicResponse): MutableMap<String, String> =
        Jsoup.parse(page.text, page.url).selectFirst("form")?.let { AcademicHtml.formFields(it).toMap().toMutableMap() } ?: mutableMapOf()

    private fun qzScheduleForm(page: AcademicResponse): Element? {
        val forms = Jsoup.parse(page.text, page.url).select("form")
        // Legacy pages may prepend an SSO form and a print form to the timetable.
        // Only the semester form owns the query defaults and submission method.
        val candidates = forms.filter { it.selectFirst("[name=xnxq01id]") != null }
        return when {
            candidates.size == 1 -> candidates.single()
            candidates.isEmpty() && forms.size <= 1 -> forms.firstOrNull()
            else -> throw AcademicException(AcademicStatus.PAGE_CHANGED, "无法确定学校课表查询表单")
        }
    }

    private fun isModernQz(page: AcademicResponse) = page.text.contains("qzTable") || page.text.contains("/assets_newL/")

    private fun checked(page: AcademicResponse): AcademicResponse {
        if (AcademicHtml.isLoginPage(page.text)) throw AcademicException(AcademicStatus.SESSION_EXPIRED, "登录已失效，请重新登录")
        if (page.code !in 200..299) throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校查询页面暂不可用")
        return page
    }

    private suspend fun jsonPages(request: suspend (Int) -> AcademicResponse): Pair<List<JSONObject>, JSONObject> {
        val result = mutableListOf<JSONObject>()
        val seenPages = mutableSetOf<String>()
        var summary = JSONObject()
        for (page in 1..100) {
            val response = checked(request(page))
            val rows = AcademicJson.objects(response.text, "items", "data")
            val root = runCatching { JSONObject(response.text) }.getOrDefault(JSONObject())
            if (page == 1) summary = root
            val total = AcademicJson.int(root, "count", "totalCount", "totalResult", "records")
                ?: if (school.academicType() != AcademicSystem.ZF) AcademicJson.int(root, "total") else null
            if (rows.isNotEmpty() && !seenPages.add(rows.joinToString { it.toString() }))
                throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校重复返回同一页，请稍后重试")
            result += rows
            if (total != null && result.size >= total) return result to summary
            if (rows.isEmpty()) {
                if (total != null && result.size < total) throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校返回的查询数据不完整，请刷新重试")
                return result to summary
            }
            if (total == null && rows.size < PAGE_SIZE) return result to summary
        }
        throw AcademicException(AcademicStatus.PAGE_CHANGED, "查询页数超过上限，请按学期查询")
    }

    private fun zfTerm(term: AcademicTerm?) = mapOf("xnm" to term?.year?.toString().orEmpty(),
        "xqm" to when (term?.semester) { 1 -> "3"; 2 -> "12"; 3 -> "16"; else -> "" })

    private fun queryUrl(url: String, fields: Map<String, String>): String = url.toHttpUrl().newBuilder().apply {
        fields.forEach { (name, value) -> setQueryParameter(name, value) }
    }.build().toString()

    private fun asList(rows: List<JSONObject>) = JSONObject().put("items", JSONArray(rows)).toString()

    companion object {
        private const val PAGE_SIZE = 200
        internal fun calendarTerm(): AcademicTerm {
            val now = Calendar.getInstance()
            val year = now.get(Calendar.YEAR)
            val month = now.get(Calendar.MONTH)
            val start = if (month >= Calendar.AUGUST) year else year - 1
            val semester = if (month >= Calendar.AUGUST || month < Calendar.FEBRUARY) 1 else 2
            return AcademicTerm("$start-${start + 1}-$semester")
        }
    }
}

package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl

internal open class QzAcademicAdapter(
    school: SchoolConfig, session: AcademicSession, transport: AcademicHttpTransport,
    system: AcademicSystem = AcademicSystem.QZ
) : BaseAcademicAdapter(school, session, transport, system), AcademicCaptchaLogin {
    @Volatile private var loginAttempt: AcademicLoginAttempt? = null
    @Volatile private var casLogin: QzCasSsoClient? = null
    private var casEpoch: Long = 0

    override suspend fun login(credentials: Credentials): LoginResult = serial {
        clearLoginState()
        session.invalidate()
        session.username = credentials.username
        QzCasEntry.forSchool(school)?.let { entry ->
            val cas = QzCasSsoClient(entry)
            casLogin = cas; casEpoch = session.epoch
            if (!school.allowedAcademicHosts.contains(entry.login.host)) school.allowedAcademicHosts.add(entry.login.host)
            return@serial finishCas(cas, cas.begin(credentials))
        }
        val page = transport.get(transport.appUrl(""))
        val form = AcademicLoginHtml.form(page)
            ?: return@serial LoginResult(AcademicStatus.HUMAN_VERIFICATION_REQUIRED, message = "请在教务网页完成登录验证")
        val scode = AcademicHtml.firstScriptValue(page.text, "scode")
        val sxh = AcademicHtml.firstScriptValue(page.text, "sxh")
        if (scode.isNullOrBlank() || sxh.isNullOrBlank())
            return@serial LoginResult(AcademicStatus.HUMAN_VERIFICATION_REQUIRED, message = "请在教务网页完成登录验证")

        val attempt = AcademicLoginAttempt(credentials, page, session.epoch)
        loginAttempt = attempt
        val captcha = AcademicLoginHtml.captcha(form, "RANDOMCODE")
        if (captcha != null) LoginResult(AcademicStatus.CAPTCHA_REQUIRED, captcha = captcha.load(transport, page.url))
        else submitLogin(attempt, "")
    }

    override suspend fun submitCaptcha(code: String): LoginResult = serial {
        casLogin?.let { return@serial finishCas(it, it.submit(code.trim())) }
        val attempt = currentLoginAttempt()
            ?: return@serial LoginResult(AcademicStatus.SESSION_EXPIRED, message = "登录会话已失效，请重新登录")
        if (code.isBlank()) return@serial LoginResult(AcademicStatus.CAPTCHA_REQUIRED, message = "请输入验证码")
        submitLogin(attempt, code.trim())
    }

    override suspend fun refreshCaptcha(): CaptchaChallenge? = serial {
        casLogin?.let { return@serial it.refreshCaptcha() }
        val attempt = currentLoginAttempt() ?: return@serial null
        val form = AcademicLoginHtml.form(attempt.page) ?: return@serial null
        AcademicLoginHtml.captcha(form, "RANDOMCODE")?.load(transport, attempt.page.url, refresh = true)
    }

    override fun clearLoginState() { loginAttempt = null; casLogin?.clear(); casLogin = null }

    private suspend fun finishCas(cas: QzCasSsoClient, result: LoginResult): LoginResult {
        session.requireActive()
        if (cas !== casLogin || casEpoch != session.epoch) throw CancellationException("Login session changed")
        if (result.status != AcademicStatus.SUCCESS) return finishLogin(result)
        session.cookies.saveFromResponse(cas.entry.service, cas.teachingCookies())
        return finishLogin(validateSession())
    }

    private fun currentLoginAttempt(): AcademicLoginAttempt? = loginAttempt?.takeIf { it.sessionEpoch == session.epoch }
        .also { if (it == null) clearLoginState() }

    private suspend fun submitLogin(attempt: AcademicLoginAttempt, code: String): LoginResult {
        val page = attempt.page
        val form = AcademicLoginHtml.form(page)
            ?: return finishLogin(LoginResult(AcademicStatus.PAGE_CHANGED, message = "学校登录表单已变化"))
        val scode = AcademicHtml.firstScriptValue(page.text, "scode")
        val sxh = AcademicHtml.firstScriptValue(page.text, "sxh")
        if (scode.isNullOrBlank() || sxh.isNullOrBlank())
            return finishLogin(LoginResult(AcademicStatus.PAGE_CHANGED, message = "登录编码参数已变化，请重新登录"))
        val credentials = attempt.credentials
        val fields = AcademicHtml.formFields(form).toMap().toMutableMap().apply {
            putIfAbsent("loginMethod", "LoginToXk"); putIfAbsent("userlanguage", "0")
            put("userAccount", credentials.username); put("userPassword", "")
            put("encoded", LoginEncoding.qzNew(credentials.username, credentials.password, scode, sxh))
        }
        AcademicLoginHtml.captcha(form, "RANDOMCODE")?.let { fields[it.fieldName] = code }
        val result = transport.postForm(AcademicHtml.action(form, page.url), fields.toList(), page.url)
        AcademicLoginHtml.form(result)?.let { attempt.page = result }
        AcademicLoginHtml.failure(result)?.let { failure ->
            if (failure.status == AcademicStatus.CAPTCHA_REQUIRED) {
                val updatedForm = AcademicLoginHtml.form(attempt.page)
                val captcha = updatedForm?.let { AcademicLoginHtml.captcha(it, "RANDOMCODE") }
                    ?: return finishLogin(LoginResult(AcademicStatus.HUMAN_VERIFICATION_REQUIRED, message = "请在教务网页完成验证码验证"))
                return if (code.isBlank()) failure.copy(captcha = captcha.load(transport, attempt.page.url)) else failure
            }
            return finishLogin(failure)
        }
        val status = AcademicJson.status(result.text, result.code)
        if (status in setOf(AcademicStatus.INVALID_CREDENTIALS, AcademicStatus.HUMAN_VERIFICATION_REQUIRED, AcademicStatus.SESSION_EXPIRED))
            return finishLogin(LoginResult(status, message = AcademicJson.message(result.text).ifBlank { "登录未完成，请重新登录" }))
        val json = runCatching { JSONObject(result.text) }.getOrNull()
        if (json != null && status != AcademicStatus.SUCCESS)
            return finishLogin(LoginResult(AcademicStatus.VALIDATION_FAILED, message = AcademicJson.message(result.text).ifBlank { "学校未接受登录请求，请重新登录" }))
        return finishLogin(validateSession())
    }

    private fun finishLogin(result: LoginResult): LoginResult = result.also {
        if (it.status != AcademicStatus.CAPTCHA_REQUIRED) clearLoginState()
    }

    override suspend fun validateSession(): LoginResult = serial {
        val home = transport.get(transport.appUrl("framework/xsMainV_new.htmlx?t1=1"))
        if (AcademicHtml.isLoginPage(home.text)) return@serial LoginResult(AcademicStatus.SESSION_EXPIRED)
        val rounds = transport.get(transport.appUrl("xsxk/xklc_list_data?xkmc="), home.url, ajax = true)
        try { AcademicJson.objects(rounds.text, "data") } catch (e: AcademicException) {
            return@serial LoginResult(e.status, message = e.message.orEmpty())
        }
        val identity = parseName(Jsoup.parse(home.text, home.url))
        val studentId = identity.second.ifBlank { session.username }
        if (studentId.isNotBlank()) session.username = studentId
        LoginResult(AcademicStatus.SUCCESS, identity.first, studentId)
    }

    override suspend fun loadCourseContext(): CourseContext = serial {
        val listPage = transport.get(transport.appUrl("xsxk/xklc_list"))
        requirePage(listPage)
        val response = transport.get(transport.appUrl("xsxk/xklc_list_data?xkmc="), listPage.url, ajax = true)
        val rounds = AcademicJson.objects(response.text, "data")
        val scopes = mutableListOf<CourseScope>()
        for (round in rounds) {
            val id = AcademicJson.string(round, "jx0502zbid", "id")
            if (id.isBlank()) throw AcademicException(AcademicStatus.PAGE_CHANGED, "选课轮次缺少标识")
            val term = AcademicJson.string(round, "xnxq01id")
            val name = AcademicJson.string(round, "xklc_mc", "name").ifBlank { "选课轮次" }
            if (round.has("xkzt") && round.optString("xkzt") != "1") continue
            if (listPage.text.contains("mzlist.do")) {
                val check = transport.postForm(transport.appUrl("xsxk/mzlist.do"), emptyList(), listPage.url, ajax = true)
                val json = runCatching { JSONObject(check.text) }.getOrNull()
                if (json?.optBoolean("istc") == true)
                    throw AcademicException(AcademicStatus.HUMAN_VERIFICATION_REQUIRED, "请先在教务网页阅读并确认选课声明")
            }
            val entry = Regex("""['"]([^'"]*newXsxkzx)\?jx0502zbid=""").find(listPage.text)?.groupValues?.get(1)
                ?.let { URI(listPage.url).resolve(it).toString() } ?: transport.appUrl("xsxk/newXsxkzx")
            val entryUrl = entry + "?jx0502zbid=" + encode(id) + "&isallsc="
            val params = mapOf("roundId" to id, "term" to term, "roundPage" to entryUrl,
                "selectionAllowed" to (round.optString("txkz") != "3").toString())
            val page = transport.get(entryUrl, listPage.url)
            val found = discoverScopes(page, params, name)
            if (found.isEmpty()) {
                if (AcademicJson.status(page.text) == AcademicStatus.ROUND_CLOSED) continue
                throw AcademicException(AcademicStatus.PAGE_CHANGED, "未找到当前轮次的课程分类，请使用教务网页查看")
            }
            scopes += found
        }
        CourseContext(session.epoch, scopes)
    }

    protected fun requirePage(page: AcademicResponse) {
        if (AcademicHtml.isLoginPage(page.text)) throw AcademicException(AcademicStatus.SESSION_EXPIRED, "登录已失效，请重新登录")
        if (page.code !in 200..299) throw AcademicException(AcademicStatus.PAGE_CHANGED, "教务页面暂不可用")
    }

    protected suspend fun discoverScopes(
        page: AcademicResponse, params: Map<String, String>, name: String, depth: Int = 0
    ): List<CourseScope> {
        requirePage(page)
        if (depth > 3) return emptyList()
        val selectedPage = QzScriptParser.selectedPage(page.text, page.url)
        val parameters = params + if (selectedPage != null) mapOf("selectedPage" to selectedPage) else emptyMap()
        val categories = QzScriptParser.categoryPages(page.text, page.url)
        if (categories.isNotEmpty()) return categories.map { (title, url) ->
            CourseScope((params["roundId"] ?: "") + ":" + URI(url).path, name + " · " + title,
                params["term"].orEmpty(), url, params = parameters + ("pageUrl" to url))
        }
        if (Regex("""["']?sAjaxSource["']?\s*:""").containsMatchIn(page.text)) {
            return listOf(CourseScope((params["roundId"] ?: "") + ":" + URI(page.url).path, name,
                params["term"].orEmpty(), page.url, params = parameters + ("pageUrl" to page.url)))
        }
        val frames = Jsoup.parse(page.text, page.url).select("iframe[src]").mapNotNull {
            it.absUrl("src").takeIf { url -> url.isNotBlank() && !url.contains("/selectNum") && !url.endsWith("/selectTable") }
        }.distinct().take(8)
        val result = mutableListOf<CourseScope>()
        for (frame in frames) result += discoverScopes(transport.get(frame, page.url), parameters, name, depth + 1)
        return result
    }

    protected suspend fun readCategory(url: String, query: CourseQuery): Pair<AcademicResponse, QzCategoryConfig> {
        val page = transport.get(url)
        requirePage(page)
        var combined = page.text
        var config = QzScriptParser.parse(combined, page.url, query)
        if (config == null || config.submitUrl.isBlank()) {
            for (script in QzScriptParser.externalScriptUrls(page.text, page.url).filterNot {
                listOf("jquery", "layui", "bootstrap", "iconfont", "qzTable", "qzDialog", "qzForm", "qzDate", "qzImport").any { library -> it.contains(library, true) }
            }.take(8)) {
                try { combined += "\n" + transport.get(script, page.url).text } catch (e: CancellationException) { throw e } catch (_: AcademicException) { continue }
                config = QzScriptParser.parse(combined, page.url, query)
                if (config != null && config.submitUrl.isNotBlank()) break
            }
        }
        return page to (config ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "课程页面结构已变化，无法读取列表地址"))
    }

    override suspend fun listCourses(context: CourseContext, query: CourseQuery): List<CourseOffer> = serial {
        check(context)
        val result = mutableListOf<CourseOffer>()
        var activeRound = ""
        for (scope in context.scopes.filter { query.scopeId.isBlank() || it.id == query.scopeId }) {
            val roundPage = scope.params["roundPage"].orEmpty()
            if (roundPage.isNotBlank() && activeRound != roundPage) {
                requirePage(transport.get(roundPage)); activeRound = roundPage
            }
            val (page, config) = readCategory(scope.listUrl, query)
            val size = query.pageSize.coerceIn(1, 200)
            var start = query.start.coerceAtLeast(0)
            var pagesRead = 0
            while (pagesRead++ < 100) {
                val fields = linkedMapOf("sEcho" to "1", "iDisplayStart" to start.toString(),
                    "iDisplayLength" to size.toString(), "sColumns" to "", "iColumns" to config.columns.size.toString())
                config.columns.forEachIndexed { i, column -> fields["mDataProp_" + i] = column }
                val response = transport.postForm(config.listUrl, fields.toList(), page.url, ajax = true)
                val rows = AcademicJson.objects(response.text, "aaData", "data")
                for (json in rows) {
                    val raw = AcademicJson.operationFields(json).toMutableMap()
                    raw.putAll(scope.params)
                    raw["pageUrl"] = page.url
                    raw["sessionEpoch"] = session.epoch.toString()
                    raw["kcid"] = raw["kcid"].orEmpty().ifBlank {
                        if (school.academicSystem == AcademicSystem.QZ_OLD.id) AcademicJson.string(json, "kch")
                        else AcademicJson.string(json, "jx02id", "kch_id", "kch")
                    }
                    if (json.has("cfbs") && json.isNull("cfbs")) raw["cfbs"] = "null"
                    raw["requiresVerification"] = config.requiresVerification.toString()
                    raw["scopeId"] = scope.id
                    val id = raw["kcid"].orEmpty()
                    if (id.isBlank() || raw["jx0404id"].isNullOrBlank())
                        throw AcademicException(AcademicStatus.PAGE_CHANGED, "课程记录缺少课程或教学班标识")
                    result += offer(json, scope.id, arrayOf("kcid", "jx02id", "kch")).copy(
                        stableId = id, raw = raw, capacity = AcademicJson.int(json, "xxrs", "pkrs", "jxbrl"),
                        selected = AcademicJson.int(json, "xkrs", "yxrs"),
                        time = Jsoup.parse(json.optString("sksj")).text(), location = Jsoup.parse(json.optString("skdd")).text())
                }
                val root = JSONObject(response.text)
                val total = AcademicJson.int(root, "iTotalDisplayRecords", "iTotalRecords", "recordsFiltered", "count", "total")
                start += rows.size
                if (rows.isEmpty() || (total != null && start >= total) || (total == null && rows.size < size)) break
                if (pagesRead == 100) throw AcademicException(AcademicStatus.PAGE_CHANGED, "课程页数超过读取上限，请缩小筛选范围")
            }
        }
        result.distinctBy { it.scopeId + ":" + it.raw["jx0404id"] }
            .filter { (query.keyword.isBlank() || it.name.contains(query.keyword, true)) && (query.teacher.isBlank() || it.teacher.contains(query.teacher, true)) }
    }

    override suspend fun listSections(course: CourseOffer): List<CourseSection> =
        listOf(section(JSONObject(course.raw), course))

    override suspend fun select(target: SelectionTarget): SelectionResult = serial {
        if (!target.confirmed) return@serial SelectionResult(AcademicStatus.VALIDATION_FAILED, "请先确认选课")
        if (target.course.raw["selectionAllowed"] == "false") return@serial SelectionResult(AcademicStatus.ROUND_CLOSED, "当前轮次只可退课")
        checkEpoch(target.course.raw)
        val pageUrl = target.course.raw["pageUrl"].orEmpty()
        target.course.raw["roundPage"]?.takeIf(String::isNotBlank)?.let { requirePage(transport.get(it)) }
        val (page, config) = readCategory(pageUrl, CourseQuery())
        if (config.requiresVerification) return@serial SelectionResult(AcademicStatus.HUMAN_VERIFICATION_REQUIRED, "请先完成教务网页验证")
        if (config.submitUrl.isBlank()) return@serial SelectionResult(AcademicStatus.PAGE_CHANGED, "课程页没有可验证的选课入口")
        val values = config.defaults + target.course.raw + target.section.raw
        val response = submit(config.submitUrl, config.submitMethod, config.submitFields, values, page.url)
        SelectionResult(AcademicJson.status(response.text, response.code), AcademicJson.message(response.text))
    }

    override suspend fun selected(context: CourseContext): List<SelectedCourse> = serial {
        check(context)
        val scoped = selectedInRounds(context)
        if (scoped != null) return@serial scoped
        selectedFromMenu("framework/xsMainV.htmlx")
    }

    protected suspend fun selectedInRounds(context: CourseContext): List<SelectedCourse>? {
        val scopes = context.scopes.filter { !it.params["selectedPage"].isNullOrBlank() }
            .distinctBy { it.params["roundPage"] to it.params["selectedPage"] }
        if (scopes.isEmpty()) return null
        return scopes.flatMap { scope ->
            scope.params["roundPage"]?.takeIf(String::isNotBlank)?.let { requirePage(transport.get(it)) }
            selectedFromPage(scope.params.getValue("selectedPage")).map { it.copy(raw = scope.params + it.raw) }
        }.groupBy { it.stableId }.values.map { copies ->
            copies.firstOrNull { it.raw["canDrop"] == "true" } ?: copies.first()
        }
    }

    protected suspend fun selectedFromMenu(homePath: String): List<SelectedCourse> {
        val home = transport.get(transport.appUrl(homePath))
        requirePage(home)
        val document = Jsoup.parse(home.text, home.url)
        val discovered = QzScriptParser.selectedPage(home.text, home.url)
            ?: document.select("[data-url], [data-src], a[href]").firstNotNullOfOrNull { element ->
                val value = element.attr("data-url").ifBlank { element.attr("data-src") }.ifBlank { element.attr("href") }
                if (!value.substringBefore('?').endsWith("/xkgl/xsxkjgcx")) null
                else if (value.startsWith(school.basePath.trimEnd('/') + "/") || value.startsWith("http")) URI(home.url).resolve(value).toString()
                else transport.appUrl(value)
            } ?: throw AcademicException(AcademicStatus.UNSUPPORTED, "学校未提供可识别的已选课程查询入口")
        if (!URI(discovered).path.endsWith("/xkgl/xsxkjgcx")) return selectedFromPage(discovered)
        var page = transport.get(discovered, home.url)
        requirePage(page)
        var form = Jsoup.parse(page.text, page.url).selectFirst("form")
        if (form == null) {
            val listPage = Regex("""['"]([^'"]*/xkgl/loadXsxkjgList\?lx=xkrz)['"]""")
                .find(page.text)?.groupValues?.get(1)
                ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校已选课程查询页面已变化")
            page = transport.get(URI(page.url).resolve(listPage).toString(), page.url)
            requirePage(page)
            form = Jsoup.parse(page.text, page.url).selectFirst("form")
        }
        if (form == null) throw AcademicException(AcademicStatus.PAGE_CHANGED, "未找到学校已选课程查询表单")
        val fields = AcademicHtml.formFields(form).toMap()
        if (fields["xnxqid"].isNullOrBlank())
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校未提供当前查询学期，请在教务网页确认")
        selectedJsonPage(page)?.let { return it }
        val action = AcademicHtml.queryFormAction(form, page.text, page.url)
            ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校已选课程查询地址已变化")
        if (!URI(action).path.endsWith("/xkgl/loadXsxkjgList"))
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校已选课程查询地址已变化")
        val result = transport.postForm(action, fields.toList(), page.url)
        requirePage(result)
        return selectedFromResponse(result)
    }

    private suspend fun selectedJsonPage(page: AcademicResponse, term: String? = null): List<SelectedCourse>? {
        val jsonList = Regex("""\burl\s*:\s*['"]([^'"]*/xkgl/loadXsxkjgList[^'"]*\btype=list[^'"]*)['"]""")
            .find(page.text)?.groupValues?.get(1) ?: return null
        val form = Jsoup.parse(page.text, page.url).selectFirst("form")
            ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "未找到学校已选课程查询表单")
        val fields = AcademicHtml.formFields(form).toMap().toMutableMap()
        if (term != null) {
            if (form.select("select[name=xnxqid] option").none { it.attr("value") == term })
                throw AcademicException(AcademicStatus.PAGE_CHANGED, "该课程所属学期已不在学校查询范围中")
            fields["xnxqid"] = term
        }
        if (fields["xnxqid"].isNullOrBlank())
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校未提供当前查询学期，请在教务网页确认")
        return selectedJsonQuery(URI(page.url).resolve(jsonList).toString(), fields, page.url)
    }

    private suspend fun selectedJsonQuery(url: String, fields: Map<String, String>, referer: String): List<SelectedCourse> {
        val result = mutableListOf<SelectedCourse>()
        var page = 1
        while (page <= 100) {
            val query = url.toHttpUrl().newBuilder().apply {
                (fields + mapOf("pageNum" to page.toString(), "pageSize" to "200")).forEach { (key, value) -> setQueryParameter(key, value) }
            }.build().toString()
            val response = transport.get(query, referer, ajax = true)
            requirePage(response)
            val rows = AcademicJson.objects(response.text, "data")
            result += rows.map { json ->
                val course = selected(json)
                if (course.stableId.isBlank()) throw AcademicException(AcademicStatus.PAGE_CHANGED, "已选课程缺少记录标识")
                val markup = AcademicJson.string(json, "czOper", "oper", "operation")
                val operation = QzScriptParser.dropControl(Jsoup.parseBodyFragment(markup))?.takeIf {
                    course.raw[it.field].isNullOrBlank() || course.raw[it.field] == it.id
                }
                course.copy(raw = course.raw + mapOf(
                    "pageUrl" to referer, "queryTerm" to fields.getValue("xnxqid"),
                    "sessionEpoch" to session.epoch.toString(), "canDrop" to (operation != null).toString(),
                    "dropFunction" to operation?.function.orEmpty(), "dropId" to operation?.id.orEmpty()) +
                    (operation?.let { mapOf(it.field to it.id) } ?: emptyMap()))
            }
            val total = AcademicJson.int(JSONObject(response.text), "count", "total")
            if (rows.isEmpty() || (total != null && result.size >= total) || (total == null && rows.size < 200)) break
            if (page == 100) throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校已选课程分页超出读取上限")
            page++
        }
        return result.distinctBy { it.stableId }
    }

    protected suspend fun selectedFromPage(url: String): List<SelectedCourse> {
        val page = transport.get(url)
        requirePage(page)
        return selectedFromResponse(page)
    }

    protected fun selectedFromResponse(page: AcademicResponse): List<SelectedCourse> {
        val rows = AcademicTables.rows(page.text, page.url)
        if (Jsoup.parse(page.text).select("table th, table td").none { it.text().trim() in setOf("课程名称", "课程名") })
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "无法识别学校的已选课程页面")
        return rows.map { row ->
            val operation = QzScriptParser.dropControl(row.element)
            val id = operation?.id.orEmpty()
            val courseId = row.value("课程编号", "课程代码", "课程号")
            SelectedCourse(id.ifBlank { courseId.ifBlank { row.value("课程名称", "课程名") } }, row.value("课程名称", "课程名"), row.value("上课教师", "上课老师", "教师姓名", "教师"),
                courseId, id, mapOf("pageUrl" to page.url, "jx0404id" to id,
                    "sessionEpoch" to session.epoch.toString(), "canDrop" to (operation != null).toString(),
                    "dropFunction" to operation?.function.orEmpty(), "dropId" to id,
                    "sksj" to row.value("上课时间", "时间"), "skdd" to row.value("上课地点", "地点"), "xf" to row.value("学分")) +
                    (operation?.let { mapOf(it.field to it.id) } ?: emptyMap()))
        }
    }

    override suspend fun drop(target: SelectionTarget): OperationResult = serial {
        if (!target.confirmed) return@serial OperationResult(AcademicStatus.VALIDATION_FAILED, "请先确认退课")
        checkEpoch(target.course.raw)
        val pageUrl = target.course.raw["pageUrl"].orEmpty()
        if (pageUrl.isBlank()) return@serial OperationResult(AcademicStatus.UNSUPPORTED, "该记录未提供退课入口，请使用教务网页")
        target.course.raw["roundPage"]?.takeIf(String::isNotBlank)?.let { requirePage(transport.get(it)) }
        val page = transport.get(pageUrl)
        requirePage(page)
        val courses = selectedJsonPage(page, target.course.raw["queryTerm"]) ?: selectedFromResponse(page)
        val selected = courses.singleOrNull {
            (it.sectionId == target.section.stableId || it.stableId == target.section.stableId) && it.raw["canDrop"] == "true" &&
                (target.course.raw["dropId"].isNullOrBlank() || it.raw["dropId"] == target.course.raw["dropId"])
        }
            ?: return@serial OperationResult(AcademicStatus.PAGE_CHANGED, "目标课程不在当前可退课程中")
        var combined = page.text
        val function = selected.raw["dropFunction"].orEmpty().ifBlank { "xstkOper" }
        var operation = QzScriptParser.dropOperation(combined, page.url, function)
        if (operation == null) for (script in QzScriptParser.externalScriptUrls(page.text, page.url).filter {
            it.contains("xstk", true) || it.contains("xsxk", true) || it.contains("xkjg", true)
        }.take(8)) {
            combined += "\n" + transport.get(script, page.url).text
            operation = QzScriptParser.dropOperation(combined, page.url, function)
            if (operation != null) break
        }
        val resolvedOperation = operation
            ?: return@serial OperationResult(AcademicStatus.UNSUPPORTED, "学校退课页面结构已变化")
        val response = submit(resolvedOperation.url, resolvedOperation.method, resolvedOperation.fields, selected.raw, page.url)
        OperationResult(AcademicJson.status(response.text, response.code), AcademicJson.message(response.text))
    }

    protected fun checkEpoch(raw: Map<String, String>) {
        if (raw["sessionEpoch"] != session.epoch.toString())
            throw AcademicException(AcademicStatus.SESSION_EXPIRED, "课程上下文已过期，请重新读取")
    }

    private suspend fun submit(url: String, method: String, fields: Map<String, String>, values: Map<String, String>, referer: String): AcademicResponse {
        val resolved = QzScriptParser.expand(url, values, true)
        val body = fields.map { it.key to QzScriptParser.expand(it.value, values) }
        return if (method == "POST") transport.postForm(resolved, body, referer, write = true, ajax = true)
        else transport.writeGet(resolved + (if (resolved.contains('?')) "&" else "?") + body.joinToString("&") { encode(it.first) + "=" + encode(it.second) }, referer)
    }

    protected fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}

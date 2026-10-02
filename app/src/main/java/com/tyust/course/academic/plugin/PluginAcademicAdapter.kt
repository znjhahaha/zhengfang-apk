package com.tyust.course.academic.plugin

import android.content.Context
import com.tyust.course.academic.*
import com.tyust.course.academic.plugin.runtime.PluginSandboxClient
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import okio.ByteString.Companion.decodeBase64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class PluginAcademicAdapter(
    private val app: Context, val pinned: PluginPackage, val session: AcademicSession,
    private val base: AcademicProtocolAdapter? = null, private val study: AcademicStudyAdapter? = null,
    private val baseSchool: com.tyust.course.model.SchoolConfig? = null,
    private val scopeStillActive: () -> Boolean = { true },
    private val inheritedPackage: PluginPackage? = null
) : AcademicProtocolAdapter, AcademicStudyAdapter, AcademicCaptchaLogin, SessionBackedAdapter {
    private val schema = PluginContractCache.schema(app)
    private var continuation: String? = null
    private var ownWebLogin: JSONObject? = null
    @Volatile private var loginInProgress = false
    val webLogin: JSONObject? get() = ownWebLogin ?: (base as? PluginAcademicAdapter)?.webLogin
    val version: String get() = pinned.manifest.version
    fun rebind(newSession: AcademicSession): PluginAcademicAdapter {
        val rebound = inheritedPackage?.let { PluginAcademicAdapter(app, it, newSession, scopeStillActive = scopeStillActive) }
        val reader = (rebound as? AcademicStudyAdapter)
        return PluginAcademicAdapter(app, pinned, newSession, rebound, reader, baseSchool, scopeStillActive, inheritedPackage)
    }
    @Volatile var lastTrace: List<JSONObject> = emptyList()
        private set
    private val storageRoot = File(app.filesDir, if (session.key.accountKey.startsWith("dev:")) "academic-plugin-development/${pinned.digest}" else "academic-plugin-storage")
    fun clearDevelopmentData() {
        require(session.key.accountKey.startsWith("dev:"))
        session.invalidate()
        storageRoot.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }

    val effectiveCapabilities: Set<String> get() = pinned.manifest.capabilities + (base as? PluginAcademicAdapter)?.effectiveCapabilities.orEmpty()
    suspend fun invoke(method: String, args: JSONObject = JSONObject(), confirmed: Boolean = false): JSONObject =
        invokeOperation(method, args, confirmed)

    internal suspend fun invokeSharedService(method: String, args: JSONObject, confirmed: Boolean,
        shared: PluginAcademicSession, grant: String): JSONObject = invokeOperation(method, args, confirmed, shared, grant)

    private suspend fun invokeOperation(method: String, args: JSONObject, confirmed: Boolean,
        shared: PluginAcademicSession? = null, grant: String = ""): JSONObject {
        val needsShared = pinned.manifest.sharesAcademicSession && method.startsWith("service.")
        if (needsShared != (shared != null)) throw AcademicException(AcademicStatus.UNSUPPORTED, "共享校园服务必须通过宿主授权入口调用")
        if (method !in pinned.manifest.capabilities)
            return (base as? PluginAcademicAdapter)?.invoke(method, args, confirmed) ?: unsupported(method)
        val queuedAt = android.os.SystemClock.elapsedRealtime()
        return session.withProtocolLock {
        val reusableAction = if (!confirmed && method == "service.action" && shared?.rememberedAction(args.optString("actionId")) == true) args.getString("actionId") else null
        val effectiveConfirmation = confirmed || reusableAction != null
        ServicePluginContract.requireRequest(pinned.manifest, method, args, effectiveConfirmation)
        val op = PluginOperation(session, pinned.manifest, method, development = !pinned.official && !pinned.bundled, confirmed = effectiveConfirmation,
            actionId = if (method == "service.action") args.getString("actionId") else null,
            packageDigest = pinned.digest, scopeStillActive = { shared?.requireGrant(grant); scopeStillActive() })
        PluginTrace.stage(op, "protocol_lock", android.os.SystemClock.elapsedRealtime() - queuedAt)
        val tokenCapture = if (method.startsWith("auth.") && pinned.manifest.isAcademic && pinned.manifest.json.has("academicSessionToken"))
            PluginAcademicTokenCapture(op, pinned) else null
        val host = PluginHost(op, storageRoot, shared?.cookies(grant) ?: session.cookies,
            captureToken = tokenCapture?.let { it::capture },
            sharedSite = { shared?.siteAuthorized() == true },
                    sharedApproval = shared?.let { access -> { request ->
                if (!access.siteAuthorized() && reusableAction != null && request.has("body")) throw PluginException(PluginErrorCode.PERMISSION_DENIED, "可复用操作仅接受审核过的结构化参数")
                if (confirmed) false
                else if (access.siteAuthorized()) request.getString("purpose") == "mutation"
                else if (reusableAction != null) access.operation(request)?.optString("risk") == "read-state"
                else access.requireReviewedReadOrConfirmation(request)
            } },
            sharedToken = shared?.let { access -> { url -> access.tokenHeader(grant, url) } },
            sharedRequest = shared?.let { access -> { url, verb, purpose, form -> access.requireRequest(grant, url, verb, purpose, form)
                if (reusableAction != null && !access.siteAuthorized()) access.requireRememberedAction(reusableAction, url, verb, purpose, form)
            } },
            tokenSession = shared?.session ?: session, dataGuard = if (pinned.manifest.isService || pinned.manifest.isNative && !pinned.manifest.isAcademic) PluginDataGuard(app, pinned) else null)
        val sharedCredential = shared?.session?.pluginToken
        val lease = PluginVersionLeases.acquire(pinned.manifest.id)
        try {
            shared?.track(grant, op)
            PluginTrace.stage(op, "sandbox")
            val result = PluginSandboxClient(app).execute(pinned.source, args, op, host)
            op.requireActive()
            PluginTrace.stage(op, "response_validation")
            schema.response(method, result).also { data ->
                if (method == "selection.courses" && pinned.manifest.json.optInt("minAppVersionCode") < 101 &&
                    PluginJson.objects(data.getJSONArray("items")).any { it.has("sectionCount") })
                    throw PluginException(PluginErrorCode.VALIDATION_FAILED, "sectionCount 需要声明 minAppVersionCode 101")
                if (method == "study.schedule") PluginJson.objects(data.getJSONArray("entries")).forEach { com.tyust.course.schedule.ScheduleDetails.fromEntry(it) }
                tokenCapture?.publish(data)
                if (method == "service.page") {
                    if (data.getString("pageId") != args.getString("pageId")) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "服务返回了其他页面")
                    ServicePluginContract.validatePage(pinned.manifest, data)
                }
                if (method == "service.action") {
                    if (data.getString("actionId") != args.getString("actionId")) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "服务操作结果身份不匹配")
                    data.optJSONObject("page")?.let { ServicePluginContract.validatePage(pinned.manifest, it) }
                }
            }
        } catch (e: PluginException) {
            if (shared != null && e.code == PluginErrorCode.SESSION_EXPIRED)
                runCatching { shared.expireCredentials(grant, sharedCredential) }
            if (e.code in setOf(PluginErrorCode.SESSION_EXPIRED, PluginErrorCode.INVALID_CREDENTIALS)) synchronized(session) {
                if (session.pluginToken?.owner == PluginAcademicToken.owner(pinned)) session.pluginToken = null
            }
            val failure = op.failure(e.code, e.message.orEmpty()); throw AcademicException(status(failure.code), failure.message.orEmpty(), e)
        }
        finally { lastTrace = host.report(); op.close(); shared?.untrack(op); lease.close() }
        }
    }
    private suspend fun pages(method: String, args: JSONObject = JSONObject(), onFirstPage: (JSONObject) -> Unit = {}): List<JSONObject> {
        val rows = mutableListOf<JSONObject>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(100) {
            val query = JSONObject(args.toString()); cursor?.let { query.put("cursor", it) }
            val page = invoke(method, query)
            if (cursor == null) onFirstPage(page)
            rows += PluginJson.objects(page.getJSONArray("items"))
            if (rows.size > 10000) throw AcademicException(AcademicStatus.PAGE_CHANGED, "插件结果超过数量上限")
            cursor = page.optString("nextCursor").takeIf(String::isNotBlank)
            if (cursor == null) return rows
            if (!cursors.add(cursor!!)) throw AcademicException(AcademicStatus.PAGE_CHANGED, "插件分页游标重复")
        }
        throw AcademicException(AcademicStatus.PAGE_CHANGED, "插件分页超过上限")
    }
    override suspend fun login(credentials: Credentials): LoginResult = if (has("auth.start")) {
        clearLoginState()
        session.invalidate()
        PluginHost.clearTemporaryState(session)
        loginInProgress = true
        session.username = credentials.username
        auth(invoke("auth.start", JSONObject().put("username", credentials.username).put("password", credentials.password)))
    } else native().login(credentials)
    override suspend fun validateSession(): LoginResult = if (has("auth.validate")) auth(invoke("auth.validate")) else native().validateSession()
    override suspend fun submitCaptcha(code: String): LoginResult = if (has("auth.resume"))
        auth(invoke("auth.resume", JSONObject().put("continuationId", continuation ?: unsupported("auth.resume")).put("captcha", code)))
        else (native() as? AcademicCaptchaLogin)?.submitCaptcha(code) ?: unsupported("auth.resume")
    suspend fun resumeWebLogin(): LoginResult = if (has("auth.resume"))
        auth(invoke("auth.resume", JSONObject().put("continuationId", continuation ?: unsupported("auth.resume")).put("webLoginCompleted", true)))
        else (base as? PluginAcademicAdapter)?.resumeWebLogin() ?: unsupported("auth.resume")
    override suspend fun refreshCaptcha(): CaptchaChallenge? = if (has("auth.refreshCaptcha"))
        auth(invoke("auth.refreshCaptcha", JSONObject().put("continuationId", continuation ?: unsupported("auth.refreshCaptcha")))).captcha
        else (native() as? AcademicCaptchaLogin)?.refreshCaptcha()
    override fun clearLoginState() {
        if (loginInProgress) { session.invalidate(); PluginHost.clearTemporaryState(session) }
        loginInProgress = false; continuation = null; ownWebLogin = null; (base as? AcademicCaptchaLogin)?.clearLoginState()
    }
    override fun cookieHeader() = session.cookieHeader()
    private fun auth(data: JSONObject): LoginResult = when (data.getString("status")) {
        "authenticated" -> { loginInProgress = false; continuation = null; ownWebLogin = null
            LoginResult(AcademicStatus.SUCCESS, data.getString("studentName"), data.getString("studentId")) }
        "captcha" -> { continuation = data.getString("continuationId"); LoginResult(AcademicStatus.CAPTCHA_REQUIRED,
            captcha = CaptchaChallenge(data.getString("imageBase64").decodeBase64()?.toByteArray() ?: ByteArray(0), "captcha", null)) }
        "webLogin" -> { continuation = data.getString("continuationId"); ownWebLogin = data
            val policy = PluginNetworkPolicy(pinned.manifest.network)
            for (field in listOf("url", "completionUrl")) {
                val url = data.getString(field).toHttpUrlOrNull()
                    ?: throw AcademicException(AcademicStatus.UNTRUSTED_URL, "无效登录地址")
                policy.requireAllowed(url, "GET", "auth", null)
            }
            LoginResult(AcademicStatus.HUMAN_VERIFICATION_REQUIRED, message = "请在学校网页完成登录") }
        else -> throw AcademicException(AcademicStatus.PAGE_CHANGED, "无效登录状态")
    }
    override suspend fun catalog(): AcademicStudyCatalog = if (has("study.terms")) {
        val data = invoke("study.terms")
        val terms = PluginJson.objects(data.getJSONArray("items")).map(AcademicTerm::fromJson)
        if (terms.map { it.id }.distinct().size != terms.size) throw AcademicException(AcademicStatus.PAGE_CHANGED, "学期标识重复")
        AcademicStudyCatalog(terms, terms.firstOrNull { it.id == data.getString("currentId") }
            ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "当前学期未出现在列表中"))
    } else study?.catalog() ?: unsupported("study.terms")
    override suspend fun schedule(term: AcademicTerm): List<AcademicScheduleEntry> = if (has("study.schedule")) {
        val data = invoke("study.schedule", JSONObject().put("termId", term.id))
        if (data.getString("termId") != term.id) throw AcademicException(AcademicStatus.PAGE_CHANGED, "课表学期不匹配")
        PluginJson.objects(data.getJSONArray("entries")).map { item ->
            if (item.getInt("endPeriod") < item.getInt("startPeriod")) throw AcademicException(AcademicStatus.PAGE_CHANGED, "课表节次顺序无效")
            AcademicScheduleEntry(item.getString("name"), item.optString("teacher"), item.optString("location"), item.getInt("day"),
                item.getInt("startPeriod"), item.getInt("endPeriod"), (0 until item.getJSONArray("weeks").length()).joinToString(",") { item.getJSONArray("weeks").getInt(it).toString() } + "周", item.getString("id"), com.tyust.course.schedule.ScheduleDetails.fromEntry(item))
        }
    } else study?.schedule(term) ?: unsupported("study.schedule")
    override suspend fun grades(term: AcademicTerm?): AcademicGradeReport = if (has("study.grades")) {
        var summary = JSONObject()
        val rows = pages("study.grades", JSONObject().apply { term?.let { put("termId", it.id) } }) { summary = it }
        AcademicGradeReport(rows.map { item -> AcademicGrade(item.getString("name"), item.getString("score"), item.optString("credits"), item.optString("gradePoint"),
            item.optString("type"), item.optString("termId"), item.optString("code"), item.optString("college"), item.optString("sectionId"), "", item.getString("id")) }, summary.optString("gradePointAverage"), summary.optString("totalCredits"))
    } else study?.grades(term) ?: unsupported("study.grades")
    override suspend fun gradeDetails(grade: AcademicGrade): String = if (has("study.gradeDetails") && grade.id.isNotBlank()) {
        val details = invoke("study.gradeDetails", JSONObject().put("gradeId", grade.id))
        if (details.getString("gradeId") != grade.id) throw AcademicException(AcademicStatus.PAGE_CHANGED, "成绩明细身份不匹配")
        PluginJson.objects(details.getJSONArray("items")).joinToString("；") { it.getString("name") + ":" + it.getString("score") + it.optString("weight").takeIf(String::isNotBlank)?.let { weight -> " ($weight)" }.orEmpty() }
    } else study?.gradeDetails(grade) ?: grade.detail
    override suspend fun calendar(term: AcademicTerm): JSONObject? = if (has("study.calendar")) {
        invoke("study.calendar", JSONObject().put("termId", term.id)).also { value ->
            if (value.getString("termId") != term.id) throw AcademicException(AcademicStatus.PAGE_CHANGED, "作息学期不匹配")
            PluginJson.objects(value.getJSONArray("periods")).forEach { period ->
                if (period.getString("start").substringBefore(':').toInt() > 23 || period.getString("end").substringBefore(':').toInt() > 23)
                    throw AcademicException(AcademicStatus.PAGE_CHANGED, "作息时间超出范围")
            }
        }
    } else study?.calendar(term)
    override suspend fun exams(term: AcademicTerm): List<AcademicExam> = if (has("study.exams"))
        pages("study.exams", JSONObject().put("termId", term.id)).map { AcademicExam(it.getString("name"), it.getString("time"), it.optString("location"), it.optString("seat"), it.optString("examName"), it.optString("teacher")) }
        else study?.exams(term) ?: unsupported("study.exams")
    override val hasCourseFilters: Boolean get() = has("selection.filters") || !has("selection.catalog") && base?.hasCourseFilters == true
    override suspend fun courseFilters(context: CourseContext, roundId: String): CourseFilters? {
        check(context)
        if (!has("selection.filters")) return if (!has("selection.catalog")) base?.courseFilters(context, roundId) else null
        if (context.scopes.none { it.id == roundId }) throw AcademicException(AcademicStatus.ROUND_CLOSED, "选课未开放，暂无法获取筛选条件")
        val result = invoke("selection.filters", JSONObject().put("roundId", roundId))
        if (result.getString("roundId") != roundId) throw AcademicException(AcademicStatus.PAGE_CHANGED, "筛选轮次不匹配")
        val groups = PluginJson.objects(result.getJSONArray("groups")).map { group ->
            CourseFilterGroup(group.getString("id"), group.getString("label"), group.getString("kind"),
                PluginJson.objects(group.getJSONArray("options")).map { CourseFilterOption(it.getString("value"), it.getString("label")) })
        }
        if (groups.distinctBy { it.id }.size != groups.size || groups.any { it.options.distinctBy { o -> o.value }.size != it.options.size })
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校筛选包含重复标识")
        return CourseFilters(roundId, result.getString("revision"), groups)
    }
    override suspend fun loadCourseContext(): CourseContext = if (has("selection.catalog")) CourseContext(session.epoch,
        pages("selection.catalog").filter { it.getBoolean("open") }.map { CourseScope(it.getString("id"), it.getString("name"), it.optString("termId")) }) else native().loadCourseContext()
    override suspend fun coursePage(context: CourseContext, query: CourseQuery, cursor: String?): AcademicPage<CourseOffer> {
        if (!has("selection.courses")) return native().coursePage(context, query, cursor)
        check(context)
        if (context.scopes.none { it.id == query.scopeId }) throw AcademicException(AcademicStatus.ROUND_CLOSED, "选课轮次已结束")
        val page = invoke("selection.courses", JSONObject().put("roundId", query.scopeId)
            .put("keyword", query.keyword).put("teacher", query.teacher).put("pageSize", query.pageSize.coerceIn(1, 200)).apply {
                cursor?.let { put("cursor", it) }; query.filters?.let { put("filters", it.toJson()) }
            })
        val rows = PluginJson.objects(page.getJSONArray("items")).map {
            if (it.getString("roundId") != query.scopeId) throw AcademicException(AcademicStatus.PAGE_CHANGED, "课程轮次不匹配")
            CourseOffer(it.getString("id"), it.getString("name"), it.optString("teacher"), it.optString("time"), it.optString("location"), it.optString("credits"),
                number(it,"capacity"), number(it,"selected"), query.scopeId,
                mapOf("sectionId" to it.optString("sectionId"), "jxbmc" to it.optString("sectionName")), sectionCount = number(it,"sectionCount"))
        }
        return AcademicPage(rows, page.optString("nextCursor").takeIf(String::isNotBlank))
    }
    override suspend fun listCourses(context: CourseContext, query: CourseQuery): List<CourseOffer> {
        if (!has("selection.courses")) return native().listCourses(context, query)
        val rows = mutableListOf<CourseOffer>()
        val history = CoursePageHistory()
        var position: CoursePosition? = CoursePosition()
        while (position != null && rows.size < query.start + query.pageSize) {
            val page = scopedCoursePage(context, query, position)
            rows += history.accept(position, page)
            position = page.next
        }
        return rows.drop(query.start).take(query.pageSize)
    }
    override suspend fun sectionPage(course: CourseOffer, cursor: String?): AcademicPage<CourseSection> {
        if (!has("selection.sections")) return native().sectionPage(course, cursor)
        val page = invoke("selection.sections", JSONObject().put("roundId", course.scopeId).put("courseId", course.stableId)
            .put("pageSize", 200).apply { cursor?.let { put("cursor", it) } })
        val rows = PluginJson.objects(page.getJSONArray("items")).map {
            if (it.getString("courseId") != course.stableId) throw AcademicException(AcademicStatus.PAGE_CHANGED, "教学班课程身份不匹配")
            CourseSection(it.getString("id"), course.stableId, it.optString("name"), it.optString("teacher"), it.optString("time"), it.optString("location"), number(it,"capacity"), number(it,"selected"))
        }
        return AcademicPage(rows, page.optString("nextCursor").takeIf(String::isNotBlank))
    }
    override suspend fun listSections(course: CourseOffer): List<CourseSection> {
        val rows = linkedMapOf<String, CourseSection>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = sectionPage(course, cursor)
            page.items.forEach { rows[it.stableId] = it }
            cursor = page.nextCursor
            if (rows.size > 10000 || cursor != null && (!cursors.add(cursor) || cursors.size >= 100))
                throw AcademicException(AcademicStatus.PAGE_CHANGED, "教学班分页异常")
        } while (cursor != null)
        return rows.values.toList()
    }
    override suspend fun selected(context: CourseContext): List<SelectedCourse> = if (has("selection.enrolled")) {
        check(context); pages("selection.enrolled").map(::enrollment)
    } else native().selected(context)
    override suspend fun select(target: SelectionTarget): SelectionResult = if (has("selection.select")) {
        if (!target.confirmed) throw AcademicException(AcademicStatus.VALIDATION_FAILED, "请先确认选课")
        val result = invoke("selection.select", target(target), true)
        SelectionResult(if (result.getBoolean("confirmed")) AcademicStatus.SUCCESS else AcademicStatus.RESULT_UNKNOWN,
            result.optString("message"), result.optJSONObject("enrollment")?.let(::enrollment))
    } else native().select(target)
    override suspend fun drop(target: SelectionTarget): OperationResult = if (has("selection.drop")) {
        if (!target.confirmed) throw AcademicException(AcademicStatus.VALIDATION_FAILED, "请先确认退课")
        val result = invoke("selection.drop", target(target).put("enrollmentId", target.course.raw["academic_selected_id"].orEmpty()), true)
        OperationResult(if (result.getBoolean("confirmed")) AcademicStatus.SUCCESS else AcademicStatus.RESULT_UNKNOWN, result.optString("message"), result.getBoolean("confirmed"))
    } else native().drop(target)
    private fun target(target: SelectionTarget) = JSONObject().put("courseId", target.course.stableId).put("sectionId", target.section.stableId).put("roundId", target.course.scopeId)
    private fun enrollment(item: JSONObject) = SelectedCourse(item.getString("id"), item.getString("name"), item.optString("teacher"), item.getString("courseId"), item.getString("sectionId"),
        mapOf("sksj" to item.optString("time"), "skdd" to item.optString("location"), "xf" to item.optString("credits"), "academic_selected_id" to item.getString("id")))
    private fun number(json: JSONObject, key: String) = if (json.has(key)) json.getInt(key) else null
    private fun check(context: CourseContext) { if (context.sessionEpoch != session.epoch || session.retired) throw AcademicException(AcademicStatus.SESSION_EXPIRED, "选课会话已失效") }
    private fun has(method: String) = method in pinned.manifest.capabilities
    private fun native() = base ?: unsupported("selection")
    private fun unsupported(method: String): Nothing = throw AcademicException(AcademicStatus.UNSUPPORTED, "该学校尚未适配此功能")
    companion object {
        fun status(code: PluginErrorCode): AcademicStatus = when (code) {
            PluginErrorCode.NOT_OPEN -> AcademicStatus.ROUND_CLOSED
            PluginErrorCode.WEB_LOGIN_REQUIRED -> AcademicStatus.HUMAN_VERIFICATION_REQUIRED
            PluginErrorCode.TIMEOUT -> AcademicStatus.NETWORK_RETRYABLE
            PluginErrorCode.CANCELLED, PluginErrorCode.RUNTIME_EXITED, PluginErrorCode.RESOURCE_LIMIT, PluginErrorCode.BAD_SIGNATURE -> AcademicStatus.PAGE_CHANGED
            else -> runCatching { AcademicStatus.valueOf(code.name) }.getOrDefault(AcademicStatus.PAGE_CHANGED)
        }
    }
}

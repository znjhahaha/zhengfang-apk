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
    private val baseSchool: com.tyust.course.model.SchoolConfig? = null
) : AcademicProtocolAdapter, AcademicStudyAdapter, AcademicCaptchaLogin, SessionBackedAdapter {
    private val schema = PluginSchema(PluginJson.parse(app.assets.open("academic-plugin/contract.schema.json").bufferedReader().use { it.readText() }))
    private var continuation: String? = null
    var webLogin: JSONObject? = null
        private set
    val version: String get() = pinned.manifest.version
    fun rebind(newSession: AcademicSession) = PluginAcademicAdapter(app, pinned, newSession,
        baseSchool?.let { AcademicGatewayFactory.createBuiltin(it, newSession) },
        baseSchool?.let { AcademicStudyReader(it, newSession, AcademicHttpTransport(it, newSession)) }, baseSchool)
    @Volatile var lastTrace: List<JSONObject> = emptyList()
        private set
    private val storageRoot = File(app.filesDir, if (session.key.accountKey.startsWith("dev:")) "academic-plugin-development/${pinned.digest}" else "academic-plugin-storage")
    fun clearDevelopmentData() {
        require(session.key.accountKey.startsWith("dev:"))
        session.invalidate()
        storageRoot.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }

    suspend fun invoke(method: String, args: JSONObject = JSONObject(), confirmed: Boolean = false): JSONObject = session.withProtocolLock {
        if (method !in pinned.manifest.capabilities) unsupported(method)
        val op = PluginOperation(session, pinned.manifest, method, development = !pinned.official, confirmed = confirmed)
        val host = PluginHost(op, storageRoot)
        try {
            val result = PluginSandboxClient(app).execute(pinned.source, args, op, host)
            schema.response(method, result)
        } catch (e: PluginException) { val failure = op.failure(e.code, e.message.orEmpty()); throw AcademicException(status(failure.code), failure.message.orEmpty(), e) }
        finally { lastTrace = host.report() }
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
        session.username = credentials.username
        auth(invoke("auth.start", JSONObject().put("username", credentials.username).put("password", credentials.password)))
    } else native().login(credentials)
    override suspend fun validateSession(): LoginResult = if (has("auth.validate")) auth(invoke("auth.validate")) else native().validateSession()
    override suspend fun submitCaptcha(code: String): LoginResult = if (has("auth.resume"))
        auth(invoke("auth.resume", JSONObject().put("continuationId", continuation ?: unsupported("auth.resume")).put("captcha", code)))
        else (native() as? AcademicCaptchaLogin)?.submitCaptcha(code) ?: unsupported("auth.resume")
    suspend fun resumeWebLogin(): LoginResult = auth(invoke("auth.resume", JSONObject().put("continuationId", continuation ?: unsupported("auth.resume")).put("webLoginCompleted", true)))
    override suspend fun refreshCaptcha(): CaptchaChallenge? = if (has("auth.refreshCaptcha"))
        auth(invoke("auth.refreshCaptcha", JSONObject().put("continuationId", continuation ?: unsupported("auth.refreshCaptcha")))).captcha
        else (native() as? AcademicCaptchaLogin)?.refreshCaptcha()
    override fun clearLoginState() { continuation = null; webLogin = null; (base as? AcademicCaptchaLogin)?.clearLoginState() }
    override fun cookieHeader() = session.cookieHeader()
    private fun auth(data: JSONObject): LoginResult = when (data.getString("status")) {
        "authenticated" -> LoginResult(AcademicStatus.SUCCESS, data.getString("studentName"), data.getString("studentId"))
        "captcha" -> { continuation = data.getString("continuationId"); LoginResult(AcademicStatus.CAPTCHA_REQUIRED,
            captcha = CaptchaChallenge(data.getString("imageBase64").decodeBase64()?.toByteArray() ?: ByteArray(0), "captcha", null)) }
        "webLogin" -> { continuation = data.getString("continuationId"); webLogin = data
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
                item.getInt("startPeriod"), item.getInt("endPeriod"), (0 until item.getJSONArray("weeks").length()).joinToString(",") { item.getJSONArray("weeks").getInt(it).toString() } + "周", item.getString("id"))
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
    override suspend fun loadCourseContext(): CourseContext = if (has("selection.catalog")) CourseContext(session.epoch,
        pages("selection.catalog").filter { it.getBoolean("open") }.map { CourseScope(it.getString("id"), it.getString("name"), it.optString("termId")) }) else native().loadCourseContext()
    override suspend fun listCourses(context: CourseContext, query: CourseQuery): List<CourseOffer> = if (has("selection.courses")) {
        check(context)
        val scopes = context.scopes.filter { query.scopeId.isBlank() || it.id == query.scopeId }
        scopes.flatMap { scope -> pages("selection.courses", JSONObject().put("roundId", scope.id).put("keyword", query.keyword).put("teacher", query.teacher)).map {
            CourseOffer(it.getString("id"), it.getString("name"), it.optString("teacher"), it.optString("time"), it.optString("location"), it.optString("credits"),
                number(it,"capacity"), number(it,"selected"), scope.id, mapOf("sectionId" to it.optString("sectionId"), "jxbmc" to it.optString("sectionName")))
        } }.drop(query.start).take(query.pageSize)
    } else native().listCourses(context, query)
    override suspend fun listSections(course: CourseOffer): List<CourseSection> = if (has("selection.sections")) pages("selection.sections", JSONObject().put("roundId", course.scopeId).put("courseId", course.stableId)).map {
        if (it.getString("courseId") != course.stableId) throw AcademicException(AcademicStatus.PAGE_CHANGED, "教学班课程身份不匹配")
        CourseSection(it.getString("id"), course.stableId, it.optString("name"), it.optString("teacher"), it.optString("time"), it.optString("location"), number(it,"capacity"), number(it,"selected"))
    } else native().listSections(course)
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

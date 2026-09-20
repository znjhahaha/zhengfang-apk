package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

internal class ZfAcademicAdapter(school: SchoolConfig, session: AcademicSession, transport: AcademicHttpTransport) :
    BaseAcademicAdapter(school, session, transport, AcademicSystem.ZF), AcademicCaptchaLogin {
    @Volatile private var casAttempt: CasLoginAttempt? = null

    private class CasLoginAttempt(
        val sso: ZhengfangCasSsoClient,
        val username: String,
        var password: String,
        var loginPage: ZhengfangCasProtocol.LoginPage,
        var publicKey: ZhengfangCasProtocol.PublicKey,
        val sessionEpoch: Long
    )

    override suspend fun login(credentials: Credentials): LoginResult = serial {
        clearLoginState()
        session.invalidate()
        session.username = credentials.username
        val ssoEntry = discoverSsoEntry()
        if (ssoEntry != null) loginViaCas(ssoEntry, credentials) else loginViaForm(credentials)
    }

    // ---- 正方 CAS 统一身份认证（SSO）登录 ----

    /** 探测 {教务根}/sso/zfiotlogin 是否重定向到正方定制 CAS。 */
    private suspend fun discoverSsoEntry(): ZhengfangCasSsoClient? {
        val base = (school.getFullBasePath().trimEnd('/') + "/").toHttpUrlOrNull() ?: return null
        return ZhengfangCasSsoClient.discover(base, requireHttps = school.protocol.equals("https", true))
    }

    private suspend fun loginViaCas(ssoEntry: ZhengfangCasSsoClient, credentials: Credentials): LoginResult {
        // CAS 域名记入白名单：登录成功后随学校配置持久化，同时修复 WebView 跳转被拦截的问题
        val casUrl = ssoEntry.casLoginUrl
        val defaultPort = if (casUrl.scheme == "https") 443 else 80
        val casHost = buildString {
            append(casUrl.host.lowercase())
            if (casUrl.port != defaultPort) append(':').append(casUrl.port)
        }
        if (casHost.isNotBlank() && !school.allowedAcademicHosts.contains(casHost)) school.allowedAcademicHosts.add(casHost)
        val loginPage = ssoEntry.fetchLoginPage()
        val publicKey = ssoEntry.fetchPublicKey()
        val attempt = CasLoginAttempt(ssoEntry, credentials.username, credentials.password, loginPage, publicKey, session.epoch)
        return if (ssoEntry.fetchKaptchaRequired()) {
            val image = ssoEntry.fetchCaptchaImage()
            casAttempt = attempt
            LoginResult(AcademicStatus.CAPTCHA_REQUIRED, captcha = CaptchaChallenge(image, "authcode", "cas"))
        } else {
            submitCasLogin(attempt, "")
        }
    }

    private suspend fun submitCasLogin(attempt: CasLoginAttempt, authcode: String, retryCount: Int = 0): LoginResult {
        val encrypted = try {
            ZhengfangCasProtocol.encryptPassword(attempt.password, attempt.publicKey)
        } catch (_: ZhengfangCasProtocol.ProtocolException) {
            return finishCasLogin(LoginResult(AcademicStatus.PAGE_CHANGED, message = "统一认证密码加密失败"))
        }
        return when (val outcome = attempt.sso.submitLoginForm(attempt.username, encrypted, attempt.loginPage, authcode)) {
            is ZhengfangCasSsoClient.SubmitOutcome.FlowExecutionError -> {
                // CAS 多节点偶发 flow 状态解码失败: 换新的 execution 重试，无需用户介入
                if (retryCount >= MAX_CAS_RETRIES) {
                    finishCasLogin(LoginResult(AcademicStatus.PAGE_CHANGED, message = "统一认证暂时不可用，请稍后重试"))
                } else {
                    attempt.loginPage = attempt.sso.fetchLoginPage()
                    submitCasLogin(attempt, authcode, retryCount + 1)
                }
            }
            is ZhengfangCasSsoClient.SubmitOutcome.Rejected -> handleCasRejection(attempt, outcome.html)
            is ZhengfangCasSsoClient.SubmitOutcome.Authenticated -> completeCasLogin(attempt)
        }
    }

    private suspend fun handleCasRejection(attempt: CasLoginAttempt, html: String): LoginResult {
        // 失败页通常带新 execution，刷新后供下次提交使用
        try {
            attempt.loginPage = attempt.sso.fetchLoginPage()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 刷新失败时保留旧 execution，继续按失败页内容分类
        }
        val error = ZhengfangCasProtocol.errorMessage(html)
        return when {
            ZhengfangCasProtocol.indicatesInvalidCredentials(error) || ZhengfangCasProtocol.indicatesInvalidCredentials(html) ->
                finishCasLogin(LoginResult(AcademicStatus.INVALID_CREDENTIALS))
            ZhengfangCasProtocol.indicatesCaptchaProblem(error) || ZhengfangCasProtocol.indicatesCaptchaProblem(html) -> {
                // 按应用约定：验证码被拒时返回不带图片的 CAPTCHA_REQUIRED → 上层提示验证码错误，
                // UI 随后通过 refreshCaptcha 主动获取新图
                casAttempt = attempt
                LoginResult(AcademicStatus.CAPTCHA_REQUIRED, message = error.ifBlank { "验证码不正确" })
            }
            else -> finishCasLogin(LoginResult(
                AcademicStatus.VALIDATION_FAILED,
                message = error.ifBlank { "统一认证登录失败，请检查账号密码或稍后重试" }
            ))
        }
    }

    private suspend fun completeCasLogin(attempt: CasLoginAttempt): LoginResult {
        val ticketUrl = attempt.sso.fetchServiceTicket()
        val landing = attempt.sso.exchangeTicket(ticketUrl)
        if (AcademicHtml.isLoginPage(landing.body)) {
            return finishCasLogin(LoginResult(AcademicStatus.SESSION_EXPIRED, message = "教务系统登录会话建立失败，请重试"))
        }
        importTeachingCookies(attempt.sso)
        val identity = validateIdentity()
        return if (identity != null) {
            finishCasLogin(LoginResult(AcademicStatus.SUCCESS, identity.first, identity.second))
        } else {
            finishCasLogin(LoginResult(AcademicStatus.VALIDATION_FAILED, message = "统一认证登录成功，但未能读取学号，请重试或使用教务网页登录"))
        }
    }

    private fun importTeachingCookies(sso: ZhengfangCasSsoClient) {
        val base = (school.getFullBasePath().trimEnd('/') + "/").toHttpUrlOrNull() ?: return
        session.cookies.saveFromResponse(base, sso.teachingCookies())
    }

    private fun finishCasLogin(result: LoginResult): LoginResult = result.also {
        if (it.status != AcademicStatus.CAPTCHA_REQUIRED) clearLoginState()
    }

    private fun currentCasAttempt(): CasLoginAttempt? = casAttempt?.takeIf { it.sessionEpoch == session.epoch }
        .also { if (it == null) clearLoginState() }

    override suspend fun submitCaptcha(code: String): LoginResult = serial {
        val attempt = currentCasAttempt() ?: return@serial LoginResult(AcademicStatus.SESSION_EXPIRED, message = "登录会话已失效，请重新登录")
        if (code.isBlank()) return@serial LoginResult(AcademicStatus.CAPTCHA_REQUIRED, message = "请输入验证码")
        submitCasLogin(attempt, code.trim())
    }

    override suspend fun refreshCaptcha(): CaptchaChallenge? = serial {
        val attempt = currentCasAttempt() ?: return@serial null
        try {
            CaptchaChallenge(attempt.sso.fetchCaptchaImage(), "authcode", "cas")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    override fun clearLoginState() {
        casAttempt?.sso?.clear()
        casAttempt = null
    }

    // ---- 直登表单登录（无 CAS 入口的学校） ----

    private suspend fun loginViaForm(credentials: Credentials): LoginResult = serial {
        session.username = credentials.username
        val page = transport.get(transport.appUrl("xtgl/login_slogin.html?time=${System.currentTimeMillis()}"))
        val document = Jsoup.parse(page.text, page.url)
        val hidden = AcademicHtml.hiddenFields(document).toMutableMap()
        if (hidden["csrftoken"].isNullOrBlank()) return@serial LoginResult(AcademicStatus.PAGE_CHANGED, message = "Missing csrftoken")
        val captchaImage = document.select("img").firstOrNull { it.attr("src").contains("yzm", true) || it.attr("src").contains("captcha", true) }
        if (captchaImage != null) return@serial LoginResult(AcademicStatus.HUMAN_VERIFICATION_REQUIRED, message = "Complete the school's captcha in WebView")
        val password = if (hidden["mmsfjm"] == "1") {
            val key = transport.get(transport.appUrl("xtgl/login_getPublicKey.html?time=${System.currentTimeMillis()}&_=${System.currentTimeMillis()}"))
            val json = JSONObject(key.text)
            AcademicCrypto.rsaBase64(json.optString("modulus"), json.optString("exponent"), credentials.password)
        } else credentials.password
        hidden["yhm"] = credentials.username
        hidden["mm"] = password
        hidden["language"] = hidden["language"].orEmpty().ifBlank { "zh_CN" }
        val result = transport.postForm(transport.appUrl("xtgl/login_slogin.html"), hidden.toList(), page.url)
        transport.get(transport.appUrl("xtgl/index_initMenu.html"))
        val identity = validateIdentity()
        when {
            identity != null -> LoginResult(AcademicStatus.SUCCESS, identity.first, identity.second)
            result.text.contains("密码错误") || result.text.contains("用户名或密码") -> LoginResult(AcademicStatus.INVALID_CREDENTIALS, message = "Invalid credentials")
            else -> LoginResult(AcademicStatus.VALIDATION_FAILED, message = "Login response could not be verified")
        }
    }

    private suspend fun validateIdentity(): Pair<String, String>? {
        val response = transport.get(transport.appUrl("xtgl/index_cxYhxxIndex.html?gnmkdm=index"))
        var identity = parseName(Jsoup.parse(response.text, response.url))
        if (identity.second.isBlank()) identity = zfMenuIdentityFallback(identity)
        return identity.takeIf { it.first.isNotBlank() || it.second.isNotBlank() }
    }

    override suspend fun loadCourseContext(): CourseContext = serial {
        val indexResponse = transport.get(transport.appUrl("${school.courseIndexPath}?gnmkdm=${school.courseGnmkdm}&layout=default"))
        val document = Jsoup.parse(indexResponse.text, indexResponse.url)
        ZfResponses.requirePage(indexResponse)
        val indexFields = AcademicHtml.hiddenFields(document)
        val categories = ZfCourseCategories.parse(indexResponse.text, indexFields)
        if (categories.isEmpty() && indexFields["xkxnm"].isNullOrBlank()) {
            if (AcademicJson.status(indexResponse.text) == AcademicStatus.ROUND_CLOSED)
                return@serial CourseContext(session.epoch, emptyList())
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "未找到选课入口参数，请确认已进入学校选课页面")
        }
        val contexts = if (categories.isEmpty()) listOf(indexFields) else categories.map { category ->
            val displayParams = category.merge(indexFields).apply {
                put("xszxzt", "1"); put("kspage", "0"); put("jspage", "0")
            }
            val display = transport.postForm(transport.appUrl("${school.courseDisplayPath}?gnmkdm=${school.courseGnmkdm}"),
                ZfRequestParams.build(ZfRequestKind.DISPLAY, displayParams).toList(), indexResponse.url, ajax = true)
            ZfResponses.requirePage(display)
            category.merge(indexFields, AcademicHtml.hiddenFields(Jsoup.parse(display.text, display.url)))
        }
        val scopes = contexts.mapIndexed { index, fields ->
            val category = fields["kklxdm"].orEmpty().ifBlank { "default" }
            CourseScope("zf-$index-$category", fields["kklxmc"].orEmpty().ifBlank { category }, fields["xkxnm"].orEmpty(),
                transport.appUrl("${school.courseListPath}?gnmkdm=${school.courseGnmkdm}"),
                transport.appUrl("${school.selectCoursePath}?gnmkdm=${school.courseGnmkdm}"), fields)
        }
        CourseContext(session.epoch, scopes.ifEmpty {
            listOf(CourseScope("zf-default", "选课", params = indexFields))
        })
    }

    override suspend fun listCourses(context: CourseContext, query: CourseQuery): List<CourseOffer> = serial {
        check(context)
        val result = mutableListOf<CourseOffer>()
        for (scope in context.scopes.filter { query.scopeId.isBlank() || it.id == query.scopeId }) {
            val params = zzxkRequestParams(scope.params, query.keyword,
                kspage = (query.start + 1).toString(), jspage = (query.start + query.pageSize).toString())
            val response = transport.postForm(scope.listUrl.ifBlank { transport.appUrl("${school.courseListPath}?gnmkdm=${school.courseGnmkdm}") }, params.toList(), school.courseReferer, ajax = true)
            ZfResponses.list(response, "tmpList", "courses", "items").forEach {
                val course = offer(it, scope.id, arrayOf("kch_id", "kch"))
                result += course.copy(raw = scope.params + course.raw.filterValues(String::isNotBlank))
            }
        }
        result.filter { query.teacher.isBlank() || it.teacher.contains(query.teacher, true) }
    }

    /**
     * 对齐正方 zzxkYzb.js loadCoursesByPaged 的 requestMap：只提交教务期望的约 40 个控制字段。
     * 整页隐藏域全量提交会被部分学校（如河北传媒学院）判为异常请求并返回通用错误页，
     * 这是"教务返回了无法识别的列表"的一个来源。
     */
    private fun zzxkRequestParams(source: Map<String, String>, keyword: String, kspage: String, jspage: String,
                                  extra: Map<String, String> = emptyMap()): LinkedHashMap<String, String> {
        val params = ZfRequestParams.build(ZfRequestKind.COURSES, source)
        if (source["jxbzbkg"] == "1") params["jxbzb"] = source["jxbzb"].orEmpty() else params.remove("jxbzb")
        if (source["jxbzhkg"] == "1") params["zh"] = source["zh"].orEmpty() else params.remove("zh")
        if (keyword.isNotBlank()) params["filter_list[0]"] = keyword
        params["kspage"] = kspage; params["jspage"] = jspage
        params.putAll(extra)
        return params
    }

    override suspend fun listSections(course: CourseOffer): List<CourseSection> = serial {
        val params = ZfRequestParams.build(ZfRequestKind.SECTIONS, course.raw + ("kch_id" to course.stableId))
        val response = transport.postForm(transport.appUrl("${school.courseDetailsPath}?gnmkdm=${school.courseGnmkdm}"), params.toList(), school.courseReferer, ajax = true)
        ZfResponses.list(response, "tmpList", "data", "courses", "jxbList").map { section(it, course) }
    }

    override suspend fun select(target: SelectionTarget): SelectionResult = serial {
        if (!target.confirmed) return@serial SelectionResult(AcademicStatus.VALIDATION_FAILED, "User confirmation is required")
        if (target.course.raw["sessionEpoch"] != session.epoch.toString()) return@serial SelectionResult(AcademicStatus.SESSION_EXPIRED)
        if (target.section.stableId.isBlank() || target.section.selectionId.isBlank())
            return@serial SelectionResult(AcademicStatus.PAGE_CHANGED, "缺少教学班标识，请刷新课程后重新确认")
        val values = (target.course.raw + target.section.raw).toMutableMap()
        values["jxb_ids"] = target.section.selectionId
        values["kch_id"] = target.course.stableId
        values["kcmc"] = target.course.raw["kcmc"].orEmpty().ifBlank { target.course.name }
        values["kklxdm"] = target.course.raw["kklxdm"].orEmpty()
        ZfSelectionControl.from(target.course.raw).applyTo(values)
        val response = transport.postForm(transport.appUrl("${school.selectCoursePath}?gnmkdm=${school.courseGnmkdm}"), ZfRequestParams.build(ZfRequestKind.SELECTION, values).toList(), school.courseReferer, write = true, ajax = true)
        val status = AcademicJson.zfStatus(response.text, response.code)
        if (status == AcademicStatus.SUCCESS) SelectionResult(status, "Selection request accepted", SelectedCourse(target.section.stableId, target.course.name, target.course.teacher, target.course.stableId, target.section.stableId))
        else SelectionResult(status, if (status == AcademicStatus.NO_CAPACITY) "该教学班暂无名额，继续轮询" else ZfResponses.message(response.text))
    }

    override suspend fun selected(context: CourseContext): List<SelectedCourse> = serial {
        check(context)
        val params = ZfRequestParams.build(ZfRequestKind.SELECTED, context.scopes.firstOrNull()?.params.orEmpty())
        val response = transport.postForm(transport.appUrl("${school.selectedCoursesPath}?gnmkdm=${school.courseGnmkdm}"), params.toList(), school.courseReferer, ajax = true)
        ZfResponses.list(response, "tmpList", "courses", "items", "data").map {
            val course = selected(it)
            val id = AcademicJson.string(it, "jxb_id", "do_jxb_id")
            course.copy(stableId = id, sectionId = id, raw = params + course.raw + ("sessionEpoch" to session.epoch.toString()))
        }
    }

    override suspend fun drop(target: SelectionTarget): OperationResult = serial {
        if (!target.confirmed) return@serial OperationResult(AcademicStatus.VALIDATION_FAILED, "User confirmation is required")
        if (target.course.raw["sessionEpoch"] != session.epoch.toString()) return@serial OperationResult(AcademicStatus.SESSION_EXPIRED)
        val values = listOf("kch_id" to target.course.stableId, "jxb_ids" to target.section.selectionId,
            "xkxnm" to target.course.raw["xkxnm"].orEmpty(), "xkxqm" to target.course.raw["xkxqm"].orEmpty(), "txbsfrl" to "0")
        val response = transport.postForm(transport.appUrl("xsxk/zzxkyzb_tuikBcZzxkYzb.html?gnmkdm=${school.courseGnmkdm}"), values, school.courseReferer, write = true, ajax = true)
        OperationResult(AcademicJson.zfStatus(response.text, response.code), AcademicJson.message(response.text))
    }

    private companion object {
        const val MAX_CAS_RETRIES = 2


    }
}

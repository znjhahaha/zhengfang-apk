package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class QzOldAcademicAdapter(school: SchoolConfig, session: AcademicSession, transport: AcademicHttpTransport) :
    QzAcademicAdapter(school, session, transport, AcademicSystem.QZ_OLD), AcademicCaptchaLogin {
    @Volatile private var loginAttempt: AcademicLoginAttempt? = null

    override suspend fun login(credentials: Credentials): LoginResult = serial {
        clearLoginState()
        session.invalidate()
        session.username = credentials.username
        val root = transport.get(transport.appUrl(""))
        val page = if (AcademicLoginHtml.form(root) != null) root else transport.get(transport.appUrl("xk/LoginToXk"))
        val document = Jsoup.parse(page.text, page.url)
        val form = AcademicLoginHtml.form(page) ?: return@serial LoginResult(AcademicStatus.PAGE_CHANGED, message = "未找到学校登录表单")
        var scripts = page.text
        if (!scripts.contains("flag=sess") && !scripts.contains("encodeInp")) {
            for (script in AcademicHtml.scriptUrls(document, page.url).filter { it.contains("conwork", true) || it.contains("login", true) }.take(4))
                scripts += "\n" + transport.get(script, page.url).text
        }
        scripts = activeLoginScript(form, scripts)
        if (!scripts.contains("flag=sess", true) && !(scripts.contains("encodeInp") && scripts.contains("%%%")))
            return@serial LoginResult(AcademicStatus.HUMAN_VERIFICATION_REQUIRED, message = "学校登录编码无法识别，请使用教务网页登录")
        val attempt = AcademicLoginAttempt(credentials, page, session.epoch, scripts)
        loginAttempt = attempt
        val captcha = AcademicLoginHtml.captcha(form, "RANDOMCODE")
        if (captcha != null) LoginResult(AcademicStatus.CAPTCHA_REQUIRED, captcha = captcha.load(transport, page.url))
        else submitLogin(attempt, "")
    }

    override suspend fun submitCaptcha(code: String): LoginResult = serial {
        val attempt = currentLoginAttempt() ?: return@serial LoginResult(AcademicStatus.SESSION_EXPIRED, message = "登录会话已失效，请重新登录")
        if (code.isBlank()) return@serial LoginResult(AcademicStatus.CAPTCHA_REQUIRED, message = "请输入验证码")
        submitLogin(attempt, code.trim())
    }

    override suspend fun refreshCaptcha(): CaptchaChallenge? = serial {
        val attempt = currentLoginAttempt() ?: return@serial null
        val form = AcademicLoginHtml.form(attempt.page) ?: return@serial null
        AcademicLoginHtml.captcha(form, "RANDOMCODE")?.load(transport, attempt.page.url, refresh = true)
    }

    override fun clearLoginState() { loginAttempt = null }

    // Some templates retain an unused shift-login function next to the active Base64
    // handler. Inspect the function the form actually calls; never evaluate page JS.
    private fun activeLoginScript(form: org.jsoup.nodes.Element, scripts: String): String {
        val events = listOf(form.attr("onsubmit")) + form.select("[onclick]").map { it.attr("onclick") }
        val names = events.mapNotNull { Regex("""(?:return\s+)?([\w$]+)\s*\(""").find(it)?.groupValues?.get(1) }
            .filter { it.contains("login", true) || it.contains("submit", true) }.distinct()
        if (names.size != 1) return scripts
        val start = Regex("""function\s+${Regex.escape(names.single())}\s*\([^)]*\)\s*\{""").find(scripts) ?: return scripts
        var depth = 1
        var quote: Char? = null
        var escaped = false
        var lineComment = false
        var blockComment = false
        var index = start.range.last + 1
        while (index < scripts.length) {
            val ch = scripts[index]
            val next = scripts.getOrNull(index + 1)
            when {
                lineComment -> if (ch == '\n' || ch == '\r') lineComment = false
                blockComment -> if (ch == '*' && next == '/') { blockComment = false; index++ }
                quote != null -> if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == quote) quote = null
                ch == '/' && next == '/' -> { lineComment = true; index++ }
                ch == '/' && next == '*' -> { blockComment = true; index++ }
                ch == '\'' || ch == '"' || ch == '`' -> quote = ch
                ch == '{' -> depth++
                ch == '}' -> if (--depth == 0) return scripts.substring(start.range.first, index + 1)
            }
            index++
        }
        return "" // An incomplete active handler must not fall back to unrelated code.
    }

    private fun currentLoginAttempt(): AcademicLoginAttempt? = loginAttempt?.takeIf { it.sessionEpoch == session.epoch }
        .also { if (it == null) clearLoginState() }

    private suspend fun submitLogin(attempt: AcademicLoginAttempt, code: String): LoginResult {
        val page = attempt.page
        val form = AcademicLoginHtml.form(page) ?: return finishLogin(LoginResult(AcademicStatus.PAGE_CHANGED, message = "学校登录表单已变化"))
        val credentials = attempt.credentials
        val scripts = attempt.scripts
        val fields = AcademicHtml.formFields(form).toMap().toMutableMap()
        if (scripts.contains("flag=sess", true)) {
            // Old QZ installations use either LoginToXk or Logon.do for this handshake.
            // Read the literal endpoint without executing school JavaScript.
            val endpoint = Regex("""["']([^"'<>\s]*[?&]flag=sess(?:&[^"'<>\s]*)?)["']""", RegexOption.IGNORE_CASE)
                .find(scripts)?.groupValues?.get(1)
                ?: return finishLogin(LoginResult(AcademicStatus.PAGE_CHANGED, message = "未找到学校的登录编码地址"))
            val target = transport.appUrl(endpoint).toHttpUrlOrNull()
            val origin = page.url.toHttpUrlOrNull()
            if (target == null || origin == null || target.scheme != origin.scheme || target.host != origin.host || target.port != origin.port ||
                target.username.isNotEmpty() || target.password.isNotEmpty())
                throw AcademicException(AcademicStatus.UNTRUSTED_URL, "学校登录编码地址超出当前站点")
            if (!(target.encodedPath.endsWith("/xk/LoginToXk") ||
                    target.encodedPath.endsWith("/Logon.do") && target.queryParameter("method") == "logon"))
                return finishLogin(LoginResult(AcademicStatus.PAGE_CHANGED, message = "学校登录编码地址无法识别"))
            val response = transport.postForm(target.toString(), emptyList(), page.url, ajax = true)
            val token = runCatching { JSONObject(response.text).getString("data") }.getOrDefault(response.text.trim().trim('"'))
            val parts = token.split('#', limit = 2)
            if (response.code !in 200..299 || parts.size != 2 || !parts[1].matches(Regex("[0-9]+")))
                return finishLogin(LoginResult(AcademicStatus.PAGE_CHANGED, message = "登录编码参数已变化"))
            val inputs = form.select("input[name]")
            val usernameField = inputs.firstOrNull { it.attr("name") in setOf("USERNAME", "userAccount") }?.attr("name")
            val passwordInput = inputs.firstOrNull { it.attr("name") in setOf("PASSWORD", "userPassword") }
            if (usernameField == null || passwordInput == null)
                return finishLogin(LoginResult(AcademicStatus.PAGE_CHANGED, message = "学校登录字段已变化"))
            val passwordField = passwordInput.attr("name")
            val passwordId = passwordInput.id().ifBlank { passwordField }
            val clearsPassword = Regex("""(?:\b${Regex.escape(passwordField)}\b|getElementById\(\s*["']${Regex.escape(passwordId)}["']\s*\))\s*\.value\s*=\s*["']{2}""")
                .containsMatchIn(scripts) && fields["loginMethod"] != "logonLdap"
            fields[usernameField] = credentials.username
            fields[passwordField] = if (clearsPassword) "" else credentials.password
            fields["encoded"] = LoginEncoding.qzOldShift(credentials.username, credentials.password, parts[0], parts[1])
        } else if (scripts.contains("encodeInp") && scripts.contains("%%%")) {
            fields["userAccount"] = credentials.username
            fields["userPassword"] = ""
            fields["encoded"] = LoginEncoding.qzOldBase64(credentials.username, credentials.password)
        } else return finishLogin(LoginResult(AcademicStatus.HUMAN_VERIFICATION_REQUIRED, message = "学校登录编码无法识别，请使用教务网页登录"))
        AcademicLoginHtml.captcha(form, "RANDOMCODE")?.let { fields[it.fieldName] = code }
        val result = transport.postForm(AcademicHtml.action(form, page.url), fields.toList(), page.url)
        AcademicLoginHtml.form(result)?.let { attempt.page = result }
        AcademicLoginHtml.failure(result)?.let { failure ->
            if (failure.status == AcademicStatus.CAPTCHA_REQUIRED) {
                val updatedForm = AcademicLoginHtml.form(attempt.page)
                val captcha = updatedForm?.let { AcademicLoginHtml.captcha(it, "RANDOMCODE") }
                    ?: return finishLogin(LoginResult(AcademicStatus.HUMAN_VERIFICATION_REQUIRED, message = "学校要求在教务网页完成验证"))
                return if (code.isBlank()) failure.copy(captcha = captcha.load(transport, attempt.page.url)) else failure
            }
            return finishLogin(failure)
        }
        return finishLogin(validateSession())
    }

    private fun finishLogin(result: LoginResult): LoginResult = result.also {
        if (it.status != AcademicStatus.CAPTCHA_REQUIRED) clearLoginState()
    }

    override suspend fun validateSession(): LoginResult = serial {
        val page = transport.get(transport.appUrl("framework/xsMain.jsp"))
        if (AcademicHtml.isLoginPage(page.text) || page.code !in 200..299) return@serial LoginResult(AcademicStatus.SESSION_EXPIRED)
        val document = Jsoup.parse(page.text, page.url)
        var identity = parseName(document)
        val valid = identity.first.isNotBlank() || identity.second.isNotBlank() ||
            listOf("学生个人中心", "退出登录", "xsxk/xklc_list", "学生选课").any(page.text::contains)
        if (!valid) return@serial LoginResult(AcademicStatus.SESSION_EXPIRED, message = "无法验证学校登录状态")
        if (identity.first.isBlank()) {
            val profile = document.select("iframe[src]").firstOrNull {
                URI(it.absUrl("src")).path.endsWith("/framework/xsMain_new.jsp")
            }
            if (profile != null) {
                val details = transport.get(profile.absUrl("src"), page.url)
                requirePage(details)
                val found = parseName(Jsoup.parse(details.text, details.url))
                identity = found.first.ifBlank { identity.first } to found.second.ifBlank { identity.second }
            }
        }
        val studentId = identity.second.ifBlank { session.username }
        if (studentId.isNotBlank()) session.username = studentId
        LoginResult(AcademicStatus.SUCCESS, identity.first, studentId)
    }

    override suspend fun loadCourseContext(): CourseContext = serial {
        val page = transport.get(transport.appUrl("xsxk/xklc_list"), transport.appUrl("framework/xsMain.jsp"))
        requirePage(page)
        val document = Jsoup.parse(page.text, page.url)
        data class RoundEntry(val url: String, val id: String, val name: String, val declaration: Boolean)
        val links = document.select("a[href], [onclick]").mapNotNull { element ->
            if (!AcademicHtml.isEnabledControl(element)) return@mapNotNull null
            val call = Regex("""\b(comeInXkIndx|toxk)\s*\(\s*['"]([^'"]+)['"]\s*\)""").find(element.attr("onclick") + element.attr("href"))
            val direct = element.absUrl("href").toHttpUrlOrNull()
            val round = call?.groupValues?.get(2) ?: direct?.queryParameterValues("jx0502zbid")?.singleOrNull()
            if (round.isNullOrBlank()) return@mapNotNull null
            val target = if (call != null) transport.appUrl("xsxk/" +
                (if (call.groupValues[1] == "toxk") "xklc_view" else "xsxk_index") + "?jx0502zbid=" + encode(round))
            else element.absUrl("href").takeIf {
                direct?.encodedPath?.endsWith("/xsxk/xklc_view") == true ||
                    direct?.encodedPath?.endsWith("/xsxk/xsxk_index") == true
            }
            target?.let { RoundEntry(it, round, element.closest("tr")?.text() ?: element.text(), call?.groupValues?.get(1) == "toxk") }
        }.distinctBy { it.url }
        if (links.isEmpty()) {
            val direct = discoverScopes(page, emptyMap(), "选课")
            if (direct.isNotEmpty()) return@serial CourseContext(session.epoch, direct)
            val roundTable = document.select("table").firstOrNull { table ->
                table.select("th").any { it.text().trim() == "选课名称" } &&
                    table.select("th").any { it.text().trim() == "选课时间" }
            }
            if (roundTable?.text()?.let { it.contains("未查询到数据") || it.contains("暂无数据") } == true ||
                AcademicJson.status(document.text()) == AcademicStatus.ROUND_CLOSED)
                return@serial CourseContext(session.epoch, emptyList())
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "未发现学校提供的选课轮次")
        }
        val scopes = links.flatMap { (url, id, name, declaration) ->
            val entry = if (URI(url).path.endsWith("/xklc_view")) reviewedRound(page, url, id, declaration) else transport.get(url, page.url)
            requirePage(entry)
            val found = discoverScopes(entry, mapOf("roundId" to id, "roundPage" to entry.url), name)
            if (found.isEmpty() && AcademicJson.status(Jsoup.parse(entry.text).text()) != AcademicStatus.ROUND_CLOSED)
                throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校选课入口未提供可识别的课程类别")
            found
        }
        CourseContext(session.epoch, scopes)
    }

    private suspend fun reviewedRound(list: AcademicResponse, url: String, id: String, checkDeclaration: Boolean): AcademicResponse {
        if (id.isBlank()) throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校选课入口缺少唯一轮次")
        if (checkDeclaration) checkRoundDeclaration(list)
        val overview = transport.get(url, list.url)
        requirePage(overview)
        val document = Jsoup.parse(overview.text, overview.url)
        if (AcademicJson.status(document.text()) == AcademicStatus.ROUND_CLOSED) return overview
        val buttons = document.select("[onclick]").filter(AcademicHtml::isEnabledControl).mapNotNull { control ->
            val call = Regex("""^\s*(xsxkOpen|xk)\s*\(\s*['"]([^'"]+)['"]\s*(?:,\s*['"]0['"]\s*)?\)\s*;?\s*$""")
                .matchEntire(control.attr("onclick")) ?: return@mapNotNull null
            if (call.groupValues[2] != id) return@mapNotNull null
            val function = QzScriptParser.function(overview.text, call.groupValues[1])
            Regex("""window\.open\(\s*['"]([^'"]*/xsxk/xsxk_index\?jx0502zbid=)['"]\s*\+\s*jx0502zbid\s*\)""")
                .find(function)?.groupValues?.get(1)?.let { URI(overview.url).resolve(it + encode(id)).toString() }
        }.distinct()
        val entry = buttons.singleOrNull()
            ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校未提供唯一可用的选课入口")
        return transport.get(entry, overview.url)
    }

    private suspend fun checkRoundDeclaration(list: AcademicResponse) {
        val script = QzScriptParser.function(list.text, "toxk")
        if (!Regex("""url\s*:\s*['"][^'"]*/xsxk/mzlist\.do['"]""").containsMatchIn(script))
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校选课声明检查地址已变化")
        val response = transport.postForm(transport.appUrl("xsxk/mzlist.do"), emptyList(), list.url, ajax = true)
        requirePage(response)
        val declaration = runCatching { JSONObject(response.text) }.getOrNull()
            ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "无法确认学校选课声明状态")
        if (!declaration.optBoolean("success") || !declaration.has("istc"))
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "无法确认学校选课声明状态")
        if (declaration.optBoolean("istc"))
            throw AcademicException(AcademicStatus.HUMAN_VERIFICATION_REQUIRED, "请先在教务网页阅读并确认选课声明")
    }

    override suspend fun selected(context: CourseContext): List<SelectedCourse> = serial {
        check(context)
        selectedInRounds(context) ?: selectedFromMenu("framework/xsMain.jsp")
    }
}

package com.tyust.course.login

import com.tyust.course.model.SchoolConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class ZjutSsoEndpoints(
    val ssoLogin: HttpUrl,
    val teachingService: HttpUrl,
    val teachingBase: HttpUrl
) {
    companion object {
        fun production() = ZjutSsoEndpoints(
            ssoLogin = "https://oauth.zjut.edu.cn/cas/login".toHttpUrl(),
            teachingService = "http://www.gdjw.zjut.edu.cn/sso/zfiotlogin".toHttpUrl(),
            teachingBase = "http://www.gdjw.zjut.edu.cn/".toHttpUrl()
        )
    }
}

/**
 * 浙江工业大学统一身份认证登录网关。
 *
 * 实测协议链路(2026-08):
 * 1. GET  /cas/login                     → execution 令牌 + JSESSIONID
 * 2. GET  /cas/v2/getPubKey              → RSA 公钥(modulus 每次变化, 动态计算块长)
 * 3. GET  /cas/v2/getKaptchaStatus       → true 时需验证码(图片 /cas/kaptcha?time=)
 * 4. POST /cas/login                     → 302 表示成功, 响应种下 iPlanetDirectoryPro SSO 会话
 * 5. GET  /cas/login?service=<zfiotlogin> → ST 票据 → /sso/zfiotlogin 验票
 *    → /jwglxt/ticketlogin(种正方 JSESSIONID) → index_initMenu 登录成功
 *
 * 已知坑: CAS 多节点偶发 "Error decoding flow execution", 重新获取 execution 重试即可。
 */
class ZjutSsoLoginManager internal constructor(
    private val endpoints: ZjutSsoEndpoints = ZjutSsoEndpoints.production()
) : PasswordLoginGateway {
    private val stateLock = Any()
    private var activeAttempt: LoginAttempt? = null

    override fun login(
        school: SchoolConfig,
        username: String,
        password: String,
        callback: PasswordLoginCallback
    ) {
        clearSensitiveState()
        if (school.id != ZJUT_SCHOOL_ID) {
            callback.onError("当前学校未配置统一身份认证登录")
            return
        }
        if (username.isBlank() || password.isEmpty()) {
            callback.onInvalidCredentials()
            return
        }

        val cookieJar = MatchingMemoryCookieJar()
        val client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val attempt = LoginAttempt(
            school = school,
            username = username,
            password = password,
            callback = callback,
            cookieJar = cookieJar,
            client = client
        )
        synchronized(stateLock) {
            activeAttempt = attempt
        }
        fetchLoginPage(attempt)
    }

    override fun submitCaptcha(captchaCode: String, callback: PasswordLoginCallback) {
        val attempt = synchronized(stateLock) { activeAttempt }
        if (attempt == null || attempt.completed.get()) {
            callback.onError("登录会话已失效，请重新登录")
            return
        }
        if (captchaCode.isBlank()) {
            callback.onCaptchaInvalid()
            return
        }
        if (!attempt.submissionInFlight.compareAndSet(false, true)) {
            callback.onError("登录请求正在处理，请稍候")
            return
        }
        attempt.callback = callback
        submitLoginForm(attempt, captchaCode.trim(), flowRetryCount = 0)
    }

    override fun refreshCaptcha(callback: (ByteArray?) -> Unit) {
        val attempt = synchronized(stateLock) { activeAttempt }
        val captchaUrl = attempt?.captchaUrl
        if (attempt == null || captchaUrl == null || attempt.completed.get()) {
            callback(null)
            return
        }
        val refreshed = captchaUrl.newBuilder()
            .setQueryParameter("time", System.currentTimeMillis().toString())
            .build()
        fetchCaptchaBytes(attempt, refreshed, callback)
    }

    override fun clearSensitiveState() {
        val attempt = synchronized(stateLock) {
            val current = activeAttempt
            activeAttempt = null
            current
        } ?: return
        attempt.completed.set(true)
        attempt.currentCall?.cancel()
        attempt.eraseSensitiveState(clearCookies = true)
    }

    // ---- 阶段一: CAS 账密登录 ----

    private fun fetchLoginPage(attempt: LoginAttempt) {
        if (!isAllowed(endpoints.ssoLogin)) {
            fail(attempt, "统一认证地址未通过安全校验")
            return
        }
        execute(
            attempt,
            Request.Builder().url(endpoints.ssoLogin).header("User-Agent", USER_AGENT).get().build(),
            onFailure = { fail(attempt, "无法连接统一认证服务") }
        ) { response ->
            response.use {
                if (!it.isSuccessful) {
                    fail(attempt, "统一认证服务返回错误 (${it.code})")
                    return@use
                }
                val html = it.body?.string().orEmpty()
                val form = try {
                    ZjutSsoProtocol.parseLoginPage(html)
                } catch (_: ZjutSsoProtocol.ProtocolException) {
                    fail(attempt, "无法读取统一认证登录参数")
                    return@use
                }
                attempt.form = form
                fetchPublicKey(attempt)
            }
        }
    }

    private fun fetchPublicKey(attempt: LoginAttempt) {
        val url = endpoints.ssoLogin.resolve(PUB_KEY_PATH)
        if (url == null || !isAllowed(url)) {
            fail(attempt, "统一认证公钥地址无效")
            return
        }
        execute(
            attempt,
            Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build(),
            onFailure = { fail(attempt, "无法获取统一认证公钥") }
        ) { response ->
            response.use {
                if (!it.isSuccessful) {
                    fail(attempt, "统一认证公钥获取失败 (${it.code})")
                    return@use
                }
                attempt.publicKey = try {
                    ZjutSsoProtocol.parsePublicKey(it.body?.string().orEmpty())
                } catch (_: ZjutSsoProtocol.ProtocolException) {
                    fail(attempt, "统一认证公钥格式无效")
                    null
                    return@use
                }
                fetchKaptchaStatus(attempt)
            }
        }
    }

    private fun fetchKaptchaStatus(attempt: LoginAttempt) {
        val url = endpoints.ssoLogin.resolve(KAPTCHA_STATUS_PATH)
        if (url == null || !isAllowed(url)) {
            fail(attempt, "统一认证验证码状态地址无效")
            return
        }
        execute(
            attempt,
            Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build(),
            onFailure = { fail(attempt, "无法获取验证码开关状态") }
        ) { response ->
            response.use {
                if (!it.isSuccessful) {
                    fail(attempt, "验证码开关状态获取失败 (${it.code})")
                    return@use
                }
                val required = ZjutSsoProtocol.parseKaptchaStatus(it.body?.string().orEmpty())
                if (required) {
                    val captchaUrl = endpoints.ssoLogin.resolve(
                        "$KAPTCHA_IMAGE_PATH?time=${System.currentTimeMillis()}"
                    )
                    if (captchaUrl == null || !isAllowed(captchaUrl)) {
                        fail(attempt, "统一认证验证码地址无效")
                    } else {
                        attempt.captchaUrl = captchaUrl
                        fetchCaptcha(attempt, captchaUrl)
                    }
                } else {
                    attempt.submissionInFlight.set(true)
                    submitLoginForm(attempt, "", flowRetryCount = 0)
                }
            }
        }
    }

    private fun submitLoginForm(
        attempt: LoginAttempt,
        captchaCode: String,
        flowRetryCount: Int
    ) {
        val form = attempt.form
        if (form == null || attempt.password.isEmpty()) {
            fail(attempt, "登录会话已失效，请重新登录")
            return
        }
        val postUrl = if (form.formAction.isNullOrBlank()) {
            endpoints.ssoLogin
        } else {
            endpoints.ssoLogin.resolve(form.formAction)
        }
        if (postUrl == null || !isAllowed(postUrl)) {
            fail(attempt, "统一认证表单地址未通过安全校验")
            return
        }
        val publicKey = attempt.publicKey
        if (publicKey == null) {
            fail(attempt, "统一认证公钥缺失，请重新登录")
            return
        }
        val encryptedPassword = try {
            ZjutSsoProtocol.encryptPassword(attempt.password, publicKey.modulusHex, publicKey.exponentHex)
        } catch (_: ZjutSsoProtocol.ProtocolException) {
            fail(attempt, "统一认证密码加密失败")
            return
        }
        val body = FormBody.Builder()
            .add("username", attempt.username)
            .add("password", encryptedPassword)
            .add("execution", form.execution)
            .add("_eventId", "submit")
            .apply { if (captchaCode.isNotEmpty()) add("authcode", captchaCode) }
            .build()
        val request = Request.Builder()
            .url(postUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", endpoints.ssoLogin.toString())
            .header("Origin", originOf(endpoints.ssoLogin))
            .post(body)
            .build()
        execute(
            attempt,
            request,
            onFailure = { fail(attempt, "统一认证提交连接失败") }
        ) { response ->
            handleLoginResponse(attempt, response, captchaCode, flowRetryCount)
        }
    }

    private fun handleLoginResponse(
        attempt: LoginAttempt,
        response: Response,
        captchaCode: String,
        flowRetryCount: Int
    ) {
        response.use {
            if (it.code in REDIRECT_CODES) {
                val location = it.header("Location").orEmpty()
                if (location.contains(FLOW_EXECUTION_ERROR_MARKER) && flowRetryCount < MAX_FLOW_RETRIES) {
                    // CAS 多节点偶发 flow 状态解码失败: 换新的 execution 重试, 无需用户介入
                    refreshExecutionAndRetry(attempt, captchaCode, flowRetryCount + 1)
                    return@use
                }
                if (it.code == 302 || it.code == 303) {
                    if (attempt.cookieJar.cookieHeaderFor(
                            endpoints.ssoLogin,
                            setByHost = endpoints.ssoLogin.host
                        ).contains(SSO_SESSION_COOKIE)
                    ) {
                        startServiceLogin(attempt, redirectCount = 0)
                        return@use
                    }
                }
                // 未取得 SSO 会话的 302: 跟随后按登录页错误处理
                val nextUrl = location.takeIf(String::isNotBlank)?.let(it.request.url::resolve)
                if (nextUrl != null && isAllowed(nextUrl)) {
                    execute(
                        attempt,
                        Request.Builder().url(nextUrl).header("User-Agent", USER_AGENT).get().build(),
                        onFailure = { fail(attempt, "统一认证服务连接失败") }
                    ) { follow ->
                        follow.use { fr ->
                            handleLoginFailurePage(attempt, fr.body?.string().orEmpty(), captchaCode)
                        }
                    }
                    return@use
                }
                fail(attempt, "统一认证返回了无效跳转地址")
                return@use
            }

            val body = it.body?.string().orEmpty()
            handleLoginFailurePage(attempt, body, captchaCode)
        }
    }

    private fun refreshExecutionAndRetry(
        attempt: LoginAttempt,
        captchaCode: String,
        nextRetryCount: Int
    ) {
        execute(
            attempt,
            Request.Builder().url(endpoints.ssoLogin).header("User-Agent", USER_AGENT).get().build(),
            onFailure = { fail(attempt, "统一认证服务连接失败") }
        ) { response ->
            response.use {
                if (!it.isSuccessful) {
                    fail(attempt, "统一认证服务返回错误 (${it.code})")
                    return@use
                }
                val form = try {
                    ZjutSsoProtocol.parseLoginPage(it.body?.string().orEmpty())
                } catch (_: ZjutSsoProtocol.ProtocolException) {
                    fail(attempt, "无法读取统一认证登录参数")
                    return@use
                }
                attempt.form = form
                submitLoginForm(attempt, captchaCode, nextRetryCount)
            }
        }
    }

    private fun handleLoginFailurePage(
        attempt: LoginAttempt,
        html: String,
        captchaCode: String
    ) {
        val updatedForm = runCatching { ZjutSsoProtocol.parseLoginPage(html) }.getOrNull()
        if (updatedForm != null) {
            attempt.form = updatedForm
        }
        val errorMessage = Jsoup.parse(html)
            .getElementById("errormsg")
            ?.text()
            .orEmpty()
        when {
            errorMessage.indicatesInvalidCredentials() || html.indicatesInvalidCredentials() ->
                invalidCredentials(attempt)
            captchaCode.isNotEmpty() && (errorMessage.indicatesCaptchaProblem() || html.indicatesCaptchaProblem()) -> {
                attempt.submissionInFlight.set(false)
                if (!attempt.completed.get()) attempt.callback.onCaptchaInvalid()
            }
            errorMessage.indicatesCaptchaProblem() || html.indicatesCaptchaProblem() -> {
                val captchaUrl = attempt.captchaUrl
                    ?: endpoints.ssoLogin.resolve("$KAPTCHA_IMAGE_PATH?time=${System.currentTimeMillis()}")
                if (captchaUrl == null || !isAllowed(captchaUrl)) {
                    fail(attempt, "统一认证验证码地址无效")
                } else {
                    attempt.captchaUrl = captchaUrl
                    attempt.submissionInFlight.set(false)
                    fetchCaptcha(attempt, captchaUrl)
                }
            }
            else -> fail(attempt, "统一认证登录失败，请检查账号密码或稍后重试")
        }
    }

    // ---- 阶段二: CAS service 登录 → 正方教务会话 ----

    private fun startServiceLogin(attempt: LoginAttempt, redirectCount: Int) {
        if (redirectCount > MAX_REDIRECTS) {
            fail(attempt, "统一认证跳转次数过多")
            return
        }
        val serviceUrl = endpoints.ssoLogin.newBuilder()
            .setQueryParameter("service", endpoints.teachingService.toString())
            .build()
        execute(
            attempt,
            Request.Builder().url(serviceUrl).header("User-Agent", USER_AGENT).get().build(),
            onFailure = { fail(attempt, "已登录统一认证，但发起教务登录失败") }
        ) { response ->
            followTeachingRedirects(attempt, response, redirectCount)
        }
    }

    private fun followTeachingRedirects(
        attempt: LoginAttempt,
        response: Response,
        redirectCount: Int
    ) {
        if (response.code in REDIRECT_CODES) {
            val location = response.header("Location")
            val nextUrl = location?.let(response.request.url::resolve)
            if (nextUrl == null || !isAllowed(nextUrl)) {
                response.close()
                fail(attempt, "教务登录返回了无效跳转地址")
                return
            }
            if (nextUrl.host == endpoints.teachingBase.host) {
                if (!nextUrl.queryParameter("ticket").isNullOrBlank()) {
                    attempt.sawServiceTicket = true
                }
                if (nextUrl.encodedPath.contains(TICKET_LOGIN_PATH)) {
                    attempt.sawTeachingTicketLogin = true
                }
            }
            val nextRequest = redirectedRequest(response, nextUrl)
            response.close()
            if (redirectCount + 1 > MAX_REDIRECTS) {
                fail(attempt, "教务登录跳转次数过多")
                return
            }
            execute(
                attempt,
                nextRequest,
                onFailure = {
                    if (attempt.sawServiceTicket) {
                        fail(attempt, "已取得统一认证票据，但连接教务系统失败")
                    } else {
                        fail(attempt, "连接教务系统失败")
                    }
                }
            ) { next ->
                followTeachingRedirects(attempt, next, redirectCount + 1)
            }
            return
        }

        response.use {
            val body = it.body?.string().orEmpty()
            if (isAuthenticatedIndex(it.request.url, body)) {
                if (!attempt.sawServiceTicket) {
                    fail(attempt, "统一认证未返回有效服务票据")
                    return@use
                }
                val cookieUrl = endpoints.teachingBase.newBuilder()
                    .encodedPath("/jwglxt/")
                    .query(null)
                    .build()
                val cookieHeader = attempt.cookieJar.cookieHeaderFor(
                    cookieUrl,
                    setByHost = endpoints.teachingBase.host
                )
                val effectiveCookie = cookieHeader
                    .split("; ")
                    .filter { part -> part.isNotBlank() && !part.startsWith("rememberMe=") }
                    .joinToString("; ")
                if (effectiveCookie.isBlank()) {
                    fail(attempt, "教务系统未返回有效登录会话")
                } else {
                    succeed(attempt, effectiveCookie)
                }
                return@use
            }
            fail(
                attempt,
                if (attempt.sawServiceTicket) "教务系统登录会话建立失败" else "统一认证登录失败，请稍后重试"
            )
        }
    }

    private fun redirectedRequest(response: Response, nextUrl: HttpUrl): Request {
        val preserveBody = response.code == 307 || response.code == 308
        val builder = response.request.newBuilder().url(nextUrl)
        return if (preserveBody) {
            builder.method(response.request.method, response.request.body).build()
        } else {
            builder.removeHeader("Content-Type")
                .removeHeader("Content-Length")
                .get()
                .build()
        }
    }

    private fun fetchCaptcha(attempt: LoginAttempt, url: HttpUrl) {
        fetchCaptchaBytes(attempt, url) { bytes ->
            if (bytes == null || bytes.isEmpty()) {
                fail(attempt, "获取统一认证验证码失败")
            } else if (!attempt.completed.get()) {
                attempt.callback.onCaptchaRequired(bytes)
            }
        }
    }

    private fun fetchCaptchaBytes(
        attempt: LoginAttempt,
        url: HttpUrl,
        callback: (ByteArray?) -> Unit
    ) {
        if (!isAllowed(url)) {
            callback(null)
            return
        }
        execute(
            attempt,
            Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build(),
            onFailure = { callback(null) }
        ) { response ->
            response.use {
                callback(if (it.isSuccessful) it.body?.bytes() else null)
            }
        }
    }

    private fun execute(
        attempt: LoginAttempt,
        request: Request,
        onFailure: (() -> Unit)? = null,
        onResponse: (Response) -> Unit
    ) {
        if (attempt.completed.get()) return
        val call = attempt.client.newCall(request)
        attempt.currentCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (attempt.completed.get()) return
                if (onFailure != null) {
                    onFailure()
                } else {
                    fail(attempt, "网络连接失败，请检查网络后重试")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (attempt.completed.get()) {
                    response.close()
                    return
                }
                try {
                    onResponse(response)
                } catch (_: Exception) {
                    response.close()
                    fail(attempt, "处理统一认证响应失败")
                }
            }
        })
    }

    private fun isAuthenticatedIndex(url: HttpUrl, body: String): Boolean =
        url.host == endpoints.teachingBase.host &&
            url.encodedPath.contains("/xtgl/index_initMenu.html") &&
            !body.contains("用户登录") &&
            !body.contains("login_slogin")

    private fun isAllowed(url: HttpUrl): Boolean =
        (url.host == endpoints.ssoLogin.host && url.scheme == "https") ||
            (url.host == endpoints.teachingBase.host && url.scheme == "http")

    private fun succeed(attempt: LoginAttempt, cookie: String) {
        if (!attempt.completed.compareAndSet(false, true)) return
        detach(attempt)
        attempt.eraseSensitiveState(clearCookies = true)
        attempt.callback.onSuccess(cookie)
    }

    private fun invalidCredentials(attempt: LoginAttempt) {
        if (!attempt.completed.compareAndSet(false, true)) return
        detach(attempt)
        attempt.eraseSensitiveState(clearCookies = true)
        attempt.callback.onInvalidCredentials()
    }

    private fun fail(attempt: LoginAttempt, message: String) {
        if (!attempt.completed.compareAndSet(false, true)) return
        detach(attempt)
        attempt.eraseSensitiveState(clearCookies = true)
        attempt.callback.onError(message)
    }

    private fun detach(attempt: LoginAttempt) {
        synchronized(stateLock) {
            if (activeAttempt === attempt) activeAttempt = null
        }
    }

    private fun originOf(url: HttpUrl): String {
        val defaultPort = if (url.scheme == "https") 443 else 80
        val port = if (url.port == defaultPort) "" else ":${url.port}"
        return "${url.scheme}://${url.host}$port"
    }

    private fun String.indicatesInvalidCredentials(): Boolean =
        contains("用户名或密码不正确") ||
            contains("用户名或密码错误") ||
            contains("账号或密码不正确") ||
            contains("密码错误")

    private fun String.indicatesCaptchaProblem(): Boolean =
        contains("验证码") ||
            contains("invalid captcha", ignoreCase = true) ||
            contains("captcha error", ignoreCase = true) ||
            contains("captcha required", ignoreCase = true)

    private class LoginAttempt(
        val school: SchoolConfig,
        var username: String,
        var password: String,
        @Volatile var callback: PasswordLoginCallback,
        val cookieJar: MatchingMemoryCookieJar,
        val client: OkHttpClient
    ) {
        val completed = AtomicBoolean(false)
        val submissionInFlight = AtomicBoolean(false)
        @Volatile var currentCall: Call? = null
        @Volatile var form: ZjutSsoProtocol.LoginPage? = null
        @Volatile var publicKey: ZjutSsoProtocol.PublicKey? = null
        @Volatile var captchaUrl: HttpUrl? = null
        @Volatile var sawServiceTicket: Boolean = false
        @Volatile var sawTeachingTicketLogin: Boolean = false

        fun eraseSensitiveState(clearCookies: Boolean) {
            username = ""
            password = ""
            form = null
            publicKey = null
            captchaUrl = null
            sawServiceTicket = false
            sawTeachingTicketLogin = false
            submissionInFlight.set(false)
            currentCall = null
            if (clearCookies) cookieJar.clear()
        }
    }

    private companion object {
        const val ZJUT_SCHOOL_ID = "zjut"
        const val MAX_REDIRECTS = 12
        const val MAX_FLOW_RETRIES = 2
        const val SSO_SESSION_COOKIE = "iPlanetDirectoryPro"
        const val FLOW_EXECUTION_ERROR_MARKER = "Error+decoding+flow+execution"
        const val TICKET_LOGIN_PATH = "/jwglxt/ticketlogin"
        const val PUB_KEY_PATH = "v2/getPubKey"
        const val KAPTCHA_STATUS_PATH = "v2/getKaptchaStatus"
        const val KAPTCHA_IMAGE_PATH = "kaptcha"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/139.0.0.0 Mobile Safari/537.36"
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

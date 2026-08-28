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
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class TyustSsoEndpoints(
    val ssoLogin: HttpUrl,
    val teachingService: HttpUrl,
    val teachingBase: HttpUrl,
    val enforceHttps: Boolean = true
) {
    companion object {
        fun production() = TyustSsoEndpoints(
            ssoLogin = "https://sso1.tyust.edu.cn/login".toHttpUrl(),
            teachingService = "https://newjwc.tyust.edu.cn/sso/jasiglogin/jwglxt".toHttpUrl(),
            teachingBase = "https://newjwc.tyust.edu.cn/".toHttpUrl()
        )
    }
}

class TyustSsoLoginManager internal constructor(
    private val endpoints: TyustSsoEndpoints = TyustSsoEndpoints.production()
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
        if (school.id != TYUST_SCHOOL_ID) {
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
        postLogin(attempt, captchaCode.trim(), callback)
    }

    override fun refreshCaptcha(callback: (ByteArray?) -> Unit) {
        val attempt = synchronized(stateLock) { activeAttempt }
        val captchaUrl = attempt?.captchaUrl
        if (attempt == null || captchaUrl == null || attempt.completed.get()) {
            callback(null)
            return
        }
        fetchCaptchaBytes(attempt, captchaUrl, callback)
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

    private fun fetchLoginPage(attempt: LoginAttempt) {
        val url = endpoints.ssoLogin.newBuilder()
            .setQueryParameter("service", endpoints.teachingService.toString())
            .build()
        if (!isAllowed(url)) {
            fail(attempt, "统一认证地址未通过安全校验")
            return
        }
        execute(
            attempt,
            Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build(),
            onFailure = { fail(attempt, "无法连接统一认证服务") }
        ) { response ->
            response.use {
                if (!it.isSuccessful) {
                    fail(attempt, "统一认证服务返回错误 (${it.code})")
                    return@use
                }
                val html = it.body?.string().orEmpty()
                val form = try {
                    TyustSsoProtocol.parseLoginPage(html)
                } catch (_: TyustSsoProtocol.ProtocolException) {
                    fail(attempt, "无法读取统一认证登录参数")
                    return@use
                }
                attempt.loginPageUrl = it.request.url
                attempt.form = form
                attempt.captchaUrl = form.captchaUrl?.let(it.request.url::resolve)
                if (form.captchaRequired) {
                    val captchaUrl = attempt.captchaUrl
                    if (captchaUrl == null || !isAllowed(captchaUrl)) {
                        fail(attempt, "统一认证验证码地址无效")
                    } else {
                        fetchCaptcha(attempt, captchaUrl)
                    }
                } else {
                    postLogin(attempt, "")
                }
            }
        }
    }

    private fun postLogin(
        attempt: LoginAttempt,
        captchaCode: String,
        callback: PasswordLoginCallback? = null
    ) {
        if (!attempt.submissionInFlight.compareAndSet(false, true)) {
            callback?.onError("登录请求正在处理，请稍候")
            return
        }
        if (callback != null) attempt.callback = callback
        submitLoginForm(
            attempt = attempt,
            captchaCode = captchaCode,
            formRefreshCount = 0
        )
    }

    private fun submitLoginForm(
        attempt: LoginAttempt,
        captchaCode: String,
        formRefreshCount: Int
    ) {
        val form = attempt.form
        val loginPageUrl = attempt.loginPageUrl
        if (form == null || loginPageUrl == null || attempt.password.isEmpty()) {
            fail(attempt, "登录会话已失效，请重新登录")
            return
        }
        val postUrl = if (form.formAction.isNullOrBlank()) {
            loginPageUrl
        } else {
            loginPageUrl.resolve(form.formAction)
        }
        if (postUrl == null || !isAllowed(postUrl)) {
            fail(attempt, "统一认证表单地址未通过安全校验")
            return
        }
        val encryptedPassword = try {
            TyustSsoProtocol.encryptPassword(attempt.password, form.cryptoKey)
        } catch (_: TyustSsoProtocol.ProtocolException) {
            fail(attempt, "统一认证密码加密失败")
            return
        }
        val encryptedCaptchaPayload = try {
            TyustSsoProtocol.encryptCaptchaPayload(form.cryptoKey)
        } catch (_: TyustSsoProtocol.ProtocolException) {
            fail(attempt, "统一认证密码加密失败")
            return
        }
        val body = FormBody.Builder()
            .add("username", attempt.username)
            .add("type", "UsernamePassword")
            .add("_eventId", "submit")
            .add("geolocation", "")
            .add("execution", form.execution)
            .add("captcha_code", captchaCode)
            .add("croypto", form.cryptoKey)
            .add("password", encryptedPassword)
            .add("captcha_payload", encryptedCaptchaPayload)
            .build()
        val request = Request.Builder()
            .url(postUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", loginPageUrl.toString())
            .header("Origin", originOf(postUrl))
            .post(body)
            .build()
        executeCas(
            attempt = attempt,
            request = request,
            redirectCount = 0,
            captchaWasSubmitted = captchaCode.isNotEmpty(),
            captchaCode = captchaCode,
            formRefreshCount = formRefreshCount
        )
    }

    private fun executeCas(
        attempt: LoginAttempt,
        request: Request,
        redirectCount: Int,
        captchaWasSubmitted: Boolean,
        captchaCode: String,
        formRefreshCount: Int
    ) {
        if (redirectCount > MAX_REDIRECTS) {
            fail(attempt, "统一认证跳转次数过多")
            return
        }
        if (!isAllowed(request.url)) {
            fail(attempt, "统一认证跳转地址未通过安全校验")
            return
        }
        val connectionFailureMessage = if (request.url.host == endpoints.ssoLogin.host) {
            "统一认证提交连接失败"
        } else if (attempt.sawServiceTicket) {
            "已取得统一认证票据，但连接教务系统失败"
        } else {
            "连接教务系统失败"
        }
        execute(
            attempt,
            request,
            onFailure = { fail(attempt, connectionFailureMessage) }
        ) { response ->
            if (response.code in REDIRECT_CODES) {
                val location = response.header("Location")
                val nextUrl = location?.let(response.request.url::resolve)
                if (nextUrl == null || !isAllowed(nextUrl)) {
                    response.close()
                    fail(attempt, "统一认证返回了无效跳转地址")
                    return@execute
                }
                if (nextUrl.host == endpoints.teachingBase.host &&
                    !nextUrl.queryParameter("ticket").isNullOrBlank()
                ) {
                    attempt.sawServiceTicket = true
                }
                val nextRequest = redirectedRequest(response, nextUrl)
                response.close()
                executeCas(
                    attempt = attempt,
                    request = nextRequest,
                    redirectCount = redirectCount + 1,
                    captchaWasSubmitted = captchaWasSubmitted,
                    captchaCode = captchaCode,
                    formRefreshCount = formRefreshCount
                )
                return@execute
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
                    if (cookieHeader.isBlank()) {
                        fail(attempt, "教务系统未返回有效登录会话")
                    } else {
                        succeed(attempt, cookieHeader)
                    }
                    return@use
                }

                val previousForm = attempt.form
                val updatedForm = runCatching { TyustSsoProtocol.parseLoginPage(body) }.getOrNull()
                if (updatedForm != null) {
                    attempt.loginPageUrl = it.request.url
                    attempt.form = updatedForm
                    attempt.captchaUrl = updatedForm.captchaUrl?.let(it.request.url::resolve)
                }

                if (it.code == 200 && body.indicatesInvalidCredentials()) {
                    invalidCredentials(attempt)
                } else if (captchaWasSubmitted && body.indicatesCaptchaProblem()) {
                    attempt.submissionInFlight.set(false)
                    if (!attempt.completed.get()) attempt.callback.onCaptchaInvalid()
                } else if (!captchaWasSubmitted &&
                    (updatedForm?.captchaRequired == true || body.indicatesCaptchaProblem())
                ) {
                    val captchaUrl = attempt.captchaUrl
                    if (captchaUrl == null || !isAllowed(captchaUrl)) {
                        fail(attempt, "统一认证验证码地址无效")
                    } else {
                        attempt.submissionInFlight.set(false)
                        fetchCaptcha(attempt, captchaUrl)
                    }
                } else if (it.code == 401 && updatedForm != null) {
                    // The 2026-08 SSO rejects bad credentials with 401 plus a fresh
                    // login page that carries no error text; treat it as invalid
                    // credentials rather than a generic failure.
                    invalidCredentials(attempt)
                } else if (
                    it.code == 200 &&
                    updatedForm != null &&
                    updatedForm != previousForm &&
                    formRefreshCount < MAX_FORM_REFRESHES
                ) {
                    submitLoginForm(
                        attempt = attempt,
                        captchaCode = captchaCode,
                        formRefreshCount = formRefreshCount + 1
                    )
                } else {
                    fail(attempt, "统一认证登录失败，请检查账号密码或稍后重试")
                }
            }
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
            !body.contains("login-page-flowkey")

    private fun isAllowed(url: HttpUrl): Boolean {
        if (endpoints.enforceHttps && url.scheme != "https") return false
        return url.host == endpoints.ssoLogin.host || url.host == endpoints.teachingBase.host
    }

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
        contains("1030023") ||
            contains("用户名或密码不正确") ||
            contains("用户名或密码错误") ||
            contains("账号或密码不正确")

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
        @Volatile var form: TyustSsoProtocol.LoginPage? = null
        @Volatile var loginPageUrl: HttpUrl? = null
        @Volatile var captchaUrl: HttpUrl? = null
        @Volatile var sawServiceTicket: Boolean = false

        fun eraseSensitiveState(clearCookies: Boolean) {
            username = ""
            password = ""
            form = null
            loginPageUrl = null
            captchaUrl = null
            sawServiceTicket = false
            submissionInFlight.set(false)
            currentCall = null
            if (clearCookies) cookieJar.clear()
        }
    }

    private companion object {
        const val TYUST_SCHOOL_ID = "tyust"
        const val MAX_REDIRECTS = 12
        const val MAX_FORM_REFRESHES = 1
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/139.0.0.0 Mobile Safari/537.36"
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

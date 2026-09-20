package com.tyust.course.academic

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Two cookie stores keep parent-domain CAS cookies out of the teaching session. */
internal class QzCasSsoClient(val entry: QzCasEntry) {
    private val casCookies = AcademicCookieJar()
    private val academicCookies = AcademicCookieJar()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = jar(url).saveFromResponse(url, cookies)
            override fun loadForRequest(url: HttpUrl): List<Cookie> = jar(url).loadForRequest(url)
        }).build()
    @Volatile private var credentials: Credentials? = null
    @Volatile private var cleared = false
    private var page: QzCasProtocol.Page? = null
    private var key = ""
    private var failures = 0

    private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    private fun jar(url: HttpUrl) = if (sameOrigin(url, entry.login)) casCookies else academicCookies
    private fun checkUrl(url: HttpUrl, posting: Boolean = false) {
        val cas = sameOrigin(url, entry.login) && url.encodedPath.startsWith(entry.login.encodedPath.substringBeforeLast('/') + "/")
        val academic = sameOrigin(url, entry.service) && url.encodedPath.startsWith(entry.service.encodedPath.substringBeforeLast('/') + "/")
        if ((!cas && !academic) || (posting && !cas) || url.username.isNotEmpty() || url.password.isNotEmpty())
            throw AcademicException(AcademicStatus.UNTRUSTED_URL, "统一认证跳转到了未配置的地址")
        if (academic && url.queryParameter("ticket") != null && url.encodedPath != entry.service.encodedPath)
            throw AcademicException(AcademicStatus.UNTRUSTED_URL, "统一认证票据回调地址不匹配")
        if (cas && url.encodedPath == entry.login.encodedPath && url.queryParameter("service")?.let { it != entry.service.toString() } == true)
            throw AcademicException(AcademicStatus.UNTRUSTED_URL, "统一认证 service 地址不匹配")
    }

    suspend fun begin(value: Credentials): LoginResult = try { beginLogin(value) }
        catch (e: kotlinx.coroutines.CancellationException) { clear(); throw e }

    private suspend fun beginLogin(value: Credentials): LoginResult {
        credentials = value
        var response = request(entry.service)
        if (sameOrigin(response.url, entry.service)) {
            val redirect = QzCasProtocol.redirect(response.text, response.url)
                ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校未返回统一认证入口")
            checkUrl(redirect)
            if (!sameOrigin(redirect, entry.login) || redirect.encodedPath != entry.login.encodedPath)
                throw AcademicException(AcademicStatus.UNTRUSTED_URL, "统一认证入口不匹配")
            response = request(entry.loginWithService)
        }
        page = QzCasProtocol.page(response.text)
        key = request(requireNotNull(entry.login.resolve("jwt/publicKey"))).text
        return if (requireNotNull(page).captchaRequired) captchaResult() else submit("")
    }

    suspend fun submit(code: String): LoginResult {
        val login = credentials ?: return LoginResult(AcademicStatus.SESSION_EXPIRED)
        var current = page ?: return LoginResult(AcademicStatus.SESSION_EXPIRED)
        val encrypted = QzCasProtocol.encrypt(login.password, key)
        val values = current.fields.toMutableMap().apply {
            this["username"] = login.username; this["password"] = encrypted; this["captcha"] = code
            this["failN"] = failures.toString()
        }
        if (current.mfaEnabled) {
            val mfa = request(requireNotNull(entry.login.resolve("mfa/detect")), mapOf(
                "username" to login.username, "password" to encrypted, "fpVisitorId" to current.fields["fpVisitorId"].orEmpty()))
            val result = runCatching { JSONObject(mfa.text) }.getOrNull()
            val data = result?.optJSONObject("data")
            if (result?.optInt("code", -1) != 0 || data == null || data.optBoolean("need", true))
                return LoginResult(AcademicStatus.HUMAN_VERIFICATION_REQUIRED, message = "请在统一认证网页完成多因素验证")
            values["mfaState"] = data.optString("state")
        }
        val response = request(entry.loginWithService, values)
        if (sameOrigin(response.url, entry.service) && !AcademicHtml.isLoginPage(response.text)) return LoginResult(AcademicStatus.SUCCESS)
        failures++
        val status = QzCasProtocol.failure(response.text)
        if (status in setOf(AcademicStatus.INVALID_CREDENTIALS, AcademicStatus.SESSION_EXPIRED)) return LoginResult(status!!)
        if (status == AcademicStatus.HUMAN_VERIFICATION_REQUIRED) return LoginResult(status, message = "请在统一认证网页完成验证")
        page = QzCasProtocol.page(response.text, failures)
        current = requireNotNull(page)
        if (status == AcademicStatus.CAPTCHA_REQUIRED || current.captchaRequired) {
            // The returned form contains the next execution token; refreshing only the image preserves it.
            return if (code.isBlank()) captchaResult() else LoginResult(AcademicStatus.CAPTCHA_REQUIRED)
        }
        return LoginResult(AcademicStatus.VALIDATION_FAILED, message = "统一认证未完成，请检查账号或使用网页登录")
    }

    suspend fun refreshCaptcha(): CaptchaChallenge {
        val url = requireNotNull(entry.login.resolve("captcha.jpg")).newBuilder()
            .setQueryParameter("_", System.nanoTime().toString()).build()
        val image = request(url).bytes
        val valid = image.size > 4 && ((image[0].toInt() and 255) == 0xff && (image[1].toInt() and 255) == 0xd8 ||
            image.take(4).map { it.toInt() and 255 } == listOf(0x89, 0x50, 0x4e, 0x47) || image.take(3).toByteArray().toString(Charsets.US_ASCII) == "GIF")
        if (!valid) throw AcademicException(AcademicStatus.PAGE_CHANGED, "统一认证未返回有效验证码")
        return CaptchaChallenge(image, "captcha", "cas")
    }
    private suspend fun captchaResult() = LoginResult(AcademicStatus.CAPTCHA_REQUIRED, captcha = refreshCaptcha())
    fun teachingCookies(): List<Cookie> = academicCookies.loadForRequest(entry.service)
    fun clear() { cleared = true; credentials = null; page = null; key = ""; client.dispatcher.cancelAll(); casCookies.retire(); academicCookies.retire() }

    private data class PageResponse(val url: HttpUrl, val bytes: ByteArray) { val text get() = bytes.toString(Charsets.UTF_8) }
    private suspend fun request(initial: HttpUrl, fields: Map<String, String>? = null): PageResponse {
        var url = initial
        var body = fields?.let { FormBody.Builder().apply { it.forEach { (key, value) -> add(key, value) } }.build() }
        repeat(9) {
            if (cleared) throw kotlinx.coroutines.CancellationException("Login cancelled")
            checkUrl(url, body != null)
            val builder = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
            body?.let { builder.post(it).header("Referer", entry.loginWithService.toString()).header("Origin", "${entry.login.scheme}://${entry.login.host}" + if (entry.login.port == 443) "" else ":${entry.login.port}") }
            val response = execute(builder.build())
            response.use {
                if (it.isRedirect) {
                    if (body != null && it.code in listOf(307, 308)) throw AcademicException(AcademicStatus.PAGE_CHANGED, "统一认证要求重新登录")
                    url = it.header("Location")?.let(url::resolve)
                        ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "统一认证缺少跳转地址")
                    body = null
                } else {
                    if (!it.isSuccessful) throw AcademicException(AcademicStatus.NETWORK_RETRYABLE, "统一认证服务暂不可用（HTTP ${it.code}）")
                    val source = it.body?.source() ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "统一认证响应为空")
                    source.request(5_242_881)
                    if (source.buffer.size > 5_242_880) throw AcademicException(AcademicStatus.PAGE_CHANGED, "统一认证响应过大")
                    return PageResponse(url, source.buffer.readByteArray())
                }
            }
        }
        throw AcademicException(AcademicStatus.PAGE_CHANGED, "统一认证跳转次数过多")
    }
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(AcademicException(AcademicStatus.NETWORK_RETRYABLE, "无法连接统一认证服务，请稍后重试")) }
            override fun onResponse(call: Call, response: Response) { if (continuation.isActive) continuation.resume(response) else response.close() }
        })
    }
}

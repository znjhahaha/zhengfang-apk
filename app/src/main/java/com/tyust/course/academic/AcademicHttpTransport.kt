package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.Call
import java.io.IOException
import java.net.URI
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AcademicHttpTransport(
    private val school: SchoolConfig,
    private val session: AcademicSession,
    private val client: OkHttpClient = defaultClient(session.cookies)
) {
    private val allowedHosts = (listOf(school.domain) + school.allowedAcademicHosts)
        .map { it.trim().lowercase().removePrefix("http://").removePrefix("https://").removePrefix(".").substringBefore('/') }
        .filter(String::isNotBlank).toSet()

    fun appUrl(path: String): String {
        if (path.isEmpty()) return school.fullBasePath.trimEnd('/') + "/"
        return AcademicUrls.appUrl(school, path)
    }

    suspend fun get(url: String, referer: String? = null, ajax: Boolean = false): AcademicResponse = request("GET", url, null, referer, ajax = ajax)

    suspend fun getImage(url: String, referer: String): ByteArray {
        val response = requestBytes("GET", url, null, referer)
        val bytes = response.bytes
        fun startsWith(vararg signature: Int) = bytes.size >= signature.size &&
            signature.indices.all { (bytes[it].toInt() and 0xff) == signature[it] }
        val isImage = startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ||
            startsWith(0x47, 0x49, 0x46, 0x38, 0x37, 0x61) || startsWith(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) ||
            startsWith(0xff, 0xd8, 0xff) || startsWith(0x42, 0x4d) ||
            (startsWith(0x52, 0x49, 0x46, 0x46) && bytes.size >= 12 && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP")
        if (response.code !in 200..299 || !isImage)
            throw AcademicException(AcademicStatus.PAGE_CHANGED, "学校未返回有效的验证码图片，请重新登录")
        return bytes
    }

    suspend fun writeGet(url: String, referer: String? = null): AcademicResponse = request("GET", url, null, referer, true, ajax = true)

    suspend fun postForm(url: String, fields: Iterable<Pair<String, String>>, referer: String? = null, write: Boolean = false, ajax: Boolean = false): AcademicResponse {
        val charset = session.pageCharset ?: runCatching { Charset.forName(school.pageCharset) }.getOrDefault(Charsets.UTF_8)
        val body = FormBody.Builder(charset).apply { fields.forEach { add(it.first, it.second) } }.build()
        return request("POST", url, body, referer, write, ajax)
    }

    suspend fun postBody(url: String, body: RequestBody, referer: String? = null): AcademicResponse =
        request("POST", url, body, referer)

    private suspend fun request(method: String, initialUrl: String, body: RequestBody?, referer: String?, write: Boolean = false, ajax: Boolean = false): AcademicResponse {
        val response = requestBytes(method, initialUrl, body, referer, write, ajax)
        return AcademicResponse(response.code, response.url, decode(response.bytes, response.contentType), response.headers)
    }

    private data class RawResponse(val code: Int, val url: String, val bytes: ByteArray, val headers: Map<String, List<String>>, val contentType: String?)

    private suspend fun requestBytes(method: String, initialUrl: String, body: RequestBody?, referer: String?, write: Boolean = false, ajax: Boolean = false): RawResponse {
        var url = initialUrl
        var currentMethod = method
        var redirects = 0
        var readRetries = 0
        while (true) {
            session.requireActive()
            val parsed = url.toHttpUrlOrNull()
                ?: throw AcademicException(AcademicStatus.UNTRUSTED_URL, "Invalid academic URL")
            ensureAllowed(parsed)
            val builder = Request.Builder().url(parsed).header("Accept", "text/html,application/json,*/*;q=0.8")
                .header("User-Agent", USER_AGENT)
            if (ajax) builder.header("X-Requested-With", "XMLHttpRequest")
            if (ajax && currentMethod == "POST" && referer != null) {
                val source = referer.toHttpUrlOrNull()
                if (source != null && source.scheme == parsed.scheme && source.host == parsed.host && source.port == parsed.port) {
                    builder.header("Origin", source.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/'))
                }
            }
            if (referer != null) builder.header("Referer", referer)
            if (currentMethod == "POST") builder.post(body ?: FormBody.Builder().build()) else builder.get()
            val request = builder.build()
            try {
                // This school's backend expires sessions on rapid repeated page requests.
                // A 1.2s interval was verified against its authorized read-only workflow.
                if (parsed.host == "jw.cqiivc.com") session.paceRequest(1_200)
                execute(request).use {
                session.requireActive()
                if (it.code in 300..399) {
                    val location = it.headers["Location"] ?: throw AcademicException(AcademicStatus.PAGE_CHANGED, "Redirect has no location")
                    if (write) {
                        // Never replay a write across a redirect. The caller must verify the
                        // selected list before deciding whether a retry is safe.
                        throw AcademicException(AcademicStatus.RESULT_UNKNOWN, "Write request was redirected")
                    }
                    if (currentMethod == "POST") {
                        if (it.code == 307 || it.code == 308) throw AcademicException(AcademicStatus.PAGE_CHANGED, "POST redirect requires a new login")
                        currentMethod = "GET"
                    }
                    if (++redirects > 5) throw AcademicException(AcademicStatus.PAGE_CHANGED, "Too many redirects")
                    val resolved = parsed.resolve(location)
                        ?: throw AcademicException(AcademicStatus.UNTRUSTED_URL, "Invalid redirect")
                    val next = AcademicRedirects.preferVerifiedHttps(parsed, resolved)
                    ensureAllowed(next)
                    url = next.toString()
                    continue
                }
                val source = it.body?.source()
                source?.request((MAX_BODY_BYTES + 1).toLong())
                val bytes = source?.buffer?.readByteArray(minOf(source.buffer.size, (MAX_BODY_BYTES + 1).toLong())) ?: ByteArray(0)
                session.requireActive()
                if (bytes.size > MAX_BODY_BYTES) throw AcademicException(AcademicStatus.PAGE_CHANGED, "Academic response is too large")
                if (it.code == 401 || it.code == 403) throw AcademicException(AcademicStatus.SESSION_EXPIRED, "登录已失效，请重新登录")
                if (it.code == 429 || it.code >= 500) throw AcademicException(if (write && it.code >= 500) AcademicStatus.RESULT_UNKNOWN else AcademicStatus.NETWORK_RETRYABLE, "教务系统暂时不可用（HTTP ${it.code}），请稍后重试")
                return RawResponse(it.code, it.request.url.toString(), bytes, it.headers.toMultimap(), it.headers["Content-Type"])
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (!write && readRetries++ < 1) {
                    delay(250)
                    continue
                }
                val status = if (write) AcademicStatus.RESULT_UNKNOWN else AcademicStatus.NETWORK_RETRYABLE
                throw AcademicException(status, if (write) "请求中断，结果尚未确认，请先刷新已选课程" else "无法连接教务系统，请检查网络或稍后重试", e)
            }
        }
    }

    private fun ensureAllowed(url: HttpUrl) {
        val allowed = AcademicUrlPolicy.isAllowed(url.toString(), school.protocol, allowedHosts)
        if (!allowed) throw AcademicException(AcademicStatus.UNTRUSTED_URL, "Academic redirect is outside the configured school hosts")
        if (school.protocol.equals("https", true) && url.isHttps.not()) {
            throw AcademicException(AcademicStatus.UNTRUSTED_URL, "HTTPS academic configuration cannot downgrade to HTTP")
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { if (continuation.isActive) continuation.resume(response) else response.close() }
        })
    }

    private fun decode(bytes: ByteArray, contentType: String?): String {
        val declaration = Regex("charset\\s*=\\s*['\"]?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
        val charset = declaration.find(contentType.orEmpty())?.groupValues?.getOrNull(1)
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: declaration.find(bytes.take(4096).toByteArray().toString(Charsets.ISO_8859_1))?.groupValues?.getOrNull(1)
                ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: runCatching { Charset.forName(school.pageCharset.ifBlank { "UTF-8" }) }.getOrDefault(Charsets.UTF_8)
        if (contentType.orEmpty().contains("html", true)) session.pageCharset = charset
        return bytes.toString(charset)
    }

    companion object {
        private const val MAX_BODY_BYTES = 5 * 1024 * 1024
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private fun defaultClient(cookieJar: okhttp3.CookieJar) = OkHttpClient.Builder().cookieJar(cookieJar)
            .retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()
    }
}

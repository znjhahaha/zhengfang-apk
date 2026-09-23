package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup

enum class AcademicDetectionStatus { SUCCESS, INVALID_ADDRESS, NETWORK_ERROR, UNKNOWN_SYSTEM }

data class AcademicDetectionResult(
    val status: AcademicDetectionStatus,
    val address: AcademicAddress? = null,
    val system: AcademicSystem? = null,
    val failure: Throwable? = null
) {
    val message: String get() = when (status) {
        AcademicDetectionStatus.SUCCESS -> "已识别为${AcademicCapabilities.selectionLabel(requireNotNull(system))}"
        AcademicDetectionStatus.INVALID_ADDRESS -> "无法解析，请输入有效的教务系统网址"
        AcademicDetectionStatus.NETWORK_ERROR -> "暂时无法连接教务系统，请检查网络后重试，也可以手动选择类型"
        AcademicDetectionStatus.UNKNOWN_SYSTEM -> "网址可以访问，但暂未识别出教务类型，请手动选择"
    }
}

/** Detection owns a temporary, credential-free session and never changes the saved school. */
object AcademicDetection {
    suspend fun detect(input: String, template: SchoolConfig? = null, timeoutMillis: Long = 20_000): AcademicDetectionResult = withContext(Dispatchers.IO) {
        // OkHttp delivers headers asynchronously, but the response body and HTML parser
        // still perform blocking work. A Compose caller must never resume that on Main.
        val address = AcademicAddress.parse(input)
            ?: return@withContext AcademicDetectionResult(AcademicDetectionStatus.INVALID_ADDRESS)
        val initial = input.trim().let { if (it.contains("://")) it else "https://$it" }
        var reachable = false
        var lastFailure: Throwable? = null
        val result = withTimeoutOrNull(timeoutMillis) {
            val roots = listOf(address.basePath, "", "/jsxsd", "/jwglxt").distinct()
            val urls = linkedSetOf(initial)
            roots.forEach { root ->
                val base = "${address.protocol}://${address.domain}$root/"
                urls += base
                listOf("framework/xsMainV.htmlx", "xtgl/login_slogin.html", "default2.aspx").forEach { urls += base + it }
            }
            val probe = template?.let { SchoolConfig.fromJson(it.toJson()) }
                ?: SchoolConfig("detection", "", address.domain, address.protocol)
            probe.domain = address.domain; probe.protocol = address.protocol; probe.basePath = address.basePath
            val session = AcademicSession(AcademicSessionKey(probe.id, "address-detection"), probe.fullBasePath)
            val transport = AcademicHttpTransport(probe, session)
            try {
                for (url in urls) {
                    val page = try { withTimeoutOrNull(5_000) { transport.get(url) } }
                        catch (e: CancellationException) { throw e }
                        catch (e: AcademicException) { lastFailure = e; null }
                        ?: continue
                    reachable = true
                    if (page.code !in 200..299) continue
                    val system = SystemDetector.classify(page.text) ?: continue
                    // A server can redirect the site root into a custom application context.
                    val formAddress = Jsoup.parse(page.text, page.url).select("form[action]")
                        .mapNotNull { AcademicAddress.parse(it.absUrl("action")) }
                        .firstOrNull { it.domain == address.domain && it.basePath.isNotBlank() }
                    val effective = formAddress ?: AcademicAddress.parse(page.url) ?: address
                    return@withTimeoutOrNull AcademicDetectionResult(AcademicDetectionStatus.SUCCESS, effective, system)
                }
                null
            } finally { session.retire() }
        }
        result ?: AcademicDetectionResult(
            if (reachable) AcademicDetectionStatus.UNKNOWN_SYSTEM else AcademicDetectionStatus.NETWORK_ERROR,
            address, failure = lastFailure)
    }
}

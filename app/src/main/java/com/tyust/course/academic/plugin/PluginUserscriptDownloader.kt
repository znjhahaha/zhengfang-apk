package com.tyust.course.academic.plugin

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Transport for public originals. School cookies and plugin request headers never enter this client. */
internal class PluginUserscriptDownloader(
    private val policy: PluginUserscriptPolicy,
    private val requireActive: () -> Unit,
    private val client: OkHttpClient = defaultClient,
) {
    fun download(value: String, revalidate: Boolean = false): String {
        // Validate the original before considering a transport; the relay never expands sourcePrefix.
        val original = policy.source(value)
        relayUrl(original)?.let { relay ->
            try {
                requireActive()
                client.newCall(request(relay, revalidate)).execute().use { response ->
                    requireSuccess(response)
                    if (response.header("X-Userscript-Source") != original.toString())
                        throw PluginException(PluginErrorCode.VALIDATION_FAILED, "脚本下载通道的来源不一致")
                    val bytes = body(response)
                    if (response.header("X-Userscript-Sha256") != PluginJson.sha256(bytes))
                        throw PluginException(PluginErrorCode.BAD_SIGNATURE, "脚本下载通道的内容校验失败")
                    val source = String(bytes, Charsets.UTF_8)
                    UserscriptMetadata.parse(source)
                    requireActive()
                    return source
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                // A service outage must not disable a reachable original source.
            } catch (e: PluginException) {
                if (e.code !in setOf(PluginErrorCode.NETWORK_RETRYABLE, PluginErrorCode.VALIDATION_FAILED,
                        PluginErrorCode.BAD_SIGNATURE, PluginErrorCode.RESOURCE_LIMIT)) throw e
            }
        }
        var url = original
        try {
            repeat(4) {
                requireActive()
                client.newCall(request(url, revalidate)).execute().use { response ->
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        val location = response.header("Location")?.takeIf { it.isNotBlank() }
                            ?: throw PluginException(PluginErrorCode.UNTRUSTED_URL, "脚本更新跳转地址为空")
                        url = policy.source(url.resolve(location)?.toString().orEmpty())
                    } else {
                        requireSuccess(response)
                        val source = String(body(response), Charsets.UTF_8)
                        UserscriptMetadata.parse(source)
                        requireActive()
                        return source
                    }
                }
            }
        } catch (_: IOException) {
            requireActive()
            throw PluginException(PluginErrorCode.NETWORK_RETRYABLE, "脚本下载连接失败，请检查网络后重试")
        }
        throw PluginException(PluginErrorCode.UNTRUSTED_URL, "脚本更新跳转过多")
    }

    private fun request(url: HttpUrl, revalidate: Boolean) = Request.Builder().url(url)
        .header("User-Agent", "Zhengfang-Userscript/1")
        .header("Accept", "application/octet-stream, text/javascript, text/x-userscript-meta, text/plain")
        .apply { if (revalidate) header("Cache-Control", "no-cache") }.build()

    private fun requireSuccess(response: Response) {
        if (response.code != 200) throw PluginException(PluginErrorCode.NETWORK_RETRYABLE, "脚本来源返回 ${response.code}")
    }

    private fun body(response: Response): ByteArray = response.body?.byteStream()?.use { it.readBytesBounded(MAX_BYTES) }
        ?: throw PluginException(PluginErrorCode.NETWORK_RETRYABLE, "脚本来源返回空内容")

    companion object {
        private const val MAX_BYTES = 2 * 1024 * 1024
        private val defaultClient = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

        internal fun relayUrl(original: HttpUrl): HttpUrl? {
            if (!original.isHttps || original.host != "update.greasyfork.org" || original.port != 443 ||
                original.username.isNotEmpty() || original.password.isNotEmpty() || original.query != null || original.fragment != null) return null
            val segments = original.pathSegments
            if (segments.size !in 3..4 || segments[0] != "scripts" || !segments[1].matches(Regex("[1-9][0-9]{0,9}")) ||
                segments.size == 4 && !segments[2].matches(Regex("[1-9][0-9]{0,11}"))) return null
            val name = segments.last()
            if (original.encodedPath.length > 2048 || !name.matches(Regex(".+\\.(meta|user)\\.js")) ||
                name.any { it.code < 32 || it.code == 127 || it in "/\\%?#" }) return null
            return ("https://plugins.hidisiwa.xyz/api/userscripts/v1/greasyfork" + original.encodedPath).toHttpUrl()
        }
    }
}

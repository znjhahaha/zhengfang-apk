package com.tyust.course.academic.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.security.PublicKey
import java.util.concurrent.TimeUnit

/** Catalog and downloads are verified using the app's pinned signing key. */
class PluginCatalogClient(private val endpoint: String, private val keys: Map<String, PublicKey>, private val store: PluginPackageStore) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(45, TimeUnit.SECONDS).build()

    suspend fun cached(): List<JSONObject> = withContext(Dispatchers.IO) { store.cachedCatalog(endpoint) }

    suspend fun check(): List<JSONObject> = withContext(Dispatchers.IO) {
        val origin = endpoint.toHttpUrlOrNull() ?: throw PluginException(PluginErrorCode.UNTRUSTED_URL, "无效目录地址")
        val catalog = PluginJson.parse(download(origin, origin, PluginLimits.PACKAGE_BYTES).toString(Charsets.UTF_8))
        val payload = catalog.getJSONObject("payload")
        PluginPackageVerifier.verifySignature(payload, catalog, keys)
        if (payload.getInt("apiVersion") !in 1..PluginLimits.API_VERSION) throw PluginException(PluginErrorCode.UNSUPPORTED, "目录版本不受支持")
        PluginJson.objects(payload.getJSONArray("entries")).also { entries ->
            if (entries.size > 1000 || entries.map { it.getString("id") }.distinct().size != entries.size)
                throw PluginException(PluginErrorCode.VALIDATION_FAILED, "目录数量超限或标识重复")
            store.rememberCatalog(catalog, endpoint)
        }
    }
    suspend fun update(id: String): PluginPackage = withContext(Dispatchers.IO) {
        val entry = check().firstOrNull { it.getString("id") == id }
            ?: throw PluginException(PluginErrorCode.UNSUPPORTED, "目录未提供此适配")
        val origin = endpoint.toHttpUrlOrNull()!!
        val url = origin.resolve(entry.getString("url")) ?: throw PluginException(PluginErrorCode.UNTRUSTED_URL, "无效包地址")
        val bytes = download(url, origin, PluginLimits.PACKAGE_BYTES)
        if (PluginJson.sha256(bytes) != entry.getString("sha256")) throw PluginException(PluginErrorCode.BAD_SIGNATURE, "下载内容摘要不匹配")
        // Verify identity before installation so an incorrect catalog entry cannot switch another provider.
        val manifestJson = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
            var found: JSONObject? = null
            while (true) { val item = zip.nextEntry ?: break; if (item.name == "manifest.json") {
                val limited = zip.readBytesBounded(PluginLimits.EXPANDED_BYTES)
                found = PluginJson.parse(limited.toString(Charsets.UTF_8)); break
            } }
            found ?: throw PluginException(PluginErrorCode.VALIDATION_FAILED, "缺少清单")
        }
        if (manifestJson.getString("id") != id || manifestJson.getString("version") != entry.getString("version"))
            throw PluginException(PluginErrorCode.VALIDATION_FAILED, "目录与包身份不一致")
        currentCoroutineContext().ensureActive()
        store.install(bytes, false)
    }
    private suspend fun download(initial: HttpUrl, origin: HttpUrl, limit: Int): ByteArray {
        var url = initial
        repeat(6) {
            currentCoroutineContext().ensureActive()
            if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port || url.username.isNotEmpty() || url.password.isNotEmpty())
                throw PluginException(PluginErrorCode.UNTRUSTED_URL, "目录下载跳转越界")
            val response = fetch(url, limit)
            if (response.code in 300..399) url = url.resolve(response.location.orEmpty())
                ?: throw PluginException(PluginErrorCode.UNTRUSTED_URL, "无效跳转")
            else {
                if (response.code !in 200..299) throw PluginException(PluginErrorCode.NETWORK_RETRYABLE, "下载失败：${response.code}")
                return response.bytes
            }
        }
        throw PluginException(PluginErrorCode.UNTRUSTED_URL, "下载跳转过多")
    }
    private data class Download(val code: Int, val location: String?, val bytes: ByteArray)
    private suspend fun fetch(url: HttpUrl, limit: Int): Download = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: java.io.IOException) { continuation.resumeWithException(error) }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val downloaded = response.use { Download(it.code, it.header("Location"),
                        if (it.isSuccessful) it.body?.byteStream()?.use { body -> body.readBytesBounded(limit) } ?: ByteArray(0) else ByteArray(0)) }
                    continuation.resume(downloaded)
                } catch (error: Exception) { continuation.resumeWithException(error) }
            }
        })
    }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer); if (n < 0) break
        if (output.size() + n > limit) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "下载内容超过上限")
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}

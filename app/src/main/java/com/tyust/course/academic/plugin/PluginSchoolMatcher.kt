package com.tyust.course.academic.plugin

import com.tyust.course.model.SchoolConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.net.IDN
import java.util.Locale

object PluginSchoolMatcher {
    fun host(value: String): String = IDN.toASCII(value.trim().trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
    fun endpoint(school: SchoolConfig): HttpUrl? = "${school.protocol}://${school.domain}${school.basePath.orEmpty().let { if (it.startsWith('/')) it else "/$it" }}".toHttpUrlOrNull()
    fun key(school: SchoolConfig): String = endpoint(school)?.let { url ->
        host(url.host) + (if (url.port == if (url.isHttps) 443 else 80) "" else ":${url.port}") + url.encodedPath.trimEnd('/').ifEmpty { "/" }
    } ?: school.id
    fun validateRule(rule: JSONObject) {
        val raw = rule.getString("host"); val path = rule.getString("pathPrefix")
        if (runCatching { host(raw) }.getOrNull().isNullOrBlank() || raw.any { it.isWhitespace() || it in "/:@" } ||
            !path.startsWith('/') || path.any { it in "%\\" } || path.split('/').any { it == "." || it == ".." })
            throw PluginException(PluginErrorCode.VALIDATION_FAILED, "学校匹配范围无效")
    }
    fun matches(rule: JSONObject, school: SchoolConfig): Boolean = runCatching {
        validateRule(rule)
        val url = endpoint(school) ?: return false
        val protocol = rule.optString("protocol")
        if (protocol.isNotBlank() && protocol != url.scheme) return false
        val port = rule.optInt("port", if (url.isHttps) 443 else 80)
        val prefix = rule.getString("pathPrefix").trimEnd('/').ifEmpty { "/" }
        host(rule.getString("host")) == host(url.host) && port == url.port &&
            (prefix == "/" || url.encodedPath == prefix || url.encodedPath.startsWith("$prefix/"))
    }.getOrDefault(false)
    fun primary(manifest: PluginManifest): JSONObject? = manifest.json.optJSONObject("school")?.let { school ->
        val config = SchoolConfig.fromJson(school)
        endpoint(config)?.let { url -> JSONObject().put("host", host(url.host)).put("pathPrefix", url.encodedPath).apply { if (url.port != if (url.isHttps) 443 else 80) put("port", url.port) } }
    }
}

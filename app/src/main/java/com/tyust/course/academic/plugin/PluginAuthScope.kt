package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicSession
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Generic redirect/cookie authority. School login protocols remain in TypeScript. */
internal class PluginAuthScope(value: JSONObject) {
    val login: HttpUrl
    val service: HttpUrl
    init {
        if (value.keys().asSequence().toSet() != setOf("loginUrl", "serviceUrl")) denied()
        login = endpoint(value.optString("loginUrl")); service = endpoint(value.optString("serviceUrl"))
        if (login.query != null) denied()
    }
    fun isLogin(url: HttpUrl) = under(url, login)
    fun isService(url: HttpUrl) = under(url, service)
    fun requireAllowed(url: HttpUrl, method: String) {
        endpoint(url.toString())
        if ((!isLogin(url) && !isService(url)) || (method == "POST" && !isLogin(url))) denied()
        val callbacks = url.queryParameterValues("service")
        if (isLogin(url) && url.encodedPath == login.encodedPath && callbacks.isNotEmpty() && callbacks != listOf(service.toString())) denied()
        if (url.queryParameter("ticket") != null && (!isService(url) || url.encodedPath != service.encodedPath)) denied()
    }
    fun registeredHttpCallback(current: HttpUrl, next: HttpUrl): Boolean =
        isLogin(current) && !service.isHttps && origin(next) == origin(service) && next.encodedPath == service.encodedPath
    companion object {
        fun origin(url: HttpUrl) = "${url.scheme}://${url.host}:${url.port}"
        private fun under(url: HttpUrl, base: HttpUrl) = origin(url) == origin(base) && url.encodedPath.startsWith(base.encodedPath.substringBeforeLast('/') + "/")
        private fun endpoint(value: String): HttpUrl {
            val url = value.toHttpUrlOrNull() ?: denied()
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || '%' in url.encodedPath || '\\' in value) denied()
            return url
        }
        private fun denied(): Nothing = throw PluginException(PluginErrorCode.UNTRUSTED_URL, "统一认证跳转与已声明服务不匹配")
    }
}

internal class PluginAcademicCookies(
    private val session: AcademicSession, private val providerId: String,
    private val scope: PluginAuthScope? = null, private val active: () -> Unit
) : CookieJar {
    private val base = session.baseUrl.toHttpUrlOrNull()
    private fun jar(url: HttpUrl): CookieJar {
        active()
        val teaching = if (scope != null) scope.isService(url) && !scope.isLogin(url)
            else base != null && base.host == url.host && (base.port == url.port || base.port in setOf(80, 443) && url.port in setOf(80, 443))
        return if (teaching) session.cookies else session.authenticationCookies(providerId + ":" + PluginAuthScope.origin(url))
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> = jar(url).loadForRequest(url)
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = jar(url).saveFromResponse(url, cookies)
}

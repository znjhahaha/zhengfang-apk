package com.tyust.course.academic.plugin

import android.content.Context
import android.os.Looper
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.tyust.course.academic.AcademicSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** A web-authenticated service uses its own WebKit profile for host workflows too.
 * Cookie values stay in the host and are never returned through the JS bridge. */
object PluginWebSessionCookies {
    fun jar(app: Context, pkg: PluginPackage, session: AcademicSession, active: () -> Boolean): CookieJar {
        val prefix = "service:${pkg.manifest.id}:"
        if (!session.key.schoolId.startsWith(prefix)) return session.cookies
        val accounts = PluginServiceAccounts(app)
        val serverId = session.key.schoolId.removePrefix(prefix)
        val server = accounts.server(pkg, serverId)
        if (server.getString("authentication") != "web") return session.cookies
        val origin = server.getString("origin")
        val profileName = accounts.profile(pkg, serverId, session.key.accountKey)
        fun <T> main(block: () -> T): T = if (Looper.myLooper() == Looper.getMainLooper()) block()
            else runBlocking { withContext(Dispatchers.Main) { block() } }
        fun <T> access(block: (android.webkit.CookieManager) -> T): T = main {
            synchronized(PluginServiceAccounts.lock) {
                if (!active() || !accounts.current(pkg, session)) throw PluginException(PluginErrorCode.STALE_CONTEXT, "服务账号已改变")
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
                    throw PluginException(PluginErrorCode.UNSUPPORTED, "此设备无法在 App 内隔离网页登录，请使用系统浏览器")
                block(ProfileStore.getInstance().getOrCreateProfile(profileName).cookieManager)
            }
        }
        return ScopedWebCookieJar(origin, { active() && accounts.current(pkg, session) },
            { url -> access { it.getCookie(url).orEmpty() } },
            { url, cookie -> access { it.setCookie(url, cookie); it.flush() } })
    }
}

/** Testable boundary: exact origin, exact request path, live account, no shared jar. */
internal class ScopedWebCookieJar(
    origin: String, private val active: () -> Boolean,
    private val read: (String) -> String, private val write: (String, String) -> Unit
) : CookieJar {
    private val gate = PluginWebGate(origin)
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!active()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "服务账号已改变")
        if (!gate.owns(url.toString())) return emptyList()
        val header = read(url.toString())
        if (header.length > 32768) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "服务会话过大")
        return header.split(';').mapNotNull { item ->
            // CookieManager already matched domain, path, expiry, Secure and HttpOnly.
            // Rebuild host-only cookies for this exact request, never for a redirect.
            Cookie.parse(url, item.trim() + "; Path=" + url.encodedPath + if (url.isHttps) "; Secure" else "")
        }
    }
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!active() || !gate.owns(url.toString())) return
        cookies.forEach { if (active()) write(url.toString(), it.toString()) }
    }
}

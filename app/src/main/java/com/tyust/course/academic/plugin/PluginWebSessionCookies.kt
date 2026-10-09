package com.tyust.course.academic.plugin

import android.content.Context
import android.os.Looper
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.tyust.course.academic.AcademicSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Web profiles and native-only login both remain scoped to the service account.
 * Cookie values stay in the host and are never returned through the JS bridge. */
object PluginWebSessionCookies {
    // WebView features are fixed for the process; probing costs a blocking hop to the main thread.
    @Volatile private var multiProfile: Boolean? = null
    fun jar(app: Context, pkg: PluginPackage, session: AcademicSession, active: () -> Boolean): CookieJar {
        val prefix = "service:${pkg.manifest.id}:"
        if (!session.key.schoolId.startsWith(prefix)) return session.cookies
        val accounts = PluginServiceAccounts(app)
        val serverId = session.key.schoolId.removePrefix(prefix)
        val server = accounts.server(pkg, serverId)
        if (server.getString("authentication") != "web") return session.cookies
        val origin = server.getString("origin")
        val additionalOrigins = PluginJson.strings(server.optJSONArray("cookieOrigins")).toSet()
        val current = { active() && accounts.current(pkg, session) }
        if (PluginEmbeddedBrowser.enabled(pkg, serverId)) {
            return ScopedWebCookieJar(origin, current,
                { url -> PluginEmbeddedBrowser.cookie(app, pkg, session, current, "read", url) },
                { url, cookie -> PluginEmbeddedBrowser.cookie(app, pkg, session, current, "write", url, cookie); Unit }, additionalOrigins)
        }
        val native = NativeWebCookieStore(NativePluginVault(app,
            PluginStorageScope.session(session, pkg.manifest.id, !pkg.official)), additionalOrigins + origin, current)
        fun <T> main(block: () -> T): T = if (Looper.myLooper() == Looper.getMainLooper()) block()
            else runBlocking { withContext(Dispatchers.Main) { block() } }
        val profiles = multiProfile ?: main { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.also { multiProfile = it }
        val legacyProfile by lazy { accounts.profile(pkg, serverId, session.key.accountKey) }
        fun legacy() = PluginLegacyWebSessions.enabled(app, legacyProfile)
        if (!profiles || legacy()) {
            return ScopedWebCookieJar(origin, current,
                { url -> if (legacy()) PluginLegacyWebSessions.header(app, pkg, legacyProfile, additionalOrigins + origin, current, url) else native.header(url) },
                { url, value -> if (legacy()) PluginLegacyWebSessions.set(app, pkg, legacyProfile, additionalOrigins + origin, current, url, value) else native.set(url, value) },
                additionalOrigins)
        }
        var manager: android.webkit.CookieManager? = null
        fun <T> access(block: (android.webkit.CookieManager) -> T): T = main {
            synchronized(PluginServiceAccounts.lock) {
                if (!current()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "服务账号已改变")
                val cookies = manager ?: profile(app, pkg, session, active).cookieManager.also { manager = it }
                block(cookies)
            }
        }
        return ScopedWebCookieJar(origin, current,
            { url -> access { it.getCookie(url).orEmpty() } },
            { url, cookie -> access { it.setCookie(url, cookie); it.flush() } }, additionalOrigins)
    }

    /** Run before any page can log in, so a pending native session cannot overwrite a newer web login. */
    internal fun profile(app: Context, pkg: PluginPackage, session: AcademicSession, active: () -> Boolean): Profile =
        synchronized(PluginServiceAccounts.lock) {
            val accounts = PluginServiceAccounts(app)
            val serverId = session.key.schoolId.removePrefix("service:${pkg.manifest.id}:")
            val current = { active() && accounts.current(pkg, session) }
            if (!current()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "服务账号已改变")
            val server = accounts.server(pkg, serverId)
            if (PluginLegacyWebSessions.enabled(app, accounts.profile(pkg, serverId, session.key.accountKey)))
                throw PluginException(PluginErrorCode.UNSUPPORTED, "此账号使用兼容网页会话。更新 WebView 后，请退出此服务账号并重新登录，再启用脚本。")
            val profile = ProfileStore.getInstance().getOrCreateProfile(accounts.profile(pkg, serverId, session.key.accountKey))
            val native = NativeWebCookieStore(NativePluginVault(app,
                PluginStorageScope.session(session, pkg.manifest.id, !pkg.official)),
                PluginJson.strings(server.optJSONArray("cookieOrigins")).toSet() + server.getString("origin"), current)
            native.migrate({ url, cookie -> profile.cookieManager.setCookie(url, cookie) }, profile.cookieManager::flush)
            profile
        }
}

/** Testable boundary: exact origin, exact request path, live account, no shared jar. */
internal class ScopedWebCookieJar(
    origin: String, private val active: () -> Boolean,
    private val read: (String) -> String, private val write: (String, String) -> Unit,
    additionalOrigins: Set<String> = emptySet()
) : CookieJar {
    private val gates = (additionalOrigins + origin).map(::PluginWebGate)
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!active()) throw PluginException(PluginErrorCode.STALE_CONTEXT, "服务账号已改变")
        if (gates.none { it.owns(url.toString()) }) return emptyList()
        val header = read(url.toString())
        if (header.length > 32768) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "服务会话过大")
        return header.split(';').mapNotNull { item ->
            // CookieManager already matched domain, path, expiry, Secure and HttpOnly.
            // Rebuild host-only cookies for this exact request, never for a redirect.
            Cookie.parse(url, item.trim() + "; Path=" + url.encodedPath + if (url.isHttps) "; Secure" else "")
        }
    }
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!active() || gates.none { it.owns(url.toString()) }) return
        cookies.forEach { if (active()) write(url.toString(), it.toString()) }
    }
}

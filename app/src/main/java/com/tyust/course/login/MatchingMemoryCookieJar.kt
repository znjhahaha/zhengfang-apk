package com.tyust.course.login

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

internal class MatchingMemoryCookieJar(
    private val clockMillis: () -> Long = System::currentTimeMillis
) : CookieJar {
    private val cookies = mutableListOf<StoredCookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = clockMillis()
        for (cookie in cookies) {
            this.cookies.removeAll { stored -> stored.cookie.hasSameIdentityAs(cookie) }
            if (cookie.expiresAt > now) {
                this.cookies += StoredCookie(cookie, url.host)
            }
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = clockMillis()
        cookies.removeAll { it.cookie.expiresAt <= now }
        return cookies.map(StoredCookie::cookie).filter { it.matches(url) }
    }

    @Synchronized
    fun cookieHeaderFor(url: HttpUrl, setByHost: String? = null): String {
        val now = clockMillis()
        cookies.removeAll { it.cookie.expiresAt <= now }
        return cookies.asSequence()
            .filter { stored -> setByHost == null || stored.setByHost == setByHost }
            .map(StoredCookie::cookie)
            .filter { cookie -> cookie.matches(url) }
            .joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" }
    }

    @Synchronized
    fun clear() {
        cookies.clear()
    }

    private fun Cookie.hasSameIdentityAs(other: Cookie): Boolean =
        name == other.name && domain == other.domain && path == other.path

    private data class StoredCookie(val cookie: Cookie, val setByHost: String)
}

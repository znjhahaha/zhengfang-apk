package com.tyust.course.academic.plugin

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object PluginBrowserNavigation {
    /** Old scripts sometimes downgrade a failed page probe. Never send the account over HTTP. */
    fun httpsEquivalent(value: String, permits: (String) -> Boolean): String? {
        val url = value.toHttpUrlOrNull() ?: return null
        if (url.scheme != "http" || url.port != 80 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            Regex("(?i)%2f|%5c|%00").containsMatchIn(url.encodedPath)) return null
        val secure = url.newBuilder().scheme("https").port(443).build().toString()
        return secure.takeIf(permits)
    }
}

package com.tyust.course.academic.plugin

import okhttp3.HttpUrl
import com.tyust.course.academic.AcademicRedirects

/** Preserve registered CAS service parameters while using TLS for an advertised HTTP callback. */
internal object PluginRedirects {
    fun upgradeToHttps(current: HttpUrl, next: HttpUrl, enabled: Boolean): HttpUrl =
        if (enabled && current.isHttps && !next.isHttps && next.port == 80)
            next.newBuilder().scheme("https").port(443).build()
        else AcademicRedirects.preferVerifiedHttps(current, next)
}

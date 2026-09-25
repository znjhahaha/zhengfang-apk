package com.tyust.course.academic

import okhttp3.HttpUrl

/** HUEL advertises HTTP callbacks even when its teaching application is served over TLS. */
object AcademicRedirects {
    fun preferVerifiedHttps(current: HttpUrl, target: HttpUrl): HttpUrl {
        fun inTeachingApp(url: HttpUrl) = url.encodedPath == "/jwglxt" || url.encodedPath.startsWith("/jwglxt/")
        return if (current.isHttps && current.port == 443 && current.host == "xk.huel.edu.cn" &&
            target.host == current.host && !target.isHttps && target.port == 80 &&
            current.username.isEmpty() && current.password.isEmpty() && target.username.isEmpty() && target.password.isEmpty() &&
            inTeachingApp(current) && inTeachingApp(target)) {
            target.newBuilder().scheme("https").port(443).build()
        } else target
    }
}

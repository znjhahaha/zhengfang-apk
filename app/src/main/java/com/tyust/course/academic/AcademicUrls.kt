package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import java.net.URI

/** Configured application roots are optional; an endpoint may already contain that root. */
object AcademicUrls {
    @JvmStatic fun appUrl(school: SchoolConfig, path: String): String = resolve(school.baseUrl, school.basePath, path)

    @JvmStatic fun resolve(origin: String, basePath: String?, path: String): String {
        if (path.startsWith("http://", true) || path.startsWith("https://", true)) return path
        val root = basePath.orEmpty().trim().trim('/')
        val endpoint = path.trimStart('/')
        val base = origin.trimEnd('/') + "/"
        if (endpoint.isEmpty()) return base.trimEnd('/') + if (root.isEmpty()) "" else "/$root"
        val relative = if (root.isEmpty() || endpoint == root || endpoint.startsWith("$root/")) endpoint
            else "$root/$endpoint"
        return URI(base).resolve(relative).toString()
    }
}

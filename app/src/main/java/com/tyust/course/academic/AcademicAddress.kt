package com.tyust.course.academic

import java.net.URI

data class AcademicAddress(val protocol: String, val domain: String, val basePath: String) {
    companion object {
        fun parse(input: String): AcademicAddress? = runCatching {
            val uri = URI(input.trim().let { if (it.contains("://")) it else "https://$it" })
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null)
            require(uri.port == -1 || uri.port in 1..65535)
            val path = uri.path.orEmpty().trimEnd('/')
            val feature = Regex("/(?:framework|xtgl|xsxk|xsxkkc|xk|xkgl)(?:/|$)").find(path)
            val root = when {
                feature != null -> path.substring(0, feature.range.first)
                Regex("\\.(?:aspx|jsp|htmlx?|do)$", RegexOption.IGNORE_CASE).containsMatchIn(path) -> path.substringBeforeLast('/', "")
                else -> path
            }
            AcademicAddress(uri.scheme, uri.host.lowercase() + if (uri.port >= 0) ":${uri.port}" else "", root)
        }.getOrNull()
    }
}

object AcademicUrlPolicy {
    fun isAllowed(value: String, protocol: String, hosts: Collection<String>): Boolean = runCatching {
        val uri = URI(value)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return false
        if (protocol == "https" && uri.scheme != "https") return false
        val port = if (uri.port == -1) if (uri.scheme == "https") 443 else 80 else uri.port
        hosts.any { configured ->
            val allowed = URI(if (configured.contains("://")) configured else "${uri.scheme}://$configured")
            val allowedPort = if (allowed.port == -1) if (uri.scheme == "https") 443 else 80 else allowed.port
            allowed.host?.equals(uri.host, true) == true && port == allowedPort
        }
    }.getOrDefault(false)
}

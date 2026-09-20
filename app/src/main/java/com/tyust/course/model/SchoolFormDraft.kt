package com.tyust.course.model

import com.tyust.course.academic.AcademicSystem
import java.net.URI
import java.util.UUID

/** Values entered by the user, before assigning a persistent school identity. */
data class SchoolFormDraft(
    val name: String,
    val domain: String,
    val protocol: String,
    val basePath: String,
    val academicSystem: String,
    val detectionSource: String = if (academicSystem == AcademicSystem.AUTO.id) "pending" else "manual"
) {
    val isValidDomain: Boolean get() = runCatching {
        val authority = domain.trim()
        val uri = URI("$protocol://$authority")
        protocol in setOf("http", "https") && authority.contains('.') &&
            !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null &&
            uri.rawFragment == null && uri.path.isNullOrEmpty() &&
            (uri.port == -1 || uri.port in 1..65535)
    }.getOrDefault(false)

    private val normalizedBasePath get() = basePath.trim().trim('/').let { if (it.isEmpty()) "" else "/$it" }
    val isValid: Boolean get() = isValidDomain && AcademicSystem.fromId(academicSystem) != null &&
        basePath.none { it == '?' || it == '#' || it == '\\' } && runCatching {
            URI(normalizedBasePath).let { !it.isAbsolute && it.rawAuthority == null && it.rawQuery == null && it.rawFragment == null }
        }.getOrDefault(false)

    fun toSchoolConfig(id: String = "custom_${UUID.randomUUID()}"): SchoolConfig {
        require(isValid) { "学校地址或教务类型无效" }
        val host = domain.trim().lowercase()
        return SchoolConfig(id, name.trim().ifBlank { host }, host, protocol).apply {
            basePath = normalizedBasePath
            academicSystem = this@SchoolFormDraft.academicSystem
            detectionSource = this@SchoolFormDraft.detectionSource
            allowedAcademicHosts.add(host)
        }
    }

    fun applyTo(existing: SchoolConfig): SchoolConfig {
        val address = toSchoolConfig(existing.id)
        return SchoolConfig.fromJson(existing.toJson()).apply {
            name = address.name
            domain = address.domain
            protocol = address.protocol
            basePath = address.basePath
            academicSystem = address.academicSystem
            detectionSource = address.detectionSource
            if (!allowedAcademicHosts.contains(domain)) allowedAcademicHosts.add(domain)
        }
    }
}

package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object AcademicGatewayFactory {
    private val sessions = AcademicSessionStore()

    /** True when the school opts into the adapter flow, including auto-detection. */
    fun supports(school: SchoolConfig): Boolean {
        return school.academicSystem != AcademicSystem.LEGACY_ZF.id
    }

    /** Auto-detection opts into this flow but cannot create an adapter yet. */
    fun hasSelectedAdapter(school: SchoolConfig): Boolean = when (AcademicSystem.fromId(school.academicSystem)) {
        AcademicSystem.ZF, AcademicSystem.ZF_OLD, AcademicSystem.QZ, AcademicSystem.QZ_OLD -> true
        else -> false
    }

    suspend fun detect(school: SchoolConfig, accountStorageKey: String): AcademicSystem? {
        val result = AcademicDetection.detect(school.fullBasePath, school)
        if (result.status == AcademicDetectionStatus.NETWORK_ERROR)
            throw AcademicException(AcademicStatus.NETWORK_RETRYABLE, result.message)
        val system = result.system ?: return null
        result.address?.let { school.protocol = it.protocol; school.domain = it.domain; school.basePath = it.basePath }
        school.academicSystem = system.id
        school.detectionSource = "automatic"
        if (!school.allowedAcademicHosts.contains(school.domain)) school.allowedAcademicHosts.add(school.domain)
        return system
    }

    fun create(school: SchoolConfig, accountStorageKey: String): AcademicProtocolAdapter {
        val system = school.academicType()
        require(system != AcademicSystem.LEGACY_ZF && system != AcademicSystem.AUTO) { "School has no selected academic adapter" }
        val session = sessions.session(school.id, accountStorageKey, school.getFullBasePath())
        val transport = AcademicHttpTransport(school, session)
        return when (system) {
            AcademicSystem.ZF -> ZfAcademicAdapter(school, session, transport)
            AcademicSystem.ZF_OLD -> ZfOldAcademicAdapter(school, session, transport)
            AcademicSystem.QZ -> QzAcademicAdapter(school, session, transport)
            AcademicSystem.QZ_OLD -> QzOldAcademicAdapter(school, session, transport)
            AcademicSystem.LEGACY_ZF, AcademicSystem.AUTO -> error("Academic adapter is not selected")
        }
    }

    fun createStudy(school: SchoolConfig, accountStorageKey: String): AcademicStudyAdapter {
        require(school.academicType() !in setOf(AcademicSystem.AUTO, AcademicSystem.LEGACY_ZF))
        val session = sessions.session(school.id, accountStorageKey, school.fullBasePath)
        return AcademicStudyReader(school, session, AcademicHttpTransport(school, session))
    }

    fun invalidate(school: SchoolConfig, accountStorageKey: String) {
        sessions.invalidate(school.id, accountStorageKey)
    }

    /** Import the configured school's Cookie header from the login browser. */
    fun importCookie(school: SchoolConfig, accountStorageKey: String, header: String, replace: Boolean = true, username: String = "") {
        val session = if (replace) sessions.replace(school.id, accountStorageKey, school.fullBasePath)
            else sessions.session(school.id, accountStorageKey, school.fullBasePath)
        if (username.isNotBlank()) session.username = username
        if (!replace && session.cookieHeader().isNotBlank()) return
        val url = (school.getFullBasePath().trimEnd('/') + "/").toHttpUrlOrNull() ?: return
        val parsed = header.split(';').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            runCatching { Cookie.Builder().name(part.substring(0, separator).trim())
                .value(part.substring(separator + 1).trim()).hostOnlyDomain(url.host).path(url.encodedPath)
                .apply { if (url.isHttps) secure() }.build() }.getOrNull()
        }
        session.cookies.saveFromResponse(url, parsed)
    }

    fun accountKey(school: SchoolConfig, username: String): String =
        (school.id + "::" + username.trim()).replace(Regex("[^A-Za-z0-9_.-]"), "_")

    fun loginUrl(school: SchoolConfig): String {
        if (school.academicType() == AcademicSystem.QZ) QzCasEntry.forSchool(school)?.let { return it.service.toString() }
        val path = when (AcademicSystem.fromId(school.academicSystem)) {
            AcademicSystem.ZF -> "xtgl/login_slogin.html"
            AcademicSystem.ZF_OLD -> "default2.aspx"
            // Protected framework pages can return an AJAX "not logged in" JSON
            // response to WebView's X-Requested-With header. The root is public.
            AcademicSystem.QZ, AcademicSystem.QZ_OLD -> ""
            else -> ""
        }
        return school.fullBasePath.trimEnd('/') + "/" + path
    }

    fun detectAndApply(school: SchoolConfig, html: String): AcademicSystem? {
        val detected = SystemDetector.classify(html) ?: return null
        school.academicSystem = detected.id
        school.detectionSource = "automatic"
        if (!school.allowedAcademicHosts.contains(school.domain)) school.allowedAcademicHosts.add(school.domain)
        return detected
    }
}

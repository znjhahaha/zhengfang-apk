package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.tyust.course.academic.plugin.AcademicProviderRegistry

object AcademicGatewayFactory {
    private val sessions = AcademicSessionStore()

    /** Borrow only an existing login; plugins cannot create or select its account. */
    internal fun sharedSession(school: SchoolConfig, accountStorageKey: String): AcademicSession? =
        sessions.existing(school.id, accountStorageKey, school.fullBasePath)

    /** True when the school opts into the adapter flow, including auto-detection. */
    fun supports(school: SchoolConfig): Boolean {
        return true // legacy_zf is a compatibility name for the preinstalled TypeScript provider.
    }

    /** Auto-detection opts into this flow but cannot create an adapter yet. */
    fun hasSelectedAdapter(school: SchoolConfig): Boolean = AcademicProviderRegistry.hasBinding(school) || when (AcademicSystem.fromId(school.academicSystem)) {
        AcademicSystem.ZF, AcademicSystem.LEGACY_ZF, AcademicSystem.ZF_OLD, AcademicSystem.QZ, AcademicSystem.QZ_OLD -> true
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
        AcademicProviderRegistry.prepareBuiltinSchool(school)
        val session = sessions.session(school.id, accountStorageKey, school.getFullBasePath())
        AcademicProviderRegistry.adapter(school, session)?.let { return it }
        throw AcademicException(AcademicStatus.UNSUPPORTED, "请安装、启用或选择本校的教务协议插件")
    }

    internal fun createBuiltin(school: SchoolConfig, session: AcademicSession): AcademicProtocolAdapter {
        AcademicProviderRegistry.builtinAdapter(school, session)?.let { return it }
        throw AcademicException(AcademicStatus.UNSUPPORTED, "本校暂无可用的预装教务协议插件")
    }

    fun createStudy(school: SchoolConfig, accountStorageKey: String): AcademicStudyAdapter {
        AcademicProviderRegistry.prepareBuiltinSchool(school)
        val session = sessions.session(school.id, accountStorageKey, school.fullBasePath)
        AcademicProviderRegistry.adapter(school, session)?.let { return it }
        throw AcademicException(AcademicStatus.UNSUPPORTED, "本校暂无可用的学业数据插件")
    }

    fun invalidate(school: SchoolConfig, accountStorageKey: String) {
        sessions.invalidate(school.id, accountStorageKey)
    }

    /** Import the configured school's Cookie header from the login browser. */
    fun importCookie(school: SchoolConfig, accountStorageKey: String, header: String, replace: Boolean = true, username: String = "") {
        val session = if (replace && !AcademicProviderRegistry.overrides(school, "auth.start")) sessions.replace(school.id, accountStorageKey, school.fullBasePath)
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
        com.tyust.course.academic.plugin.BundledAcademicProviders.matching(school)?.let { return it.loginUrl }
        if (school.academicType() == AcademicSystem.QZ) com.tyust.course.academic.plugin.GenericAcademicProtocols.configuration(school).optString("casServiceUrl").takeIf { it.isNotBlank() }?.let { return it }
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
        val detected = com.tyust.course.academic.plugin.BundledAcademicProviders.matching(school)?.system
            ?: SystemDetector.classify(html) ?: return null
        school.academicSystem = detected.id
        school.detectionSource = "automatic"
        if (!school.allowedAcademicHosts.contains(school.domain)) school.allowedAcademicHosts.add(school.domain)
        return detected
    }
}

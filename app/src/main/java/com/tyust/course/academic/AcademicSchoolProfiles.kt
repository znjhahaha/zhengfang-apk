package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** School deployment defaults, separate from reusable protocol packages. */
object AcademicSchoolProfiles {
    const val AHUT_BASE_PATH = "/http/77726476706e69737468656265737421fae05988693160456a468ca88d1b203b/jsxsd"
    const val AHUT_BASE_URL = "https://vpncas.ahut.edu.cn" + AHUT_BASE_PATH
    const val AHUT_CAS_PREFIX = "https://vpncas.ahut.edu.cn/https/77726476706e69737468656265737421f3f652d237316943300981b98c1b263148c22164/cas"
    const val AHUT_PORTAL_URL = "https://smart.ahut.edu.cn/?path=https%3A%2F%2Fsmart.ahut.edu.cn%2F"
    const val AHUT_LOGIN_URL = AHUT_CAS_PREFIX + "/login?service=" + AHUT_PORTAL_URL
    const val LNTU_BASE_PATH = "/http-8080/77726476706e69737468656265737421fae05988693c66446b468ca88d1b203b/eams"
    const val LNTU_LOGIN_URL = "https://webvpn.lntu.edu.cn/https/77726476706e69737468656265737421f1e2559434357a467b1ac7a09641367b918300a4219f/authserver/login?service=https%3A%2F%2Fwebvpn.lntu.edu.cn%2Flogin%3Fcas_login%3Dtrue"
    const val HNAE_LOGIN_URL = "https://cas.hnae.edu.cn:8003/cas/login?service=https%3A%2F%2Fjw.hnae.edu.cn%2Fcaslogin"
    data class Profile(val id: String, val name: String, val system: AcademicSystem,
        val address: AcademicAddress, val loginUrl: String, val aliases: List<AcademicAddress> = emptyList(),
        val loginAliases: List<String> = emptyList(), val webLoginUrl: String? = null,
        val webVpnTargetBaseUrl: String? = null, val authPrefixes: List<String> = emptyList(),
        val matchAuthorityRoot: Boolean = true) {
        fun school() = SchoolConfig(id, name, address.domain, address.protocol).apply {
            basePath = address.basePath
            academicSystem = system.id
            allowedAcademicHosts.add(address.domain)
            (listOf(loginUrl) + listOfNotNull(webLoginUrl) + authPrefixes).forEach { authUrl ->
                authUrl.toHttpUrlOrNull()?.let { login ->
                    val authority = login.host + if (login.port == if (login.isHttps) 443 else 80) "" else ":${login.port}"
                    if (authority != address.domain && authority !in allowedAcademicHosts) allowedAcademicHosts.add(authority)
                }
            }
        }
    }
    val profiles = listOf(
        Profile("ahut", "安徽工业大学", AcademicSystem.QZ_OLD,
            AcademicAddress("https", "vpncas.ahut.edu.cn", AHUT_BASE_PATH), AHUT_BASE_URL + "/",
            aliases = listOf(AcademicAddress("http", "jwxt.ahut.edu.cn", "/jsxsd")),
            loginAliases = listOf(AHUT_LOGIN_URL, AHUT_PORTAL_URL,
                AHUT_CAS_PREFIX + "/login?service=https%3A%2F%2Fvpncas.ahut.edu.cn%2Flogin%3Fcas_login%3Dtrue"),
            webLoginUrl = AHUT_LOGIN_URL, webVpnTargetBaseUrl = "http://jwxt.ahut.edu.cn/jsxsd",
            authPrefixes = listOf(AHUT_CAS_PREFIX, "https://smart.ahut.edu.cn/", "https://vpncas.ahut.edu.cn/login"),
            matchAuthorityRoot = false),
        Profile("hnae", "湖南汽车工程职业大学", AcademicSystem.KINGO,
            AcademicAddress("https", "jw.hnae.edu.cn", ""), HNAE_LOGIN_URL,
            loginAliases = listOf("https://cas.hnae.edu.cn:8003/cas/login?service=https%3A%2F%2Fportal.hnae.edu.cn%2Flogin")),
        Profile("lntu", "辽宁工程技术大学", AcademicSystem.EAMS,
            AcademicAddress("https", "webvpn.lntu.edu.cn", LNTU_BASE_PATH), "https://webvpn.lntu.edu.cn/",
            aliases = listOf(AcademicAddress("http", "jwxt.lntu.edu.cn:8080", "/eams")),
            loginAliases = listOf(LNTU_LOGIN_URL)),
        Profile("cduestc", "电子科技大学成都学院", AcademicSystem.EAMS,
            AcademicAddress("https", "www.cduestc.cn", "/eams"), "https://www.cduestc.cn/eams/loginExt.action"),
        Profile("wtc", "武汉职业技术大学", AcademicSystem.CHAOXING_ACADEMIC,
            AcademicAddress("https", "jwxt1.wtc.edu.cn", "/admin"),
            "https://authserver.wtc.edu.cn/authserver/login?service=https%3A%2F%2Fjwxt1.wtc.edu.cn%2Fadmin%2Fcaslogin")
    )
    fun detect(input: String): Profile? {
        val url = input.trim().let { if (it.contains("://")) it else "https://$it" }.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || url.encodedPath.any { it in "%\\" }) return null
        return profiles.firstOrNull { p ->
            (listOf(p.address) + p.aliases).any { address ->
                val endpoint = "${address.protocol}://${address.domain}${address.basePath}".toHttpUrlOrNull()!!
                val path = withoutSession(url).encodedPath
                sameAuthority(url, endpoint) && (p.matchAuthorityRoot && path == "/" || path == address.basePath || path.startsWith(address.basePath + "/"))
            } || (listOf(p.loginUrl) + p.loginAliases).any { sameLogin(url, it.toHttpUrlOrNull()!!) }
        }
    }
    private fun withoutSession(url: HttpUrl): HttpUrl = url.newBuilder()
        .encodedPath(url.encodedPath.replace(Regex(";jsessionid=[A-Za-z0-9]+(?=/|$)"), "")).build()
    private fun sameAuthority(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    private fun sameLogin(actual: HttpUrl, expected: HttpUrl): Boolean {
        if (!sameAuthority(actual, expected) || withoutSession(actual).encodedPath != expected.encodedPath) return false
        if (actual.queryParameterNames != expected.queryParameterNames) return false
        return expected.queryParameterNames.all { key ->
            val values = actual.queryParameterValues(key)
            if (values.size != 1) false else if (key == "service") {
                val service = values.single()?.toHttpUrlOrNull()
                service != null && service.username.isEmpty() && service.password.isEmpty() &&
                    withoutSession(service) == expected.queryParameter(key)?.toHttpUrlOrNull()
            } else values.single() == expected.queryParameter(key)
        }
    }
}

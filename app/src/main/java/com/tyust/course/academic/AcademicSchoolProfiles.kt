package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** School deployment defaults, separate from reusable protocol packages. */
object AcademicSchoolProfiles {
    data class Profile(val id: String, val name: String, val system: AcademicSystem,
        val address: AcademicAddress, val loginUrl: String) {
        fun school() = SchoolConfig(id, name, address.domain, address.protocol).apply {
            basePath = address.basePath
            academicSystem = system.id
            allowedAcademicHosts.add(address.domain)
            loginUrl.toHttpUrlOrNull()?.host?.takeIf { it != address.domain }?.let { allowedAcademicHosts.add(it) }
        }
    }
    val profiles = listOf(
        Profile("cduestc", "电子科技大学成都学院", AcademicSystem.EAMS,
            AcademicAddress("https", "www.cduestc.cn", "/eams"), "https://www.cduestc.cn/eams/loginExt.action"),
        Profile("wtc", "武汉职业技术大学", AcademicSystem.CHAOXING_ACADEMIC,
            AcademicAddress("https", "jwxt1.wtc.edu.cn", "/admin"),
            "https://authserver.wtc.edu.cn/authserver/login?service=https%3A%2F%2Fjwxt1.wtc.edu.cn%2Fadmin%2Fcaslogin")
    )
    fun detect(input: String): Profile? {
        val url = input.trim().let { if (it.contains("://")) it else "https://$it" }.toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        return profiles.firstOrNull { p ->
            url.host == p.address.domain && (url.encodedPath == "/" || url.encodedPath == p.address.basePath ||
                url.encodedPath.startsWith(p.address.basePath + "/")) || url == p.loginUrl.toHttpUrlOrNull()
        }
    }
}

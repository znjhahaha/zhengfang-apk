package com.tyust.course.academic.plugin

import android.app.Application
import com.tyust.course.academic.AcademicSchoolProfiles
import com.tyust.course.academic.AcademicPasswordLoginGateway
import com.tyust.course.academic.AcademicUrlPolicy
import com.tyust.course.academic.AhutProfileTest
import com.tyust.course.model.SchoolConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class AhutVpnProtocolTest {
    private fun school() = AcademicSchoolProfiles.profiles.single { it.id == "ahut" }.school()
    @Test fun canonicalProfileBindsExplicitBrowserLoginAndKnownCampusTarget() {
        val config = GenericAcademicProtocols.configuration(school())
        assertEquals(AhutProfileTest.VPN_BASE, config.getString("baseUrl"))
        assertEquals(AhutProfileTest.LOGIN, config.getString("webLoginUrl"))
        assertEquals("http://jwxt.ahut.edu.cn/jsxsd", config.getString("webVpnTargetBaseUrl"))
        assertFalse(config.has("casLoginUrl"))
        assertFalse(config.has("casServiceUrl"))
    }
    @Test fun authenticationRoutesNeverGrantQueryOrMutationOutsideTeaching() {
        val policy = PluginNetworkPolicy(PluginJson.objects(GenericAcademicProtocols.network(school())))
        for (purpose in listOf("auth", "query", "mutation")) {
            policy.requireAllowed((AhutProfileTest.VPN_BASE + "/xk/query").toHttpUrl(), "POST", purpose, null)
        }
        for (url in listOf(AhutProfileTest.LOGIN, "https://smart.ahut.edu.cn/?path=https%3A%2F%2Fsmart.ahut.edu.cn%2F",
            "https://vpncas.ahut.edu.cn/login?cas_login=true")) {
            policy.requireAllowed(url.toHttpUrl(), "GET", "auth", null)
            for (purpose in listOf("query", "mutation")) assertThrows(PluginException::class.java) {
                policy.requireAllowed(url.toHttpUrl(), "POST", purpose, null)
            }
        }
        for (url in listOf("https://vpncas.ahut.edu.cn/", "https://vpncas.ahut.edu.cn/other",
            AhutProfileTest.VPN_BASE + "-other/query", "http://jwxt.ahut.edu.cn/jsxsd/query",
            AhutProfileTest.VPN_BASE.replace("https:", "http:"), AhutProfileTest.CAS.replace("f3f652", "f3f653") + "/login")) {
            assertThrows(PluginException::class.java) { policy.requireAllowed(url.toHttpUrl(), "GET", "auth", null) }
        }
    }
    @Test fun profileConfigurationDoesNotLeakToChangedEndpointsOrPlainQiangzhi() {
        for (school in listOf(SchoolConfig.fromJson(school().toJson()).apply { basePath += "-other" },
            SchoolConfig("plain", "合成", "school.example.test", "https").apply { academicSystem = "qz_old"; basePath = "/jsxsd" },
            SchoolConfig("campus", "合成", "jwxt.ahut.edu.cn", "http").apply { academicSystem = "qz_old"; basePath = "/jsxsd" })) {
            val config = GenericAcademicProtocols.configuration(school)
            assertFalse(config.has("webLoginUrl"))
            assertFalse(config.has("webVpnTargetBaseUrl"))
        }
    }
    @Test fun browserCompletionRequiresExactTeachingHomeButAllowsItsSsoQuery() {
        val home = AhutProfileTest.VPN_BASE + "/framework/xsMain.jsp"
        assertTrue(AcademicPasswordLoginGateway.matchesCompletion(home, home))
        assertTrue(AcademicPasswordLoginGateway.matchesCompletion(home, home + "?t1=1"))
        assertTrue(AcademicPasswordLoginGateway.matchesCompletion(home, home + "?ticket=ST-synthetic"))
        for (url in listOf(AhutProfileTest.LOGIN, AhutProfileTest.CAS + "/login", "https://smart.ahut.edu.cn/",
            AhutProfileTest.VPN_BASE + "/framework/xsMain_new.jsp?t1=1",
            AhutProfileTest.VPN_BASE + "/xskb/xskb_list.do", home + "/extra",
            home.replace("https:", "http:"), home.replace(".edu.cn/", ".edu.cn:8443/"),
            home.replace("vpncas.", "user@vpncas."))) {
            assertFalse(url, AcademicPasswordLoginGateway.matchesCompletion(home, url))
        }
    }
    @Test fun profileAllowsTheExplicitPortalHostForBrowserNavigationOnly() {
        val school = school()
        assertEquals(setOf("vpncas.ahut.edu.cn", "smart.ahut.edu.cn"), school.allowedAcademicHosts.toSet())
        assertTrue(AcademicUrlPolicy.isAllowed(AcademicSchoolProfiles.AHUT_PORTAL_URL, "https", school.allowedAcademicHosts.toSet()))
        assertFalse(AcademicUrlPolicy.isAllowed("https://smart.ahut.edu.cn.evil.test/", "https", school.allowedAcademicHosts.toSet()))
        val policy = PluginNetworkPolicy(PluginJson.objects(GenericAcademicProtocols.network(school)))
        assertThrows(PluginException::class.java) { policy.requireAllowed("https://smart.ahut.edu.cn/query".toHttpUrl(), "GET", "query", null) }
    }
}

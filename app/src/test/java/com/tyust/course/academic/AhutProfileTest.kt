package com.tyust.course.academic

import com.tyust.course.manager.UserManager
import org.junit.Assert.*
import org.junit.Test

class AhutProfileTest {
    @Test fun undergraduateAcademicAndSelectionEntryUsesTheSameQiangzhiProfile() {
        val profile = AcademicSchoolProfiles.detect("http://jwxt.ahut.edu.cn/jsxsd/")!!
        assertEquals("ahut", profile.id)
        assertEquals(AcademicSystem.QZ_OLD, profile.system)
        assertEquals(VPN_BASE, profile.school().fullBasePath.trimEnd('/'))
        assertEquals(profile, AcademicSchoolProfiles.detect("http://jwxt.ahut.edu.cn/jsxsd/xsxk/xklc_list"))
        assertNull(AcademicSchoolProfiles.detect("http://jwxt.ahut.edu.cn.evil.test/jsxsd/"))
        assertNull(AcademicSchoolProfiles.detect("http://jwxt.ahut.edu.cn/other/"))
    }
    @Test fun schoolPickerOffersAhutWithoutCreatingACustomSchool() {
        val school = UserManager.getInstance().supportedSchools.single { it.id == "ahut" }
        assertEquals("安徽工业大学", school.name)
        assertEquals("qz_old", school.academicSystem)
        assertEquals(VPN_BASE, school.fullBasePath.trimEnd('/'))
    }
    @Test fun vpnTeachingAndExactPortalLoginAliasesResolveToTheCanonicalProfile() {
        val profile = AcademicSchoolProfiles.detect(VPN_BASE + "/framework/xsMain.jsp")!!
        assertEquals("ahut", profile.id)
        assertEquals(LOGIN, profile.webLoginUrl)
        for (url in listOf(LOGIN, CAS + "/login?service=https%3A%2F%2Fvpncas.ahut.edu.cn%2Flogin%3Fcas_login%3Dtrue",
            "https://smart.ahut.edu.cn/?path=https%3A%2F%2Fsmart.ahut.edu.cn%2F")) {
            assertEquals(profile, AcademicSchoolProfiles.detect(url))
        }
    }
    @Test fun vpnRecognitionDoesNotBroadenAuthorityOrAcceptOtherServices() {
        for (url in listOf("https://vpncas.ahut.edu.cn/", "https://vpncas.ahut.edu.cn/other/jsxsd",
            VPN_BASE.replace("https:", "http:"), VPN_BASE.replace(".edu.cn", ".edu.cn:8443"),
            VPN_BASE.replace("fae059", "fae058"), VPN_BASE + "-other/framework/xsMain.jsp",
            LOGIN + "&service=https://evil.test/", LOGIN.replace("smart.ahut.edu.cn/?path=", "evil.test/?path="),
            "https://smart.ahut.edu.cn/other", "https://smart.ahut.edu.cn/?path=https://evil.test/",
            VPN_BASE.replace("vpncas.", "user@vpncas."))) {
            assertNull(url, AcademicSchoolProfiles.detect(url))
        }
    }
    companion object {
        const val VPN_BASE = "https://vpncas.ahut.edu.cn/http/77726476706e69737468656265737421fae05988693160456a468ca88d1b203b/jsxsd"
        const val CAS = "https://vpncas.ahut.edu.cn/https/77726476706e69737468656265737421f3f652d237316943300981b98c1b263148c22164/cas"
        const val LOGIN = CAS + "/login?service=https://smart.ahut.edu.cn/?path=https%3A%2F%2Fsmart.ahut.edu.cn%2F"
    }
}

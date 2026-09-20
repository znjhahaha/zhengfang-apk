package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import org.junit.Assert.*
import org.junit.Test

class AcademicGatewayReadinessTest {
    @Test fun autoAndUnknownConfigurationsCannotStartAdapterSessionChecks() {
        val school = SchoolConfig("pending", "School", "jw.example.edu.cn", "https")
        for (type in listOf("auto", "unknown", "")) {
            school.academicSystem = type
            assertTrue(AcademicGatewayFactory.supports(school))
            assertFalse(AcademicGatewayFactory.hasSelectedAdapter(school))
        }
        school.academicSystem = "legacy_zf"
        assertFalse(AcademicGatewayFactory.supports(school))
        assertFalse(AcademicGatewayFactory.hasSelectedAdapter(school))
        for (type in listOf("zf", "zf_old", "qz", "qz_old")) {
            school.academicSystem = type
            assertTrue(AcademicGatewayFactory.hasSelectedAdapter(school))
        }
    }

    @Test fun bothQzBrowserEntriesUseThePublicLoginPage() {
        val school = SchoolConfig("qz", "School", "jw.example.edu.cn", "http").apply { basePath = "/jsxsd" }
        for (type in listOf("qz", "qz_old")) {
            school.academicSystem = type
            assertEquals("http://jw.example.edu.cn/jsxsd/", AcademicGatewayFactory.loginUrl(school))
        }
    }

    @Test fun confirmedHutNewQzUsesItsCasServiceEntry() {
        val school = SchoolConfig("hut", "School", "jwxt.hut.edu.cn", "http").apply {
            basePath = "/jsxsd"
            academicSystem = "qz"
        }
        assertEquals("http://jwxt.hut.edu.cn/jsxsd/sso.jsp", AcademicGatewayFactory.loginUrl(school))
        school.academicSystem = "qz_old"
        assertEquals("http://jwxt.hut.edu.cn/jsxsd/", AcademicGatewayFactory.loginUrl(school))
    }
}

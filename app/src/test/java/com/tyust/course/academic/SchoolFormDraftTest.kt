package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import com.tyust.course.model.SchoolFormDraft
import org.junit.Assert.*
import org.junit.Test

class SchoolFormDraftTest {
    @Test fun eachSelectableTypeSurvivesSavingAndReloading() {
        for (type in AcademicCapabilities.selectableSystems) {
            val draft = SchoolFormDraft(" 测试大学 ", " JW.Example.edu.cn:8443 ", "https", "/custom/", type.id)
            val saved = draft.toSchoolConfig("school-test")
            val restored = SchoolConfig.fromJson(saved.toJson())
            assertEquals(type.id, restored.academicSystem)
            assertEquals(if (type == AcademicSystem.AUTO) "pending" else "manual", restored.detectionSource)
            assertEquals("jw.example.edu.cn:8443", restored.domain)
            assertEquals("/custom", restored.basePath)
            assertEquals("测试大学", restored.name)
            assertTrue(restored.allowedAcademicHosts.contains(restored.domain))
        }
        assertEquals(listOf("自动识别", "新正方", "旧正方", "新强智", "旧强智"),
            AcademicCapabilities.selectableSystems.map(AcademicCapabilities::selectionLabel))
    }

    @Test fun editingKeepsLegacyTypePathsAndTheExistingIdentity() {
        val existing = SchoolConfig("existing", "旧配置", "jw.example.edu.cn", "https").apply {
            academicSystem = "legacy_zf"
            detectionSource = "legacy"
            courseListPath = "/custom/list"
            pageCharset = "GBK"
            courseGnmkdm = "custom-module"
        }
        assertEquals(1, AcademicCapabilities.selectionIndex(existing.academicSystem))
        val selected = AcademicCapabilities.selectedTypeId(existing.academicSystem, AcademicSystem.ZF)
        assertEquals("legacy_zf", selected)
        val updated = SchoolFormDraft("新名称", "new.example.edu.cn", "https", "/root", selected, existing.detectionSource).applyTo(existing)
        val restored = SchoolConfig.fromJson(updated.toJson())
        assertEquals(existing.id, restored.id)
        assertEquals("legacy_zf", restored.academicSystem)
        assertEquals("legacy", restored.detectionSource)
        assertEquals("/custom/list", restored.courseListPath)
        assertEquals("GBK", restored.pageCharset)
        assertEquals("custom-module", restored.courseGnmkdm)
        assertEquals("旧配置", existing.name)
        assertEquals("jw.example.edu.cn", existing.domain)
        assertEquals("manual", SchoolFormDraft("新名称", "new.example.edu.cn", "https", "/root", selected, "manual").applyTo(existing).detectionSource)
    }

    @Test fun parsingAnAddressDoesNotChooseATypeOrReplaceTheUsersManualChoice() {
        val parsed = AcademicAddress.parse("https://jw.example.edu.cn:8443/custom/framework/xsMainV.htmlx")!!
        assertEquals("/custom", parsed.basePath)
        for (type in AcademicCapabilities.selectableSystems) {
            val school = SchoolFormDraft("", parsed.domain, parsed.protocol, parsed.basePath, type.id).toSchoolConfig()
            assertEquals(type.id, school.academicSystem)
            assertEquals("/custom", school.basePath)
        }
        assertEquals("", AcademicAddress.parse("https://jw.example.edu.cn/default2.aspx")!!.basePath)
    }

    @Test fun manuallyChosenTypesRouteToTheCorrespondingLoginPages() {
        val paths = mapOf(AcademicSystem.ZF to "xtgl/login_slogin.html", AcademicSystem.ZF_OLD to "default2.aspx",
            AcademicSystem.QZ to "", AcademicSystem.QZ_OLD to "")
        for ((type, path) in paths) {
            val school = SchoolFormDraft("test", "jw.example.edu.cn", "https", "/custom", type.id).toSchoolConfig()
            assertEquals("https://jw.example.edu.cn/custom/$path", AcademicGatewayFactory.loginUrl(school))
            assertEquals(type, school.academicType())
        }
    }

    @Test fun emptyBasePathIsAllowedButInvalidAddressAndTypeAreRejected() {
        val draft = SchoolFormDraft("", "jw.example.edu.cn", "https", "", "auto")
        assertTrue(draft.isValid)
        assertEquals("", draft.toSchoolConfig().basePath)
        for (bad in listOf("https://jw.example.edu.cn", "user@jw.example.edu.cn", "jw.example.edu.cn/path", "jw.example.edu.cn:99999"))
            assertFalse(draft.copy(domain = bad).isValid)
        for (bad in listOf("/path?query=1", "/path#fragment", "/path\\wrong", "/has space"))
            assertFalse(draft.copy(basePath = bad).isValid)
        assertFalse(draft.copy(academicSystem = "unknown").isValid)
    }
}

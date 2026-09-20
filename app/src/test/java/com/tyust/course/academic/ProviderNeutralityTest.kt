package com.tyust.course.academic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProviderNeutralityTest {
    @Test fun nativeTokensStayPrivateAndSubmissionUsesOriginalSnapshot() = runBlocking {
        val session = AcademicSessionStore().session("school", "account", "https://school.test")
        val original = CourseOffer("COURSE", "课程", scopeId = "ROUND", raw = mapOf("academic_system" to "zf", "jxb_id" to "CLASS", "do_jxb_id" to "secret-token", "kklxdm" to "1"))
        var submitted = ""
        val native = object : AcademicProtocolAdapter {
            override suspend fun login(credentials: Credentials) = LoginResult(AcademicStatus.SUCCESS)
            override suspend fun validateSession() = LoginResult(AcademicStatus.SUCCESS)
            override suspend fun loadCourseContext() = CourseContext(session.epoch, listOf(CourseScope("ROUND", "轮次", params = mapOf("secret" to "token"))))
            override suspend fun listCourses(context: CourseContext, query: CourseQuery) = listOf(original)
            override suspend fun listSections(course: CourseOffer) = listOf(CourseSection("CLASS", "COURSE", raw = original.raw, selectionId = "fresh-token"))
            override suspend fun select(target: SelectionTarget): SelectionResult { submitted = target.section.selectionId; assertEquals("1", target.course.raw["kklxdm"]); return SelectionResult(AcademicStatus.SUCCESS) }
            override suspend fun selected(context: CourseContext) = emptyList<SelectedCourse>()
            override suspend fun drop(target: SelectionTarget) = OperationResult(AcademicStatus.SUCCESS)
        }
        val provider = BuiltinAcademicProvider(native, session, AcademicSystem.ZF)
        val context = provider.loadCourseContext()
        assertTrue(context.scopes.single().params.isEmpty())
        val offer = provider.listCourses(context, CourseQuery()).single()
        val section = provider.listSections(offer).single()
        val ui = AcademicCourseBridge.toCourse(offer, section)
        assertEquals("CLASS", ui.classId)
        assertEquals("CLASS", ui.doJxbId)
        assertEquals("true", ui.completeParams["academic_stable_section"])
        assertFalse(ui.completeParams.values.any { it.contains("token") })
        assertFalse(ui.completeParams.containsKey("jxb_id"))
        val restored = provider.restoreOffer(ui.completeParams)!!
        provider.select(SelectionTarget(restored, section, true))
        assertEquals("fresh-token", submitted)
    }
    @Test fun opaqueTermMetadataRoundTripsWithoutParsingId() {
        val term = AcademicTerm("summer:alpha", "短学期", 2026, 3, 5, "2026-07-01", "2026-07-30", "fall:beta")
        assertEquals(term, AcademicTerm.fromJson(term.toJson()))
        assertEquals("fall:beta", term.next().id)
    }
}

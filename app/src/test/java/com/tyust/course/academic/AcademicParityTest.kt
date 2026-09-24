package com.tyust.course.academic

import com.tyust.course.model.Course
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AcademicParityTest {
    private fun course(scope: String = "round-a", section: String = "section") = Course().apply {
        courseId = "course"; classId = section; name = "Course"; teacher = "Teacher"; time = "Monday"; location = "Room A"; credit = "2.0"
        completeParams["academic_system"] = AcademicSystem.QZ.id
        completeParams["academic_scope_id"] = scope
        completeParams["academic_course_id"] = courseId
    }

    @Test fun scopedSelectionAndGroupingCannotMixReusedIdentifiers() {
        val a = course()
        val b = course("round-b")
        assertNotEquals(a.catalogSelectionKey(), b.catalogSelectionKey())
        assertNotEquals(a.catalogGroupKey(), b.catalogGroupKey())
        assertEquals(a.catalogGroupKey(), course(section = "second").catalogGroupKey())
        a.completeParams.clear()
        assertEquals(a.classId, a.catalogSelectionKey())
        assertEquals(a.courseId, a.catalogGroupKey())
    }

    @Test fun availabilityRequiresBothKnownCountsAndCreditIsNumeric() {
        val item = course().apply { capacity = 30; selected = 20 }
        val filter = AcademicCourseFilter(teacher = "teacher", time = "Mon", location = "room", credit = "2", availableOnly = true)
        assertFalse(filter.matches(item))
        item.completeParams["academic_capacity_known"] = "true"
        assertFalse(filter.matches(item))
        item.completeParams["academic_selected_known"] = "true"
        assertTrue(filter.matches(item))
        item.selected = 30
        assertFalse(filter.matches(item))
        assertFalse(filter.copy(credit = "NaN", availableOnly = false).matches(item))
        assertFalse(filter.copy(credit = "bad", availableOnly = false).matches(item))
    }

    @Test fun batchKeepsSameIdentifiersInDifferentRoundsAndCountsActualResults() = runBlocking {
        val a = course(); val b = course("round-b")
        val attempted = mutableListOf<Course>()
        val result = runAcademicBatch(listOf(a, a, b)) {
            attempted += it
            SelectionResult(if (it === a) AcademicStatus.NO_CAPACITY else AcademicStatus.SUCCESS)
        }
        assertEquals(listOf(a, b), attempted)
        assertEquals(2, result.attempted)
        assertEquals(1, result.succeeded)
        assertFalse(result.stopped)
    }

    @Test fun aBatchStopsAfterEveryStatusThatNeedsAccountOrResultAttention() = runBlocking {
        for (status in AcademicStatus.entries.filter { it.blocksFurtherSelections() }) {
            var writes = 0
            val result = runAcademicBatch(listOf(course(), course("round-b"))) { writes++; SelectionResult(status, "attention") }
            assertTrue(status.name, result.stopped)
            assertEquals(status.name, 1, writes)
        }
    }

    @Test fun aThrownExpiredSessionStopsTheBatchAndCancellationPropagates() = runBlocking {
        val result = runAcademicBatch(listOf(course())) { throw AcademicException(AcademicStatus.SESSION_EXPIRED, "expired") }
        assertTrue(result.stopped)
        assertEquals("expired", result.message)
        try { runAcademicBatch(listOf(course())) { throw CancellationException("cancelled") }; fail() }
        catch (_: CancellationException) { }
    }

    @Test fun supportNamesIncludeJinzhiAndChengfangAndPreserveLegacyNewZfMapping() {
        assertEquals(setOf(AcademicSystem.ZF, AcademicSystem.ZF_OLD, AcademicSystem.QZ, AcademicSystem.QZ_OLD,
            AcademicSystem.JINZHI, AcademicSystem.CHENGFANG), AcademicCapabilities.systems.map { it.system }.toSet())
        assertEquals("新正方", AcademicCapabilities.name("legacy_zf"))
        assertTrue(AcademicCapabilities.ACCOUNT_LIMIT.contains("3"))
    }
}

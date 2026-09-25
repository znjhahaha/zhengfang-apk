package com.tyust.course.schedule

import org.junit.Assert.*
import org.junit.Test

class SemesterRemindersTest {
    private val a = ScheduleCourseRecord("a", "数学", "", "", 1, 1, 2, "1-16周")
    private val b = a.copy(id = "custom:b", custom = true, day = 5)
    private fun reminder(account: String = "one", term: String = "term", course: ScheduleCourseRecord = a,
        enabled: Boolean = false, lead: Int = 30) = CourseReminder(CourseReminderKey(account, term, course.id), course, enabled, lead)

    @Test fun completeTermIncludesManualCoursesAndPreservesOtherAccountsAndLeadTimes() {
        val others = listOf(reminder("two", enabled = true), reminder(term = "other", enabled = true))
        val result = SemesterReminders.update(others + reminder(), "one", "term", listOf(a, b), true)
        assertTrue(result.containsAll(others)); assertEquals(4, result.size)
        assertEquals(30, result.first { it.key == reminder().key }.leadMinutes)
        assertEquals(15, result.first { it.key.courseId == b.id }.leadMinutes)
        assertTrue(result.all { it.enabled })
    }
    @Test fun repeatIsIdempotentAndDisablingDoesNotOptInFutureCourses() {
        val once = SemesterReminders.update(emptyList(), "one", "term", listOf(a, b, a), true)
        assertEquals(once, SemesterReminders.update(once, "one", "term", listOf(a, b), true))
        val off = SemesterReminders.update(once, "one", "term", listOf(a, b), false)
        assertTrue(off.none { it.enabled })
        assertEquals(off, SemesterReminders.update(off, "one", "term", listOf(a, b, a.copy(id = "new")), false))
        assertFalse(once.any { it.key.courseId == "new" })
    }
    @Test fun roundTripRetainsIndividualOverrideAndDoesNotCreateNewReminder() {
        val records = SemesterReminders.update(emptyList(), "one", "term", listOf(a, b), true)
            .map { if (it.key.courseId == a.id) it.copy(enabled = false) else it }
        assertEquals(records, ReminderJson.decode(ReminderJson.encode(records)))
        val summary = SemesterReminders.summary(records, "one", "term", listOf(a, b, a.copy(id = "new")),
            null, ReminderPermissions(false, false), "one", 0)
        assertEquals(3, summary.total); assertEquals(1, summary.enabled)
        assertEquals(1, summary.count(ReminderAvailability.NeedsPermission))
    }
    @Test fun incompleteTimeIsShownWithoutLosingEnabledIntent() {
        val records = SemesterReminders.update(emptyList(), "one", "term", listOf(a), true)
        val summary = SemesterReminders.summary(records, "one", "term", listOf(a), null,
            ReminderPermissions(true, true), "one", 0)
        assertEquals(1, summary.enabled); assertEquals(1, summary.count(ReminderAvailability.NeedsTime))
        assertEquals(0, summary.count(ReminderAvailability.Scheduled))
    }
    @Test fun blankScopeAndEmptySnapshotDoNotModifyAnything() {
        val records = listOf(reminder(enabled = true))
        assertEquals(records, SemesterReminders.update(records, "", "term", listOf(a), false))
        assertEquals(records, SemesterReminders.update(records, "one", "", listOf(a), false))
        assertEquals(records, SemesterReminders.update(records, "one", "term", emptyList(), false))
    }
}

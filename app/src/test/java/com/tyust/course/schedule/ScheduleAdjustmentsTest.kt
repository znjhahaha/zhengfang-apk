package com.tyust.course.schedule

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone
import androidx.compose.ui.graphics.Color
import com.tyust.course.ui.screen.ScheduleCourseUi
import com.tyust.course.utils.ICalExporter

class ScheduleAdjustmentsTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val course = ScheduleCourseRecord("math", "数学", "教师", "A101", 1, 1, 2, "1-2")
    private val base = ScheduleTimeBase("2026-09-07", mapOf(1 to "08:00", 3 to "10:00"), mapOf(2 to "09:40", 4 to "11:40"))
    private fun resolve(rules: ScheduleAdjustments) = ScheduleOccurrenceResolver.resolve(listOf(course), base.copy(adjustments = rules), zone)

    @Test fun holidayAndMakeupReadOriginalScheduleWithoutFollowingMappings() {
        val rules = ScheduleAdjustments(days = listOf(ScheduleDayOverride("2026-09-07"), ScheduleDayOverride("2026-09-12", "2026-09-07")))
        val result = resolve(rules)
        assertEquals(listOf("2026-09-12", "2026-09-14"), result.map { ScheduleTimeBase.dateFromMillis(it.startsAt, zone) })
        assertEquals("2026-09-12", result.first().originalDate)
    }

    @Test fun movingALessonKeepsItsIdentityAndChangesTimeAndLocation() {
        val result = resolve(ScheduleAdjustments(lessons = listOf(ScheduleLessonOverride("math", "2026-09-07", targetDate = "2026-09-15", startPeriod = 3, endPeriod = 4, location = "B202"))))
        val moved = result.last()
        assertEquals("2026-09-07", moved.originalDate)
        assertEquals("2026-09-15", ScheduleTimeBase.dateFromMillis(moved.startsAt, zone))
        assertEquals("B202", moved.course.location)
        assertEquals(100 * 60_000L, moved.endsAt - moved.startsAt)
        assertEquals("A101", result.first().course.location)
    }

    @Test fun cancellationFeedsAgendaAndReminderAndCanBeUndone() {
        val adjusted = base.copy(adjustments = ScheduleAdjustments(lessons = listOf(ScheduleLessonOverride("math", "2026-09-07", cancelled = true))))
        val now = ScheduleOccurrenceResolver.date("2026-09-07", zone)!!.timeInMillis
        assertTrue(ScheduleAgenda.calculate(listOf(course), adjusted, now, zone).today.isEmpty())
        val reminder = CourseReminder(CourseReminderKey("a", "term", course.id), course, true, 30)
        val plan = CourseReminderPlanner.plan(reminder, adjusted, now, ReminderPermissions(true, true), "a", zone).next!!
        assertEquals("2026-09-14", ScheduleTimeBase.dateFromMillis(plan.startsAt, zone))
        assertEquals(30 * 60_000L, plan.startsAt - plan.triggerAt)
        assertEquals(2, ScheduleOccurrenceResolver.resolve(listOf(course), base, zone).size)
    }

    @Test fun staleCoursesAreReportedInsteadOfReassigned() {
        val rules = ScheduleAdjustments(lessons = listOf(ScheduleLessonOverride("old", "2026-09-07", cancelled = true)))
        assertEquals(1, rules.unresolved(listOf(course)).size)
        assertEquals(2, resolve(rules).size)
    }

    @Test fun tokenRenewalAndProviderRefreshKeepSchedulePageIdentity() {
        assertEquals(schedulePageEntryKey("app.schedule", "a", "old", 1), schedulePageEntryKey("app.schedule", "a", "new", 2))
        assertNotEquals(schedulePageEntryKey("app.schedule", "a", "old", 1), schedulePageEntryKey("app.schedule", "b", "old", 1))
        assertNotEquals(schedulePageEntryKey("app.grades", "a", "old", 1), schedulePageEntryKey("app.grades", "a", "new", 1))
    }

    @Test fun anExplicitMakeupSurvivesAHolidayAndEveryConsumerUsesItsNewTimeAndPlace() {
        val rules = ScheduleAdjustments(days = listOf(ScheduleDayOverride("2026-09-07")),
            lessons = listOf(ScheduleLessonOverride(course.id, "2026-09-07", targetDate = "2026-09-15", startPeriod = 3, endPeriod = 4, location = "B202")))
        val adjusted = base.copy(adjustments = rules)
        val now = ScheduleOccurrenceResolver.date("2026-09-15", zone)!!.timeInMillis
        val occurrence = ScheduleAgenda.calculate(listOf(course), adjusted, now, zone).today.single()
        assertEquals("2026-09-07", occurrence.originalDate)
        assertEquals("B202", occurrence.course.location)
        val reminder = CourseReminder(CourseReminderKey("a", "term", course.id), course, true, 15)
        val plan = CourseReminderPlanner.plan(reminder, adjusted, now, ReminderPermissions(true, true), "a", zone).next!!
        val widget = ScheduleWidgetState.from(ScheduleSnapshot("a", "school", "term", listOf(course), adjusted, now, true), now, zone)
        assertEquals(occurrence.startsAt, plan.startsAt)
        assertEquals(occurrence, CourseReminderPlanner.occurrence(reminder, adjusted, plan.startsAt, zone))
        assertEquals(occurrence, widget.primary!!.occurrence)
        val ui = ScheduleCourseUi(course.name, course.teacher, course.location, course.day, course.startPeriod, course.endPeriod, course.weeks, Color.Blue, id = course.id)
        val ics = ICalExporter.generateICalContent(listOf(ui), ScheduleOccurrenceResolver.date(base.firstWeekDate, zone)!!,
            periodTimes = mapOf(1 to ("08:00" to "08:45"), 2 to ("08:55" to "09:40"), 3 to ("10:00" to "10:45"), 4 to ("10:55" to "11:40")), adjustments = rules)
        assertTrue(ics.contains("DTSTART;TZID=Asia/Shanghai:20260915T100000"))
        assertTrue(ics.contains("DTEND;TZID=Asia/Shanghai:20260915T114000"))
        assertTrue(ics.contains("LOCATION:B202"))
        assertFalse(ics.contains("DTSTART;TZID=Asia/Shanghai:20260907"))
        assertTrue(rules.unresolved(listOf(course), adjusted).isEmpty())
    }

    @Test fun retainedRulesBecomeUnresolvedWhenACourseNoLongerRunsOnTheOriginalDate() {
        val rules = ScheduleAdjustments(lessons = listOf(ScheduleLessonOverride(course.id, "2026-09-07", targetDate = "2026-09-15")))
        assertEquals(rules.lessons, rules.unresolved(listOf(course.copy(weeks = "2")), base))
    }

    @Test fun oldReminderLeadsMigrateWithoutLosingTheirOverrideAndCustomBoundsRemainValid() {
        val key = CourseReminderKey("a", "term", course.id)
        for (lead in listOf(0, 15, 45, 1440)) {
            val legacy = ReminderJson.reminder(CourseReminder(key, course, true, lead)).apply { remove("customLead") }
            val restored = ReminderJson.reminder(legacy)
            assertEquals(lead, restored.leadMinutes)
            assertEquals(lead != 15, restored.customLead)
            val now = ScheduleOccurrenceResolver.date("2026-09-05", zone)!!.timeInMillis
            val plan = CourseReminderPlanner.plan(restored, base, now, ReminderPermissions(true, true), "a", zone).next!!
            assertEquals(lead * 60_000L, plan.startsAt - plan.triggerAt)
        }
    }
}

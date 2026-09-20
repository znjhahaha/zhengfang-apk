package com.tyust.course.schedule

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.TimeZone

class ScheduleAgendaTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val base = ScheduleTimeBase("2026-09-07", mapOf(1 to "08:00", 2 to "09:00", 3 to "10:00"),
        mapOf(1 to "08:45", 2 to "09:45", 3 to "10:45"))
    private fun time(value: String, tz: TimeZone = zone) = SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = tz }.parse(value)!!.time
    private fun course(id: String = "a", day: Int = 1, period: Int = 1, weeks: String = "1-16周") =
        ScheduleCourseRecord(id, id, "", "A101", day, period, period, weeks)
    private fun agenda(now: String, courses: List<ScheduleCourseRecord>) = ScheduleAgenda.calculate(courses, base, time(now), zone)

    @Test fun startIsInclusiveEndIsExclusiveAndNextDayIsPredicted() {
        val courses = listOf(course(), course("b", period = 2), course("tomorrow", day = 2))
        val before = agenda("2026-09-07 07:59", courses)
        assertTrue(before.current.isEmpty())
        assertEquals("a", before.next?.course?.id)
        assertEquals(listOf("a", "b", "tomorrow"), before.upcoming.take(3).map { it.course.id })
        assertEquals(time("2026-09-07 08:00"), before.nextChangeAt)
        val start = agenda("2026-09-07 08:00", courses)
        assertEquals("a", start.current.single().course.id)
        assertEquals("b", start.next?.course?.id)
        assertEquals(time("2026-09-07 08:45"), start.nextChangeAt)
        val end = agenda("2026-09-07 08:45", courses)
        assertTrue(end.current.isEmpty())
        assertEquals(1, end.remaining(time("2026-09-07 08:45")))
        val done = agenda("2026-09-07 11:00", courses)
        assertEquals(2, done.today.size)
        assertEquals(0, done.remaining(time("2026-09-07 11:00")))
        assertEquals("tomorrow", done.next?.course?.id)
        assertEquals(time("2026-09-08 00:00"), done.nextChangeAt)
    }

    @Test fun parityCrossWeekAndBeforeSemesterUseRealDates() {
        val odd = course(weeks = "1-16周(单)")
        assertEquals(0, agenda("2026-09-14 08:10", listOf(odd)).today.size)
        assertEquals(time("2026-09-21 08:00"), agenda("2026-09-14 08:10", listOf(odd)).next?.startsAt)
        val even = course("even", weeks = "1-16周(双)")
        assertEquals("even", agenda("2026-09-14 08:10", listOf(even)).current.single().course.id)
        val before = agenda("2026-09-06 08:10", listOf(odd))
        assertTrue(before.today.isEmpty())
        assertEquals(time("2026-09-07 08:00"), before.next?.startsAt)
        assertNull(agenda("2027-03-01 08:10", listOf(odd)).next)
        assertEquals(0, ScheduleDates.weekIndexAt(base.firstWeekDate, time("2026-09-06 08:10"), zone))
        assertEquals(-1, ScheduleDates.weekIndexAt(base.firstWeekDate, time("2026-08-30 08:10"), zone))
        assertEquals(26, ScheduleDates.weekIndexAt(base.firstWeekDate, time("2027-03-01 08:10"), zone))
        assertNull(ScheduleDates.weekAt(base.firstWeekDate, time("2027-03-01 08:10"), zone))
    }

    @Test fun unknownWeeksAndMissingOrReversedTimesAreNeverPredicted() {
        val invalid = listOf(course(weeks = "待安排"), course("missing", period = 4), course("bad-day", day = 9),
            course("reversed").copy(startPeriod = 3, endPeriod = 1))
        val result = agenda("2026-09-07 08:10", invalid)
        assertNull(result.next)
        assertTrue(result.current.isEmpty())
        assertTrue(ScheduleWeeks.parse("待安排").visibleIn(2))
        assertTrue(ScheduleAgenda.calculate(invalid, null, time("2026-09-07 08:10"), zone).needsCalendar)
        assertTrue(ScheduleAgenda.calculate(invalid, base.copy(firstWeekDate = "bad"), time("2026-09-07 08:10"), zone).needsCalendar)
        assertNull(ScheduleAgenda.calculate(listOf(course()), base.copy(periodStarts = mapOf(1 to "25:10")), time("2026-09-07 08:10"), zone).next)
    }

    @Test fun overlappingClassesAreAllCurrentButIdenticalRecordsAreCountedOnce() {
        val courses = listOf(course(), course(), course("overlap"))
        val result = agenda("2026-09-07 08:10", courses)
        assertEquals(2, result.today.size)
        assertEquals(2, result.current.size)
    }

    @Test fun civilDaysRespectTimezoneAndDaylightSaving() {
        val ny = TimeZone.getTimeZone("America/New_York")
        val calendar = base.copy(firstWeekDate = "2026-03-02")
        val courses = listOf(course(day = 1, weeks = "1-3周"))
        val result = ScheduleAgenda.calculate(courses, calendar, time("2026-03-08 23:30", ny), ny)
        assertEquals(time("2026-03-09 08:00", ny), result.next?.startsAt)
        assertEquals(time("2026-03-09 00:00", ny), result.nextChangeAt)
        assertEquals(2, ScheduleAgenda.calculate(courses, calendar, time("2026-03-09 08:10", ny), ny).week)
    }

    @Test fun overlapGroupsPreserveEveryCourseAndDoNotMergeAdjacentPeriodsOrDays() {
        val data = listOf(course("a").copy(endPeriod = 2), course("b", period = 2).copy(endPeriod = 3),
            course("c", period = 3), course("d", period = 4), course("other-day", day = 2))
        val groups = scheduleOverlapGroups(data)
        assertEquals(3, groups.size)
        assertEquals(listOf("a", "b", "c"), groups.first().courses.map { it.id })
        assertEquals(3, groups.first().end)
        assertEquals(data.map { it.id }.sorted(), groups.flatMap { it.courses }.map { it.id }.sorted())
    }
}

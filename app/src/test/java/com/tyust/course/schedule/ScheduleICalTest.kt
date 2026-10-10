package com.tyust.course.schedule

import androidx.compose.ui.graphics.Color
import com.tyust.course.ui.screen.ScheduleCourseUi
import com.tyust.course.utils.ICalExporter
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class ScheduleICalTest {
    private val monday = ScheduleDates.firstWeekDate("2026-09-07", TimeZone.getTimeZone("Asia/Shanghai"))!!
    private val course = ScheduleCourseUi("数学", "老师", "A101", 7, 1, 2, "1-3周(单),4周", Color.Blue, id = "network:stable")

    @Test fun sundayMixedWeeksAndConfiguredTimesMatchTheTimetable() {
        val content = ICalExporter.generateICalContent(listOf(course), monday, 25, mapOf(1 to ("09:00" to "09:45"), 2 to ("10:00" to "10:45")))
        assertTrue(content.contains("DTSTART;TZID=Asia/Shanghai:20260913T090000"))
        assertTrue(content.contains("DTEND;TZID=Asia/Shanghai:20260913T104500"))
        assertTrue(content.contains("20260927T090000"))
        assertTrue(content.contains("20261004T090000"))
        assertFalse(content.contains("20260920T090000"))
        assertEquals(ScheduleWeeks.parse(course.weeks).weeks.size, content.lineSequence().count { it == "BEGIN:VEVENT" })
    }

    @Test fun stableUidsSurviveReorderingAndCustomTextIsEscaped() {
        val other = course.copy(id = "custom:other", name = "课程,分组;一\n二", teacher = "教师\n甲")
        fun uids(list: List<ScheduleCourseUi>) = ICalExporter.generateICalContent(list, monday).lineSequence().filter { it.startsWith("UID:") }.toSet()
        assertEquals(uids(listOf(course, other)), uids(listOf(other, course)))
        val text = ICalExporter.generateICalContent(listOf(other), monday)
        assertTrue(text.contains("SUMMARY:课程\\,分组\\;一\\n二"))
        assertFalse(ICalExporter.generateICalContent(listOf(course.copy(weeks = "待核对")), monday).contains("BEGIN:VEVENT"))
    }
}

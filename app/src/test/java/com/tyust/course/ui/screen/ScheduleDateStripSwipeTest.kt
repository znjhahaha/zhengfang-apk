package com.tyust.course.ui.screen

import com.tyust.course.schedule.ScheduleMaxWeeks
import org.junit.Assert.assertEquals
import org.junit.Test

/** The week strip is one page per week: a swipe always turns exactly one week and never wraps. */
class ScheduleDateStripSwipeTest {
    @Test fun everyWeekHasItsOwnPage() {
        assertEquals(0, stripWeekPage(1))
        assertEquals(5, stripWeekPage(6))
        assertEquals(ScheduleMaxWeeks - 1, stripWeekPage(ScheduleMaxWeeks))
    }

    @Test fun aPageMapsBackToTheSameWeek() {
        for (week in 1..ScheduleMaxWeeks) assertEquals(week, stripPageWeek(stripWeekPage(week)))
    }

    @Test fun bothEndsClampInsteadOfWrapping() {
        assertEquals(0, stripWeekPage(0))
        assertEquals(0, stripWeekPage(-4))
        assertEquals(ScheduleMaxWeeks - 1, stripWeekPage(ScheduleMaxWeeks + 3))
        assertEquals(1, stripPageWeek(-3))
        assertEquals(ScheduleMaxWeeks, stripPageWeek(ScheduleMaxWeeks + 3))
    }
}

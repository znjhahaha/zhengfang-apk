package com.tyust.course.schedule

import org.junit.Assert.*
import org.junit.Test

class ScheduleWeekPagerSyncTest {
    private val calendar = "2026-2027-1|2026-09-07"

    @Test fun dayPagerCrossesSundayAndMondayWithoutTurningAParentEchoIntoAJump() {
        val sync = ScheduleWeekPagerSync(7, calendar, firstWeek = 1, lastWeek = 175)
        assertEquals(6, sync.requestPage(7, calendar))
        assertNull(sync.settledWeek(6))
        assertEquals(8, sync.settledWeek(7))
        assertNull(sync.requestPage(8, calendar))
        assertEquals(9, sync.settledWeek(8))
        assertNull(sync.requestPage(8, calendar))
        assertNull(sync.requestPage(9, calendar))
        assertEquals(2, sync.requestPage(3, "$calendar|today"))
    }

    @Test fun unboundedTodayCanBeDisplayedBeforeTheSemester() {
        val sync = ScheduleWeekPagerSync(0, calendar, firstWeek = -2, lastWeek = 25)
        assertEquals(2, sync.requestPage(0, calendar))
        assertNull(sync.settledWeek(2))
        assertEquals(1, sync.settledWeek(3))
    }

    @Test fun initialPagerPageCannotOverwriteTheWeekCalculatedFromTheStartDate() {
        val sync = ScheduleWeekPagerSync(3, calendar)
        assertNull(sync.settledWeek(0))
        assertEquals(2, sync.requestPage(3, calendar))
        assertNull(sync.settledWeek(2))
        assertNull(sync.requestPage(3, calendar))
    }

    @Test fun restoringABrowsedWeekAlignsThePagerBeforeReportingAnyUserChange() {
        val sync = ScheduleWeekPagerSync(8, calendar)
        assertNull(sync.settledWeek(0))
        assertEquals(7, sync.requestPage(8, calendar))
        assertNull(sync.settledWeek(7))
        assertEquals(9, sync.settledWeek(8))
        assertNull(sync.settledWeek(8))
    }

    @Test fun reportingAUserSwipeNeverBecomesANewCalendarJump() {
        val sync = ScheduleWeekPagerSync(3, calendar)
        sync.settledWeek(2)
        assertEquals(4, sync.settledWeek(3))
        assertEquals(5, sync.settledWeek(4))
        // The parent can acknowledge a previous page after the next gesture has started.
        assertNull(sync.requestPage(4, calendar))
        assertNull(sync.requestPage(5, calendar))
        assertEquals(6, sync.settledWeek(5))
    }

    @Test fun savingADateOverridesAnOldSettledPageEvenWhenTheRequestedWeekIsUnchanged() {
        val sync = ScheduleWeekPagerSync(3, calendar)
        sync.settledWeek(2)
        val changed = "2026-2027-1|2026-08-31"
        assertEquals(2, sync.requestPage(3, changed))
        assertNull(sync.settledWeek(3))
        assertEquals(2, sync.requestPage(3, changed))
        assertNull(sync.settledWeek(2))
        assertNull(sync.requestPage(3, changed))
    }

    @Test fun aCalendarArrivingAfterTheInitialPageAndSemesterChangesAlwaysSelectTheirOwnWeek() {
        val sync = ScheduleWeekPagerSync(1, "")
        sync.settledWeek(0)
        assertEquals(2, sync.requestPage(3, calendar))
        assertNull(sync.settledWeek(0))
        sync.settledWeek(2)
        assertEquals(0, sync.requestPage(1, "2026-2027-2|2027-03-01"))
        sync.settledWeek(0)
        assertEquals(2, sync.requestPage(3, calendar))
    }

    @Test fun callersWithoutACalendarKeyCanStillRequestWeeksAndAcknowledgeSwipes() {
        val sync = ScheduleWeekPagerSync(1, null)
        sync.settledWeek(0)
        assertEquals(4, sync.requestPage(5, null))
        sync.settledWeek(4)
        assertEquals(6, sync.settledWeek(5))
        assertNull(sync.requestPage(6, null))
        assertEquals(1, sync.requestPage(2, null))
    }
}

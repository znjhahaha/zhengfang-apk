package com.tyust.course.schedule

import com.tyust.course.manager.AppThemeMode
import com.tyust.course.manager.resolveDarkTheme
import org.junit.Assert.*
import org.junit.Test

class ScheduleDomainTest {
    @Test fun chosenDateNormalizesToLocalMondayAcrossMonthAndDstBoundaries() {
        val zone = java.util.TimeZone.getTimeZone("Europe/Berlin")
        listOf(Triple(2026, 2, 31) to "2026-03-30", Triple(2026, 10, 1) to "2026-10-26")
            .forEach { (date, expected) ->
                val chosen = java.util.Calendar.getInstance(zone).apply {
                    clear()
                    set(date.first, date.second, date.third, 19, 45)
                }
                val monday = ScheduleDates.mondayOfWeek(chosen.timeInMillis, zone)
                assertEquals(ScheduleDates.firstWeekDate(expected, zone)?.timeInMillis, monday.timeInMillis)
                assertEquals(java.util.Calendar.MONDAY, monday.get(java.util.Calendar.DAY_OF_WEEK))
                assertEquals(0, monday.get(java.util.Calendar.HOUR_OF_DAY))
                assertEquals(0, monday.get(java.util.Calendar.MINUTE))
                assertEquals(0, monday.get(java.util.Calendar.SECOND))
            }
    }

    @Test fun themeSelectionOverridesSystemAndUnknownPreferencesFollowSystem() {
        for (system in listOf(false, true)) {
            assertEquals(system, resolveDarkTheme(AppThemeMode.System, system))
            assertFalse(resolveDarkTheme(AppThemeMode.Light, system))
            assertTrue(resolveDarkTheme(AppThemeMode.Dark, system))
        }
        assertEquals(AppThemeMode.System, AppThemeMode.decode(null))
        assertEquals(AppThemeMode.System, AppThemeMode.decode("obsolete"))
        AppThemeMode.entries.forEach { assertEquals(it, AppThemeMode.decode(it.storageValue)) }
    }

    @Test fun mixedParityAndChineseSeparatorsAreSegmentLocal() {
        assertEquals(setOf(1, 2, 3, 4, 6, 8, 10, 12, 14, 15, 16), ScheduleWeeks.parse("1-4周，6-14周（双）、15-16周").weeks)
        assertEquals(setOf(1, 3, 5, 8), ScheduleWeeks.parse("１－５周(单), 8周").weeks)
        listOf("", "待定", "0-4", "4-1", "1-26", "1周,").forEach { assertFalse(it, ScheduleWeeks.parse(it).valid) }
        assertTrue(ScheduleWeeks.parse("待定").visibleIn(6))
    }

    @Test fun identitiesIgnoreListOrderAndEquivalentWeekFormatting() {
        val first = ScheduleIdentity.network("row1", "数学", "王老师", 1, 1, 2, "1-3周")
        assertEquals(first, ScheduleIdentity.network("row1", "数学", "王老师", 1, 1, 2, "1周,2周,3周"))
        assertNotEquals(first, ScheduleIdentity.network("row2", "数学", "王老师", 1, 1, 2, "1-3周"))
        assertNotEquals(first, ScheduleIdentity.network("row1", "数学", "王老师", 3, 1, 2, "1-3周"))
    }

    @Test fun conflictRequiresIntersectingDayPeriodsAndWeeksAndExcludesSelf() {
        val course = ScheduleCourseRecord("one", "数学", "", "", 1, 1, 2, "1-8周(单)")
        val conflicts = scheduleConflicts(course, listOf(course,
            course.copy(id = "touch", startPeriod = 2, endPeriod = 3, weeks = "3-5周"),
            course.copy(id = "later", startPeriod = 3, endPeriod = 4),
            course.copy(id = "even", weeks = "2-8周(双)"),
            course.copy(id = "other-day", day = 2)))
        assertEquals(listOf("touch"), conflicts.map { it.otherId })
        assertEquals(setOf(3, 5), conflicts.single().weeks)
        assertEquals(2, conflicts.single().startPeriod)
        assertEquals(2, conflicts.single().endPeriod)
    }

    @Test fun calendarWeekUsesLocalCivilDatesAcrossDaylightSavingBoundaries() {
        val zone = java.util.TimeZone.getTimeZone("Europe/Berlin")
        val later = com.tyust.course.schedule.ScheduleDates.date("2026-10-19", 2, zone = zone)!!
        assertEquals(2, ScheduleDates.weekAt("2026-10-19", later.timeInMillis, zone))
        assertNull(ScheduleDates.firstWeekDate("2026-02-30"))
        // Any weekday is a valid first-week anchor now; only a malformed date is rejected.
        assertEquals(
            "2026-09-08",
            ScheduleTimeBase.dateFromMillis(requireNotNull(ScheduleDates.firstWeekDate("2026-09-08")).timeInMillis)
        )
    }

    @Test fun sundayWeekStartShiftsTheAnchorAndTheColumnOrder() {
        val zone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        val monday = requireNotNull(ScheduleDates.firstWeekDate("2026-09-07", zone))
        val sunday = ScheduleDates.alignFirstWeekDate(monday.timeInMillis, ScheduleWeekStart.Sunday, zone)
        assertEquals("2026-09-06", ScheduleTimeBase.dateFromMillis(sunday.timeInMillis, zone))

        val sundayDate = requireNotNull(ScheduleDates.firstWeekDate("2026-09-06", zone))
        assertEquals(
            "2026-09-07",
            ScheduleTimeBase.dateFromMillis(ScheduleDates.alignFirstWeekDate(sundayDate.timeInMillis, ScheduleWeekStart.Monday, zone).timeInMillis, zone)
        )

        // Day numbers stay ISO (1 = Monday .. 7 = Sunday) whichever weekday starts the week.
        assertEquals("2026-09-07", ScheduleTimeBase.dateFromMillis(requireNotNull(ScheduleDates.date("2026-09-06", 1, 1, zone)).timeInMillis, zone))
        assertEquals("2026-09-06", ScheduleTimeBase.dateFromMillis(requireNotNull(ScheduleDates.date("2026-09-06", 1, 7, zone)).timeInMillis, zone))
        assertEquals("2026-10-18", ScheduleTimeBase.dateFromMillis(requireNotNull(ScheduleDates.date("2026-09-06", 7, 7, zone)).timeInMillis, zone))

        assertEquals(listOf(7, 1, 2, 3, 4, 5, 6), ScheduleDates.weekDayOrder("2026-09-06", zone = zone))
        assertEquals(listOf(1, 2, 3, 4, 5), ScheduleDates.weekDayOrder("2026-09-06", showWeekend = false, zone = zone))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), ScheduleDates.weekDayOrder("2026-09-07", zone = zone))
        assertEquals(ScheduleWeekStart.Sunday, ScheduleDates.weekStartOf("2026-09-06", zone))
        assertEquals(ScheduleWeekStart.Monday, ScheduleDates.weekStartOf("2026-09-07", zone))
        // A legacy anchor on any weekday still snaps to the week the user was looking at.
        assertEquals("2026-09-07", ScheduleDates.normalizeFirstWeekDate("2026-09-09", ScheduleWeekStart.Monday))
        assertEquals("2026-09-06", ScheduleDates.normalizeFirstWeekDate("2026-09-09", ScheduleWeekStart.Sunday))
    }
}

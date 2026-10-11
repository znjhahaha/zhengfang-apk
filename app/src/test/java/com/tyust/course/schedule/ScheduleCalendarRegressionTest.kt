package com.tyust.course.schedule

import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class ScheduleCalendarRegressionTest {
    private val account = "account-a"
    private val term = "2026-2027-1"

    @Test fun previouslySavedNonMondayStartStillDeterminesTheTeachingWeekAfterReload() {
        val preferences = MemoryPreferences()
        val oldCalendar = ScheduleTimeBase("2026-09-09", mapOf(1 to "08:10"), mapOf(1 to "08:55"))
        preferences.edit().putString("calendar:$account|$term", ReminderJson.timeBase(oldCalendar).toString()).apply()

        val restored = requireNotNull(ScheduleCalendarStore(preferences).read(account, term))
        assertEquals("2026-09-07", restored.firstWeekDate)
        assertEquals(oldCalendar.periodStarts, restored.periodStarts)
        assertEquals(oldCalendar.periodEnds, restored.periodEnds)
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val now = requireNotNull(ScheduleDates.firstWeekDate("2026-09-14", zone)).timeInMillis
        assertEquals(2, ScheduleDates.weekAt(restored.firstWeekDate, now, zone))
    }

    @Test fun legacyStartFillsAnExistingPeriodOnlyCalendarWithoutOverwritingItsTimes() {
        val preferences = MemoryPreferences()
        val store = ScheduleCalendarStore(preferences)
        store.write(account, term, ScheduleTimeBase(periodStarts = mapOf(1 to "08:30"), periodEnds = mapOf(1 to "09:15")))

        assertTrue(store.migrateLegacy(account, term,
            ScheduleTimeBase("2026-09-09", mapOf(1 to "08:00", 2 to "09:00"), mapOf(1 to "08:45"))))
        val restored = requireNotNull(ScheduleCalendarStore(preferences).read(account, term))
        assertEquals("2026-09-07", restored.firstWeekDate)
        assertEquals(mapOf(1 to "08:30", 2 to "09:00"), restored.periodStarts)
        assertEquals(mapOf(1 to "09:15"), restored.periodEnds)
    }

    @Test fun anEarlierEmptyMigrationCanBeRepairedOnlyForTheSameAccountAndTerm() {
        val preferences = MemoryPreferences()
        val store = ScheduleCalendarStore(preferences)
        assertFalse(store.migrateLegacy(account, term, ScheduleTimeBase()))

        val legacy = ScheduleTimeBase("2026-09-09")
        assertTrue(ScheduleCalendarStore(preferences).migrateLegacy(account, term, legacy))
        assertEquals("2026-09-07", store.read(account, term)?.firstWeekDate)
        assertFalse(store.migrateLegacy(account, "2026-2027-2", legacy))
        assertNull(store.read(account, "2026-2027-2"))
        assertNull(store.read("account-b", term))
        assertFalse(store.migrateLegacy(account, term, legacy.copy(firstWeekDate = "2026-08-31")))
        assertEquals("2026-09-07", store.read(account, term)?.firstWeekDate)
    }
}

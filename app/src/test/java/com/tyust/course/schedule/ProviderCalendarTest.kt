package com.tyust.course.schedule

import com.tyust.course.manager.ScheduleSettingsManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderCalendarTest {
    private fun calendar(date: String, time: String) = JSONObject("""{"startDate":"$date","periods":[{"number":1,"start":"$time","end":"10:00"}]}""")

    @Test fun providerUpdatesApplyUntilUserSavesAndSurviveReload() {
        val prefs = MemoryPreferences()
        val manager = ScheduleSettingsManager(prefs)
        manager.applyProviderCalendar("default", calendar("2026-09-01", "08:00"))
        val original = manager.semesterStartDate
        manager.applyProviderCalendar("default", calendar("2026-09-07", "09:00"))
        assertNotEquals(original, manager.semesterStartDate)
        assertEquals("09:00", manager.getPeriodTimes().single().startTime)
        // Saving the same date is still an explicit user choice.
        manager.semesterStartDate = manager.semesterStartDate
        val chosen = manager.semesterStartDate
        manager.savePeriodTimes(listOf(ScheduleSettingsManager.PeriodTime(1, "09:30", "10:15")))
        val reloaded = ScheduleSettingsManager(prefs)
        reloaded.applyProviderCalendar("default", calendar("2027-02-01", "07:00"))
        assertEquals(chosen, reloaded.semesterStartDate)
        assertEquals("09:30", reloaded.getPeriodTimes().single().startTime)
        reloaded.applyProviderCalendar("other", calendar("2027-02-01", "07:00"))
        assertEquals("07:00", reloaded.getPeriodTimes("other").single().startTime)
        assertEquals("09:30", reloaded.getPeriodTimes().single().startTime)
    }

    @Test fun legacyManualValuesAreNotOverwrittenBeforeMigration() {
        val prefs = MemoryPreferences()
        prefs.edit().putLong("semester_start", 1234L).putString("period_times", """[{"period":1,"start":"11:00","end":"11:45"}]""").apply()
        val manager = ScheduleSettingsManager(prefs)
        manager.applyProviderCalendar("default", calendar("2026-09-01", "08:00"))
        assertEquals(1234L, manager.semesterStartDate)
        assertEquals("11:00", manager.getPeriodTimes().single().startTime)
    }
}

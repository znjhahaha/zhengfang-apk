package com.tyust.course.schedule

import com.tyust.course.manager.ScheduleSettingsManager
import org.junit.Assert.*
import org.junit.Test

class ScheduleDisplayStoreTest {
    @Test fun defaultsAndPreferencesAreScopedToTheAccount() {
        val prefs = MemoryPreferences()
        val store = ScheduleDisplayStore(prefs, "session-a")
        assertEquals(ScheduleDisplayPreferences(true, true, false), store.read("a"))
        store.write("a", ScheduleDisplayPreferences(false, false, true))
        assertEquals(ScheduleDisplayPreferences(false, false, true), ScheduleDisplayStore(prefs, "session-a").read("a"))
        assertEquals(ScheduleDisplayPreferences(true, false, true), ScheduleDisplayStore(prefs, "fresh-launch").read("a"))
        assertEquals(ScheduleDisplayPreferences(), store.read("b"))
    }
    @Test fun dateAndBothScrollPositionsRoundTripPerAccountAndSemester() {
        val prefs = MemoryPreferences()
        val store = ScheduleDisplayStore(prefs, "session-a")
        val position = ScheduleViewPosition(8, 6, 1234, 480, "term1|2026-09-07")
        store.savePosition("a", "term1", position)
        assertEquals(position, ScheduleDisplayStore(prefs, "session-a").position("a", "term1"))
        assertNull(ScheduleDisplayStore(prefs, "fresh-launch").position("a", "term1"))
        assertNull(ScheduleDisplayStore(prefs).position("a", "term1"))
        assertNull(store.position("b", "term1"))
        assertNull(store.position("a", "term2"))
        prefs.edit().putString("position:a|term1", "corrupt").apply()
        assertNull(store.position("a", "term1"))
    }
    @Test fun customMergeUsesTheSameIdentityAndDoesNotDuplicateCachedCustomData() {
        val network = ScheduleCourseRecord("network:1", "网络课程", "", "", 1, 1, 2, "1-16周")
        val stale = network.copy(id = "custom:1", name = "旧名称", custom = true)
        val custom = ScheduleSettingsManager.CustomCourse("1", "更新课程", "A102", "", 2, 3, 4, "1-16周")
        val result = ScheduleRepository.mergeCustom(listOf(network, stale), listOf(custom))
        assertEquals(listOf("network:1", "custom:1"), result.map { it.id })
        assertEquals("更新课程", result.last().name)
    }
    @Test fun periodTimeChangesPublishARevisionAndRemainAccountScoped() {
        val prefs = MemoryPreferences()
        val manager = ScheduleSettingsManager(prefs)
        val before = manager.revision
        manager.savePeriodTimes(listOf(ScheduleSettingsManager.PeriodTime(1, "09:00", "09:45")))
        assertTrue(manager.revision > before)
        assertEquals("09:00", ScheduleSettingsManager(prefs).getPeriodTimes().first().startTime)
        assertEquals("08:00", manager.getPeriodTimes("different-account").first().startTime)
    }
}

package com.tyust.course.schedule

import com.tyust.course.manager.StartupPage
import com.tyust.course.manager.StartupPagePreferences
import org.junit.Assert.*
import org.junit.Test

class ScheduleStartupTest {
    @Test fun firstLaunchStartsOnScheduleAndExplicitStartupChoicesStillWork() {
        val preferences = StartupPagePreferences(MemoryPreferences())
        assertEquals(StartupPage.Schedule, preferences.read())
        preferences.write(StartupPage.Courses)
        assertEquals(StartupPage.Courses, preferences.read())
        assertEquals(StartupPage.Schedule, StartupPage.decode("removed-page"))
    }
}

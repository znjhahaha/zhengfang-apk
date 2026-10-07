package com.tyust.course.academic.plugin

import android.app.Application
import android.content.Intent
import com.tyust.course.schedule.CourseReminderNavigation
import com.tyust.course.schedule.ScheduleReminderScheduler
import com.tyust.course.schedule.ScheduleWidgetNavigation
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class PluginPageNavigationTest {
    @Test fun processRestorationDoesNotReplayTheSystemCopyOfAnOldLaunchIntent() {
        PluginPages.requested.value = null
        val restored = Intent().putExtra("pageId", PluginPageRegistry.SERVICES)
        PluginPages.accept(restored, restoring = true)
        assertNull(PluginPages.requested.value)
        assertFalse(restored.hasExtra("pageId"))
        PluginPages.accept(Intent().putExtra("pageId", PluginPageRegistry.SERVICES))
        assertEquals(PluginPageRegistry.SERVICES, PluginPages.requested.value?.route)
        PluginPages.consume(requireNotNull(PluginPages.requested.value))
    }

    @Test fun explicitPageRequestIsTakenOnlyOnceIncludingItsParameters() {
        val intent = Intent().putExtra("pageId", PluginPageRegistry.SERVICES).putExtra("pageParams", "{\"tab\":\"tools\"}")
        val request = PluginPages.takeRequest(intent)!!
        assertEquals(PluginPageRegistry.SERVICES, request.route)
        assertEquals("tools", org.json.JSONObject(request.params).getString("tab"))
        assertNull(PluginPages.takeRequest(Intent(intent)))
        assertFalse(intent.hasExtra("pageParams"))
    }

    @Test fun invalidAndSupersededRequestsCannotReplayOrConsumeANewerEntry() {
        val invalid = Intent().putExtra("pageId", " ").putExtra("pageParams", "bad")
        assertNull(PluginPages.takeRequest(invalid))
        assertFalse(invalid.hasExtra("pageId"))
        val old = PluginPageRequest("app.courses", "{}")
        val next = PluginPageRequest("app.schedule", "{}")
        PluginPages.requested.value = next
        PluginPages.consume(old)
        assertEquals(next, PluginPages.requested.value)
        PluginPages.consume(next)
        assertNull(PluginPages.requested.value)
    }

    @Test fun reminderAndWidgetLaunchIntentsDoNotReplayAfterConsumption() {
        val reminder = Intent().putExtra(ScheduleReminderScheduler.EXTRA_REMINDER_ID, "reminder-test")
        CourseReminderNavigation.accept(reminder)
        assertEquals("reminder-test", CourseReminderNavigation.requestedId)
        CourseReminderNavigation.consume()
        CourseReminderNavigation.accept(Intent(reminder))
        assertNull(CourseReminderNavigation.requestedId)
        val widget = Intent(ScheduleWidgetNavigation.ACTION).setData(Uri.parse("course-schedule://today?account=test&school=school&term=term"))
        ScheduleWidgetNavigation.accept(widget)
        assertEquals("test", ScheduleWidgetNavigation.requested?.account)
        ScheduleWidgetNavigation.consume()
        ScheduleWidgetNavigation.accept(Intent(widget))
        assertNull(ScheduleWidgetNavigation.requested)
    }
}

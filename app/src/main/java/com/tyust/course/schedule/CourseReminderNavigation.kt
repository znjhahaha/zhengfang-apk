package com.tyust.course.schedule

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object CourseReminderNavigation {
    var requestedId by mutableStateOf<String?>(null)
        private set
    fun accept(intent: Intent?) {
        intent?.getStringExtra(ScheduleReminderScheduler.EXTRA_REMINDER_ID)?.takeIf { it.isNotBlank() }?.let { requestedId = it }
        intent?.removeExtra(ScheduleReminderScheduler.EXTRA_REMINDER_ID)
    }
    fun consume() { requestedId = null }
}

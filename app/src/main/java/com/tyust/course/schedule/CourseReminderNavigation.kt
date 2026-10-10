package com.tyust.course.schedule

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object CourseReminderNavigation {
    var requestedId by mutableStateOf<String?>(null)
        private set
    var requestedStartsAt by mutableStateOf<Long?>(null)
        private set
    fun accept(intent: Intent?) {
        intent?.getStringExtra(ScheduleReminderScheduler.EXTRA_REMINDER_ID)?.takeIf { it.isNotBlank() }?.let {
            requestedId = it
            requestedStartsAt = intent.getLongExtra(ScheduleReminderScheduler.EXTRA_STARTS_AT, 0).takeIf { start -> start > 0 }
        }
        intent?.removeExtra(ScheduleReminderScheduler.EXTRA_REMINDER_ID)
        intent?.removeExtra(ScheduleReminderScheduler.EXTRA_STARTS_AT)
    }
    fun consume() { requestedId = null; requestedStartsAt = null }
}

package com.tyust.course.schedule

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tyust.course.MainActivity
import com.tyust.course.manager.UserManager

data class ScheduleWidgetRequest(val account: String, val school: String, val term: String, val course: String?, val nonce: Long,
    val action: ScheduleWidgetAction = if (course == null) ScheduleWidgetAction.Today else ScheduleWidgetAction.Course,
    val startsAt: Long? = null) {
    fun matches(accountKey: String, schoolId: String): Boolean =
        if (account.isBlank()) course == null && action in listOf(ScheduleWidgetAction.Today, ScheduleWidgetAction.Login)
        else account == accountKey && school == schoolId
}

object ScheduleWidgetNavigation {
    const val ACTION = "com.tyust.course.action.OPEN_TODAY_SCHEDULE"
    var requested by mutableStateOf<ScheduleWidgetRequest?>(null)
        private set

    internal fun intent(context: Context, account: String, school: String, term: String, course: String? = null,
        action: ScheduleWidgetAction = if (course == null) ScheduleWidgetAction.Today else ScheduleWidgetAction.Course,
        startsAt: Long? = null): Intent =
        Intent(context, MainActivity::class.java).setAction(ACTION)
            .putExtra("pageId", com.tyust.course.academic.plugin.PluginPageRegistry.SCHEDULE)
            .setData(Uri.Builder().scheme("course-schedule").authority("today")
                .appendQueryParameter("account", account).appendQueryParameter("school", school)
                .appendQueryParameter("term", term).appendQueryParameter("target", action.name).apply {
                    course?.let { appendQueryParameter("course", it) }
                    startsAt?.let { appendQueryParameter("startsAt", it.toString()) }
                }.build())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun accept(intent: Intent?) {
        if (intent?.action != ACTION) return
        val data = intent.data?.takeIf { it.scheme == "course-schedule" && it.authority == "today" } ?: return
        val course = data.getQueryParameter("course")
        val action = data.getQueryParameter("target")?.let { value -> ScheduleWidgetAction.entries.firstOrNull { it.name == value } }
            ?: if (course == null) ScheduleWidgetAction.Today else ScheduleWidgetAction.Course
        requested = ScheduleWidgetRequest(data.getQueryParameter("account").orEmpty(), data.getQueryParameter("school").orEmpty(),
            data.getQueryParameter("term").orEmpty(), course, System.nanoTime(), action, data.getQueryParameter("startsAt")?.toLongOrNull())
    }
    fun matchesCurrentAccount(request: ScheduleWidgetRequest): Boolean = UserManager.getInstance().let {
        request.matches(it.currentAccountStorageKey, it.currentSchool?.id.orEmpty())
    }
    fun consume() { requested = null }
}

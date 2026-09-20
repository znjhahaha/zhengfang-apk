package com.tyust.course.schedule

import android.content.Context
import com.tyust.course.academic.AcademicStudyParser
import com.tyust.course.manager.ScheduleSettingsManager

data class ScheduleSnapshot(
    val account: String, val school: String, val term: String,
    val courses: List<ScheduleCourseRecord>, val timeBase: ScheduleTimeBase,
    val cachedAt: Long, val hasCache: Boolean
)

/** Persistent schedule data shared by the app and launcher; no network or login is needed. */
class ScheduleRepository(private val context: Context,
    private val settings: ScheduleSettingsManager = ScheduleSettingsManager.getInstance().apply { init(context) }) {
    fun snapshot(account: String, school: String, termId: String? = null): ScheduleSnapshot {
        val cachePrefs = context.getSharedPreferences("schedule_cache", Context.MODE_PRIVATE)
        val cache = ScheduleCacheStore(cachePrefs)
        val term = termId?.let(AcademicStudyParser::term) ?: cache.currentTerm(account, school)
        val json = cache.read(account, school, term)
        val network = json?.let(ScheduleJson::parse).orEmpty().map { it.course }
        val records = mergeCustom(network, settings.getCustomCourses(account))
        val base = timeBase(account, term.id, cache.currentTerm(account, school).id)
        return ScheduleSnapshot(account, school, term.id, records, base,
            cachePrefs.getLong("schedule_${account}_${school}_${term.id}_time", 0), json != null)
    }

    fun timeBase(account: String, term: String, currentTerm: String): ScheduleTimeBase {
        val periods = settings.getPeriodTimes(account)
        val store = ScheduleCalendarStore(context.getSharedPreferences("course_reminders", Context.MODE_PRIVATE))
        if (account.isNotBlank() && term == currentTerm) {
            store.migrateLegacy(account, currentTerm, ScheduleTimeBase(
                ScheduleTimeBase.dateFromMillis(settings.getSemesterStartDate(account)),
                periods.associate { it.period to it.startTime }, periods.associate { it.period to it.endTime }))
        }
        val calendar = store.read(account, term)
        return ScheduleTimeBase(calendar?.firstWeekDate.orEmpty(),
            periods.associate { it.period to it.startTime } + calendar?.periodStarts.orEmpty(),
            periods.associate { it.period to it.endTime } + calendar?.periodEnds.orEmpty())
    }
    companion object {
        fun mergeCustom(network: List<ScheduleCourseRecord>, custom: List<ScheduleSettingsManager.CustomCourse>): List<ScheduleCourseRecord> =
            network.filterNot { it.custom } + custom.map { ScheduleCourseRecord("custom:${it.id}", it.name, it.teacher,
                it.location, it.day, it.startPeriod, it.endPeriod, it.weeks, true) }
    }
}

package com.tyust.course.schedule

import java.util.Calendar
import java.util.TimeZone

data class ScheduleOccurrence(val course: ScheduleCourseRecord, val startsAt: Long, val endsAt: Long,
    val originalDate: String = "")
data class ScheduleAgenda(
    val week: Int?,
    val today: List<ScheduleOccurrence>,
    val current: List<ScheduleOccurrence>,
    val next: ScheduleOccurrence?,
    val nextChangeAt: Long,
    val needsCalendar: Boolean,
    val upcoming: List<ScheduleOccurrence> = listOfNotNull(next)
) {
    fun remaining(now: Long) = today.count { it.endsAt > now }
    companion object {
        fun calculate(courses: List<ScheduleCourseRecord>, base: ScheduleTimeBase?, now: Long, zone: TimeZone = TimeZone.getDefault()): ScheduleAgenda {
            val day = Calendar.getInstance(zone).apply {
                timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val tomorrow = (day.clone() as Calendar).apply { add(Calendar.DATE, 1) }.timeInMillis
            if (base == null || ScheduleDates.firstMonday(base.firstWeekDate, zone) == null)
                return ScheduleAgenda(null, emptyList(), emptyList(), null, tomorrow, true)
            val occurrences = ScheduleOccurrenceResolver.resolve(courses, base, zone).filter { it.endsAt >= day.timeInMillis }
            val today = occurrences.filter { it.startsAt in day.timeInMillis until tomorrow }
            val current = today.filter { now in it.startsAt until it.endsAt }
            val upcoming = occurrences.filter { it.startsAt > now }
            val next = upcoming.firstOrNull()
            val boundary = (current.map { it.endsAt } + listOfNotNull(next?.startsAt) + tomorrow).filter { it > now }.min()
            return ScheduleAgenda(ScheduleDates.weekAt(base.firstWeekDate, now, zone), today, current, next, boundary, false, upcoming)
        }
    }
}

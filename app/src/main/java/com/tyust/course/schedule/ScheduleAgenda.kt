package com.tyust.course.schedule

import java.util.Calendar
import java.util.TimeZone

data class ScheduleOccurrence(val course: ScheduleCourseRecord, val startsAt: Long, val endsAt: Long)
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
            fun time(value: String?): Pair<Int, Int>? {
                val parts = value?.split(':')?.map { it.toIntOrNull() } ?: return null
                return if (parts.size == 2 && parts[0] in 0..23 && parts[1] in 0..59) parts[0]!! to parts[1]!! else null
            }
            val occurrences = courses.distinctBy { it.id }.flatMap { course ->
                val weeks = ScheduleWeeks.parse(course.weeks)
                val start = time(base.periodStarts[course.startPeriod])
                val end = time(base.periodEnds[course.endPeriod])
                if (!weeks.valid || course.day !in 1..7 || start == null || end == null || course.endPeriod < course.startPeriod) emptyList()
                else weeks.weeks.mapNotNull { week ->
                    val date = ScheduleDates.date(base.firstWeekDate, week, course.day, zone) ?: return@mapNotNull null
                    val startsAt = (date.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, start.first); set(Calendar.MINUTE, start.second) }.timeInMillis
                    val endsAt = date.apply { set(Calendar.HOUR_OF_DAY, end.first); set(Calendar.MINUTE, end.second) }.timeInMillis
                    if (endsAt <= startsAt || endsAt < day.timeInMillis) null else ScheduleOccurrence(course, startsAt, endsAt)
                }
            }.sortedWith(compareBy<ScheduleOccurrence> { it.startsAt }.thenBy { it.course.id })
            val today = occurrences.filter { it.startsAt in day.timeInMillis until tomorrow }
            val current = today.filter { now in it.startsAt until it.endsAt }
            val upcoming = occurrences.filter { it.startsAt > now }
            val next = upcoming.firstOrNull()
            val boundary = (current.map { it.endsAt } + listOfNotNull(next?.startsAt) + tomorrow).filter { it > now }.min()
            return ScheduleAgenda(ScheduleDates.weekAt(base.firstWeekDate, now, zone), today, current, next, boundary, false, upcoming)
        }
    }
}

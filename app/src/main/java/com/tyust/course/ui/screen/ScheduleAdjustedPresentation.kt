package com.tyust.course.ui.screen

import com.tyust.course.schedule.*

/** Keep the source course id for actions; occurrences carry their own displayed date and week. */
internal fun resolvedScheduleUi(courses: List<ScheduleCourseUi>, base: ScheduleTimeBase): List<ScheduleCourseUi> {
    if (base.adjustments.isEmpty || ScheduleDates.firstMonday(base.firstWeekDate) == null) return courses
    val sources = courses.associateBy { it.id }
    return ScheduleOccurrenceResolver.resolve(courses.map { it.record() }, base).mapNotNull { occurrence ->
        val source = sources[occurrence.course.id] ?: return@mapNotNull null
        source.copy(day = ScheduleDates.dayAt(occurrence.startsAt),
            weeks = (ScheduleDates.weekIndexAt(base.firstWeekDate, occurrence.startsAt) ?: 1).toString(),
            startPeriod = occurrence.course.startPeriod, endPeriod = occurrence.course.endPeriod, location = occurrence.course.location,
            occurrenceDate = ScheduleTimeBase.dateFromMillis(occurrence.startsAt), originalOccurrenceDate = occurrence.originalDate)
    } + courses.filter { !ScheduleWeeks.parse(it.weeks).valid }
}

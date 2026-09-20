package com.tyust.course.schedule

data class ScheduleOverlapGroup(val courses: List<ScheduleCourseRecord>) {
    val day get() = courses.first().day
    val start get() = courses.minOf { it.startPeriod }
    val end get() = courses.maxOf { it.endPeriod }
}

/** Connected intervals share one selectable group, so a later card cannot cover an earlier one. */
fun scheduleOverlapGroups(courses: List<ScheduleCourseRecord>): List<ScheduleOverlapGroup> =
    courses.filter { it.day in 1..7 && it.startPeriod > 0 && it.endPeriod >= it.startPeriod }
        .groupBy { it.day }.toSortedMap().values.flatMap { day ->
            val result = mutableListOf<ScheduleOverlapGroup>()
            var group = mutableListOf<ScheduleCourseRecord>()
            var end = 0
            for (course in day.sortedWith(compareBy<ScheduleCourseRecord> { it.startPeriod }.thenBy { it.id })) {
                if (group.isNotEmpty() && course.startPeriod > end) {
                    result += ScheduleOverlapGroup(group.toList())
                    group = mutableListOf()
                }
                group += course
                end = group.maxOf { it.endPeriod }
            }
            if (group.isNotEmpty()) result += ScheduleOverlapGroup(group)
            result
        }

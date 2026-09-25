package com.tyust.course.schedule

/** A one-time action over a complete term snapshot; it is never an opt-in default for future courses. */
object SemesterReminders {
    fun update(current: List<CourseReminder>, account: String, term: String,
        courses: List<ScheduleCourseRecord>, enabled: Boolean): List<CourseReminder> {
        if (account.isBlank() || term.isBlank() || courses.isEmpty()) return current
        val result = current.associateByTo(linkedMapOf()) { it.key }
        for (course in courses.distinctBy { it.id }) {
            if (course.id.isBlank()) continue
            val key = CourseReminderKey(account, term, course.id)
            val old = result[key]
            if (old == null && !enabled) continue
            if (old?.enabled == enabled && old.course == course) continue
            result[key] = CourseReminder(key, course, enabled, old?.leadMinutes ?: 15, (old?.revision ?: 0) + 1)
        }
        return result.values.toList()
    }

    fun summary(current: List<CourseReminder>, account: String, term: String, courses: List<ScheduleCourseRecord>,
        timeBase: ScheduleTimeBase?, permissions: ReminderPermissions, activeAccount: String, now: Long): SemesterReminderSummary {
        val ids = courses.map { it.id }.toSet()
        val enabled = current.filter { it.key.account == account && it.key.term == term && it.key.courseId in ids && it.enabled }
        val states = enabled.groupingBy { CourseReminderPlanner.plan(it, timeBase, now, permissions, activeAccount).availability }.eachCount()
        return SemesterReminderSummary(ids.size, enabled.size, states)
    }
}

data class SemesterReminderSummary(val total: Int, val enabled: Int, val states: Map<ReminderAvailability, Int>) {
    fun count(state: ReminderAvailability) = states[state] ?: 0
}

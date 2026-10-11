package com.tyust.course.schedule

import java.util.Calendar
import java.util.TimeZone

data class CourseReminderKey(val account: String, val term: String, val courseId: String) {
    val storageId: String get() = ScheduleIdentity.digest(listOf(account, term, courseId).joinToString("\u001f"))
}

data class CourseReminder(val key: CourseReminderKey, val course: ScheduleCourseRecord, val enabled: Boolean = false,
    val leadMinutes: Int = 15, val revision: Long = 1L, val customLead: Boolean = false)

data class ScheduleTimeBase(val firstWeekDate: String = "", val periodStarts: Map<Int, String> = emptyMap(),
    val periodEnds: Map<Int, String> = emptyMap(), val adjustments: ScheduleAdjustments = ScheduleAdjustments()) {
    companion object {
        fun dateFromMillis(millis: Long, zone: TimeZone = TimeZone.getDefault()): String {
            if (millis <= 0) return ""
            val calendar = Calendar.getInstance(zone).apply { timeInMillis = millis }
            return "%04d-%02d-%02d".format(java.util.Locale.ROOT, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH))
        }
    }
}

data class ReminderPermissions(val notifications: Boolean, val exactAlarms: Boolean) {
    val available: Boolean get() = notifications && exactAlarms
}

enum class ReminderAvailability { Off, NeedsPermission, NeedsTime, InvalidWeeks, Scheduled, NoUpcoming, InactiveAccount }

data class PlannedReminder(val reminder: CourseReminder, val triggerAt: Long, val startsAt: Long)
data class ReminderStatus(val availability: ReminderAvailability, val next: PlannedReminder? = null)

object CourseReminderPlanner {
    fun occurrence(reminder: CourseReminder, timeBase: ScheduleTimeBase?, startsAt: Long,
        zone: TimeZone = TimeZone.getDefault()): ScheduleOccurrence? =
        ScheduleOccurrenceResolver.resolve(listOf(reminder.course), timeBase, zone, requireEnd = false).firstOrNull { it.startsAt == startsAt }

    fun plan(reminder: CourseReminder, timeBase: ScheduleTimeBase?, now: Long, permissions: ReminderPermissions,
        activeAccount: String, zone: TimeZone = TimeZone.getDefault()): ReminderStatus {
        if (!reminder.enabled) return ReminderStatus(ReminderAvailability.Off)
        if (reminder.key.account != activeAccount || activeAccount.isBlank()) return ReminderStatus(ReminderAvailability.InactiveAccount)
        if (!permissions.available) return ReminderStatus(ReminderAvailability.NeedsPermission)
        val course = reminder.course
        val weeks = ScheduleWeeks.parse(course.weeks)
        if (!weeks.valid) return ReminderStatus(ReminderAvailability.InvalidWeeks)
        val date = timeBase?.firstWeekDate?.split('-')?.map { it.toIntOrNull() }
        val time = timeBase?.periodStarts?.get(course.startPeriod)?.split(':')?.map { it.toIntOrNull() }
        if (date?.size != 3 || date.any { it == null } || time?.size != 2 || time.any { it == null } ||
            time[0] !in 0..23 || time[1] !in 0..59 || course.day !in 1..7 ||
            course.startPeriod < 1 || course.endPeriod < course.startPeriod || reminder.leadMinutes !in 0..1440) {
            return ReminderStatus(ReminderAvailability.NeedsTime)
        }
        if (ScheduleDates.firstWeekDate(timeBase.firstWeekDate, zone) == null)
            return ReminderStatus(ReminderAvailability.NeedsTime)
        for (occurrence in ScheduleOccurrenceResolver.resolve(listOf(course), timeBase, zone, requireEnd = false)) {
            val startsAt = occurrence.startsAt
            val trigger = startsAt - reminder.leadMinutes * 60_000L
            if (trigger > now) return ReminderStatus(ReminderAvailability.Scheduled, PlannedReminder(reminder, trigger, startsAt))
        }
        return ReminderStatus(ReminderAvailability.NoUpcoming)
    }
}

interface ReminderAlarmPort {
    fun scheduled(): Map<String, PlannedReminder>
    fun exists(key: CourseReminderKey): Boolean
    fun schedule(plan: PlannedReminder)
    fun cancel(key: CourseReminderKey)
}

/** Only a previously scheduled, unchanged occurrence may survive until its broadcast arrives. */
fun isCurrentReminderOccurrence(plan: PlannedReminder, reminder: CourseReminder, timeBase: ScheduleTimeBase?,
    permissions: ReminderPermissions, activeAccount: String, zone: TimeZone = TimeZone.getDefault()): Boolean =
    plan.reminder == reminder && CourseReminderPlanner.plan(reminder, timeBase, plan.triggerAt - 1,
        permissions, activeAccount, zone).next == plan

/** Repeated reconciliation has no side effects when the desired alarms already exist. */
fun reconcileCourseReminders(reminders: List<CourseReminder>, activeAccount: String,
    timeBase: (CourseReminderKey) -> ScheduleTimeBase?, permissions: ReminderPermissions, now: Long,
    alarms: ReminderAlarmPort, zone: TimeZone = TimeZone.getDefault()): Map<String, ReminderStatus> {
    val existing = alarms.scheduled()
    val status = reminders.associate { reminder ->
        val base = timeBase(reminder.key)
        val pending = existing[reminder.key.storageId]
        val due = pending?.takeIf { now >= it.triggerAt && now < it.startsAt &&
            isCurrentReminderOccurrence(it, reminder, base, permissions, activeAccount, zone) && alarms.exists(reminder.key) }
        // A resume/cold-start can run before Android delivers an already due alarm. Do not
        // replace its PendingIntent extras with the next week's occurrence in that window.
        reminder.key.storageId to (due?.let { ReminderStatus(ReminderAvailability.Scheduled, it) }
            ?: CourseReminderPlanner.plan(reminder, base, now, permissions, activeAccount, zone))
    }
    val desired = status.mapNotNull { (id, state) -> state.next?.let { id to it } }.toMap()
    existing.filterKeys { it !in desired }.values.forEach { alarms.cancel(it.reminder.key) }
    desired.forEach { (id, plan) ->
        if (existing[id] != plan || !alarms.exists(plan.reminder.key)) alarms.schedule(plan)
    }
    return status
}

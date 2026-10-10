package com.tyust.course.schedule

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CourseReminderPlannerTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val course = ScheduleCourseRecord("custom:one", "数学", "", "A101", 1, 1, 2, "1-3周")
    private val reminder = CourseReminder(CourseReminderKey("account1", "2026-2027-1", course.id), course, enabled = true)
    private val base = ScheduleTimeBase("2026-09-07", mapOf(1 to "08:00"))
    private val allowed = ReminderPermissions(true, true)
    private fun time(day: Int, hour: Int, minute: Int) = Calendar.getInstance(zone).apply {
        clear(); set(2026, Calendar.SEPTEMBER, day, hour, minute)
    }.timeInMillis

    @Test fun schedulesFifteenMinutesBeforeAndSkipsPastReminderTimes() {
        val first = CourseReminderPlanner.plan(reminder, base, time(7, 7, 0), allowed, "account1", zone)
        assertEquals(time(7, 7, 45), first.next!!.triggerAt)
        val later = CourseReminderPlanner.plan(reminder, base, time(7, 7, 50), allowed, "account1", zone)
        assertEquals(time(14, 7, 45), later.next!!.triggerAt)
        assertEquals(ReminderAvailability.NoUpcoming, CourseReminderPlanner.plan(reminder, base, time(22, 0, 0), allowed, "account1", zone).availability)
    }

    @Test fun permissionsDatesAndAccountScopePreventInvalidAlarms() {
        fun status(r: CourseReminder = reminder, b: ScheduleTimeBase? = base, p: ReminderPermissions = allowed, account: String = "account1") =
            CourseReminderPlanner.plan(r, b, time(7, 7, 0), p, account, zone).availability
        assertEquals(ReminderAvailability.Off, status(reminder.copy(enabled = false)))
        assertEquals(ReminderAvailability.NeedsPermission, status(p = ReminderPermissions(false, true)))
        assertEquals(ReminderAvailability.NeedsPermission, status(p = ReminderPermissions(true, false)))
        assertEquals(ReminderAvailability.NeedsTime, status(b = null))
        assertEquals(ReminderAvailability.NeedsTime, status(b = base.copy(firstWeekDate = "not-a-date")))
        // A teaching week may start on any weekday now, so a Tuesday anchor still schedules its Monday course one week later.
        assertEquals(ReminderAvailability.Scheduled, status(b = base.copy(firstWeekDate = "2026-09-08")))
        assertEquals(ReminderAvailability.InvalidWeeks, status(reminder.copy(course = course.copy(weeks = "待定"))))
        assertEquals(ReminderAvailability.InactiveAccount, status(account = "other"))
    }

    @Test fun reconciliationIsIdempotentAndCancelsOnAccountSwitchDeleteOrPermissionLoss() {
        val alarms = FakeAlarms()
        fun run(list: List<CourseReminder> = listOf(reminder), account: String = "account1", permissions: ReminderPermissions = allowed) =
            reconcileCourseReminders(list, account, { base }, permissions, time(7, 7, 0), alarms, zone)
        run(); run()
        assertEquals(1, alarms.writes)
        run(account = "other")
        assertTrue(alarms.plans.isEmpty())
        run(); run(permissions = ReminderPermissions(false, true))
        assertTrue(alarms.plans.isEmpty())
        run(); run(emptyList())
        assertTrue(alarms.plans.isEmpty())
        run()
        val changed = reminder.copy(revision = 2, course = course.copy(day = 2))
        run(listOf(changed))
        assertEquals(time(8, 7, 45), alarms.plans.values.single().triggerAt)
        assertNotEquals(reminder.key.storageId, reminder.key.copy(term = "2026-2027-2").storageId)
    }

    @Test fun coldStartAndResumeKeepDueBroadcastUntilDeliveryWithoutReplayingHistory() {
        val alarms = FakeAlarms()
        fun run(now: Long, list: List<CourseReminder> = listOf(reminder), calendar: ScheduleTimeBase = base) =
            reconcileCourseReminders(list, "account1", { calendar }, allowed, now, alarms, zone)
        run(time(7, 7, 0))
        val due = alarms.plans.values.single()
        run(time(7, 7, 46))
        run(time(7, 7, 50))
        assertEquals(due, alarms.plans.values.single())
        assertEquals(1, alarms.writes)
        // Delivery consumes its saved plan before reconciling the next occurrence.
        alarms.plans.clear()
        run(time(7, 7, 50))
        assertEquals(time(14, 7, 45), alarms.plans.values.single().triggerAt)
        assertEquals(2, alarms.writes)
    }

    @Test fun expiredOrChangedPendingOccurrenceCannotSurviveReconciliation() {
        val alarms = FakeAlarms()
        fun run(now: Long, calendar: ScheduleTimeBase = base, list: List<CourseReminder> = listOf(reminder)) =
            reconcileCourseReminders(list, "account1", { calendar }, allowed, now, alarms, zone)
        run(time(7, 7, 0))
        val original = alarms.plans.values.single()
        assertFalse(isCurrentReminderOccurrence(original, reminder, base.copy(periodStarts = mapOf(1 to "09:00")), allowed, "account1", zone))
        run(time(7, 7, 50), base.copy(periodStarts = mapOf(1 to "09:00")))
        assertEquals(time(7, 8, 45), alarms.plans.values.single().triggerAt)
        run(time(7, 9, 0))
        assertEquals(time(14, 7, 45), alarms.plans.values.single().triggerAt)
        run(time(7, 9, 1), list = listOf(reminder.copy(enabled = false, revision = 2)))
        assertTrue(alarms.plans.isEmpty())
    }

    private class FakeAlarms : ReminderAlarmPort {
        val plans = mutableMapOf<String, PlannedReminder>()
        var writes = 0
        override fun scheduled() = plans.toMap()
        override fun exists(key: CourseReminderKey) = key.storageId in plans
        override fun schedule(plan: PlannedReminder) { writes++; plans[plan.reminder.key.storageId] = plan }
        override fun cancel(key: CourseReminderKey) { plans.remove(key.storageId) }
    }
}

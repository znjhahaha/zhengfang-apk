package com.tyust.course.schedule

import java.util.Calendar
import java.util.TimeZone

object ScheduleDates {
    /** UI-selected dates use Monday-based teaching weeks, in the user's local zone. */
    fun mondayOfWeek(millis: Long, zone: TimeZone = TimeZone.getDefault()): Calendar =
        Calendar.getInstance(zone).apply {
            timeInMillis = millis
            add(Calendar.DAY_OF_MONTH, -((get(Calendar.DAY_OF_WEEK) + 5) % 7))
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun calendarDate(value: String?, zone: TimeZone): Calendar? = runCatching {
        val parts = requireNotNull(value).split('-').map(String::toInt)
        require(parts.size == 3)
        Calendar.getInstance(zone).apply {
            clear(); isLenient = false
            set(parts[0], parts[1] - 1, parts[2])
            timeInMillis
        }
    }.getOrNull()

    fun firstMonday(value: String?, zone: TimeZone = TimeZone.getDefault()): Calendar? =
        calendarDate(value, zone)?.takeIf { it.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY }

    /** Older versions persisted any selected weekday; normalize the civil date without a zone shift. */
    fun normalizeFirstWeekDate(value: String?): String? {
        val zone = TimeZone.getTimeZone("UTC")
        val chosen = calendarDate(value, zone) ?: return null
        return ScheduleTimeBase.dateFromMillis(mondayOfWeek(chosen.timeInMillis, zone).timeInMillis, zone)
    }

    fun date(value: String?, week: Int, day: Int = 1, zone: TimeZone = TimeZone.getDefault()): Calendar? =
        firstMonday(value, zone)?.apply { add(Calendar.DAY_OF_YEAR, (week - 1) * 7 + day - 1) }

    fun dayAt(now: Long, zone: TimeZone = TimeZone.getDefault()): Int =
        (Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1

    fun weekAt(value: String?, now: Long, zone: TimeZone = TimeZone.getDefault()): Int? =
        weekIndexAt(value, now, zone)?.takeIf { it in 1..ScheduleMaxWeeks }

    /** An unbounded civil week lets Today stay on the real date before/after a semester. */
    fun weekIndexAt(value: String?, now: Long, zone: TimeZone = TimeZone.getDefault()): Int? {
        val start = firstMonday(value, TimeZone.getTimeZone("UTC")) ?: return null
        val local = Calendar.getInstance(zone).apply { timeInMillis = now }
        val today = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }
        val days = (today.timeInMillis - start.timeInMillis) / 86_400_000L
        return Math.floorDiv(days, 7L).toInt() + 1
    }
}

package com.tyust.course.schedule

import java.util.Calendar
import java.util.TimeZone

/**
 * Teaching weeks in the user's local zone.
 *
 * The persisted first-week date is the first day of week 1, and the user picks which weekday that
 * is ([ScheduleWeekStart]). Every displayed date, column order and export therefore derives from
 * the anchor alone instead of assuming Monday.
 */
object ScheduleDates {
    private const val ISO_SATURDAY = 6
    private const val ISO_SUNDAY = 7

    /** The ISO day (1 = Monday .. 7 = Sunday) of a [Calendar.DAY_OF_WEEK] constant. */
    fun isoDayOfWeek(dayOfWeek: Int): Int = (dayOfWeek + 5) % 7 + 1

    /** Midnight of the first day of the week containing [millis]. */
    fun startOfWeek(
        millis: Long,
        weekStart: ScheduleWeekStart = ScheduleWeekStart.Default,
        zone: TimeZone = TimeZone.getDefault()
    ): Calendar = Calendar.getInstance(zone).apply {
        timeInMillis = millis
        add(Calendar.DAY_OF_MONTH, -((get(Calendar.DAY_OF_WEEK) - weekStart.firstDayOfWeek + 7) % 7))
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    /** Monday-based weeks; kept for previews and callers without an account preference. */
    fun mondayOfWeek(millis: Long, zone: TimeZone = TimeZone.getDefault()): Calendar =
        startOfWeek(millis, ScheduleWeekStart.Monday, zone)

    private fun calendarDate(value: String?, zone: TimeZone): Calendar? = runCatching {
        val parts = requireNotNull(value).split('-').map(String::toInt)
        require(parts.size == 3)
        Calendar.getInstance(zone).apply {
            clear(); isLenient = false
            set(parts[0], parts[1] - 1, parts[2])
            timeInMillis
        }
    }.getOrNull()

    /**
     * Parses the stored first-week anchor. Any weekday is valid because the week start is the
     * user's choice; only an unparseable date is rejected.
     */
    fun firstWeekDate(value: String?, zone: TimeZone = TimeZone.getDefault()): Calendar? = calendarDate(value, zone)

    /** The week start the stored anchor itself stands for. */
    fun weekStartOf(value: String?, zone: TimeZone = TimeZone.getDefault()): ScheduleWeekStart =
        firstWeekDate(value, zone)?.let { ScheduleWeekStart.of(it.get(Calendar.DAY_OF_WEEK)) } ?: ScheduleWeekStart.Default

    /**
     * Moves [millis] onto [weekStart] while week 1 keeps the days the user already sees: the anchor
     * is the first day of the week holding the day *after* it. Monday → Sunday → Monday therefore
     * round-trips exactly, and an older arbitrary weekday (say Wednesday) still lands on its Monday.
     */
    fun alignFirstWeekDate(
        millis: Long,
        weekStart: ScheduleWeekStart = ScheduleWeekStart.Default,
        zone: TimeZone = TimeZone.getDefault()
    ): Calendar = startOfWeek(Calendar.getInstance(zone).apply {
        timeInMillis = millis
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis, weekStart, zone)

    /**
     * Snaps a stored anchor to the user's chosen first weekday without a zone shift, so an older
     * Monday-only value keeps its week while a Sunday week start survives a reload.
     */
    fun normalizeFirstWeekDate(value: String?, weekStart: ScheduleWeekStart = ScheduleWeekStart.Default): String? {
        val zone = TimeZone.getTimeZone("UTC")
        val chosen = calendarDate(value, zone) ?: return null
        return ScheduleTimeBase.dateFromMillis(alignFirstWeekDate(chosen.timeInMillis, weekStart, zone).timeInMillis, zone)
    }

    /** Column order of one week: ISO days starting from the anchor's own weekday. */
    fun weekDayOrder(value: String?, showWeekend: Boolean = true, zone: TimeZone = TimeZone.getDefault()): List<Int> {
        val first = firstWeekDate(value, zone)?.get(Calendar.DAY_OF_WEEK) ?: ScheduleWeekStart.Default.firstDayOfWeek
        val order = (0 until 7).map { isoDayOfWeek(((first - 1 + it) % 7) + 1) }
        return if (showWeekend) order else order.filter { it != ISO_SATURDAY && it != ISO_SUNDAY }
    }

    /**
     * The civil date of [day] (ISO: 1 = Monday .. 7 = Sunday) in teaching [week]. A Monday anchor
     * keeps the historical `week - 1` + `day - 1` arithmetic; a Sunday anchor starts one day earlier.
     */
    fun date(value: String?, week: Int, day: Int = 1, zone: TimeZone = TimeZone.getDefault()): Calendar? {
        val anchor = firstWeekDate(value, zone) ?: return null
        val anchorDay = isoDayOfWeek(anchor.get(Calendar.DAY_OF_WEEK))
        return anchor.apply {
            add(Calendar.DAY_OF_YEAR, (week - 1) * 7 + (((day - anchorDay) % 7) + 7) % 7)
        }
    }

    fun dayAt(now: Long, zone: TimeZone = TimeZone.getDefault()): Int =
        isoDayOfWeek(Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.DAY_OF_WEEK))

    fun weekAt(value: String?, now: Long, zone: TimeZone = TimeZone.getDefault()): Int? =
        weekIndexAt(value, now, zone)?.takeIf { it in 1..ScheduleMaxWeeks }

    /** An unbounded civil week lets Today stay on the real date before/after a semester. */
    fun weekIndexAt(value: String?, now: Long, zone: TimeZone = TimeZone.getDefault()): Int? {
        val start = firstWeekDate(value, TimeZone.getTimeZone("UTC")) ?: return null
        val local = Calendar.getInstance(zone).apply { timeInMillis = now }
        val today = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }
        val days = (today.timeInMillis - start.timeInMillis) / 86_400_000L
        return Math.floorDiv(days, 7L).toInt() + 1
    }
}

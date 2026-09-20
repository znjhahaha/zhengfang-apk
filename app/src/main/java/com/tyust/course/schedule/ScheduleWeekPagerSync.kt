package com.tyust.course.schedule

/** Calendar changes request a page; settled user swipes only report the page for restoration. */
internal class ScheduleWeekPagerSync(initialWeek: Int, initialRequestKey: String?,
    private val firstWeek: Int = 1, private val lastWeek: Int = ScheduleMaxWeeks) {
    private var requestKey = initialRequestKey
    private var requestedWeek = initialWeek.coerceIn(firstWeek, lastWeek)
    private var pendingWeek: Int? = requestedWeek
    private var lastSettledWeek: Int? = null
    private var lastReportedWeek: Int? = null

    fun requestPage(week: Int, key: String?): Int? {
        val requested = week.coerceIn(firstWeek, lastWeek)
        // Callers without a calendar key can still request a week directly.
        val directRequest = key == null && requested != requestedWeek && requested != lastReportedWeek
        if (key != requestKey || directRequest) pendingWeek = requested
        requestKey = key
        requestedWeek = requested
        return pendingWeek?.minus(firstWeek)
    }

    fun settledWeek(page: Int): Int? {
        val week = (page + firstWeek).coerceIn(firstWeek, lastWeek)
        if (pendingWeek != null) {
            if (week == pendingWeek) {
                pendingWeek = null
                lastSettledWeek = week
            }
            return null
        }
        if (week == lastSettledWeek) return null
        lastSettledWeek = week
        lastReportedWeek = week
        return week
    }
}

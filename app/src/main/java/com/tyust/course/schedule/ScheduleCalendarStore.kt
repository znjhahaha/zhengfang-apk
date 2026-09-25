package com.tyust.course.schedule

import android.content.SharedPreferences
import org.json.JSONObject

internal class ScheduleCalendarStore(private val preferences: SharedPreferences) {
    private fun normalized(value: ScheduleTimeBase): ScheduleTimeBase = value.copy(
        firstWeekDate = ScheduleDates.normalizeFirstWeekDate(value.firstWeekDate) ?: value.firstWeekDate
    )

    fun read(account: String, term: String): ScheduleTimeBase? = preferences.getString("calendar:$account|$term", null)?.let {
        runCatching { normalized(ReminderJson.timeBase(JSONObject(it))) }.getOrNull()
    }

    fun write(account: String, term: String, value: ScheduleTimeBase): Boolean {
        val calendar = normalized(value)
        if (account.isBlank() || term.isBlank() || read(account, term) == calendar) return false
        check(preferences.edit().putString("calendar:$account|$term", ReminderJson.timeBase(calendar).toString()).commit())
        return true
    }

    /** The caller supplies the actual current term, never the term the user is browsing. */
    fun migrateLegacy(account: String, currentTerm: String, value: ScheduleTimeBase): Boolean {
        val marker = "legacy-calendar-migrated:$account"
        if (account.isBlank() || currentTerm.isBlank()) return false
        val migratedTerm = preferences.getString(marker, null)
        if (migratedTerm != null && migratedTerm != currentTerm) return false
        if (migratedTerm == null) preferences.edit().putString(marker, currentTerm).apply()
        val legacy = normalized(value)
        val existing = read(account, currentTerm)
        // A period-only record or an earlier empty migration must not discard the old start date.
        // An explicitly saved start date and every other semester keep their own calendar.
        if (ScheduleDates.firstMonday(legacy.firstWeekDate) == null || !existing?.firstWeekDate.isNullOrBlank()) return false
        return write(account, currentTerm, legacy.copy(
            periodStarts = legacy.periodStarts + existing?.periodStarts.orEmpty(),
            periodEnds = legacy.periodEnds + existing?.periodEnds.orEmpty()
        ))
    }
}

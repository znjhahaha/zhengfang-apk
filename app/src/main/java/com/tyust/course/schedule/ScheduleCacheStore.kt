package com.tyust.course.schedule

import android.content.SharedPreferences
import com.tyust.course.academic.AcademicStudyAdapter
import com.tyust.course.academic.AcademicStudyBridge
import com.tyust.course.academic.AcademicStudyParser
import com.tyust.course.academic.AcademicStudyReader
import com.tyust.course.academic.AcademicTerm
import org.json.JSONObject

internal data class CachedSchedule(
    val currentTerm: AcademicTerm,
    val term: AcademicTerm,
    val json: String,
    val fromCache: Boolean,
    val calendar: JSONObject? = null
)

/** Account/term data survives login sessions. Entry refreshes and manual sync both bypass it. */
internal class ScheduleCacheStore(
    private val preferences: SharedPreferences,
    private val calendarTerm: () -> AcademicTerm = { AcademicStudyReader.calendarTerm() }
) {
    private fun prefix(account: String, school: String) = "schedule_${account}_${school}"

    fun currentTerm(account: String, school: String): AcademicTerm {
        val calendar = calendarTerm()
        val known = runCatching {
            JSONObject(preferences.getString("${prefix(account, school)}_current", null) ?: return@runCatching null)
        }.getOrNull()
        // Resolve the school's term again when the local academic half-year changes.
        // A school can legitimately start later than the calendar fallback.
        val current = known?.takeIf { it.optString("calendar") == calendar.id }
        return current?.optJSONObject("termMetadata")?.let(AcademicTerm::fromJson)
            ?: current?.optString("term")?.let(AcademicStudyParser::term) ?: calendar
    }

    fun read(account: String, school: String, term: AcademicTerm): String? {
        val base = prefix(account, school)
        val keys = buildList {
            add("${base}_${term.id}")
            if (term.semester in 1..2) add("${base}_${term.year}_${if (term.semester == 1) 3 else 12}")
        }
        return keys.firstNotNullOfOrNull { key ->
            runCatching { preferences.getString(key, null) }.getOrNull()
                ?.takeIf { ScheduleJson.parse(it) != null }
        }
    }

    fun selected(account: String, school: String, nextSemester: Boolean): CachedSchedule? {
        val current = currentTerm(account, school)
        val term = if (nextSemester) runCatching { current.next() }.getOrNull() ?: return null else current
        return read(account, school, term)?.let { CachedSchedule(current, term, it, true) }
    }

    suspend fun load(
        account: String,
        school: String,
        nextSemester: Boolean,
        forceRefresh: Boolean,
        reader: () -> AcademicStudyAdapter
    ): CachedSchedule {
        if (!forceRefresh) selected(account, school, nextSemester)?.let { return it }
        val remote = reader()
        val catalog = remote.catalog()
        val current = catalog.currentTerm
        val next = if (nextSemester) current.next() else current
        val term = catalog.terms.firstOrNull { it.id == next.id } ?: next
        // Existing installations may have data but no persisted current-term metadata yet.
        if (!forceRefresh) read(account, school, term)?.let { return CachedSchedule(current, term, it, true) }
        return CachedSchedule(current, term, AcademicStudyBridge.scheduleJson(remote.schedule(term)), false, remote.calendar(term))
    }

    fun save(account: String, school: String, schedule: CachedSchedule) {
        require(ScheduleJson.parse(schedule.json) != null) { "Invalid timetable must not replace the cache" }
        val base = prefix(account, school)
        preferences.edit()
            .putString("${base}_${schedule.term.id}", schedule.json)
            .putLong("${base}_${schedule.term.id}_time", System.currentTimeMillis())
            .putString("${base}_current", JSONObject().put("term", schedule.currentTerm.id)
                .put("termMetadata", schedule.currentTerm.toJson())
                .put("calendar", calendarTerm().id).toString())
            .apply()
    }
}

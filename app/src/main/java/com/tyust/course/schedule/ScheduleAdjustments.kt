package com.tyust.course.schedule

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** A blank teaching date cancels this day. Mappings always read the imported timetable. */
data class ScheduleDayOverride(val date: String, val teachingDate: String = "", val note: String = "")
data class ScheduleLessonOverride(val courseId: String, val date: String, val cancelled: Boolean = false,
    val targetDate: String = date, val startPeriod: Int? = null, val endPeriod: Int? = null, val location: String? = null)
data class ScheduleAdjustments(val days: List<ScheduleDayOverride> = emptyList(),
    val lessons: List<ScheduleLessonOverride> = emptyList()) {
    val isEmpty get() = days.isEmpty() && lessons.isEmpty()
    fun withDay(value: ScheduleDayOverride) = copy(days = days.filterNot { it.date == value.date } + value)
    fun withLesson(value: ScheduleLessonOverride) = copy(lessons = lessons.filterNot { it.courseId == value.courseId && it.date == value.date } + value)
    fun unresolved(courses: List<ScheduleCourseRecord>, base: ScheduleTimeBase? = null): List<ScheduleLessonOverride> {
        if (lessons.isEmpty()) return emptyList()
        val keys = base?.let {
            (ScheduleOccurrenceResolver.resolve(courses, it.copy(adjustments = ScheduleAdjustments()), requireEnd = false) +
                ScheduleOccurrenceResolver.resolve(courses, it.copy(adjustments = copy(lessons = emptyList())), requireEnd = false))
                .map { occurrence -> occurrence.course.id to occurrence.originalDate }.toSet()
        }
        return lessons.filter { rule -> courses.none { it.id == rule.courseId } || keys != null && (rule.courseId to rule.date) !in keys }
    }
}

object ScheduleAdjustmentJson {
    fun encode(value: ScheduleAdjustments) = JSONObject().put("days", JSONArray().apply {
        value.days.forEach { put(JSONObject().put("date", it.date).put("teach", it.teachingDate).put("note", it.note)) }
    }).put("lessons", JSONArray().apply {
        value.lessons.forEach { put(JSONObject().put("course", it.courseId).put("date", it.date).put("cancelled", it.cancelled)
            .put("target", it.targetDate).put("start", it.startPeriod).put("end", it.endPeriod).put("location", it.location)) }
    })
    fun decode(value: JSONObject?): ScheduleAdjustments {
        fun rows(name: String): List<JSONObject> = value?.optJSONArray(name)?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }.orEmpty()
        return ScheduleAdjustments(rows("days").mapNotNull { row -> runCatching {
            ScheduleDayOverride(row.getString("date"), row.optString("teach"), row.optString("note"))
                .takeIf { ScheduleOccurrenceResolver.date(it.date) != null && (it.teachingDate.isBlank() || ScheduleOccurrenceResolver.date(it.teachingDate) != null) }
        }.getOrNull() }.distinctBy { it.date }, rows("lessons").mapNotNull { row -> runCatching {
            ScheduleLessonOverride(row.getString("course"), row.getString("date"), row.optBoolean("cancelled"),
                row.optString("target", row.getString("date")), row.optInt("start").takeIf { it > 0 },
                row.optInt("end").takeIf { it > 0 }, if (row.has("location") && !row.isNull("location")) row.getString("location") else null)
                .takeIf { it.courseId.isNotBlank() && ScheduleOccurrenceResolver.date(it.date) != null && ScheduleOccurrenceResolver.date(it.targetDate) != null }
        }.getOrNull() }.distinctBy { it.courseId to it.date })
    }
}

/** The only expansion of teaching weeks into civil-date occurrences, shared by every consumer. */
object ScheduleOccurrenceResolver {
    fun date(value: String, zone: TimeZone = TimeZone.getDefault()): Calendar? = runCatching {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value))
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone; isLenient = false }
        Calendar.getInstance(zone).apply { time = requireNotNull(format.parse(value)) }
    }.getOrNull()

    private fun at(day: String, clock: String?, zone: TimeZone): Long? {
        if (clock == null || !Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(clock)) return null
        return date(day, zone)?.apply { set(Calendar.HOUR_OF_DAY, clock.substring(0, 2).toInt()); set(Calendar.MINUTE, clock.substring(3).toInt()) }?.timeInMillis
    }

    fun resolve(courses: List<ScheduleCourseRecord>, base: ScheduleTimeBase?, zone: TimeZone = TimeZone.getDefault(), requireEnd: Boolean = true): List<ScheduleOccurrence> {
        if (base == null || ScheduleDates.firstMonday(base.firstWeekDate, zone) == null) return emptyList()
        fun occurrence(course: ScheduleCourseRecord, day: String, origin: String = day): ScheduleOccurrence? {
            if (course.day !in 1..7 || course.startPeriod < 1 || course.endPeriod < course.startPeriod) return null
            val start = at(day, base.periodStarts[course.startPeriod], zone) ?: return null
            val end = at(day, base.periodEnds[course.endPeriod], zone) ?: if (!requireEnd) start + 1 else return null
            if (end <= start) return null
            return ScheduleOccurrence(course, start, end, origin)
        }
        val imported = courses.distinctBy { it.id }.flatMap { course ->
            val weeks = ScheduleWeeks.parse(course.weeks)
            if (!weeks.valid) emptyList() else weeks.weeks.mapNotNull { week ->
                val day = ScheduleDates.date(base.firstWeekDate, week, course.day, zone) ?: return@mapNotNull null
                occurrence(course, ScheduleTimeBase.dateFromMillis(day.timeInMillis, zone))
            }
        }
        val byDate = imported.groupBy { it.originalDate }
        val dayRules = base.adjustments.days.associateBy { it.date }
        val days = imported.filterNot { it.originalDate in dayRules } + dayRules.values.flatMap { rule ->
            byDate[rule.teachingDate].orEmpty().mapNotNull { occurrence(it.course, rule.date) }
        }
        val lessons = base.adjustments.lessons.associateBy { it.courseId to it.date }
        val dayKeys = days.map { it.course.id to it.originalDate }.toSet()
        // An explicit make-up lesson remains valid when its original day was cancelled or remapped.
        val individuallyRestored = imported.filter { item ->
            val key = item.course.id to item.originalDate
            key !in dayKeys && lessons[key]?.cancelled == false
        }
        return (days + individuallyRestored).mapNotNull { item ->
            val rule = lessons[item.course.id to item.originalDate] ?: return@mapNotNull item
            if (rule.cancelled) return@mapNotNull null
            occurrence(item.course.copy(startPeriod = rule.startPeriod ?: item.course.startPeriod,
                endPeriod = rule.endPeriod ?: item.course.endPeriod, location = rule.location ?: item.course.location), rule.targetDate, item.originalDate)
        }.distinctBy { Triple(it.course.id, it.originalDate, it.startsAt) }
            .sortedWith(compareBy<ScheduleOccurrence> { it.startsAt }.thenBy { it.course.id })
    }
}

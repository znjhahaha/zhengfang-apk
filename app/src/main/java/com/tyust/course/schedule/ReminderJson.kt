package com.tyust.course.schedule

import org.json.JSONArray
import org.json.JSONObject

internal object ReminderJson {
    fun course(value: ScheduleCourseRecord) = JSONObject().apply {
        put("id", value.id); put("name", value.name); put("teacher", value.teacher); put("location", value.location)
        put("day", value.day); put("start", value.startPeriod); put("end", value.endPeriod)
        put("weeks", value.weeks); put("custom", value.custom)
        if (value.details.isNotEmpty()) put("details", ScheduleDetails.json(value.details))
    }
    fun course(value: JSONObject) = ScheduleCourseRecord(value.getString("id"), value.getString("name"),
        value.optString("teacher"), value.optString("location"), value.getInt("day"), value.getInt("start"),
        value.getInt("end"), value.getString("weeks"), value.optBoolean("custom"), ScheduleDetails.parse(value.optJSONArray("details"), ScheduleWeeks.parse(value.getString("weeks")).weeks))

    fun reminder(value: CourseReminder) = JSONObject().apply {
        put("account", value.key.account); put("term", value.key.term); put("course", course(value.course))
        put("enabled", value.enabled); put("lead", value.leadMinutes); put("revision", value.revision); put("customLead", value.customLead)
    }
    fun reminder(value: JSONObject): CourseReminder {
        val course = course(value.getJSONObject("course"))
        return CourseReminder(CourseReminderKey(value.getString("account"), value.getString("term"), course.id), course,
            value.optBoolean("enabled"), value.optInt("lead", 15), value.optLong("revision", 1),
            value.optBoolean("customLead", value.optInt("lead", 15) != 15))
    }
    fun encode(values: List<CourseReminder>): String = JSONArray().apply { values.forEach { put(reminder(it)) } }.toString()
    fun decode(value: String?): List<CourseReminder> = runCatching {
        val list = JSONArray(value ?: "[]")
        (0 until list.length()).mapNotNull { runCatching { reminder(list.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    fun timeBase(value: ScheduleTimeBase) = JSONObject().apply {
        put("date", value.firstWeekDate)
        put("adjustments", ScheduleAdjustmentJson.encode(value.adjustments))
        put("starts", JSONObject().apply { value.periodStarts.forEach { (period, time) -> put(period.toString(), time) } })
        put("ends", JSONObject().apply { value.periodEnds.forEach { (period, time) -> put(period.toString(), time) } })
    }
    fun timeBase(value: JSONObject): ScheduleTimeBase {
        val starts = value.optJSONObject("starts") ?: JSONObject()
        val ends = value.optJSONObject("ends") ?: JSONObject()
        return ScheduleTimeBase(value.optString("date"), starts.keys().asSequence().mapNotNull { key ->
            key.toIntOrNull()?.let { it to starts.optString(key) }
        }.toMap(), ends.keys().asSequence().mapNotNull { key -> key.toIntOrNull()?.let { it to ends.optString(key) } }.toMap(),
            ScheduleAdjustmentJson.decode(value.optJSONObject("adjustments")))
    }
}

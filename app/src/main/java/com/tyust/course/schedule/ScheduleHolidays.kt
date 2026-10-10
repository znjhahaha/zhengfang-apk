package com.tyust.course.schedule

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ScheduleHoliday(val date: String, val name: String, val off: Boolean)
data class ScheduleHolidayCalendar(val year: Int, val source: String, val days: List<ScheduleHoliday>, val cached: Boolean)

/** holiday-cn is a calendar reference, never a source of university teaching-day mappings. */
object ScheduleHolidays {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    fun load(context: Context, year: Int): ScheduleHolidayCalendar {
        require(year in 2000..2100)
        val prefs = context.getSharedPreferences("schedule_holidays", Context.MODE_PRIVATE)
        fun parse(raw: String, cached: Boolean): ScheduleHolidayCalendar {
            val json = JSONObject(raw)
            val array = json.getJSONArray("days")
            val days = (0 until array.length()).map { array.getJSONObject(it) }.map {
                ScheduleHoliday(it.getString("date"), it.getString("name"), it.getBoolean("isOffDay"))
            }.filter { ScheduleOccurrenceResolver.date(it.date) != null && it.date.startsWith(year.toString()) }.sortedBy { it.date }
            val sources = json.optJSONArray("papers")
            val source = sources?.optString(0).orEmpty().takeIf { it.startsWith("https://www.gov.cn/") }
                ?: "https://github.com/NateScarlet/holiday-cn"
            return ScheduleHolidayCalendar(year, source, days, cached)
        }
        val saved = prefs.getString(year.toString(), null)
        if (saved != null && System.currentTimeMillis() - prefs.getLong("time:$year", 0) < TimeUnit.DAYS.toMillis(7)) return parse(saved, true)
        return try {
            client.newCall(Request.Builder().url("https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/$year.json").build()).execute().use { response ->
                check(response.isSuccessful) { "该年度节假日数据暂不可用（${response.code}）" }
                val raw = response.body?.string().orEmpty()
                check(raw.length <= 262144) { "节假日数据过大" }
                val result = parse(raw, false)
                prefs.edit().putString(year.toString(), raw).putLong("time:$year", System.currentTimeMillis()).apply()
                result
            }
        } catch (e: Exception) { if (saved != null) parse(saved, true) else throw e }
    }
}

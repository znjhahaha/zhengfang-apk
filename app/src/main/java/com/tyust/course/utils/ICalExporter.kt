package com.tyust.course.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.tyust.course.ui.screen.ScheduleCourseUi
import com.tyust.course.schedule.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * iCal (.ics) 课表导出工具
 * 将课表数据转换为标准 iCalendar 格式，可导入到系统日历
 */
object ICalExporter {
    
    // 节次对应的时间（根据学校作息时间调整）
    private val defaultPeriodTimes = mapOf(
        1 to Pair("08:00", "08:45"),
        2 to Pair("08:55", "09:40"),
        3 to Pair("10:00", "10:45"),
        4 to Pair("10:55", "11:40"),
        5 to Pair("14:00", "14:45"),
        6 to Pair("14:55", "15:40"),
        7 to Pair("16:00", "16:45"),
        8 to Pair("16:55", "17:40"),
        9 to Pair("19:00", "19:45"),
        10 to Pair("19:55", "20:40"),
        11 to Pair("20:50", "21:35"),
        12 to Pair("21:45", "22:30")
    )
    
    /**
     * 生成 iCal 文件内容
     * @param courses 课程列表
     * @param semesterStartDate 学期开始日期（周一）
     * @param totalWeeks 总周数
     * @return iCal 格式字符串
     */
    fun generateICalContent(
        courses: List<ScheduleCourseUi>,
        semesterStartDate: Calendar,
        totalWeeks: Int = 20,
        periodTimes: Map<Int, Pair<String, String>> = defaultPeriodTimes,
        adjustments: ScheduleAdjustments = ScheduleAdjustments()
    ): String {
        val sb = StringBuilder()
        
        // iCal 文件头
        sb.appendLine("BEGIN:VCALENDAR")
        sb.appendLine("VERSION:2.0")
        sb.appendLine("PRODID:-//Zhengfang Course Assistant//CN")
        sb.appendLine("CALSCALE:GREGORIAN")
        sb.appendLine("METHOD:PUBLISH")
        sb.appendLine("X-WR-CALNAME:我的课表")
        sb.appendLine("X-WR-TIMEZONE:Asia/Shanghai")
        
        // 时区定义
        sb.appendLine("BEGIN:VTIMEZONE")
        sb.appendLine("TZID:Asia/Shanghai")
        sb.appendLine("BEGIN:STANDARD")
        sb.appendLine("DTSTART:19700101T000000")
        sb.appendLine("TZOFFSETFROM:+0800")
        sb.appendLine("TZOFFSETTO:+0800")
        sb.appendLine("END:STANDARD")
        sb.appendLine("END:VTIMEZONE")
        
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { timeZone = semesterStartDate.timeZone }
        val now = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        
        val base = ScheduleTimeBase(ScheduleTimeBase.dateFromMillis(semesterStartDate.timeInMillis, semesterStartDate.timeZone),
            periodTimes.mapValues { it.value.first }, periodTimes.mapValues { it.value.second }, adjustments)
        val records = courses.map { it.record().copy(weeks = ScheduleWeeks.parse(it.weeks).weeks.filter { week -> week <= totalWeeks }.joinToString(",")) }
        val timestamp = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.ROOT).apply { timeZone = semesterStartDate.timeZone }
        ScheduleOccurrenceResolver.resolve(records, base, semesterStartDate.timeZone).forEach { item ->
            val course = item.course
            val identityDate = item.originalDate.replace("-", "")
            sb.appendLine("BEGIN:VEVENT")
            sb.appendLine("UID:course-" + ScheduleIdentity.digest(course.id) + "-" + identityDate + "@tyust.edu.cn")
            sb.appendLine("DTSTAMP:" + now)
            sb.appendLine("DTSTART;TZID=Asia/Shanghai:" + timestamp.format(Date(item.startsAt)))
            sb.appendLine("DTEND;TZID=Asia/Shanghai:" + timestamp.format(Date(item.endsAt)))
            sb.appendLine("SUMMARY:" + escapeText(course.name))
            if (course.location.isNotEmpty()) sb.appendLine("LOCATION:" + escapeText(course.location))
            val week = ScheduleDates.weekIndexAt(base.firstWeekDate, item.startsAt, semesterStartDate.timeZone)
            sb.appendLine("DESCRIPTION:" + escapeText("授课教师: " + course.teacher + "\n第" + week + "周"))
            sb.appendLine("END:VEVENT")
        }

        sb.appendLine("END:VCALENDAR")
        return sb.toString()
    }

    private fun escapeText(value: String): String = value.replace("\\", "\\\\")
        .replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
    
    /**
     * 解析周次字符串
     * 支持格式: "1-16周", "1,3,5,7周", "1-8,10-16周", "1-16周(单)" 等
     */
    private fun parseWeeks(weeksStr: String, totalWeeks: Int): List<Int> {
        return com.tyust.course.schedule.ScheduleWeeks.parse(weeksStr).weeks.filter { it <= totalWeeks }.sorted()
    }
    
    /**
     * 检查是否为连续周次
     */
    private fun isConsecutiveWeeks(weeks: List<Int>): Boolean {
        if (weeks.size <= 1) return true
        for (i in 1 until weeks.size) {
            if (weeks[i] - weeks[i - 1] != 1) return false
        }
        return true
    }
    
    /**
     * 导出并分享 iCal 文件
     */
    fun exportAndShare(
        context: Context,
        courses: List<ScheduleCourseUi>,
        semesterStartDate: Calendar,
        totalWeeks: Int = 20,
        periodTimes: Map<Int, Pair<String, String>> = defaultPeriodTimes,
        adjustments: ScheduleAdjustments = ScheduleAdjustments()
    ) {
        try {
            val icsContent = generateICalContent(courses, semesterStartDate, totalWeeks, periodTimes, adjustments)
            
            // 保存到临时文件
            val fileName = "课表_${SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())}.ics"
            val file = File(context.cacheDir, fileName)
            FileOutputStream(file).use { it.write(icsContent.toByteArray()) }
            
            // 使用 FileProvider 获取 URI
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            
            // 创建分享 Intent
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/calendar"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(Intent.createChooser(shareIntent, "导出课表到..."))
            
        } catch (e: Exception) {
            android.util.Log.e("ICalExporter", "导出失败: ${e.message}")
            throw e
        }
    }
}

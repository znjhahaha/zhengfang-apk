package com.tyust.course.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.tyust.course.ui.screen.ScheduleCourseUi
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
    private val periodTimes = mapOf(
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
        totalWeeks: Int = 20
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
        
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HHmmss", Locale.getDefault())
        val now = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        
        var eventIndex = 0
        courses.forEach { course ->
            // 解析周次
            val weekList = parseWeeks(course.weeks, totalWeeks)
            if (weekList.isEmpty()) return@forEach
            
            // 获取上课时间
            val startTime = periodTimes[course.startPeriod]?.first ?: "08:00"
            val endTime = periodTimes[course.endPeriod]?.second ?: "09:40"
            val startTimeStr = startTime.replace(":", "") + "00"
            val endTimeStr = endTime.replace(":", "") + "00"
            
            // 为每个上课周生成单独的事件（最大兼容性）
            weekList.forEach { week ->
                val eventDate = semesterStartDate.clone() as Calendar
                // 移动到对应周
                eventDate.add(Calendar.DAY_OF_YEAR, (week - 1) * 7)
                // 移动到对应的周几 (course.day: 1=周一, 7=周日)
                // Calendar 默认周日是第一天，需要调整
                val currentDayOfWeek = eventDate.get(Calendar.DAY_OF_WEEK)
                // 周一在 Calendar 中是 2,  course.day 1 对应周一
                val targetDayOfWeek = if (course.day == 7) Calendar.SUNDAY else course.day + 1
                val dayOffset = targetDayOfWeek - currentDayOfWeek
                eventDate.add(Calendar.DAY_OF_YEAR, dayOffset)
                
                val eventDateStr = dateFormat.format(eventDate.time)
                
                // 生成 VEVENT
                sb.appendLine("BEGIN:VEVENT")
                sb.appendLine("UID:course-${eventIndex++}-${course.name.hashCode()}-w${week}@tyust.edu.cn")
                sb.appendLine("DTSTAMP:$now")
                sb.appendLine("DTSTART;TZID=Asia/Shanghai:${eventDateStr}T$startTimeStr")
                sb.appendLine("DTEND;TZID=Asia/Shanghai:${eventDateStr}T$endTimeStr")
                sb.appendLine("SUMMARY:${course.name}")
                if (course.location.isNotEmpty()) {
                    sb.appendLine("LOCATION:${course.location}")
                }
                val description = buildString {
                    if (course.teacher.isNotEmpty()) append("授课教师: ${course.teacher}")
                    append(if (isNotEmpty()) "\n" else "")
                    append("第${week}周")
                }
                sb.appendLine("DESCRIPTION:$description")
                sb.appendLine("END:VEVENT")
            }
        }
        
        sb.appendLine("END:VCALENDAR")
        return sb.toString()
    }
    
    /**
     * 解析周次字符串
     * 支持格式: "1-16周", "1,3,5,7周", "1-8,10-16周", "1-16周(单)" 等
     */
    private fun parseWeeks(weeksStr: String, totalWeeks: Int): List<Int> {
        val weeks = mutableListOf<Int>()
        val cleanStr = weeksStr.replace("周", "").replace("(", "").replace(")", "")
        
        val isSingleWeek = weeksStr.contains("单")
        val isDoubleWeek = weeksStr.contains("双")
        
        val rangeStr = cleanStr.replace("单", "").replace("双", "").trim()
        
        // 解析范围
        rangeStr.split(",").forEach { part ->
            if (part.contains("-")) {
                val (start, end) = part.split("-").map { it.trim().toIntOrNull() ?: 0 }
                for (w in start..end.coerceAtMost(totalWeeks)) {
                    weeks.add(w)
                }
            } else {
                part.trim().toIntOrNull()?.let { weeks.add(it) }
            }
        }
        
        // 过滤单/双周
        return when {
            isSingleWeek -> weeks.filter { it % 2 == 1 }
            isDoubleWeek -> weeks.filter { it % 2 == 0 }
            else -> weeks
        }.sorted()
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
        totalWeeks: Int = 20
    ) {
        try {
            val icsContent = generateICalContent(courses, semesterStartDate, totalWeeks)
            
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

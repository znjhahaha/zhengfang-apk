package com.tyust.course.schedule

import java.util.Calendar

/**
 * 用户选择的「每周第一天」。
 *
 * 学周本身以这一天开头：持久化的 `firstWeekDate` 始终落在这一天上，因此课表列序、周次换算
 * 与课程日期全部可以由这一个锚点推出来，不需要在各个消费方重复判断。
 */
enum class ScheduleWeekStart(val firstDayOfWeek: Int, val storageValue: String) {
    Monday(Calendar.MONDAY, "monday"),
    Sunday(Calendar.SUNDAY, "sunday");

    companion object {
        val Default = Monday

        fun decode(value: String?): ScheduleWeekStart = entries.firstOrNull { it.storageValue == value } ?: Default

        /** 由 [Calendar.DAY_OF_WEEK] 常量反查，用于从锚点日期还原用户当时的选择。 */
        fun of(firstDayOfWeek: Int): ScheduleWeekStart = entries.firstOrNull { it.firstDayOfWeek == firstDayOfWeek } ?: Default
    }
}

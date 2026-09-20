package com.tyust.course.ui

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.demo.DemoData
import com.tyust.course.manager.ScheduleSettingsManager
import com.tyust.course.manager.UserManager
import com.tyust.course.schedule.ScheduleDates
import com.tyust.course.schedule.ScheduleReminderScheduler
import com.tyust.course.schedule.ScheduleTimeBase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleCalendarFlowDeviceTest {
    @Test fun savingStartDateJumpsToTodayEvenWhenSettingsCloseWithSystemBack() {
        DemoUiDriver().use { ui ->
            val activity = requireNotNull(ui.main)
            val account = UserManager.getInstance().currentAccountStorageKey
            assertEquals(DemoData.ACCOUNT_KEY, UserManager.getInstance().currentAccountKey)
            val manager = ScheduleSettingsManager.getInstance().apply { init(activity) }
            val scheduler = ScheduleReminderScheduler.get(activity)
            val term = DemoData.currentTerm.id
            val nextTerm = DemoData.currentTerm.next().id
            val previousDate = manager.semesterStartDate
            val previousCalendar = scheduler.timeBase(account, term)
            val previousNextCalendar = scheduler.timeBase(account, nextTerm)
            val initial = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                add(Calendar.MONTH, -2)
                while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) add(Calendar.DAY_OF_MONTH, 1)
            }.let { ScheduleDates.mondayOfWeek(it.timeInMillis) }
            val selected = (initial.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 8) }
            val expected = ScheduleDates.mondayOfWeek(selected.timeInMillis)
            val expectedDate = ScheduleTimeBase.dateFromMillis(expected.timeInMillis)
            val expectedWeek = requireNotNull(ScheduleDates.weekAt(expectedDate, System.currentTimeMillis()))
            val nextCalendar = ScheduleTimeBase("2027-03-01")
            try {
                ui.onMain {
                    manager.semesterStartDate = initial.timeInMillis
                    scheduler.updateTimeBase(account, term, ScheduleTimeBase(ScheduleTimeBase.dateFromMillis(initial.timeInMillis)))
                    scheduler.updateTimeBase(account, nextTerm, nextCalendar)
                }
                ui.navigate("课表")
                ui.click("更多课表操作")
                ui.click("课表设置")
                ui.waitText("课表设置")
                ui.scrollTo("第一周开始日期")
                ui.click("第一周开始日期")
                var day = initial.get(Calendar.DAY_OF_MONTH)
                while (day < selected.get(Calendar.DAY_OF_MONTH)) {
                    day = minOf(day + 2, selected.get(Calendar.DAY_OF_MONTH))
                    ui.click("${day}日")
                }
                ui.click("确定")
                ui.waitText(SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(expected.time))
                assertEquals(expectedDate, scheduler.timeBase(account, term)?.firstWeekDate)
                assertEquals(nextCalendar, scheduler.timeBase(account, nextTerm))
                // Deliver real window back, not the settings screen's explicit close callback.
                ui.shell("input -d ${activity.display!!.displayId} keyevent 4")
                ui.waitText("课表设置", false)
                ui.waitText("第 $expectedWeek 周")
                ui.screenshot("calendar-saved-current-week")

                val browsedWeek = expectedWeek + 1
                ui.click("选择日期与学期")
                ui.click("选择第 $browsedWeek 周")
                ui.waitText("第 $browsedWeek 周")
                ui.onMain {
                    val base = requireNotNull(scheduler.timeBase(account, term))
                    scheduler.updateTimeBase(account, term, base.copy(periodStarts = mapOf(1 to "08:10")))
                }
                SystemClock.sleep(300)
                ui.waitText("第 $browsedWeek 周")
                ui.navigate("课程")
                ui.navigate("课表")
                ui.waitText("第 $browsedWeek 周")
                ui.click("更多课表操作")
                ui.click("课表设置")
                ui.click("完成")
                ui.waitText("课表设置", false)
                ui.waitText("第 $browsedWeek 周")
                ui.screenshot("calendar-browsed-week-preserved")
                ui.click("更多课表操作")
                ui.click("同步课表")
                ui.waitText("课表设置", false)
                ui.waitText("第 $browsedWeek 周")
            } finally {
                ui.onMain {
                    manager.semesterStartDate = previousDate
                    scheduler.updateTimeBase(account, term, previousCalendar ?: ScheduleTimeBase())
                    scheduler.updateTimeBase(account, nextTerm, previousNextCalendar ?: ScheduleTimeBase())
                }
            }
        }
    }
}

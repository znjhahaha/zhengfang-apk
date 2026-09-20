package com.tyust.course.ui

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.MainActivity
import com.tyust.course.demo.DemoData
import com.tyust.course.manager.*
import com.tyust.course.schedule.*
import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end interactions use the isolated preview package and local demo data. */
@RunWith(AndroidJUnit4::class)
class ScheduleAgendaFlowDeviceTest {
    @Test fun courseDetailKeepsItsRoundedSurfaceInBothThemes() {
        DemoUiDriver().use { ui ->
            val previous = AppearanceSettingsManager.themeMode
            try {
                ui.navigate("课表")
                chooseWeek(ui, 3)
                ui.click("星期三")
                for (theme in listOf(AppThemeMode.Light, AppThemeMode.Dark)) {
                    ui.onMain { AppearanceSettingsManager.updateThemeMode(theme) }
                    ui.waitText("数据结构")
                    ui.click("数据结构")
                    ui.waitText("课程详情")
                    ui.waitText("博学楼 A205")
                    ui.screenshot("verified-course-detail-${theme.name.lowercase()}")
                    ui.back()
                }
            } finally { ui.onMain { AppearanceSettingsManager.updateThemeMode(previous) } }
        }
    }

    @Test fun widgetPickerShowsThreeStylesAndCanReturnToTheSchedule() {
        DemoUiDriver().use { ui ->
            ui.navigate("课表")
            ui.click("更多课表操作")
            ui.click("桌面组件")
            ui.waitText("选择桌面组件")
            ui.waitText("简洁单课")
            ui.screenshot("widget-picker-single")
            ui.scrollTo("添加双课程")
            ui.screenshot("widget-picker-double")
            ui.scrollTo("添加今日时间轴")
            ui.screenshot("widget-picker-timeline")
            ui.back()
            ui.waitText("选择日期与学期")
        }
    }

    private fun chooseWeek(ui: DemoUiDriver, week: Int) {
        ui.click("选择日期与学期")
        ui.click("选择第 $week 周")
    }

    private fun swipe(ui: DemoUiDriver, left: Boolean) {
        val activity = requireNotNull(ui.main)
        val view = activity.window.decorView
        val from = if (left) view.width * 4 / 5 else view.width / 5
        val to = view.width - from
        ui.shell("input -d ${activity.display!!.displayId} swipe $from ${view.height / 2} $to ${view.height / 2} 350")
        SystemClock.sleep(450)
    }

    @Test fun datesViewsAndScrollSurviveThemeRecreationAnd125SecondsInBackground() {
        DemoUiDriver().use { ui ->
            val context = requireNotNull(ui.main).applicationContext
            val account = UserManager.getInstance().currentAccountStorageKey
            val term = DemoData.currentTerm.id
            val scheduler = ScheduleReminderScheduler.get(context)
            val previousCalendar = scheduler.timeBase(account, term)
            val previousTheme = AppearanceSettingsManager.themeMode
            val previousGlass = AppearanceSettingsManager.glassEffectEnabled
            val previousAnimator = ui.shell("settings get global animator_duration_scale").trim()
            val startup = StartupPagePreferences.from(context)
            val previousStartup = startup.read()
            val firstWeek = ScheduleDates.mondayOfWeek(System.currentTimeMillis()).apply { add(Calendar.DATE, -7) }
            try {
                ui.onMain {
                    AppearanceSettingsManager.updateThemeMode(AppThemeMode.Light)
                    AppearanceSettingsManager.updateGlassEffect(true)
                    startup.write(StartupPage.Schedule)
                    scheduler.updateTimeBase(account, term, ScheduleTimeBase(ScheduleTimeBase.dateFromMillis(firstWeek.timeInMillis)))
                }
                ui.navigate("课表")
                ui.waitSelected("日视图")
                ui.waitText("第 2 周")
                chooseWeek(ui, 3)
                ui.click("星期三")
                ui.waitText("数据结构")
                ui.waitText("当日 2 堂")
                ui.waitText("正在上课", false)
                ui.screenshot("light-glass-day-light")
                ui.click("数据结构")
                ui.waitText("课程详情")
                ui.waitText("博学楼 A205")
                ui.screenshot("light-glass-course-detail")
                ui.back()
                ui.longClick("数据结构")
                ui.waitText("复制教室")
                ui.screenshot("light-glass-course-actions")
                ui.click("复制教室")
                ui.onMain {
                    assertEquals("博学楼 A205", context.getSystemService(android.content.ClipboardManager::class.java)
                        .primaryClip!!.getItemAt(0).text.toString())
                }
                // Day swipes cross a week boundary, and swiping back returns to Sunday.
                ui.click("星期日")
                swipe(ui, true)
                ui.waitText("第 4 周")
                ui.waitSelected("星期一")
                swipe(ui, false)
                ui.waitText("第 3 周")
                ui.waitSelected("星期日")
                ui.click("星期三")
                ui.click("周视图")
                ui.waitSelected("周视图")
                ui.screenshot("light-glass-week-light")
                val floatingActivity = requireNotNull(ui.main)
                val floatingView = floatingActivity.window.decorView
                val floatingX = floatingView.width / 2
                ui.shell("input -d ${floatingActivity.display!!.displayId} swipe $floatingX ${floatingView.height * 3 / 4} $floatingX ${floatingView.height / 2} 450")
                SystemClock.sleep(350)
                ui.screenshot("light-glass-header-floating")
                swipe(ui, true)
                ui.waitText("第 4 周")
                swipe(ui, false)
                ui.waitText("第 3 周")
                ui.click("星期三")
                ui.waitSelected("日视图")
                ui.waitText("数据结构")
                ui.navigate("课程")
                ui.navigate("课表")
                ui.waitText("第 3 周")
                ui.waitSelected("星期三")
                ui.onMain { AppearanceSettingsManager.updateGlassEffect(false) }
                ui.waitText("数据结构")
                ui.screenshot("light-glass-disabled")
                ui.onMain {
                    AppearanceSettingsManager.updateGlassEffect(true)
                    AppearanceSettingsManager.updateThemeMode(AppThemeMode.Dark)
                }
                ui.waitText("第 3 周")
                ui.screenshot("light-glass-day-dark")
                ui.shell("settings put global animator_duration_scale 0")
                ui.click("周视图")
                ui.click("星期三")
                ui.waitSelected("日视图")
                ui.waitText("第 3 周")
                ui.screenshot("light-glass-reduced-motion")
                ui.click("周视图")
                ui.waitSelected("周视图")
                // A course can already be accessible near the viewport edge.
                // Always scroll before checking that its position is restored.
                val scrollingActivity = requireNotNull(ui.main)
                val scrollingView = scrollingActivity.window.decorView
                val scrollX = scrollingView.width / 2
                ui.shell("input -d ${scrollingActivity.display!!.displayId} swipe $scrollX ${scrollingView.height * 3 / 4} $scrollX ${scrollingView.height / 2} 450")
                ui.scrollTo("电影音乐")
                val displayStore = context.getSharedPreferences("schedule_display", 0)
                fun weekScroll() = displayStore.getString("position:$account|$term", null)
                    ?.let { org.json.JSONObject(it).optInt("weekScroll") } ?: 0
                ui.await("The week view did not scroll") { weekScroll() > 0 }
                val savedScroll = weekScroll()
                val savedCourseTop = ui.boundsOf("电影音乐").top
                ui.navigate("课程")
                ui.navigate("课表")
                ui.waitSelected("周视图")
                assertEquals(savedScroll, weekScroll())
                assertEquals(savedCourseTop.toFloat(), ui.boundsOf("电影音乐").top.toFloat(), 3f)
                val before = requireNotNull(ui.main)
                ui.onMain { before.recreate() }
                ui.await("Activity did not recreate") { ui.main !== before }
                ui.waitText("第 3 周")
                ui.waitSelected("星期三")
                ui.waitSelected("周视图")
                assertEquals(savedScroll, weekScroll())
                assertEquals(savedCourseTop.toFloat(), ui.boundsOf("电影音乐").top.toFloat(), 3f)
                // Secondary emulator displays need not have a launcher. Put a
                // different application in front and verify this Activity stops.
                val backgroundDisplay = requireNotNull(ui.main).display!!.displayId
                val backgroundTask = requireNotNull(ui.main).taskId
                ui.shell("am start --display $backgroundDisplay -f 0x18000000 -a android.settings.SETTINGS")
                ui.await("Activity did not enter the background") {
                    requireNotNull(ui.main).lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED
                }
                val backgroundStarted = SystemClock.elapsedRealtime()
                SystemClock.sleep(125_000)
                // Resume the existing task as Recents does. A bare NEW_TASK Intent
                // can create another MainActivity and is a cold start on API 32.
                context.getSystemService(android.app.ActivityManager::class.java).appTasks
                    .first { it.taskInfo?.taskId == backgroundTask }.moveToFront()
                ui.waitText("第 3 周")
                ui.waitSelected("星期三")
                ui.waitSelected("周视图")
                assertTrue(SystemClock.elapsedRealtime() - backgroundStarted >= 125_000)
                assertEquals(savedScroll, weekScroll())
                assertEquals(savedCourseTop.toFloat(), ui.boundsOf("电影音乐").top.toFloat(), 3f)
                android.util.Log.i("ScheduleAgendaFlow", "Restored after ${SystemClock.elapsedRealtime() - backgroundStarted}ms; weekScroll=$savedScroll")
                ui.screenshot("light-glass-background-restored")
                ui.click("回到今天")
                ui.waitText("第 2 周")
                ui.waitSelected("星期${"一二三四五六日"[ScheduleDates.dayAt(System.currentTimeMillis()) - 1]}")
                chooseWeek(ui, 4)
                ui.click("周视图")
                val old = requireNotNull(ui.main)
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                ui.await("Fresh task did not start") { ui.main !== old }
                ui.waitSelected("课表")
                ui.waitSelected("日视图")
                ui.waitText("第 2 周")
                ui.waitText("回到今天", false)
                ui.screenshot("light-glass-fresh-launch-today")
            } finally {
                ui.shell(if (previousAnimator == "null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $previousAnimator")
                ui.onMain {
                    scheduler.updateTimeBase(account, term, previousCalendar ?: ScheduleTimeBase())
                    AppearanceSettingsManager.updateThemeMode(previousTheme)
                    AppearanceSettingsManager.updateGlassEffect(previousGlass)
                    startup.write(previousStartup)
                }
            }
        }
    }

    @Test fun semesterWidgetTargetsReminderEditingAndDeleteUndoUseTheSelectedCourse() {
        DemoUiDriver().use { ui ->
            val context = requireNotNull(ui.main).applicationContext
            val user = UserManager.getInstance()
            val account = user.currentAccountStorageKey
            val school = user.currentSchool!!.id
            val term = DemoData.currentTerm.id
            val nextTerm = DemoData.currentTerm.next().id
            val scheduler = ScheduleReminderScheduler.get(context)
            val manager = ScheduleSettingsManager.getInstance()
            val previous = scheduler.timeBase(account, term)
            val previousNext = scheduler.timeBase(account, nextTerm)
            val firstWeek = ScheduleDates.mondayOfWeek(System.currentTimeMillis()).apply { add(Calendar.DATE, -7) }
            val nextStart = (firstWeek.clone() as Calendar).apply { add(Calendar.DATE, 140) }
            val custom = ScheduleSettingsManager.CustomCourse("light-glass-fixture", "测试自习", "图书馆 302", "", 3, 1, 2, "1-16周")
            try {
                ui.onMain {
                    scheduler.updateTimeBase(account, term, ScheduleTimeBase(ScheduleTimeBase.dateFromMillis(firstWeek.timeInMillis)))
                    scheduler.updateTimeBase(account, nextTerm, ScheduleTimeBase(ScheduleTimeBase.dateFromMillis(nextStart.timeInMillis)))
                    manager.addCustomCourse(custom, account)
                }
                ui.navigate("课表")
                ui.click("选择日期与学期")
                ui.click("下学期")
                ui.click("选择第 2 周")
                ui.waitText("下学期 · 第 2 周")
                ui.click("回到今天")
                ui.waitText("第 2 周")
                chooseWeek(ui, 3)
                ui.click("星期三")
                ui.longClick("测试自习")
                ui.click("编辑课程")
                ui.waitText("课程名称")
                ui.back()
                ui.longClick("测试自习")
                ui.click("开启提醒")
                ui.waitText("课程提醒")
                assertTrue(scheduler.find(CourseReminderKey(account, term, "custom:${custom.id}"))?.enabled == true)
                ui.back()
                ui.longClick("测试自习")
                ui.click("关闭提醒")
                ui.click("测试自习")
                ui.click("更多课程操作")
                ui.click("删除课程")
                ui.waitText("已删除课程")
                ui.click("撤销")
                ui.waitText("测试自习")
                assertTrue(manager.getCustomCourses(account).any { it.id == custom.id })
                // Exercise the same PendingIntent targets as the launcher, including a future occurrence.
                val course = DemoData.scheduleCourses().first { it.day == 4 }
                val startsAt = requireNotNull(ScheduleDates.date(ScheduleTimeBase.dateFromMillis(firstWeek.timeInMillis), 4, 4))
                    .apply { set(Calendar.HOUR_OF_DAY, 8) }.timeInMillis
                context.startActivity(ScheduleWidgetNavigation.intent(context, account, school, term, course.id,
                    ScheduleWidgetAction.Course, startsAt).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ui.waitText("课程详情")
                ui.waitText(course.name)
                ui.back()
                ui.waitText("第 4 周")
                ui.waitSelected("星期四")
                context.startActivity(ScheduleWidgetNavigation.intent(context, account, school, term,
                    action = ScheduleWidgetAction.Calendar).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ui.waitText("课表设置")
                ui.click("完成")
                ui.waitText("第 2 周")
                chooseWeek(ui, 3)
                context.startActivity(ScheduleWidgetNavigation.intent(context, "different-account", school, term, course.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ui.waitText("这张课表属于其他账号，请刷新桌面组件")
                ui.waitText("第 3 周")
                ui.waitText("课程详情", false)
                context.startActivity(ScheduleWidgetNavigation.intent(context, account, school, term).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ui.waitText("第 2 周")
                ui.waitSelected("日视图")
                ui.click("更多课表操作")
                ui.click("添加课程")
                ui.waitText("课程名称")
                ui.back()
            } finally {
                ui.onMain {
                    manager.removeCustomCourse(custom.id, account)
                    scheduler.removeCourse(account, "custom:${custom.id}")
                    scheduler.updateTimeBase(account, term, previous ?: ScheduleTimeBase())
                    scheduler.updateTimeBase(account, nextTerm, previousNext ?: ScheduleTimeBase())
                }
            }
        }
    }
}

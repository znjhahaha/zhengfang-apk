package com.tyust.course.ui

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyust.course.BuildConfig
import com.tyust.course.manager.StartupPage
import com.tyust.course.academic.plugin.PluginPages
import com.tyust.course.academic.plugin.PluginPageRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real main activity and settings route in the separate, offline demo package. */
@RunWith(AndroidJUnit4::class)
class StartupPageDeviceTest {
    private fun withPreferences(block: (PluginPageRegistry) -> Unit) {
        assumeTrue("Use the isolated demo variant", BuildConfig.UI_PREVIEW)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val raw = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val wasSet = raw.contains("startup_page")
        val previous = raw.getString("startup_page", null)
        val pages = context.getSharedPreferences("plugin-pages-v3", Context.MODE_PRIVATE)
        val previousPages = pages.getString("state", "{}")
        fun initialize() = InstrumentationRegistry.getInstrumentation().runOnMainSync { PluginPages.initialize(context) }
        raw.edit().remove("startup_page").commit()
        pages.edit().putString("state", "{}").commit()
        initialize()
        try { block(PluginPages.registry) } finally {
            if (wasSet) raw.edit().putString("startup_page", previous).commit() else raw.edit().remove("startup_page").commit()
            pages.edit().putString("state", previousPages).commit()
            PluginPages.requested.value = null
            initialize()
        }
    }

    @Test fun settingsSelectionPersistsWithoutNavigatingAwayAndNextLaunchOpensSchedule() = withPreferences { preferences ->
        preferences.setStartup("app.courses")
        DemoUiDriver().use { ui ->
            ui.navigate("设置")
            scrollToStartupPage(ui)
            ui.click("启动首屏")
            ui.waitText("首屏页面")
            ui.click("课程")
            ui.waitText("课表")
            ui.screenshot("startup-picker-expanded")
            ui.click("课表")
            ui.await("The selected start page was not saved") { preferences.startup() == PluginPageRegistry.SCHEDULE }
            ui.screenshot("startup-picker-schedule")
            ui.click("完成")
            ui.waitText("课表 · 下次启动时显示")
            // The collapsed navigation capsule has no selected tab semantics.
            // Assert the visible settings content without clicking a tab again.
            ui.waitText("检查更新")
            ui.screenshot("startup-settings-schedule")
        }
        DemoUiDriver().use { ui ->
            ui.waitSelected("课表")
            ui.screenshot("startup-schedule")
        }
    }

    @Test fun activityRecreationKeepsTheCurrentPageAndEveryPageCanBeAStartupDestination() = withPreferences { preferences ->
        for (page in StartupPage.entries) {
            preferences.setStartup("app.${page.route}")
            DemoUiDriver().use { ui -> ui.waitSelected(page.label) }
        }
        preferences.setStartup(PluginPageRegistry.SCHEDULE)
        DemoUiDriver().use { ui ->
            ui.waitSelected("课表")
            ui.navigate("成绩")
            val original = requireNotNull(ui.main)
            ui.onMain { original.recreate() }
            ui.await("The recreated activity did not resume") { ui.main !== original && ui.foreground === ui.main }
            ui.waitSelected("成绩")
            assertEquals(PluginPageRegistry.SCHEDULE, preferences.startup())
        }
    }

    @Test fun unknownStartupPreferenceFallsBackToSchedule() = withPreferences {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putString("startup_page", "removed-page").commit()
        context.getSharedPreferences("plugin-pages-v3", Context.MODE_PRIVATE).edit().putString("state", "{}").commit()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { PluginPages.initialize(context) }
        DemoUiDriver().use { ui -> ui.waitSelected("课表") }
    }

    @Test fun legacyScheduleOverridesOldServiceStartupAndSettingsShowsTheSameChoice() = withPreferences {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().putString("startup_page", "schedule").commit()
        context.getSharedPreferences("plugin-pages-v3", Context.MODE_PRIVATE).edit()
            .putString("state", "{\"startup\":\"app.services\"}").commit()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { PluginPages.initialize(context) }
        DemoUiDriver().use { ui ->
            ui.waitSelected("课表")
            ui.navigate("设置")
            scrollToStartupPage(ui)
            ui.waitText("课表 · 下次启动时显示")
        }
    }

    @Test fun consumedServiceLaunchDoesNotOverrideRecreationOrNextColdStart() = withPreferences { registry ->
        registry.setStartup(PluginPageRegistry.SCHEDULE)
        DemoUiDriver().use { ui ->
            ui.waitSelected("课表")
            ui.onMain { PluginPages.open(requireNotNull(ui.main), PluginPageRegistry.SERVICES) }
            ui.waitText("主导航")
            ui.await("Page request was not consumed") { PluginPages.requested.value == null }
            assertFalse(requireNotNull(ui.main).intent.hasExtra("pageId"))
            ui.navigate("成绩")
            val original = requireNotNull(ui.main)
            ui.onMain { original.recreate() }
            ui.await("Activity did not recreate") { ui.main !== original && ui.foreground === ui.main }
            ui.waitSelected("成绩")
            val taskId = requireNotNull(ui.main).taskId
            ui.shell("input keyevent 3")
            ui.await("Activity did not move to the background") {
                ui.main?.lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == false
            }
            SystemClock.sleep(500)
            ui.onMain { requireNotNull(ui.main).getSystemService(android.app.ActivityManager::class.java)
                .appTasks.first { it.taskInfo?.taskId == taskId }.moveToFront() }
            ui.await("Background task did not resume") { ui.main?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED }
            ui.waitSelected("成绩")
        }
        DemoUiDriver().use { ui -> ui.waitSelected("课表") }
    }

    @Test fun serviceCenterAndSettingsUseTheSameStartupSelection() = withPreferences {
        DemoUiDriver().use { ui ->
            ui.onMain { PluginPages.open(requireNotNull(ui.main), PluginPageRegistry.SERVICES) }
            ui.waitText("主导航")
            scrollFullyIntoView(ui, "管理服务中心")
            ui.click("管理服务中心")
            ui.click("设为启动页")
            ui.await("Service startup was not saved") { PluginPages.registry.startup() == PluginPageRegistry.SERVICES }
            ui.navigate("设置")
            scrollToStartupPage(ui)
            ui.waitText("服务中心 · 下次启动时显示")
            ui.click("启动首屏")
            ui.click("服务中心")
            ui.click("课表")
            ui.click("完成")
            ui.waitText("课表 · 下次启动时显示")
        }
        DemoUiDriver().use { ui -> ui.waitSelected("课表") }
    }

    private fun scrollToStartupPage(ui: DemoUiDriver) {
        scrollFullyIntoView(ui, "启动首屏")
    }

    private fun scrollFullyIntoView(ui: DemoUiDriver, text: String) {
        repeat(6) {
            val activity = requireNotNull(ui.foreground)
            val width = activity.window.decorView.width
            val height = activity.window.decorView.height
            if (ui.hasText(text) && ui.boundsOf(text).bottom < height * 3 / 4) return
            ui.shell("input -d ${activity.display!!.displayId} swipe ${width / 2} ${height * 3 / 4} ${width / 2} ${height / 3} 300")
            SystemClock.sleep(350)
        }
        ui.waitText(text)
    }
}

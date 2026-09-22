package com.tyust.course.academic.plugin

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CampusServiceUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun loginReorderHideResetQueryAndConfirmAction() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pkg = runBlocking { AcademicProviderRegistry.packages().install(campusFixtureBytes("test.service.ui"), true) }
        AcademicProviderRegistry.reload()
        val settings = com.tyust.course.manager.AppearanceSettingsManager
        val theme = settings.themeMode
        try {
            ActivityScenario.launch<ServicePluginActivity>(Intent(context, ServicePluginActivity::class.java).putExtra("pluginId", pkg.manifest.id).putExtra("preview", true)).use { scenario ->
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("service-username").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("service-username").performTextInput("demo")
                compose.onNodeWithTag("service-password").performTextInput("demo")
                compose.onNodeWithTag("service-login").performScrollTo().performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("service-block-profile").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("service-block-profile").performScrollTo().assertIsDisplayed()
                capture(context, "service-overview")
                compose.onNodeWithText("调整排布").performScrollTo().performClick()
                compose.onNodeWithTag("service-visible-profile").performScrollTo().performClick()
                compose.onNodeWithTag("service-block-profile").assertDoesNotExist()
                compose.onNodeWithText("恢复插件默认布局").performScrollTo().performClick()
                compose.onNodeWithTag("service-block-profile").assertExists()
                compose.onNodeWithText("完成排布").performScrollTo().performClick()
                compose.onNodeWithText("报名演示活动").performScrollTo().performClick()
                compose.onNodeWithText("确认操作").assertIsDisplayed()
                compose.onNodeWithText("取消").performClick()
                compose.onNodeWithText("报名演示活动").performScrollTo().performClick()
                compose.onNodeWithText("确认操作").performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithText("演示报名已完成").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("查看报名记录").performScrollTo().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("已报名").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("返回成长记录").performScrollTo().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("service-field-keyword").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("service-field-keyword").performScrollTo().performTextInput("不存在的活动")
                compose.onNodeWithTag("service-form-submit").performScrollTo().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("暂无记录").fetchSemanticsNodes().isNotEmpty() }
                scenario.onActivity { settings.updateThemeMode(com.tyust.course.manager.AppThemeMode.Dark) }
                compose.waitForIdle(); capture(context, "service-records-dark")
            }
        } finally { settings.updateThemeMode(theme); AcademicProviderRegistry.packages().deactivate(pkg.manifest.id); AcademicProviderRegistry.reload() }
    }
    private fun capture(context: android.content.Context, name: String) {
        val file = File(context.getExternalFilesDir(null), "plugin-ui/$name.png"); file.parentFile!!.mkdirs()
        if (android.os.Build.VERSION.SDK_INT < 26) {
            check(androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file))
            return
        }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}

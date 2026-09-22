package com.tyust.course.academic.plugin

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class NativePluginUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun componentPageSupportsInputConditionalLayoutPersistenceAndNavigation() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val pkg = runBlocking { AcademicProviderRegistry.packages().install(nativeFixtureBytes("native-components", "test.native.ui" + UUID.randomUUID().toString().replace("-", "")), true) }
        AcademicProviderRegistry.reload()
        try {
            ActivityScenario.launch<NativePluginActivity>(Intent(app, NativePluginActivity::class.java).putExtra("pluginId", pkg.manifest.id)).use {
                awaitText("可以开始组合页面")
                compose.onNodeWithTag("native-node-label").performScrollTo().performTextReplacement("设备验证页面")
                compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("native-node-heading") and hasText("设备验证页面")).fetchSemanticsNodes().isNotEmpty() }
                androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()).pressBack()
                compose.onNodeWithTag("native-node-increment").performScrollTo().performClick()
                awaitText("计数：1")
                compose.onNodeWithTag("native-node-save").performScrollTo().performClick(); awaitText("偏好已保存")
                compose.onNodeWithTag("native-node-details").performScrollTo()
                compose.onAllNodes(isToggleable()).onFirst().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-node-chart").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("native-node-summary").performScrollTo().performClick()
                awaitText("已恢复当前账号与版本的偏好")
                compose.onNodeWithText("设备验证页面").assertIsDisplayed()
                compose.onNodeWithText("计数：1").assertIsDisplayed()
                capture(app, "native-summary")
                compose.onNodeWithTag("native-node-back").performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithTag("native-node-load").fetchSemanticsNodes().isNotEmpty() }
                awaitText("已恢复当前账号与版本的偏好")
                compose.onNodeWithTag("native-node-load").performScrollTo().performClick(); awaitText("数据源返回 10 条记录")
                capture(app, "native-components")
            }
        } finally { AcademicProviderRegistry.packages().deactivate(pkg.manifest.id); AcademicProviderRegistry.reload() }
    }
    @Test fun hostFileWorkflowRunsFromPluginWithoutKotlinBusinessCode() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val pkg = runBlocking { AcademicProviderRegistry.packages().install(nativeFixtureBytes("native-capabilities", "test.native.files" + UUID.randomUUID().toString().replace("-", "")), true) }
        AcademicProviderRegistry.reload()
        try {
            ActivityScenario.launch<NativePluginActivity>(Intent(app, NativePluginActivity::class.java).putExtra("pluginId", pkg.manifest.id)).use {
                awaitText("选择一种宿主能力进行体验")
                compose.onNodeWithTag("native-node-create").performScrollTo().performClick(); awaitText("已选择文件，可读取或交给其他应用")
                compose.onNodeWithTag("native-node-read").performScrollTo().performClick(); awaitText("已读取 10 字节（返回 Base64）")
                compose.onNodeWithTag("native-node-remove").performScrollTo().performClick(); awaitText("还没有文件")
                compose.onNodeWithTag("native-node-read").assertIsNotEnabled()
                capture(app, "native-capabilities")
            }
        } finally { AcademicProviderRegistry.packages().deactivate(pkg.manifest.id); AcademicProviderRegistry.reload() }
    }
    private fun awaitText(text: String) = compose.waitUntil(20_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun capture(app: Context, name: String) {
        val file = File(app.getExternalFilesDir(null), "plugin-ui/$name.png"); file.parentFile!!.mkdirs()
        if (android.os.Build.VERSION.SDK_INT < 26) {
            androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()).takeScreenshot(file)
            return
        }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}

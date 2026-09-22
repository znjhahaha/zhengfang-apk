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
import androidx.test.platform.app.InstrumentationRegistry
import com.tyust.course.academic.AcademicException
import com.tyust.course.academic.AcademicStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

internal fun nativeFixtureBytes(id: String): ByteArray {
    val assets = InstrumentationRegistry.getInstrumentation().context.assets
    val manifest = JSONObject(assets.open("academic-plugin/native-extension-manifest.json").bufferedReader().use { it.readText() }).put("id", id)
    val source = assets.open("academic-plugin/native-extension.js").bufferedReader().use { it.readText() }
    manifest.put("files", JSONObject().put("index.js", PluginJson.sha256(source.toByteArray())))
    return java.io.ByteArrayOutputStream().also { out -> java.util.zip.ZipOutputStream(out).use { zip ->
        for ((name, value) in mapOf("manifest.json" to manifest.toString(), "index.js" to source)) {
            zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
        }
    } }.toByteArray()
}

@RunWith(AndroidJUnit4::class)
class NativeServiceDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    @get:Rule val compose = createEmptyComposeRule()
    private fun installed(id: String) = runBlocking { AcademicProviderRegistry.packages().install(nativeFixtureBytes(id), true) }.also { AcademicProviderRegistry.reload() }
    private fun remove(pkg: PluginPackage) { AcademicProviderRegistry.packages().deactivate(pkg.manifest.id); AcademicProviderRegistry.reload() }
    private fun intent(pkg: PluginPackage) = Intent(app, ServicePluginActivity::class.java).putExtra("pluginId", pkg.manifest.id).putExtra("preview", true)
    private fun awaitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun capture(name: String, dialog: Boolean = false) {
        val file = File(app.getExternalFilesDir(null), "plugin-ui/$name.png"); file.parentFile!!.mkdirs()
        // Capture the tested window; MuMu's default display can still show its launcher.
        val root = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        val bitmap = root.captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    @Test fun nativeTicketsExpireOnSessionReplacementEpochChangeAndAccountRevocation() = runBlocking {
        val pkg = installed("test.native.scope")
        var active = true
        val runtime = ServicePluginSession(app, pkg, "native-scope") { active && AcademicProviderRegistry.isEnabled(pkg.manifest.id) }
        fun request(): Pair<ServiceNativeController, NativeTicket> {
            val controller = ServiceNativeController(runtime)
            controller.request(JSONObject("""{"type":"native","operationId":"scan"}"""))
            return controller to controller.active!!
        }
        try {
            val (first, ticket) = request(); assertTrue(first.isCurrent(ticket))
            runtime.logout(); assertFalse(first.isCurrent(ticket)); assertTrue(ticket.session.retired)
            val (second, next) = request(); runtime.session.invalidate(); assertFalse(second.isCurrent(next))
            runtime.logout()
            val result = runtime.nativeResult(JSONObject("""{"operationId":"scan","status":"success","text":"https://example.test/only-text"}"""))
            assertTrue(result.getString("message").contains("https://example.test/only-text"))
            val (third, scoped) = request(); active = false; assertFalse(third.isCurrent(scoped))
            try { runtime.nativeResult(JSONObject("""{"operationId":"scan","status":"cancelled"}""")); fail("Revoked callback accepted") }
            catch (e: AcademicException) { assertEquals(AcademicStatus.SESSION_EXPIRED, e.status) }
            val enabledRuntime = ServicePluginSession(app, pkg, "disable-check") { AcademicProviderRegistry.isEnabled(pkg.manifest.id) }
            val controller = ServiceNativeController(enabledRuntime); controller.request(JSONObject("""{"type":"native","operationId":"scan"}"""))
            val pending = controller.active!!; remove(pkg); assertFalse(controller.isCurrent(pending)); enabledRuntime.close()
        } finally { runtime.close(); remove(pkg) }
    }
    @Test fun placementPreferencesStayWithinAccountPluginEntryAndSurface() {
        val account = "native-test-${System.nanoTime()}"
        val first = ServiceEntryPreferences(app, account)
        first.setVisible("one", "tools", "home", false)
        assertFalse(ServiceEntryPreferences(app, account).visible("one", "tools", "home"))
        assertTrue(ServiceEntryPreferences(app, "$account-other").visible("one", "tools", "home"))
        assertTrue(first.visible("two", "tools", "home")); assertTrue(first.visible("one", "another", "home")); assertTrue(first.visible("one", "tools", "grades"))
        first.setVisible("one", "tools", "home", true); assertTrue(first.visible("one", "tools", "home"))
    }
    @Test fun nativePagesFormsAndEveryPermissionDenialRenderAndReturnResults() {
        val pkg = installed("test.native.ui")
        val appearance = com.tyust.course.manager.AppearanceSettingsManager
        val oldTheme = appearance.themeMode
        try {
            ActivityScenario.launch<ServicePluginActivity>(intent(pkg)).use { scenario ->
                awaitTag("service-block-shortcuts")
                compose.onNodeWithTag("service-block-shortcuts").performScrollTo(); capture("native-tools")
                compose.onNodeWithText("成长报告", substring = false).performScrollTo().performClick()
                awaitTag("service-block-summary")
                for (block in listOf("summary", "chart", "table", "timeline")) compose.onNodeWithTag("service-block-$block").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("service-block-chart").performScrollTo(); capture("native-report")
                scenario.onActivity { appearance.updateThemeMode(com.tyust.course.manager.AppThemeMode.Dark) }
                compose.waitForIdle(); capture("native-report-dark")
                compose.onNodeWithText("返回校园工具").performScrollTo().performClick(); awaitTag("service-field-note")
                compose.onNodeWithTag("service-field-note").performScrollTo().performTextInput("第一行\n第二行")
                compose.onNodeWithTag("service-field-enabled").performScrollTo().performClick()
                compose.onNodeWithTag("service-form-submit").performScrollTo().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("表单已读取，显示已完成：是。模板没有保存这些内容。").fetchSemanticsNodes().isNotEmpty() }
                for (label in listOf("扫描二维码", "选择文件", "演示通知", "演示日程")) {
                    compose.onNodeWithText(label, substring = false).performScrollTo().performClick()
                    compose.onNodeWithTag("service-native-allow").assertIsDisplayed()
                    if (label == "选择文件") capture("native-file-consent", dialog = true)
                    compose.onNodeWithTag("service-native-deny").performClick()
                    compose.waitUntil(10_000) { compose.onAllNodesWithText("你已取消系统操作").fetchSemanticsNodes().isNotEmpty() }
                }
            }
        } finally { appearance.updateThemeMode(oldTheme); remove(pkg) }
    }
    @Test fun systemFilePickerReturnsOnlySelectedContentAndNotificationIsAttributed() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33)
        val pkg = installed("test.native.system")
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        val name = "native-v3-${System.nanoTime()}.txt"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = app.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        app.contentResolver.openOutputStream(uri)!!.use { it.write("synthetic".toByteArray()) }
        app.contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        try {
            // Grant outside instrumentation: revoking inside this process kills the runner on Android 15.
            assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS))
            ActivityScenario.launch<ServicePluginActivity>(intent(pkg)).use {
                awaitTag("service-block-shortcuts")
                compose.onNodeWithText("选择文件", substring = false).performScrollTo().performClick()
                compose.onNodeWithTag("service-native-allow").performClick()
                val selected = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text(name)), 10_000)
                assertNotNull("Synthetic selected file is visible in DocumentsUI", selected)
                selected.click()
                compose.waitUntil(15_000) { compose.onAllNodesWithText("系统操作已完成：$name（9 字节）").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("演示通知", substring = false).performScrollTo().performClick()
                compose.onNodeWithTag("service-native-allow").performClick()
                compose.waitUntil(10_000) { manager.activeNotifications.any { it.tag == pkg.manifest.id } }
                val notification = manager.activeNotifications.first { it.tag == pkg.manifest.id }.notification
                assertEquals(pkg.manifest.name, notification.extras.getString(android.app.Notification.EXTRA_SUB_TEXT))
                assertEquals("校园工具演示", notification.extras.getString(android.app.Notification.EXTRA_TITLE))
                compose.waitUntil(10_000) { compose.onAllNodesWithText("系统操作已完成", substring = false).fetchSemanticsNodes().isNotEmpty() }
            }
        } finally {
            manager.cancel(pkg.manifest.id, "notify".hashCode())
            app.contentResolver.delete(uri, null, null)
            remove(pkg)
        }
    }
}

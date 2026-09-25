package com.tyust.course.academic.plugin

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class PluginCenterDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<PluginCenterActivity>()
    @Test fun managementPageRendersAndDeveloperControlsCanBeOpened() {
        val appearance = com.tyust.course.manager.AppearanceSettingsManager
        val previous = appearance.themeMode
        try {
            compose.runOnIdle { appearance.updateThemeMode(com.tyust.course.manager.AppThemeMode.Light) }
            compose.onNodeWithText("发现").assertIsDisplayed()
            compose.onNodeWithText("已安装").assertIsDisplayed()
            capture("management")
            compose.onNodeWithContentDescription("更多").performClick()
            compose.onNodeWithText("导入、回滚与开发工具").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("plugin-import").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("plugin-import").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("plugin-developer-toggle").performScrollTo().performClick()
            compose.onNodeWithText("本地验收目录 URL").performScrollTo().assertIsDisplayed()
            capture("development")
            compose.runOnIdle { appearance.updateThemeMode(com.tyust.course.manager.AppThemeMode.Dark) }
            compose.waitForIdle()
            capture("development-dark")
            compose.onNodeWithTag("plugin-import").performScrollTo()
            capture("management-dark")
        } finally { compose.runOnIdle { appearance.updateThemeMode(previous) } }
    }
    @Test fun catalogListsAndInstallsVerifiedPackageThroughTheUi() {
        org.junit.Assume.assumeTrue(com.tyust.course.BuildConfig.DEBUG)
        val context = compose.activity.applicationContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bytes = assets.open("academic-plugin/local.test-mock-1.0.0-signed.zfplugin").use { it.readBytes() }
        val id = "catalog.ui.mock"
        // Use a fresh test key for both the local catalog and its package.
        val pair = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        var source = ""
        val manifest = java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            var parsed: JSONObject? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "manifest.json") parsed = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                if (entry.name == "index.js") source = zip.readBytes().toString(Charsets.UTF_8)
            }
            parsed!!
        }
        manifest.put("id", id).put("name", "模拟大学适配")
        manifest.getJSONObject("school").put("id", id)
        fun sign(value: JSONObject) = android.util.Base64.encodeToString(java.security.Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(PluginJson.canonical(value).toByteArray()); sign() }, android.util.Base64.NO_WRAP)
        val key = JSONObject().put("keyId", "ui-test").put("spki", android.util.Base64.encodeToString(pair.public.encoded, android.util.Base64.NO_WRAP))
        val signed = java.io.ByteArrayOutputStream().also { out -> java.util.zip.ZipOutputStream(out).use { zip ->
            for ((name, value) in mapOf("manifest.json" to manifest.toString(), "index.js" to source, "signature.json" to JSONObject().put("keyId", "ui-test").put("signature", sign(manifest)).toString())) {
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            }
        } }.toByteArray()
        AcademicProviderRegistry.packages().deactivate(id)
        val prefs = context.getSharedPreferences("plugin-local-catalog", android.content.Context.MODE_PRIVATE)
        val oldUrl = prefs.getString("url", null); val oldKey = prefs.getString("key", null)
        MockWebServer().use { server ->
            server.start()
            val payload = JSONObject().put("apiVersion", 1).put("entries", JSONArray().put(JSONObject().put("id", id).put("name", "模拟大学适配").put("version", "1.0.0").put("url", "mock.zfplugin").put("sha256", PluginJson.sha256(signed))))
            val catalog = JSONObject().put("payload", payload).put("keyId", "ui-test").put("signature", sign(payload)).toString()
            try {
                AcademicProviderRegistry.configureLocalCatalog(server.url("/catalog.json").toString(), key.toString())
                val offline = java.util.concurrent.atomic.AtomicBoolean(false)
                server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                        offline.get() -> MockResponse().setResponseCode(503)
                        request.path == "/catalog.json" -> MockResponse().setBody(catalog)
                        request.path == "/mock.zfplugin" -> MockResponse().setBody(okio.Buffer().write(signed))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                compose.activityRule.scenario.recreate()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("catalog-install-$id").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("catalog-install-$id").performScrollTo()
                capture("catalog")
                val beforeSearch = server.requestCount
                compose.onNodeWithTag("plugin-search").performTextInput("找不到的学校")
                compose.onNodeWithText("没有找到匹配的插件").assertExists()
                compose.onNodeWithTag("plugin-search").performTextReplacement("模拟 适配")
                compose.onNodeWithTag("catalog-install-$id").assertExists()
                org.junit.Assert.assertEquals(beforeSearch, server.requestCount)
                compose.onNodeWithTag("plugin-search").performTextClearance()
                compose.onNodeWithTag("catalog-install-$id").performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithText("确认安装").fetchSemanticsNodes().isNotEmpty() }
                org.junit.Assert.assertNull(AcademicProviderRegistry.packages().active(id))
                compose.onNodeWithText("确认安装").performClick()
                compose.waitUntil(15_000) { AcademicProviderRegistry.packages().active(id)?.official == true }
                compose.waitForIdle()
                // Installation opens details; school binding remains a deliberate action.
                compose.waitUntil(10_000) { compose.onAllNodesWithText("声明支持").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("实际验证").assertExists()
                compose.onNodeWithText("完成").performClick()
                compose.waitUntil(10_000) { compose.onAllNodes(isRoot()).fetchSemanticsNodes().size == 1 }
                compose.onNodeWithTag("plugin-use-$id").performScrollTo().assertIsEnabled()
                capture("installed")
                offline.set(true)
                compose.onNodeWithContentDescription("更多").performClick()
                compose.onNodeWithText("刷新插件目录").performClick()
                compose.onNodeWithTag("plugin-list").performScrollToIndex(0)
                compose.waitUntil(10_000) { compose.onAllNodesWithText("刷新失败，仍可浏览已保存的插件").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("plugin-use-$id").assertExists()
                capture("catalog-offline")
            } finally {
                AcademicProviderRegistry.packages().deactivate(id)
                prefs.edit().putString("url", oldUrl).putString("key", oldKey).commit()
                AcademicProviderRegistry.initialize(context)
            }
        }
    }
    private fun capture(name: String) {
        val file = File(compose.activity.getExternalFilesDir(null), "plugin-ui/$name.png")
        file.parentFile!!.mkdirs()
        if (android.os.Build.VERSION.SDK_INT < 26) {
            check(androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file))
            return
        }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}

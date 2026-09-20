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
            compose.onNodeWithTag("plugin-import").assertIsDisplayed()
            capture("management")
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
                server.enqueue(MockResponse().setBody(catalog)); server.enqueue(MockResponse().setBody(catalog)); server.enqueue(MockResponse().setBody(okio.Buffer().write(signed)))
                compose.onNodeWithText("浏览适配目录").performScrollTo().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("catalog-install-$id").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("catalog-install-$id").performScrollTo()
                capture("catalog")
                compose.onNodeWithTag("catalog-install-$id").assertIsEnabled().performClick()
                compose.waitUntil(15_000) { AcademicProviderRegistry.packages().active(id)?.official == true }
                compose.waitForIdle()
                // Dismiss the optional school binding prompt; importing itself is complete.
                if (compose.onAllNodesWithText("取消").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("取消").performClick()
                compose.onNodeWithTag("catalog-install-$id").performScrollTo().assertIsNotEnabled()
                capture("installed")
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
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}

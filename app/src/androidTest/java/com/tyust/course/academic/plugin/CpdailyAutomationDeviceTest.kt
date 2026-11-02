package com.tyust.course.academic.plugin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyust.course.BuildConfig
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionKey
import com.tyust.course.ui.system.GlassWindowHost
import com.tyust.course.ui.theme.CourseSelectorTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * UI_PREVIEW only. Pass -e cpdailyPackage <absolute target external-files path>.
 * Installs the unchanged 2026-11-02 delivery through the normal developer importer.
 * Real: Android QuickJS ui.init/ui.reduce, contract validation, Compose renderer, scoped
 * storage, file handles and denied foreground-start confirmation. Synthetic: school,
 * auth, requirements, map/GPS/profile, picker result and workflow/foreground results.
 * No service/network/upload/workflow write or foreground worker is ever dispatched.
 * This deliberately does not claim school acceptance or automatic task.run coverage.
 */
@RunWith(AndroidJUnit4::class)
class CpdailyAutomationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val shown = mutableStateOf<JSONObject?>(null)
    private val emitted = LinkedBlockingQueue<JSONObject>()

    @Before fun previewOnly() { assumeTrue("Requires UI_PREVIEW", BuildConfig.UI_PREVIEW) }

    private class Interaction : NativePluginInteraction {
        var confirmations = 0
        override suspend fun confirm(title: String, message: String): Boolean {
            confirmations++
            assertTrue(message.contains("1 项"))
            return false // Reach the real consent boundary; never start a worker.
        }
        override suspend fun authenticate(challenge: JSONObject, image: File?): JSONObject? = error("No real authentication")
        override suspend fun pick(types: Array<String>): android.net.Uri? = error("Picker result is supplied by test")
        override suspend fun notificationPermission() = false
        override fun haptic() {}
        override fun navigate(pageId: String, params: JSONObject) = error("Unexpected navigation")
        override fun back() {}
    }

    private inner class Page(val pkg: PluginPackage, val session: AcademicSession) : AutoCloseable {
        val interaction = Interaction()
        val host = NativeCapabilityHost(app, pkg, session, interaction) { true }
        var result = JSONObject()
        val effects get() = PluginJson.objects(result.getJSONArray("effects"))
        val state get() = result.getJSONObject("state")
        fun invoke(method: String, args: JSONObject) {
            result = runBlocking {
                NativePluginRunner.invoke(app, pkg, session, method, args,
                    JSONObject().put("capabilities", host.capabilities())) { true }
            }
            compose.runOnIdle { shown.value = result.getJSONObject("view") }
        }
        fun init() = invoke("ui.init", JSONObject().put("pageId", "dorm"))
        fun event(name: String, value: Any? = null) = reduce(JSONObject().put("type", "click").put("name", name)
            .apply { if (value != null) put("value", value) })
        fun reduce(event: JSONObject) = invoke("ui.reduce", JSONObject().put("pageId", "dorm").put("state", state).put("event", event))
        fun effect(capability: String): JSONObject = effects.single().also { assertEquals(capability, it.getString("capability")) }
        fun reply(data: Any?, capability: String) {
            val effect = effect(capability)
            val receipt = if (capability == "services.call")
                JSONObject((data as JSONObject).toString()).put("providerId", pkg.manifest.id).put("digest", pkg.digest)
            else data ?: JSONObject.NULL
            val descriptor = PluginJson.objects(host.capabilities()).single {
                it.getString("name") == capability && it.getInt("version") == effect.getInt("version")
            }
            PluginContractCache.schema(app).validate(receipt, descriptor.getJSONObject("output"))
            reduce(JSONObject().put("type", "effect.result").put("effectId", effect.getString("id"))
                .put("result", JSONObject().put("ok", true).put("data", receipt)))
        }
        fun local() {
            val effect = effects.single()
            check(effect.getString("capability") in setOf("storage.get", "storage.set")) { "Only local storage may be dispatched" }
            reply(runBlocking { host.execute(effect, NativeFlow(true)) }, effect.getString("capability"))
        }
        fun click(id: String) {
            assertTrue("Unexpected queued Compose event", emitted.isEmpty())
            scroll(id).performClick()
            compose.waitUntil(5_000) { emitted.isNotEmpty() }
            reduce(emitted.remove())
        }
        fun scroll(id: String): SemanticsNodeInteraction {
            compose.onNodeWithTag("native-node-root").performScrollToNode(hasTestTag("native-node-$id"))
            return compose.onNodeWithTag("native-node-$id", useUnmergedTree = true)
        }
        fun input(id: String, value: String) {
            scroll(id)
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("native-node-$id")),
                useUnmergedTree = true).performTextReplacement(value)
            compose.waitUntil(5_000) { emitted.isNotEmpty() }
            reduce(emitted.remove())
        }
        fun node(id: String) = NativePluginContract.findNode(result.getJSONObject("view"), id)
            ?: error("Missing node $id")
        fun ready() {
            init()
            event("school", "synthetic-campus.invalid")
            event("validate")
            reply(JSONObject().put("state", "ready").put("message", "合成学校会话"), "auth.service")
            event("tasks")
            assertEquals("cpdaily.tasks", effect("services.call").getJSONObject("input").getString("name"))
            reply(JSONObject().put("value", JSONObject().put("rows", JSONArray().put(JSONObject()
                .put("identity", JSONArray(listOf("synthetic-task", "synthetic-instance")))
                .put("group", "unSignedTasks").put("record", JSONObject().put("taskName", "合成设备任务"))))), "services.call")
            event("record-0")
            assertEquals("cpdaily.requirements", effect("services.call").getJSONObject("input").getString("name"))
            reply(JSONObject().put("value", JSONObject().put("state", "ready").put("mode", "photo")
                .put("detail", JSONObject().put("isPhoto", 1).put("isFaceIdentify", 0))), "services.call")
            event("materials")
        }
        fun deviceMaterials() {
            event("location")
            reply(JSONObject().put("latitude", 0.0).put("longitude", 0.0).put("accuracy", 5)
                .put("timestamp", System.currentTimeMillis()), "device.location")
            reply(JSONObject().put("systemName", "android").put("systemVersion", "15").put("model", "synthetic")
                .put("manufacturer", "test").put("installationId", "synthetic-device").put("appVersion", "test"), "device.profile")
            event("address", "合成测试地点（非真实学校）")
            event("addressConfirm", true)
        }
        override fun close() {
            runBlocking {
                host.execute(JSONObject().put("id", "cleanup").put("capability", "storage.remove").put("version", 1)
                    .put("input", JSONObject().put("key", "cpdaily.locations.v1")), NativeFlow(false))
            }
            host.files.clear()
            host.close()
            session.retire()
        }
    }

    private fun withPackage(test: (PluginPackage) -> Unit) {
        val argument = InstrumentationRegistry.getArguments().getString("cpdailyPackage")
        require(!argument.isNullOrBlank()) { "Pass -e cpdailyPackage /sdcard/Android/data/${app.packageName}/files/cpdaily-test/cpdaily.eduplugin" }
        val external = requireNotNull(app.getExternalFilesDir(null)).canonicalFile
        val delivery = File(argument).canonicalFile
        require(delivery.path.startsWith(external.path + File.separator) && delivery.isFile) { "Package must be inside target external files: $external" }
        val bytes = delivery.readBytes()
        assertEquals("Wrong final delivery", DELIVERY_SHA256, PluginJson.sha256(bytes))
        val store = AcademicProviderRegistry.packages()
        val previous = store.active(ID)
        check(previous == null || previous.digest == DELIVERY_SHA256) { "Use a clean preview profile or the same delivery; existing CPDaily is preserved" }
        val pkg = runBlocking { store.install(bytes, true) }
        try {
            assertEquals(ID, pkg.manifest.id)
            assertEquals("0.1.1", pkg.manifest.version)
            assertFalse(pkg.official)
            assertEquals(pkg.digest, store.activeDigest(ID))
            AcademicProviderRegistry.reload()
            test(pkg)
        } finally {
            compose.runOnIdle { shown.value = null }
            compose.waitForIdle()
            val keepInstalled = InstrumentationRegistry.getArguments().getString("cpdailyKeepInstalled") == "true"
            if (previous == null && !keepInstalled) { store.deactivate(ID); AcademicProviderRegistry.reload() }
        }
    }

    private fun page(pkg: PluginPackage, account: String = UUID.randomUUID().toString()) = Page(pkg,
        AcademicSession(AcademicSessionKey("synthetic-school.invalid", "device-test-$account"), "https://school.invalid/"))

    private fun render(page: Page) {
        compose.setContent {
            CourseSelectorTheme { GlassWindowHost {
                shown.value?.let { NativePluginNode(it, page.host.files) { event, _ -> emitted.add(event) } }
            } }
        }
    }

    private fun capture(name: String) {
        val image = File(app.getExternalFilesDir(null), "cpdaily-ui/$name.png")
        image.parentFile!!.mkdirs()
        compose.waitForIdle()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }

    @Test fun deliveryInstallsAndSelectedPointPersistsOnlyForItsAccount() = withPackage { pkg ->
        page(pkg).use { one ->
            render(one)
            one.ready()
            one.click("addressLookup")
            assertEquals(2, one.effect("device.location.pick").getInt("version"))
            // Map dialog is isolated; the real handler consumes its synthetic result.
            one.reply(JSONObject().put("latitude", 0.0).put("longitude", 0.0).put("coordinateSystem", "WGS84")
                .put("address", "合成地图地点"), "device.location.pick")
            one.input("address", "合成地图地点（设备输入）")
            assertFalse(one.state.getBoolean("addressConfirmed"))
            one.click("placeSave"); one.local(); one.local()
            assertEquals(1, one.state.getJSONArray("savedPositions").length())
            one.ready(); one.event("placesLoad"); one.local()
            assertEquals("合成地图地点", one.state.getJSONArray("savedPositions").getJSONObject(0).getString("label"))
            one.event("placeSelect", "0")
            assertEquals("saved", one.state.getJSONObject("selectedPosition").getString("source"))
            one.node("selected-position").getString("text").let { assertTrue(it.contains("合成地图地点")) }
            one.scroll("selected-position")
            capture("saved-location")
            page(pkg).use { other ->
                other.ready(); other.event("placesLoad"); other.local()
                assertEquals(0, other.state.getJSONArray("savedPositions").length())
                // A late result from the previous account must clear material bindings.
                other.invoke("ui.reduce", JSONObject().put("pageId", "dorm").put("state", one.state)
                    .put("event", JSONObject().put("type", "effect.result").put("effectId", "stale")
                        .put("result", JSONObject().put("ok", true).put("data", JSONObject()))))
                assertEquals("signed_out", other.state.getString("authState"))
                assertTrue(other.state.isNull("selectedPosition"))
                assertEquals(0, other.state.getJSONArray("savedPositions").length())
                assertTrue(other.effects.isEmpty())
            }
        }
    }

    @Test fun realPngHandleRendersMaterialsAndCannotCrossAccounts() = withPackage { pkg ->
        page(pkg).use { one ->
            render(one); one.ready()
            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            val image = one.host.files.create("synthetic-device-photo.png", "image/png", bytes)
            val handle = image.getString("handle")
            assertArrayEquals(bytes, one.host.files.file(handle).readBytes())
            requireNotNull(BitmapFactory.decodeFile(one.host.files.file(handle).path)).let { decoded ->
                assertEquals(24, decoded.width); decoded.recycle()
            }
            one.click("photoImport")
            assertEquals("image/png", one.effect("files.pick").getJSONObject("input").getJSONArray("mimeTypes").getString(1))
            one.reply(image, "files.pick")
            assertEquals(handle, one.node("photo-preview").getString("handle"))
            one.scroll("photo-preview").assertIsDisplayed()
            compose.onNodeWithContentDescription("本次本人真实照片，上传与提交前请核对").assertExists()
            capture("photo-preview")
            assertEquals("import", one.state.getString("photoSource"))
            assertEquals("", one.state.getString("photoId"))
            assertTrue(one.effects.isEmpty()) // Import never uploads or submits.
            page(pkg).use { other ->
                try { other.host.files.file(handle); fail("Cross-account handle accepted") }
                catch (error: PluginException) { assertEquals(PluginErrorCode.VALIDATION_FAILED, error.code) }
            }
            one.event("school", "another-synthetic-school.invalid")
            assertTrue(one.state.isNull("photo")); assertTrue(one.state.isNull("selectedPosition"))
            assertEquals(-1, one.state.getInt("selected"))
            assertFalse(one.result.getJSONObject("view").toString().contains(handle))
        }
    }

    @Test fun automaticStartRequiresConsentAndUnknownWorkflowCannotReplayFromUi() = withPackage { pkg ->
        page(pkg).use { one ->
            render(one); one.ready(); one.deviceMaterials()
            // Synthetic task requires no photo for this branch; no school service is invoked.
            one.event("requirements")
            one.reply(JSONObject().put("value", JSONObject().put("state", "ready").put("mode", "normal")), "services.call")
            one.event("materials"); one.deviceMaterials()
            one.click("auto.add"); one.click("auto.start")
            val start = one.effect("tasks.foreground.start")
            assertEquals(3, start.getInt("version"))
            assertEquals(1, start.getJSONObject("input").getJSONObject("input").getJSONArray("items").length())
            try { runBlocking { one.host.execute(start, NativeFlow(true)) }; fail("Consent denial must prevent start") }
            catch (error: PluginException) { assertEquals(PluginErrorCode.CANCELLED, error.code) }
            assertEquals(1, one.interaction.confirmations)
            // Successful start/stop are synthetic: never launch a worker with test materials.
            one.reply(JSONObject().put("handle", "synthetic-foreground-handle"), "tasks.foreground.start")
            one.scroll("auto.start").assertIsNotEnabled()
            one.click("auto.stop")
            assertEquals("synthetic-foreground-handle", one.effect("tasks.foreground.stop").getJSONObject("input").getString("handle"))
            one.reply(JSONObject.NULL, "tasks.foreground.stop")
            assertEquals("", one.state.getString("autoHandle")); assertTrue(one.effects.isEmpty())
            one.click("preview")
            val preparation = one.effect("workflow.prepare").getJSONObject("input").getJSONObject("input")
            assertEquals("synthetic-campus.invalid", preparation.getString("tenantId"))
            assertEquals("synthetic-task", preparation.getJSONArray("identity").getString(0))
            one.reply(JSONObject().put("workflowId", "synthetic-workflow").put("summary", "合成流程预览")
                .put("title", "合成设备验收").put("approved", false).put("cancelled", false)
                .put("steps", JSONArray().put(JSONObject().put("label", "合成操作").put("status", "ready"))), "workflow.prepare")
            one.click("run")
            assertEquals("synthetic-workflow", one.effect("workflow.step").getJSONObject("input").getString("workflowId"))
            one.reply(JSONObject().put("status", "unknown").put("message", "合成未知结果"), "workflow.step")
            one.scroll("run").assertIsNotEnabled().performClick()
            compose.waitForIdle()
            assertTrue("Disabled execution must emit no event", emitted.isEmpty())
            assertTrue(one.effects.isEmpty())
            assertTrue(one.node("reconcile").getBoolean("enabled"))
            capture("unknown-workflow")
            one.click("cancel")
            one.reply(JSONObject(one.state.getJSONObject("workflow").toString()).put("cancelled", true), "workflow.cancel")
            assertTrue(one.state.getJSONObject("workflow").getBoolean("cancelled"))
            assertEquals("unknown", one.state.getJSONObject("workflow").getJSONArray("steps").getJSONObject(0).getString("status"))
            assertFalse(one.node("run").getBoolean("enabled"))
        }
    }

    companion object {
        private const val ID = "dev.znjhahaha.cpdaily"
        private const val DELIVERY_SHA256 = "34bef3427da97071e515e392f49ea943ccf9fff0e48d3aa0c37815ab7cdce499"
    }
}

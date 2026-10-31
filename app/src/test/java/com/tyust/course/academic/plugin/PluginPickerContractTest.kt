package com.tyust.course.academic.plugin

import android.app.Application
import android.net.Uri
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class PluginPickerContractTest {
    private lateinit var app: Application

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After fun cleanup() = Dispatchers.resetMain()

    private fun manifest(code: Int, permissions: List<String> = listOf("pages")) = PluginManifest(JSONObject("""{
      "id":"demo.pickers","name":"Pickers","version":"1.0.0","kind":"native","apiVersion":3,"minAppVersionCode":$code,
      "capabilities":[],"permissions":${JSONArray(permissions)},
      "requires":[{"name":"ui.date.pick","version":1},{"name":"ui.time.pick","version":1},{"name":"ui.datetime.pick","version":1},{"name":"ui.time.range.pick","version":1}],
      "network":[],"contributes":{"pages":[],"entries":[]}}
    """))

    private fun pkg(code: Int = 130, permissions: List<String> = listOf("pages")) =
        PluginPackage(manifest(code, permissions), "", "a".repeat(64), false)

    private fun effect(name: String, input: JSONObject) = JSONObject()
        .put("id", "picker-effect").put("capability", name).put("version", 1).put("input", input)

    private class FakePicker(private val result: JSONObject?) : NativePluginInteraction {
        var seen: Pair<String, JSONObject>? = null
        override suspend fun confirm(title: String, message: String) = true
        override suspend fun authenticate(challenge: JSONObject, image: File?) = JSONObject()
        override suspend fun pick(types: Array<String>): Uri? = null
        override suspend fun notificationPermission() = false
        override suspend fun picker(kind: String, input: JSONObject): JSONObject? {
            seen = kind to input
            return result
        }
        override fun haptic() {}
        override fun navigate(pageId: String, params: JSONObject) {}
        override fun back() {}
    }

    private fun host(interaction: NativePluginInteraction, code: Int = 130, permissions: List<String> = listOf("pages")) =
        NativeCapabilityHost(app, pkg(code, permissions), AcademicSession(AcademicSessionKey("plugin:demo.pickers", "default"), "https://school.test"), interaction) { true }

    @Test fun wheelPickersAreImplementedHostCapabilities() {
        for (name in listOf("ui.date.pick", "ui.time.pick", "ui.datetime.pick", "ui.time.range.pick")) {
            assertTrue(name, name in NativeCapabilityHost.IMPLEMENTED)
        }
    }

    @Test fun pickersRequireAppOneZeroOneThreeZero() {
        assertThrows(PluginException::class.java) { PluginPlatformContract.validate(manifest(129)) }
        PluginPlatformContract.validate(manifest(130))
    }

    @Test fun pickersAreNegotiatedThroughRequires() {
        val m = manifest(130)
        PluginPlatformContract.requireCompatible(m, 130, mapOf("ui.date.pick" to 1, "ui.time.pick" to 1, "ui.datetime.pick" to 1, "ui.time.range.pick" to 1))
        assertThrows(PluginException::class.java) { PluginPlatformContract.requireCompatible(m, 130, emptyMap()) }
    }

    @Test fun datePickForwardsInitialValueAndReturnsPickedDate() = runBlocking {
        val picked = JSONObject().put("date", "2026-03-02").put("millis", 1774022400000L)
        val interaction = FakePicker(picked)
        val result = host(interaction).execute(effect("ui.date.pick", JSONObject().put("initial", "2026-02-23").put("title", "选择开学第一周")), NativeFlow(true))
        assertTrue(result is JSONObject)
        result as JSONObject
        assertTrue(result.getString("date") == "2026-03-02")
        val (kind, input) = interaction.seen!!
        assertTrue(kind == "ui.date.pick")
        assertTrue(input.getString("initial") == "2026-02-23")
        assertTrue(input.getString("title") == "选择开学第一周")
    }

    @Test fun cancelledPickerAndMissingPermissionAreRejected() {
        assertThrows(PluginException::class.java) {
            runBlocking { host(FakePicker(null)).execute(effect("ui.time.pick", JSONObject()), NativeFlow(true)) }
        }
        assertThrows(PluginException::class.java) {
            runBlocking { host(FakePicker(JSONObject().put("time", "08:05")), permissions = emptyList()).execute(effect("ui.time.pick", JSONObject()), NativeFlow(true)) }
        }
        // 格式不合法的初始值必须被契约拒绝，而不是被静默忽略。
        assertThrows(PluginException::class.java) {
            runBlocking { host(FakePicker(JSONObject().put("date", "2026-03-02").put("millis", 0))).execute(effect("ui.date.pick", JSONObject().put("initial", "2026-3-2")), NativeFlow(true)) }
        }
    }

    @Test fun preEpochInitialConfirmationPassesFinalHostOutputValidation() = runBlocking {
        for ((name, input, picked) in listOf(
            Triple("ui.date.pick", "1969-12-31", JSONObject().put("date", "1969-12-31").put("millis", -115200000L)),
            Triple("ui.datetime.pick", "1969-12-31 12:00", JSONObject().put("datetime", "1969-12-31 12:00").put("millis", -72000000L))
        )) {
            val interaction = FakePicker(picked)
            val result = host(interaction).execute(effect(name, JSONObject().put("initial", input)), NativeFlow(true)) as JSONObject
            assertTrue(result.getLong("millis") < 0)
            assertTrue(interaction.seen!!.second.getString("initial") == input)
        }
    }

    @Test fun pickersNeedForegroundNativePage() {
        assertThrows(PluginException::class.java) {
            runBlocking {
                NativeCapabilityHost(app, pkg(), AcademicSession(AcademicSessionKey("plugin:demo.pickers", "default"), "https://school.test"), null) { true }
                    .execute(effect("ui.date.pick", JSONObject()), NativeFlow(true))
            }
        }
    }
}

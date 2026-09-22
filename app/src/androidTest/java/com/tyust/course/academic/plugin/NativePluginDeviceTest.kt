package com.tyust.course.academic.plugin

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionKey
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal fun nativeFixtureBytes(template: String, id: String, change: (JSONObject) -> Unit = {}): ByteArray {
    val assets = InstrumentationRegistry.getInstrumentation().context.assets
    val source = assets.open("academic-plugin/$template.js").bufferedReader().use { it.readText() }
    val manifest = JSONObject(assets.open("academic-plugin/$template-manifest.json").bufferedReader().use { it.readText() })
        .put("id", id).put("files", JSONObject().put("index.js", PluginJson.sha256(source.toByteArray())))
    change(manifest)
    return java.io.ByteArrayOutputStream().also { out -> java.util.zip.ZipOutputStream(out).use { zip ->
        for ((name, value) in mapOf("manifest.json" to manifest.toString(), "index.js" to source)) {
            zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
        }
    } }.toByteArray()
}

@RunWith(AndroidJUnit4::class)
class NativePluginDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private fun session(account: String = "a", school: String = "s") = AcademicSession(AcademicSessionKey(school, account), "https://example.test/")
    private suspend fun install(template: String = "native-capabilities", change: (JSONObject) -> Unit = {}): PluginPackage = AcademicProviderRegistry.packages()
        .install(nativeFixtureBytes(template, "test.native." + UUID.randomUUID().toString().replace("-", ""), change), true).also { AcademicProviderRegistry.reload() }
    private fun remove(pkg: PluginPackage) { AcademicProviderRegistry.packages().deactivate(pkg.manifest.id); AcademicProviderRegistry.reload() }
    private fun effect(name: String, input: JSONObject = JSONObject()) = JSONObject().put("id", "test-effect").put("capability", name).put("version", 1).put("input", input)
    private open class Interaction : NativePluginInteraction {
        override suspend fun confirm(title: String, message: String) = true
        override suspend fun authenticate(challenge: JSONObject, image: File?) = JSONObject().put("values", JSONObject().put("username", "fictional").put("password", "fixture-secret")).put("remember", true)
        override suspend fun pick(types: Array<String>): Uri? = null
        override suspend fun notificationPermission() = false
        override fun haptic() {}
        override fun navigate(pageId: String, params: JSONObject) {}
        override fun back() {}
    }
    private suspend fun rejects(code: PluginErrorCode, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: PluginException) { assertEquals(code, error.code) }
    }
    private suspend fun awaitView(ui: NativeUiSession, text: String) = withTimeout(20_000) {
        while (ui.snapshot.value.view?.toString()?.contains(text) != true) {
            check(ui.snapshot.value.error.isBlank()) { ui.snapshot.value.error }
            delay(20)
        }
    }
    private suspend fun click(ui: NativeUiSession, id: String, name: String) = withContext(Dispatchers.Main) {
        ui.event(ui.snapshot.value.instance, JSONObject().put("type", "click").put("nodeId", id).put("name", name), true)
    }

    @Test fun quickJsReducerComposesPagesAndRestoresOnlyItsAccountState() = runBlocking {
        val pkg = install("native-components")
        val one = session()
        val host = NativeCapabilityHost(app, pkg, one, Interaction()) { true }
        val ui = withContext(Dispatchers.Main) { NativeUiSession(app, pkg, one, host) { true }.also { it.open("overview") } }
        try {
            awaitView(ui, "可以开始组合页面")
            click(ui, "increment", "increment"); awaitView(ui, "计数：1")
            click(ui, "save", "save"); awaitView(ui, "偏好已保存")
            withContext(Dispatchers.Main) { ui.open("summary") }
            awaitView(ui, "已恢复当前账号与版本的偏好"); awaitView(ui, "计数：1")
            val query = NativePluginRunner.invoke(app, pkg, one, "data.query", JSONObject().put("providerId", "sample.rows").put("input", JSONObject()), JSONObject()) { true }
            assertEquals(10, query.getJSONArray("items").length())
            val other = NativeCapabilityHost(app, pkg, session("b"), Interaction()) { true }
            try { assertEquals(JSONObject.NULL, other.execute(effect("storage.get", JSONObject().put("key", "preferences")), NativeFlow(false))) } finally { other.close() }
        } finally { withContext(Dispatchers.Main) { ui.close() }; remove(pkg) }
    }

    @Test fun capabilitiesEnforcePermissionsGesturesCancellationAndScope() = runBlocking {
        val pkg = install("native-components"); val current = session()
        var active = true
        val host = NativeCapabilityHost(app, pkg, current, Interaction()) { active }
        try {
            host.requireCompatible()
            rejects(PluginErrorCode.PERMISSION_DENIED) { host.execute(effect("files.pick", JSONObject().put("mimeTypes", JSONArray(listOf("text/plain")))), NativeFlow(true)) }
            active = false
            rejects(PluginErrorCode.STALE_CONTEXT) { host.execute(effect("storage.get", JSONObject().put("key", "x")), NativeFlow(false)) }
        } finally { host.close(); remove(pkg) }
        val capable = install(); val foreground = NativeCapabilityHost(app, capable, session(), Interaction()) { true }
        val background = NativeCapabilityHost(app, capable, session(), null) { true }
        try {
            val names = PluginJson.objects(background.capabilities()).map { it.getString("name") }
            assertFalse(names.contains("files.pick")); assertFalse(names.contains("navigation.page"))
            rejects(PluginErrorCode.PERMISSION_DENIED) { foreground.execute(effect("files.pick", JSONObject().put("mimeTypes", JSONArray(listOf("text/plain")))), NativeFlow(false)) }
            rejects(PluginErrorCode.CANCELLED) { foreground.execute(effect("files.pick", JSONObject().put("mimeTypes", JSONArray(listOf("text/plain")))), NativeFlow(true)) }
        } finally { foreground.close(); background.close(); remove(capable) }
    }

    @Test fun filesAndEncryptedCredentialsStayWithinSchoolAccountAndPackage() = runBlocking {
        val pkg = install(); val one = NativeCapabilityHost(app, pkg, session(), Interaction()) { true }
        val others = listOf(NativeCapabilityHost(app, pkg, session("b"), Interaction()) { true }, NativeCapabilityHost(app, pkg, session(school = "t"), Interaction()) { true })
        try {
            val created = one.execute(effect("files.create", JSONObject().put("name", "sample.txt").put("mime", "text/plain")), NativeFlow(false)) as JSONObject
            val handle = created.getString("handle")
            one.execute(effect("files.write", JSONObject().put("handle", handle).put("base64", "SGVsbG8=")), NativeFlow(false))
            assertEquals("SGVsbG8=", (one.execute(effect("files.read", JSONObject().put("handle", handle).put("length", 20)), NativeFlow(false)) as JSONObject).getString("base64"))
            for (other in others) rejects(PluginErrorCode.VALIDATION_FAILED) { other.execute(effect("files.read", JSONObject().put("handle", handle).put("length", 20)), NativeFlow(false)) }
            val vault = NativePluginVault(app, one.namespace); val values = JSONObject().put("password", "fictional-secret-for-test")
            val credential = vault.saveCredential("example", values, true)
            assertEquals(values.toString(), NativePluginVault(app, one.namespace).get("credential:$credential").toString())
            for (namespace in listOf(others[0].namespace, others[1].namespace, one.namespace + "different-package")) assertNull(NativePluginVault(app, namespace).get("credential:$credential"))
            coroutineScope { (1..12).map { index -> async(Dispatchers.IO) { NativePluginVault(app, one.namespace).put("concurrent", JSONObject().put("index", index)) } }.awaitAll() }
            assertTrue(NativePluginVault(app, one.namespace).get("concurrent")!!.getInt("index") in 1..12)
            one.execute(effect("files.remove", JSONObject().put("handle", handle)), NativeFlow(false))
            vault.remove("credential:$credential"); vault.remove("credential-key:example"); vault.remove("concurrent")
        } finally { one.close(); others.forEach { it.close() }; remove(pkg) }
    }

    @Test fun lateAndCancelledPickerResultsCannotChangeANewPage() = runBlocking {
        val pkg = install(); val current = session(); val picker = CompletableDeferred<Uri?>(); val started = CompletableDeferred<Unit>()
        val interaction = object : Interaction() { override suspend fun pick(types: Array<String>): Uri? { started.complete(Unit); return picker.await() } }
        val host = NativeCapabilityHost(app, pkg, current, interaction) { true }
        val ui = withContext(Dispatchers.Main) { NativeUiSession(app, pkg, current, host) { true }.also { it.open("overview") } }
        try {
            awaitView(ui, "选择一种宿主能力")
            click(ui, "pick", "pick"); withTimeout(10_000) { started.await() }
            val old = ui.snapshot.value.instance
            withContext(Dispatchers.Main) { ui.open("overview") }
            assertNotEquals(old, ui.snapshot.value.instance)
            picker.complete(null); awaitView(ui, "选择一种宿主能力")
            withContext(Dispatchers.Main) { ui.event(old, JSONObject().put("type", "click").put("nodeId", "create").put("name", "create"), true) }
            delay(200); assertFalse(ui.snapshot.value.view.toString().contains("操作未完成"))
            assertFalse(ui.snapshot.value.view.toString().contains("已选择文件，可读取"))
        } finally { withContext(Dispatchers.Main) { ui.close() }; remove(pkg) }
    }

    @Test fun cancellationAfterNetworkWriteIsUnknownAndNeverReplayed() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val origin = server.url("/").toString().trimEnd('/')
            val pkg = install { it.put("network", JSONArray().put(JSONObject().put("origin", origin).put("pathPrefix", "/write").put("methods", JSONArray(listOf("POST"))).put("purposes", JSONArray(listOf("mutation"))))) }
            val host = NativeCapabilityHost(app, pkg, session(), Interaction()) { true }; val flow = NativeFlow(true)
            try {
                flow.accept("write")
                val task = launch(Dispatchers.IO) { host.execute(effect("network.request", JSONObject().put("url", "$origin/write").put("method", "POST").put("purpose", "mutation").put("body", "fictional")), flow) }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(10, TimeUnit.SECONDS) })
                task.cancelAndJoin()
                assertEquals(PluginErrorCode.RESULT_UNKNOWN, flow.failureCode(PluginErrorCode.CANCELLED))
                rejects(PluginErrorCode.RESULT_UNKNOWN) { flow.accept("write-again") }
                assertEquals(1, server.requestCount)
            } finally { host.close(); remove(pkg) }
        }
    }

    @Test fun scheduledTasksPinSchoolAccountAndDigestAndCanBeCancelled() = runBlocking {
        val pkg = install(); val one = NativeCapabilityHost(app, pkg, session(), Interaction()) { true }
        try {
            val result = one.execute(effect("tasks.schedule", JSONObject().put("taskId", "remember").put("delaySeconds", 60).put("input", "fixture").put("state", JSONObject())), NativeFlow(true)) as JSONObject
            val handle = result.getString("handle"); val record = NativePluginTasks.load(app, handle)
            assertEquals(pkg.digest, record.getString("digest")); assertEquals("s", record.getString("schoolKey")); assertEquals("a", record.getString("accountKey"))
            assertEquals(0, NativePluginTasks.list(app, one.namespace + "another-account").length())
            rejects(PluginErrorCode.PERMISSION_DENIED) { NativePluginTasks.cancel(app, one.namespace + "another-account", handle) }
            one.execute(effect("tasks.cancel", JSONObject().put("handle", handle)), NativeFlow(false))
            assertEquals("cancelled", NativePluginTasks.load(app, handle).getString("status"))
        } finally { one.close(); remove(pkg) }
    }
}

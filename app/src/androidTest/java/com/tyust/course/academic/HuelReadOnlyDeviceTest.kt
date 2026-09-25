package com.tyust.course.academic

import android.app.ActivityOptions
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.tyust.course.BuildConfig
import com.tyust.course.academic.plugin.*
import com.tyust.course.login.PasswordLoginCallback
import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.regex.Pattern

/** Explicitly opt-in. Real login and reads only; reports contain counts and status, never student data. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class HuelReadOnlyDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = "https://xk.huel.edu.cn/jwglxt"
    private fun school() = SchoolConfig("huel-device-verification", "河南财经政法大学", "xk.huel.edu.cn", "https").apply {
        basePath = "/jwglxt"; academicSystem = "zf"; academicProvider = "builtin.zf"
    }
    private fun credentials(): Credentials {
        val name = InstrumentationRegistry.getArguments().getString("huelFixture")
        assumeTrue("HUEL live tests require an explicitly supplied private fixture", !name.isNullOrBlank())
        require(name == File(name!!).name)
        val value = JSONObject(File(app.cacheDir, name).readText())
        return Credentials(value.getString("username"), value.getString("password"))
    }
    private fun report(mode: String, value: JSONObject) {
        File(app.cacheDir, "huel-$mode-report.json").writeText(value.toString(2))
    }
    private fun identity(login: LoginResult, credentials: Credentials) {
        assertEquals("School login status", AcademicStatus.SUCCESS, login.status)
        assertTrue("Authenticated student must match the supplied account", login.studentId == credentials.username)
        assertTrue("School identity must include a name", login.studentName.isNotBlank())
    }
    private suspend fun readOnly(adapter: PluginAcademicAdapter, result: JSONObject, mode: String) {
        suspend fun <T> read(name: String, call: suspend () -> T): T {
            result.put("running", name); report(mode, result)
            return call()
        }
        val catalog = read("terms") { adapter.catalog() }
        assertTrue("Term catalog cannot be empty", catalog.terms.isNotEmpty())
        result.put("terms", catalog.terms.size).put("currentTerm", catalog.currentTerm.id)
        val schedule = read("schedule") { adapter.schedule(catalog.currentTerm) }
        assertTrue("Expected the reported student's current schedule", schedule.isNotEmpty())
        assertTrue("Schedule structure", schedule.all { it.name.isNotBlank() && it.day in 1..7 && it.startPeriod > 0 && it.endPeriod >= it.startPeriod })
        result.put("schedule", schedule.size).put("scheduleRoomsPresent", schedule.count { it.location.isNotBlank() })
        val calendar = read("calendar") { adapter.calendar(catalog.currentTerm) }
        result.put("periods", calendar?.optJSONArray("periods")?.length() ?: 0)
        val grades = read("grades") { adapter.grades() }
        result.put("grades", grades.grades.size)
        if (grades.grades.isNotEmpty()) result.put("gradeDetailsPresent", read("gradeDetails") { adapter.gradeDetails(grades.grades.first()) }.isNotBlank())
        result.put("exams", read("exams") { adapter.exams(catalog.currentTerm) }.size)
        try {
            val context = read("selectionCatalog") { adapter.loadCourseContext() }
            result.put("openSelectionRounds", context.scopes.size)
            if (context.scopes.isNotEmpty()) {
                val offers = read("selectionCourses") { adapter.listCourses(context, CourseQuery(pageSize = 20)) }
                result.put("selectionCourses", offers.size)
                if (offers.isNotEmpty()) result.put("sections", read("sections") { adapter.listSections(offers.first()) }.size)
            }
            result.put("enrolled", read("enrolled") { adapter.selected(context) }.size)
        } catch (error: AcademicException) {
            if (error.status !in setOf(AcademicStatus.ROUND_CLOSED, AcademicStatus.UNSUPPORTED)) throw error
            result.put("selection", error.status.name)
        }
        result.remove("running"); result.put("readOnly", true); report(mode, result)
    }
    private suspend fun expired(adapter: PluginAcademicAdapter) {
        adapter.session.cookies.clear()
        try { adapter.validateSession(); fail("Cleared school cookies must not authenticate") }
        catch (error: AcademicException) { assertEquals("Expired session must allow relogin", AcademicStatus.SESSION_EXPIRED, error.status) }
    }
    private fun callback(result: CompletableDeferred<String>) = object : PasswordLoginCallback {
        override fun onSuccess(cookie: String) { result.complete("success") }
        override fun onCaptchaRequired(imageBytes: ByteArray) { result.complete("captcha") }
        override fun onCaptchaInvalid() { result.complete("captcha-invalid") }
        override fun onInvalidCredentials() { result.complete("invalid-credentials") }
        override fun onError(message: String) { result.completeExceptionally(AssertionError("Password gateway: $message")) }
        override fun onWebLoginRequired(message: String) { result.complete("web") }
    }

    @Test fun a_builtinPasswordAndAllReadOnlyQueries() = runBlocking {
        val credentials = credentials(); val school = school()
        val key = AcademicGatewayFactory.accountKey(school, credentials.username)
        val gateway = AcademicPasswordLoginGateway(school)
        val event = CompletableDeferred<String>(); val result = JSONObject().put("mode", "builtin").put("status", "running")
        report("builtin", result)
        try {
            gateway.login(school, credentials.username, credentials.password, callback(event))
            assertEquals("Password login should complete directly", "success", withTimeout(120_000) { event.await() })
            identity(LoginResult(AcademicStatus.SUCCESS, gateway.studentName, gateway.studentId), credentials)
            val adapter = gateway.completedPlugin ?: error("Expected the bundled protocol runtime")
            result.put("passwordLogin", true).put("identityMatched", true).put("provider", adapter.pinned.manifest.id).put("providerVersion", adapter.version)
            readOnly(adapter, result, "builtin")
            expired(adapter); result.put("expiredSessionRejected", true).put("status", "passed"); report("builtin", result)
        } finally { gateway.clearSensitiveState(); AcademicGatewayFactory.invalidate(school, key) }
    }

    @Test fun b_nativeOpaqueCredentialRsaLogin() = runBlocking {
        val credentials = credentials()
        val manifest = PluginManifest(JSONObject().put("id", "local.huel.native-verification").put("name", "HUEL native verification")
            .put("kind", "native").put("apiVersion", 3).put("version", "3.2.1").put("category", "campus")
            .put("contributes", JSONObject().put("pages", JSONArray()).put("entries", JSONArray()))
            .put("permissions", JSONArray(listOf("network", "auth", "credentials")))
            .put("requires", JSONArray().put(JSONObject().put("name", "network.request").put("version", 2)))
            .put("network", JSONArray().put(JSONObject().put("origin", "https://xk.huel.edu.cn").put("pathPrefix", "/jwglxt")
                .put("methods", JSONArray(listOf("GET", "POST"))).put("purposes", JSONArray(listOf("auth", "query"))))))
        val pkg = PluginPackage(manifest, "", "b".repeat(64), false)
        val session = AcademicSession(AcademicSessionKey("plugin:${manifest.id}", "verification"), base)
        val interaction = object : NativePluginInteraction {
            override suspend fun confirm(title: String, message: String) = true
            override suspend fun authenticate(challenge: JSONObject, image: File?) = JSONObject().put("remember", false)
                .put("values", JSONObject().put("username", credentials.username).put("password", credentials.password))
            override suspend fun pick(types: Array<String>): Uri? = null
            override suspend fun notificationPermission() = false
            override fun haptic() {}
            override fun navigate(pageId: String, params: JSONObject) {}
            override fun back() {}
        }
        val host = NativeCapabilityHost(app, pkg, session, interaction) { true }
        val result = JSONObject().put("mode", "native").put("status", "running")
        report("native", result)
        fun effect(name: String, input: JSONObject, version: Int = 1) = JSONObject().put("id", "huel-check")
            .put("capability", name).put("version", version).put("input", input)
        suspend fun request(path: String, body: JSONObject? = null): JSONObject = host.execute(effect("network.request",
            (body ?: JSONObject()).put("url", "$base/$path").put("purpose", "auth"), 2), NativeFlow(true)) as JSONObject
        var handle: String? = null
        try {
            host.requireCompatible()
            val prompt = JSONObject().put("title", "HUEL authorized test").put("key", "huel-test").put("fields", JSONArray()
                .put(JSONObject().put("id", "username").put("label", "Account").put("type", "text"))
                .put(JSONObject().put("id", "password").put("label", "Password").put("type", "password")))
            val grant = host.execute(effect("auth.prompt", prompt), NativeFlow(true)) as JSONObject
            assertEquals(setOf("credential"), grant.keys().asSequence().toSet()); handle = grant.getString("credential")
            val page = request("xtgl/login_slogin.html")
            val form = JSONObject()
            Jsoup.parse(page.getString("body")).select("input[type=hidden][name]").forEach { form.put(it.attr("name"), it.attr("value")) }
            assertEquals("RSA marker from live school page", "1", form.optString("mmsfjm"))
            assertTrue("School CSRF field", form.optString("csrftoken").isNotBlank())
            val key = JSONObject(request("xtgl/login_getPublicKey.html").getString("body"))
            val bound = JSONObject().put("method", "POST").put("credential", handle).put("form", form)
                .put("bindings", JSONObject().put("form", JSONObject().put("yhm", "username")
                    .put("mm", JSONObject().put("from", "password").put("transform", JSONObject().put("type", "rsa-pkcs1")
                        .put("modulus", key.getString("modulus")).put("exponent", key.getString("exponent")).put("encoding", "base64")))))
            val posted = request("xtgl/login_slogin.html", bound)
            assertEquals("Encrypted login response", 200, posted.getInt("status"))
            assertTrue("Callback must stay HTTPS", posted.getString("url").startsWith(base + "/"))
            val adapter = AcademicProviderRegistry.builtinAdapter(school(), session) ?: error("Missing protocol")
            identity(adapter.validateSession(), credentials)
            result.put("opaqueCredential", true).put("rsaPkcs1", true).put("mmsfjm", true).put("identityMatched", true)
            val catalog = adapter.catalog(); result.put("terms", catalog.terms.size).put("schedule", adapter.schedule(catalog.currentTerm).size)
            host.execute(effect("credentials.remove", JSONObject().put("handle", handle)), NativeFlow(true)); handle = null
            assertEquals(JSONObject.NULL, host.execute(effect("credentials.find", JSONObject().put("key", "huel-test")), NativeFlow(true)))
            expired(adapter); result.put("expiredSessionRejected", true).put("status", "passed"); report("native", result)
        } finally {
            handle?.let { host.execute(effect("credentials.remove", JSONObject().put("handle", it)), NativeFlow(true)) }
            host.close(); PluginHost.clearTemporaryState(session); session.retire()
        }
    }

    @Test fun c_schoolPluginBrowserContinuationAndReadOnlyQueries() = runBlocking {
        assumeTrue("Browser result probe is preview-only", BuildConfig.UI_PREVIEW)
        val credentials = credentials()
        val packageFile = InstrumentationRegistry.getArguments().getString("huelPackage")
        require(!packageFile.isNullOrBlank() && packageFile == File(packageFile).name)
        val pkg = AcademicProviderRegistry.packages().install(File(app.cacheDir, packageFile).readBytes(), true)
        AcademicProviderRegistry.reload()
        assertEquals("cn.edu.huel.academic", pkg.manifest.id)
        val school = AcademicProviderRegistry.school(pkg)
        val key = AcademicGatewayFactory.accountKey(school, credentials.username)
        val gateway = AcademicPasswordLoginGateway(school)
        val event = CompletableDeferred<String>()
        val result = JSONObject().put("mode", "school-plugin").put("status", "running")
        val browserResult = File(app.cacheDir, "huel-web-result.private.json")
        browserResult.delete(); report("plugin", result)
        try {
            gateway.login(school, credentials.username, credentials.password, callback(event))
            assertEquals("School plugin must open the official login page", "web", withTimeout(30_000) { event.await() })
            result.put("webLoginRequested", true).put("running", "browser-login"); report("plugin", result)
            app.startActivity(Intent().setClassName(app.packageName, "com.tyust.course.AcademicWebLoginProbeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            fun tap(node: UiObject2) {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                val windows = if (android.os.Build.VERSION.SDK_INT >= 30) automation.windowsOnAllDisplays.let { groups ->
                    (0 until groups.size()).flatMap { groups.valueAt(it) }
                } else automation.windows
                val window = windows.first { it.root?.packageName?.toString() == app.packageName }
                val display = if (android.os.Build.VERSION.SDK_INT >= 30) window.displayId else 0
                val bounds = node.visibleBounds
                device.executeShellCommand("input -d $display tap ${bounds.centerX()} ${bounds.centerY()}")
            }
            withTimeout(30_000) {
                while (device.findObject(By.clazz("android.webkit.WebView"))?.findObjects(By.clazz("android.widget.EditText"))
                        ?.count { it.visibleBounds.height() > 10 } != 2) delay(250)
            }
            val web = device.findObject(By.clazz("android.webkit.WebView"))
            val fields = web.findObjects(By.clazz("android.widget.EditText")).filter { it.visibleBounds.height() > 10 }
            fields[0].text = credentials.username; fields[1].text = credentials.password
            tap(web.findObject(By.text(Pattern.compile("登\\s*录"))) ?: error("School login button is unavailable"))
            assertTrue("Expected the HTTPS school completion page", device.wait(Until.hasObject(By.text(
                Pattern.compile("https://xk\\.huel\\.edu\\.cn/jwglxt/xtgl/index_initMenu\\.html(?:\\?.*)?"))), 30_000))
            tap(device.findObject(By.text("完成登录")) ?: error("Host completion button is unavailable"))
            withTimeout(240_000) { while (!browserResult.exists()) delay(500) }
            val completed = JSONObject(browserResult.readText()); browserResult.delete()
            assertTrue("Browser was cancelled", completed.getBoolean("completed"))
            assertTrue("Browser completion must stay HTTPS", completed.getString("url").startsWith(base + "/xtgl/index_initMenu.html"))
            identity(gateway.resumeWebLogin(completed.getString("cookie"), completed.getString("url")), credentials)
            result.put("httpsCompletion", true).put("identityMatched", true).remove("running")
            val adapter = gateway.completedPlugin ?: error("Missing resumed plugin")
            readOnly(adapter, result, "plugin")
            expired(adapter); result.put("expiredSessionRejected", true).put("status", "passed"); report("plugin", result)
        } finally {
            browserResult.delete(); gateway.clearSensitiveState(); AcademicGatewayFactory.invalidate(school, key)
        }
    }
}

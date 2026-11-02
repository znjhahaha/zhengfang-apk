package com.tyust.course.academic.plugin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
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
 * App134 / SDK3.11.1 / CPDaily0.1.3 UI_PREVIEW draft. Pass -e cpdailyPackage <absolute target external-files path>.
 * Pins the packed delivery via DELIVERY_SHA256 and uses the normal developer importer.
 * Replacing the old local development delivery additionally requires
 * -e cpdailyReplaceSha <PREVIOUS_DELIVERY_SHA256>; accounts are never removed.
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
        fun init() {
            invoke("ui.init", JSONObject().put("pageId", "dorm"))
            assertEquals("cpdaily.schools", effect("services.call").getJSONObject("input").getString("name"))
            // Every seed consumes the one automatic directory effect before sending UI events.
            reply(JSONObject().put("schools", JSONArray().put(JSONObject()
                .put("id", SYNTHETIC_TENANT).put("name", "合成测试学校"))), "services.call")
            assertTrue("Directory initialization must settle without another effect", effects.isEmpty())
            assertEquals("", state.getString("pending"))
        }
        fun event(name: String, value: Any? = null) = reduce(JSONObject().put("type", "click").put("name", name)
            .apply { if (value != null) put("value", value) })
        fun reduce(event: JSONObject) = invoke("ui.reduce", JSONObject().put("pageId", "dorm").put("state", state).put("event", event))
        fun effect(capability: String): JSONObject = effects.single().also { effect ->
            assertEquals(capability, effect.getString("capability"))
            val descriptor = PluginJson.objects(host.capabilities()).single {
                it.getString("name") == capability && effect.getInt("version") in 1..it.getInt("version")
            }
            // Optional prompt/auth fields must be omitted, not serialized as JSON null.
            PluginContractCache.schema(app).validate(effect.getJSONObject("input"), descriptor.getJSONObject("input"))
        }
        fun reply(data: Any?, capability: String) {
            val effect = effect(capability)
            val receipt = if (capability == "services.call")
                (if (data is JSONObject && data.has("value")) JSONObject(data.toString())
                else JSONObject().put("value", data ?: JSONObject.NULL))
                    .put("providerId", pkg.manifest.id).put("digest", pkg.digest)
            else data ?: JSONObject.NULL
            val descriptor = PluginJson.objects(host.capabilities()).single {
                it.getString("name") == capability && effect.getInt("version") in 1..it.getInt("version")
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
        fun cancel(capability: String) {
            val pending = effect(capability)
            reduce(JSONObject().put("type", "effect.result").put("effectId", pending.getString("id"))
                .put("result", PluginJson.error(PluginErrorCode.CANCELLED, "已取消合成认证")))
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
        fun nodes(root: JSONObject = result.getJSONObject("view")): List<JSONObject> = listOf(root) +
            PluginJson.objects(root.optJSONArray("children") ?: JSONArray()).flatMap { nodes(it) }
        fun loginButton(): JSONObject = node(when {
            state.optString("authState") == "otp_required" -> "otp"
            state.optString("loginMethod") == "sso" && state.optString("officialUrl").isNotBlank() -> "official"
            else -> "login"
        })
        fun selectMethod(method: String) {
            val select = nodes().single { node -> node.optString("type") == "select" &&
                PluginJson.objects(node.getJSONArray("options")).map { it.getString("value") }.toSet() ==
                    setOf("mobile", "password", "sso") }
            val label = PluginJson.objects(select.getJSONArray("options")).single {
                it.getString("value") == method
            }.getString("label")
            assertTrue("Unexpected queued Compose event", emitted.isEmpty())
            scroll(select.getString("id"))
            compose.onNode(hasContentDescription(select.getString("label")) and
                hasAnyAncestor(hasTestTag("native-node-${select.getString("id")}")), useUnmergedTree = true)
                .assertIsEnabled().performClick()
            compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assertIsEnabled().performClick()
            compose.waitUntil(5_000) { emitted.isNotEmpty() }
            val event = emitted.remove()
            assertEquals(select.getString("event"), event.getString("name"))
            assertEquals(method, event.getString("value"))
            reduce(event)
            assertEquals(method, state.getString("loginMethod"))
            assertEquals("signed_out", state.getString("authState"))
            assertEquals("", state.getString("continuationId"))
            assertEquals("", state.getString("grant"))
            assertEquals("", state.optString("officialUrl"))
            assertTrue("Selecting a method must not start authentication", effects.isEmpty())
        }
        fun auth(action: String, method: String = "mobile"): JSONObject {
            val effect = effect("auth.service")
            assertEquals(2, effect.getInt("version"))
            return effect.getJSONObject("input").also {
                assertEquals(action, it.getString("action"))
                assertEquals(method, it.getString("method"))
                assertEquals("cpdaily", it.getString("serverId"))
                assertEquals(SYNTHETIC_TENANT, it.getString("tenantId"))
            }
        }
        fun loginCard(button: JSONObject): JSONObject = nodes().filter {
            it.optString("surface") in setOf("glass", "tonal") &&
                NativePluginContract.findNode(it, button.getString("id")) != null
        }.minByOrNull { nodes(it).size } ?: error("Login button must belong to its own card")
        fun account() {
            init()
            event("school", SYNTHETIC_TENANT)
            assertTrue("School selection must not start authentication", effects.isEmpty())
        }
        fun restartLogin() {
            assertEquals("重新开始登录", node("logout").getString("label"))
            assertEquals("plain", node("logout").getString("variant"))
            val method = state.getString("loginMethod")
            click("logout")
            auth("logout", method)
            // The host clears the session; this receipt must only reset the plugin UI.
            reply(JSONObject().put("state", "signed_out"), "auth.service")
            assertTrue("Logout must not emit redundant session.clear or other effects", effects.isEmpty())
            assertEquals("", state.getString("pending"))
            assertEquals("signed_out", state.getString("authState"))
            assertEquals("", state.getString("tenantId"))
            assertEquals("", state.getString("grant"))
            assertEquals("", state.getString("continuationId"))
            assertEquals("", state.optString("officialUrl"))
            assertNoSignedOutBusinessControls()
        }
        fun assertNoSignedOutBusinessControls() {
            val businessEvents = setOf("source", "tasks", "requirements", "materials", "location", "addressLookup", "placeSave",
                "photo", "photoImport", "upload", "scan", "preview", "run", "reconcile", "cancel", "auto.add", "auto.start", "auto.stop")
            val exposed = nodes().filter {
                it.optString("event") in businessEvents &&
                    it.optString("type") in setOf("button", "input", "select", "toggle", "listItem")
            }
            assertTrue("Signed-out account page must hide business controls, including disabled ones: " +
                exposed.joinToString { it.getString("id") }, exposed.isEmpty())
        }
        fun mobileLoginUntil(stage: LoginStage) {
            account()
            selectMethod("mobile")
            val sms = loginButton()
            scroll(sms.getString("id")).assertIsEnabled()
            click(sms.getString("id"))
            auth("start")
            if (stage == LoginStage.AUTH_START) return
            reply(JSONObject().put("state", "scope_required").put("tenantId", SYNTHETIC_TENANT)
                .put("scopes", JSONArray().put(JSONObject().put("origin", "https://school.invalid")
                    .put("pathPrefix", "/api").put("methods", JSONArray(listOf("GET", "POST")))
                    .put("purposes", JSONArray(listOf("auth", "query"))))), "auth.service")
            val scope = effect("network.scope.authorize").getJSONObject("input")
            assertEquals(SYNTHETIC_TENANT, scope.getString("tenantId"))
            if (stage == LoginStage.SCOPE) return
            reply(JSONObject().put("grant", SYNTHETIC_SCOPE), "network.scope.authorize")
            assertEquals(SYNTHETIC_SCOPE, auth("start").getString("scopeGrant"))
            reply(JSONObject().put("state", "signed_out").put("tenantId", SYNTHETIC_TENANT), "auth.service")
            val mobile = effect("auth.prompt").getJSONObject("input")
            assertTrue("Expected a mobile prompt", PluginJson.objects(mobile.getJSONArray("fields")).any {
                it.getString("id") == "mobile" && it.getString("type") == "text"
            })
            if (stage == LoginStage.MOBILE) return
            reply(JSONObject().put("credential", "synthetic-mobile-handle"), "auth.prompt")
            val start = auth("start")
            assertEquals("synthetic-mobile-handle", start.getString("credential"))
            assertEquals(SYNTHETIC_SCOPE, start.getString("scopeGrant"))
            reply(JSONObject().put("state", "otp_required").put("tenantId", SYNTHETIC_TENANT)
                .put("continuationId", SYNTHETIC_CONTINUATION), "auth.service")
            assertTrue("OTP input is an explicit user action", effects.isEmpty())
            click("otp")
            val otp = effect("auth.prompt").getJSONObject("input")
            assertTrue("Expected an OTP prompt", PluginJson.objects(otp.getJSONArray("fields")).any {
                it.getString("id") == "otp" && it.getString("type") == "otp"
            })
        }
        fun ready() {
            init()
            event("school", "synthetic-campus.invalid")
            event("validate")
            reply(JSONObject().put("state", "ready").put("message", "合成学校会话"), "auth.service")
            event("tasks")
            assertEquals("cpdaily.source", effect("services.call").getJSONObject("input").getString("name"))
            reply(JSONObject().put("state", "observed"), "services.call")
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
        check(DELIVERY_SHA256.matches(Regex("[a-f0-9]{64}"))) { "Set DELIVERY_SHA256 to the main agent's packed delivery SHA before building/running" }
        check(DELIVERY_SHA256 != PREVIOUS_DELIVERY_SHA256) { "The new UI tests require the repacked delivery, not the old delivery" }
        assertEquals("Wrong final delivery", DELIVERY_SHA256, PluginJson.sha256(bytes))
        val store = AcademicProviderRegistry.packages()
        val previous = store.active(ID)
        val replacement = InstrumentationRegistry.getArguments().getString("cpdailyReplaceSha")
        check(replacement == null || replacement == PREVIOUS_DELIVERY_SHA256) { "Only the explicitly named old local delivery may be replaced" }
        check(previous == null || previous.digest == DELIVERY_SHA256 ||
            replacement == PREVIOUS_DELIVERY_SHA256 && previous.digest == PREVIOUS_DELIVERY_SHA256 &&
                !previous.official && !previous.bundled) { "Existing CPDaily is preserved; pass cpdailyReplaceSha only for the old local development delivery" }
        val accounts = PluginServiceAccounts(app)
        val retainedAccounts = previous?.manifest?.json?.optJSONArray("servers")?.let(PluginJson::objects).orEmpty().associate {
            val server = it.getString("id")
            server to (accounts.selected(ID, server) to PluginJson.canonical(JSONArray(accounts.accounts(ID, server))))
        }
        val pkg = runBlocking { store.install(bytes, true) }
        try {
            assertEquals(ID, pkg.manifest.id)
            assertTrue("Packed plugin must declare its version", pkg.manifest.version.isNotBlank())
            assertFalse(pkg.official)
            assertEquals("Developer import is staged or blocked; close the old page/check update permissions without clearing accounts",
                pkg.digest, store.activeDigest(ID))
            retainedAccounts.forEach { (server, retained) ->
                assertEquals("Selected service account changed during import", retained.first, accounts.selected(ID, server))
                assertEquals("Service accounts changed during import", retained.second, PluginJson.canonical(JSONArray(accounts.accounts(ID, server))))
            }
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
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("cpdailyCapture", image.absolutePath)
            putString("cpdailyTestTarget", "App134 / SDK3.11.1 / CPDaily0.1.3")
            putString("cpdailyRuntimeVersionCode", BuildConfig.VERSION_CODE.toString())
        })
    }

    @Test fun signedOutAccountHasSchoolAndLoginCardsWithThreeMethodsWithoutBusinessControls() = withPackage { pkg ->
        page(pkg).use { one ->
            render(one); one.account()
            assertEquals("signed_out", one.state.getString("authState"))
            val school = one.node("school.edit")
            one.selectMethod("mobile")
            val sms = one.loginButton()
            val schoolCard = one.loginCard(school)
            val smsCard = one.loginCard(sms)
            assertNotEquals("School selection and selected-method login need separate cards", schoolCard.getString("id"), smsCard.getString("id"))
            assertEquals("school-card", schoolCard.getString("id"))
            assertEquals("login-card", smsCard.getString("id"))
            assertNull(NativePluginContract.findNode(schoolCard, sms.getString("id")))
            assertNull(NativePluginContract.findNode(smsCard, school.getString("id")))
            one.assertNoSignedOutBusinessControls()
            one.scroll(school.getString("id")).assertIsEnabled()
            capture("account-school-card")
            one.scroll(sms.getString("id")).assertIsEnabled()
            assertTrue(sms.getString("label").contains("验证码"))
            capture("account-sms-card")
            assertTrue(one.effects.isEmpty())

            one.selectMethod("password")
            one.scroll("login").assertIsEnabled()
            capture("account-password-card")
            one.click("login")
            one.auth("start", "password")
            one.reply(JSONObject().put("state", "scope_required").put("tenantId", SYNTHETIC_TENANT)
                .put("scopes", JSONArray().put(JSONObject().put("origin", "https://school.invalid")
                    .put("pathPrefix", "/cas").put("methods", JSONArray(listOf("GET", "POST")))
                    .put("purposes", JSONArray(listOf("auth"))))), "auth.service")
            assertEquals(SYNTHETIC_TENANT, one.effect("network.scope.authorize").getJSONObject("input").getString("tenantId"))
            one.reply(JSONObject().put("grant", SYNTHETIC_SCOPE), "network.scope.authorize")
            assertEquals(SYNTHETIC_SCOPE, one.auth("start", "password").getString("scopeGrant"))
            one.reply(JSONObject().put("state", "signed_out").put("tenantId", SYNTHETIC_TENANT)
                .put("continuationId", "synthetic-password-continuation"), "auth.service")
            val fields = PluginJson.objects(one.effect("auth.prompt").getJSONObject("input").getJSONArray("fields"))
            assertTrue(fields.any { it.getString("id") == "username" && it.getString("type") == "text" })
            assertTrue(fields.any { it.getString("id") == "password" && it.getString("type") == "password" })
            one.reply(JSONObject().put("credential", "synthetic-password-handle"), "auth.prompt")
            val password = one.auth("resume", "password")
            assertEquals("synthetic-password-handle", password.getString("credential"))
            assertEquals("synthetic-password-continuation", password.getString("continuationId"))
            assertEquals(SYNTHETIC_SCOPE, password.getString("scopeGrant"))
            one.cancel("auth.service")
            assertTrue(one.effects.isEmpty())

            one.selectMethod("sso")
            one.scroll("login").assertIsEnabled()
            capture("account-sso-card")
            one.click("login")
            one.auth("start", "sso")
            // Official navigation only: no browser session or callback exchange is dispatched.
            one.reply(JSONObject().put("state", "official_required").put("tenantId", SYNTHETIC_TENANT)
                .put("officialUrl", "https://school.invalid/cas/login"), "auth.service")
            // auth -> official navigation happens immediately, without a second entry click.
            assertEquals("https://school.invalid/cas/login", one.effect("navigation.url").getJSONObject("input").getString("url"))
            one.reply(null, "navigation.url")
            assertEquals("official_required", one.state.getString("authState"))
            assertEquals("primary", one.node("official").getString("variant"))
            assertNull("SSO official entry must replace the duplicate login action",
                NativePluginContract.findNode(one.result.getJSONObject("view"), "login"))
            one.assertNoSignedOutBusinessControls()
            assertTrue("Opening official entry must not imply SSO completion", one.effects.isEmpty())
            one.scroll("official").assertIsEnabled()
            capture("account-sso-official-entry")
            one.restartLogin()
            capture("account-sso-restarted")
        }
    }

    @Test fun syntheticMobileLoginContinuesThroughScopeMobileAndOtpWithoutDispatch() = withPackage { pkg ->
        page(pkg).use { one ->
            render(one); one.mobileLoginUntil(LoginStage.OTP)
            one.reply(JSONObject().put("credential", "synthetic-otp-handle"), "auth.prompt")
            val resume = one.auth("resume")
            assertEquals(SYNTHETIC_TENANT, resume.getString("tenantId"))
            assertEquals(SYNTHETIC_SCOPE, resume.getString("scopeGrant"))
            assertEquals(SYNTHETIC_CONTINUATION, resume.getString("continuationId"))
            assertEquals("synthetic-otp-handle", resume.getString("credential"))
            one.reply(JSONObject().put("state", "ready").put("tenantId", SYNTHETIC_TENANT)
                .put("message", "合成短信登录完成；未发送短信或认证请求"), "auth.service")
            assertEquals("ready", one.state.getString("authState"))
            assertTrue("Login must not automatically query or write business data", one.effects.isEmpty())
            one.event("page", "account")
            capture("account-synthetic-ready")
        }
    }

    private fun cancelledMobileLogin(stage: LoginStage, capability: String) = withPackage { pkg ->
        page(pkg).use { one ->
            render(one); one.mobileLoginUntil(stage)
            one.cancel(capability)
            assertTrue("Cancellation must not continue authentication", one.effects.isEmpty())
            assertNotEquals("Cancellation must not create an authenticated session", "ready", one.state.getString("authState"))
            one.assertNoSignedOutBusinessControls()
            one.scroll(one.loginButton().getString("id")).assertIsEnabled()
            capture("account-cancel-${stage.name.lowercase(java.util.Locale.ROOT)}")
            one.click(one.loginButton().getString("id"))
            if (stage == LoginStage.OTP) {
                assertEquals("OTP cancellation must preserve the current continuation", SYNTHETIC_CONTINUATION,
                    one.state.getString("continuationId"))
                one.effect("auth.prompt")
                one.cancel("auth.prompt")
                // The method picker remains usable while awaiting OTP, including after cancellation.
                one.selectMethod("password")
                assertNull(NativePluginContract.findNode(one.result.getJSONObject("view"), "otp"))
                one.scroll("login").assertIsEnabled()
                capture("account-otp-switched-to-password")
                one.click("login")
                val password = one.auth("start", "password")
                assertFalse(password.has("continuationId"))
                assertFalse(password.has("credential"))
                assertFalse(password.has("scopeGrant"))
                one.cancel("auth.service")
                // Seed another synthetic OTP flow to verify restart at the continuation boundary.
                one.mobileLoginUntil(LoginStage.OTP)
                one.cancel("auth.prompt")
                one.restartLogin()
                capture("account-otp-restarted")
            } else {
                one.auth("start")
                one.cancel("auth.service")
                if (stage == LoginStage.MOBILE) {
                    one.restartLogin()
                    capture("account-authorized-restarted")
                }
            }
            assertTrue(one.effects.isEmpty())
        }
    }

    @Test fun syntheticAuthStartCancellationLeavesSmsRetryEnabled() = cancelledMobileLogin(LoginStage.AUTH_START, "auth.service")
    @Test fun syntheticScopeCancellationLeavesSmsRetryEnabled() = cancelledMobileLogin(LoginStage.SCOPE, "network.scope.authorize")
    @Test fun syntheticMobilePromptCancellationLeavesSmsRetryEnabled() = cancelledMobileLogin(LoginStage.MOBILE, "auth.prompt")
    @Test fun syntheticOtpPromptCancellationPreservesContinuationAndInputRetry() = cancelledMobileLogin(LoginStage.OTP, "auth.prompt")

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
            assertNull(NativePluginContract.findNode(one.result.getJSONObject("view"), "auto.start"))
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
            assertNull(NativePluginContract.findNode(one.result.getJSONObject("view"), "run"))
            compose.waitForIdle()
            assertTrue("Unknown execution must expose no event", emitted.isEmpty())
            assertTrue(one.effects.isEmpty())
            assertTrue(one.node("reconcile").getBoolean("enabled"))
            capture("unknown-workflow")
            one.click("cancel")
            one.reply(JSONObject(one.state.getJSONObject("workflow").toString()).put("cancelled", true), "workflow.cancel")
            assertTrue(one.state.getJSONObject("workflow").getBoolean("cancelled"))
            assertEquals("unknown", one.state.getJSONObject("workflow").getJSONArray("steps").getJSONObject(0).getString("status"))
            assertNull(NativePluginContract.findNode(one.result.getJSONObject("view"), "run"))
        }
    }

    private enum class LoginStage { AUTH_START, SCOPE, MOBILE, OTP }

    companion object {
        private const val ID = "dev.znjhahaha.cpdaily"
        private const val PREVIOUS_DELIVERY_SHA256 = "b5ac3fd78a31d7a1d0113ee124e02925722276978c09904867fadd2f3109140a"
        // Main agent must replace this after packing; never accept a caller-selected new digest.
        private const val DELIVERY_SHA256 = "a7546dbdb3f542d08f03fcb0a58638dc906eafdb43b1562dbe25c14d9a3bcccc"
        private const val SYNTHETIC_TENANT = "synthetic-campus.invalid"
        private const val SYNTHETIC_SCOPE = "synthetic-school-scope"
        private const val SYNTHETIC_CONTINUATION = "synthetic-otp-continuation"
    }
}

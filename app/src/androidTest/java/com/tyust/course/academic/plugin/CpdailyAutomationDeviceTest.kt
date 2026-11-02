package com.tyust.course.academic.plugin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.*
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
 * App135 / SDK3.11.1 / CPDaily0.1.4 UI_PREVIEW draft. Pass -e cpdailyPackage <absolute target external-files path>.
 * Pins the packed delivery via DELIVERY_SHA256 and uses the normal developer importer.
 * Replacing the old local development delivery additionally requires
 * -e cpdailyReplaceSha <PREVIOUS_DELIVERY_SHA256>; accounts are never removed.
 * Real: Android QuickJS ui.init/ui.reduce, contract validation, Compose renderer, scoped
 * storage, file handles and denied foreground-start confirmation. Synthetic: school,
 * auth, requirements, map/GPS/profile, picker result and workflow/foreground results.
 * Restoration also dispatches auth.service.validate through the real host to a
 * test-only QuickJS handler using an isolated legal service session. No school
 * network/upload/workflow write or foreground worker is ever dispatched.
 * This deliberately does not claim school acceptance or automatic task.run coverage.
 */
@RunWith(AndroidJUnit4::class)
class CpdailyAutomationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val shown = mutableStateOf<JSONObject?>(null)
    private val emitted = LinkedBlockingQueue<JSONObject>()
    private val scopeInteraction = PageInteraction()
    private val activePage = mutableStateOf<Page?>(null)
    private var exits = 0

    @Before fun previewOnly() {
        assumeTrue("Requires UI_PREVIEW", BuildConfig.UI_PREVIEW)
        assertEquals("This test delivery targets App135", 135, BuildConfig.VERSION_CODE)
    }

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

    private inner class Page(val pkg: PluginPackage, val session: AcademicSession, scopeUi: Boolean = false) : AutoCloseable {
        val interaction = Interaction()
        val host = NativeCapabilityHost(app, pkg, session, if (scopeUi) scopeInteraction else interaction) { true }
        var result = JSONObject()
        private val outstanding = linkedMapOf<String, JSONObject>()
        var autoLocalWrites = true
        private var settlingLocal = false
        init { host.cancelEffects = { ids -> ids.forEach(outstanding::remove) } }
        val effects get() = outstanding.values.toList()
        val state get() = result.getJSONObject("state")
        fun invoke(method: String, args: JSONObject) {
            if (method == "ui.init") outstanding.clear()
            args.optJSONObject("event")?.takeIf { it.optString("type") == "effect.result" }
                ?.let { outstanding.remove(it.getString("effectId")) }
            result = runBlocking {
                NativePluginRunner.invoke(app, pkg, session, method, args,
                    JSONObject().put("capabilities", host.capabilities())) { true }
            }
            PluginJson.objects(result.getJSONArray("effects")).forEach { outstanding[it.getString("id")] = it }
            compose.runOnIdle { shown.value = result.getJSONObject("view") }
            // Preference/catalog writes may accompany a UI or business result. Dispatch only
            // local writes and keep all other outstanding effects for explicit test replies.
            settleLocal()
        }
        fun settleLocal() {
            if (!autoLocalWrites || settlingLocal) return
            settlingLocal = true
            try {
                while (true) {
                    val next = effects.firstOrNull { it.getString("capability") in setOf("storage.set", "runtime.cancel") } ?: break
                    local(next.getString("capability"))
                }
            } finally { settlingLocal = false }
        }
        fun init(pageId: String = "dorm") {
            invoke("ui.init", JSONObject().put("pageId", pageId))
            assertEquals("cpdaily.ui.preferences.v1", effect("storage.get").getJSONObject("input").getString("key"))
            local()
            assertEquals("cpdaily.ui.catalog.v1", effect("storage.get").getJSONObject("input").getString("key"))
            local()
            if (effects.any { it.getString("capability") == "services.call" }) {
                assertEquals("cpdaily.schools", effect("services.call").getJSONObject("input").getString("name"))
                reply(JSONObject().put("schools", JSONArray().put(JSONObject()
                    .put("id", SYNTHETIC_TENANT).put("name", "合成测试学校"))), "services.call")
            }
        }
        fun event(name: String, value: Any? = null) = reduce(JSONObject().put("type", "click").put("name", name)
            .apply { if (value != null) put("value", value) })
        fun reduce(event: JSONObject) = invoke("ui.reduce", JSONObject().put("pageId", "dorm").put("state", state).put("event", event))
        fun effect(capability: String): JSONObject = effects.single { it.getString("capability") == capability }.also { effect ->
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
        fun local(capability: String = "storage.get") {
            val effect = effect(capability)
            check(effect.getString("capability") in setOf("storage.get", "storage.set", "runtime.cancel")) { "Only local storage/cancellation may be dispatched" }
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
        fun tab(value: String) {
            val options = PluginJson.objects(node("service-tabs").getJSONArray("options"))
            val label = options.single { it.getString("value") == value }.getString("label")
            // Glass tabs also draw a noninteractive label layer for refraction. Select
            // the merged actionable tab, not either copy of its unmerged label text.
            compose.onNode(hasText(label) and hasClickAction() and isSelectable() and
                hasAnyAncestor(hasTestTag("native-node-service-tabs")))
                .assertIsEnabled().performClick()
            compose.waitUntil(5_000) { emitted.isNotEmpty() }
            val event = emitted.remove()
            assertEquals("page", event.getString("name"))
            assertEquals("input", event.getString("type"))
            assertEquals(value, event.getString("value"))
            reduce(event)
            assertEquals(value, state.getString("page"))
        }
        fun back() {
            compose.onNodeWithContentDescription("返回").performClick()
            consumeUiEvent()
        }
        fun consumeUiEvent() {
            compose.waitUntil(5_000) { emitted.isNotEmpty() }
            reduce(emitted.remove())
        }
        fun scroll(id: String): SemanticsNodeInteraction {
            if (id != "service-tabs") compose.onNodeWithTag("native-node-page-content")
                .performScrollToNode(hasTestTag("native-node-$id"))
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
            val id = if (state.optString("loginMethod") == method) "login" else "login-$method"
            assertEquals("listItem", node(id).getString("type"))
            click(id)
            auth("start", method)
            cancel("auth.service")
            assertEquals(method, state.getString("loginMethod"))
            assertEquals("", state.getString("continuationId"))
            assertEquals("", state.optString("officialUrl"))
            assertTrue("Cancelled login operation must settle", effects.isEmpty())
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
            if (effects.any { it.getString("capability") == "auth.service" }) cancel("auth.service")
            event("school", SYNTHETIC_TENANT)
            assertTrue("School selection must not start authentication", effects.isEmpty())
        }
        fun restartLogin() {
            assertEquals("退出当前账号", node("logout").getString("title"))
            assertEquals("listItem", node("logout").getString("type"))
            val method = state.getString("loginMethod")
            click("logout")
            auth("logout", method)
            // The host clears the session; this receipt must only reset the plugin UI.
            reply(JSONObject().put("state", "signed_out"), "auth.service")
            assertTrue("Logout must not emit redundant session.clear or other effects", effects.isEmpty())
            assertEquals("", state.getString("pending"))
            assertEquals("signed_out", state.getString("authState"))
            assertEquals(SYNTHETIC_TENANT, state.getString("tenantId"))
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
            if (state.getString("tenantId").isBlank()) event("school", SYNTHETIC_TENANT)
            if (effects.none { it.getString("capability") == "auth.service" }) event("validate")
            auth("validate", state.getString("loginMethod"))
            reply(JSONObject().put("state", "ready").put("message", "合成学校会话"), "auth.service")
            if (effects.isEmpty()) event("tasks")
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
            dispose(clearStorage = true)
        }
        fun dispose(clearStorage: Boolean) {
            runBlocking {
                if (clearStorage) for (key in listOf("cpdaily.locations.v1", "cpdaily.ui.preferences.v1", "cpdaily.ui.catalog.v1"))
                    host.execute(JSONObject().put("id", "cleanup").put("capability", "storage.remove").put("version", 1)
                        .put("input", JSONObject().put("key", key)), NativeFlow(false))
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
            if (store.activeDigest(ID) != pkg.digest &&
                InstrumentationRegistry.getArguments().getString("cpdailyApproveRuntimeUpdate") == DELIVERY_SHA256) {
                // Explicit approval is limited to this reviewed local delivery's missing
                // cancellation declaration. Keep the normal staged-update transaction.
                check(previous?.digest == PREVIOUS_DELIVERY_SHA256 && replacement == PREVIOUS_DELIVERY_SHA256)
                check(previous != null && !previous.official && !previous.bundled)
                check(pkg.source == previous.source) { "Runtime approval cannot approve a source change" }
                check(pkg.manifest.permissions - previous.manifest.permissions == setOf("runtime"))
                check(previous.manifest.permissions - pkg.manifest.permissions == emptySet<String>())
                val declared = JSONObject(pkg.manifest.json.toString())
                declared.put("permissions", JSONArray(pkg.manifest.permissions.filter { it != "runtime" }))
                declared.put("requires", JSONArray(PluginJson.objects(declared.getJSONArray("requires"))
                    .filterNot { it.optString("name") == "runtime.cancel" }))
                assertEquals("Only the declared runtime cancellation update was approved",
                    PluginJson.canonical(previous.manifest.json), PluginJson.canonical(declared))
                assertEquals(DELIVERY_SHA256, store.activateStaged(ID, allowExpanded = true,
                    expectedDigest = DELIVERY_SHA256)?.digest)
            }
            assertEquals(ID, pkg.manifest.id)
            assertEquals("0.1.4", pkg.manifest.version)
            assertEquals(135, pkg.manifest.json.getInt("minAppVersionCode"))
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
        activePage.value = page
        compose.setContent {
            CourseSelectorTheme { GlassWindowHost {
                activePage.value?.let { current -> shown.value?.let {
                    NativePluginPageSurface(NativeUiSnapshot(pageId = "dorm", instance = "synthetic-${current.session.instanceId}",
                        view = it, navigation = NativePageNavigation.from(current.result)), current.host.files,
                        "合成今日校园验收", true, Modifier, onExit = { exits++ }, onRetry = {},
                        emit = { event, _ -> emitted.add(event) })
                } }
                ScopePrompt()
            } }
        }
    }

    @Composable private fun ScopePrompt() {
        scopeInteraction.prompt?.let { PluginScopeConsentDialog(it) }
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
            putString("cpdailyTestTarget", "App135 / SDK3.11.1 / CPDaily0.1.4")
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
            assertEquals("listItem", sms.getString("type"))
            assertTrue(sms.getString("title").contains("验证码"))
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
            assertEquals("listItem", one.node("official").getString("type"))
            assertEquals("login-card", one.loginCard(one.node("official")).getString("id"))
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
                // OTP is its own operation row; exit the continuation before another login method.
                one.restartLogin()
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

    @Test fun fixedTabsAndIndependentSchoolPickerNavigateBackThroughEveryInternalLevel() = withPackage { pkg ->
        page(pkg).use { one ->
            render(one); one.ready()
            assertEquals("column", one.result.getJSONObject("view").getString("type"))
            assertEquals("scroll", one.node("page-content").getString("type"))
            assertNull(NativePluginContract.findNode(one.node("page-content"), "service-tabs"))
            val tabs = compose.onNodeWithTag("native-node-service-tabs").fetchSemanticsNode().boundsInRoot
            one.scroll("preview")
            assertEquals(tabs, compose.onNodeWithTag("native-node-service-tabs").fetchSemanticsNode().boundsInRoot)
            one.event("stage", "results")
            for ((stage, depth) in listOf("materials" to 3, "details" to 2, "tasks" to 1)) {
                one.back()
                assertEquals(stage, one.state.getString("stage"))
                assertEquals(depth, NativePageNavigation.from(one.result)!!.depth)
                assertEquals(0, exits)
            }
            one.back()
            assertEquals("account", one.state.getString("page"))
            assertEquals(0, NativePageNavigation.from(one.result)!!.depth)
            // Saved-school editing now uses a listItem and an independent picker page.
            assertEquals("listItem", one.node("school.edit").getString("type"))
            assertEquals("合成测试学校", one.node("school.edit").getString("title"))
            one.click("school.edit")
            assertTrue(one.state.getBoolean("schoolEditing"))
            assertNull(NativePluginContract.findNode(one.result.getJSONObject("view"), "service-tabs"))
            assertNull(NativePluginContract.findNode(one.result.getJSONObject("view"), "login-card"))
            assertEquals("listItem", one.node("recent-school-0").getString("type"))
            one.input("search", "合成")
            one.back()
            assertFalse(one.state.getBoolean("schoolEditing"))
            assertEquals(SYNTHETIC_TENANT, one.state.getString("tenantId"))
            assertEquals("", one.state.getString("search"))
            one.tab("classroom"); one.back()
            assertEquals("account", one.state.getString("page"))
            assertEquals(0, exits)
            capture("internal-back-and-saved-school")
            compose.onNodeWithContentDescription("返回").performClick()
            compose.runOnIdle { assertEquals(1, exits) }
        }
    }

    @Test fun leavingAndReopeningRestoresSchoolMethodAndPageButVerifiesAuthAgain() = withPackage { pkg ->
        val account = UUID.randomUUID().toString()
        val first = page(pkg, account)
        try {
            render(first); first.account(); first.selectMethod("password"); first.tab("classroom")
            first.event("validate")
            first.reply(JSONObject().put("state", "ready").put("message", "合成会话；不是学校登录验收"), "auth.service")
            first.event("reason", "不应保存的合成材料")
            compose.runOnIdle { shown.value = null; activePage.value = null }
            compose.waitForIdle()
        } finally { first.dispose(clearStorage = false) }
        page(pkg, account).use { reopened ->
            compose.runOnIdle { activePage.value = reopened }
            reopened.init("account")
            assertEquals(SYNTHETIC_TENANT, reopened.state.getString("tenantId"))
            assertEquals("合成测试学校", reopened.state.getString("schoolName"))
            assertEquals("password", reopened.state.getString("loginMethod"))
            assertEquals("classroom", reopened.state.getString("page"))
            assertEquals("signed_out", reopened.state.getString("authState"))
            assertEquals("", reopened.state.getString("grant"))
            assertEquals("", reopened.state.getString("reason"))
            assertTrue(reopened.state.isNull("photo")); assertTrue(reopened.state.isNull("workflow"))
            val restore = reopened.auth("validate", "password")
            assertFalse(restore.has("credential")); assertFalse(restore.has("scopeGrant"))
            reopened.reply(JSONObject().put("state", "signed_out").put("message", "合成过期会话"), "auth.service")
            assertTrue("Reopening cannot request a password or send SMS automatically", reopened.effects.isEmpty())
            assertEquals("合成测试学校", reopened.node("school.edit").getString("title"))
            reopened.click("school.edit")
            assertEquals("listItem", reopened.node("recent-school-0").getString("type"))
            reopened.click("recent-school-0")
            assertFalse(reopened.state.getBoolean("schoolEditing"))
            capture("reopened-public-preferences")
        }
    }

    @Test fun restoredValidateReachesProtectedQuickJsWithoutGestureButLoginStillRequiresGesture() = withPackage { delivered ->
        val id = "device.test.cpdaily.restore." + UUID.randomUUID().toString().replace("-", "")
        // Keep the delivered reducer intact; replace only protected school handlers.
        // The real host, service-account validation and Binder/QuickJS dispatch all run.
        val source = delivered.source + """
            ;globalThis.plugin.serviceAuth = {
                validate: (args) => {
                    if (args.serverId !== 'cpdaily' || args.tenantId !== '$SYNTHETIC_TENANT' ||
                        args.method !== 'password' || 'action' in args || 'credential' in args || 'scopeGrant' in args)
                        throw new Error('Unexpected restoration input');
                    return {ok:true,data:{state:'ready',tenantId:args.tenantId,message:'synthetic host validate only'}};
                },
                start: () => { throw new Error('Ungestured start reached handler'); },
                resume: () => { throw new Error('Ungestured resume reached handler'); },
                logout: () => { throw new Error('Ungestured logout reached handler'); }
            };
        """.trimIndent()
        val pkg = delivered.copy(manifest = PluginManifest(JSONObject(delivered.manifest.json.toString()).put("id", id)),
            source = source, digest = PluginJson.sha256(source.toByteArray(Charsets.UTF_8)))
        val accounts = PluginServiceAccounts(app)
        var first: Page? = null
        try {
            val one = Page(pkg, accounts.session(pkg, "cpdaily")).also { first = it }
            render(one); one.account(); one.selectMethod("password"); one.tab("account")
            compose.runOnIdle { shown.value = null; activePage.value = null }
            compose.waitForIdle()
            one.dispose(clearStorage = false); first = null
            Page(pkg, accounts.session(pkg, "cpdaily")).use { reopened ->
                compose.runOnIdle { activePage.value = reopened }
                reopened.init("account")
                val input = reopened.auth("validate", "password")
                assertEquals("account", reopened.state.getString("page"))
                assertEquals("signed_out", reopened.state.getString("authState"))
                assertTrue(reopened.state.getBoolean("restoreSession"))
                val restore = reopened.effect("auth.service")
                assertTrue(restore.getString("id").startsWith("restoreAuth."))
                assertEquals("service:$id:cpdaily", reopened.session.key.schoolId)
                assertTrue(accounts.current(pkg, reopened.session))
                assertFalse(input.has("credential")); assertFalse(input.has("scopeGrant"))
                for (action in listOf("start", "resume", "logout")) {
                    val forbidden = JSONObject(restore.toString())
                    forbidden.getJSONObject("input").put("action", action)
                    try {
                        runBlocking { reopened.host.execute(forbidden, NativeFlow(false)) }
                        fail("Ungestured $action must be denied before dispatch")
                    } catch (error: PluginException) { assertEquals(PluginErrorCode.PERMISSION_DENIED, error.code) }
                }
                val receipt = runBlocking { reopened.host.execute(restore, NativeFlow(false)) } as JSONObject
                assertEquals("ready", receipt.getString("state"))
                assertEquals("synthetic host validate only", receipt.getString("message"))
                reopened.reply(receipt, "auth.service")
                assertEquals("ready", reopened.state.getString("authState"))
                assertFalse(reopened.state.getBoolean("restoreSession"))
                assertEquals("", reopened.state.getString("pending"))
                assertTrue("Read-only restoration must not prompt, log in or start school work", reopened.effects.isEmpty())
                assertEquals(0, reopened.interaction.confirmations)
                assertNull(scopeInteraction.prompt)
                capture("restored-validate-without-gesture")
            }
        } finally {
            first?.close()
            accounts.clearPlugin(pkg)
        }
    }

    @Test fun quickJsScopeEffectShowsExpandableDetailsAndReusesOnlyRememberedScopeAfterReopen() = withPackage { delivered ->
        // Reuse the packed production reducer with an isolated synthetic plugin identity.
        // This keeps consent/account cleanup away from the user's actual CPDaily accounts.
        val id = "device.test.cpdaily.scope." + UUID.randomUUID().toString().replace("-", "")
        val pkg = delivered.copy(manifest = PluginManifest(JSONObject(delivered.manifest.json.toString()).put("id", id)))
        val accounts = PluginServiceAccounts(app)
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val rules = JSONArray().put(JSONObject().put("origin", "https://school.example.invalid")
            .put("pathPrefix", "/api/login").put("methods", JSONArray(listOf("GET", "POST")))
            .put("purposes", JSONArray(listOf("auth"))))
            .put(JSONObject().put("origin", "https://school.example.invalid")
                .put("pathPrefix", "/api/tasks").put("methods", JSONArray(listOf("GET")))
                .put("purposes", JSONArray(listOf("query"))))
        fun scopeReply(one: Page) = one.reply(JSONObject().put("state", "scope_required").put("tenantId", SYNTHETIC_TENANT)
            .put("scopes", rules), "auth.service")
        var first: Page? = null
        try {
            val one = Page(pkg, accounts.session(pkg, "cpdaily"), scopeUi = true).also { first = it }
            render(one); one.account(); one.click("login"); scopeReply(one)
            val pending = one.effect("network.scope.authorize")
            val decision = jobs.async { one.host.execute(pending, NativeFlow(true)) as JSONObject }
            compose.waitUntil(10_000) { scopeInteraction.prompt != null || decision.isCompleted }
            assertFalse("New scope must await visible consent", decision.isCompleted)
            val disclosure = scopeInteraction.prompt!!.message
            compose.onNodeWithText(disclosure).assertDoesNotExist()
            compose.onNodeWithText("本次申请 1 个站点、2 项网络范围。").assertIsDisplayed()
            compose.onNodeWithText("查看完整授权详情（站点、路径、方法与用途）").performScrollTo().performClick()
            compose.onNodeWithText(disclosure).assertExists()
            assertTrue(disclosure.contains("https://school.example.invalid/api/login"))
            assertTrue(disclosure.contains("GET、POST")); assertTrue(disclosure.contains("登录认证"))
            assertTrue(disclosure.contains("/api/tasks")); assertTrue(disclosure.contains("读取任务与要求"))
            assertTrue(disclosure.contains("撤销学校网络授权"))
            assertFalse(decision.isCompleted)
            capture("school-scope-expanded")
            compose.onNodeWithText("允许并记住").performClick()
            compose.waitUntil(10_000) { decision.isCompleted }
            val grant = runBlocking { decision.await() }
            assertTrue(PluginSchoolNetworkConsent.remembered(app, id))
            one.reply(grant, "network.scope.authorize")
            assertEquals(grant.getString("grant"), one.auth("start").getString("scopeGrant"))
            one.cancel("auth.service")
            compose.runOnIdle { shown.value = null; activePage.value = null }
            compose.waitForIdle()
            one.dispose(clearStorage = false); first = null
            Page(pkg, accounts.session(pkg, "cpdaily"), scopeUi = true).use { reopened ->
                compose.runOnIdle { activePage.value = reopened }
                reopened.init("account")
                reopened.auth("validate")
                reopened.reply(JSONObject().put("state", "signed_out"), "auth.service")
                assertTrue("Restore must not start login or request scope automatically", reopened.effects.isEmpty())
                reopened.click("login"); reopened.auth("start"); scopeReply(reopened)
                val reuse = jobs.async { reopened.host.execute(reopened.effect("network.scope.authorize"), NativeFlow(false)) as JSONObject }
                compose.waitUntil(10_000) { reuse.isCompleted }
                val next = runBlocking { reuse.await() }
                assertNotEquals(grant.getString("grant"), next.getString("grant"))
                assertNull(scopeInteraction.prompt)
                reopened.reply(next, "network.scope.authorize"); reopened.cancel("auth.service")
                val unknown = JSONObject(pending.toString())
                unknown.getJSONObject("input").put("rules", JSONArray().put(JSONObject(rules.getJSONObject(0).toString())
                    .put("origin", "https://unapproved.example.invalid")))
                try { runBlocking { reopened.host.execute(unknown, NativeFlow(false)) }; fail("Unknown origin silently approved") }
                catch (error: PluginException) { assertEquals(PluginErrorCode.PERMISSION_DENIED, error.code) }
                assertNull(scopeInteraction.prompt)
                capture("school-scope-reused")
            }
        } finally {
            jobs.cancel(); scopeInteraction.prompt?.result?.cancel()
            first?.close()
            accounts.clearPlugin(pkg); PluginSchoolNetworkConsent.revoke(app, id)
        }
    }

    @Test fun switchingTabsAndGoingBackDuringStartupKeepPreferenceReadAndAuthRestorationAlive() = withPackage { pkg ->
        val account = UUID.randomUUID().toString()
        val first = page(pkg, account)
        try {
            render(first); first.account(); first.tab("dorm")
            compose.runOnIdle { shown.value = null; activePage.value = null }
            compose.waitForIdle()
        } finally { first.dispose(clearStorage = false) }
        page(pkg, account).use { reopened ->
            compose.runOnIdle { activePage.value = reopened }
            reopened.invoke("ui.init", JSONObject().put("pageId", "account"))
            val preferencesRead = reopened.state.getString("pending")
            reopened.tab("classroom")
            assertEquals(preferencesRead, reopened.state.getString("pending"))
            assertEquals("storage.get", reopened.effects.single().getString("capability"))
            reopened.local()
            val catalogRead = reopened.state.getString("pending")
            reopened.back()
            assertEquals(catalogRead, reopened.state.getString("pending"))
            assertEquals("account", reopened.state.getString("page"))
            reopened.local()
            val restoreAuth = reopened.state.getString("pending")
            reopened.auth("validate")
            reopened.tab("classroom")
            assertEquals(restoreAuth, reopened.state.getString("pending"))
            assertTrue(reopened.state.getBoolean("restoreSession"))
            reopened.reply(JSONObject().put("state", "ready"), "auth.service")
            assertEquals("classroom", reopened.state.getString("page"))
            assertEquals("cpdaily.source", reopened.effect("services.call").getJSONObject("input").getString("name"))
            reopened.reply(JSONObject().put("state", "observed"), "services.call")
            reopened.reply(JSONObject().put("rows", JSONArray()), "services.call")
            assertTrue(reopened.state.getBoolean("tasksLoaded"))
            assertTrue(reopened.effects.isEmpty())
            assertEquals(0, exits)
            capture("startup-navigation-restores-latest-tab")
        }
    }

    @Test fun sameAccountEpochChangeCannotSwallowAnOutstandingPreferenceWrite() = withPackage { pkg ->
        page(pkg).use { one ->
            render(one); one.account()
            one.autoLocalWrites = false
            one.tab("classroom")
            val stale = one.effect("storage.set").getString("id")
            one.session.invalidate()
            // The stale acknowledgement reaches the real reducer in the new session epoch.
            one.reply(JSONObject.NULL, "storage.set")
            assertNotEquals(stale, one.state.optJSONObject("preferencesWrite")?.optString("id"))
            assertEquals("cpdaily.ui.preferences.v1", one.effect("storage.set").getJSONObject("input").getString("key"))
            assertTrue(one.state.getBoolean("restoreSession"))
            one.auth("validate")
            one.autoLocalWrites = true; one.settleLocal()
            one.reply(JSONObject().put("state", "signed_out"), "auth.service")
            one.tab("leave-new")
            val saved = runBlocking { one.host.execute(JSONObject().put("id", "read-saved-preferences")
                .put("capability", "storage.get").put("version", 1)
                .put("input", JSONObject().put("key", "cpdaily.ui.preferences.v1")), NativeFlow(false)) } as JSONObject
            assertEquals("leave-new", saved.getString("page"))
            assertEquals(SYNTHETIC_TENANT, saved.getString("tenantId"))
            assertFalse(saved.has("authState")); assertFalse(saved.has("grant"))
            assertTrue(one.state.isNull("preferencesWrite"))
            capture("epoch-change-still-saves-preferences")
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
            one.click("placeSave"); one.local()
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
                assertEquals("cpdaily.ui.preferences.v1", other.effect("storage.get").getJSONObject("input").getString("key"))
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
        private const val PREVIOUS_DELIVERY_SHA256 = "227588f7fbe95f883a0a12d01439dfb31a36cab6297084dbd5f51e5fe3fd9817"
        // Main agent must replace this after packing; never accept a caller-selected new digest.
        private const val DELIVERY_SHA256 = "955fa0764ae446b97474c991369d9a5fb0c05632f62c1e9fa883994639e6ecb0"
        private const val SYNTHETIC_TENANT = "synthetic-campus.invalid"
        private const val SYNTHETIC_SCOPE = "synthetic-school-scope"
        private const val SYNTHETIC_CONTINUATION = "synthetic-otp-continuation"
    }
}

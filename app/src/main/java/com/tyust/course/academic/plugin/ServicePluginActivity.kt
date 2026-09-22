package com.tyust.course.academic.plugin

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tyust.course.academic.*
import com.tyust.course.manager.UserManager
import com.tyust.course.ui.system.*
import com.tyust.course.ui.theme.CourseSelectorTheme
import kotlinx.coroutines.*
import org.json.JSONObject

class ServicePluginActivity : ComponentActivity() {
    private var openedScope: String? = null
    private var preview = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CourseSelectorTheme { Screen() } }
    }
    override fun onResume() {
        super.onResume()
        if (!preview && openedScope != null && openedScope != accountScope()) finish()
    }
    @Composable private fun Screen() {
        var runtime by remember { mutableStateOf<ServicePluginSession?>(null) }
        var problem by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            try {
                val pkg = withContext(Dispatchers.IO) { AcademicProviderRegistry.packages().active(intent.getStringExtra("pluginId").orEmpty()) }
                    ?: error("这个校园服务尚未安装")
                require(pkg.manifest.isService) { "这不是校园服务插件" }
                preview = intent.getBooleanExtra("preview", false) && !pkg.official
                val school = UserManager.getInstance().currentSchool
                require(preview || school != null && ServicePluginContract.matches(pkg.manifest, school)) { "请先切换到此服务对应的学校" }
                val scope = if (preview) "preview:${pkg.digest}" else accountScope()
                openedScope = scope
                runtime = ServicePluginSession(this@ServicePluginActivity, pkg, scope) {
                    AcademicProviderRegistry.isEnabled(pkg.manifest.id) && (preview || scope == accountScope())
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { problem = e.message ?: "无法打开校园服务" }
        }
        val current = runtime
        DisposableEffect(current) { onDispose { current?.close() } }
        if (current == null) GlassPageScaffold(title = "校园服务", onBack = { finish() }) { padding ->
            Column(Modifier.padding(padding).padding(24.dp)) {
                if (problem.isBlank()) CircularProgressIndicator() else Text(problem, color = MaterialTheme.colorScheme.error)
            }
        } else ServiceScreen(current)
    }

    @Composable private fun ServiceScreen(runtime: ServicePluginSession) {
        val scope = rememberCoroutineScope()
        val pkg = runtime.pkg
        val config = pkg.manifest.service!!
        val initial = intent.getStringExtra("pageId")?.takeIf { id -> PluginJson.objects(config.getJSONArray("pages")).any { it.getString("id") == id } }
            ?: config.getJSONArray("entries").getJSONObject(0).getString("pageId")
        var page by remember { mutableStateOf<JSONObject?>(null) }
        var history by remember { mutableStateOf(listOf(initial to JSONObject())) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        var loggedIn by remember { mutableStateOf(runtime.authenticated) }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var captcha by remember { mutableStateOf<CaptchaChallenge?>(null) }
        var captchaCode by remember { mutableStateOf("") }
        var pendingAction by remember { mutableStateOf<JSONObject?>(null) }
        var editing by remember { mutableStateOf(false) }
        var layoutRevision by remember { mutableIntStateOf(0) }
        val layout = remember { ServicePageLayout(this, openedScope.orEmpty() + ":" + pkg.manifest.id) }

        fun run(block: suspend () -> Unit) {
            if (busy) return
            busy = true; message = ""
            scope.launch {
                try { block() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: "服务暂时不可用" }
                finally { busy = false; loggedIn = runtime.authenticated; if (!loggedIn) page = null }
            }
        }
        fun load(id: String, params: JSONObject = JSONObject(), push: Boolean = false, pop: Boolean = false) = run {
            val next = withContext(Dispatchers.IO) { runtime.page(id, params) }
            page = next; editing = false
            if (pop) history = history.dropLast(1)
            else if (push) history = history + (id to params)
        }
        val native = rememberServiceNativeActions(runtime, onResult = { result -> run {
            val response = withContext(Dispatchers.IO) { runtime.nativeResult(result) }
            message = response.optString("message").ifBlank { when (result.getString("status")) {
                "success" -> "系统操作已完成"; "opened" -> "已打开系统编辑器，请在其中确认保存"; "cancelled" -> "已取消系统操作"; else -> "系统能力暂时不可用"
            } }
            if (response.getBoolean("confirmed")) response.optJSONObject("page")?.let { next ->
                if (next.getString("pageId") != history.last().first) history = history + (next.getString("pageId") to JSONObject())
                page = next
            }
        } }, onMessage = { message = it })
        fun back() {
            if (busy || native.busy) return
            if (history.size > 1) { val previous = history[history.lastIndex - 1]; load(previous.first, previous.second, pop = true) }
            else finish()
        }
        BackHandler(busy || native.busy || history.size > 1) { back() }
        fun performAction(action: JSONObject, confirmed: Boolean) = run {
            val response = withContext(Dispatchers.IO) { runtime.action(action.getString("actionId"), action.optJSONObject("params") ?: JSONObject(), confirmed) }
            message = response.optString("message").ifBlank { if (response.getBoolean("confirmed")) "操作已完成" else "结果尚未确认，请先查询服务记录" }
            if (response.getBoolean("confirmed")) response.optJSONObject("page")?.let { next ->
                if (next.getString("pageId") != history.last().first) history = history + (next.getString("pageId") to JSONObject())
                page = next
            }
        }
        fun navigate(action: JSONObject) {
            if (busy || native.busy) return
            try {
                ServicePluginContract.validateLink(pkg.manifest, action)
                when (action.getString("type")) {
                    "page" -> load(action.getString("pageId"), action.optJSONObject("params") ?: JSONObject(), push = true)
                    "url" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.getString("url"))))
                    "action" -> if (ServicePluginContract.action(pkg.manifest, action.getString("actionId")).getString("kind") == "mutation") pendingAction = JSONObject(action.toString()) else performAction(action, false)
                    "native" -> native.request(action)
                }
            } catch (e: Exception) { message = e.message ?: "无法打开此操作" }
        }
        LaunchedEffect(runtime) { if (runtime.authenticated) load(initial) }
        GlassPageScaffold(title = page?.getString("title") ?: pkg.manifest.name,
            subtitle = if (preview) "开发预览 · 独立服务会话" else "${pkg.manifest.school.getString("name")} · 校园服务", onBack = ::back) { padding ->
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp,
                top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(if (page?.optString("layout") == "compact") 12.dp else 22.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("service-busy"))
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("service-message"))
                if (!loggedIn) {
                    val auth = config.getJSONObject("authentication")
                    InsetGroupedSection(header = "登录此服务", footer = "服务账号独立于教务账号。密码仅用于本次登录，退出服务后清除会话。") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (auth.optString("help").isNotBlank()) Text(auth.getString("help"), style = MaterialTheme.typography.bodySmall)
                            if (captcha == null) {
                                OutlinedTextField(username, { username = it.take(200) }, label = { Text(auth.optString("usernameLabel", "服务账号")) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("service-username"))
                                OutlinedTextField(password, { password = it.take(1000) }, label = { Text(auth.optString("passwordLabel", "服务密码")) }, singleLine = true, enabled = !busy,
                                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("service-password"))
                            } else {
                                val challenge = captcha!!
                                val bitmap = remember(challenge) { val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(challenge.image, 0, challenge.image.size, options)
                                    if (options.outWidth in 1..2048 && options.outHeight in 1..2048) BitmapFactory.decodeByteArray(challenge.image, 0, challenge.image.size)?.asImageBitmap() else null }
                                bitmap?.let { Image(it, "服务验证码", Modifier.height(60.dp).fillMaxWidth()) }
                                OutlinedTextField(captchaCode, { captchaCode = it.take(32) }, label = { Text("验证码") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("service-captcha"))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    LiquidButton({ run { captcha = withContext(Dispatchers.IO) { runtime.refreshCaptcha() }; captchaCode = "" } }, enabled = !busy) { Text("换一张") }
                                    LiquidButton({ runtime.logout(); captcha = null; captchaCode = "" }, enabled = !busy) { Text("重新登录") }
                                }
                            }
                            LiquidButton({
                                val account = username; val secret = password; val answer = captchaCode; val resuming = captcha != null
                                password = ""
                                run {
                                    val result = withContext(Dispatchers.IO) { if (resuming) runtime.submitCaptcha(answer) else runtime.login(account, secret) }
                                    when (result.status) {
                                        AcademicStatus.SUCCESS -> { captcha = null; captchaCode = ""; page = withContext(Dispatchers.IO) { runtime.page(history.last().first, history.last().second) } }
                                        AcademicStatus.CAPTCHA_REQUIRED -> { captcha = result.captcha ?: withContext(Dispatchers.IO) { runtime.refreshCaptcha() }; captchaCode = ""; message = result.message }
                                        else -> { captcha = null; message = result.message.ifBlank { if (result.status == AcademicStatus.INVALID_CREDENTIALS) "服务账号或密码不正确" else "登录未完成，请重试" } }
                                    }
                                }
                            }, enabled = !busy && (if (captcha == null) username.isNotBlank() && password.isNotEmpty() else captchaCode.isNotBlank()),
                                modifier = Modifier.fillMaxWidth().testTag("service-login"), style = LiquidButtonStyle.Tinted) { Text(if (captcha == null) "登录" else "验证并继续") }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiquidButton({ val current = history.last(); load(current.first, current.second) }, enabled = !busy && !native.busy, modifier = Modifier.weight(1f), horizontalPadding = 8.dp) { Text("刷新") }
                        LiquidButton({ editing = !editing }, enabled = !busy && !native.busy && page != null, modifier = Modifier.weight(1f), horizontalPadding = 8.dp) { Text(if (editing) "完成排布" else "调整排布") }
                        if (runtime.needsLogin) LiquidButton({ runtime.logout(); loggedIn = false; page = null; captcha = null }, enabled = !busy && !native.busy, horizontalPadding = 12.dp) { Text("退出") }
                    }
                    val current = page
                    if (current != null) {
                        if (current.optString("subtitle").isNotBlank()) Text(current.getString("subtitle"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val blocks = remember(current.toString(), layoutRevision) { layout.order(current) }
                        val hidden = remember(current.getString("pageId"), layoutRevision) { layout.hidden(current.getString("pageId")) }
                        if (editing) {
                            InsetGroupedSection(header = "页面模块", footer = "顺序和显示设置只影响当前账号的此插件页面。") {
                                blocks.forEachIndexed { index, block ->
                                    val id = block.getString("id")
                                    InsetGroupedRow(title = block.optString("title").ifBlank { blockName(block.getString("type")) }, showDivider = index < blocks.lastIndex,
                                        trailing = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton({ layout.move(current, id, -1); layoutRevision++ }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "上移模块") }
                                                IconButton({ layout.move(current, id, 1); layoutRevision++ }, enabled = index < blocks.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "下移模块") }
                                                LiquidSwitch(id !in hidden, { layout.hide(current.getString("pageId"), id, !it); layoutRevision++ }, modifier = Modifier.testTag("service-visible-$id"))
                                            }
                                        })
                                }
                                InsetGroupedRow(title = "恢复插件默认布局", showDivider = false, onClick = { layout.reset(current.getString("pageId")); layoutRevision++ })
                            }
                        }
                        blocks.filter { it.getString("id") !in hidden }.forEach { block -> key(block.getString("id")) { ServicePageBlock(block, !busy && !native.busy && !editing, ::navigate) } }
                        if (blocks.isNotEmpty() && blocks.all { it.getString("id") in hidden }) Text("模块已全部隐藏，可在“调整排布”中恢复。", style = MaterialTheme.typography.bodySmall)
                    } else if (!busy) Text("页面暂未加载，点击刷新重试。")
                }
            }
        }
        pendingAction?.let { action ->
            val declaration = ServicePluginContract.action(pkg.manifest, action.getString("actionId"))
            AlertDialog(onDismissRequest = { pendingAction = null }, title = { Text(declaration.getString("title")) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(declaration.getString("confirmation"))
                    val params = action.optJSONObject("params")
                    val fields = page?.optJSONArray("blocks")?.let(PluginJson::objects).orEmpty()
                        .filter { it.optString("type") == "form" && it.getJSONObject("submit").getString("actionId") == action.getString("actionId") }
                        .flatMap { PluginJson.objects(it.getJSONArray("fields")) }.associateBy { it.getString("id") }
                    if (params != null && fields.isNotEmpty()) Text(params.keys().asSequence().mapNotNull { id ->
                        fields[id]?.let { field ->
                            val value = params.getString(id)
                            val label = field.optJSONArray("options")?.let(PluginJson::objects)?.firstOrNull { it.getString("value") == value }?.getString("label") ?: value
                            "${field.getString("label")}：${label.take(160)}"
                        }
                    }.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                } }, confirmButton = { TextButton({ pendingAction = null; performAction(action, true) }) { Text("确认操作") } },
                dismissButton = { TextButton({ pendingAction = null }) { Text("取消") } })
        }
    }

    companion object {
        fun open(context: android.content.Context, pkg: PluginPackage, pageId: String? = null, preview: Boolean = false) {
            context.startActivity(Intent(context, ServicePluginActivity::class.java).putExtra("pluginId", pkg.manifest.id).putExtra("pageId", pageId).putExtra("preview", preview))
        }
        private fun accountScope(): String = UserManager.getInstance().let { (it.currentSchool?.id ?: "") + ":" + it.currentAccountStorageKey }
        private fun blockName(type: String) = when (type) { "profile" -> "个人信息"; "metrics" -> "统计卡片"; "progress" -> "分类进度"; "list" -> "记录列表"; "notice" -> "说明"; "actions" -> "功能入口"; "keyValue" -> "信息摘要"; "table" -> "数据表格"; "timeline" -> "时间线"; "barChart" -> "柱状图"; "grid" -> "网格入口"; else -> "查询表单" }
    }
}

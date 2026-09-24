package com.tyust.course.academic.plugin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionKey
import com.tyust.course.manager.UserManager
import com.tyust.course.ui.system.SystemDialog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private data class PagePrompt(val title: String, val message: String, val challenge: JSONObject?, val result: CompletableDeferred<JSONObject?>, val choices: List<Pair<String, String>> = emptyList(), val image: File? = null)

/** Shared by a pinned main page and the standalone plugin page activity. */
@Composable fun PluginPageContent(route: String, onNavigate: (String, JSONObject) -> Unit, onBack: () -> Unit, commandId: String? = null, pluginId: String? = null, params: JSONObject = JSONObject()) {
    val context = LocalContext.current
    val revision by PluginPages.revision.collectAsState()
    val accountRevision by PluginServiceAccounts.revision.collectAsState()
    val academicState by UserManager.getInstance().sessionState.state.collectAsState()
    val page = remember(route, revision) { PluginPages.registry.page(route) }
    val pkg = remember(page?.pluginId, pluginId, revision) { (page?.pluginId ?: pluginId)?.let { AcademicProviderRegistry.packages().active(it) } }
    val commandAllowed = pkg != null && commandId != null && PluginPages.available(pkg) && runCatching {
        val command = NativePluginContract.contribution(pkg.manifest, "commands", commandId)
        PluginPlatformContract.requirements(command, PluginPages.capabilities()).isEmpty()
    }.getOrDefault(false)
    if (pkg == null || (page == null && !commandAllowed) || commandId != null && !commandAllowed) {
        Column(Modifier.fillMaxSize().padding(20.dp)) { Text("页面已移除或插件已停用"); TextButton(onClick = onBack) { Text("返回") } }; return
    }
    val template = page?.let { NativePluginContract.page(pkg.manifest, it.templateId) }
    val serverId = template?.optString("serverId")?.takeIf { it.isNotBlank() }
        ?: pkg.manifest.json.optJSONArray("servers")?.takeIf { it.length() == 1 }?.getJSONObject(0)?.getString("id")
    val accounts = remember(context) { PluginServiceAccounts(context) }
    val serviceAccount = serverId?.let { accounts.selected(pkg.manifest.id, it) }
    val pageParams = JSONObject((page?.params ?: JSONObject()).toString()).apply { params.keys().forEach { put(it, params.get(it)) } }
    val scopeKey = "${pkg.digest}:$route:$serviceAccount:${academicState.token}:$accountRevision:${PluginJson.canonical(pageParams)}"
    key(scopeKey) {
        val live = remember { AtomicBoolean(true) }
        val session = remember {
            if (serverId != null) accounts.session(pkg, serverId)
            else PluginLegacyData.session(context, pkg, UserManager.getInstance().currentSchool, UserManager.getInstance().currentAccountStorageKey)
        }
        val active = remember { { live.get() && !session.retired && PluginServiceAccounts.revision.value == accountRevision && UserManager.getInstance().sessionState.state.value.token == academicState.token &&
            accounts.current(pkg, session) && AcademicProviderRegistry.isCurrentPackage(pkg.manifest.id, pkg.digest) && AcademicProviderRegistry.isEnabled(pkg.manifest.id) &&
            (commandId != null || PluginPages.registry.page(route) != null) && (serverId == null || accounts.selected(pkg.manifest.id, serverId) == serviceAccount) } }
        val interaction = rememberPageInteraction(pkg.manifest.name, onNavigate, onBack)
        val host = remember { NativeCapabilityHost(context, pkg, session, interaction, active) }
        DisposableEffect(host) { onDispose { host.close(); live.set(false); session.retire() } }
        if (page?.renderer == "web") {
            PluginWebPage(pkg, page.copy(params = pageParams), session, interaction, active)
        } else if (commandId != null) {
            var status by remember { mutableStateOf("正在执行…") }
            LaunchedEffect(commandId) {
                try {
                    val result = withContext(Dispatchers.IO) { NativePluginRunner.invoke(context, pkg, session, "command.run", JSONObject().put("commandId", commandId), JSONObject()) { active() } }
                    val flow = NativeFlow(true)
                    for (effect in PluginJson.objects(result.getJSONArray("effects"))) { flow.accept(effect.getString("id")); host.execute(effect, flow) }
                    status = result.opt("value")?.toString() ?: "已完成"
                } catch (e: Exception) { status = e.message ?: "操作未完成" }
            }
            Column(Modifier.padding(20.dp)) { Text(status); TextButton(onClick = onBack) { Text("返回") } }
        } else {
            val native = remember { NativeUiSession(context, pkg, session, host, active) }
            DisposableEffect(native) { onDispose { native.close() } }
            LaunchedEffect(route) { native.open(route, pageParams) }
            val snapshot by native.snapshot.collectAsState()
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (snapshot.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (snapshot.error.isNotBlank()) { Text(snapshot.error, color = MaterialTheme.colorScheme.error); TextButton(onClick = { native.open(route, pageParams) }) { Text("重试") } }
                snapshot.view?.let { view -> NativePluginNode(view, host.files, Modifier.fillMaxSize()) { event, gesture -> native.event(snapshot.instance, event, gesture) } }
            }
        }
    }
}

@Composable private fun rememberPageInteraction(pluginName: String, onNavigate: (String, JSONObject) -> Unit, onBack: () -> Unit): NativePluginInteraction {
    val view = LocalView.current
    var prompt by remember { mutableStateOf<PagePrompt?>(null) }
    val gate = remember { Mutex() }
    var picker by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    var permission by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picker?.complete(it); picker = null }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission?.complete(it); permission = null }
    val navigate by rememberUpdatedState(onNavigate)
    val back by rememberUpdatedState(onBack)
    val interaction = remember {
        object : NativePluginInteraction {
            override suspend fun choose(title: String, choices: List<Pair<String, String>>): String? = gate.withLock {
                val p = PagePrompt(title, "请选择此功能使用的服务。", null, CompletableDeferred(), choices); prompt = p
                try { p.result.await()?.optString("choice") } finally { if (prompt === p) prompt = null }
            }
            override suspend fun confirm(title: String, message: String): Boolean = gate.withLock {
                val p = PagePrompt(title, message, null, CompletableDeferred()); prompt = p
                try { p.result.await() != null } finally { if (prompt === p) prompt = null }
            }
            override suspend fun authenticate(challenge: JSONObject, image: File?): JSONObject? = gate.withLock {
                val p = PagePrompt(challenge.getString("title"), "由 $pluginName 发起，凭据仅用于该插件的服务。", challenge, CompletableDeferred(), image = image); prompt = p
                try { p.result.await() } finally { if (prompt === p) prompt = null }
            }
            override suspend fun pick(types: Array<String>): Uri? = gate.withLock {
                if (picker != null) throw PluginException(PluginErrorCode.CONFLICT, "文件选择器仍在使用中")
                val result = CompletableDeferred<Uri?>(); picker = result; fileLauncher.launch(types)
                try { result.await() } finally { if (!result.isCompleted) result.cancel(); if (picker === result) picker = null }
            }
            override suspend fun notificationPermission(): Boolean = gate.withLock {
                if (Build.VERSION.SDK_INT < 33) return@withLock true
                val result = CompletableDeferred<Boolean>(); permission = result; permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                try { result.await() } finally { permission = null }
            }
            override fun haptic() { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
            override fun navigate(pageId: String, params: JSONObject) { navigate(pageId, JSONObject(params.toString())) }
            override fun back() { back() }
        }
    }
    DisposableEffect(interaction) { onDispose { prompt?.result?.cancel(); picker?.cancel(); permission?.cancel() } }
    prompt?.let { p ->
        val fields = remember(p) { p.challenge?.optJSONArray("fields")?.let(PluginJson::objects).orEmpty() }
        val values = remember(p) { mutableStateMapOf<String, String>() }
        var choice by remember(p) { mutableStateOf<String?>(null) }
        var save by remember(p) { mutableStateOf(false) }
        val valid = (p.choices.isEmpty() || choice != null) && fields.all { !it.optBoolean("required", true) || !values[it.getString("id")].isNullOrBlank() }
        SystemDialog(onDismissRequest = { p.result.complete(null) }, title = { Text(p.title) },
            confirmButton = { TextButton(enabled = valid, onClick = { p.result.complete(if (p.choices.isNotEmpty()) JSONObject().put("choice", choice) else if (p.challenge == null) JSONObject() else JSONObject().put("values", JSONObject(values.toMap())).put("remember", save)) }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { p.result.complete(null) }) { Text("取消") } }) {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(p.message)
                p.image?.let { coil.compose.AsyncImage(it, "认证图片", Modifier.fillMaxWidth().heightIn(max = 160.dp)) }
                p.choices.forEach { (id, label) -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = choice == id, onClick = { choice = id }); TextButton(onClick = { choice = id }) { Text(label) }
                } }
                fields.forEach { field -> val id = field.getString("id")
                    OutlinedTextField(values[id].orEmpty(), { if (it.length <= 2000) values[id] = it }, label = { Text(field.getString("label")) },
                        visualTransformation = if (field.optString("type") == "password") PasswordVisualTransformation() else VisualTransformation.None, singleLine = true)
                }
                if (p.challenge?.optBoolean("remember") == true) Row { Checkbox(save, { save = it }); Text("为此服务账号加密保存") }
            }
        }
    }
    return interaction
}

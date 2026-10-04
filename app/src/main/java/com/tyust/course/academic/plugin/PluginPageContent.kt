package com.tyust.course.academic.plugin

import com.tyust.course.ui.system.SystemDialogButton
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
import androidx.compose.ui.layout.onSizeChanged
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

internal data class PagePrompt(val title: String, val message: String, val challenge: JSONObject?, val result: CompletableDeferred<JSONObject?>, val choices: List<Pair<String, String>> = emptyList(), val image: File? = null, val directChoices: Boolean = false, val values: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String> = mutableStateMapOf())

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
        Column(Modifier.fillMaxSize().padding(20.dp)) { Text("页面已移除或插件已停用"); SystemDialogButton(onClick = onBack) { Text("返回") } }; return
    }
    val template = page?.let { NativePluginContract.page(pkg.manifest, it.templateId) }
    val serverId = template?.optString("serverId")?.takeIf { it.isNotBlank() }
        ?: pkg.manifest.json.optJSONArray("servers")?.takeIf { it.length() == 1 }?.getJSONObject(0)?.getString("id")
    val accounts = remember(context) { PluginServiceAccounts(context) }
    val serviceAccount = serverId?.let { accounts.selected(pkg.manifest.id, it) }
    val pageParams = JSONObject((page?.params ?: JSONObject()).toString()).apply { params.keys().forEach { put(it, params.get(it)) } }
    val scopeKey = "${pkg.digest}:$route:$commandId:$serviceAccount:${academicState.token}:$accountRevision:${PluginJson.canonical(pageParams)}"
    key(scopeKey) {
        val owner: PluginPageRetainer = androidx.lifecycle.viewmodel.compose.viewModel()
        val app = context.applicationContext
        val lifetime = remember(scopeKey) { owner.obtain(route + ":" + commandId, scopeKey) {
            val session = if (serverId != null) accounts.session(pkg, serverId)
                else PluginLegacyData.session(app, pkg, UserManager.getInstance().currentSchool, UserManager.getInstance().currentAccountStorageKey)
            PluginPageLifetime(app, pkg, session) {
                !session.retired && PluginServiceAccounts.revision.value == accountRevision && UserManager.getInstance().sessionState.state.value.token == academicState.token &&
                PluginServiceAccounts(app).current(pkg, session) && AcademicProviderRegistry.isCurrentPackage(pkg.manifest.id, pkg.digest) && AcademicProviderRegistry.isEnabled(pkg.manifest.id) &&
                (commandId != null || PluginPages.registry.page(route) != null) && (serverId == null || PluginServiceAccounts(app).selected(pkg.manifest.id, serverId) == serviceAccount)
            }
        } }
        val session = lifetime.session
        val active = lifetime.active
        val interaction = rememberPageInteraction(lifetime.interaction, pkg.manifest.name, onNavigate, onBack)
        val host = lifetime.host
        DisposableEffect(lifetime) { onDispose {
            lifetime.viewport = null
            var activityContext: Context? = context
            while (activityContext is android.content.ContextWrapper && activityContext !is android.app.Activity) activityContext = activityContext.baseContext
            if ((activityContext as? android.app.Activity)?.isChangingConfigurations != true) owner.release(lifetime)
        } }
        if (page?.renderer == "web") {
            PluginWebPage(pkg, page.copy(params = pageParams), session, interaction, active)
        } else if (commandId != null) {
            var commandStarted by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(commandId) {
                if (!commandStarted || lifetime.commandStarted) lifetime.command(commandId)
                else lifetime.status = "页面已恢复；上次操作结果需先核对，请勿重复提交"
                commandStarted = true
            }
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) { Text(lifetime.status); SystemDialogButton(onClick = onBack) { Text("返回") } }
        } else {
            val native = lifetime.native()
            LaunchedEffect(route) { if (native.snapshot.value.instance.isEmpty()) native.open(route, pageParams); lifetime.viewport?.let(native::viewportChanged) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            DisposableEffect(lifecycle, lifetime) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> lifetime.foreground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
                lifecycle.addObserver(observer); lifetime.foreground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                onDispose { lifecycle.removeObserver(observer); lifetime.foreground = false }
            }
            val snapshot by native.snapshot.collectAsState()
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).then(Modifier.onSizeChanged { size ->
                val width = size.width / density.density; val height = size.height / density.density
                val viewport = JSONObject().put("widthDp", width).put("heightDp", height)
                    .put("widthClass", com.tyust.course.ui.system.windowWidthClass(width)).put("fontScale", density.fontScale)
                if (lifetime.viewport?.toString() != viewport.toString()) { lifetime.viewport = viewport; if (lifetime.foreground) native.viewportChanged(viewport) }
            }), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (snapshot.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (snapshot.error.isNotBlank()) { Text(snapshot.error, color = MaterialTheme.colorScheme.error); SystemDialogButton(onClick = { native.open(route, pageParams) }) { Text("重试") } }
                snapshot.view?.let { view -> NativePluginNode(view, host.files, Modifier.fillMaxSize()) { event, gesture -> native.event(snapshot.instance, event, gesture) } }
            }
        }
    }
}

@Composable private fun rememberPageInteraction(state: PageInteraction, pluginName: String, onNavigate: (String, JSONObject) -> Unit, onBack: () -> Unit): NativePluginInteraction {
    val view = LocalView.current
    var prompt by state::prompt
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { state.picker?.complete(it); state.picker = null }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { state.permission?.complete(it); state.permission = null }
    SideEffect {
        state.pluginName = pluginName
        state.launchFile = { fileLauncher.launch(it) }
        state.launchPermission = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        state.navigateTo = onNavigate; state.goBack = onBack
        state.hapticAction = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    }
    DisposableEffect(state) { onDispose { state.launchFile = null; state.launchPermission = null; state.navigateTo = null; state.goBack = null; state.hapticAction = null } }
    prompt?.let { p ->
        val fields = remember(p) { p.challenge?.optJSONArray("fields")?.let(PluginJson::objects).orEmpty() }
        val values = p.values
        var choice by androidx.compose.runtime.saveable.rememberSaveable(p.title) { mutableStateOf<String?>(null) }
        var save by androidx.compose.runtime.saveable.rememberSaveable(p.title) { mutableStateOf(false) }
        val valid = (p.choices.isEmpty() || choice != null) && fields.all { !it.optBoolean("required", true) || !values[it.getString("id")].isNullOrBlank() }
        SystemDialog(onDismissRequest = { p.result.complete(null) }, title = { Text(p.title) },
            confirmButton = {
                if (p.directChoices) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    p.choices.filter { it.first != "deny" }.reversed().forEach { (id, label) ->
                        SystemDialogButton(modifier = Modifier.fillMaxWidth(), primary = id in setOf("remember", "allow"), onClick = { p.result.complete(JSONObject().put("choice", id)) }) { Text(label) }
                    }
                } else SystemDialogButton(primary = true, enabled = valid, onClick = { p.result.complete(if (p.choices.isNotEmpty()) JSONObject().put("choice", choice) else if (p.challenge == null) JSONObject() else JSONObject().put("values", JSONObject(values.toMap())).put("remember", save)) }) { Text("确认") }
            },
            dismissButton = { SystemDialogButton(onClick = { p.result.complete(null) }) { Text(if (p.directChoices) "拒绝" else "取消") } }) {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(p.message)
                p.image?.let { coil.compose.AsyncImage(it, "认证图片", Modifier.fillMaxWidth().heightIn(max = 160.dp)) }
                if (!p.directChoices) p.choices.forEach { (id, label) -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = choice == id, onClick = { choice = id }); SystemDialogButton(onClick = { choice = id }) { Text(label) }
                } }
                fields.forEach { field -> val id = field.getString("id")
                    OutlinedTextField(values[id].orEmpty(), { if (it.length <= 2000) values[id] = it }, label = { Text(field.getString("label")) },
                        visualTransformation = if (field.optString("type") == "password") PasswordVisualTransformation() else VisualTransformation.None, singleLine = true)
                }
                if (p.challenge?.optBoolean("remember") == true) Row { Checkbox(save, { save = it }); Text("为此服务账号加密保存") }
            }
        }
    }
    return state
}

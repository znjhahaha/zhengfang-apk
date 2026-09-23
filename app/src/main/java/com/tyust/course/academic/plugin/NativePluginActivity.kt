package com.tyust.course.academic.plugin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionKey
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.ui.system.*
import com.tyust.course.ui.theme.CourseSelectorTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

class NativePluginActivity : ComponentActivity(), NativePluginInteraction {
    private data class Prompt(val title: String, val message: String, val challenge: JSONObject?, val image: File?, val result: CompletableDeferred<JSONObject?>)
    private var runtime by mutableStateOf<NativeUiSession?>(null)
    private var problem by mutableStateOf("")
    private var prompt by mutableStateOf<Prompt?>(null)
    private val promptGate = Mutex()
    private var picker: CompletableDeferred<Uri?>? = null
    private var permission: CompletableDeferred<Boolean>? = null
    private val history = mutableListOf<Pair<String, JSONObject>>()
    private var openedScope: String? = null
    private var preview = false
    private val selectFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> picker?.complete(uri); picker = null }
    private val requestNotification = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> permission?.complete(granted); permission = null }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CourseSelectorTheme { GlassWindowHost { Screen() } } }
        lifecycleScope.launch {
            try {
                val pkg = withContext(Dispatchers.IO) { AcademicProviderRegistry.packages().active(intent.getStringExtra("pluginId").orEmpty()) } ?: error("插件尚未安装")
                require(pkg.manifest.isNative) { "此插件不是 API v3 原生插件" }
                preview = !pkg.official
                val school = UserManager.getInstance().currentSchool
                require(preview || school == null && pkg.manifest.json.optJSONArray("matches")?.length().let { it == null || it == 0 } || school != null && AcademicProviderRegistry.matches(pkg, school) && AcademicProviderRegistry.isEnabled(pkg.manifest.id, school)) { "此插件不适用于当前学校或已在本校停用" }
                openedScope = if (preview) "dev:${pkg.digest}" else accountScope()
                val baseUrl = school?.let(PluginSchoolMatcher::endpoint)?.toString() ?: "https://invalid.example/"
                val session = AcademicSession(AcademicSessionKey(school?.let(PluginSchoolMatcher::key) ?: "global", openedScope!!), baseUrl)
                val active = { !isFinishing && !isDestroyed && (preview || openedScope == accountScope() && (school == null || AcademicProviderRegistry.isEnabled(pkg.manifest.id, school))) && AcademicProviderRegistry.isCurrentPackage(pkg.manifest.id, pkg.digest) }
                val host = NativeCapabilityHost(this@NativePluginActivity, pkg, session, this@NativePluginActivity, active)
                val native = NativeUiSession(this@NativePluginActivity, pkg, session, host, active)
                runtime = native
                val initial = intent.getStringExtra("pageId")?.takeIf { it.isNotBlank() } ?: pkg.manifest.contributes.getJSONArray("pages").getJSONObject(0).getString("id")
                navigate(initial, JSONObject())
            } catch (error: Exception) { problem = error.message ?: "无法打开插件" }
        }
    }
    override fun onResume() {
        super.onResume()
        if (!preview && openedScope != null && openedScope != accountScope()) finish()
        runtime?.pkg?.let { pkg -> if (AcademicProviderRegistry.packages().active(pkg.manifest.id)?.digest != pkg.digest) finish() }
    }
    @Composable private fun Screen() {
        val native = runtime
        val snapshot = native?.snapshot?.collectAsState()?.value
        var menu by remember { mutableStateOf(false) }
        BackHandler(history.size > 1) { back() }
        GlassPageScaffold(title = snapshot?.pageId?.let { id -> runCatching { native?.pkg?.manifest?.let { NativePluginContract.page(it, id).getString("title") } }.getOrNull() } ?: "原生插件",
            subtitle = native?.pkg?.manifest?.name, onBack = { back() }, actions = {
                SystemIconButton(Icons.Outlined.MoreHoriz, "更多", { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    native?.pkg?.manifest?.contributes?.optJSONArray("menuActions")?.let { actions -> PluginJson.objects(actions).forEach { action ->
                        DropdownMenuItem(text = { Text(action.getString("title")) }, onClick = { menu = false; val target = action.optString("pageId"); if (target.isNotBlank() && target != snapshot?.pageId) navigate(target, JSONObject()) else native.menu(action) })
                    } }
                    DropdownMenuItem(text = { Text("反馈此插件") }, onClick = { menu = false; PluginFeedback.open(this@NativePluginActivity, native?.pkg, snapshot?.errorCode) })
                }
            }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (snapshot?.busy == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                val error = snapshot?.error?.ifBlank { problem } ?: problem
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { PluginFeedback.open(this@NativePluginActivity, native?.pkg, snapshot?.errorCode) }) { Text("快捷反馈") }
                }
                if (snapshot?.view != null && native != null) key(snapshot.instance) {
                    NativePluginNode(snapshot.view, native.host.files, Modifier.fillMaxSize()) { event, gesture -> native.event(snapshot.instance, event, gesture) }
                }
            }
            prompt?.let { PromptDialog(it) }
        }
    }
    @Composable private fun PromptDialog(value: Prompt) {
        val fields = remember(value) { value.challenge?.optJSONArray("fields")?.let(PluginJson::objects).orEmpty() }
        val values = remember(value) { mutableStateMapOf<String, String>() }
        var rememberCredential by remember(value) { mutableStateOf(false) }
        val valid = fields.all { !it.optBoolean("required", true) || !values[it.getString("id")].isNullOrBlank() }
        SystemDialog(onDismissRequest = { value.result.complete(null) }, title = { Text(value.title) },
            confirmButton = { TextButton(enabled = valid, onClick = { value.result.complete(if (value.challenge == null) JSONObject() else JSONObject().put("values", JSONObject(values.toMap())).put("remember", rememberCredential)) }) { Text(if (value.challenge == null) "允许" else "继续") } },
            dismissButton = { TextButton(onClick = { value.result.complete(null) }) { Text("取消") } }) {
            Text(value.message)
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                value.image?.let { AsyncImage(it, "认证图片", Modifier.fillMaxWidth().heightIn(max = 160.dp)) }
                fields.forEach { field ->
                    val id = field.getString("id"); val password = field.getString("type") == "password"
                    OutlinedTextField(values[id].orEmpty(), { if (it.length <= 2000) values[id] = it }, Modifier.fillMaxWidth(), label = { Text(field.getString("label")) }, singleLine = true,
                        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text))
                }
                if (value.challenge?.optBoolean("remember") == true) Row { Checkbox(rememberCredential, { rememberCredential = it }); Text("为此学校、账号和插件版本加密保存凭据") }
            }
        }
    }
    override suspend fun confirm(title: String, message: String): Boolean = promptGate.withLock {
        val pending = Prompt(title, message, null, null, CompletableDeferred())
        prompt = pending
        try { pending.result.await() != null } finally { if (prompt === pending) prompt = null }
    }
    override suspend fun authenticate(challenge: JSONObject, image: File?): JSONObject? = promptGate.withLock {
        val pending = Prompt(challenge.getString("title"), "由 ${runtime?.pkg?.manifest?.name ?: "插件"} 发起。凭据只用于其声明的服务。", challenge, image, CompletableDeferred())
        prompt = pending
        try { pending.result.await() } finally { if (prompt === pending) prompt = null }
    }
    override suspend fun pick(types: Array<String>): Uri? = promptGate.withLock {
        if (picker != null) throw PluginException(PluginErrorCode.CANCELLED, "上一个文件选择器尚未关闭")
        val pending = CompletableDeferred<Uri?>(); picker = pending
        selectFile.launch(types)
        try { pending.await() } catch (e: CancellationException) { pending.cancel(); throw e }
    }
    override suspend fun notificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return true
        if (permission != null) return false
        val pending = CompletableDeferred<Boolean>(); permission = pending; requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        return pending.await()
    }
    override fun haptic() { window.decorView.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS) }
    override fun navigate(pageId: String, params: JSONObject) { runtime?.open(pageId, params); history.add(pageId to params) }
    override fun back() {
        if (history.size > 1) { history.removeAt(history.lastIndex); val previous = history.last(); runtime?.open(previous.first, previous.second) } else finish()
    }
    override fun onDestroy() { runtime?.close(); prompt?.result?.cancel(); picker?.cancel(); permission?.cancel(); super.onDestroy() }
    companion object {
        private fun accountScope(): String = UserManager.getInstance().let { (it.currentSchool?.let(PluginSchoolMatcher::key) ?: "global") + ":" + it.currentAccountStorageKey }
        fun open(context: Context, pkg: PluginPackage, pageId: String? = null) { context.startActivity(Intent(context, NativePluginActivity::class.java).putExtra("pluginId", pkg.manifest.id).putExtra("pageId", pageId)) }
    }
}

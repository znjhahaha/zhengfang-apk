package com.tyust.course.academic.plugin

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.tyust.course.academic.AcademicSession
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/** Retains in-memory work during configuration recreation, never serializes credentials. */
class PluginPageRetainer : ViewModel() {
    private val pages = mutableMapOf<String, Pair<String, PluginPageLifetime>>()
    internal fun obtain(slot: String, key: String, create: () -> PluginPageLifetime): PluginPageLifetime {
        pages[slot]?.let { if (it.first == key) return it.second else it.second.close() }
        return create().also { pages[slot] = key to it }
    }
    internal fun release(page: PluginPageLifetime) {
        pages.entries.removeAll { it.value.second === page }; page.close()
    }
    override fun onCleared() { pages.values.forEach { it.second.close() }; pages.clear() }
}

internal class PluginPageLifetime(private val app: Context, private val pkg: PluginPackage, val session: AcademicSession, valid: () -> Boolean) {
    @Volatile private var closed = false
    val active = { !closed && valid() }
    val interaction = PageInteraction()
    @Volatile var viewport: JSONObject? = null
    @Volatile var foreground = false
        set(value) { field = value; if (value) viewport?.let { native?.viewportChanged(it) } }
    val host = NativeCapabilityHost(app, pkg, session, interaction, viewport = { viewport?.takeIf { foreground } }, active = active)
    private var native: NativeUiSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var status by mutableStateOf("正在执行…")
    var commandStarted = false
        private set
    fun native(): NativeUiSession = native ?: NativeUiSession(app, pkg, session, host, active).also { native = it }
    fun command(id: String) {
        if (commandStarted || closed) return
        commandStarted = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { NativePluginRunner.invoke(app, pkg, session, "command.run", JSONObject().put("commandId", id), JSONObject(), active = active) }
                val flow = NativeFlow(true)
                for (effect in PluginJson.objects(result.getJSONArray("effects"))) { flow.accept(effect.getString("id")); host.execute(effect, flow) }
                status = result.opt("value")?.toString() ?: "已完成"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = e.message ?: "操作未完成" }
        }
    }
    fun close() {
        if (closed) return
        closed = true; scope.cancel(); native?.close(); host.close(); session.retire(); interaction.close()
    }
}

/** Launchers are attached by each composition; pending prompts and results survive rotation. */
internal class PageInteraction : NativePluginInteraction {
    var prompt by mutableStateOf<PagePrompt?>(null)
    var visualPrompt by mutableStateOf<PluginVisualPrompt?>(null)
    var picker: CompletableDeferred<Uri?>? = null
    var permission: CompletableDeferred<Boolean>? = null
    var pluginName = ""
    var launchFile: ((Array<String>) -> Unit)? = null
    var launchPermission: (() -> Unit)? = null
    var launchDevice: ((String) -> Unit)? = null
    var deviceResult: CompletableDeferred<JSONObject?>? = null
    var photoUri: Uri? = null
    var navigateTo: ((String, JSONObject) -> Unit)? = null
    var goBack: (() -> Unit)? = null
    var hapticAction: (() -> Unit)? = null
    private val gate = Mutex()
    private var closed = false
    private suspend fun ask(p: PagePrompt): JSONObject? = gate.withLock {
        if (closed) throw CancellationException("Page closed")
        prompt = p
        try { p.result.await() } finally { if (prompt === p) prompt = null }
    }
    override suspend fun consent(title: String, message: String): String? = ask(PagePrompt(title, message, null, CompletableDeferred(), NativePluginInteraction.CONSENT_CHOICES, directChoices = true))?.optString("choice")
    override suspend fun choose(title: String, choices: List<Pair<String, String>>): String? = ask(PagePrompt(title, "请选择此功能使用的服务。", null, CompletableDeferred(), choices))?.optString("choice")
    override suspend fun confirm(title: String, message: String) = ask(PagePrompt(title, message, null, CompletableDeferred())) != null
    override suspend fun authenticate(challenge: JSONObject, image: File?) = ask(PagePrompt(challenge.getString("title"), "由 $pluginName 发起，凭据仅用于该插件的服务。", challenge, CompletableDeferred(), image = image))
    override suspend fun pick(types: Array<String>): Uri? = gate.withLock {
        val launch = launchFile ?: throw PluginException(PluginErrorCode.CANCELLED, "页面暂不可交互，请重试")
        val result = CompletableDeferred<Uri?>(); picker = result
        try { launch(types); result.await() } finally { result.cancel(); if (picker === result) picker = null }
    }
    override suspend fun notificationPermission(): Boolean = gate.withLock {
        if (android.os.Build.VERSION.SDK_INT < 33) return@withLock true
        val launch = launchPermission ?: return@withLock false
        val result = CompletableDeferred<Boolean>(); permission = result
        try { launch(); result.await() } finally { result.cancel(); if (permission === result) permission = null }
    }
    override suspend fun device(kind: String): JSONObject? = gate.withLock {
        val launch = launchDevice ?: throw PluginException(PluginErrorCode.CANCELLED, "页面暂不可交互")
        val result = CompletableDeferred<JSONObject?>(); deviceResult = result
        try { launch(kind); result.await() } finally { result.cancel(); if (deviceResult === result) deviceResult = null }
    }
    override fun navigate(pageId: String, params: JSONObject) { navigateTo?.invoke(pageId, JSONObject(params.toString())) }
    override suspend fun visual(kind: String, input: JSONObject, images: List<File>): JSONObject? = gate.withLock {
        if (closed) throw CancellationException("Page closed")
        val request = PluginVisualPrompt(kind, input, images)
        visualPrompt = request
        try { request.result.await() } finally { request.result.cancel(); if (visualPrompt === request) visualPrompt = null }
    }
    override fun back() { goBack?.invoke() }
    override fun haptic() { hapticAction?.invoke() }
    fun close() { closed = true; prompt?.result?.cancel(); visualPrompt?.result?.cancel(); visualPrompt = null; picker?.cancel(); permission?.cancel(); deviceResult?.cancel(); prompt = null }
}

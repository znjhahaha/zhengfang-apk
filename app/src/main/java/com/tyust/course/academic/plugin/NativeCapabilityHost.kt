package com.tyust.course.academic.plugin

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tyust.course.academic.AcademicSession
import kotlinx.coroutines.*
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface NativePluginInteraction {
    suspend fun choose(title: String, choices: List<Pair<String, String>>): String? {
        for ((id, label) in choices) if (confirm(title, label)) return id
        return null
    }
    suspend fun confirm(title: String, message: String): Boolean
    suspend fun authenticate(challenge: JSONObject, image: File?): JSONObject?
    suspend fun pick(types: Array<String>): Uri?
    suspend fun notificationPermission(): Boolean
    fun haptic()
    fun navigate(pageId: String, params: JSONObject)
    fun back()
}

class NativeCapabilityHost(
    val app: Context, val pkg: PluginPackage, val session: AcademicSession,
    private val interaction: NativePluginInteraction?, private val active: () -> Boolean
) {
    val namespace = PluginStorageScope.session(session, pkg.manifest.id, !pkg.official)
    private val legacyNamespaces = PluginLegacyData.namespaces(app, pkg, session)
    init { (legacyNamespaces + namespace).forEach { PluginServiceAccounts(app).trackScope(pkg.manifest.id, it) } }
    val files = NativePluginFiles(app, namespace, legacyNamespaces) { ensureActive() }
    private val vault = NativePluginVault(app, namespace, legacyNamespaces) { ensureActive() }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = ConcurrentHashMap.newKeySet<PluginOperation>()
    private val descriptors = PluginJson.objects(JSONArray(app.assets.open("academic-plugin/host-capabilities.json").bufferedReader().use { it.readText() }))
    private val schema = PluginSchema(JSONObject())
    private val prefs = app.getSharedPreferences("native-plugin-permissions", Context.MODE_PRIVATE)
    var cancelEffects: (List<String>) -> Unit = {}
    private val services by lazy { PluginServices(app, pkg, session, interaction, active) }
    private val academic by lazy { PluginAcademicData(app, pkg, active) }
    fun capabilities(): JSONArray = JSONArray(descriptors.filter { descriptor ->
        descriptor.getString("name") in (IMPLEMENTED + PLATFORM) && (interaction != null || !descriptor.getBoolean("userGesture") && !descriptor.getString("name").startsWith("navigation."))
    })
    fun requireCompatible() {
        val available = PluginJson.objects(capabilities()).associate { it.getString("name") to it.getInt("version") }
        PluginPlatformContract.requireCompatible(pkg.manifest, com.tyust.course.BuildConfig.VERSION_CODE, available)
        pkg.manifest.json.optJSONArray("requires")?.let { list -> PluginJson.objects(list).forEach { requirement ->
            if ((available[requirement.getString("name")] ?: 0) < requirement.getInt("version")) throw PluginException(PluginErrorCode.UNSUPPORTED, "当前 App 不支持插件要求的能力：${requirement.getString("name")}")
        } }
    }
    private fun ensureActive() {
        if (!active() || session.retired || !PluginServiceAccounts(app).current(pkg, session)) throw PluginException(PluginErrorCode.STALE_CONTEXT, "插件上下文已改变")
    }
    private fun denied(message: String): Nothing = throw PluginException(PluginErrorCode.PERMISSION_DENIED, message)
    private suspend fun confirm(flow: NativeFlow, title: String, message: String) {
        if (!flow.userGesture || interaction == null) denied("此操作需要你主动确认")
        if (!withContext(Dispatchers.Main) { interaction.confirm(title, message) }) throw PluginException(PluginErrorCode.CANCELLED, "你已取消此操作")
        ensureActive()
    }
    suspend fun execute(effect: JSONObject, flow: NativeFlow): Any? {
        ensureActive()
        val name = effect.getString("capability")
        val descriptor = PluginJson.objects(capabilities()).firstOrNull { it.getString("name") == name && effect.getInt("version") in 1..it.getInt("version") }
            ?: throw PluginException(PluginErrorCode.UNSUPPORTED, "宿主未提供此版本的能力：$name")
        if (descriptor.getString("permission") !in pkg.manifest.permissions) denied("插件未声明 ${descriptor.getString("permission")} 权限")
        if (descriptor.getBoolean("userGesture") && !flow.userGesture) denied("此设备交互必须由你发起")
        val input = effect.getJSONObject("input")
        schema.validate(input, descriptor.getJSONObject("input"))
        val request = when (name) { "network.request" -> input; "files.download", "files.upload" -> input.getJSONObject("request"); else -> null }
        if (request != null && PluginCredentialBindings.structured(request) &&
            (effect.getInt("version") < 2 || PluginJson.objects(pkg.manifest.json.optJSONArray("requires") ?: JSONArray()).none { it.optString("name") == name && it.optInt("version") >= 2 }))
            throw PluginException(PluginErrorCode.UNSUPPORTED, "加密凭据绑定需要声明并调用 $name 版本 2")
        val result = withTimeout(effect.optLong("timeoutMs", 120_000).coerceIn(1000, 600_000)) {
            when (name) {
                "academic.study.snapshot", "academic.study.refresh" -> {
                    val grant = pkg.manifest.id + ":" + PluginJson.sha256((namespace + "academic.read" + com.tyust.course.manager.UserManager.getInstance().currentAccountStorageKey).toByteArray())
                    if (!prefs.getBoolean(grant, false)) {
                        confirm(flow, "共享学业数据", "允许 ${pkg.manifest.name} 读取当前教务账号的课表和成绩？")
                        check(prefs.edit().putBoolean(grant, true).commit())
                    }
                    academic.read(input, name.endsWith("refresh"))
                }
                "academic.schedule.preview" -> withContext(Dispatchers.IO) { academic.preview(input) }
                "academic.schedule.confirm" -> {
                    val id = input.getString("previewId"); val preview = academic.describe(id)
                    confirm(flow, "导入课表", "学期：${preview.getString("termId")}\n新增 ${preview.getInt("added")} 项，跳过 ${preview.getInt("duplicates")} 个重复项，保留 ${preview.getInt("manualPreserved")} 项手动课程。")
                    withContext(Dispatchers.IO) { academic.confirm(id) }
                }
                "services.discover", "services.call", "workflow.prepare", "workflow.step", "workflow.reconcile", "workflow.cancel", "workflow.list" -> services.execute(name, input, flow)
                "network.request" -> network(input, flow)
                "storage.get", "storage.set", "storage.remove" -> callHost(name, input)
                "auth.prompt" -> {
                    val fields = PluginJson.objects(input.getJSONArray("fields"))
                    if (fields.map { it.getString("id") }.distinct().size != fields.size) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "认证字段 ID 重复")
                    val values = withContext(Dispatchers.Main) { interaction!!.authenticate(input, input.optString("imageHandle").takeIf { it.isNotBlank() }?.let(files::file)) }
                        ?: throw PluginException(PluginErrorCode.CANCELLED, "已取消认证")
                    ensureActive()
                    withContext(Dispatchers.IO) { JSONObject().put("credential", vault.saveCredential(input.getString("key"), values.getJSONObject("values"), values.optBoolean("remember"))) }
                }
                "credentials.find" -> withContext(Dispatchers.IO) { vault.findCredential(input.getString("key")) ?: JSONObject.NULL }
                "credentials.remove" -> withContext(Dispatchers.IO) { vault.remove("credential:" + input.getString("handle")); JSONObject.NULL }
                "session.save" -> withContext(Dispatchers.IO) {
                    val cookies = JSONArray(session.cookies.snapshot().map { cookie -> JSONObject().put("url", "${if (cookie.secure) "https" else "http"}://${cookie.domain}${cookie.path}").put("value", cookie.toString()) })
                    vault.put("session:" + input.getString("key"), JSONObject().put("cookies", cookies)); JSONObject.NULL
                }
                "session.restore" -> withContext(Dispatchers.IO) {
                    val saved = vault.get("session:" + input.getString("key"))
                    if (saved != null) { session.cookies.clear(); PluginJson.objects(saved.getJSONArray("cookies")).forEach { entry ->
                        val url = entry.getString("url").toHttpUrlOrNull() ?: return@forEach
                        Cookie.parse(url, entry.getString("value"))?.let { session.cookies.saveFromResponse(url, listOf(it)) }
                    } }
                    JSONObject().put("restored", saved != null)
                }
                "session.clear" -> withContext(Dispatchers.IO) { vault.remove("session:" + input.getString("key")); session.cookies.clear(); JSONObject.NULL }
                "files.pick" -> {
                    val uri = withContext(Dispatchers.Main) { interaction!!.pick(PluginJson.strings(input.getJSONArray("mimeTypes")).toTypedArray()) }
                        ?: throw PluginException(PluginErrorCode.CANCELLED, "已取消文件选择")
                    ensureActive(); withContext(Dispatchers.IO) { files.import(uri) }
                }
                "files.create" -> withContext(Dispatchers.IO) { files.create(input.getString("name"), input.getString("mime")) }
                "files.read" -> withContext(Dispatchers.IO) { files.read(input) }
                "files.write" -> withContext(Dispatchers.IO) { files.write(input) }
                "files.remove" -> withContext(Dispatchers.IO) { files.remove(input.getString("handle")); JSONObject.NULL }
                "files.download" -> {
                    requireNetworkPermission()
                    val request = JSONObject(input.getJSONObject("request").toString()).put("responseType", "base64")
                    val response = network(request, flow)
                    if (response.getInt("status") !in 200..299) throw PluginException(PluginErrorCode.NETWORK_RETRYABLE, "文件下载失败：${response.getInt("status")}")
                    withContext(Dispatchers.IO) { files.create(input.getString("name"), input.getString("mime"), android.util.Base64.decode(response.getString("body"), android.util.Base64.DEFAULT)) }
                }
                "files.upload" -> {
                    requireNetworkPermission()
                    val request = input.getJSONObject("request")
                    if (request.optString("method") != "POST" || request.optString("purpose") != "mutation") throw PluginException(PluginErrorCode.VALIDATION_FAILED, "文件上传须声明 POST 写入")
                    confirm(flow, "上传文件", "${pkg.manifest.name} 将上传 ${files.info(input.getString("handle")).getString("name")} 到 ${request.getString("url")}")
                    callHost("http", credentialRequest(request), true, files.file(input.getString("handle")), input.getString("field"), flow) as JSONObject
                }
                "files.open", "files.share" -> {
                    val handle = input.getString("handle"); val info = files.info(handle)
                    confirm(flow, if (name == "files.share") "分享文件" else "打开文件", "${pkg.manifest.name} 请求将 ${info.getString("name")} 交给你选择的应用")
                    val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", files.file(handle))
                    val intent = if (name == "files.share") Intent(Intent.ACTION_SEND).setType(info.getString("mime")).putExtra(Intent.EXTRA_STREAM, uri)
                        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, info.getString("mime"))
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).setClipData(ClipData.newRawUri("文件", uri))
                    withContext(Dispatchers.Main) { app.startActivity(Intent.createChooser(intent, "选择应用").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; JSONObject.NULL
                }
                "device.clipboard.read" -> {
                    confirm(flow, "读取剪贴板", "允许 ${pkg.manifest.name} 读取当前剪贴板文字？")
                    withContext(Dispatchers.Main) { val clip = app.getSystemService(ClipboardManager::class.java).primaryClip
                        JSONObject().put("text", if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString()?.take(8000).orEmpty() else "") }
                }
                "device.clipboard.write" -> withContext(Dispatchers.Main) { app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(pkg.manifest.name, input.getString("text"))); JSONObject.NULL }
                "device.haptic" -> withContext(Dispatchers.Main) { interaction!!.haptic(); JSONObject.NULL }
                "tasks.schedule" -> {
                    NativePluginContract.contribution(pkg.manifest, "tasks", input.getString("taskId"))
                    confirm(flow, "安排后台任务", "${pkg.manifest.name} 请求在后台运行 ${NativePluginContract.contribution(pkg.manifest, "tasks", input.getString("taskId")).getString("title")}。任务会继续使用当前学校、账号和插件版本。")
                    withContext(Dispatchers.IO) { NativePluginTasks.schedule(app, pkg, session, namespace, input) }
                }
                "tasks.cancel" -> withContext(Dispatchers.IO) { NativePluginTasks.cancel(app, namespace, input.getString("handle")); JSONObject.NULL }
                "tasks.list" -> withContext(Dispatchers.IO) { NativePluginTasks.list(app, namespace) }
                "notifications.post" -> {
                    val grant = pkg.manifest.id + ":" + PluginJson.sha256((namespace + "notifications").toByteArray())
                    if (!prefs.getBoolean(grant, false)) {
                        confirm(flow, "允许通知", "允许 ${pkg.manifest.name} 在任务完成时发送通知？")
                        if (!withContext(Dispatchers.Main) { interaction!!.notificationPermission() }) denied("通知权限未获允许")
                        prefs.edit().putBoolean(grant, true).apply()
                    }
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) denied("系统通知权限未获允许")
                    val manager = app.getSystemService(NotificationManager::class.java)
                    if (!manager.areNotificationsEnabled()) denied("系统已关闭通知")
                    val channel = "plugin_${PluginJson.sha256(pkg.manifest.id.toByteArray()).take(16)}"
                    if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channel, pkg.manifest.name, NotificationManager.IMPORTANCE_DEFAULT))
                    val notification = NotificationCompat.Builder(app, channel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(input.getString("title")).setContentText(input.getString("body")).setSubText(pkg.manifest.name).setAutoCancel(true).build()
                    manager.notify(namespace, input.getInt("id"), notification); JSONObject.NULL
                }
                "pages.register" -> {
                    val page = PluginPages.registry.register(pkg.manifest.id, input.getString("templateId"), input.getString("instanceId"), input.optString("title").takeIf { it.isNotBlank() }, input.optJSONObject("params") ?: JSONObject())
                    JSONObject().put("pageId", page.id)
                }
                "pages.unregister" -> { val id = input.getString("pageId"); PluginPages.registry.unregister(pkg.manifest.id, if (id.contains('/')) id else "${pkg.manifest.id}/$id"); JSONObject.NULL }
                "pages.close" -> { withContext(Dispatchers.Main) { interaction!!.back() }; JSONObject.NULL }
                "pages.open", "navigation.page" -> {
                    val requested = input.getString("pageId"); val route = if (requested.contains('/')) requested else "${pkg.manifest.id}/$requested"
                    if (PluginPages.registry.page(route)?.pluginId != pkg.manifest.id) denied("只能打开自己的已注册页面")
                    withContext(Dispatchers.Main) { interaction!!.navigate(route, input.optJSONObject("params") ?: JSONObject()) }; JSONObject.NULL
                }
                "accounts.select" -> {
                    val accounts = PluginServiceAccounts(app); accounts.server(pkg, input.getString("serverId"))
                    val result = withContext(Dispatchers.Main) { interaction!!.authenticate(JSONObject().put("title", "选择服务账号").put("fields", JSONArray().put(JSONObject().put("id", "label").put("label", "已有账号名称或新账号名称").put("type", "text"))), null) } ?: throw PluginException(PluginErrorCode.CANCELLED, "已取消")
                    val id = accounts.select(pkg, input.getString("serverId"), result.getJSONObject("values").getString("label")); JSONObject().put("accountId", id)
                }
                "accounts.remove" -> { confirm(flow, "移除服务账号", "清除这个服务账号在 App 内的凭据与登录状态，服务器业务数据保留。")
                    PluginServiceAccounts(app).remove(pkg, input.getString("serverId"), input.getString("accountId")); JSONObject.NULL }
                "navigation.back" -> { withContext(Dispatchers.Main) { interaction!!.back() }; JSONObject.NULL }
                "navigation.url" -> {
                    val url = input.getString("url").toHttpUrlOrNull() ?: throw PluginException(PluginErrorCode.UNTRUSTED_URL, "无效网址")
                    PluginNetworkPolicy(pkg.manifest.network).requireAllowed(url, "GET", "query", null)
                    confirm(flow, "打开网页", "${pkg.manifest.name} 请求打开 ${url.host} 的网页")
                    withContext(Dispatchers.Main) { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; JSONObject.NULL
                }
                "runtime.cancel" -> { cancelEffects(PluginJson.strings(input.getJSONArray("effectIds"))); JSONObject.NULL }
                "data.query" -> NativePluginRunner.invoke(app, pkg, session, "data.query", input, JSONObject().put("capabilities", capabilities()), active = active)
                else -> throw PluginException(PluginErrorCode.UNSUPPORTED, "未实现的宿主能力")
            }
        }
        ensureActive()
        schema.validate(result, descriptor.getJSONObject("output"))
        return result
    }
    private fun requireNetworkPermission() { if ("network" !in pkg.manifest.permissions) denied("文件传输还需要网络权限") }
    private suspend fun network(request: JSONObject, flow: NativeFlow): JSONObject {
        if (request.optString("purpose") == "mutation") confirm(flow, "确认提交", "${pkg.manifest.name} 请求向 ${request.getString("url")} 提交数据。请确认这是你要执行的操作。")
        return callHost("http", credentialRequest(request), request.optString("purpose") == "mutation", flow = flow) as JSONObject
    }
    private fun credentialRequest(request: JSONObject): JSONObject {
        val handle = request.optString("credential")
        var values: JSONObject? = null
        if (handle.isNotBlank()) {
            if ("credentials" !in pkg.manifest.permissions && "auth" !in pkg.manifest.permissions) denied("插件未声明凭据权限")
            values = vault.get("credential:$handle") ?: throw PluginException(PluginErrorCode.SESSION_EXPIRED, "凭据已失效，请重新认证")
        }
        return PluginCredentialBindings.apply(request, values)
    }
    private suspend fun callHost(method: String, input: JSONObject, confirmed: Boolean = false, upload: File? = null, field: String = "file", flow: NativeFlow? = null): Any? = suspendCancellableCoroutine { continuation ->
        val operation = PluginOperation(session, pkg.manifest, "host.effect", development = !pkg.official, confirmed = confirmed, packageDigest = pkg.digest,
            scopeStillActive = { active() && PluginServiceAccounts(app).current(pkg, session) })
        operations.add(operation)
        val worker = ioScope.launch {
            val lease = PluginVersionLeases.acquire(pkg.manifest.id)
            try {
                val host = PluginHost(operation, File(app.filesDir, "academic-plugin-storage"), PluginWebSessionCookies.jar(app, pkg, session, active))
                val value = if (upload != null) host.upload(input, upload, field) else {
                    val response = host.call(method, input)
                    if (!response.getBoolean("ok")) { val error = response.getJSONObject("error"); throw PluginException(PluginErrorCode.valueOf(error.getString("code")), error.getString("message")) }
                    response.opt("data") ?: JSONObject.NULL
                }
                if (continuation.isActive) continuation.resume(value)
            } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(if (error is PluginException) operation.failure(error.code, error.message.orEmpty()) else error) }
            finally { operation.close(); operations.remove(operation); lease.close() }
        }
        continuation.invokeOnCancellation {
            operation.close()
            if (operation.mutationSent) flow?.markUnknown()
            worker.cancel()
        }
    }
    fun close() { operations.forEach(PluginOperation::close); operations.clear(); ioScope.cancel(); if (active()) PluginSessionCookies.save(app, pkg, session) }
    companion object {
        val PLATFORM = setOf("pages.register", "pages.open", "pages.close", "pages.unregister", "accounts.select", "accounts.remove", "services.discover", "services.call", "workflow.prepare", "workflow.step", "workflow.reconcile", "workflow.cancel", "workflow.list", "academic.study.snapshot", "academic.study.refresh", "academic.schedule.preview", "academic.schedule.confirm")
        val IMPLEMENTED = setOf("network.request", "storage.get", "storage.set", "storage.remove", "auth.prompt", "credentials.find", "credentials.remove", "session.save", "session.restore", "session.clear", "files.pick", "files.create", "files.read", "files.write", "files.remove", "files.download", "files.upload", "files.open", "files.share", "device.clipboard.read", "device.clipboard.write", "device.haptic", "tasks.schedule", "tasks.cancel", "tasks.list", "notifications.post", "navigation.page", "navigation.back", "navigation.url", "runtime.cancel", "data.query")
    }
}

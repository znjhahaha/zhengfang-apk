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
    suspend fun consent(title: String, message: String): String? = choose("$title\n$message", CONSENT_CHOICES)
    suspend fun choose(title: String, choices: List<Pair<String, String>>): String? {
        for ((id, label) in choices) if (confirm(title, label)) return id
        return null
    }
    suspend fun confirm(title: String, message: String): Boolean
    suspend fun authenticate(challenge: JSONObject, image: File?): JSONObject?
    suspend fun pick(types: Array<String>): Uri?
    suspend fun notificationPermission(): Boolean
    suspend fun device(kind: String): JSONObject? = throw PluginException(PluginErrorCode.UNSUPPORTED, "当前页面不支持此设备能力")
    suspend fun visual(kind: String, input: JSONObject, images: List<File>): JSONObject? = throw PluginException(PluginErrorCode.UNSUPPORTED, "当前页面不支持此交互")
    fun haptic()
    fun navigate(pageId: String, params: JSONObject)
    fun back()
    companion object {
        val CONSENT_CHOICES = listOf("remember" to "允许并记住", "once" to "仅本次", "deny" to "拒绝")
    }
}

class NativeCapabilityHost internal constructor(
    val app: Context, val pkg: PluginPackage, val session: AcademicSession,
    private val interaction: NativePluginInteraction?, private val disclosureOrigin: String? = null, private val viewport: (() -> JSONObject?)? = null,
    private val foregroundGrant: NativeForegroundGrant? = null, private val active: () -> Boolean
) {
    private val dataGuard = PluginDataGuard(app, pkg)
    val namespace = PluginStorageScope.session(session, pkg.manifest.id, !pkg.official)
    private val legacyNamespaces = PluginLegacyData.namespaces(app, pkg, session)
    init { (legacyNamespaces + namespace).forEach { PluginServiceAccounts(app).trackScope(pkg.manifest.id, it) } }
    val files = NativePluginFiles(app, namespace, legacyNamespaces) { ensureActive() }
    private val vault = NativePluginVault(app, namespace, legacyNamespaces) { ensureActive() }
    private val records by lazy { NativePluginRecords(app, namespace, guard = ::ensureActive) }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = ConcurrentHashMap.newKeySet<PluginOperation>()
    private val descriptors = PluginJson.objects(JSONArray(app.assets.open("academic-plugin/host-capabilities.json").bufferedReader().use { it.readText() }))
    private val schema = PluginSchema(JSONObject())
    private val prefs = app.getSharedPreferences("native-plugin-permissions", Context.MODE_PRIVATE)
    var cancelEffects: (List<String>) -> Unit = {}
    private val services by lazy { PluginServices(app, pkg, session, interaction, active) }
    private val academic by lazy { PluginAcademicData(app, pkg, active) }
    private val academicSession by lazy { PluginAcademicSession(app, pkg, active) }
    fun capabilities(): JSONArray = JSONArray(descriptors.filter { descriptor ->
        descriptor.getString("name") in (IMPLEMENTED + PLATFORM) &&
            (android.os.Build.VERSION.SDK_INT >= 26 || !descriptor.getString("name").startsWith("browser.session.")) &&
            (interaction != null || !descriptor.getBoolean("userGesture") && !descriptor.getString("name").startsWith("navigation."))
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
        if (!PluginExecutionBudget.userInput { withContext(Dispatchers.Main) { interaction.confirm(title, message) } }) throw PluginException(PluginErrorCode.CANCELLED, "你已取消此操作")
        ensureActive()
    }
    private suspend fun authorizeAcademic(flow: NativeFlow): JSONObject = PluginExecutionBudget.userInput {
        PluginConsentCoordinator.request(academicSession.coordinationKey()) {
            if (academicSession.siteAuthorized()) academicSession.authorize()
            else {
                if (!flow.userGesture || interaction == null) denied("请主动授权此插件使用学校登录")
                val description = academicSession.description()
                val choice = withContext(Dispatchers.Main) { interaction.consent("授权使用教务登录", description) }
                ensureActive()
                if (choice !in setOf("once", "remember")) throw PluginException(PluginErrorCode.CANCELLED, "已拒绝授权")
                academicSession.authorizeSite(choice == "remember")
            }
        }
    }
    suspend fun execute(effect: JSONObject, flow: NativeFlow): Any? {
        ensureActive()
        val name = effect.getString("capability")
        val descriptor = PluginJson.objects(capabilities()).firstOrNull { it.getString("name") == name && effect.getInt("version") in 1..it.getInt("version") }
            ?: throw PluginException(PluginErrorCode.UNSUPPORTED, "宿主未提供此版本的能力：$name")
        if (descriptor.getString("permission") !in pkg.manifest.permissions) denied("插件未声明 ${descriptor.getString("permission")} 权限")
        if (descriptor.getBoolean("userGesture") && !flow.userGesture && !(name == "academic.session.authorize" && academicSession.siteAuthorized())) denied("此设备交互必须由你发起")
        val input = effect.getJSONObject("input")
        schema.validate(input, descriptor.getJSONObject("input"))
        val request = when (name) { "network.request" -> input; "files.download", "files.upload" -> input.getJSONObject("request"); else -> null }
        if (name == "device.location.pick" && input.has("query") &&
            (effect.getInt("version") < 2 || PluginJson.objects(pkg.manifest.json.optJSONArray("requires") ?: JSONArray())
                .none { it.optString("name") == "device.location.pick" && it.optInt("version") >= 2 }))
            throw PluginException(PluginErrorCode.UNSUPPORTED, "地址搜索预填需要 device.location.pick 版本 2")
        if (request?.optJSONObject("headers")?.keys()?.asSequence()?.any { it.equals("referer", true) } == true &&
            (name != "network.request" || effect.getInt("version") < 4 || PluginJson.objects(pkg.manifest.json.optJSONArray("requires") ?: JSONArray())
                .none { it.optString("name") == "network.request" && it.optInt("version") >= 4 }))
            throw PluginException(PluginErrorCode.UNSUPPORTED, "来源页请求头需要声明并调用 network.request 版本 4")
        if (request != null && PluginCredentialBindings.structured(request) &&
            (effect.getInt("version") < 2 || PluginJson.objects(pkg.manifest.json.optJSONArray("requires") ?: JSONArray()).none { it.optString("name") == name && it.optInt("version") >= 2 }))
            throw PluginException(PluginErrorCode.UNSUPPORTED, "加密凭据绑定需要声明并调用 $name 版本 2")
        val result = PluginExecutionBudget.run(effect.optLong("timeoutMs", 120_000).coerceIn(1000, 600_000)) {
            when (name) {
                "ui.viewport" -> viewport?.invoke() ?: throw PluginException(PluginErrorCode.UNSUPPORTED, "视口只在前台原生页面可用")
                "ui.components" -> NativePluginContract.components()
                "ui.navigation" -> JSONObject().put("maxDepth", 8)
                "academic.session.authorize" -> authorizeAcademic(flow)
                "privacy.status" -> dataGuard.status()
                "privacy.revoke" -> { dataGuard.revoke(); JSONObject.NULL }
                "academic.session.revoke" -> { PluginAcademicSession.revoke(app, pkg.manifest.id); JSONObject.NULL }
                "academic.session.request" -> {
                    requireNetworkPermission()
                    if (effect.getInt("version") >= 2 && PluginJson.objects(pkg.manifest.json.optJSONArray("requires") ?: JSONArray()).none {
                        it.optString("name") == "academic.session.request" && it.optInt("version") >= 2
                    }) throw PluginException(PluginErrorCode.UNSUPPORTED, "共享教务令牌需要声明 academic.session.request 版本 2")
                    val grant = input.getString("grant")
                    academicSession.requireGrant(grant)
                    val request = input.getJSONObject("request")
                    val mutation = request.getString("purpose") == "mutation"
                    val url = request.getString("url").toHttpUrlOrNull() ?: throw PluginException(PluginErrorCode.UNTRUSTED_URL, "地址无效")
                    academicSession.requireSiteDestination(url, request.optString("method", "GET"), request.getString("purpose"))
                    if (!academicSession.siteAuthorized()) authorizeAcademic(flow)
                    academicSession.requireRequest(grant, url, request.optString("method", "GET"), request.getString("purpose"), request.optJSONObject("form"))
                    dataGuard.mark()
                    callHost("http", request, mutation, flow = flow, shared = academicSession, grant = grant, shareToken = effect.getInt("version") >= 2)
                }
                "academic.study.snapshot", "academic.study.refresh" -> {
                    val grant = pkg.manifest.id + ":" + PluginJson.sha256((namespace + "academic.read" + com.tyust.course.manager.UserManager.getInstance().currentAccountStorageKey).toByteArray())
                    if (!prefs.getBoolean(grant, false)) {
                        confirm(flow, "共享学业数据", "允许 ${pkg.manifest.name} 读取当前教务账号的课表和成绩？")
                        check(prefs.edit().putBoolean(grant, true).commit())
                    }
                    dataGuard.mark()
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
                "device.location.pick", "images.puzzle" -> {
                    val images = if (name == "images.puzzle") listOf(files.file(input.getString("backgroundHandle")), files.file(input.getString("pieceHandle"))) else emptyList()
                    val result = PluginExecutionBudget.userInput { withContext(Dispatchers.Main) { interaction!!.visual(name, input, images) } }
                        ?: throw PluginException(PluginErrorCode.CANCELLED, "已取消操作")
                    ensureActive()
                    if (name == "images.puzzle" && System.currentTimeMillis() >= input.getLong("expiresAt")) throw PluginException(PluginErrorCode.SESSION_EXPIRED, "验证图片已过期，请重新获取")
                    result
                }
                "images.match" -> withContext(Dispatchers.Default) { PluginImageMatcher.match(files.file(input.getString("backgroundHandle")), files.file(input.getString("pieceHandle")), input.optDouble("expectedY").takeIf { it.isFinite() }, app) }
                "records.upsert" -> withContext(Dispatchers.IO) { records.upsert(input) }
                "records.query" -> withContext(Dispatchers.IO) { records.query(input) }
                "userscript.status" -> PluginUserscripts.status(app, pkg, session, input.getString("scriptId"))
                "userscript.configure" -> PluginUserscripts.configure(app, pkg, session, input)
                "userscript.update" -> {
                    val status = PluginUserscripts.status(app, pkg, session, input.getString("scriptId"))
                    if (!status.optBoolean("subscribed")) confirm(flow, "订阅原脚本", "原脚本从插件声明的官方来源下载，保留作者和许可信息。启用后自动检查更新，新版将在下次任务运行。")
                    PluginUserscripts.update(app, pkg, session, input.getString("scriptId"))
                }
                "userscript.rollback" -> PluginUserscripts.rollback(app, pkg, session, input.getString("scriptId"))
                "userscript.start" -> {
                    if (PluginEmbeddedBrowser.enabled(pkg, session)) PluginEmbeddedBrowser.requireSupport() else PluginUserscripts.requireSupport()
                    continuousPermission(flow, "启动原脚本任务", "按原脚本当前设置处理课程内容，包括已开启的答题和自动提交。离开页面后继续运行，可从常驻通知停止。考试页面不在运行范围。")
                    PluginUserscripts.start(app, pkg, session, input.getString("scriptId"), input.getString("url"))
                }
                "userscript.control" -> PluginUserscripts.control(app, pkg, session, input)
                "userscript.interact", "userscript.action" -> PluginEmbeddedBrowser.interactScript(app, pkg, session, input, name == "userscript.action")
                "browser.session.open" -> PluginEmbeddedBrowser.openUrl(app, pkg, session, input)
                "browser.session.status", "browser.session.close" -> PluginEmbeddedBrowser.pageControl(app, pkg, session, input.getString("handle"), name.endsWith("close"))
                "tasks.foreground.start" -> {
                    val task = NativePluginContract.contribution(pkg.manifest, "tasks", input.getString("taskId"))
                    val declaration = task.optJSONObject("foreground") ?: throw PluginException(PluginErrorCode.UNSUPPORTED, "任务没有声明持续运行")
                    val batch = input.getJSONObject("input").optJSONArray(declaration.optString("batchInputKey"))
                    val names = PluginJson.objects(batch ?: JSONArray()).map { it.optString("name", it.optString("id", "所选项目")) }.joinToString("、").take(600)
                    continuousPermission(flow, task.getString("title"), "每 ${declaration.getInt("intervalSeconds")} 秒错峰检查${if (names.isBlank()) "所选范围" else "：$names"}。仅对本次范围自动尝试声明的操作，需验证时通知你继续。可随时从常驻通知停止。")
                    NativeForegroundTasks.start(app, pkg, session, namespace, input)
                }
                "tasks.foreground.stop" -> { NativeForegroundTasks.stop(app, namespace, input.getString("handle")); JSONObject.NULL }
                "tasks.foreground.list" -> withContext(Dispatchers.IO) { NativeForegroundTasks.list(app, namespace) }
                "device.scan", "device.location", "device.photo" -> {
                    val result = withContext(Dispatchers.Main) { interaction!!.device(name.substringAfter('.')) }
                        ?: throw PluginException(PluginErrorCode.CANCELLED, "已取消设备操作")
                    ensureActive()
                    if (name == "device.photo") withContext(Dispatchers.IO) {
                        val uri = Uri.parse(result.getString("uri"))
                        try { files.import(uri) } finally { app.contentResolver.delete(uri, null, null) }
                    } else result
                }
                "storage.get", "storage.set", "storage.remove" -> callHost(name, input)
                "auth.prompt" -> {
                    val fields = PluginJson.objects(input.getJSONArray("fields"))
                    if (fields.map { it.getString("id") }.distinct().size != fields.size) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "认证字段 ID 重复")
                    val values = PluginExecutionBudget.userInput { withContext(Dispatchers.Main) { interaction!!.authenticate(input, input.optString("imageHandle").takeIf { it.isNotBlank() }?.let(files::file)) } }
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
                    val info = files.info(input.getString("handle"))
                    authorizeAction(request, flow, "上传文件", "文件：${info.getString("name")}")
                    val upload = PluginUpload(files.file(input.getString("handle")), info.getString("name"), info.getString("mime"))
                    callHost("http", credentialRequest(request), true, upload, input.getString("field"), flow) as JSONObject
                }
                "files.open", "files.share" -> {
                    val handle = input.getString("handle"); val info = files.info(handle)
                    val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", files.file(handle))
                    val intent = if (name == "files.share") Intent(Intent.ACTION_SEND).setType(info.getString("mime")).putExtra(Intent.EXTRA_STREAM, uri)
                        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, info.getString("mime"))
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).setClipData(ClipData.newRawUri("文件", uri))
                    withContext(Dispatchers.Main) { app.startActivity(Intent.createChooser(intent, "${info.getString("name")} · 选择接收应用").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; JSONObject.NULL
                }
                "device.clipboard.read" -> {
                    confirm(flow, "读取剪贴板", "允许 ${pkg.manifest.name} 读取当前剪贴板文字？")
                    withContext(Dispatchers.Main) { val clip = app.getSystemService(ClipboardManager::class.java).primaryClip
                        JSONObject().put("text", if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString()?.take(8000).orEmpty() else "") }
                }
                "device.clipboard.write" -> {
                    if (dataGuard.sensitive()) confirm(flow, "复制个人数据", input.getString("text").take(8000))
                    withContext(Dispatchers.Main) { app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(pkg.manifest.name, input.getString("text"))); JSONObject.NULL }
                }
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
                    authorizeAction(JSONObject().put("url", url.toString()).put("purpose", "query"), flow, "打开网页", "${pkg.manifest.name} 请求打开此网站")
                    withContext(Dispatchers.Main) { ensureActive(); dataGuard.requireNetwork(url); app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; JSONObject.NULL
                }
                "runtime.cancel" -> { cancelEffects(PluginJson.strings(input.getJSONArray("effectIds"))); JSONObject.NULL }
                "data.query" -> NativePluginRunner.invoke(app, pkg, session, "data.query", input, JSONObject().put("capabilities", capabilities()), active = active)
                else -> throw PluginException(PluginErrorCode.UNSUPPORTED, "未实现的宿主能力")
            }
        }
        ensureActive()
        if (disclosureOrigin != null) authorizeDisclosure(disclosureOrigin, flow)
        ensureActive()
        if (name != "privacy.revoke") dataGuard.requireCurrent()
        schema.validate(result, descriptor.getJSONObject("output"))
        return result
    }
    private fun requireNetworkPermission() { if ("network" !in pkg.manifest.permissions) denied("文件传输还需要网络权限") }
    private suspend fun continuousPermission(flow: NativeFlow, title: String, message: String) {
        confirm(flow, title, message)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (!withContext(Dispatchers.Main) { interaction!!.notificationPermission() }) denied("持续任务需要通知权限，以便查看和停止")
        }
        if (!app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) denied("请先开启 App 通知，以便查看和停止持续任务")
    }
    private suspend fun authorizeDisclosure(url: String, flow: NativeFlow) {
        dataGuard.requireCurrent()
        val destination = url.toHttpUrlOrNull() ?: denied("地址无效")
        if (!dataGuard.allowed(destination)) {
            val declaration = dataGuard.declaration(destination) ?: denied("插件未声明个人数据接收方")
            confirm(flow, "允许向网站提供个人数据", "${pkg.manifest.name} 将向 ${PluginAuthScope.origin(destination)} 提供学业数据。用途：${declaration.getString("purpose")}。允许后可在插件详情撤销；App 无法控制网站收到数据后的使用。")
            dataGuard.authorize(destination)
        }
    }
    private suspend fun authorizeAction(request: JSONObject, flow: NativeFlow, title: String, summary: String) {
        val destination = request.getString("url").toHttpUrlOrNull() ?: denied("地址无效")
        PluginNetworkPolicy(pkg.manifest.network).requireAllowed(destination, request.optString("method", "GET"), request.getString("purpose"), request.optJSONObject("form"))
        if (foregroundGrant != null) {
            foregroundGrant.requireRequest(destination, request.optString("method", "GET"), request.getString("purpose"), request.optJSONObject("form"))
            dataGuard.requireNetwork(destination)
            return
        }
        dataGuard.requireCurrent()
        val disclosure = if (dataGuard.allowed(destination)) null else (dataGuard.declaration(destination)
            ?: denied("插件未声明个人数据接收方"))
        if (disclosure == null && flow.userGesture && flow.submission?.matches(request) == true) {
            dataGuard.requireNetwork(destination)
            return
        }
        val message = summary + "\n接收网站：${PluginAuthScope.origin(destination)}" +
            "\n请求：${request.optString("method", "GET")} ${destination.encodedPath.take(200)}" +
            (request.opt("body")?.let { "\n提交内容（最多显示 1000 字）：\n" + it.toString().take(1000) } ?: request.optJSONObject("form")?.let { "\n表单字段：" + it.keys().asSequence().take(30).joinToString("、") } ?: "") +
            (disclosure?.let { "\n将提供学业数据，用途：${it.getString("purpose")}。记住此接收方授权，可在插件详情撤销。App 无法控制网站收到数据后的使用。" } ?: "")
        confirm(flow, title, message)
        dataGuard.requireCurrent()
        if (disclosure != null) dataGuard.authorize(destination)
        dataGuard.requireNetwork(destination)
    }
    private suspend fun network(request: JSONObject, flow: NativeFlow): JSONObject {
        if (request.optString("purpose") == "mutation") authorizeAction(request, flow, "确认提交", "${pkg.manifest.name} 请求提交数据")
        else authorizeDisclosure(request.getString("url"), flow)
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
    private suspend fun callHost(method: String, input: JSONObject, confirmed: Boolean = false, upload: PluginUpload? = null, field: String = "file", flow: NativeFlow? = null,
        shared: PluginAcademicSession? = null, grant: String = "", shareToken: Boolean = false): Any? = suspendCancellableCoroutine { continuation ->
        val operation = PluginOperation(shared?.session ?: session, pkg.manifest, "host.effect", development = !pkg.official, confirmed = confirmed, packageDigest = pkg.digest,
            scopeStillActive = { shared?.requireGrant(grant); active() && PluginServiceAccounts(app).current(pkg, session) })
        shared?.track(grant, operation)
        operations.add(operation)
        val worker = ioScope.launch {
            val lease = PluginVersionLeases.acquire(pkg.manifest.id)
            try {
                val host = PluginHost(operation, File(app.filesDir, "academic-plugin-storage"), shared?.cookies(grant) ?: PluginWebSessionCookies.jar(app, pkg, session, active),
                    sharedSite = { shared?.siteAuthorized() == true },
                    sharedApproval = shared?.let { access -> { request ->
                        // Initial request was confirmed/classified before entering this worker.
                        // A redirect must not inherit consent for a different unreviewed endpoint.
                        if (!access.siteAuthorized() && request.getString("url") != input.getString("url") && access.operation(request)?.optString("risk") != "read")
                            throw PluginException(PluginErrorCode.PERMISSION_DENIED, "跳转后的端点未经审核，请单独确认")
                        access.siteAuthorized() && request.getString("purpose") == "mutation"
                    } },
                    sharedToken = if (shareToken) shared?.let { access -> { url -> access.tokenHeader(grant, url) } } else null,
                    sharedRequest = shared?.let { access -> { url, verb, purpose, form -> access.requireRequest(grant, url, verb, purpose, form) } }, dataGuard = dataGuard,
                    requestGuard = foregroundGrant?.let { taskGrant -> { url, verb, purpose, form -> taskGrant.requireRequest(url, verb, purpose, form) } })
                val value = if (upload != null) host.upload(input, upload, field) else {
                    val response = host.call(method, input)
                    if (!response.getBoolean("ok")) { val error = response.getJSONObject("error"); throw PluginException(PluginErrorCode.valueOf(error.getString("code")), error.getString("message")) }
                    response.opt("data") ?: JSONObject.NULL
                }
                if (continuation.isActive) continuation.resume(value)
            } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(if (error is PluginException) operation.failure(error.code, error.message.orEmpty()) else error) }
            finally { operation.close(); shared?.untrack(operation); operations.remove(operation); lease.close() }
        }
        continuation.invokeOnCancellation {
            operation.close()
            if (operation.mutationSent) flow?.markUnknown()
            worker.cancel()
        }
    }
    fun close() { operations.forEach(PluginOperation::close); operations.clear(); ioScope.cancel(); if (active()) PluginSessionCookies.save(app, pkg, session) }
    companion object {
        val PLATFORM = setOf("privacy.status", "privacy.revoke", "pages.register", "pages.open", "pages.close", "pages.unregister", "accounts.select", "accounts.remove", "services.discover", "services.call", "workflow.prepare", "workflow.step", "workflow.reconcile", "workflow.cancel", "workflow.list", "academic.study.snapshot", "academic.study.refresh", "academic.schedule.preview", "academic.schedule.confirm", "academic.session.authorize", "academic.session.request", "academic.session.revoke")
        val IMPLEMENTED = setOf("userscript.interact", "userscript.action", "device.location.pick", "images.match", "images.puzzle", "browser.session.open", "browser.session.status", "browser.session.close", "userscript.configure", "records.upsert", "records.query", "ui.viewport", "ui.components", "ui.navigation", "network.request", "storage.get", "storage.set", "storage.remove", "auth.prompt", "credentials.find", "credentials.remove", "session.save", "session.restore", "session.clear", "files.pick", "files.create", "files.read", "files.write", "files.remove", "files.download", "files.upload", "files.open", "files.share", "device.clipboard.read", "device.clipboard.write", "device.haptic", "tasks.schedule", "tasks.cancel", "tasks.list", "notifications.post", "navigation.page", "navigation.back", "navigation.url", "runtime.cancel", "data.query", "userscript.status", "userscript.update", "userscript.rollback", "userscript.start", "userscript.control", "tasks.foreground.start", "tasks.foreground.stop", "tasks.foreground.list", "device.scan", "device.location", "device.photo")
    }
}

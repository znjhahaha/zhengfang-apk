package com.tyust.course.academic.plugin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.ui.system.*
import com.tyust.course.ui.theme.CourseSelectorTheme
import kotlinx.coroutines.*
import org.json.JSONObject

class PluginCenterActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TARGET_SCHOOL = "plugin_target_school"
        const val EXTRA_FROM_LOGIN = "plugin_from_login"
        const val EXTRA_PROVIDER = "plugin_provider"
        const val EXTRA_CHANGED = "plugin_changed"
        fun intent(context: android.content.Context, school: SchoolConfig?, fromLogin: Boolean = false) =
            Intent(context, PluginCenterActivity::class.java).putExtra(EXTRA_TARGET_SCHOOL, school?.id)
                .putExtra(EXTRA_FROM_LOGIN, fromLogin)
    }
    private var generation by mutableIntStateOf(0)
    private var targetSchoolId by mutableStateOf<String?>(null)
    private var changed = false
    private var packageSnapshot: String? = null
    private val fromLogin get() = intent.getBooleanExtra(EXTRA_FROM_LOGIN, false)
    override fun onResume() { super.onResume(); generation++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetSchoolId = if (savedInstanceState != null) savedInstanceState.getString(EXTRA_TARGET_SCHOOL)
            else if (intent.hasExtra(EXTRA_TARGET_SCHOOL)) intent.getStringExtra(EXTRA_TARGET_SCHOOL)
            else UserManager.getInstance().currentSchool?.id
        targetSchoolId = targetSchoolId?.takeIf { UserManager.getInstance().getSchoolById(it) != null }
        changed = savedInstanceState?.getBoolean(EXTRA_CHANGED) ?: false
        packageSnapshot = savedInstanceState?.getString("packages")
        setContent { CourseSelectorTheme { GlassWindowHost { Center() } } }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_TARGET_SCHOOL, targetSchoolId)
        outState.putBoolean(EXTRA_CHANGED, changed)
        outState.putString("packages", packageSnapshot)
        super.onSaveInstanceState(outState)
    }
    override fun finish() {
        val school = targetSchoolId?.let { UserManager.getInstance().getSchoolById(it) }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TARGET_SCHOOL, school?.id).putExtra(EXTRA_CHANGED, changed)
            .putExtra(EXTRA_PROVIDER, school?.let { runCatching { AcademicProviderRegistry.resolve(it)?.manifest?.id ?: "builtin.auto" }.getOrNull() }))
        super.finish()
    }

    @Composable private fun Center() {
        val scope = rememberCoroutineScope()
        var packages by remember { mutableStateOf<List<PluginPackage>>(emptyList()) }
        var catalog by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
        var selected by remember { mutableStateOf<PluginPackage?>(null) }
        var uninstall by remember { mutableStateOf<PluginPackage?>(null) }
        var installedView by remember { mutableStateOf(false) }
        var chooseProvider by remember { mutableStateOf(false) }
        var chooseSchool by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        val school = remember(targetSchoolId, generation) { targetSchoolId?.let { UserManager.getInstance().getSchoolById(it) } }
        val candidates = remember(packages, generation, school) { school?.let(AcademicProviderRegistry::candidates).orEmpty() }
        val choice = remember(generation, school) { school?.let(AcademicProviderRegistry::manualChoice).orEmpty() }
        val resolved = remember(packages, generation, school) { school?.let { runCatching { AcademicProviderRegistry.resolve(it) } } }
        val current = resolved?.getOrNull()

        suspend fun refresh() {
            packages = withContext(Dispatchers.IO) { AcademicProviderRegistry.reload(); AcademicProviderRegistry.packages().list() }
            val snapshot = packages.sortedBy { it.manifest.id }.joinToString { it.digest }
            if (packageSnapshot != null && packageSnapshot != snapshot) changed = true
            packageSnapshot = snapshot
        }
        fun run(block: suspend () -> Unit) {
            if (busy) return
            scope.launch {
                busy = true; message = ""
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { message = error.message ?: "操作暂时未完成，请重试" }
                finally { busy = false }
            }
        }
        LaunchedEffect(generation) { refresh() }
        fun select(id: String?) {
            school?.let { AcademicProviderRegistry.choose(it, id) }
            changed = true
            chooseProvider = false; generation++
            message = "已保存本校选择，正在运行的任务继续使用原适配"
        }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) run {
                selected = withContext(Dispatchers.IO) {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytesBounded(PluginLimits.PACKAGE_BYTES) }
                        ?: error("无法读取插件文件")
                    AcademicProviderRegistry.packages().install(bytes, allowDevelopment = false)
                }
                changed = true; refresh(); generation++
                message = "导入完成，签名与兼容性已验证"
            }
        }
        val developerTools = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.data?.getBooleanExtra(EXTRA_CHANGED, false) == true) changed = true
            result.data?.getStringExtra(EXTRA_TARGET_SCHOOL)?.takeIf { UserManager.getInstance().getSchoolById(it) != null }?.let { targetSchoolId = it }
            generation++
        }
        fun bind(pkg: PluginPackage, returnToLogin: Boolean) {
            val target = school ?: return
            AcademicProviderRegistry.setSchoolEnabled(pkg.manifest.id, target, true)
            select(pkg.manifest.id)
            selected = null
            if (returnToLogin) finish()
        }

        GlassPageScaffold(title = "插件中心", subtitle = school?.name ?: "学校适配与通用服务", onBack = { finish() }, actions = {
            SystemIconButton(Icons.Outlined.MoreHoriz, "更多", { menu = true })
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("导入、回滚与开发工具") }, onClick = {
                    menu = false; developerTools.launch(Intent(this@PluginCenterActivity, PluginDeveloperActivity::class.java)
                        .putExtra(EXTRA_TARGET_SCHOOL, targetSchoolId))
                })
                DropdownMenuItem(text = { Text("开发文档与交流群") }, onClick = { menu = false; web("/developers") })
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(
                start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiquidButton({ installedView = false }, modifier = Modifier.weight(1f),
                        style = if (!installedView) LiquidButtonStyle.Tinted else LiquidButtonStyle.Surface) { Text(if (school == null) "全部" else "本校") }
                    LiquidButton({ installedView = true }, modifier = Modifier.weight(1f),
                        style = if (installedView) LiquidButtonStyle.Tinted else LiquidButtonStyle.Surface) { Text("已安装") }
                }
                InsetGroupedRow(title = school?.name ?: "选择学校", subtitle = if (fromLogin) "安装与选择适配后，返回登录页继续" else "按学校管理插件",
                    icon = Icons.Outlined.School, onClick = { chooseSchool = true }, showDivider = false,
                    trailing = { Icon(Icons.Outlined.ChevronRight, "选择学校") })
                if (fromLogin) LiquidButton(onClick = {
                    if (school == null) chooseSchool = true else if (resolved?.isFailure == true) chooseProvider = true else finish()
                }, modifier = Modifier.fillMaxWidth(), enabled = !busy, style = LiquidButtonStyle.Tinted) {
                    Text(if (resolved?.isFailure == true) "选择适配后返回登录" else "返回登录")
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
                if (!installedView) {
                    InsetGroupedSection(header = "当前教务适配", footer = if (choice.isBlank()) "按学校域名、端口和路径自动匹配已安装插件。" else "已保存手动选择，可随时恢复自动匹配。") {
                        InsetGroupedRow(title = when {
                            school == null -> "尚未选择学校"
                            resolved?.isFailure == true -> "需要选择本校适配"
                            current != null -> current.manifest.name
                            else -> "内置适配"
                        }, subtitle = when {
                            resolved?.isFailure == true -> resolved.exceptionOrNull()?.message
                            current != null -> "正在生效 · ${current.manifest.version}"
                            else -> "没有匹配插件时使用 App 内置适配"
                        }, icon = Icons.Outlined.School, onClick = { if (school != null) chooseProvider = true else chooseSchool = true }, showDivider = false,
                            trailing = { Icon(Icons.Outlined.ChevronRight, null) })
                    }
                }
                val shown = if (installedView) packages else packages.filter { pkg ->
                    school?.let { AcademicProviderRegistry.matches(pkg, it) } ?: true
                }
                InsetGroupedSection(header = if (installedView || school == null) "已安装 · ${shown.size}" else "本校插件 · ${shown.size}") {
                    if (shown.isEmpty()) InsetGroupedRow(title = "还没有可用插件", subtitle = "从插件商店获取学校适配或通用服务", icon = Icons.Outlined.Extension, showDivider = false)
                    shown.sortedBy { it.manifest.name }.forEachIndexed { index, pkg ->
                        val enabled = school?.let { AcademicProviderRegistry.isEnabled(pkg.manifest.id, it) } ?: true
                        val state = when {
                            !enabled -> "本校已停用"
                            current?.digest == pkg.digest -> "教务适配生效中"
                            pkg.manifest.isAcademic -> if (school != null && AcademicProviderRegistry.matches(pkg, school)) "可选教务适配" else "适用于其他学校"
                            school != null && !AcademicProviderRegistry.matches(pkg, school) -> "适用于其他学校"
                            else -> "服务已启用"
                        }
                        InsetGroupedRow(title = pkg.manifest.name, subtitle = "$state · ${pkg.manifest.version}", icon = Icons.Outlined.Extension,
                            onClick = { selected = pkg }, showDivider = index < shown.lastIndex, modifier = Modifier.testTag("plugin-${pkg.manifest.id}"),
                            trailing = { Icon(Icons.Outlined.ChevronRight, "插件详情") })
                    }
                }
                InsetGroupedSection(header = "获取插件") {
                    InsetGroupedRow(title = "浏览并安装插件", subtitle = "下载前自动验证签名与兼容性", icon = Icons.Outlined.CloudDownload,
                        enabled = !busy, onClick = { run { catalog = AcademicProviderRegistry.catalog().check(); if (catalog.isEmpty()) message = "目录暂时没有可用插件" } },
                        trailing = { Icon(Icons.Outlined.ChevronRight, null) })
                    InsetGroupedRow(title = "从文件安装", subtitle = "导入已签名的插件包", icon = Icons.Outlined.FileOpen,
                        enabled = !busy, onClick = { importer.launch(arrayOf("*/*")) }, trailing = { Icon(Icons.Outlined.ChevronRight, null) })
                    InsetGroupedRow(title = "打开插件商店", subtitle = "搜索学校、查看介绍与作者", icon = Icons.Outlined.Language,
                        onClick = { web("/") }, showDivider = false, trailing = { Icon(Icons.Outlined.OpenInNew, null) })
                }
                if (catalog.isNotEmpty()) InsetGroupedSection(header = "可安装插件") {
                    catalog.forEachIndexed { index, entry ->
                        val id = entry.getString("id")
                        val installed = packages.firstOrNull { it.manifest.id == id }
                        val latest = installed?.official == true && installed.manifest.version == entry.getString("version")
                        InsetGroupedRow(title = entry.optString("name", id), subtitle = entry.optString("description").ifBlank { "版本 ${entry.getString("version")}" },
                            showDivider = index < catalog.lastIndex, trailing = {
                                LiquidButton(onClick = { run {
                                    selected = AcademicProviderRegistry.catalog().update(id); refresh()
                                    changed = true; generation++
                                    message = "安装完成，可在详情中选择本校适配；已有手动选择保持不变"
                                } }, enabled = !busy && !latest, minHeight = 44.dp, horizontalPadding = 16.dp, style = LiquidButtonStyle.Tinted,
                                    modifier = Modifier.testTag("catalog-install-$id")) { Text(if (latest) "已安装" else if (installed == null) "安装" else "更新") }
                            })
                    }
                }
                InsetGroupedSection(header = "一起适配更多学校") {
                    InsetGroupedRow(title = "招募校园插件开发者", subtitle = "欢迎有能力的同学为自己的学校开发独立适配。开发文档、示例与工具已集中提供。QQ群 1074017033",
                        icon = Icons.Outlined.Code, onClick = { web("/developers") }, showDivider = false, trailing = { Icon(Icons.Outlined.OpenInNew, null) })
                }
            }
        }
        if (chooseSchool) com.tyust.course.ui.screen.SchoolManagementDialog(
            selectedSchoolId = targetSchoolId, onDismiss = { chooseSchool = false },
            onSelect = { targetSchoolId = it.id; generation++ }, onChanged = {
                if (targetSchoolId?.let { UserManager.getInstance().getSchoolById(it) } == null) targetSchoolId = null
                changed = true; generation++
            })
        if (chooseProvider && school != null) SystemDialog(onDismissRequest = { chooseProvider = false }, title = { Text("本校教务适配") },
            confirmButton = { TextButton(onClick = { chooseProvider = false }) { Text("完成") } }) {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { select(null) }) { Text(if (choice.isBlank()) "自动匹配（当前）" else "自动匹配") }
                TextButton(onClick = { select("builtin.auto") }) { Text(if (choice.startsWith("builtin.")) "内置适配（当前）" else "内置适配") }
                candidates.forEach { pkg ->
                    TextButton(onClick = { select(pkg.manifest.id) }) { Text(pkg.manifest.name + if (choice == pkg.manifest.id) "（当前）" else "") }
                }
                if (candidates.size > 1) Text("本校有多个适配，请选择一个；选择会为本校记忆。", style = MaterialTheme.typography.bodySmall)
            }
        }
        selected?.let { pkg ->
            val metadata = if (pkg.official) AcademicProviderRegistry.packages().metadata(pkg.manifest.id) else null
            val authorRef = metadata?.optString("authorRef")?.ifBlank { null } ?: pkg.manifest.json.optString("authorRef")
            val author = authorRef.takeIf { it.isNotBlank() }?.let { AcademicProviderRegistry.packages().author(it) }
            val features = (metadata?.optJSONArray("features") ?: pkg.manifest.json.optJSONArray("features"))?.let(PluginJson::strings)
                ?: pkg.manifest.capabilities.map(::capabilityName)
            val enabled = school?.let { AcademicProviderRegistry.isEnabled(pkg.manifest.id, it) } ?: true
            val canBind = pkg.manifest.isAcademic && school != null && AcademicProviderRegistry.matches(pkg, school)
            SystemDialog(onDismissRequest = { selected = null }, title = { Text(pkg.manifest.name) },
                confirmButton = {
                    if (fromLogin && canBind) TextButton(onClick = { bind(pkg, true) }) { Text("使用此适配并返回登录") }
                    else TextButton(onClick = { selected = null }) { Text("完成") }
                }) {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(metadata?.optString("description")?.ifBlank { null } ?: pkg.manifest.json.optString("description").ifBlank { "作者尚未提供介绍" })
                    Detail("声明支持", features.joinToString("、").ifBlank { "未声明功能" })
                    val verification = metadata?.optJSONObject("verification")
                    Detail("实际验证", verification?.let { item ->
                        listOfNotNull(item.optString("summary").takeIf { it.isNotBlank() },
                            listOf(item.optString("date"), item.optString("environment")).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotBlank() },
                            item.optJSONArray("checks")?.let(PluginJson::strings)?.joinToString("、"),
                            item.optJSONArray("limitations")?.let(PluginJson::strings)?.joinToString("\n")).filter { it.isNotBlank() }.joinToString("\n").ifBlank { null }
                    } ?: "尚无公开验收记录")
                    Detail("作者", author?.optString("name")?.ifBlank { null } ?: pkg.manifest.json.optString("author").ifBlank { "作者未公开" })
                    author?.optString("bio")?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (authorRef.isNotBlank()) TextButton(onClick = { web("/authors/" + Uri.encode(authorRef)) }) { Text("作者主页") }
                    Detail("版本与兼容", "${pkg.manifest.version} · API ${pkg.manifest.apiVersion}" + if (pkg.official) " · 已验证签名" else " · 开发包")
                    Detail("权限", pkg.manifest.permissions.joinToString("、") { permissionName(it) }.ifBlank { "无额外权限" })
                    Detail("网络范围", pkg.manifest.network.toString())
                    val matchRules = metadata?.optJSONArray("matches") ?: pkg.manifest.json.optJSONArray("matches")
                    Detail("适用范围", matchRules?.let(PluginJson::objects)?.joinToString("\n") { it.getString("host") + (if (it.has("port")) ":${it.getInt("port")}" else "") + it.optString("pathPrefix", "/") }
                        ?.ifBlank { null } ?: pkg.manifest.json.optJSONObject("school")?.optString("name") ?: "通用服务")
                    Detail("版本说明", metadata?.optString("releaseNotes")?.ifBlank { null } ?: pkg.manifest.json.optString("releaseNotes").ifBlank { "暂无版本说明" })
                    val releases = (metadata?.optJSONArray("releases") ?: metadata?.optJSONArray("versions"))?.let(PluginJson::objects).orEmpty()
                    releases.forEach { Detail(it.getString("version"), it.optString("notes").ifBlank { "未提供说明" }) }
                    if (!fromLogin && school?.id == UserManager.getInstance().currentSchool?.id && (pkg.manifest.isService || pkg.manifest.isNative && pkg.manifest.contributes.getJSONArray("pages").length() > 0) && enabled && (school == null || AcademicProviderRegistry.matches(pkg, school))) {
                        LiquidButton(onClick = { selected = null; if (pkg.manifest.isNative) NativePluginActivity.open(this@PluginCenterActivity, pkg) else ServicePluginActivity.open(this@PluginCenterActivity, pkg) },
                            modifier = Modifier.fillMaxWidth(), style = LiquidButtonStyle.Tinted) { Text("打开插件") }
                    }
                    if (pkg.manifest.isAcademic && school != null && AcademicProviderRegistry.matches(pkg, school)) {
                        TextButton(onClick = { bind(pkg, false) }) { Text("用作本校教务适配") }
                    }
                    if (pkg.manifest.isAcademic && UserManager.getInstance().getSchoolById(pkg.manifest.school.getString("id")) == null) {
                        TextButton(onClick = {
                            val added = AcademicProviderRegistry.school(pkg)
                            UserManager.getInstance().addCustomSchool(added)
                            targetSchoolId = added.id; changed = true; generation++
                            message = "已添加学校，可使用此适配返回登录"
                        }) { Text("添加到学校列表") }
                    }
                    if (school != null && AcademicProviderRegistry.matches(pkg, school)) TextButton(onClick = {
                        AcademicProviderRegistry.setSchoolEnabled(pkg.manifest.id, school, !enabled)
                        changed = true; generation++; selected = null
                    }) { Text(if (enabled) "本校停用" else "本校启用") }
                    TextButton(onClick = { PluginFeedback.open(this@PluginCenterActivity, pkg) }) { Text("快捷反馈") }
                    TextButton(onClick = { selected = null; uninstall = pkg }) { Text("卸载插件", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        uninstall?.let { pkg -> SystemDialog(onDismissRequest = { uninstall = null }, title = { Text("卸载 ${pkg.manifest.name}？") },
            confirmButton = { TextButton(onClick = { run { withContext(Dispatchers.IO) { AcademicProviderRegistry.packages().deactivate(pkg.manifest.id) }; refresh(); generation++ }; uninstall = null }) { Text("卸载") } },
            dismissButton = { TextButton(onClick = { uninstall = null }) { Text("取消") } }) {
            Text("将从所有学校的可用插件中移除。已开始的后台任务仍使用原来的包版本。仅想在当前学校关闭时，请使用“本校停用”。")
        } }
    }
    @Composable private fun Detail(label: String, value: String) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
    private fun web(path: String) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AcademicProviderRegistry.OFFICIAL_WEBSITE + path))) }
    private fun permissionName(name: String) = mapOf("network" to "网络", "storage" to "隔离存储", "credentials" to "加密凭据", "session" to "会话",
        "files" to "文件", "device.clipboard" to "剪贴板", "device.haptics" to "触感", "tasks" to "后台任务", "notifications" to "通知",
        "navigation" to "导航", "auth" to "认证", "runtime" to "运行控制")[name] ?: name
    private fun capabilityName(name: String) = mapOf("ui.init" to "原生页面", "ui.reduce" to "交互与状态", "task.run" to "后台流程", "data.query" to "数据提供者",
        "auth.start" to "登录", "auth.resume" to "继续认证", "auth.validate" to "会话校验", "auth.refreshCaptcha" to "验证码",
        "study.terms" to "学期", "study.schedule" to "课表", "study.grades" to "成绩", "study.gradeDetails" to "成绩明细", "study.exams" to "考试",
        "study.calendar" to "校历与作息", "selection.select" to "选课", "selection.drop" to "退课", "service.page" to "服务页面", "service.action" to "服务操作")[name] ?: name
}

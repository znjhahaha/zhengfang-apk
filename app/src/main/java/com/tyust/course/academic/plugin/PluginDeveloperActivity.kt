package com.tyust.course.academic.plugin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tyust.course.academic.AcademicException
import com.tyust.course.academic.AcademicSessionStore
import com.tyust.course.manager.UserManager
import com.tyust.course.ui.system.*
import com.tyust.course.ui.theme.CourseSelectorTheme
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

class PluginDeveloperActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CourseSelectorTheme { GlassWindowHost { Center() } } }
    }
    @Composable private fun Center() {
        val scope = rememberCoroutineScope()
        var packages by remember { mutableStateOf<List<PluginPackage>>(emptyList()) }
        var selected by remember { mutableStateOf<PluginPackage?>(null) }
        var developer by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var feedback by remember { mutableStateOf("") }
        var report by remember { mutableStateOf("{}") }
        var operation by remember { mutableStateOf("study.terms") }
        var args by remember { mutableStateOf("{}") }
        var showMethods by remember { mutableStateOf(false) }
        var confirmWrite by remember { mutableStateOf(false) }
        var catalogUrl by remember { mutableStateOf("http://127.0.0.1:8787/catalog.json") }
        var catalogKey by remember { mutableStateOf("") }
        var catalogEntries by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
        var bindCandidate by remember { mutableStateOf<PluginPackage?>(null) }
        val sessions = remember { AcademicSessionStore() }
        val sessionId = remember { "dev:${UUID.randomUUID()}" }
        val adapter = remember(selected?.digest) { selected?.takeUnless { it.manifest.isNative }?.let { pkg ->
            val school = com.tyust.course.model.SchoolConfig.fromJson(pkg.manifest.school)
            AcademicProviderRegistry.adapterFor(pkg, school, sessions.session(school.id, sessionId, school.fullBasePath))
        } }
        DisposableEffect(Unit) { onDispose { sessions.clear() } }
        suspend fun refresh() { packages = withContext(Dispatchers.IO) { AcademicProviderRegistry.packages().list() } }
        LaunchedEffect(Unit) { refresh() }
        fun runOperation(confirmed: Boolean) {
            val current = adapter ?: return
            scope.launch {
                busy = true
                val result = try { withContext(Dispatchers.IO) { PluginJson.success(current.invoke(operation, PluginJson.parse(args), confirmed)) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { JSONObject().put("ok", false).put("error", JSONObject().put("code", (e as? AcademicException)?.status?.name ?: "VALIDATION_FAILED").put("message", e.message.orEmpty())) }
                finally { busy = false }
                report = JSONObject().put("provider", current.pinned.manifest.id).put("version", current.version).put("operation", operation)
                    .put("result", result).put("trace", JSONArray(current.lastTrace)).toString(2)
                feedback = if (result.optBoolean("ok")) "调用完成" else "调用未成功，请查看结果"
            }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                busy = true
                try {
                    selected = withContext(Dispatchers.IO) {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytesBounded(PluginLimits.PACKAGE_BYTES) }
                            ?: throw IllegalArgumentException("无法读取文件")
                        AcademicProviderRegistry.packages().install(bytes, developer)
                    }
                    AcademicProviderRegistry.reload(); refresh()
                    val imported = selected
                    feedback = if (imported != null && AcademicProviderRegistry.packages().activeDigest(imported.manifest.id) != imported.digest)
                        "已验证并暂存，请在插件管理中查看待更新版本及授权" else "导入完成"
                    selected = imported?.let { AcademicProviderRegistry.packages().active(it.manifest.id) }
                    operation = selected?.manifest?.capabilities?.firstOrNull() ?: "study.terms"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { feedback = e.message ?: "导入失败" }
                finally { busy = false }
            }
        }
        val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) scope.launch(Dispatchers.IO) { contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) } }
        }
        fun loadCatalog() { scope.launch {
            busy = true
            try {
                val entries = AcademicProviderRegistry.catalog()?.check()
                catalogEntries = entries.orEmpty()
                feedback = when { entries == null -> "当前版本未配置适配目录"; entries.isEmpty() -> "目录中暂时没有适配"; else -> "目录已更新，可选择适配进行安装" }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { feedback = e.message.orEmpty() }
            finally { busy = false }
        } }
        fun installFromCatalog(id: String, addSchool: Boolean) { scope.launch {
            busy = true
            try {
                val pkg = AcademicProviderRegistry.catalog()?.update(id) ?: throw IllegalStateException("尚未配置适配目录")
                AcademicProviderRegistry.reload(); refresh(); selected = pkg
                operation = pkg.manifest.capabilities.firstOrNull() ?: "study.terms"
                args = defaultArgs(operation)
                feedback = "${pkg.manifest.name} 安装完成"
                if (addSchool && pkg.manifest.isAcademic) bindCandidate = pkg
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { feedback = e.message ?: "安装失败，请重试" }
            finally { busy = false }
        } }
        GlassPageScaffold(title = "插件高级工具", subtitle = "学校连接与校园服务", onBack = { finish() }) { padding ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(
                    start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                val school = if (intent.hasExtra(PluginCenterActivity.EXTRA_TARGET_SCHOOL))
                    intent.getStringExtra(PluginCenterActivity.EXTRA_TARGET_SCHOOL)?.let { UserManager.getInstance().getSchoolById(it) }
                else UserManager.getInstance().currentSchool
                val current = school?.let { runCatching { AcademicProviderRegistry.resolve(it) }.getOrNull() }
                InsetGroupedSection(header = "当前学校") {
                    InsetGroupedRow(title = school?.name ?: "尚未选择学校", icon = Icons.Outlined.School,
                        subtitle = current?.let { "${it.manifest.name} · ${it.manifest.version}" } ?: "内置适配 · 无需额外安装",
                        showDivider = false)
                }
                InsetGroupedSection(header = "获取插件", footer = if (AcademicProviderRegistry.usingLocalCatalog) "当前使用本地调试目录，重启应用后恢复正式目录。" else "正式目录已启用，下载与更新均会校验签名。") {
                    InsetGroupedRow(title = "浏览适配目录", subtitle = "查看可用学校，下载或更新适配", icon = Icons.Outlined.CloudDownload,
                        enabled = !busy, onClick = ::loadCatalog, trailing = { ForwardIcon() })
                    InsetGroupedRow(title = "导入适配包", subtitle = "支持学校适配与校园服务插件", icon = Icons.Outlined.FileOpen,
                        enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.testTag("plugin-import"), trailing = { ForwardIcon() })
                    InsetGroupedRow(title = "访问插件网站", subtitle = "安装指南、源码提交与作者交流", icon = Icons.Outlined.Language,
                        onClick = { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(AcademicProviderRegistry.OFFICIAL_WEBSITE))) }, showDivider = false, trailing = { ForwardIcon() })
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
                if (catalogEntries.isNotEmpty()) {
                    InsetGroupedSection(header = "目录中的适配", footer = "安装前会验证来源和包内容，安装后可添加学校并登录。") {
                        catalogEntries.forEachIndexed { index, entry ->
                            val id = entry.getString("id")
                            val installed = packages.firstOrNull { it.manifest.id == id }
                            val upToDate = installed?.manifest?.version == entry.getString("version") && installed.official
                            InsetGroupedRow(title = entry.optString("name", id), subtitle = "版本 ${entry.getString("version")}",
                                icon = Icons.Outlined.CloudDownload, showDivider = index < catalogEntries.lastIndex,
                                trailing = {
                                    LiquidButton(onClick = { installFromCatalog(id, installed == null) }, enabled = !busy && !upToDate,
                                        modifier = Modifier.testTag("catalog-install-$id"), style = LiquidButtonStyle.Tinted,
                                        minHeight = 40.dp, horizontalPadding = 16.dp) {
                                        Text(if (upToDate) "已安装" else if (installed != null) "更新" else "安装", style = MaterialTheme.typography.labelLarge)
                                    }
                                })
                        }
                    }
                }
                InsetGroupedSection(header = "已安装 · ${packages.size} 项") {
                    if (packages.isEmpty()) InsetGroupedRow(title = "还没有安装适配", subtitle = "可以浏览目录，或导入本地适配包",
                        icon = Icons.Outlined.Extension, showDivider = false)
                    packages.forEachIndexed { index, pkg ->
                        val chosen = selected?.digest == pkg.digest
                        InsetGroupedRow(title = pkg.manifest.name,
                            subtitle = "${packageKind(pkg)} · ${pkg.manifest.version} · ${if (pkg.official) "已验签" else "本地开发"}",
                            icon = packageIcon(pkg), enabled = !busy, showDivider = index < packages.lastIndex,
                            iconTint = if (pkg.official) androidx.compose.ui.graphics.Color(0xFF18796B) else MaterialTheme.colorScheme.primary,
                            onClick = {
                                selected = pkg; operation = pkg.manifest.capabilities.firstOrNull() ?: "study.terms"
                                args = defaultArgs(operation); report = "{}"
                            }, trailing = { Icon(if (chosen) Icons.Outlined.CheckCircle else Icons.Outlined.ChevronRight,
                                if (chosen) "已选中" else "查看详情", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) })
                    }
                }
                selected?.let { pkg ->
                    InsetGroupedSection(header = pkg.manifest.name) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("支持能力", style = MaterialTheme.typography.titleSmall)
                            Text(pkg.manifest.capabilities.joinToString(" · ") { capabilityName(it) }.ifBlank { "复用内置适配能力" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            pkg.manifest.json.optString("description").takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (pkg.manifest.isNative) InsetGroupedRow(title = "预览原生插件", icon = Icons.Outlined.Extension, onClick = { NativePluginActivity.open(this@PluginDeveloperActivity, pkg) })
                        else if (pkg.manifest.isService) InsetGroupedRow(title = if (pkg.official) "打开校园服务" else "预览校园服务", icon = Icons.Outlined.School, enabled = !busy,
                            onClick = { ServicePluginActivity.open(this@PluginDeveloperActivity, pkg, preview = !pkg.official) }, trailing = { ForwardIcon() })
                        else InsetGroupedRow(title = "用于此学校", icon = Icons.Outlined.School, enabled = !busy,
                            onClick = { bindCandidate = pkg }, trailing = { ForwardIcon() })
                        InsetGroupedRow(title = "检查并更新适配", icon = Icons.Outlined.SystemUpdate, enabled = !busy,
                            onClick = { installFromCatalog(pkg.manifest.id, false) }, trailing = { ForwardIcon() })
                        InsetGroupedRow(title = "恢复上一版本", icon = Icons.Outlined.History, enabled = !busy,
                            onClick = { scope.launch {
                                busy = true
                                try {
                                    selected = withContext(Dispatchers.IO) { AcademicProviderRegistry.packages().rollback(pkg.manifest.id) }
                                    AcademicProviderRegistry.reload(); refresh(); feedback = "已恢复上一版本"
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { feedback = e.message.orEmpty() }
                                finally { busy = false }
                            } }, trailing = { ForwardIcon() })
                        if (pkg.manifest.isService || pkg.manifest.isNative) InsetGroupedRow(title = "卸载此插件", icon = Icons.Outlined.Close, enabled = !busy, showDivider = false,
                            onClick = { AcademicProviderRegistry.packages().deactivate(pkg.manifest.id); AcademicProviderRegistry.reload(); selected = null; scope.launch { refresh() }; feedback = "已停用校园服务" })
                        else InsetGroupedRow(title = "恢复内置适配", icon = Icons.Outlined.Restore, enabled = !busy, showDivider = false,
                            onClick = {
                                val boundSchool = UserManager.getInstance().getSchoolById(pkg.manifest.school.getString("id"))
                                if (boundSchool != null && pkg.manifest.baseProvider != null) {
                                    boundSchool.academicProvider = pkg.manifest.baseProvider
                                    UserManager.getInstance().updateSchoolConfig(boundSchool)
                                    feedback = "已恢复内置适配，请重新登录"
                                } else feedback = "此学校暂无内置适配"
                            }, trailing = { ForwardIcon() })
                    }
                }
                InsetGroupedSection(header = "开发者工具", footer = if (developer) "开发模式允许导入未签名的本地包，请仅使用可信来源。" else null) {
                    InsetGroupedRow(title = "开发者模式", subtitle = "本地包导入与独立会话调试", icon = Icons.Outlined.Code,
                        showDivider = false, enabled = !busy,
                        trailing = { LiquidSwitch(checked = developer, onCheckedChange = { developer = it }, enabled = !busy,
                            modifier = Modifier.testTag("plugin-developer-toggle")) })
                }
                if (developer && com.tyust.course.BuildConfig.DEBUG) {
                    InsetGroupedSection(header = "本地目录调试") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(catalogUrl, { catalogUrl = it }, label = { Text("本地验收目录 URL") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                            OutlinedTextField(catalogKey, { catalogKey = it }, label = { Text("测试公钥 JSON") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                            LiquidButton(onClick = {
                                try { AcademicProviderRegistry.configureLocalCatalog(catalogUrl, catalogKey); feedback = "本地目录已配置" }
                                catch (e: Exception) { feedback = e.message.orEmpty() }
                            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("应用本地目录") }
                            LiquidButton(onClick = { AcademicProviderRegistry.restoreOfficialCatalog(); feedback = "已恢复正式目录"; loadCatalog() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("恢复正式目录") }
                        }
                    }
                }
                val debugPackage = selected
                if (developer && debugPackage != null && !debugPackage.manifest.isNative && adapter?.effectiveCapabilities?.isNotEmpty() == true) {
                    InsetGroupedSection(header = "独立测试会话", footer = "测试会话与当前账号隔离，选退课调用仍需确认。") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${debugPackage.manifest.id} · ${debugPackage.manifest.version}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box {
                                LiquidButton({ showMethods = true }, enabled = !busy) { Text(operation, style = MaterialTheme.typography.bodyMedium); Icon(Icons.Outlined.ExpandMore, null) }
                                DropdownMenu(showMethods, { showMethods = false }) {
                                    adapter.effectiveCapabilities.forEach { method -> DropdownMenuItem(text = { Text(method) }, onClick = {
                                        operation = method; showMethods = false; args = defaultArgs(method)
                                    }) }
                                }
                            }
                            OutlinedTextField(args, { args = it }, label = { Text("JSON 参数") }, modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = !busy)
                            LiquidButton({ if (operation in setOf("selection.select", "selection.drop", "service.action")) confirmWrite = true else runOperation(false) },
                                enabled = !busy, modifier = Modifier.fillMaxWidth(), style = LiquidButtonStyle.Tinted) {
                                Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp)); Text("运行测试")
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LiquidButton({ adapter?.clearDevelopmentData(); report = "{}"; feedback = "已清除开发会话与数据" }, enabled = !busy,
                                    modifier = Modifier.weight(1f), horizontalPadding = 8.dp) { Text("清除数据", style = MaterialTheme.typography.labelLarge) }
                                LiquidButton({ exporter.launch("plugin-validation.json") }, enabled = !busy,
                                    modifier = Modifier.weight(1f), horizontalPadding = 8.dp) { Text("导出报告", style = MaterialTheme.typography.labelLarge) }
                            }
                            SelectionContainer { Text(report, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
        if (confirmWrite) SystemDialog(onDismissRequest = { confirmWrite = false }, title = { Text("确认测试操作") },
            content = { Text("此操作会执行所选插件接口。模拟插件只修改模拟数据，真实服务可能改变账号记录。") },
            confirmButton = { TextButton({ confirmWrite = false; runOperation(true) }) { Text("确认执行") } },
            dismissButton = { TextButton({ confirmWrite = false }) { Text("取消") } })
        bindCandidate?.let { pkg -> SystemDialog(onDismissRequest = { bindCandidate = null }, title = { Text("使用 ${pkg.manifest.name}") },
            content = { Text("将此适配添加到学校列表，之后可在登录页选择。已有学校和账号 ID 保持不变。") },
            confirmButton = { TextButton({
                val school = AcademicProviderRegistry.school(pkg)
                val user = UserManager.getInstance()
                if (user.getSchoolById(school.id) != null) user.updateSchoolConfig(school) else user.addCustomSchool(school)
                setResult(RESULT_OK, android.content.Intent().putExtra(PluginCenterActivity.EXTRA_CHANGED, true)
                    .putExtra(PluginCenterActivity.EXTRA_TARGET_SCHOOL, school.id))
                feedback = "已添加学校，请从学校列表选择并登录"; bindCandidate = null
            }) { Text("使用适配") } }, dismissButton = { TextButton({ bindCandidate = null }) { Text("取消") } }) }
    }
    @Composable private fun ForwardIcon() = Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    private fun packageKind(pkg: PluginPackage) = when (pkg.manifest.kind) {
        "configuration" -> "学校配置"; "extension" -> "内置扩展"; "service" -> "校园服务"; else -> "独立适配"
    }
    private fun packageIcon(pkg: PluginPackage) = when (pkg.manifest.kind) {
        "configuration" -> Icons.Outlined.Tune; "extension", "service" -> Icons.Outlined.Extension; else -> Icons.Outlined.School
    }
    private fun defaultArgs(method: String) = when (method) {
        "auth.start" -> "{\"username\":\"demo\",\"password\":\"demo\"}"
        "study.schedule", "study.exams", "study.calendar" -> "{\"termId\":\"autumn:2026\"}"
        "service.page" -> "{\"pageId\":\"overview\"}"
        else -> "{}"
    }
    private fun capabilityName(method: String) = when (method) {
        "auth.start" -> "账号登录"; "auth.resume" -> "继续验证"; "auth.refreshCaptcha" -> "刷新验证码"; "auth.validate" -> "登录校验"
        "study.terms" -> "学期"; "study.schedule" -> "课表"; "study.calendar" -> "校历与作息"; "study.grades" -> "成绩"; "study.gradeDetails" -> "成绩明细"; "study.exams" -> "考试"
        "selection.catalog" -> "选课轮次"; "selection.courses" -> "可选课程"; "selection.sections" -> "教学班"; "selection.enrolled" -> "已选课程"; "selection.select" -> "选课"; "selection.drop" -> "退课"
        "service.page" -> "服务页面"; "service.action" -> "服务操作"
        else -> method
    }
}

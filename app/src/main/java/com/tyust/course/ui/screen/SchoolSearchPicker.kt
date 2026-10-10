package com.tyust.course.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tyust.course.academic.plugin.*
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.ui.system.*
import kotlinx.coroutines.*

private data class PendingSchoolInstall(val pkg: PluginPackage, val school: SchoolConfig, val token: Long)

@Composable
fun SchoolSearchPicker(schools: List<SchoolConfig>, selected: SchoolConfig?, enabled: Boolean,
    onSelected: (SchoolConfig) -> Unit, onAdded: () -> Unit, onAddManually: (String) -> Unit,
    onManage: () -> Unit, subtitle: String? = null) {
    var open by remember { mutableStateOf(false) }
    InsetGroupedRow(title = selected?.name ?: "搜索学校", subtitle = subtitle ?: "搜索或切换学校",
        icon = Icons.Default.School, showDivider = false, enabled = enabled, onClick = { open = true },
        modifier = Modifier.fillMaxWidth().testTag("login-school-picker"),
        trailing = { Icon(Icons.Default.ChevronRight, "切换学校") })
    if (open) SchoolSearchDialog(schools, onDismiss = { open = false }, onSelected = {
        onSelected(it); open = false
    }, onAdded = onAdded, onAddManually = { open = false; onAddManually(it) },
        onManage = { open = false; onManage() })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchoolSearchDialog(schools: List<SchoolConfig>, onDismiss: () -> Unit,
    onSelected: (SchoolConfig) -> Unit, onAdded: () -> Unit, onAddManually: (String) -> Unit,
    onManage: () -> Unit) {
    val scope = rememberCoroutineScope()
    val gate = remember { SchoolSearchSession() }
    var job by remember { mutableStateOf<Job?>(null) }
    var query by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var verified by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf<List<SchoolSearchProvider>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingSchoolInstall?>(null) }
    val installed = remember(reload, busy) { AcademicProviderRegistry.knownPackages().filter { it.official && it.manifest.isAcademic }
        .map { SchoolSearchProvider(it.manifest.id, it.manifest.name, it.manifest.version,
            AcademicProviderRegistry.school(it), installed = true) } }
    val rows = remember(query, schools, remote, installed) { PluginSchoolSearch.merge(query, schools, remote, installed) }
    fun cancel() { gate.cancel(); job?.cancel(); job = null; busy = false; pending = null }
    fun dismiss() { cancel(); onDismiss() }
    fun manual(name: String) { cancel(); onAddManually(name) }
    fun submit() {
        if (PluginSchoolSearch.shouldOfferAdd(query, true, verified && !loading, rows)) manual(query.trim())
    }
    DisposableEffect(Unit) { onDispose { gate.cancel(); job?.cancel() } }
    LaunchedEffect(reload) {
        loading = true; verified = false; problem = null
        try { remote = AcademicProviderRegistry.catalog().schoolEntries(); verified = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { problem = "无法加载已验证的学校目录：${error.message ?: "请检查网络后重试"}" }
        finally { loading = false }
    }
    fun bind(pkg: PluginPackage, requested: SchoolConfig, token: Long) {
        if (!gate.current(token)) return
        check(AcademicProviderRegistry.isCurrentPackage(pkg.manifest.id, pkg.digest)) { "适配版本已改变，请重新选择" }
        val manager = UserManager.getInstance()
        val existing = PluginSchoolSearch.existing(manager.supportedSchools, requested)
        val target = existing ?: AcademicProviderRegistry.school(pkg)
        check(AcademicProviderRegistry.matches(pkg, target)) { "适配不支持已保存的自定义地址，请保留原配置或手动添加学校" }
        // No suspension between the final session check and these small local writes.
        if (!gate.current(token)) return
        if (existing == null) manager.addCustomSchool(target)
        AcademicProviderRegistry.setEnabled(pkg.manifest.id, true)
        AcademicProviderRegistry.setSchoolEnabled(pkg.manifest.id, target, true)
        AcademicProviderRegistry.choose(target, pkg.manifest.id)
        gate.finish(token); busy = false; onSelected(target); onAdded()
    }
    fun activate(item: PendingSchoolInstall, approved: Boolean) {
        job = scope.launch {
            try {
                if (!gate.current(item.token)) return@launch
                val pkg = withContext(Dispatchers.IO) { AcademicProviderRegistry.packages().activateStaged(item.pkg.manifest.id, approved, item.pkg.digest) }
                ensureActive()
                if (!gate.current(item.token)) return@launch
                AcademicProviderRegistry.reload()
                pending = null
                if (pkg == null) { problem = "适配已暂存，相关任务结束后请重新选择；学校尚未切换"; gate.finish(item.token); busy = false }
                else bind(pkg, item.school, item.token)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (gate.current(item.token)) { problem = error.message; cancel() } }
        }
    }
    fun choose(row: SchoolSearchResult, provider: SchoolSearchProvider) {
        val token = gate.begin() ?: return
        busy = true; problem = null
        job = scope.launch {
            try {
                val current = AcademicProviderRegistry.knownPackage(provider.id)?.takeIf { it.official }
                if (current != null) { bind(current, row.school, token); return@launch }
                val candidate = AcademicProviderRegistry.catalog().update(provider.id, stageOnly = true)
                ensureActive()
                if (!gate.current(token)) return@launch
                check(candidate.manifest.version == provider.version) { "目录版本已更新，请刷新后重新选择" }
                val item = PendingSchoolInstall(candidate, row.school, token)
                if (PluginUpdatePolicy.expanded(null, candidate.manifest).isNotEmpty()) pending = item
                else activate(item, false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (gate.current(token)) { problem = "安装未完成：${error.message.orEmpty()}"; cancel() } }
        }
    }
    SystemDialog(onDismissRequest = ::dismiss, title = { Text("搜索学校") },
        confirmButton = { SystemDialogButton(primary = true, onClick = ::dismiss) { Text("关闭") } }) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassTextField(query, { cancel(); query = it }, Modifier.fillMaxWidth(), placeholder = "输入学校名称",
                leadingIcon = Icons.Default.Search, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SystemActionButton("搜索", ::submit, icon = Icons.Default.Search, enabled = query.isNotBlank() && !busy)
                SystemActionButton("手动添加", { manual(query.trim()) }, icon = Icons.Default.Add)
                SystemActionButton("管理学校", { cancel(); onManage() }, icon = Icons.Default.Settings)
            }
            if (loading) Text("正在查询学校适配，本地学校仍可选择…", style = MaterialTheme.typography.bodySmall)
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (!loading && !verified) SystemActionButton("重试目录查询", { reload++ })
            if (busy) Text("正在处理适配；可关闭面板取消学校切换。", style = MaterialTheme.typography.bodySmall)
            if (rows.isEmpty() && verified && !loading) Text("没有匹配学校，点击搜索可添加。")
            rows.forEach { row ->
                InsetGroupedSection(header = row.school.name,
                    footer = PluginSchoolMatcher.endpoint(row.school).toString() +
                        if (row.providers.size > 1) " · 选择一个适配提供者" else "") {
                    val last = row.providers.size - 1
                    if (row.configured) InsetGroupedRow(title = "使用已配置学校", subtitle = "保留当前地址与适配",
                        icon = Icons.Default.School, enabled = !busy, showDivider = last >= 0,
                        onClick = { cancel(); onSelected(row.school) },
                        trailing = { Icon(Icons.Default.ChevronRight, null) })
                    if (row.builtin && !row.configured) InsetGroupedRow(title = "使用内置适配", subtitle = "随 App 提供，无需安装",
                        icon = Icons.Default.School, enabled = !busy, showDivider = last >= 0,
                        onClick = {
                            cancel()
                            val manager = UserManager.getInstance()
                            val existing = PluginSchoolSearch.existing(manager.supportedSchools, row.school)
                            val target = existing ?: row.school
                            if (existing == null) manager.addCustomSchool(target)
                            onAdded(); onSelected(target)
                        }, trailing = { Icon(Icons.Default.ChevronRight, null) })
                    row.providers.forEachIndexed { index, provider ->
                        val status = if (provider.installed) "已安装" else provider.incompatibleReason ?: "可安装"
                        val available = provider.installed || provider.incompatibleReason == null
                        InsetGroupedRow(title = provider.name, subtitle = "${provider.version} · $status",
                            icon = Icons.Default.Extension, enabled = !busy && available, showDivider = index < last,
                            onClick = { choose(row, provider) },
                            trailing = { if (available) Text(if (provider.installed) "选择" else "安装",
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) })
                    }
                }
            }
        }
    }
    pending?.let { item -> SystemDialog(onDismissRequest = { cancel() }, title = { Text("安装 ${item.pkg.manifest.name}") },
        confirmButton = { SystemDialogButton(primary = true, onClick = { pending = null; activate(item, true) }) { Text("确认安装") } },
        dismissButton = { SystemDialogButton(onClick = { cancel() }) { Text("取消") } }) {
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("版本 ${item.pkg.manifest.version}")
            Text("权限：" + item.pkg.manifest.permissions.joinToString("、").ifBlank { "无额外权限" })
            Text("网络范围：" + item.pkg.manifest.network.joinToString("\n") { it.optString("origin") + it.optString("pathPrefix") })
        }
    } }
}

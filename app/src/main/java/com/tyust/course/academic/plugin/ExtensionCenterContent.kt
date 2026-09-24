package com.tyust.course.academic.plugin

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tyust.course.manager.UserManager
import com.tyust.course.ui.system.*
import org.json.JSONObject

@Composable fun ExtensionCenterContent(onOpen: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val revision by PluginPages.revision.collectAsState()
    val registry = PluginPages.registry
    val school = UserManager.getInstance().currentSchool
    val pages = remember(revision) { registry.pages() }
    val pinned = remember(revision) { registry.pinned() }
    val packages = remember(revision) { AcademicProviderRegistry.packages().list() }
    var replace by remember { mutableStateOf<PluginPage?>(null) }
    var settings by remember { mutableStateOf<PluginPackage?>(null) }
    var clearData by remember { mutableStateOf<PluginPackage?>(null) }
    fun open(id: String) { if (onOpen != null) onOpen(id) else PluginPages.open(context, id) }
    fun pin(page: PluginPage) { if (pinned.size < 5) registry.customize(pinned + page.id) else replace = page }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("服务中心", style = MaterialTheme.typography.headlineSmall)
        InsetGroupedSection(header = "主导航", footer = "最多五项，设置始终保留。移除入口不会删除服务器数据。") {
            pinned.forEachIndexed { index, route -> val page = registry.page(route) ?: return@forEachIndexed
                InsetGroupedRow(title = page.title, subtitle = if (page.pluginId == null) "内置页面" else page.pluginId,
                    onClick = { open(route) }, trailing = { Row {
                        if (index > 0) SystemIconButton(Icons.Outlined.ArrowUpward, "上移", { registry.customize(pinned.toMutableList().apply { this[index] = this[index - 1]; this[index - 1] = route }) })
                        if (index < pinned.lastIndex) SystemIconButton(Icons.Outlined.ArrowDownward, "下移", { registry.customize(pinned.toMutableList().apply { this[index] = this[index + 1]; this[index + 1] = route }) })
                        if (route != PluginPageRegistry.SETTINGS) SystemIconButton(Icons.Outlined.RemoveCircleOutline, "取消固定", { registry.customize(pinned - route) })
                    } })
            }
            InsetGroupedRow(title = "恢复默认导航", onClick = { registry.restoreDefaults() }, showDivider = false)
        }
        InsetGroupedSection(header = "全部页面") {
            pages.filter { it.pluginId != null || UserManager.getInstance().isLoggedIn }.forEach { page ->
                var menu by remember(page.id) { mutableStateOf(false) }
                InsetGroupedRow(title = page.title, subtitle = page.pluginId ?: "内置页面", icon = Icons.Outlined.Extension, onClick = { open(page.id) }, trailing = {
                    Box { SystemIconButton(Icons.Outlined.MoreHoriz, "管理页面", { menu = true })
                        DropdownMenu(menu, { menu = false }) {
                            if (page.id !in pinned) DropdownMenuItem(text = { Text("固定到主导航") }, onClick = { menu = false; pin(page) })
                            DropdownMenuItem(text = { Text("设为启动页") }, onClick = { menu = false; registry.setStartup(page.id); GlassToaster.show("已设置启动页") })
                            if (page.pluginId != null) DropdownMenuItem(text = { Text("移除页面入口") }, onClick = { menu = false; registry.unregister(page.pluginId, page.id) })
                        }
                    }
                })
            }
            if (pages.none { it.pluginId != null }) InsetGroupedRow(title = "还没有插件页面", subtitle = "安装插件后，页面会先出现在这里，由你决定是否固定到导航。", showDivider = false)
        }
        val templates = packages.filter { PluginPages.available(it) && it.manifest.isNative }.flatMap { pkg ->
            pkg.manifest.contributes.optJSONArray("pages")?.let(PluginJson::objects).orEmpty().filter { p -> pages.none { it.pluginId == pkg.manifest.id && it.templateId == p.getString("id") } }.map { pkg to it }
        }
        if (templates.isNotEmpty()) InsetGroupedSection(header = "可添加与恢复的页面") {
            templates.forEach { (pkg, template) ->
                val missing = PluginPlatformContract.requirements(template, PluginPages.capabilities())
                InsetGroupedRow(title = template.getString("title"), subtitle = if (missing.isEmpty()) pkg.manifest.name else "需要新版 App：${missing.joinToString()}",
                    onClick = if (missing.isEmpty()) ({ runCatching { registry.register(pkg.manifest.id, template.getString("id"), template.getString("id")) }.onFailure { GlassToaster.show(it.message.orEmpty()) } }) else null)
            }
        }
        val commands = packages.filter { it.manifest.isNative && PluginPages.available(it) }.flatMap { pkg -> pkg.manifest.contributes.optJSONArray("commands")?.let(PluginJson::objects).orEmpty().map { pkg to it } }
        if (commands.isNotEmpty()) InsetGroupedSection(header = "快捷工具") { commands.forEach { (pkg, command) ->
            val enabled = PluginPlatformContract.requirements(command, PluginPages.capabilities()).isEmpty()
            InsetGroupedRow(title = command.getString("title"), subtitle = if (enabled) pkg.manifest.name else "当前 App 不支持此工具", onClick = if (enabled) ({ NativePluginActivity.command(context, pkg, command.getString("id")) }) else null)
        } }
        val legacy = school?.let(AcademicProviderRegistry::services).orEmpty().filter { it.manifest.isService }
        if (legacy.isNotEmpty()) InsetGroupedSection(header = "校园服务") { legacy.forEach { pkg -> PluginJson.objects(pkg.manifest.service!!.getJSONArray("entries")).forEach { entry ->
            InsetGroupedRow(title = entry.getString("title"), subtitle = pkg.manifest.name, onClick = { ServicePluginActivity.open(context, pkg, entry.getString("pageId")) })
        } } }
        InsetGroupedSection(header = "管理") {
            packages.filter { (it.manifest.contributes.optJSONArray("settings")?.length() ?: 0) > 0 }.forEach { pkg -> InsetGroupedRow(title = "${pkg.manifest.name} · 设置", onClick = { settings = pkg }) }
            packages.forEach { pkg -> InsetGroupedRow(title = "${pkg.manifest.name} · 清理本地数据", onClick = { clearData = pkg }) }
            InsetGroupedRow(title = "插件管理", subtitle = "安装、更新、停用和卸载", icon = Icons.Outlined.Extension, onClick = { context.startActivity(Intent(context, PluginCenterActivity::class.java)) }, showDivider = false)
        }
        Spacer(Modifier.height(LocalAppOverlayBottomInset.current))
    }
    replace?.let { page -> SystemDialog(onDismissRequest = { replace = null }, title = { Text("替换哪个导航入口") }, confirmButton = { TextButton(onClick = { replace = null }) { Text("取消") } }) {
        Column { pinned.filter { it != PluginPageRegistry.SETTINGS }.forEach { id -> TextButton(onClick = { registry.customize(pinned.map { if (it == id) page.id else it }); replace = null }) { Text(registry.page(id)?.title ?: id) } } }
    } }
    settings?.let { pkg -> PluginSettingsDialog(pkg) { settings = null } }
    clearData?.let { pkg -> SystemDialog(onDismissRequest = { clearData = null }, title = { Text("清理 ${pkg.manifest.name} 的本地数据") },
        confirmButton = { TextButton(onClick = { runCatching { PluginLocalData.clear(context, pkg) }.onSuccess { GlassToaster.show("本地数据已清理") }.onFailure { GlassToaster.show(it.message.orEmpty()) }; clearData = null }) { Text("清理") } },
        dismissButton = { TextButton(onClick = { clearData = null }) { Text("取消") } }) {
        Text("清理此插件的本地设置、文件、缓存、服务登录和数据读取授权。服务器上的帖子、校园卡记录等业务数据会保留。未完成的任务需先处理。")
    } }
}

@Composable private fun PluginSettingsDialog(pkg: PluginPackage, close: () -> Unit) {
    val context = LocalContext.current
    val accountRevision by PluginServiceAccounts.revision.collectAsState()
    val academic by UserManager.getInstance().sessionState.state.collectAsState()
    val store = remember { context.getSharedPreferences("plugin-settings", android.content.Context.MODE_PRIVATE) }
    val declarations = pkg.manifest.contributes.optJSONArray("settings")?.let(PluginJson::objects).orEmpty()
    val keys = remember(pkg.digest, academic.token, accountRevision) { declarations.associate { it.getString("id") to PluginSettings.key(context, pkg, it) } }
    val values = remember(pkg.digest, keys) { mutableStateMapOf<String, String>() }
    val defaults = remember(pkg.digest, keys) { PluginSettings.values(context, pkg) }
    val legacy = remember(pkg.digest, keys) { PluginSettings.legacyServiceValues(context, pkg) }
    SystemDialog(onDismissRequest = close, title = { Text("${pkg.manifest.name} · 设置") }, confirmButton = { TextButton(onClick = {
        runCatching { PluginSettings.write(context, pkg, keys, values.toMap()) }.onFailure { GlassToaster.show(it.message.orEmpty()) }; close()
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } }) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (legacy.isNotEmpty()) {
                Text("检测到按学校账号保存的旧版服务设置。确认属于当前服务账号后，可以复制；原记录会保留。")
                TextButton(onClick = { values.putAll(legacy) }) { Text("复制旧版服务设置到当前账号") }
            }
            declarations.forEach { setting ->
            val id = setting.getString("id"); val value = values[id] ?: defaults.opt(id)?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
            if (setting.getString("type") == "boolean") Row { Text(setting.getString("title"), Modifier.weight(1f)); Switch(value == "true", { values[id] = it.toString() }) }
            else OutlinedTextField(value, { next -> if (next.length <= 1000 && (setting.getString("type") != "number" || next.toDoubleOrNull() != null)) values[id] = next }, label = { Text(setting.getString("title")) })
        } }
    }
}

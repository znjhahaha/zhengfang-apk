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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
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
    private var catalogGeneration by mutableIntStateOf(0)
    private var targetSchoolId by mutableStateOf<String?>(null)
    private var changed = false
    private var packageSnapshot: String? = null
    private val fromLogin get() = intent.getBooleanExtra(EXTRA_FROM_LOGIN, false)
    override fun onResume() { super.onResume(); generation++; catalogGeneration++ }
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
        val focus = LocalFocusManager.current
        var packages by remember { mutableStateOf<List<PluginPackage>>(emptyList()) }
        var catalog by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
        var staged by remember { mutableStateOf<List<PluginPackage>>(emptyList()) }
        var pending by remember { mutableStateOf<PluginPackage?>(null) }
        var selected by remember { mutableStateOf<PluginPackage?>(null) }
        var uninstall by remember { mutableStateOf<PluginPackage?>(null) }
        var installedView by rememberSaveable { mutableStateOf(false) }
        var chooseProvider by remember { mutableStateOf(false) }
        var chooseSchool by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var catalogLoading by remember { mutableStateOf(false) }
        var catalogError by remember { mutableStateOf("") }
        var catalogJob by remember { mutableStateOf<Job?>(null) }
        var installingId by remember { mutableStateOf<String?>(null) }
        var query by rememberSaveable { mutableStateOf("") }
        var onlySchool by rememberSaveable { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        val catalogClient = remember(catalogGeneration) { AcademicProviderRegistry.catalog() }
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
            staged = withContext(Dispatchers.IO) { AcademicProviderRegistry.packages().staged() }
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
        LaunchedEffect(generation) {
            try { refresh() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "已安装插件读取失败" }
        }
        fun loadCatalog() {
            if (catalogJob?.isActive == true) return
            catalogLoading = true; catalogError = ""
            catalogJob = scope.launch {
                try { catalog = catalogClient.check() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { catalogError = if (catalog.isEmpty()) "暂时无法获取插件目录" else "刷新失败，仍可浏览已保存的插件" }
                finally { catalogLoading = false }
            }
        }
        LaunchedEffect(catalogClient) {
            catalogJob?.cancelAndJoin()
            catalog = catalogClient.cached()
            loadCatalog()
        }
        fun select(id: String?) {
            school?.let { AcademicProviderRegistry.choose(it, id) }
            changed = true
            chooseProvider = false; generation++
            message = "已保存本校选择，正在运行的任务继续使用原适配"
        }
        suspend fun activate(candidate: PluginPackage) {
            val activated = withContext(Dispatchers.IO) {
                AcademicProviderRegistry.packages().activateStaged(candidate.manifest.id, expectedDigest = candidate.digest)
            }
            if (activated == null) pending = candidate else selected = activated
            refresh(); generation++
            message = if (activated == null) "插件已暂存，请查看权限或等待相关任务结束" else "安装完成，选择“用于本校”或打开服务即可使用"
        }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) run {
                val candidate = withContext(Dispatchers.IO) {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytesBounded(PluginLimits.PACKAGE_BYTES) }
                        ?: error("无法读取插件文件")
                    AcademicProviderRegistry.packages().install(bytes, allowDevelopment = false, stageOnly = true)
                }
                activate(candidate)
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

        fun install(entry: JSONObject) = run {
            installingId = entry.getString("id")
            try {
                activate(catalogClient.update(entry.getString("id"), stageOnly = true))
            } finally { installingId = null }
        }
        fun canOpen(pkg: PluginPackage): Boolean = !fromLogin &&
            (school == null && PluginDiscovery.universal(pkg.manifest.json) || school != null &&
                school.id == UserManager.getInstance().currentSchool?.id &&
                AcademicProviderRegistry.isEnabled(pkg.manifest.id, school) &&
                AcademicProviderRegistry.matches(pkg, school)) &&
            (pkg.manifest.isService || pkg.manifest.isNative && (pkg.manifest.contributes.optJSONArray("pages")?.length() ?: 0) > 0)
        fun use(pkg: PluginPackage) {
            when {
                pkg.manifest.isAcademic && school != null && AcademicProviderRegistry.matches(pkg, school) -> bind(pkg, fromLogin)
                canOpen(pkg) -> if (pkg.manifest.isNative) NativePluginActivity.open(this@PluginCenterActivity, pkg)
                    else ServicePluginActivity.open(this@PluginCenterActivity, pkg)
                else -> selected = pkg
            }
        }
        fun useLabel(pkg: PluginPackage) = when {
            pkg.manifest.isAcademic && school != null && AcademicProviderRegistry.matches(pkg, school) ->
                if (current?.digest == pkg.digest) "正在使用" else "用于本校"
            canOpen(pkg) -> "打开服务"
            else -> "查看详情"
        }

        val schools = remember(generation) { UserManager.getInstance().supportedSchools.toList() }
        val discovered = remember(catalog, school, schools, query, onlySchool) {
            PluginDiscovery.filter(catalog, school, query, onlySchool, schools)
        }
        val installedEntries = remember(packages, generation) {
            packages.map { pkg ->
                val metadata = if (pkg.official) AcademicProviderRegistry.packages().metadata(pkg.manifest.id) else null
                JSONObject(pkg.manifest.json.toString()).apply {
                    metadata?.keys()?.forEach { name -> put(name, metadata.get(name)) }
                }
            }
        }
        val installed = remember(installedEntries, school, schools, query, onlySchool) {
            PluginDiscovery.filter(installedEntries, school, query, onlySchool, schools)
        }
        val byId = remember(packages) { packages.associateBy { it.manifest.id } }
        val discoveryScroll = rememberLazyListState()
        val installedScroll = rememberLazyListState()
        val listState = if (installedView) installedScroll else discoveryScroll
        LaunchedEffect(query, onlySchool, targetSchoolId, installedView) { listState.scrollToItem(0) }

        GlassPageScaffold(title = "插件中心", subtitle = "学校教务与校园服务", onBack = { finish() }, actions = {
            SystemIconButton(Icons.Outlined.MoreHoriz, "更多", { menu = true })
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("从文件安装") }, enabled = !busy,
                    onClick = { menu = false; importer.launch(arrayOf("*/*")) })
                DropdownMenuItem(text = { Text("刷新插件目录") }, enabled = !catalogLoading,
                    onClick = { menu = false; loadCatalog() })
                DropdownMenuItem(text = { Text("打开插件商店") }, onClick = { menu = false; web("/") })
                DropdownMenuItem(text = { Text("导入、回滚与开发工具") }, onClick = {
                    menu = false; developerTools.launch(Intent(this@PluginCenterActivity, PluginDeveloperActivity::class.java)
                        .putExtra(EXTRA_TARGET_SCHOOL, targetSchoolId))
                })
                DropdownMenuItem(text = { Text("开发文档与交流群") }, onClick = { menu = false; web("/developers") })
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiquidSegmentedControl(listOf("发现", "已安装"), if (installedView) 1 else 0,
                        { installedView = it == 1 }, Modifier.fillMaxWidth().testTag("plugin-tabs"), height = 44.dp)
                    GlassTextField(value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().testTag("plugin-search"),
                        placeholder = "搜索学校、插件或功能", leadingIcon = Icons.Outlined.Search,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                        trailing = if (query.isEmpty()) null else ({
                            IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Close, "清除搜索", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(onClick = { focus.clearFocus(); chooseSchool = true },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("plugin-school"), shape = RoundedCornerShape(16.dp),
                            colors = AssistChipDefaults.assistChipColors(containerColor = glassSurfaceColor(),
                                labelColor = MaterialTheme.colorScheme.onSurface, leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                            border = BorderStroke(0.5.dp, glassBorderColor()),
                            leadingIcon = { Icon(Icons.Outlined.School, null, Modifier.size(16.dp)) },
                            label = { Text(school?.name ?: "选择学校", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = { Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp)) })
                        FilterChip(selected = onlySchool, onClick = { onlySchool = !onlySchool }, enabled = school != null,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("plugin-school-filter"), shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(0.5.dp, if (onlySchool) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else glassBorderColor()),
                            colors = FilterChipDefaults.filterChipColors(containerColor = glassSurfaceColor(),
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.primary),
                            leadingIcon = if (!onlySchool) null else ({ Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }),
                            label = { Text("适用本校") })
                    }
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().testTag("plugin-list"),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (fromLogin) item("login") {
                        TextButton(onClick = {
                            if (school == null) chooseSchool = true else if (resolved?.isFailure == true) chooseProvider = true else finish()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (resolved?.isFailure == true) "选择适配后返回登录" else "返回登录")
                        }
                    }
                    if (school != null) item("adapter") {
                        TextButton(onClick = { chooseProvider = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("当前适配 · " + when {
                                resolved?.isFailure == true -> "请先选择"
                                current != null -> current.manifest.name
                                else -> "内置适配"
                            }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall)
                            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
                        }
                    }
                    if (message.isNotBlank()) item("message") {
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (installedView && staged.isNotEmpty()) item("staged-updates") {
                        InsetGroupedSection(header = "待处理更新") {
                            staged.forEach { pkg -> InsetGroupedRow(title = pkg.manifest.name,
                                subtitle = "${pkg.manifest.version} · 查看权限或等待任务结束", onClick = { pending = pkg }) }
                        }
                    }
                    if (installedView) item("update-settings") {
                        val preferences = remember { getSharedPreferences("plugin-updates", MODE_PRIVATE) }
                        var automatic by remember { mutableStateOf(preferences.getBoolean("enabled", true)) }
                        InsetGroupedSection {
                            InsetGroupedRow(title = "自动更新兼容插件", subtitle = "每天检查，新增权限需确认", trailing = {
                                Switch(automatic, { automatic = it; preferences.edit().putBoolean("enabled", it).apply() })
                            })
                        }
                    }
                    if (catalogLoading && !installedView) item("loading") {
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("plugin-catalog-loading"))
                    }
                    if (catalogError.isNotBlank() && !installedView) item("error") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(catalogError, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { loadCatalog() }) { Text("重试") }
                        }
                    }
                    item("count") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (installedView) "已安装" else "发现插件", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            AnimatedNumberText("${if (installedView) installed.size else discovered.size} 个",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    val entries = if (installedView) installed else discovered
                    if (entries.isEmpty() && !(catalogLoading && !installedView)) item("empty") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Extension, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (query.isNotBlank() || onlySchool) "没有找到匹配的插件" else if (installedView) "还没有安装插件" else "暂时没有可用插件")
                            Text(if (query.isNotBlank() || onlySchool) "试试其他关键词，或查看全部学校" else "学校适配和校园服务都可以在这里找到",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (installedView && query.isBlank() && !onlySchool) TextButton(onClick = { installedView = false }) { Text("去发现插件") }
                        }
                    }
                    items(entries, key = { it.getString("id") }) { entry ->
                        val id = entry.getString("id")
                        val pkg = byId[id]
                        val upToDate = pkg?.official == true && pkg.manifest.version == entry.optString("version")
                        val needsDownload = !installedView && !upToDate
                        val enabled = pkg == null || school == null || AcademicProviderRegistry.isEnabled(id, school)
                        val state = when {
                            pkg == null -> "可安装"
                            !enabled -> "本校已停用"
                            current?.digest == pkg.digest -> "正在使用"
                            needsDownload -> "有更新"
                            else -> "已安装"
                        }
                        PluginCard(entry, PluginDiscovery.scope(entry, school), state,
                            action = when {
                                installingId == id -> "安装中…"
                                needsDownload -> if (pkg == null) "安装" else "更新"
                                pkg != null -> useLabel(pkg)
                                else -> "安装"
                            }, enabled = !busy,
                            actionTag = if (needsDownload) "catalog-install-$id" else "plugin-use-$id",
                            onAction = { if (needsDownload) install(entry) else pkg?.let(::use) },
                            onDetails = pkg?.let { { selected = it } })
                    }
                }
            }
        }
        if (chooseSchool) com.tyust.course.ui.screen.SchoolManagementDialog(
            selectedSchoolId = targetSchoolId, onDismiss = { chooseSchool = false },
            onSelect = { targetSchoolId = it.id; chooseSchool = false; generation++ }, onChanged = {
                if (targetSchoolId?.let { UserManager.getInstance().getSchoolById(it) } == null) targetSchoolId = null
                changed = true; generation++
            })
        pending?.let { pkg -> SystemDialog(onDismissRequest = { pending = null }, title = { Text("安装 ${pkg.manifest.name} ${pkg.manifest.version}") },
            confirmButton = { TextButton(onClick = { pending = null; run {
                val activated = withContext(Dispatchers.IO) { AcademicProviderRegistry.packages().activateStaged(pkg.manifest.id, true, pkg.digest) }
                refresh(); generation++
                if (activated != null) selected = activated
                message = if (activated == null) "已确认，相关任务结束后将自动切换" else "安装完成"
            } }) { Text("确认安装") } }, dismissButton = { TextButton(onClick = { pending = null }) { Text("稍后") } }) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("权限：" + pkg.manifest.permissions.joinToString("、", transform = ::permissionName).ifBlank { "无额外权限" })
                Text("网络范围：" + pkg.manifest.network.joinToString("\n") { it.getString("origin") + it.getString("pathPrefix") })
                Text("正在运行和需要核对结果的任务会保留原版本。")
            }
        } }
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
            val catalogMetadata = if (pkg.official) AcademicProviderRegistry.packages().metadata(pkg.manifest.id) else null
            val metadata = PluginSourceDetails.release(pkg, catalogMetadata)
            val source = PluginSourceDetails.source(pkg, catalogMetadata)
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
                    Detail("许可证", source?.optString("license")?.ifBlank { null } ?: pkg.manifest.json.optString("license").ifBlank { "作者未提供" })
                    val repository = PluginSourceDetails.repository(source?.optString("repository")?.ifBlank { null } ?: pkg.manifest.json.optString("repository"))
                    repository?.let { url -> TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("源码仓库") } }
                    if (pkg.official) {
                        source?.let {
                            Detail("本版本源码 SHA-256", it.getString("sha256"))
                            TextButton(onClick = { web(PluginSourceDetails.download(pkg)) }) { Text("下载 ${pkg.manifest.version} 源码") }
                        }
                        TextButton(onClick = { web(PluginSourceDetails.page(pkg)) }) { Text("本版本源码与参与修改") }
                    }
                    if ("academic.session" in pkg.manifest.permissions) {
                        val shared = runCatching { PluginAcademicSession(this@PluginCenterActivity, pkg, { true }).authorized() }.getOrDefault(false)
                        Detail("教务登录共享", if (shared) "已授权使用当前教务登录" else "尚未授权；首次使用时确认")
                        if (shared) TextButton(onClick = {
                            PluginAcademicSession.revoke(this@PluginCenterActivity, pkg.manifest.id)
                            message = "已撤销此插件的教务登录授权"; generation++; selected = null
                        }) { Text("撤销教务登录授权") }
                    }
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
                    TextButton(onClick = { AcademicProviderRegistry.setEnabled(pkg.manifest.id, !AcademicProviderRegistry.isEnabled(pkg.manifest.id)); generation++; selected = null }) {
                        Text(if (AcademicProviderRegistry.isEnabled(pkg.manifest.id)) "停用所有入口与任务" else "启用插件")
                    }
                    TextButton(onClick = { PluginFeedback.open(this@PluginCenterActivity, pkg) }) { Text("快捷反馈") }
                    TextButton(onClick = { selected = null; uninstall = pkg }) { Text("卸载插件", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        uninstall?.let { pkg -> SystemDialog(onDismissRequest = { uninstall = null }, title = { Text("卸载 ${pkg.manifest.name}？") },
            confirmButton = { TextButton(onClick = { run { withContext(Dispatchers.IO) { AcademicProviderRegistry.packages().deactivate(pkg.manifest.id) }; refresh(); generation++ }; uninstall = null }) { Text("卸载") } },
            dismissButton = { TextButton(onClick = { uninstall = null }) { Text("取消") } }) {
            Text("将移除页面、账号授权并停止后续任务。已提交结果和服务器业务数据保留。仅想在当前学校关闭时，请使用“本校停用”。")
        } }
    }
    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun PluginCard(
        entry: JSONObject, scope: String, state: String, action: String, enabled: Boolean,
        actionTag: String, onAction: () -> Unit, onDetails: (() -> Unit)?
    ) {
        val colors = MaterialTheme.colorScheme
        SystemCard(modifier = Modifier.fillMaxWidth().testTag("plugin-${entry.getString("id")}"), contentPadding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(12.dp), color = colors.primary.copy(alpha = 0.10f)) {
                        Icon(Icons.Outlined.Extension, null, Modifier.padding(10.dp).size(22.dp), tint = colors.primary)
                    }
                    Column(Modifier.weight(1f).then(if (onDetails == null) Modifier else Modifier.clickable(onClick = onDetails))) {
                        Text(entry.optString("name", entry.getString("id")), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(scope, style = MaterialTheme.typography.labelSmall, color = colors.primary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (onDetails != null) IconButton(onClick = onDetails) { Icon(Icons.Outlined.Info, "插件详情") }
                }
                Text(entry.optString("description").ifBlank {
                    if (entry.optString("kind") in setOf("configuration", "independent", "extension")) "连接学校教务，查询课表与成绩" else "为校园生活添加更多服务"
                }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val features = PluginJson.strings(entry.optJSONArray("features"))
                if (features.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    features.forEach { feature ->
                        Surface(shape = RoundedCornerShape(8.dp), color = colors.primary.copy(alpha = 0.08f)) {
                            Text(feature, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall, color = colors.primary)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${entry.optString("version")} · $state", Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    LiquidButton(onClick = onAction, enabled = enabled, contentColor = colors.primary,
                        modifier = Modifier.testTag(actionTag), style = LiquidButtonStyle.Surface) {
                        Text(action, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    @Composable private fun Detail(label: String, value: String) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
    private fun web(path: String) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AcademicProviderRegistry.OFFICIAL_WEBSITE + path))) }
    private fun permissionName(name: String) = mapOf("network" to "网络", "storage" to "隔离存储", "credentials" to "加密凭据", "session" to "会话",
        "files" to "文件", "device.clipboard" to "剪贴板", "device.haptics" to "触感", "tasks" to "后台任务", "notifications" to "通知",
        "navigation" to "导航", "auth" to "认证", "runtime" to "运行控制", "academic.session" to "使用本校教务登录",
        "academic.read" to "读取学业数据", "academic.write" to "导入课表")[name] ?: name
    private fun capabilityName(name: String) = mapOf("ui.init" to "原生页面", "ui.reduce" to "交互与状态", "task.run" to "后台流程", "data.query" to "数据提供者",
        "auth.start" to "登录", "auth.resume" to "继续认证", "auth.validate" to "会话校验", "auth.refreshCaptcha" to "验证码",
        "study.terms" to "学期", "study.schedule" to "课表", "study.grades" to "成绩", "study.gradeDetails" to "成绩明细", "study.exams" to "考试",
        "study.calendar" to "校历与作息", "selection.select" to "选课", "selection.drop" to "退课", "service.page" to "服务页面", "service.action" to "服务操作")[name] ?: name
}

package com.tyust.course.academic.plugin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tyust.course.manager.UserManager
import com.tyust.course.ui.system.*
import com.tyust.course.ui.theme.CourseSelectorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CampusServiceCenterActivity : ComponentActivity() {
    private var generation by mutableIntStateOf(0)
    override fun onResume() { super.onResume(); generation++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CourseSelectorTheme { Center() } }
    }
    @Composable private fun Center() {
        val school = UserManager.getInstance().currentSchool
        var services by remember { mutableStateOf<List<PluginPackage>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var problem by remember { mutableStateOf("") }
        var visibilityRevision by remember { mutableIntStateOf(0) }
        val entryPreferences = remember(school?.id, UserManager.getInstance().currentAccountStorageKey) { ServiceEntryPreferences(this, ServiceEntryPreferences.accountScope()) }
        LaunchedEffect(generation) {
            loading = true
            try { services = withContext(Dispatchers.IO) { AcademicProviderRegistry.reload(); school?.let(AcademicProviderRegistry::services).orEmpty() } }
            catch (e: Exception) { problem = e.message ?: "服务列表暂时不可用" }
            finally { loading = false }
        }
        GlassPageScaffold(title = "校园服务", subtitle = school?.name ?: "先选择学校，再查看专属服务", onBack = { finish() }) { padding ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (problem.isNotBlank()) Text(problem, color = MaterialTheme.colorScheme.error)
                InsetGroupedSection(header = "本校服务", footer = "各服务单独登录。服务页面可调整模块顺序和显示内容。") {
                    val entries = services.flatMap { pkg -> PluginJson.objects((if (pkg.manifest.isNative) pkg.manifest.contributes else pkg.manifest.service!!).getJSONArray("entries")).map { pkg to it } }
                        .sortedWith(compareBy({ it.second.optInt("order") }, { it.second.getString("title") }))
                    if (!loading && entries.isEmpty()) InsetGroupedRow(title = "暂未安装本校的校园服务", subtitle = "可以在插件中心导入服务插件，或浏览开发者的适配进度。", icon = Icons.Outlined.Extension, showDivider = false)
                    entries.forEachIndexed { index, (pkg, entry) ->
                        InsetGroupedRow(title = entry.getString("title"), subtitle = pkg.manifest.name, icon = serviceIcon(entry.optString("icon")),
                            onClick = { if (pkg.manifest.isNative) NativePluginActivity.open(this@CampusServiceCenterActivity, pkg, entry.getString("pageId")) else ServicePluginActivity.open(this@CampusServiceCenterActivity, pkg, entry.getString("pageId")) }, showDivider = index < entries.lastIndex,
                            trailing = { Icon(Icons.Outlined.ChevronRight, null) })
                    }
                }
                val mounted = services.filter { it.official && it.manifest.isService }.flatMap { pkg ->
                    PluginJson.objects(pkg.manifest.service!!.getJSONArray("entries")).flatMap { entry ->
                        PluginJson.strings(entry.optJSONArray("placements")).map { placement -> Triple(pkg, entry, placement) }
                    }
                }
                if (mounted.isNotEmpty()) InsetGroupedSection(header = "页面扩展入口", footer = "只调整当前账号的入口显示；关闭入口不会停用插件，仍可从本校服务打开。") {
                    mounted.forEachIndexed { index, (pkg, entry, placement) ->
                        val visible = remember(visibilityRevision, pkg.digest, entry.getString("id"), placement) { entryPreferences.visible(pkg.manifest.id, entry.getString("id"), placement) }
                        InsetGroupedRow(title = entry.getString("title"), subtitle = ServiceEntryPreferences.placementName(placement), showDivider = index < mounted.lastIndex,
                            trailing = { LiquidSwitch(visible, { entryPreferences.setVisible(pkg.manifest.id, entry.getString("id"), placement, it); visibilityRevision++ },
                                modifier = Modifier.testTag("service-entry-visible-${entry.getString("id")}-${placement}")) })
                    }
                }
                InsetGroupedSection(header = "发现更多") {
                    InsetGroupedRow(title = "插件中心", subtitle = "下载、导入与管理校园插件", icon = Icons.Outlined.CloudDownload,
                        onClick = { startActivity(Intent(this@CampusServiceCenterActivity, PluginCenterActivity::class.java)) }, trailing = { Icon(Icons.Outlined.ChevronRight, null) })
                    InsetGroupedRow(title = "反馈与作者交流", subtitle = "告诉作者你需要的校园服务", icon = Icons.Outlined.ChatBubbleOutline, showDivider = false,
                        onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AcademicProviderRegistry.OFFICIAL_WEBSITE + "/community"))) }, trailing = { Icon(Icons.Outlined.OpenInNew, null) })
                }
            }
        }
    }
    private fun serviceIcon(name: String) = when (name) {
        "book" -> Icons.Outlined.MenuBook; "chart" -> Icons.Outlined.BarChart; "calendar" -> Icons.Outlined.CalendarMonth
        "wallet" -> Icons.Outlined.AccountBalanceWallet; "activity" -> Icons.Outlined.LocalActivity; else -> Icons.Outlined.School
    }
}

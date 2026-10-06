package com.tyust.course.ui.system

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.theme.CourseSelectorTheme

/** Local synthetic preview; no registry, catalog, session, installer or HTTP client. */
class GlassBrowserPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var dark by rememberSaveable { mutableStateOf(false) }
            CourseSelectorTheme(darkTheme = dark) {
                GlassWindowHost { BrowserPreview(dark, { dark = it }) { finish() } }
            }
        }
    }
}

@Composable
private fun BrowserPreview(dark: Boolean, onDarkChange: (Boolean) -> Unit, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val discovery = rememberLazyListState()
    val installed = rememberLazyListState()
    var panel by rememberSaveable { mutableStateOf<BrowserPanel?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("全部") }
    var scenario by rememberSaveable { mutableStateOf("长列表") }
    var glass by rememberSaveable { mutableStateOf(true) }
    var reduced by rememberSaveable { mutableStateOf(false) }
    var largeFont by rememberSaveable { mutableStateOf(false) }
    var controls by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf<Int?>(null) }
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val search: @Composable () -> Unit = {
        GlassTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "搜索模拟插件")
    }
    val filters: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "教务适配", "校园服务", "通用工具").forEach { value ->
                GlassFilterCapsule(value, { kind = value }, selected = kind == value)
            }
        }
        Text("所有条目均为本地模拟，不执行安装或学校请求。", style = MaterialTheme.typography.bodySmall)
    }
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, if (largeFont) 1.8f else density.fontScale),
        LocalAppBackdrop provides LocalAppBackdrop.current.takeIf { glass },
        LocalControlBackdrop provides LocalControlBackdrop.current.takeIf { glass },
        LocalModalBackdrop provides LocalModalBackdrop.current.takeIf { glass }
    ) {
        MaterialTheme {
            CollapsingGlassBrowser("组件预览", "文字透镜与浮动面板 · 模拟数据", if (tab == 0) discovery else installed,
                onBack, listOf("发现", "已安装"), tab, { tab = it }, panelActive = panel != null,
                actions = {
                    GlassSearchFilterPanel(panel, { focus.clearFocus(); panel = it }, reduceMotion = reduced,
                        moreActions = listOf(SystemMenuAction("预览选项", Icons.Outlined.MoreHoriz, { controls = true })),
                        search = search, filters = filters)
                }, expandedControls = {
                    TextButton({ panel = BrowserPanel.Filters }) { Text("$kind · 全部学校") }
                    if (query.isNotBlank()) TextButton({ panel = BrowserPanel.Search }) { Text("搜索：$query") }
                }) {
                if (scenario == "加载中") item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("模拟加载中") }
                if (scenario == "失败") item { Text("模拟目录加载失败"); SystemDialogButton({ scenario = "长列表" }) { Text("重试") } }
                val entries = if (scenario == "空列表" || scenario == "失败") emptyList() else
                    (1..if (tab == 0) 100 else 25).filter {
                        (query.isBlank() || "模拟插件 $it".contains(query)) &&
                            (kind == "全部" || kind == listOf("教务适配", "校园服务", "通用工具")[it % 3])
                    }
                item { Text("${entries.size} 个 · $kind", style = MaterialTheme.typography.titleSmall) }
                if (entries.isEmpty()) item { Text("没有匹配的模拟插件") }
                items(entries, key = { "preview-$it" }) { index ->
                    InsetGroupedSection {
                        InsetGroupedRow("模拟插件 $index", subtitle = "用于验证滚动、返回和大字体", onClick = { details = index })
                    }
                }
            }
            if (controls) SystemDialog(onDismissRequest = { controls = false }, title = { Text("预览选项") }, scrollContent = true,
                confirmButton = { SystemDialogButton({ controls = false }, primary = true) { Text("完成") } }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("长列表", "空列表", "加载中", "失败").forEach { value -> FilterChip(scenario == value, { scenario = value }, label = { Text(value) }) }
                }
                listOf("深色主题" to (dark to { value: Boolean -> onDarkChange(value) }),
                    "玻璃背景" to (glass to { value: Boolean -> glass = value }),
                    "减少面板动效" to (reduced to { value: Boolean -> reduced = value }),
                    "大字体 180%" to (largeFont to { value: Boolean -> largeFont = value })).forEach { (label, setting) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, Modifier.weight(1f)); Switch(setting.first, setting.second)
                    }
                }
                Text("宽度与短窗口请调整窗口或旋转。此预览不能替代真机 GPU 验收。", style = MaterialTheme.typography.bodySmall)
            }
            details?.let { index -> SystemDialog({ details = null }, title = { Text("模拟插件 $index") },
                confirmButton = { SystemDialogButton({ details = null }) { Text("返回列表") } }) {
                Text("关闭后保留列表位置和筛选；不提供真实安装操作。")
            } }
        }
    }
}

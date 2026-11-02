package com.tyust.course.academic.plugin

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemDialogButton
import org.json.JSONObject

/** Presentation only: the original disclosure remains intact and never determines authority. */
@Composable
internal fun PluginScopeConsentDialog(prompt: PagePrompt) {
    var expanded by rememberSaveable(prompt.message) { mutableStateOf(false) }
    val lines = prompt.message.lines()
    val endpoints = lines.filter { it.startsWith("https://") }
    val sites = endpoints.mapNotNull { runCatching { java.net.URI(it).authority }.getOrNull() }.distinct()
    val purposes = lines.filter { " · " in it }.map { it.substringAfter(" · ") }.distinct()
    SystemDialog(
        onDismissRequest = { prompt.result.complete(null) },
        title = { Text(prompt.title) },
        scrollContent = true,
        confirmButton = {
            SystemDialogButton(primary = true, onClick = { prompt.result.complete(JSONObject()) }) { Text("允许并记住") }
        },
        dismissButton = { SystemDialogButton(onClick = { prompt.result.complete(null) }) { Text("拒绝") } }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(lines.firstOrNull().orEmpty())
            if (endpoints.isNotEmpty()) Text("本次申请 ${sites.size} 个站点、${endpoints.size} 项网络范围。")
            if (purposes.isNotEmpty()) Text("用途：${purposes.joinToString("；")}")
            Text("允许后，将记住此插件、安装包、服务账号和学校的这些网络范围，关闭页面或重启 App 后可复用。提交业务数据仍需确认。")
            Text("撤销入口：插件详情 → 权限与数据 → 撤销学校网络授权")
            SystemDialogButton(modifier = Modifier.fillMaxWidth(), onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起授权详情" else "查看完整授权详情（站点、路径、方法与用途）")
            }
            if (expanded) SelectionContainer { Text(prompt.message) }
        }
    }
}

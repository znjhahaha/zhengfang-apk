package com.tyust.course.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tyust.course.academic.plugin.PluginPage
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemPicker
import com.tyust.course.ui.system.SystemPrimaryButton

@Composable
fun StartupPageSettingsDialog(pages: List<PluginPage>, selectedRoute: String, onPageChange: (String) -> Unit, onDismiss: () -> Unit) {
    SystemDialog(
        onDismissRequest = onDismiss,
        title = { Text("启动首屏") },
        confirmButton = { SystemPrimaryButton("完成", onDismiss, Modifier.fillMaxWidth()) }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("下次启动应用时，直接进入选择的页面。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val unavailable = pages.none { it.id == selectedRoute }
            val options = if (unavailable) listOf(PluginPage(selectedRoute, "页面暂不可用，暂用课表")) + pages else pages
            SystemPicker(
                options = options.map { it.title },
                selectedIndex = options.indexOfFirst { it.id == selectedRoute },
                onSelect = { if (!unavailable || it != 0) onPageChange(options[it].id) },
                label = "首屏页面",
                modifier = Modifier.fillMaxWidth().testTag("startup-page-picker")
            )
        }
    }
}

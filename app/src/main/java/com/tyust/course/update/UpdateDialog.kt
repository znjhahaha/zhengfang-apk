package com.tyust.course.update

import com.tyust.course.ui.system.SystemDialogButton
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSecondaryButton

@Composable
fun UpdateDialog(manager: UpdateManager = UpdateManager.getInstance(LocalContext.current), onDismiss: () -> Unit = { manager.dismiss() }) {
    val state by manager.state.collectAsState()
    val info = state.manifest
    val active = state.active
    var showMirrors by remember { mutableStateOf(false) }
    val current = manager.getCurrentVersionCode()
    val canDownload = info?.isInstallCandidate(current, Build.VERSION.SDK_INT, state.testChannel) == true
    SystemDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.testChannel) "测试更新" else "应用更新") },
        confirmButton = {
            when {
                active -> SystemPrimaryButton(text = "暂停下载", onClick = { manager.pauseDownload() }, modifier = Modifier.fillMaxWidth())
                canDownload && state.phase == UpdateManager.Phase.READY -> SystemPrimaryButton(text = "安装", onClick = { manager.installReady() }, modifier = Modifier.fillMaxWidth())
                canDownload -> SystemPrimaryButton(text = if (state.phase == UpdateManager.Phase.PAUSED) "继续下载" else if (info!!.versionCode == current) "重新下载测试包" else "下载更新",
                    onClick = { manager.startDownload(testCurrent = state.testChannel) }, modifier = Modifier.fillMaxWidth())
                else -> SystemPrimaryButton(text = "重新检查", onClick = { manager.checkForUpdate(manual = true) }, modifier = Modifier.fillMaxWidth(), enabled = state.check != UpdateManager.Check.CHECKING)
            }
        },
        dismissButton = { SystemSecondaryButton(text = if (active) "后台继续" else "稍后", onClick = onDismiss, modifier = Modifier.fillMaxWidth()) }
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("当前渠道：" + if (state.testChannel) "测试版" else "正式版", style = MaterialTheme.typography.bodySmall)
            Text(state.message.ifBlank { "检查版本与下载进度" }, color = if (state.errorCode.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.check == UpdateManager.Check.CACHED) Text("当前为缓存信息，无法确认是否已有更新版本。", style = MaterialTheme.typography.bodySmall)
            val versionLabel = when {
                canDownload && info!!.versionCode > current -> "v${manager.getCurrentVersionName()} → v${info.versionName}"
                canDownload -> "当前测试版本 v${manager.getCurrentVersionName()}"
                else -> "当前版本 v${manager.getCurrentVersionName()}"
            }
            Text(versionLabel, style = MaterialTheme.typography.titleMedium)
            if (canDownload && info != null) {
                Text("${info.artifactLabel} · ${"%.1f".format(info.size / 1048576.0)} MiB", style = MaterialTheme.typography.bodySmall)
                if (info.forceUpdate) Text("此版本包含重要更新，请尽快安装", color = MaterialTheme.colorScheme.error)
                if (info.releaseNotes.isNotBlank()) Text(info.releaseNotes, style = MaterialTheme.typography.bodyMedium)
            }
            if (canDownload) state.mirror?.let { Text("下载线路：${it.name}", style = MaterialTheme.typography.bodySmall) }
            if (canDownload && (active || state.phase in setOf(UpdateManager.Phase.PAUSED, UpdateManager.Phase.READY))) {
                LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("${state.progress}% · ${"%.1f".format(state.bytes / 1048576.0)} / ${"%.1f".format((info?.size ?: 0) / 1048576.0)} MiB", style = MaterialTheme.typography.bodySmall)
            }
            if (active || state.phase == UpdateManager.Phase.PAUSED) SystemDialogButton(onClick = { manager.pauseDownload(cancel = true) }) { Text("取消下载") }
            if (canDownload && info != null) {
                SystemDialogButton(onClick = { showMirrors = !showMirrors }) { Text(if (showMirrors) "收起下载线路" else "下载线路与浏览器下载") }
                if (showMirrors) info.mirrors.forEach { mirror ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SystemDialogButton(enabled = !active && canDownload, onClick = { manager.startDownload(mirror.id, state.testChannel) }) { Text(mirror.name) }
                        SystemDialogButton(onClick = { manager.openBrowser(mirror) }) { Text("浏览器打开") }
                    }
                }
            }
        }
    }
}

@Composable
fun rememberUpdateState(): UpdateState {
    val manager = UpdateManager.getInstance(LocalContext.current)
    val observed = manager.state.collectAsState()
    return remember(manager) { UpdateState(manager) { observed.value } }
}
class UpdateState(private val manager: UpdateManager, private val snapshot: () -> UpdateManager.State) {
    fun checkForUpdate() = manager.checkForUpdate()
    fun updateInfo() = snapshot().manifest
    fun showDialog() = snapshot().showDialog
    fun dismiss() = manager.dismiss()
}

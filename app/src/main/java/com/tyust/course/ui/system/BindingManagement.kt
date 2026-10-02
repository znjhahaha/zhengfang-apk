package com.tyust.course.ui.system

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tyust.course.manager.StudentLimitManager
import com.tyust.course.manager.UserManager

@Composable
fun BindingManagementContent(onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val session by UserManager.getInstance().sessionState.state.collectAsState()
    val records = remember(revision, session) { StudentLimitManager.getBoundStudents(context) }
    val current = StudentLimitManager.currentBindingKey()
    var requested by remember { mutableStateOf<List<StudentLimitManager.BoundStudentRecord>?>(null) }
    var result by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("本机保留 ${records.size}/${StudentLimitManager.MAX_STUDENTS} 个绑定", style = MaterialTheme.typography.bodyMedium)
        records.forEach { record ->
            val active = record.schoolId to record.identity == current
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(record.schoolName.ifBlank { record.schoolId }, style = MaterialTheme.typography.labelMedium)
                    Text(record.displayName, style = MaterialTheme.typography.bodyMedium)
                }
                SystemSecondaryButton(text = if (active) "正在使用" else "解绑", enabled = !active,
                    onClick = { requested = listOf(record) })
            }
        }
        val removable = records.filter { it.schoolId to it.identity != current }
        if (removable.isNotEmpty()) SystemSecondaryButton(text = if (current == null) "解绑全部记录" else "解绑所有非当前账号",
            onClick = { requested = removable })
        Text("解绑只释放本机名额，保留保存的账号、凭据和缓存。再次使用时需要重新占用名额。", style = MaterialTheme.typography.bodySmall)
        if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodySmall)
    }
    requested?.let { snapshot ->
        SystemConfirmDialog(title = "释放 ${snapshot.size} 个绑定名额？",
            text = snapshot.joinToString("\n") { "${it.schoolName} · ${it.displayName}" } +
                "\n仅释放本机名额，不修改学校数据，也不删除账号、凭据或课表缓存。再次使用需要重新占用名额；当前登录账号会保留。",
            confirmText = "确认解绑", onDismiss = { requested = null }, onConfirm = {
                val count = StudentLimitManager.release(context, snapshot)
                requested = null; revision++
                result = if (count > 0) "已释放 $count 个名额" else "未释放记录：账号状态已变化或保存失败，请检查后重试"
                onChanged()
            })
    }
}

@Composable
fun BindingManagementDialog(onDismiss: () -> Unit) {
    SystemDialog(onDismissRequest = onDismiss, title = { Text("管理本机绑定") },
        confirmButton = { SystemDialogButton(onClick = onDismiss) { Text("完成") } }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            BindingManagementContent()
        }
    }
}

package com.tyust.course.ui.route

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.tyust.course.manager.UserManager
import com.tyust.course.study.*
import com.tyust.course.ui.screen.*
import com.tyust.course.ui.system.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExamManagement(val items: List<ExamItemUi>, val add: () -> Unit,
    val edit: (ExamItemUi) -> Unit, val delete: (ExamItemUi) -> Unit)

@Composable
fun rememberExamManagement(store: AcademicSnapshotStore, scope: AcademicDataScope, term: String,
    automatic: List<ExamItemUi>, enabled: Boolean = true): ExamManagement {
    val session by UserManager.getInstance().sessionState.state.collectAsState()
    val tasks = rememberCoroutineScope()
    var manual by remember(scope.manualScope(), term) { mutableStateOf(store.manual(scope, term)) }
    val records = remember(automatic, term) { ExamRecords.automatic(term, automatic) }
    val combined = remember(records, manual) { ExamRecords.merge(records, manual) }
    var adding by remember(scope, term) { mutableStateOf(false) }
    var editing by remember(scope, term) { mutableStateOf<ExamRecord?>(null) }
    var deleting by remember(scope, term) { mutableStateOf<ExamRecord?>(null) }
    var relink by remember(scope, term) { mutableStateOf<ExamRecord?>(null) }
    var saving by remember(scope, term) { mutableStateOf(false) }
    fun change(next: List<ExamRecord>, done: () -> Unit) {
        if (saving) return
        saving = true
        val expected = session.token
        tasks.launch {
            try {
                val requestJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                val saved = withContext(Dispatchers.IO) { store.saveManual(scope, term, next, expected) { requestJob?.isActive != false } }
                if (saved) { manual = next; done() } else GlassToaster.show("考试保存失败，请重试")
            } finally { saving = false }
        }
    }
    if (enabled && (adding || editing != null)) {
        val selected = editing
        ExamEditorDialog(selected?.exam, onDismiss = { adding = false; editing = null; relink = null }, onSave = { value ->
            val entry = when {
                selected == null -> ExamRecord.manual(term, value)
                relink != null -> relink!!.overrideWith(value).copy(id = selected.id)
                else -> selected.overrideWith(value)
            }
            change(manual.filterNot { it.id == entry.id } + entry) { adding = false; editing = null; relink = null }
        }, candidates = if (selected?.overridesId?.isNotBlank() == true) records.map { it.exam.copy(recordId = it.id) } else emptyList(),
            onRelink = { item -> relink = records.firstOrNull { it.id == item.recordId } })
    }
    deleting?.let { record -> SystemDialog(onDismissRequest = { deleting = null }, title = { Text("删除手动考试？") },
        confirmButton = { SystemDialogButton(onClick = { change(manual.filterNot { it.id == record.id }) { deleting = null } }, destructive = true) { Text("删除") } },
        dismissButton = { SystemDialogButton(onClick = { deleting = null }) { Text("取消") } }) {
        Text(if (record.overridesId.isBlank()) "删除后无法恢复这条手动考试。" else "删除手动覆盖后，将恢复教务系统最新的考试信息。")
    } }
    return ExamManagement(combined.map { it.exam.copy(recordId = it.id, manual = it.manual, needsRelink = it.needsRelink) },
        { if (enabled) { adding = true; editing = null; relink = null } },
        { item -> if (enabled) { editing = combined.firstOrNull { it.id == item.recordId }; adding = false; relink = null } },
        { item -> if (enabled) deleting = combined.firstOrNull { it.id == item.recordId && it.manual } })
}

internal fun academicSyncStatus(at: Long, loading: Boolean, error: String): String {
    val date = if (at > 0) java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(at)) else ""
    return when {
        loading -> if (date.isBlank()) "正在同步" else "本地数据 $date · 正在同步"
        error.isNotBlank() -> if (date.isBlank()) "同步未完成" else "本地数据 $date · 同步未完成"
        date.isNotBlank() -> "最近同步 $date"
        else -> "本地手动考试"
    }
}

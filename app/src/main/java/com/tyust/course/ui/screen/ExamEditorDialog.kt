package com.tyust.course.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.*
import java.text.SimpleDateFormat
import java.util.*

internal fun examDate(value: String): String = Regex("\\d{4}-\\d{2}-\\d{2}").find(value)?.value.orEmpty()

@Composable
fun ExamEditorDialog(initial: ExamItemUi?, onDismiss: () -> Unit, onSave: (ExamItemUi) -> Unit,
    candidates: List<ExamItemUi> = emptyList(), onRelink: ((ExamItemUi) -> Unit)? = null) {
    var name by rememberSaveable { mutableStateOf(initial?.courseName.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(examDate(initial?.examTime.orEmpty())) }
    var time by rememberSaveable { mutableStateOf(initial?.examTime.orEmpty().let { value ->
        val initialDate = examDate(value)
        if (initialDate.isBlank()) value.trim() else value.substringAfter(initialDate).trim()
    }) }
    var location by rememberSaveable { mutableStateOf(initial?.location.orEmpty()) }
    var seat by rememberSaveable { mutableStateOf(initial?.seatNumber.orEmpty()) }
    var type by rememberSaveable { mutableStateOf(initial?.examName.orEmpty()) }
    var teacher by rememberSaveable { mutableStateOf(initial?.teacher.orEmpty()) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var linked by remember { mutableStateOf<ExamItemUi?>(null) }
    val format = remember { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false } }
    val validDate = Regex("\\d{4}-\\d{2}-\\d{2}").matches(date) &&
        runCatching { format.parse(date) != null }.getOrDefault(false)
    SystemDialog(onDismissRequest = onDismiss, scrollContent = true,
        title = { Text(if (initial == null) "添加考试" else "编辑考试") },
        confirmButton = { SystemDialogButton(onClick = {
            linked?.let { onRelink?.invoke(it) }
            onSave(ExamItemUi(name.trim(), date + if (time.isBlank()) "" else " ${time.trim()}",
                location.trim(), seat.trim(), type.trim(), teacher.trim()))
        }, primary = true, enabled = name.isNotBlank() && validDate, modifier = Modifier.testTag("exam-save")) { Text("保存") } },
        dismissButton = { SystemDialogButton(onClick = onDismiss) { Text("取消") } }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SchoolFormField("课程名称", name, { name = it }, modifier = Modifier.testTag("exam-name"))
            SchoolFormField("考试日期", date, { date = it }, placeholder = "YYYY-MM-DD",
                error = if (date.isNotBlank() && !validDate) "请输入有效日期" else null,
                modifier = Modifier.testTag("exam-date-input"))
            SystemDialogButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth().testTag("exam-date")) {
                Text(date.ifBlank { "选择考试日期" })
            }
            SystemDialogButton(onClick = { pickTime = true }, modifier = Modifier.fillMaxWidth()) { Text(time.ifBlank { "选择考试时间（选填）" }) }
            if (time.isNotBlank()) TextButton(onClick = { time = "" }) { Text("清除时间") }
            SchoolFormField("地点", location, { location = it }, modifier = Modifier.testTag("exam-location"))
            SchoolFormField("座位号", seat, { seat = it })
            SchoolFormField("考试类型", type, { type = it }, placeholder = "例如期末、补考")
            SchoolFormField("教师", teacher, { teacher = it })
            if (candidates.isNotEmpty() && onRelink != null) {
                Text("重新关联教务考试")
                candidates.forEach { candidate -> TextButton(onClick = { linked = candidate }) {
                    Text((if (linked?.recordId == candidate.recordId) "✓ " else "") + candidate.courseName + " · " + candidate.examTime)
                } }
            }
        }
    }
    if (pickDate) GlassDatePickerDialog(onConfirm = { date = format.format(Date(it)); pickDate = false },
        onDismiss = { pickDate = false }, initialMillis = runCatching { format.parse(date)?.time }.getOrNull() ?: System.currentTimeMillis(),
        title = "考试日期", preserveInitial = true)
    if (pickTime) {
        val clocks = Regex("\\d{2}:\\d{2}").findAll(time).take(2).map { it.value }.toList()
        GlassTimeRangePickerDialog(onConfirm = { start, end -> time = "$start-$end"; pickTime = false },
            onDismiss = { pickTime = false }, initialStart = clocks.getOrNull(0) ?: "08:00",
            initialEnd = clocks.getOrNull(1) ?: "10:00", title = "考试时间", minuteStep = 1, preserveInitial = true)
    }
}

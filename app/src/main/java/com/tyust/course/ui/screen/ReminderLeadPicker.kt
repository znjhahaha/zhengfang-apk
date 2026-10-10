package com.tyust.course.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.*

@Composable fun ReminderLeadPicker(current: Int, allowDefault: Boolean = false, onDismiss: () -> Unit, onSave: (Int?) -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val minutes = text.toIntOrNull()?.takeIf { it in 0..1440 }
    SystemDialog(onDismissRequest = onDismiss, title = { Text("课前提醒时间") }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(0, 5, 10, 15, 30, 60).chunked(3).forEach { values ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    values.forEach { n -> FilterChip(selected = minutes == n, onClick = { text = n.toString() },
                        label = { Text(if (n == 0) "上课时" else "$n 分钟") }) }
                }
            }
            OutlinedTextField(text, { text = it.filter(Char::isDigit).take(4) }, label = { Text("提前分钟数（0–1440）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, isError = minutes == null)
            if (allowDefault) SystemDialogButton(onClick = { onSave(null); onDismiss() }) { Text("跟随全局默认") }
            SystemDialogButton(primary = true, enabled = minutes != null, onClick = { minutes?.let(onSave); onDismiss() }) { Text("保存") }
        }
    }
}

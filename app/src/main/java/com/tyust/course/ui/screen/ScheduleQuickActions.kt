package com.tyust.course.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.*

@Composable
internal fun ScheduleQuickActions(course: ScheduleCourseUi, reminderEnabled: Boolean, onDismiss: () -> Unit,
    onReminder: () -> Unit, onCopy: () -> Unit, onEdit: () -> Unit) {
    SystemDialog(onDismissRequest = onDismiss, title = { Text(course.name) }) {
        Column(Modifier.fillMaxWidth()) {
            QuickCourseAction(if (reminderEnabled) "关闭提醒" else "开启提醒", onReminder) {
                AnimatedLineIcon(AnimatedIconSpec.Bell, Modifier.size(20.dp))
            }
            if (course.location.isNotBlank()) QuickCourseAction("复制教室", onCopy) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(20.dp))
            }
            if (course.isCustom) QuickCourseAction("编辑课程", onEdit) {
                AnimatedLineIcon(AnimatedIconSpec.Edit, Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun QuickCourseAction(text: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        icon()
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

package com.tyust.course.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.ui.system.*

/** School-list maintenance only; packages, accounts and course caches have separate lifetimes. */
@Composable
fun SchoolManagementDialog(selectedSchoolId: String?, onSelect: (SchoolConfig) -> Unit,
                           onChanged: () -> Unit, onDismiss: () -> Unit) {
    val manager = UserManager.getInstance()
    var schools by remember { mutableStateOf(manager.supportedSchools) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SchoolConfig?>(null) }
    var notice by remember { mutableStateOf("") }
    SystemDialog(onDismissRequest = onDismiss, title = { Text("选择与管理学校") },
        confirmButton = { TextButton(onClick = { adding = true }) { Text("添加学校") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }) {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            schools.forEach { school ->
                val custom = manager.isCustomSchool(school.id)
                InsetGroupedRow(title = school.name,
                    subtitle = if (school.id == selectedSchoolId) "已选择" else if (custom) "自行添加" else "内置学校",
                    onClick = { onSelect(school); onDismiss() },
                    trailing = {
                        if (custom) IconButton(onClick = { removing = school },
                            modifier = Modifier.testTag("school-remove-${school.id}")) {
                            Icon(Icons.Outlined.DeleteOutline, "移除${school.name}")
                        }
                    })
            }
            Text("可移除自行添加的测试学校。已安装插件、账号记录和本地课表会保留。",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
    }
    if (adding) AddSchoolDialog(onDismiss = { adding = false }, onConfirm = { draft ->
        val school = draft.toSchoolConfig()
        manager.addCustomSchool(school)
        schools = manager.supportedSchools
        onChanged()
        adding = false
        val actual = manager.getSchoolById(school.id) ?: schools.firstOrNull {
            it.domain.equals(school.domain, true) && it.protocol == school.protocol &&
                it.basePath.trimEnd('/') == school.basePath.trimEnd('/') && it.academicProvider.isBlank()
        }
        if (actual != null) { onSelect(actual); onDismiss() }
    })
    removing?.let { school ->
        SystemConfirmDialog(title = "移除 ${school.name}？",
            text = "仅从学校列表移除，不卸载插件，也不删除账号或课表。后续可通过同一插件重新添加。",
            confirmText = "移除", onDismiss = { removing = null }, onConfirm = {
                if (!manager.canRemoveCustomSchool(school.id)) {
                    notice = "当前登录中的学校不能移除，请先退出或切换账号。"
                } else {
                    manager.removeCustomSchool(school.id)
                    schools = manager.supportedSchools
                    onChanged()
                    notice = ""
                }
                removing = null
            })
    }
}

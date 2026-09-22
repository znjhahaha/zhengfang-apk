package com.tyust.course.ui.route

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.SystemEmptyState
import com.tyust.course.ui.system.SystemTopBar

@Composable internal fun AcademicCapabilityUnavailable(title: String, message: String) {
    Column(Modifier.fillMaxSize()) {
        SystemTopBar(title = title)
        Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            SystemEmptyState(title = "尚未适配", message = message)
        }
    }
}

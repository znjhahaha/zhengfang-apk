package com.tyust.course.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tyust.course.manager.ScheduleSettingsManager
import com.tyust.course.manager.ScheduleSettingsManager.PeriodTime
import com.tyust.course.ui.theme.SystemBlue
import com.tyust.course.ui.theme.Neutral50
import com.tyust.course.ui.theme.Neutral100
import com.tyust.course.ui.theme.Neutral200
import com.tyust.course.ui.theme.SurfaceWhite
import com.tyust.course.ui.theme.Neutral900
import com.tyust.course.ui.theme.Neutral500
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSettingsScreen(
    manager: ScheduleSettingsManager,
    onClose: () -> Unit,
    onShowDatePicker: () -> Unit
) {
    // State
    var periodCount by remember { mutableStateOf(manager.periodCount) }
    var periodTimes by remember { mutableStateOf(manager.getPeriodTimes()) }
    var semesterStartDate by remember { mutableStateOf(manager.semesterStartDate) }
    
    // Formatting
    val dateFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA)
    val dateText = if (semesterStartDate > 0) dateFormat.format(Date(semesterStartDate)) else "未设置"
    
    // Sync state from manager if changed externally (e.g. date picker)
    LaunchedEffect(manager.semesterStartDate) {
        semesterStartDate = manager.semesterStartDate
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课表设置", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceWhite
                ),
                actions = {
                    TextButton(onClick = onClose) {
                        Text("完成", fontWeight = FontWeight.Bold)
                    }
                }
            )
        },
        containerColor = Neutral50
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section: Basic Settings
            item {
                Text(
                    "基础设置", 
                    style = MaterialTheme.typography.labelLarge, 
                    color = SystemBlue,
                    fontWeight = FontWeight.Bold
                )
            }
            
            item {
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Filled.DateRange,
                        title = "第一周开始日期",
                        value = dateText,
                        onClick = onShowDatePicker
                    )
                    HorizontalDivider(color = Neutral200)
                    
                    // Period Count Dropdown Logic
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        SettingsRow(
                            icon = Icons.Outlined.AccessTime,
                            title = "每天节数",
                            value = "${periodCount}节",
                            onClick = { expanded = true }
                        )
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.background(SurfaceWhite)
                        ) {
                            (8..16).forEach { count ->
                                DropdownMenuItem(
                                    text = { Text("${count}节") },
                                    onClick = {
                                        periodCount = count
                                        manager.periodCount = count
                                        expanded = false
                                    },
                                    trailingIcon = if (count == periodCount) {
                                        { Icon(Icons.Filled.Check, null, tint = SystemBlue) }
                                    } else null
                                )
                            }
                        }
                    }
                }
            }
            
            // Section: Period Times
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "节次时间", 
                    style = MaterialTheme.typography.labelLarge, 
                    color = SystemBlue,
                    fontWeight = FontWeight.Bold
                )
            }
            
            items(periodTimes) { periodTime ->
                PeriodTimeRow(
                    periodTime = periodTime,
                    onUpdate = { newStart, newEnd ->
                        val newList = periodTimes.toMutableList()
                        val index = newList.indexOfFirst { it.period == periodTime.period }
                        if (index != -1) {
                            newList[index] = PeriodTime(periodTime.period, newStart, newEnd)
                            periodTimes = newList
                            manager.savePeriodTimes(newList)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Neutral200),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SystemBlue,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = SystemBlue,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Default.ArrowDropDown, // Or generic arrow
            contentDescription = null,
            tint = Neutral500
        )
    }
}

@Composable
fun PeriodTimeRow(
    periodTime: PeriodTime,
    onUpdate: (String, String) -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Neutral200),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clickable { showEditDialog = true }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SystemBlue.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${periodTime.period}",
                    fontWeight = FontWeight.Bold,
                    color = SystemBlue
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Text(
                "第 ${periodTime.period} 节",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            Surface(
                color = Neutral100,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = "${periodTime.startTime} - ${periodTime.endTime}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
    
    if (showEditDialog) {
        TimeEditDialog(
            initialStart = periodTime.startTime,
            initialEnd = periodTime.endTime,
            onDismiss = { showEditDialog = false },
            onSave = { s, e ->
                onUpdate(s, e)
                showEditDialog = false
            }
        )
    }
}

@Composable
fun TimeEditDialog(
    initialStart: String,
    initialEnd: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var start by remember { mutableStateOf(initialStart) }
    var end by remember { mutableStateOf(initialEnd) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑时间") },
        text = {
            Column {
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text("开始时间 (HH:MM)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    label = { Text("结束时间 (HH:MM)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(start, end) }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

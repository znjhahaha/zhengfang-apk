package com.tyust.course.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tyust.course.model.SchoolConfig
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSecondaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSchoolConfigDialog(
    school: SchoolConfig,
    onDismiss: () -> Unit,
    onSave: (SchoolConfig) -> Unit
) {
    // State for all editable fields
    var name by remember { mutableStateOf(school.name) }
    var domain by remember { mutableStateOf(school.domain) }
    var protocol by remember { mutableStateOf(school.protocol) }
    var basePath by remember { mutableStateOf(school.basePath) }
    var courseGnmkdm by remember { mutableStateOf(school.courseGnmkdm) }
    var gradeGnmkdm by remember { mutableStateOf(school.gradeGnmkdm) }
    var scheduleGnmkdm by remember { mutableStateOf(school.scheduleGnmkdm) }
    
    // URL input for smart parsing
    var urlInput by remember { mutableStateOf("") }
    
    // Advanced paths
    var showAdvanced by remember { mutableStateOf(false) }
    var studentInfoPath by remember { mutableStateOf(school.studentInfoPath) }
    var courseIndexPath by remember { mutableStateOf(school.courseIndexPath) }
    var courseListPath by remember { mutableStateOf(school.courseListPath) }
    var selectCoursePath by remember { mutableStateOf(school.selectCoursePath) }
    var schedulePath by remember { mutableStateOf(school.schedulePath) }
    var gradesPath by remember { mutableStateOf(school.gradesPath) }
    
    // Protocol dropdown state
    var protocolExpanded by remember { mutableStateOf(false) }
    
    // Smart URL parsing function
    fun parseUrl(url: String) {
        if (url.isBlank()) return
        
        var cleanUrl = url.trim()
        
        // Extract protocol
        when {
            cleanUrl.startsWith("https://") -> {
                protocol = "https"
                cleanUrl = cleanUrl.removePrefix("https://")
            }
            cleanUrl.startsWith("http://") -> {
                protocol = "http"
                cleanUrl = cleanUrl.removePrefix("http://")
            }
        }
        
        // Extract domain and base path
        val pathStart = cleanUrl.indexOf('/')
        if (pathStart > 0) {
            domain = cleanUrl.substring(0, pathStart)
            val pathPart = cleanUrl.substring(pathStart)
            
            // Find common base paths like /jwglxt, /jwxt, /jw
            val commonPaths = listOf("/jwglxt", "/jwxt", "/jwxs", "/jw", "/xk")
            for (commonPath in commonPaths) {
                if (pathPart.startsWith(commonPath)) {
                    val endIndex = pathPart.indexOf('/', commonPath.length)
                    basePath = if (endIndex > 0) {
                        pathPart.substring(0, endIndex)
                    } else {
                        commonPath
                    }
                    break
                }
            }
            
            // If no common path found, try to extract first path segment
            if (basePath == school.basePath && pathPart.length > 1) {
                val secondSlash = pathPart.indexOf('/', 1)
                if (secondSlash > 1) {
                    basePath = pathPart.substring(0, secondSlash)
                }
            }
        } else {
            domain = cleanUrl.split("?")[0]
        }
    }
    
    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "编辑学校配置",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "保存",
                onClick = {
                    // Create updated config
                    val updatedSchool = SchoolConfig(school.id, name, domain, protocol).apply {
                        this.basePath = basePath
                        this.courseGnmkdm = courseGnmkdm
                        this.gradeGnmkdm = gradeGnmkdm
                        this.scheduleGnmkdm = scheduleGnmkdm
                        this.studentInfoPath = studentInfoPath
                        this.courseIndexPath = courseIndexPath
                        this.courseListPath = courseListPath
                        this.selectCoursePath = selectCoursePath
                        this.schedulePath = schedulePath
                        this.gradesPath = gradesPath
                    }
                    onSave(updatedSchool)
                },
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            SystemSecondaryButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 350.dp) // 限制最大高度以容纳下方大圆角按钮
                .verticalScroll(rememberScrollState())
        ) {
            // Smart URL Parser Section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "智能识别 URL",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("粘贴教务系统 URL") },
                        placeholder = { Text("http://jwxt.example.edu.cn/jwglxt") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Button(
                        onClick = { parseUrl(urlInput) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        enabled = urlInput.isNotBlank()
                    ) {
                        Text("自动识别并填充")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "基本配置",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // School Name
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("学校名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Domain
            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                label = { Text("教务系统域名") },
                placeholder = { Text("jwxt.example.edu.cn") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Protocol Dropdown
            ExposedDropdownMenuBox(
                expanded = protocolExpanded,
                onExpandedChange = { protocolExpanded = !protocolExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = protocol.uppercase(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("协议") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                )
                
                ExposedDropdownMenu(
                    expanded = protocolExpanded,
                    onDismissRequest = { protocolExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { 
                            Column {
                                Text("HTTPS", fontWeight = FontWeight.Medium)
                                Text("安全连接 (推荐)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = {
                            protocol = "https"
                            protocolExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { 
                            Column {
                                Text("HTTP", fontWeight = FontWeight.Medium)
                                Text("非加密连接", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = {
                            protocol = "http"
                            protocolExpanded = false
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Base Path
            OutlinedTextField(
                value = basePath,
                onValueChange = { basePath = it },
                label = { Text("基础路径") },
                placeholder = { Text("/jwglxt") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                supportingText = { Text("如 /jwglxt 或 /jwxt") }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "模块代码 (gnmkdm)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = courseGnmkdm,
                    onValueChange = { courseGnmkdm = it },
                    label = { Text("选课") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = gradeGnmkdm,
                    onValueChange = { gradeGnmkdm = it },
                    label = { Text("成绩") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Advanced toggle
            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (showAdvanced) "隐藏高级配置 ▲" else "显示高级配置 ▼")
            }
            
            // Advanced paths
            if (showAdvanced) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "URL 路径配置",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = studentInfoPath,
                    onValueChange = { studentInfoPath = it },
                    label = { Text("学生信息验证") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = courseIndexPath,
                    onValueChange = { courseIndexPath = it },
                    label = { Text("选课首页") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = courseListPath,
                    onValueChange = { courseListPath = it },
                    label = { Text("课程列表") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = selectCoursePath,
                    onValueChange = { selectCoursePath = it },
                    label = { Text("选课提交") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = schedulePath,
                    onValueChange = { schedulePath = it },
                    label = { Text("课表查询") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = gradesPath,
                    onValueChange = { gradesPath = it },
                    label = { Text("成绩查询") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }
    }
}

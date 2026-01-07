package com.tyust.course.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import com.tyust.course.model.Course
import com.tyust.course.ui.theme.PrimaryPurple

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectedCoursesScreen(
    courses: List<Course>,
    isLoading: Boolean,
    isDropping: Boolean = false,
    onRefresh: () -> Unit,
    onDropCourse: (Course) -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
    ) {
        // 添加退课进度条
        if (isDropping) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                color = Color(0xFFE53935),
                trackColor = Color(0xFFFFCDD2)
            )
        }
        
        SwipeRefresh(
            state = rememberSwipeRefreshState(isRefreshing = isLoading),
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            if (isLoading && courses.isEmpty()) {
                // 🔧 骨架屏加载状态
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(6) {
                        CourseSkeletonItem()
                    }
                }
            } else if (courses.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无已选课程数据\n请尝试刷新",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = Color.LightGray
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 🔧 显示已选课程数量
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = PrimaryPurple.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "📚",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "已选课程: ${courses.size} 门",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryPurple
                                )
                            }
                        }
                    }
                    
                    items(courses) { course ->
                        SelectedCourseItem(
                            course = course,
                            isDropping = isDropping,
                            onDrop = { onDropCourse(course) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SelectedCourseItem(
    course: Course,
    isDropping: Boolean = false,
    onDrop: () -> Unit = {}
) {
    // 🔧 退课确认对话框状态
    var showDropConfirmDialog by remember { mutableStateOf(false) }
    
    // 退课确认对话框
    if (showDropConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDropConfirmDialog = false },
            icon = { 
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = Color(0xFFE53935)
                )
            },
            title = { Text("确认退课") },
            text = { 
                Text("确定要退选「${course.name}」吗？\n\n退课后可能无法再次选上此课程。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDropConfirmDialog = false
                        onDrop()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE53935)
                    )
                ) {
                    Text("确认退课")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDropConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = course.name ?: "未知课程",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1A1A1A)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "ID: ${course.courseId ?: "--"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
                
                Surface(
                    color = PrimaryPurple.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "${course.credit ?: "0.0"} 学分",
                        style = MaterialTheme.typography.labelMedium,
                        color = PrimaryPurple,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = Color(0xFFEEEEEE),
                thickness = 1.dp
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // 教师
                InfoRow(label = "教师", value = course.teacher ?: "--", iconColor = Color(0xFF4CAF50))
                // 教学班
                InfoRow(label = "班级", value = course.classId ?: "--", iconColor = PrimaryPurple)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 时间地点：使用更柔和的背景
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFF1F3F5),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "📅 ${course.time ?: "时间未安排"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF495057)
                    )
                    if (!course.location.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "📍 ${course.location}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF495057)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 🔥 退课按钮
            Button(
                onClick = { showDropConfirmDialog = true },  // 🔧 改为显示确认对话框
                enabled = !isDropping,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE53935),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFFBDBDBD)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isDropping) "退课中..." else "退课",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, iconColor: Color) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = iconColor
        )
    }
}

@Composable
fun CourseSkeletonItem() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(modifier = Modifier.width(150.dp).height(20.dp).background(Color.LightGray.copy(alpha = 0.3f), RoundedCornerShape(4.dp)))
                Box(modifier = Modifier.width(50.dp).height(20.dp).background(Color.LightGray.copy(alpha = 0.3f), RoundedCornerShape(4.dp)))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.LightGray.copy(alpha = 0.1f)))
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(modifier = Modifier.width(60.dp).height(30.dp).background(Color.LightGray.copy(alpha = 0.3f), RoundedCornerShape(4.dp)))
                Box(modifier = Modifier.width(60.dp).height(30.dp).background(Color.LightGray.copy(alpha = 0.3f), RoundedCornerShape(4.dp)))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth().height(40.dp).background(Color.LightGray.copy(alpha = 0.2f), RoundedCornerShape(8.dp)))
        }
    }
}

package com.tyust.course.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.tyust.course.model.Course
import com.tyust.course.ui.theme.*
import com.tyust.course.ui.system.GlassPullRefreshBox
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import com.tyust.course.ui.system.SystemDestructiveButton
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemSecondaryButton
import com.tyust.course.ui.system.neumorphicShadow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectedCoursesScreen(
    courses: List<Course>,
    isLoading: Boolean,
    isDropping: Boolean = false,
    onRefresh: () -> Unit,
    onDropCourse: (Course) -> Unit = {}
) {

    // 透明容器：让 Aurora 壁纸透出，与"可选"页一致
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 退课进度条
        if (isDropping) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                color = SemanticDanger,
                trackColor = SemanticDanger.copy(alpha = 0.2f)
            )
        }

        GlassPullRefreshBox(
            isRefreshing = isLoading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                isLoading && courses.isEmpty() -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(6) {
                            CourseSkeletonItem()
                        }
                    }
                }
                courses.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "暂无已选课程\n下拉刷新获取数据",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = Neutral500
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            SystemCard(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "📚",
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "已选 ${courses.size} 门课程",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = NeuPrimary
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
}

@Composable
fun SelectedCourseItem(
    course: Course,
    isDropping: Boolean = false,
    onDrop: () -> Unit = {}
) {
    var showDropConfirmDialog by remember { mutableStateOf(false) }
    
    if (showDropConfirmDialog) {
        SystemDialog(
            onDismissRequest = { showDropConfirmDialog = false },
            title = {
                Text(
                    text = "确认退课",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = SemanticDanger
                )
            },
            dismissButton = {
                SystemSecondaryButton(
                    text = "取消",
                    onClick = { showDropConfirmDialog = false },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                SystemDestructiveButton(
                    text = "确认退课",
                    onClick = {
                        showDropConfirmDialog = false
                        onDrop()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Text(
                text = "确定要退选「${course.name ?: "未知课程"}」吗？\n\n退课后可能无法再次选上此课程。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
    
    SystemCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：课程信息及安排
            Column(modifier = Modifier.weight(1f)) {
                // 第一行：标题 + 学分
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = course.name ?: "未知课程",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    SystemStatusBadge(
                        text = "${course.credit ?: "0.0"}学分",
                        tone = SystemTone.Info
                    )
                }
                
                Spacer(modifier = Modifier.height(6.dp))
                
                // 第二行：教师和班级（紧凑同行显示）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = NeuPrimary.copy(alpha = 0.8f)
                        )
                        Text(
                            text = course.teacher ?: "--",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Group,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = NeuPrimary.copy(alpha = 0.8f)
                        )
                        Text(
                            text = course.jxbmc ?: course.classId ?: "--",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(6.dp))
                
                // 第三行：时间
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Text(
                        text = course.time ?: "时间未安排",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // 第四行：地点（若有）
                if (!course.location.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            text = course.location ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // 右侧：圆形红色退课按钮
            Surface(
                onClick = { showDropConfirmDialog = true },
                enabled = !isDropping,
                shape = RoundedCornerShape(19.dp),
                color = SemanticDanger,
                contentColor = Color.White,
                modifier = Modifier
                    .size(38.dp)
                    .neumorphicShadow(cornerRadius = 19.dp, elevation = 4.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isDropping) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "退课",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CourseSkeletonItem() {
    SystemCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(modifier = Modifier.width(150.dp).height(20.dp).background(Neutral200.copy(alpha = 0.5f), RoundedCornerShape(4.dp)))
                Box(modifier = Modifier.width(50.dp).height(20.dp).background(Neutral200.copy(alpha = 0.5f), RoundedCornerShape(4.dp)))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), thickness = 0.5.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(modifier = Modifier.width(60.dp).height(30.dp).background(Neutral200.copy(alpha = 0.5f), RoundedCornerShape(4.dp)))
                Box(modifier = Modifier.width(60.dp).height(30.dp).background(Neutral200.copy(alpha = 0.5f), RoundedCornerShape(4.dp)))
            }
            Box(modifier = Modifier.fillMaxWidth().height(40.dp).background(Neutral200.copy(alpha = 0.3f), RoundedCornerShape(8.dp)))
        }
    }
}

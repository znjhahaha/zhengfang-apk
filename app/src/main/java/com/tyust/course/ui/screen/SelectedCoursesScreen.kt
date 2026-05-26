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
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.tyust.course.model.Course
import com.tyust.course.ui.theme.*
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import com.tyust.course.ui.system.SystemDestructiveButton
import com.tyust.course.ui.system.SystemSecondaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectedCoursesScreen(
    courses: List<Course>,
    isLoading: Boolean,
    isDropping: Boolean = false,
    onRefresh: () -> Unit,
    onDropCourse: (Course) -> Unit = {}
) {
    val pullRefreshState = rememberPullToRefreshState(enabled = { !isLoading })

    LaunchedEffect(pullRefreshState.isRefreshing, isLoading) {
        if (pullRefreshState.isRefreshing && !isLoading) {
            onRefresh()
        }
    }
    LaunchedEffect(isLoading) {
        if (isLoading) pullRefreshState.startRefresh()
        else pullRefreshState.endRefresh()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NeuSurface)
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(pullRefreshState.nestedScrollConnection)
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

            PullToRefreshContainer(
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter)
            )
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
    
    // 定制滑入动效的退课确认对话框
    if (showDropConfirmDialog) {
        var animateTrigger by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { animateTrigger = true }

        fun dismiss() {
            animateTrigger = false
        }

        if (!animateTrigger) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(300)
                showDropConfirmDialog = false
            }
        }

        Dialog(onDismissRequest = { dismiss() }) {
            AnimatedVisibility(
                visible = animateTrigger,
                enter = slideInVertically(
                    initialOffsetY = { it / 3 },
                    animationSpec = tween(MotionDuration.DialogEnter, easing = MotionEasing.FastOutSlowIn)
                ) + fadeIn(animationSpec = tween(MotionDuration.Medium)),
                exit = slideOutVertically(
                    targetOffsetY = { it / 3 },
                    animationSpec = tween(MotionDuration.Medium, easing = MotionEasing.Accelerate)
                ) + fadeOut(animationSpec = tween(MotionDuration.Medium))
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "确认退课",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = SemanticDanger
                        )
                        
                        Text(
                            text = "确定要退选「${course.name ?: "未知课程"}」吗？\n\n退课后可能无法再次选上此课程。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SystemSecondaryButton(
                                text = "取消",
                                onClick = { dismiss() },
                                modifier = Modifier.weight(1f)
                            )
                            SystemDestructiveButton(
                                text = "确认退课",
                                onClick = {
                                    dismiss()
                                    onDrop()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
    
    SystemCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "ID: ${course.courseId ?: "--"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                SystemStatusBadge(
                    text = "${course.credit ?: "0.0"} 学分",
                    tone = SystemTone.Info
                )
            }
            
            // 分割线使用系统透明度
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 教师
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = NeuPrimary
                    )
                    Column {
                        Text(text = "教师", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = course.teacher ?: "--",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                
                // 班级
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Group,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = NeuPrimary
                    )
                    Column {
                        Text(text = "班级", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = course.jxbmc ?: course.classId ?: "--",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            
            // 时间地点：使用系统圆角柔和背景
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = NeuInsetBackground.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = course.time ?: "时间未安排",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!course.location.isNullOrEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Place,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = course.location ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(2.dp))
            
            SystemDestructiveButton(
                text = if (isDropping) "退课中..." else "退课",
                onClick = { showDropConfirmDialog = true },
                enabled = !isDropping,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
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

package com.tyust.course.ui.screen

import android.util.Log

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import com.tyust.course.model.Course
import com.tyust.course.ui.theme.PrimaryPurple
import com.tyust.course.ui.theme.PurpleGrey80

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CourseListScreen(
    courses: List<Course>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onSearch: (String) -> Unit,
    onCourseSelect: (Course) -> Unit,
    onAutoGrab: (Course) -> Unit,
    // 批量抢课支持
    onBatchSelect: (List<Course>) -> Unit = {},
    isBatchSelecting: Boolean = false,
    // 获取课程详情（展开时触发）- 回调 Boolean 表示是否成功
    onFetchDetails: (List<Course>, (Boolean) -> Unit) -> Unit = { _, callback -> callback(true) },
    // 🔧 预加载进度
    isPreloading: Boolean = false,
    preloadProgress: Float = 0f,
    preloadedGroupIds: Set<String> = emptySet(),
    // 🔧 交互锁：数据是否就绪
    isDetailsReady: Boolean = false,
    // 🔧 加入队列（定时抢课）
    onAddToQueue: (Course) -> Unit = {},
    // 🔧 设置为目标课程（长按触发）
    onSetTargetCourse: (Course) -> Unit = {},
    // 🔧 设置模糊匹配监控目标 (courseId, courseName, xkkzId, kklxdm)
    onSetFuzzyMatchTarget: ((String, String, String?, String?) -> Unit)? = null,
    // 🔧 状态由外部管理
    isMultiSelectMode: Boolean = false,
    selectedClassIds: Set<String> = emptySet(),
    onToggleSelection: (String, Boolean) -> Unit = { _, _ -> },
    onEnterMultiSelect: (String) -> Unit = {}
) {
    // 分组折叠状态: courseId -> isExpanded
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    // 正在加载详情的分组
    val loadingGroups = remember { mutableStateMapOf<String, Boolean>() }
    // 已加载详情的分组
    val loadedGroups = remember { mutableStateMapOf<String, Boolean>() }

    val groupedCourses = remember(courses) {
        courses.groupBy { (it.courseId ?: "") to (it.name ?: "") }.toList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
    ) {
        // 批量选课处理中标记
        if (isBatchSelecting) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = PrimaryPurple,
                trackColor = PrimaryPurple.copy(alpha = 0.1f)
            )
        }
        
        // 🔧 预加载进度条
        AnimatedVisibility(visible = isPreloading) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFE3F2FD))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚡ 正在加载课程详情...",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF1976D2)
                    )
                    Text(
                        text = "${(preloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1976D2)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { preloadProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Color(0xFF1976D2),
                    trackColor = Color(0xFFBBDEFB),
                )
            }
        }

        SwipeRefresh(
            state = rememberSwipeRefreshState(isRefreshing = isLoading),
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            if (courses.isEmpty() && !isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无课程数据\n请尝试刷新",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = Color.Gray
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(groupedCourses) { (key, classes) ->
                        val courseId = key.first
                        val courseName = key.second
                        val isExpanded = expandedGroups[courseId] == true

                        CourseGroupItem(
                            courseId = courseId,
                            courseName = courseName,
                            classes = classes,
                            isExpanded = isExpanded,
                            isLoading = loadingGroups[courseId] == true,
                            isDetailsReady = isDetailsReady, // Pass down
                            onExpandClick = {
                                if (!isExpanded && loadedGroups[courseId] != true) {
                                    // 首次展开，获取详情
                                    loadingGroups[courseId] = true
                                    onFetchDetails(classes) { success ->
                                        loadingGroups[courseId] = false
                                        if (success) {
                                            loadedGroups[courseId] = true
                                            expandedGroups[courseId] = true
                                        }
                                        // 失败时不标记为已加载，允许重新点击
                                    }
                                } else {
                                    expandedGroups[courseId] = !isExpanded
                                }
                            },
                            isMultiSelectMode = isMultiSelectMode,
                            selectedClassIds = selectedClassIds,
                            onToggleSelection = onToggleSelection,
                            onEnterMultiSelect = onEnterMultiSelect,
                            onCourseSelect = onCourseSelect,
                            onAutoGrab = onAutoGrab,
                            onAddToQueue = onAddToQueue,
                            onSetTargetCourse = onSetTargetCourse,
                            onSetFuzzyMatchTarget = onSetFuzzyMatchTarget
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun CourseGroupItem(
    courseId: String,
    courseName: String,
    classes: List<Course>,
    isExpanded: Boolean,
    isLoading: Boolean = false,
    isDetailsReady: Boolean = false,
    onExpandClick: () -> Unit,
    isMultiSelectMode: Boolean,
    selectedClassIds: Set<String>,
    onToggleSelection: (String, Boolean) -> Unit,
    onEnterMultiSelect: (String) -> Unit,
    onCourseSelect: (Course) -> Unit,
    onAutoGrab: (Course) -> Unit,
    onAddToQueue: (Course) -> Unit = {}, // 🔧 加入队列
    onSetTargetCourse: (Course) -> Unit = {}, // 🔧 设置为目标课程
    onSetFuzzyMatchTarget: ((String, String, String?, String?) -> Unit)? = null // 🔧 设置模糊匹配目标 (courseId, courseName, xkkzId, kklxdm)
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val firstCourse = classes.firstOrNull()
    val credits = firstCourse?.credit ?: "0.0"
    val hasSelected = classes.any { it.isSelected } 
    val statusText = if (hasSelected) "已选" else "未选"
    val statusColor = if (hasSelected) MaterialTheme.colorScheme.primary else Color.Gray
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .animateContentSize(), // 平滑动画
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp), // 更圆润的角
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min) // 确保高度一致
            ) {
                // 左侧色条
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .fillMaxHeight()
                        .background(if (hasSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha=0.3f))
                )
                
                // 主要内容
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                        .clickable { 
                            if (isDetailsReady) {
                                Log.d("CourseListScreen", "👆 点击展开: 允许 (ready=true)")
                                onExpandClick() 
                            } else {
                                Log.d("CourseListScreen", "🚫 点击展开: 拦截 (ready=false)")
                                android.widget.Toast.makeText(context, "正在加载选课参数，请稍候...", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "($courseId) $courseName",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "$credits 学分",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${classes.size} 个教学班",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        if (hasSelected) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "已选",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "已选课",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                // 🔧 监控按钮（模糊匹配模式）
                if (onSetFuzzyMatchTarget != null && !hasSelected) {
                    Surface(
                        onClick = { 
                            // 传递 courseId, courseName, xkkz_id, kklxdm
                            val xkkzId = firstCourse?._xkkz_id
                            val kklxdm = firstCourse?.kklxdm
                            onSetFuzzyMatchTarget(courseId, courseName, xkkzId, kklxdm)
                            android.widget.Toast.makeText(context, "🔍 已设为监控目标: $courseName", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.padding(end = 8.dp),
                        color = Color(0xFFFF9800),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "监控",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
                
                // 加载中显示进度条，否则显示箭头
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }
            
            // Divider (仅在展开时显示)
            if (isExpanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            }

            // Body (Expanded Content)
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    // 表头行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isMultiSelectMode) {
                            Spacer(modifier = Modifier.width(36.dp)) // Checkbox 占位 + padding
                        }
                        Text("教学班/教师", modifier = Modifier.weight(1.2f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                        Text("时间/地点", modifier = Modifier.weight(1.6f).padding(horizontal = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 1.dp)

                    classes.forEachIndexed { index, course ->
                        TeachingClassRow(
                            course = course,
                            isMultiSelectMode = isMultiSelectMode,
                            isChecked = course.classId in selectedClassIds,
                            onToggleSelection = { onToggleSelection(course.classId ?: "", it) },
                            onLongClick = { onEnterMultiSelect(course.classId ?: "") },
                            onClick = { onCourseSelect(course) },
                            onAutoGrab = { onAutoGrab(course) },
                            onAddToQueue = { onAddToQueue(course) },
                            onSetTargetCourse = { onSetTargetCourse(course) }
                        )
                        if (index < classes.size - 1) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TeachingClassRow(
    course: Course,
    isMultiSelectMode: Boolean,
    isChecked: Boolean,
    onToggleSelection: (Boolean) -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onAutoGrab: () -> Unit,
    onAddToQueue: () -> Unit = {}, // 🔧 加入队列
    onSetTargetCourse: () -> Unit = {} // 🔧 设为目标课程
) {
    val teacher = course.teacher.ifEmpty { "--" }
    val time = course.time.ifEmpty { "--" }
    val location = course.location.ifEmpty { "--" }
    
    val backgroundColor = if (isChecked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.White
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isMultiSelectMode) {
                        if (!course.isSelected) {
                             onToggleSelection(isChecked)
                        }
                    } else {
                        onClick()
                    }
                },
                onLongClick = {
                    if (!isMultiSelectMode && !course.isSelected) {
                        onLongClick()
                    } else if (!isMultiSelectMode) {
                        onAutoGrab()
                    }
                }
            )
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isMultiSelectMode) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = { onToggleSelection(isChecked) },
                enabled = !course.isSelected,
                colors = CheckboxDefaults.colors(checkedColor = PrimaryPurple),
                modifier = Modifier.padding(end = 16.dp).size(20.dp)
            )
        }

        // 1. 教学班/教师/容量列 (Weight 1.2) - 🔧 优化：显示教学班名称和教师名
        Column(modifier = Modifier.weight(1.2f)) {
            // 🔧 优先显示教学班名称 (jxbmc)，如果没有则显示教师名
            val displayName = course.jxbmc.ifEmpty { course.teacher.ifEmpty { "未知教师" } }
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 如果有教学班名称且教师不为空，额外显示教师名
            if (course.jxbmc.isNotEmpty() && course.teacher.isNotEmpty()) {
                Text(
                    text = course.teacher,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 容量进度条
            LinearProgressIndicator(
                progress = { if (course.capacity > 0) course.selected.toFloat() / course.capacity else 0f },
                modifier = Modifier.fillMaxWidth(0.9f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = if (course.selected >= course.capacity) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${course.selected}/${course.capacity}",
                style = MaterialTheme.typography.labelSmall,
                color = if (course.selected >= course.capacity) MaterialTheme.colorScheme.error else Color.Gray
            )
        }

        // 2. 时间地点列 (Weight 1.6)
        Column(modifier = Modifier.weight(1.6f).padding(horizontal = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(12.dp).padding(top = 2.dp), tint = Color.Gray)
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = time,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.DarkGray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.Gray)
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = location,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        // 🔧 加入队列按钮（未选课程显示）- 显眼的带文字按钮
        if (!course.isSelected && !isMultiSelectMode) {
            Spacer(modifier = Modifier.width(4.dp))
            Surface(
                onClick = onAddToQueue,
                color = Color(0xFF4CAF50), // 绿色背景
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "+ 队列",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            // 🔧 设为目标按钮
            Spacer(modifier = Modifier.width(4.dp))
            Surface(
                onClick = onSetTargetCourse,
                color = PrimaryPurple, // 紫色背景
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "🎯",
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }
        
        // 已选标记
        if (course.isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Text(
                    text = "已选",
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

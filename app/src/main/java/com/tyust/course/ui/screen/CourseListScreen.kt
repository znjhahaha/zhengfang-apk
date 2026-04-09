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
import com.tyust.course.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CourseListScreen(
    courses: List<Course>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onSearch: (String) -> Unit,
    onCourseSelect: (Course) -> Unit,
    onAutoGrab: (Course) -> Unit,
    onBatchSelect: (List<Course>) -> Unit = {},
    isBatchSelecting: Boolean = false,
    onFetchDetails: (List<Course>, (Boolean) -> Unit) -> Unit = { _, callback -> callback(true) },
    isPreloading: Boolean = false,
    preloadProgress: Float = 0f,
    preloadedGroupIds: Set<String> = emptySet(),
    isDetailsReady: Boolean = false,
    onAddToQueue: (Course) -> Unit = {},
    onSetTargetCourse: (Course) -> Unit = {},
    onSetFuzzyMatchTarget: ((String, String, String?, String?) -> Unit)? = null,
    isMultiSelectMode: Boolean = false,
    selectedClassIds: Set<String> = emptySet(),
    onToggleSelection: (String, Boolean) -> Unit = { _, _ -> },
    onEnterMultiSelect: (String) -> Unit = {}
) {
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val loadingGroups = remember { mutableStateMapOf<String, Boolean>() }
    val loadedGroups = remember { mutableStateMapOf<String, Boolean>() }

    val groupedCourses = remember(courses) {
        courses.groupBy { (it.courseId ?: "") to (it.name ?: "") }.toList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Neutral50) // Apple System Utility Background
    ) {
        if (isBatchSelecting) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = SystemBlue,
                trackColor = SystemBlueLight
            )
        }
        
        AnimatedVisibility(visible = isPreloading) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SystemBlueLight)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚡ 正在加载详情",
                        style = MaterialTheme.typography.bodySmall,
                        color = SystemBlueDark,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${(preloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = SystemBlueDark
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { preloadProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = SystemBlueDark,
                    trackColor = SystemBlueLight,
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
                        text = "没有找到课程\n尝试下拉刷新",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = Neutral300,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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
                            isDetailsReady = isDetailsReady,
                            onExpandClick = {
                                if (!isExpanded && loadedGroups[courseId] != true) {
                                    loadingGroups[courseId] = true
                                    onFetchDetails(classes) { success ->
                                        loadingGroups[courseId] = false
                                        if (success) {
                                            loadedGroups[courseId] = true
                                            expandedGroups[courseId] = true
                                        }
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
    onAddToQueue: (Course) -> Unit = {},
    onSetTargetCourse: (Course) -> Unit = {},
    onSetFuzzyMatchTarget: ((String, String, String?, String?) -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val firstCourse = classes.firstOrNull()
    val credits = firstCourse?.credit ?: "0.0"
    val hasSelected = classes.any { it.isSelected } 
    val statusText = if (hasSelected) "已选" else "开放"
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = androidx.compose.animation.core.spring(
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy
                )
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(16.dp), // iOS Inset Rounded Group
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Neutral200),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { 
                        if (isDetailsReady) {
                            onExpandClick() 
                        } else {
                            android.widget.Toast.makeText(context, "正在获取安全标识，请稍候...", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Vercel like indicator chip
                Box(
                    modifier = Modifier
                        .size(4.dp, 24.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (hasSelected) SystemBlue else Neutral200)
                )
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = courseName,
                        style = MaterialTheme.typography.titleMedium,
                        color = Neutral900,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = courseId,
                            style = MaterialTheme.typography.labelSmall,
                            color = Neutral500,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Surface(
                            color = Neutral100,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "$credits 学分",
                                style = MaterialTheme.typography.labelSmall,
                                color = Neutral700,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${classes.size} 模块",
                            style = MaterialTheme.typography.labelSmall,
                            color = Neutral500
                        )
                        if (hasSelected) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "已选",
                                tint = SystemBlue,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "已囊获",
                                style = MaterialTheme.typography.labelSmall,
                                color = SystemBlue,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                if (onSetFuzzyMatchTarget != null && !hasSelected) {
                    Surface(
                        onClick = { 
                            val xkkzId = firstCourse?._xkkz_id
                            val kklxdm = firstCourse?.kklxdm
                            onSetFuzzyMatchTarget(courseId, courseName, xkkzId, kklxdm)
                            android.widget.Toast.makeText(context, "🔍 追踪锁定: $courseName", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.padding(end = 8.dp),
                        color = SemanticWarning.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "追踪",
                            color = SemanticWarning,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
                
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = SystemBlue,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Neutral300
                    )
                }
            }
            
            // Expanded Content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    HorizontalDivider(color = Neutral100, thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Neutral50)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isMultiSelectMode) {
                            Spacer(modifier = Modifier.width(36.dp))
                        }
                        Text("建制班/讲师", modifier = Modifier.weight(1.2f), fontSize = 11.sp, color = Neutral500, fontWeight = FontWeight.SemiBold)
                        Text("安排", modifier = Modifier.weight(1.6f).padding(horizontal = 4.dp), fontSize = 11.sp, color = Neutral500, fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider(color = Neutral100, thickness = 1.dp)

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
                            HorizontalDivider(
                                color = Neutral100, 
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 16.dp)
                            )
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
    onAddToQueue: () -> Unit = {},
    onSetTargetCourse: () -> Unit = {}
) {
    val teacher = course.teacher.ifEmpty { "--" }
    val time = course.time.ifEmpty { "--" }
    val location = course.location.ifEmpty { "--" }
    
    val backgroundColor = if (isChecked) SystemBlueLight.copy(alpha = 0.5f) else Color.Transparent
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isMultiSelectMode) {
                        if (!course.isSelected) onToggleSelection(isChecked)
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isMultiSelectMode) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = { onToggleSelection(isChecked) },
                enabled = !course.isSelected,
                colors = CheckboxDefaults.colors(checkedColor = SystemBlue),
                modifier = Modifier.padding(end = 16.dp).size(20.dp)
            )
        }

        Column(modifier = Modifier.weight(1.2f)) {
            val displayName = course.jxbmc.ifEmpty { course.teacher.ifEmpty { "N/A" } }
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = Neutral900,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (course.jxbmc.isNotEmpty() && course.teacher.isNotEmpty()) {
                Text(
                    text = course.teacher,
                    style = MaterialTheme.typography.labelSmall,
                    color = Neutral500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (course.capacity > 0) course.selected.toFloat() / course.capacity else 0f },
                modifier = Modifier.fillMaxWidth(0.9f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = if (course.selected >= course.capacity) SemanticDanger else SystemBlue,
                trackColor = Neutral100,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${course.selected} / ${course.capacity} (已满/总量)",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = if (course.selected >= course.capacity) SemanticDanger else Neutral500
            )
        }

        Column(modifier = Modifier.weight(1.6f).padding(horizontal = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(12.dp).padding(top = 1.dp), tint = Neutral300)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = time,
                    style = MaterialTheme.typography.bodySmall,
                    color = Neutral700,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(12.dp), tint = Neutral300)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = location,
                    style = MaterialTheme.typography.labelSmall,
                    color = Neutral500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        if (!course.isSelected && !isMultiSelectMode) {
            Spacer(modifier = Modifier.width(2.dp))
            // AppStore GET Style Button
            Surface(
                onClick = onAddToQueue,
                color = SystemBlueLight, 
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.defaultMinSize(minWidth = 52.dp)
            ) {
                Text(
                    text = "获取",
                    color = SystemBlueDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Surface(
                onClick = onSetTargetCourse,
                color = Neutral100,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ListAlt,
                    contentDescription = null,
                    modifier = Modifier.padding(6.dp).size(16.dp),
                    tint = Neutral700
                )
            }
        }
        
        if (course.isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                color = SystemBlue,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(start = 4.dp).defaultMinSize(minWidth = 52.dp)
            ) {
                Text(
                    text = "已获",
                    color = Color.White,
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

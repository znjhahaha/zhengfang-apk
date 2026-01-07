package com.tyust.course.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.model.Course
import com.tyust.course.ui.theme.PrimaryPurple

/**
 * 抢课队列项状态
 */
enum class GrabQueueItemStatus {
    WAITING,    // 等待中
    GRABBING,   // 抢课中
    SUCCESS,    // 成功
    FAILED      // 失败
}

/**
 * 抢课队列屏幕组件
 */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.grabQueueItems(
    queue: List<Course>,
    currentIndex: Int,
    itemStatuses: Map<String, GrabQueueItemStatus>,  // 改用课程ID作为key，而不是索引
    isRunning: Boolean,
    isParallelMode: Boolean,
    queueVersion: Int = 0, // 🔧 显式接收版本号
    onMoveItem: (fromIndex: Int, toIndex: Int) -> Unit,
    onRemoveItem: (index: Int) -> Unit,
    onAddCourse: () -> Unit,
    onToggleMode: (index: Int) -> Unit = {}, // 🔧 切换精确/智能模式
    showMode: Boolean = true // 🔧 控制是否显示模式标签和切换
) {
    if (queue.isEmpty()) {
        item {
            GrabQueueEmptyState(onAddCourse)
        }
    } else {
        itemsIndexed(
            items = queue,
            // 🔧 包含 queueVersion 确保任何刷新都会引起重组
            // 🔧 使用对象唯一标识(UUID)作为key，彻底解决动画问题
            key = { _, course -> 
                course.uuid
            }
        ) { index, course ->
            // 显式提取模式值，确保 Compose 追踪此变量
            val currentExactMode = course.useExactMatch
            
            // 用课程名+老师+时间组合获取状态
            val courseKey = "${course.name ?: ""}_${course.teacher ?: ""}_${course.time ?: ""}"
            val status = itemStatuses[courseKey] ?: GrabQueueItemStatus.WAITING
            
            // 用 Box 包裹并应用动画
            Box(modifier = Modifier.animateItemPlacement()) {
                GrabQueueItem(
                    course = course,
                    index = index,
                    status = status,
                    isActive = index == currentIndex && isRunning,
                    enabled = !isRunning,
                    onRemove = { onRemoveItem(index) },
                    onMoveUp = if (index > 0 && !isRunning) {{ onMoveItem(index, index - 1) }} else null,
                    onMoveDown = if (index < queue.size - 1 && !isRunning) {{ onMoveItem(index, index + 1) }} else null,
                    onToggleMode = { onToggleMode(index) }, // 🔧 传递模式切换回调
                    showMode = showMode, // 🔧 控制显示模式
                    useExactMatch = currentExactMode // 🔧 显式传递模式，修复刷新问题
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        item {
            // 添加课程按钮
            OutlinedButton(
                onClick = onAddCourse,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                enabled = !isRunning
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("添加课程")
            }
        }
    }
}

@Composable
fun GrabQueueHeader(
    queueSize: Int,
    isParallelMode: Boolean,
    onParallelModeChange: (Boolean) -> Unit,
    onClearQueue: () -> Unit,
    isRunning: Boolean,
    showMode: Boolean = false, // 🔧 是否显示模式切换控制
    isExactModeGlobal: Boolean = true, // 🔧 全局模式状态 (从父组件传入)
    onToggleAllMode: ((Boolean) -> Unit)? = null, // 🔧 一键设置所有模式
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 顶部工具栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "抢课队列 ($queueSize)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            // 🔧 全局模式开关 (使用父组件传入的状态)
            if (onToggleAllMode != null && !isRunning && queueSize > 0) {
                GrabModeSwitch(
                    checked = isExactModeGlobal, // 🔧 使用父组件传入的状态
                    onCheckedChange = { onToggleAllMode(it) }
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            // 清空队列
            IconButton(
                onClick = onClearQueue,
                enabled = queueSize > 0 && !isRunning,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "清空队列")
            }
        }
    }
}

@Composable
fun GrabQueueEmptyState(onAddCourse: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Queue,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "队列为空",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "在课程列表长按课程添加到队列",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = onAddCourse) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("手动添加")
            }
        }
    }
}

/**
 * 队列项组件
 */
@Composable
fun GrabQueueItem(
    course: Course,
    index: Int,
    status: GrabQueueItemStatus,
    isActive: Boolean,
    enabled: Boolean,
    onRemove: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onToggleMode: () -> Unit = {},  // 🔧 模式切换回调
    showMode: Boolean = true, // 🔧 是否显示模式标签
    useExactMatch: Boolean = false, // 🔧 显式模式参数
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        when (status) {
            GrabQueueItemStatus.WAITING -> Color.White
            GrabQueueItemStatus.GRABBING -> Color(0xFFFFF3E0)  // 橙色背景
            GrabQueueItemStatus.SUCCESS -> Color(0xFFE8F5E9)   // 绿色背景
            GrabQueueItemStatus.FAILED -> Color(0xFFFFEBEE)    // 红色背景
        },
        label = "bgColor"
    )
    
    val elevation by animateDpAsState(
        if (isActive) 8.dp else 2.dp,
        label = "elevation"
    )
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 序号
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        when (status) {
                            GrabQueueItemStatus.SUCCESS -> Color(0xFF4CAF50)
                            GrabQueueItemStatus.FAILED -> Color(0xFFF44336)
                            GrabQueueItemStatus.GRABBING -> PrimaryPurple
                            else -> Color.LightGray
                        },
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                when (status) {
                    GrabQueueItemStatus.SUCCESS -> Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    GrabQueueItemStatus.FAILED -> Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    GrabQueueItemStatus.GRABBING -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    else -> Text(
                        text = "${index + 1}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // 课程信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = course.name ?: "未知课程",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${course.teacher ?: "未知教师"} | ${course.time ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 🔧 模式指示器 (当 showMode 为 true 时显示)
                if (showMode) {
                    val hasClassId = !course.classId.isNullOrEmpty()
                    val effectiveExactMode = hasClassId && useExactMatch // 🔧 使用显式参数
                    Text(
                        text = if (effectiveExactMode) "🔒 精确模式" else "🔄 智能模式",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (effectiveExactMode) PrimaryPurple else Color(0xFF4CAF50),
                        fontSize = 10.sp
                    )
                }
            }
            
            // 操作按钮
            if (enabled) {
                Row {
                    // 🔧 模式切换 (当 showMode 为 true 且有 classId 时显示)
                    if (showMode) {
                        val hasClassId = !course.classId.isNullOrEmpty()
                        if (hasClassId) {
                            IconButton(onClick = onToggleMode, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = if (useExactMatch) Icons.Default.Lock else Icons.Default.Refresh,
                                    contentDescription = if (useExactMatch) "精确模式" else "智能模式",
                                    tint = if (useExactMatch) PrimaryPurple else Color(0xFF4CAF50),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    // 上移
                    onMoveUp?.let {
                        IconButton(onClick = it, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = "上移",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    // 下移
                    onMoveDown?.let {
                        IconButton(onClick = it, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "下移",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    // 删除
                    IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = Color(0xFFF44336),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 🔧 仿图二样式的自定义模式切换开关
 */
@Composable
fun GrabModeSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackWidth = 84.dp
    val trackHeight = 32.dp
    val thumbSize = 26.dp
    val padding = 3.dp
    
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) padding else trackWidth - thumbSize - padding,
        label = "thumbOffset"
    )
    
    val trackColor by animateColorAsState(
        targetValue = if (checked) PrimaryPurple.copy(alpha = 0.9f) else Color(0xFF4CAF50).copy(alpha = 0.9f),
        label = "trackColor"
    )

    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(trackHeight / 2),
        color = trackColor,
        modifier = modifier.size(trackWidth, trackHeight),
        shadowElevation = 4.dp
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // 背景文字和图标
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!checked) {
                    Text("智能", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Default.Refresh, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                } else {
                    Icon(Icons.Default.Lock, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                    Text("精确", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            
            // 滑块
            Surface(
                shape = RoundedCornerShape(thumbSize / 2),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(padding)
                    .size(thumbSize)
                    .offset(x = thumbOffset),
                shadowElevation = 2.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (checked) Icons.Default.Lock else Icons.Default.Refresh,
                        contentDescription = null,
                        tint = if (checked) PrimaryPurple else Color(0xFF4CAF50),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

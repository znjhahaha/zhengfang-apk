package com.tyust.course.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.model.Course
import com.tyust.course.ui.theme.NeuPrimary
import com.tyust.course.ui.theme.SemanticSuccess
import com.tyust.course.ui.theme.SemanticDanger
import com.tyust.course.ui.theme.SemanticWarning
import com.tyust.course.ui.theme.Neutral500
import com.tyust.course.ui.theme.Neutral900
import com.tyust.course.ui.theme.SurfaceWhite
import com.tyust.course.ui.theme.Neutral100
import com.tyust.course.ui.theme.Neutral200
import com.tyust.course.ui.theme.NeuInsetBackground
import com.tyust.course.ui.theme.GlassBorderLight
import com.tyust.course.ui.theme.GlassBorderDark
import com.tyust.course.ui.theme.MotionSpring
import com.tyust.course.ui.theme.MotionSpecs
import com.tyust.course.ui.system.GlassRecipe
import com.tyust.course.ui.system.LiquidSwitch
import com.tyust.course.ui.system.neumorphicShadow
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
            Box(modifier = Modifier.animateItem()) {
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
                enabled = !isRunning,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, NeuPrimary.copy(alpha = 0.3f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = NeuPrimary
                )
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
            val clearEnabled = queueSize > 0 && !isRunning
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        if (clearEnabled) SemanticDanger.copy(alpha = 0.12f)
                        else NeuInsetBackground.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp)
                    )
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = clearEnabled, onClick = onClearQueue),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "清空队列",
                    tint = if (clearEnabled) SemanticDanger.copy(alpha = 0.85f)
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (queueSize > 1) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(
                    0.5.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "并行抢课",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Neutral900
                        )
                        Text(
                            text = "最多同时处理 2 门课程，仅限当前账号；切换账号前请先停止抢课。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Neutral500,
                            lineHeight = 17.sp
                        )
                    }
                    LiquidSwitch(
                        checked = isParallelMode,
                        onCheckedChange = onParallelModeChange,
                        enabled = !isRunning
                    )
                }
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
            .background(SurfaceWhite, RoundedCornerShape(12.dp))
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
    onToggleMode: () -> Unit = {},
    showMode: Boolean = true,
    useExactMatch: Boolean = false,
    modifier: Modifier = Modifier
) {
    val statusColor by animateColorAsState(
        when (status) {
            GrabQueueItemStatus.WAITING -> Color.LightGray
            GrabQueueItemStatus.GRABBING -> SemanticWarning
            GrabQueueItemStatus.SUCCESS -> SemanticSuccess
            GrabQueueItemStatus.FAILED -> SemanticDanger
        },
        label = "statusColor"
    )
    
    com.tyust.course.ui.system.SystemCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = if (isActive) NeuInsetBackground else MaterialTheme.colorScheme.surface,
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧状态圆点
            Box(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.7f))
            )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // 状态小标/Loading
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (status) {
                GrabQueueItemStatus.SUCCESS -> Icon(Icons.Default.Check, contentDescription = null, tint = SemanticSuccess, modifier = Modifier.size(16.dp))
                GrabQueueItemStatus.FAILED -> Icon(Icons.Default.Close, contentDescription = null, tint = SemanticDanger, modifier = Modifier.size(16.dp))
                GrabQueueItemStatus.GRABBING -> CircularProgressIndicator(modifier = Modifier.size(16.dp), color = SemanticWarning, strokeWidth = 2.dp)
                else -> Text("${index + 1}", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
            
            // 课程信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = course.name ?: "未知",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Neutral900,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${course.teacher ?: ""} | ${course.time ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Neutral500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (showMode) {
                    val hasClassId = !course.classId.isNullOrEmpty()
                    val effectiveExactMode = hasClassId && useExactMatch
                    Text(
                        text = if (effectiveExactMode) "🔒 精确模式" else "🔄 智能模式",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (effectiveExactMode) NeuPrimary else SemanticSuccess,
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
                                    tint = if (useExactMatch) NeuPrimary else SemanticSuccess,
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
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(
                                SemanticDanger.copy(alpha = 0.12f),
                                RoundedCornerShape(8.dp)
                            )
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onRemove),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = SemanticDanger.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 🔧 仿图二样式的自定义模式切换开关（物理优化版：对称滑动与层叠文字染色，新增触压弹性及图标微动效）
 */
@Composable
fun GrabModeSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackWidth = 88.dp
    val trackHeight = 32.dp
    val thumbWidth = 41.dp
    val thumbHeight = 26.dp
    val padding = 3.dp
    val trackShape = RoundedCornerShape(trackHeight / 2)
    val thumbShape = RoundedCornerShape(13.dp)

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = MotionSpring.gentle(),
        label = "scale"
    )

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbWidth - padding * 2 else 0.dp,
        animationSpec = MotionSpring.gentle(),
        label = "thumbOffset"
    )

    val trackColor by animateColorAsState(
        targetValue = if (checked) NeuPrimary.copy(alpha = 0.9f) else SemanticSuccess.copy(alpha = 0.9f),
        animationSpec = MotionSpecs.standard(),
        label = "trackColor"
    )

    val refreshRotation by animateFloatAsState(
        targetValue = if (!checked) 360f else 0f,
        animationSpec = MotionSpring.gentle(),
        label = "refreshRotation"
    )

    val lockScale by animateFloatAsState(
        targetValue = if (checked) 1.15f else 0.9f,
        animationSpec = MotionSpring.bounce(),
        label = "lockScale"
    )

    // 共享的文字/图标表层
    @Composable
    fun TextOverlay() {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val leftActive = !checked
            val leftColor by animateColorAsState(
                targetValue = if (leftActive) SemanticSuccess else Color.White.copy(alpha = 0.7f),
                animationSpec = MotionSpecs.standard(),
                label = "leftColor"
            )
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("智能", color = leftColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = leftColor,
                        modifier = Modifier.size(11.dp).graphicsLayer { rotationZ = refreshRotation }
                    )
                }
            }

            val rightActive = checked
            val rightColor by animateColorAsState(
                targetValue = if (rightActive) NeuPrimary else Color.White.copy(alpha = 0.7f),
                animationSpec = MotionSpecs.standard(),
                label = "rightColor"
            )
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = rightColor,
                        modifier = Modifier.size(11.dp).scale(lockScale)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("精确", color = rightColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // 简洁模式
    Surface(
            shape = trackShape,
            color = trackColor,
            modifier = modifier
                .size(trackWidth, trackHeight)
                .scale(scale)
                .border(
                    width = 0.5.dp,
                    color = Color.White.copy(alpha = 0.15f),
                    shape = trackShape
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) { onCheckedChange(!checked) },
            shadowElevation = 2.dp
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = thumbShape,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(padding)
                        .size(width = thumbWidth, height = thumbHeight)
                        .offset(x = thumbOffset)
                        .scale(if (isPressed) 0.94f else 1f)
                        .border(
                            width = 0.5.dp,
                            color = Color.Black.copy(alpha = 0.06f),
                            shape = thumbShape
                        ),
                    shadowElevation = 2.dp
                ) {}
                TextOverlay()
            }
        }
}

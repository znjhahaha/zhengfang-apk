package com.tyust.course.ui.screen

import androidx.compose.animation.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import com.tyust.course.ui.theme.Neutral100
import com.tyust.course.ui.theme.Neutral200
import com.tyust.course.ui.theme.NeuInsetBackground
import com.tyust.course.ui.theme.GlassBorderLight
import com.tyust.course.ui.theme.GlassBorderDark
import com.tyust.course.ui.system.GlassRecipe
import com.tyust.course.ui.system.LiquidSwitch
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemCompactSegmentedControl
import com.tyust.course.ui.system.SystemEmptyState
import com.tyust.course.ui.system.SystemSecondaryButton
import com.tyust.course.ui.system.glass.applyPressSquash
import com.tyust.course.ui.system.glass.glassChip
import com.tyust.course.ui.system.glass.rememberInteractiveOptics
import com.tyust.course.ui.system.rememberGlassAccessibilityMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
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
            SystemSecondaryButton(
                text = "添加课程",
                onClick = onAddCourse,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                enabled = !isRunning,
                leadingIcon = {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
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
            
            // 🔧 全局模式切换：与全 App 分段选择栏同语言（原自制开关已并入体系）
            if (onToggleAllMode != null && !isRunning && queueSize > 0) {
                SystemCompactSegmentedControl(
                    options = listOf("智能", "精确"),
                    selectedIndex = if (isExactModeGlobal) 1 else 0,
                    onSelect = { onToggleAllMode(it == 1) },
                    modifier = Modifier.width(124.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            // 清空队列
            val clearEnabled = queueSize > 0 && !isRunning
            GlassQueueIconButton(
                icon = Icons.Default.DeleteSweep,
                contentDescription = "清空队列",
                tint = SemanticDanger,
                enabled = clearEnabled,
                onClick = onClearQueue
            )
        }

        if (queueSize > 1) {
            SystemCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
    SystemCard(modifier = Modifier.fillMaxWidth()) {
        SystemEmptyState(
            title = "队列为空",
            message = "在课程列表长按课程添加到队列，或在此手动添加"
        ) {
            SystemSecondaryButton(
                text = "手动添加",
                onClick = onAddCourse,
                modifier = Modifier.fillMaxWidth(0.62f)
            )
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
    
    SystemCard(
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
                        text = if (effectiveExactMode) "精确模式" else "智能模式",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (effectiveExactMode) NeuPrimary else SemanticSuccess,
                        fontSize = 10.sp
                    )
                }
            }
            
            // 操作按钮
            if (enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 🔧 模式切换 (当 showMode 为 true 且有 classId 时显示)
                    if (showMode) {
                        val hasClassId = !course.classId.isNullOrEmpty()
                        if (hasClassId) {
                            GlassQueueIconButton(
                                icon = if (useExactMatch) Icons.Default.Lock else Icons.Default.Refresh,
                                contentDescription = if (useExactMatch) "切换为智能模式" else "切换为精确模式",
                                tint = if (useExactMatch) NeuPrimary else SemanticSuccess,
                                onClick = onToggleMode
                            )
                        }
                    }
                    // 上移
                    onMoveUp?.let {
                        GlassQueueIconButton(
                            icon = Icons.Default.KeyboardArrowUp,
                            contentDescription = "上移",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = it
                        )
                    }
                    // 下移
                    onMoveDown?.let {
                        GlassQueueIconButton(
                            icon = Icons.Default.KeyboardArrowDown,
                            contentDescription = "下移",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = it
                        )
                    }
                    // 删除
                    GlassQueueIconButton(
                        icon = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = SemanticDanger,
                        onClick = onRemove
                    )
                }
            }
        }
    }
}

/**
 * 队列行内的玻璃图标钮：边缘光玻璃（glassChip）而非 backdrop 折射——
 * 它们待在 SystemCard 半透面板【里面】，逐枚采样等于玻璃叠玻璃
 * （同 CourseFilterPanel 的 GlassFilterChip：玻璃感由面板承担）。
 * 交互能力与 adaptiveGlassChip 回退分支等价：按压挤压 + optics 手势。
 */
@Composable
private fun GlassQueueIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val optics = rememberInteractiveOptics()
    val interactive = enabled && !rememberGlassAccessibilityMode().reduceMotion
    Box(
        modifier = Modifier
            .size(32.dp)
            // 必须在 glassChip 之前：graphicsLayer 只变换它右侧的内容
            .graphicsLayer {
                if (!interactive) return@graphicsLayer
                applyPressSquash(
                    progress = optics.pressProgress,
                    depth = GlassRecipe.ChipFallbackPressDepth
                )
            }
            .glassChip(
                shape = RoundedCornerShape(10.dp),
                dimmed = !enabled,
                pressProgress = { optics.pressProgress }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .then(if (interactive) optics.gestureModifier else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(17.dp)
        )
    }
}

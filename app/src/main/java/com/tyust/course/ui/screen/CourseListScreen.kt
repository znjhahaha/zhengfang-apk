package com.tyust.course.ui.screen

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.tyust.course.ui.system.GlassCircleButton
import com.tyust.course.ui.system.LocalAppBackdrop
import com.tyust.course.ui.system.isBackdropSupported
import com.tyust.course.ui.system.GlassToaster
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.model.Course
import com.tyust.course.utils.CourseParser
import com.tyust.course.ui.system.GlassPullRefreshBox
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SystemActionButton
import com.tyust.course.ui.system.SystemCapacityIndicator
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemEmptyState
import com.tyust.course.ui.system.SystemLoadingState
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import com.tyust.course.ui.theme.NeuPrimary
import com.tyust.course.ui.theme.MotionEasing
import com.tyust.course.ui.theme.MotionSpecs
import com.tyust.course.ui.theme.MotionSpring
import com.tyust.course.ui.system.SystemDivider
import com.tyust.course.ui.theme.SemanticDanger
import com.tyust.course.ui.theme.SemanticSuccess
import com.tyust.course.ui.theme.SemanticWarning

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
@Suppress("UNUSED_PARAMETER")
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
    onEnterMultiSelect: (String) -> Unit = {},
    // 筛选相关
    showFilterPanel: Boolean = false,
    onToggleFilterPanel: () -> Unit = {},
    activeFilter: com.tyust.course.model.CourseFilter? = null,
    draftFilter: com.tyust.course.model.CourseFilter = com.tyust.course.model.CourseFilter(),
    onDraftFilterChange: (com.tyust.course.model.CourseFilter) -> Unit = {},
    onFilterApply: () -> Unit = {},
    onFilterClear: () -> Unit = {},
    isFilterLoading: Boolean = false,
    isFilterOptionsLoading: Boolean = false,
    filterOptionsMessage: String = "筛选条件加载失败，请下拉刷新重试",
    filterCategories: List<CourseParser.FilterCategory> = emptyList()
) {
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val loadingGroups = remember { mutableStateMapOf<String, Boolean>() }
    val loadedGroups = remember { mutableStateMapOf<String, Boolean>() }

    val groupedCourses = remember(courses) {
        courses.groupBy { (it.courseId ?: "") to (it.name ?: "") }.toList()
    }

    // 课程列表的捕获层。筛选面板是列表的【兄弟】节点、不在这一层内，
    // 所以采样「壁纸 + 这一层」不构成自采样，面板边缘能真实折射课程卡片。
    // 注意不能取 LocalModalBackdrop：那一层的捕获范围包含整个页面内容（面板也在里面），
    // 取它等于自采样 -> RenderThread 死循环 -> native SIGSEGV。
    val wallpaperBackdrop = LocalAppBackdrop.current
    val listBackdrop = if (wallpaperBackdrop != null && isBackdropSupported()) {
        rememberLayerBackdrop()
    } else {
        null
    }
    val panelBackdrop = if (wallpaperBackdrop != null && listBackdrop != null) {
        rememberCombinedBackdrop(wallpaperBackdrop, listBackdrop)
    } else {
        null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        if (isBatchSelecting) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = NeuPrimary,
                trackColor = MaterialTheme.colorScheme.secondaryContainer
            )
        }

        AnimatedVisibility(visible = isPreloading) {
            PreloadBanner(
                preloadProgress = preloadProgress,
                readyCount = preloadedGroupIds.size
            )
        }

        // 筛选入口在顶栏（CourseListRoute 的芯片组），这里不再有那条居中把手带。
        // 已激活筛选标签栏
        if (activeFilter != null && !activeFilter.isEmpty()) {
            ActiveFilterBar(
                filter = activeFilter,
                filterCategories = filterCategories,
                onClear = onFilterClear,
                isLoading = isFilterLoading
            )
        }

        // 列表与筛选浮层同属一个 Box：面板浮在列表之上，不占布局高度，
        // 于是展开筛选不再把列表整体顶下去、列表滚动位置也不会被打断。
        Box(modifier = Modifier.fillMaxSize()) {
            GlassPullRefreshBox(
                isRefreshing = isLoading,
                onRefresh = onRefresh,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (listBackdrop != null) Modifier.layerBackdrop(listBackdrop) else Modifier
                    )
            ) {
                when {
                    isLoading && courses.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            SystemLoadingState(text = "正在加载课程列表…")
                        }
                    }

                    courses.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            SystemEmptyState(
                                title = "暂无可选课程",
                                message = "下拉刷新获取最新数据"
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = PagePadding,
                                end = PagePadding,
                                top = PagePadding,
                                bottom = com.tyust.course.ui.system.LocalAppOverlayBottomInset.current + 24.dp
                            ),
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

            // 点面板外关闭 + 筛选浮层。抽成 BoxScope 扩展是有原因的：
            // ColumnScope 与 BoxScope 都带 @LayoutScopeMarker，在 Column 里再嵌 Box 时
            // AnimatedVisibility 会解析到 ColumnScope 那个重载、而该 receiver 已被
            // DslMarker 屏蔽，直接编译不过。函数里只有 BoxScope，解析到顶层重载。
            FilterPanelOverlay(
                visible = showFilterPanel,
                draftFilter = draftFilter,
                onDraftFilterChange = onDraftFilterChange,
                onFilterApply = onFilterApply,
                onFilterClear = onFilterClear,
                filterCategories = filterCategories,
                isFilterOptionsLoading = isFilterOptionsLoading,
                filterOptionsMessage = filterOptionsMessage,
                panelBackdrop = panelBackdrop,
                onDismiss = onToggleFilterPanel
            )
        }
    }
}

@Composable
private fun BoxScope.FilterPanelOverlay(
    visible: Boolean,
    draftFilter: com.tyust.course.model.CourseFilter,
    onDraftFilterChange: (com.tyust.course.model.CourseFilter) -> Unit,
    onFilterApply: () -> Unit,
    onFilterClear: () -> Unit,
    filterCategories: List<CourseParser.FilterCategory>,
    isFilterOptionsLoading: Boolean,
    filterOptionsMessage: String,
    panelBackdrop: com.kyant.backdrop.Backdrop?,
    onDismiss: () -> Unit
) {
    // 极轻压暗让列表读起来被推远——不做重压暗：把手和顶部区域在这个 Box 之外，
    // 盖不到，深压暗会在分界处露出一条硬边。
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(180)),
        exit = fadeOut(animationSpec = tween(140))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.10f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )
    }

    // 从顶栏那枚筛选钮"长出来"：以按钮为原点缩放 + 淡入。
    //
    // 原点写常量而不是 onGloballyPositioned 实测：面板左右各内缩 PagePadding(20dp)，
    // 筛选钮是顶栏最右一枚（中心约在 W-21dp），算出来的 originX≈0.99；
    // 0.96 已经落在按钮上，省掉一条跨 Route/Screen 的位置状态。
    //
    // 刻意不再用 expandVertically：那是"从无到有长高"，与"从哪个按钮打开的"无关。
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = fadeIn(animationSpec = tween(140)) +
            scaleIn(
                initialScale = 0.86f,
                transformOrigin = TransformOrigin(0.96f, 0f),
                animationSpec = MotionSpring.liquidSettle()
            ),
        exit = fadeOut(animationSpec = tween(120, easing = MotionEasing.Accelerate)) +
            scaleOut(
                targetScale = 0.90f,
                transformOrigin = TransformOrigin(0.96f, 0f),
                animationSpec = tween(160, easing = MotionEasing.Accelerate)
            )
    ) {
        CourseFilterPanel(
            filter = draftFilter,
            onFilterChange = onDraftFilterChange,
            onApply = onFilterApply,
            onClear = onFilterClear,
            filterCategories = filterCategories,
            isLoading = isFilterOptionsLoading,
            emptyMessage = filterOptionsMessage,
            sampleBackdrop = panelBackdrop,
            onDismiss = onDismiss,
            modifier = Modifier.padding(horizontal = PagePadding, vertical = 4.dp)
        )
    }
}

@Composable
private fun PreloadBanner(
    preloadProgress: Float,
    readyCount: Int
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = PagePadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "正在预加载课程详情",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${(preloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            LinearProgressIndicator(
                progress = { preloadProgress },
                modifier = Modifier.fillMaxWidth(),
                color = NeuPrimary,
                trackColor = MaterialTheme.colorScheme.surface
            )
            Text(
                text = if (readyCount > 0) "已准备 $readyCount 个课程分组" else "正在获取可展开详情所需标识",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
            )
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
    val context = LocalContext.current
    val firstCourse = classes.firstOrNull()
    val credits = firstCourse?.credit ?: "0.0"
    val hasSelected = classes.any { it.isSelected }
    val hasAvailableSeat = classes.any { !it.isSelected && (it.capacity <= 0 || it.selected < it.capacity) }
    val cardBorderColor by animateColorAsState(
        targetValue = when {
            hasSelected -> SemanticSuccess.copy(alpha = 0.35f)
            hasAvailableSeat -> NeuPrimary.copy(alpha = 0.25f)
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = MotionSpecs.standard(),
        label = "courseGroupBorder"
    )
    // 满员组不再整卡灰底（会在玻璃页面里形成"死块"），状态由"紧张"徽章表达
    SystemCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = cardBorderColor,
        onClick = {
            if (isDetailsReady) {
                onExpandClick()
            } else {
                GlassToaster.show("正在准备课程数据…")
            }
        },
        contentPadding = PaddingValues(0.dp)
    ) {
        Column {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = courseName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SystemStatusBadge(
                                text = when {
                                    hasSelected -> "已选"
                                    hasAvailableSeat -> "可选"
                                    else -> "紧张"
                                },
                                tone = when {
                                    hasSelected -> SystemTone.Success
                                    hasAvailableSeat -> SystemTone.Info
                                    else -> SystemTone.Warning
                                }
                            )
                            SystemStatusBadge(
                                text = "$credits 学分",
                                tone = SystemTone.Neutral
                            )
                            SystemStatusBadge(
                                text = "${classes.size} 个教学班",
                                tone = SystemTone.Neutral
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (onSetFuzzyMatchTarget != null && !hasSelected) {
                            SystemActionButton(
                                text = "监控",
                                onClick = {
                                    val xkkzId = firstCourse?._xkkz_id
                                    val kklxdm = firstCourse?.kklxdm
                                    onSetFuzzyMatchTarget(courseId, courseName, xkkzId, kklxdm)
                                    GlassToaster.show("已设为监控目标: $courseName")
                                }
                            )
                        }

                        TextButton(onClick = onExpandClick) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = NeuPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                val arrowRotation by animateFloatAsState(
                                    targetValue = if (isExpanded) 180f else 0f,
                                    animationSpec = MotionSpecs.emphasized(),
                                    label = "arrowRotation"
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .rotate(arrowRotation)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isExpanded) "收起" else "展开")
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = MotionSpecs.emphasized()) + fadeIn(animationSpec = MotionSpecs.emphasized()),
                exit = shrinkVertically(animationSpec = MotionSpecs.emphasized()) + fadeOut(animationSpec = MotionSpecs.emphasized())
            ) {
                Column {
                    SystemDivider()

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
                            SystemDivider(
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
    val teacher = course.teacher.ifEmpty { "未提供教师" }
    val time = course.time.ifEmpty { "未提供时间" }
    val location = course.location.ifEmpty { "未提供地点" }
    val displayName = course.jxbmc.ifEmpty { course.teacher.ifEmpty { "未命名教学班" } }
    val isSelectedRow = course.isSelected
    val rowBackgroundColor = when {
        isSelectedRow -> SemanticSuccess
        isChecked -> MaterialTheme.colorScheme.secondaryContainer
        else -> com.tyust.course.ui.theme.NeuSurface
    }
    val backgroundAlpha by animateFloatAsState(
        targetValue = when {
            isSelectedRow -> 0.12f
            isChecked -> 0.64f
            else -> 1f
        },
        animationSpec = MotionSpecs.standard(),
        label = "classRowBackgroundAlpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (isSelectedRow) SemanticSuccess.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.3f), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelectedRow && backgroundAlpha > 0f) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            SemanticSuccess.copy(alpha = backgroundAlpha),
                            SemanticSuccess.copy(alpha = backgroundAlpha * 0.1f)
                        )
                    )
                } else {
                    androidx.compose.ui.graphics.SolidColor(rowBackgroundColor.copy(alpha = backgroundAlpha))
                }
            )
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isMultiSelectMode) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = { onToggleSelection(isChecked) },
                enabled = !course.isSelected,
                colors = CheckboxDefaults.colors(checkedColor = NeuPrimary),
                modifier = Modifier.padding(end = 14.dp)
            )
        } else if (isSelectedRow) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = SemanticSuccess,
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(18.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1.2f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = teacher,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 紧凑容量显示：内联文字替代独立进度条
            val ratio = if (course.capacity > 0) course.selected.toFloat() / course.capacity else 0f
            val capacityText = if (course.capacity > 0) "${course.selected}/${course.capacity}" else "${course.selected}/--"
            val capacityColor = when {
                course.capacity > 0 && course.selected >= course.capacity -> SemanticDanger
                ratio >= 0.85f -> SemanticWarning
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(12.dp), tint = capacityColor)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = capacityText,
                    style = MaterialTheme.typography.labelSmall,
                    color = capacityColor,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1.1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CourseDetailLine(
                icon = Icons.Default.Schedule,
                text = time
            )
            CourseDetailLine(
                icon = Icons.Default.Place,
                text = location
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        if (!course.isSelected && !isMultiSelectMode) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SystemActionButton(
                    text = "入队",
                    onClick = onAddToQueue,
                    primary = true
                )
                SystemActionButton(
                    text = "目标",
                    onClick = onSetTargetCourse,
                    icon = Icons.Default.Flag
                )
            }
        }

        if (course.isSelected) {
            Spacer(modifier = Modifier.width(8.dp))
            SystemStatusBadge(
                text = "已选",
                tone = SystemTone.Success
            )
        }
    }
}

@Composable
private fun CourseDetailLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ActiveFilterBar(
    filter: com.tyust.course.model.CourseFilter,
    filterCategories: List<CourseParser.FilterCategory>,
    onClear: () -> Unit,
    isLoading: Boolean
) {
    val tags = remember(filter, filterCategories) { filter.toDynamicDisplayTags(filterCategories) }
    // 原先是一条 surfaceVariant×0.5 的实心横条，在玻璃页面里是一块死区。
    // 标签自己就是玻璃芯片，横条不需要底。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = PagePadding, end = PagePadding - 6.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = NeuPrimary
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            tags.forEach { tag ->
                // 与面板里的筛选芯片同一枚组件，只是更小且不可点
                GlassFilterChip(label = tag, selected = true, compact = true)
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        GlassCircleButton(
            onClick = onClear,
            icon = Icons.Default.Close,
            contentDescription = "清除筛选",
            size = 28.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 把筛选条件翻成人读的标签。
 *
 * internal 而不是 private：顶栏那枚筛选钮的角标数量也读它（`CourseListRoute`），
 * 两处必须是同一个来源，否则角标和标签栏会各说一套。
 */
internal fun com.tyust.course.model.CourseFilter.toDynamicDisplayTags(
    filterCategories: List<CourseParser.FilterCategory>
): List<String> {
    val labelByParamAndKey = filterCategories.associate { category ->
        category.paramName to category.options.associate { option -> option.key to option.label }
    }

    fun MutableList<String>.appendLabels(paramName: String, values: List<String>?) {
        val labels = labelByParamAndKey[paramName].orEmpty()
        values.orEmpty()
            .filter { it.isNotBlank() }
            .forEach { key -> add(labels[key] ?: key) }
    }

    return buildList {
        appendLabels("kkbm_id_list", kkbmIdList)
        appendLabels("njdm_id_list", njdmIdList)
        appendLabels("jg_id_list", jgIdList)
        appendLabels("zyh_id_list", zyhIdList)
        appendLabels("kclb_id_list", kclbIdList)
        appendLabels("kcxzdm_list", kcxzdmList)
        appendLabels("kcgs_list", kcgsList)
        appendLabels("jxms_list", jxmsList)
        appendLabels("sksj_list", sksjList)
        appendLabels("skjc_list", skjcList)
        appendLabels("cxbj_list", cxbjList)
        appendLabels("yl_list", ylList)
        appendLabels("jxbmc_list", jxbmcList)
        if (!searchInput.isNullOrBlank()) add("\"$searchInput\"")
    }
}

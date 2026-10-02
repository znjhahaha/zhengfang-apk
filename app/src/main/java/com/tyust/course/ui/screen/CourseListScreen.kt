package com.tyust.course.ui.screen

import com.tyust.course.ui.theme.ModuleMotion

import androidx.compose.foundation.interaction.MutableInteractionSource
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.tyust.course.ui.system.GlassCircleButton
import com.tyust.course.ui.system.LocalAppBackdrop
import com.tyust.course.ui.system.isBackdropSupported
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Group
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.collectLatest
import com.tyust.course.academic.CourseBrowserState
import com.tyust.course.academic.CourseDetails
import com.tyust.course.ui.system.SystemSecondaryButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tyust.course.model.Course
import com.tyust.course.academic.catalogGroupKey
import com.tyust.course.academic.catalogSelectionKey
import com.tyust.course.utils.CourseParser
import com.tyust.course.ui.system.GlassPullRefreshBox
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemEmptyState
import com.tyust.course.ui.system.SystemLoadingState
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import com.tyust.course.ui.theme.NeuPrimary
import com.tyust.course.ui.theme.MotionEasing
import com.tyust.course.ui.theme.MotionSpecs
import com.tyust.course.ui.theme.MotionSpring
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
    filterAnchor: Offset? = null,
    onToggleFilterPanel: () -> Unit = {},
    activeFilter: com.tyust.course.model.CourseFilter? = null,
    draftFilter: com.tyust.course.model.CourseFilter = com.tyust.course.model.CourseFilter(),
    onDraftFilterChange: (com.tyust.course.model.CourseFilter) -> Unit = {},
    onFilterApply: () -> Unit = {},
    onFilterClear: () -> Unit = {},
    isFilterLoading: Boolean = false,
    isFilterOptionsLoading: Boolean = false,
    filterOptionsMessage: String = "筛选条件加载失败，请下拉刷新重试",
    filterCategories: List<CourseParser.FilterCategory> = emptyList(),
    showTargetAction: Boolean = true,
    browserState: CourseBrowserState? = null,
    onVisibleGroups: (Set<String>) -> Unit = {},
    onToggleGroup: (String) -> Unit = {},
    onExpandAll: (Set<String>) -> Unit = {},
    onCollapseAll: () -> Unit = {},
    onStopPreload: () -> Unit = {},
    onRetryPreload: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    localFilterNotice: Boolean = false
) {
    val reduceMotion = com.tyust.course.ui.system.rememberGlassAccessibilityMode().reduceMotion
    val listState = rememberLazyListState()
    var filterContainerBounds by remember { mutableStateOf(Rect.Zero) }
    val expandedGroups = rememberSaveable(saver = mapSaver(
        save = { it.toMap() },
        restore = { saved -> mutableStateMapOf<String, Boolean>().apply {
            saved.forEach { (key, value) -> put(key, value as Boolean) }
        } }
    )) { mutableStateMapOf<String, Boolean>() }
    val loadingGroups = remember { mutableStateMapOf<String, Boolean>() }
    val loadedGroups = remember { mutableStateMapOf<String, Boolean>() }

    val groupedCourses = remember(courses) {
        courses.groupBy { it.catalogGroupKey() to it.name.orEmpty() }.toList()
    }

    val currentGroups by rememberUpdatedState(groupedCourses)
    val reportVisible by rememberUpdatedState(onVisibleGroups)
    LaunchedEffect(listState, browserState != null) {
        if (browserState == null) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress to listState.layoutInfo.visibleItemsInfo.map { it.key } }
            .collectLatest { (scrolling, keys) ->
                reportVisible(emptySet())
                if (!scrolling) {
                    kotlinx.coroutines.delay(150)
                    val available = currentGroups.associate { (key, _) -> (key.first.length.toString() + ":" + key.first + key.second) to key.first }
                    reportVisible(keys.mapNotNull { available[it] }.toSet())
                }
            }
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

        AnimatedVisibility(visible = isPreloading, enter = ModuleMotion.expand(reduceMotion), exit = ModuleMotion.collapse(reduceMotion)) {
            PreloadBanner(
                preloadProgress = preloadProgress,
                readyCount = preloadedGroupIds.size
            )
        }

        if (browserState != null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = PagePadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SystemSecondaryButton(text = "展开全部", enabled = courses.isNotEmpty() && !isLoading,
                    onClick = { onExpandAll(groupedCourses.map { it.first.first }.toSet()) })
                SystemSecondaryButton(text = "收起全部", onClick = onCollapseAll)
                if (browserState.bulkRunning) SystemSecondaryButton(text = "停止加载", onClick = onStopPreload)
                if (browserState.bulk.any { browserState.details[it]?.phase == "failed" }) SystemSecondaryButton(text = "重试失败项", onClick = onRetryPreload)
            }
            if (browserState.bulk.isNotEmpty()) {
                val complete = browserState.bulk.count { browserState.details[it]?.phase in setOf("ready", "failed") }
                val failed = browserState.bulk.count { browserState.details[it]?.phase == "failed" }
                Text("已处理 $complete/${browserState.bulk.size} 门 · 失败 $failed 门", modifier = Modifier.padding(horizontal = PagePadding), style = MaterialTheme.typography.bodySmall)
            }
            if (localFilterNotice) Text("本地筛选仅作用于已加载课程", modifier = Modifier.padding(horizontal = PagePadding), style = MaterialTheme.typography.bodySmall)
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
        Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { filterContainerBounds = it.boundsInWindow() }) {
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

                    courses.isEmpty() && browserState == null -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            SystemEmptyState(
                                title = "暂无可选课程",
                                message = "下拉刷新获取最新数据"
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = PagePadding,
                                end = PagePadding,
                                top = PagePadding,
                                bottom = com.tyust.course.ui.system.LocalAppOverlayBottomInset.current + 24.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(
                                groupedCourses,
                                key = { (group, _) -> group.first.length.toString() + ":" + group.first + group.second },
                                contentType = { "course-group" }
                            ) { (key, classes) ->
                                val courseId = classes.firstOrNull()?.courseId.orEmpty()
                                val groupId = key.first
                                val courseName = key.second
                                val isExpanded = browserState?.expanded?.contains(groupId) ?: (expandedGroups[groupId] == true)

                                CourseGroupItem(
                                    modifier = if (reduceMotion) Modifier else Modifier.animateItem(
                                        fadeInSpec = null,
                                        placementSpec = MotionSpring.snappy(),
                                        fadeOutSpec = tween(ModuleMotion.ExitMillis)
                                    ),
                                    courseId = courseId,
                                    courseName = courseName,
                                    classes = classes,
                                    isExpanded = isExpanded,
                                    isLoading = if (browserState != null) browserState.details[groupId]?.phase == "loading" else loadingGroups[groupId] == true,
                                    detail = if (browserState != null) browserState.details[groupId] ?: CourseDetails() else null,
                                    isDetailsReady = isDetailsReady,
                                    onExpandClick = {
                                        if (browserState != null) {
                                            onToggleGroup(groupId)
                                        } else if (!isExpanded && loadedGroups[groupId] != true) {
                                            loadingGroups[groupId] = true
                                            onFetchDetails(classes) { success ->
                                                loadingGroups[groupId] = false
                                                if (success) {
                                                    loadedGroups[groupId] = true
                                                    expandedGroups[groupId] = true
                                                }
                                            }
                                        } else {
                                            expandedGroups[groupId] = !isExpanded
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
                                    showTargetAction = showTargetAction,
                                    onSetFuzzyMatchTarget = onSetFuzzyMatchTarget
                                )
                            }
                            if (browserState != null) item(key = "course-pagination-footer") {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("已加载 ${browserState.courses.map { it.catalogGroupKey() }.distinct().size} 门课程", style = MaterialTheme.typography.bodySmall)
                                    if (courses.isEmpty()) Text("已加载课程中暂无匹配项")
                                    if (browserState.pageError.isNotBlank()) Text(browserState.pageError, color = MaterialTheme.colorScheme.error)
                                    if (browserState.hasMore) SystemSecondaryButton(
                                        text = if (browserState.loadingMore) "正在加载…" else if (browserState.pageError.isNotBlank()) "重试下一页" else "加载更多",
                                        enabled = !browserState.loadingMore && !isLoading, onClick = onLoadMore)
                                    else if (!isLoading && browserState.pageError.isBlank()) Text("已加载全部返回课程", style = MaterialTheme.typography.bodySmall)
                                }
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
                anchor = filterAnchor,
                containerBounds = filterContainerBounds,
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
    anchor: Offset?,
    containerBounds: Rect,
    onDismiss: () -> Unit
) {
    val reduced = com.tyust.course.ui.system.rememberGlassAccessibilityMode().reduceMotion
    val padding = with(LocalDensity.current) { PagePadding.toPx() }
    val anchorX = anchor?.let { ((it.x - containerBounds.left - padding) / (containerBounds.width - 2 * padding).coerceAtLeast(1f)).coerceIn(0f, 1f) } ?: 1f
    val origin = TransformOrigin(anchorX, 0f)
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

    // The untransformed list bounds and measured trigger center define the reveal anchor.
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = fadeIn(animationSpec = tween(if (reduced) 0 else com.tyust.course.ui.theme.MotionProfile.IconMillis)) +
            scaleIn(
                initialScale = if (reduced) 1f else 0.96f,
                transformOrigin = origin,
                animationSpec = com.tyust.course.ui.theme.MotionProfile.hierarchySpring()
            ),
        exit = fadeOut(animationSpec = tween(120, easing = MotionEasing.Accelerate)) +
            scaleOut(
                targetScale = if (reduced) 1f else 0.97f,
                transformOrigin = origin,
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

@OptIn(ExperimentalLayoutApi::class)
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
    onSetFuzzyMatchTarget: ((String, String, String?, String?) -> Unit)? = null,
    showTargetAction: Boolean = true,
    modifier: Modifier = Modifier,
    detail: CourseDetails? = null
) {
    val firstCourse = classes.firstOrNull()
    val reduced = com.tyust.course.ui.system.rememberGlassAccessibilityMode().reduceMotion
    val credits = firstCourse?.credit ?: "0.0"
    val hasSelected = classes.any { it.isSelected }
    val hasUnknownCapacity = classes.any { it.completeParams["academic_capacity_known"] == "false" || it.completeParams["academic_selected_known"] == "false" }
    val hasAvailableSeat = (detail == null || detail.phase == "ready" && detail.count != 0) && classes.any { !it.isSelected && it.completeParams["academic_capacity_known"] != "false" &&
        it.completeParams["academic_selected_known"] != "false" && (it.selected < it.capacity) }
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
        modifier = modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = cardBorderColor,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isDetailsReady && (!isLoading || detail != null), onClick = onExpandClick)
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
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (detail != null) {
                                val quota = when {
                                    detail.phase == "failed" -> "加载失败 · 点击重试"
                                    detail.phase == "loading" -> "名额正在加载"
                                    detail.phase != "ready" -> "名额待加载"
                                    detail.count == 0 -> "暂无教学班"
                                    hasUnknownCapacity -> "部分教学班未公布名额"
                                    else -> "余量合计 ${classes.sumOf { (it.capacity - it.selected).coerceAtLeast(0) }}（查询时）"
                                }
                                SystemStatusBadge(text = quota, tone = if (detail.phase == "failed") SystemTone.Warning else SystemTone.Neutral)
                            } else if (hasSelected || !hasAvailableSeat) {
                                SystemStatusBadge(
                                    text = when { hasSelected -> "已选"; hasUnknownCapacity -> "名额未公布"; else -> "紧张" },
                                    tone = when { hasSelected -> SystemTone.Success; hasUnknownCapacity -> SystemTone.Neutral; else -> SystemTone.Warning }
                                )
                            }
                            Text(
                                text = "$credits 学分 · " + (if (detail == null) "${classes.size} 个教学班" else
                                    (if (detail.phase == "ready") detail.count else firstCourse?.completeParams?.get("academic_section_count")?.toIntOrNull())
                                        ?.let { "$it 个教学班" } ?: "教学班待加载"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onSetFuzzyMatchTarget != null && !hasSelected) {
                            com.tyust.course.ui.system.SystemIconButton(
                                icon = Icons.Default.MyLocation,
                                contentDescription = "设为监控目标",
                                onClick = {
                                    val xkkzId = firstCourse?.completeParams?.get("academic_scope_id") ?: firstCourse?._xkkz_id
                                    val kklxdm = firstCourse?.kklxdm
                                    onSetFuzzyMatchTarget(courseId, courseName, xkkzId, kklxdm)
                                }
                            )
                        }

                        IconButton(onClick = onExpandClick, enabled = isDetailsReady && (!isLoading || detail != null)) {
                            com.tyust.course.ui.system.AnimatedLineIcon(
                                com.tyust.course.ui.system.AnimatedIconSpec.Expand, Modifier.size(20.dp),
                                state = when {
                                    isLoading -> com.tyust.course.ui.system.IconVisualState.Running
                                    isExpanded -> com.tyust.course.ui.system.IconVisualState.Expanded
                                    else -> com.tyust.course.ui.system.IconVisualState.Idle
                                },
                                description = if (isLoading) "加载教学班" else if (isExpanded) "收起教学班" else "展开教学班",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = if (reduced) androidx.compose.animation.EnterTransition.None else expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = spring(
                        dampingRatio = 0.86f,
                        stiffness = 420f,
                        visibilityThreshold = IntSize.VisibilityThreshold
                    )
                ) + fadeIn(animationSpec = tween(170)) + scaleIn(
                    initialScale = 0.97f,
                    transformOrigin = TransformOrigin(0.5f, 0f),
                    animationSpec = MotionSpring.liquidSettle()
                ),
                exit = ModuleMotion.collapse(reduced)
            ) {
                Column(
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    if (detail != null && detail.phase != "ready") {
                        Text(if (detail.error.isNotBlank()) detail.error else "正在准备教学班…", Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    (if (detail == null || detail.phase == "ready" && detail.count != 0) classes else emptyList()).forEachIndexed { index, course ->
                        TeachingClassRow(
                            course = course,
                            isMultiSelectMode = isMultiSelectMode,
                            isChecked = course.catalogSelectionKey() in selectedClassIds,
                            onToggleSelection = { onToggleSelection(course.catalogSelectionKey(), it) },
                            onLongClick = { onEnterMultiSelect(course.catalogSelectionKey()) },
                            onClick = { onCourseSelect(course) },
                            onAutoGrab = { onAutoGrab(course) },
                            onAddToQueue = { onAddToQueue(course) },
                            onSetTargetCourse = { onSetTargetCourse(course) },
                            showTargetAction = showTargetAction
                        )
                        if (index < classes.size - 1) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
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
    onSetTargetCourse: () -> Unit = {},
    showTargetAction: Boolean = true
) {
    val teacher = course.teacher.ifBlank { "未提供教师" }
    val displayName = course.jxbmc.ifBlank { teacher }
    val capacityColor = when {
        course.completeParams["academic_capacity_known"] == "false" || course.completeParams["academic_selected_known"] == "false" -> MaterialTheme.colorScheme.onSurfaceVariant
        course.capacity > 0 && course.selected >= course.capacity -> SemanticDanger
        course.capacity > 0 && course.selected.toFloat() / course.capacity >= 0.85f -> SemanticWarning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val highlight by animateColorAsState(
        targetValue = when {
            course.isSelected -> SemanticSuccess.copy(alpha = 0.08f)
            isChecked -> NeuPrimary.copy(alpha = 0.10f)
            else -> Color.Transparent
        },
        label = "teachingClassSelection"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(highlight)
            .combinedClickable(
                onClick = {
                    if (isMultiSelectMode) {
                        if (!course.isSelected) onToggleSelection(isChecked)
                    } else onClick()
                },
                onLongClick = {
                    if (!isMultiSelectMode && !course.isSelected) onLongClick()
                }
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isMultiSelectMode) Checkbox(
                checked = isChecked,
                onCheckedChange = { onToggleSelection(isChecked) },
                enabled = !course.isSelected,
                colors = CheckboxDefaults.colors(checkedColor = NeuPrimary)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (displayName != teacher) CourseDetailLine(Icons.Default.Person, teacher)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(14.dp), tint = capacityColor)
                    Text(
                        (if (course.completeParams["academic_selected_known"] == "false") "--" else course.selected.toString()) + " / " +
                            (if (course.completeParams["academic_capacity_known"] == "true" || course.capacity > 0) course.capacity.toString() else "--"),
                        style = MaterialTheme.typography.labelMedium,
                        color = capacityColor
                    )
                }
            }
            if (course.isSelected) {
                SystemStatusBadge("已选", SystemTone.Success)
            } else if (!isMultiSelectMode) {
                if (showTargetAction) com.tyust.course.ui.system.SystemIconButton(
                    onClick = onSetTargetCourse,
                    icon = Icons.Default.Flag,
                    contentDescription = "设为目标课程"
                )
                com.tyust.course.ui.system.SystemIconButton(
                    onClick = onAddToQueue,
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = "加入抢课队列",
                    tint = NeuPrimary
                )
            }
        }
        CourseDetailLine(Icons.Default.Schedule, course.time.ifBlank { "时间未安排" })
        if (course.location.isNotBlank()) CourseDetailLine(Icons.Default.Place, course.location)
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
            modifier = Modifier.weight(1f)
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
        Row(
            modifier = Modifier.weight(1f).then(Modifier.testTag("active-filter-summary")).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
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

package com.tyust.course.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.GlassSegmentedBar
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.glass.LiquidActionGroup
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemEmptyState
import com.tyust.course.ui.system.SystemLoadingState
import com.tyust.course.ui.system.SystemSectionHeader
import com.tyust.course.ui.system.SystemStatStrip
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule
import com.tyust.course.ui.system.GlassOptionWheelDialog
import com.tyust.course.ui.system.HeaderGlassSlab
import com.tyust.course.ui.system.LocalAppBackdrop
import com.tyust.course.ui.system.LocalAppOverlayBottomInset
import com.tyust.course.ui.system.LocalControlBackdrop
import com.tyust.course.ui.system.StatusBarFrost
import com.tyust.course.ui.system.SystemCompactSegmentedControl
import com.tyust.course.ui.system.SystemDivider
import com.tyust.course.ui.system.isBackdropSupported
import com.tyust.course.ui.system.lerpDp
import com.tyust.course.ui.system.reportNoticeAnchor
import com.tyust.course.ui.theme.MotionSpring
import com.tyust.course.ui.theme.NeuPrimary
import com.tyust.course.ui.theme.SemanticDanger
import com.tyust.course.ui.theme.SemanticInfo
import com.tyust.course.ui.theme.SemanticSuccess
import com.tyust.course.ui.theme.SemanticWarning

data class GradeItemUi(
    val courseName: String,
    val grade: String,
    val credits: String,
    val gpa: String,
    val courseType: String,
    val year: String = "",
    val term: String = "",
    val college: String = "",
    val courseCode: String = "",
    val teachingClass: String = "",
    val jxbId: String = "",
    val detail: String = ""
)

data class ExamItemUi(
    val courseName: String,
    val examTime: String,
    val location: String,
    val seatNumber: String,
    val examName: String,
    val teacher: String
)

data class OverallStatsUi(
    val gpa: String,
    val credits: String,
    val courseCount: Int,
    val excellent: Int,
    val good: Int,
    val medium: Int,
    val pass: Int
)

// ── 顶栏折叠几何 ────────────────────────────────────────────────
// 与课表页同一条铁律：展开态与折叠态的高度【差】必须等于折叠行程，
// 于是顶栏收缩与内容上滚 1:1 对消、全程跟手。脱钩就会互相追赶。
private val GradesHeaderExpandedHeight = 116.dp   // 状态栏以下：10 + 52 + 8 + 36 + 10
private val GradesHeaderCollapsedHeight = 54.dp   // 状态栏以下：9 + 36 + 9
private val GradesHeaderCollapseTravel = 62.dp    // == 两者之差

/** 大标题 + 副标题那一块。折叠时整块淡出上移，只留选择栏。 */
private val GradesTitleBlockHeight = 52.dp
/** 选择栏高度，两态不变——它是这一页的主导航，缩它没有收益只有风险。 */
private val GradesSegmentHeight = 36.dp
private val GradesTitleGap = 8.dp

private val GradesSlabInset = 12.dp
private val GradesSlabTopGap = 6.dp
private val GradesSlabBottomGap = 4.dp
/** 折叠态玻璃条高 44dp（54 - 6 - 4），圆角取一半正好是胶囊。 */
private val GradesSlabCorner = 22.dp

@Composable
fun GradesScreen(
    currentTab: Int,
    onTabChange: (Int) -> Unit,
    semesterGrades: List<GradeItemUi>,
    semesters: List<String>,
    currentSemester: String,
    onSemesterChange: (String) -> Unit,
    semesterIsLoading: Boolean,
    overallGrades: List<GradeItemUi>,
    overallStats: OverallStatsUi,
    overallIsLoading: Boolean,
    examList: List<ExamItemUi>,
    examIsLoading: Boolean,
    onRefresh: () -> Unit,
    onExportGrades: (List<GradeItemUi>) -> Unit = {}
) {
    val tabTitles = listOf("学期成绩", "总体成绩", "考试安排")
    val isRefreshing = semesterIsLoading || overallIsLoading || examIsLoading
    val subtitle = when (currentTab) {
        0 -> if (currentSemester.isBlank()) "按学期查看课程成绩" else currentSemester
        1 -> "累计成绩与分布概览"
        else -> "近期考试安排"
    }

    // 每个 tab 一份滚动位置：共用一份会让切 tab 时另一页的位置被顶掉，
    // 折叠进度也跟着跳。顶栏读当前 tab 那一份。
    val semesterListState = rememberLazyListState()
    val overallListState = rememberLazyListState()
    val examListState = rememberLazyListState()
    val activeListState = when (currentTab) {
        0 -> semesterListState
        1 -> overallListState
        else -> examListState
    }
    val travelPx = with(LocalDensity.current) { GradesHeaderCollapseTravel.toPx() }
    val headerCollapse by remember(travelPx, activeListState) {
        derivedStateOf {
            // 第 0 项就是首屏那一块内容，它比行程高得多，所以只看它的偏移；
            // 一旦滚过第 0 项，直接判定为完全折叠。
            if (activeListState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (activeListState.firstVisibleItemScrollOffset / travelPx).coerceIn(0f, 1f)
            }
        }
    }

    // 内容的捕获层。顶栏玻璃采样「壁纸 + 这一层」，于是列表从玻璃条底下穿过时
    // 会被折射；顶栏本身不在这一层内，不构成自采样。
    val wallpaperBackdrop = LocalAppBackdrop.current
    val contentBackdrop = if (wallpaperBackdrop != null && isBackdropSupported()) {
        rememberLayerBackdrop()
    } else {
        null
    }
    val headerSampleBackdrop = if (wallpaperBackdrop != null && contentBackdrop != null) {
        rememberCombinedBackdrop(wallpaperBackdrop, contentBackdrop)
    } else {
        null
    }

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // 【常量】top inset：Scaffold 的 topPadding 随顶栏一起缩，喂给滚动容器会让
    // 内容走两倍行程（课表页踩过）。这里固定按展开态高度留白。
    val contentTopInset = statusBarHeight + GradesHeaderExpandedHeight
    val contentBottomInset = LocalAppOverlayBottomInset.current + 24.dp

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            // topBar slot 只测量单个子项，内联通知必须与顶栏在同一个 Column 里
            Column(modifier = Modifier.reportNoticeAnchor()) {
                GradesHeader(
                    subtitle = subtitle,
                    tabTitles = tabTitles,
                    currentTab = currentTab,
                    onTabChange = onTabChange,
                    collapseFraction = headerCollapse,
                    sampleBackdrop = headerSampleBackdrop,
                    shareEnabled = when (currentTab) {
                        0 -> semesterGrades.isNotEmpty()
                        1 -> overallGrades.isNotEmpty()
                        else -> false
                    },
                    showShare = currentTab != 2,
                    isRefreshing = isRefreshing,
                    onShare = {
                        val grades = if (currentTab == 0) semesterGrades else overallGrades
                        if (grades.isNotEmpty()) onExportGrades(grades)
                    },
                    onRefresh = onRefresh
                )
            }
        }
    ) { _ ->
        // 内容铺满整屏、从顶栏底下穿过；留白由各自的 contentPadding 负责（见上）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (contentBackdrop != null) {
                        Modifier.layerBackdrop(contentBackdrop)
                    } else {
                        Modifier
                    }
                )
        ) {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "grades_tab_content"
            ) { tab ->
                when (tab) {
                    0 -> SemesterGradesContent(
                        grades = semesterGrades,
                        semesters = semesters,
                        currentSemester = currentSemester,
                        onSemesterChange = onSemesterChange,
                        isLoading = semesterIsLoading,
                        listState = semesterListState,
                        topInset = contentTopInset,
                        bottomInset = contentBottomInset
                    )

                    1 -> OverallGradesContent(
                        grades = overallGrades,
                        stats = overallStats,
                        isLoading = overallIsLoading,
                        listState = overallListState,
                        topInset = contentTopInset,
                        bottomInset = contentBottomInset
                    )

                    else -> ExamScheduleContent(
                        exams = examList,
                        isLoading = examIsLoading,
                        listState = examListState,
                        topInset = contentTopInset,
                        bottomInset = contentBottomInset
                    )
                }
            }
        }
    }
}

/**
 * 成绩页顶栏：上划收拢成一条悬浮玻璃胶囊。
 *
 * 展开态是 iOS 的大标题（直接浮在内容上），折叠态只留【选择栏 + 动作钮】——
 * 三个标签本身就说明了在哪一页，小标题是多余信息。
 *
 * 动作钮两态都待在选择栏这一行、不跨行搬家：折叠于是是一段连续插值，
 * 没有"某一帧消失、另一处重生"的接缝。
 */
@Composable
private fun GradesHeader(
    subtitle: String,
    tabTitles: List<String>,
    currentTab: Int,
    onTabChange: (Int) -> Unit,
    collapseFraction: Float,
    sampleBackdrop: Backdrop?,
    showShare: Boolean,
    shareEnabled: Boolean,
    isRefreshing: Boolean,
    onShare: () -> Unit,
    onRefresh: () -> Unit
) {
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // 折叠全程跟手；高刚度临界阻尼弹簧只负责抹平 LazyList 快滚时的跳变
    val collapse by animateFloatAsState(
        targetValue = collapseFraction.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
        label = "gradesHeaderCollapse"
    )

    // 顶栏玻璃把自己的渲染结果导出到这一层，供板上的选择栏与芯片二次采样
    val headerBackdrop = rememberLayerBackdrop()
    val chipBackdrop = if (sampleBackdrop != null) {
        rememberCombinedBackdrop(sampleBackdrop, headerBackdrop)
    } else {
        null
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(
                statusBarHeight +
                    lerpDp(GradesHeaderExpandedHeight, GradesHeaderCollapsedHeight, collapse)
            )
    ) {
        // 玻璃层【必须是前景内容的兄弟节点】：layerBackdrop 捕获整棵子树，
        // 挂在包含按钮的父节点上，按钮就会采样一个含有自己的图层 → native 崩。
        if (sampleBackdrop != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .layerBackdrop(headerBackdrop)
            ) {
                StatusBarFrost(
                    height = statusBarHeight + 1.dp,
                    collapse = collapse,
                    backdrop = sampleBackdrop
                )
                HeaderGlassSlab(
                    // 比 collapse 晚起步：顶栏还高的时候它是一张大卡片，
                    // 提前显形会让人先看到"卡"再看到"条"。
                    strength = ((collapse - 0.35f) / 0.65f).coerceIn(0f, 1f),
                    backdrop = sampleBackdrop,
                    cornerRadius = GradesSlabCorner,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = statusBarHeight + GradesSlabTopGap,
                            start = GradesSlabInset,
                            end = GradesSlabInset,
                            bottom = GradesSlabBottomGap
                        )
                        .fillMaxHeight()
                )
            }
        }

        CompositionLocalProvider(LocalControlBackdrop provides chipBackdrop) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = lerpDp(10.dp, 9.dp, collapse))
            ) {
                // 收拢方式：容器高度与内容缩放走【同一个系数】，于是绘制尺寸永远等于
                // 容器高度——既不会溢出压到下面那一行，也不会被压扁。
                // 单缩容器高度是"文字挤出轨道"的成因（Modifier.height 会夹住子件）。
                val titleFraction = (1f - collapse).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(GradesTitleBlockHeight * titleFraction)
                        .padding(horizontal = PagePadding)
                ) {
                    Column(
                        modifier = Modifier
                            .requiredHeight(GradesTitleBlockHeight)
                            .graphicsLayer {
                                alpha = (titleFraction * 2.2f - 0.2f).coerceIn(0f, 1f)
                                scaleX = titleFraction
                                scaleY = titleFraction
                                transformOrigin = TransformOrigin(0f, 0f)
                            },
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "成绩与考试",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(NeuPrimary.copy(alpha = 0.65f))
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = 0.80f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(lerpDp(GradesTitleGap, 0.dp, collapse)))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(GradesSegmentHeight)
                        .padding(horizontal = PagePadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SystemCompactSegmentedControl(
                        options = tabTitles,
                        selectedIndex = currentTab,
                        onSelect = onTabChange,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // 与课表顶栏同一套液体玻璃芯片组。刷新中不掉出这套材质——
                    // 用内容槽把图标换成进度圈，芯片本身不变。
                    LiquidActionGroup(spacing = 4.dp) {
                        if (showShare) {
                            action(
                                index = 0,
                                icon = Icons.Default.Share,
                                contentDescription = "导出成绩",
                                onClick = onShare,
                                enabled = shareEnabled && !isRefreshing,
                                buttonSize = 32.dp,
                                iconSize = 15.dp
                            )
                        }
                        if (isRefreshing) {
                            action(
                                index = 1,
                                contentDescription = "正在刷新",
                                onClick = {},
                                enabled = false,
                                buttonSize = 32.dp
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        } else {
                            action(
                                index = 1,
                                icon = Icons.Default.Refresh,
                                contentDescription = "刷新",
                                onClick = onRefresh,
                                buttonSize = 32.dp,
                                iconSize = 15.dp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverallGradesContent(
    grades: List<GradeItemUi>,
    stats: OverallStatsUi,
    isLoading: Boolean,
    listState: LazyListState,
    topInset: Dp,
    bottomInset: Dp
) {
    when {
        isLoading -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = topInset),
                contentAlignment = Alignment.Center
            ) {
                SystemLoadingState(text = "正在加载总体成绩…")
            }
        }

        grades.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = topInset),
                contentAlignment = Alignment.Center
            ) {
                SystemEmptyState(
                    title = "暂无总体成绩",
                    message = "点击刷新获取最新成绩"
                )
            }
        }

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    start = PagePadding,
                    end = PagePadding,
                    top = topInset,
                    bottom = bottomInset
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SystemStatStrip(
                            items = listOf(
                                "累计绩点" to stats.gpa.ifBlank { "0.00" },
                                "已修学分" to stats.credits.ifBlank { "0" },
                                "总课程" to stats.courseCount.toString()
                            )
                        )
                        GradeDistributionCard(stats = stats)
                        SystemSectionHeader(
                            title = "课程明细",
                            subtitle = "共 ${grades.size} 门课程"
                        )
                    }
                }

                items(grades) { item ->
                    GradeItemRow(item = item)
                }
            }
        }
    }
}

@Composable
private fun SemesterGradesContent(
    grades: List<GradeItemUi>,
    semesters: List<String>,
    currentSemester: String,
    onSemesterChange: (String) -> Unit,
    isLoading: Boolean,
    listState: LazyListState,
    topInset: Dp,
    bottomInset: Dp
) {
    val totalCredits = grades.sumOf { it.credits.toDoubleOrNull() ?: 0.0 }
    val weightedGpa = grades.sumOf {
        (it.credits.toDoubleOrNull() ?: 0.0) * (it.gpa.toDoubleOrNull() ?: 0.0)
    }
    val averageGpa = if (totalCredits > 0) weightedGpa / totalCredits else 0.0

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = PagePadding,
            end = PagePadding,
            top = topInset,
            bottom = bottomInset
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SemesterSelector(
                    semesters = semesters,
                    currentSemester = currentSemester,
                    onSemesterChange = onSemesterChange
                )

                when {
                    isLoading -> SystemLoadingState(text = "正在加载学期成绩…")
                    grades.isEmpty() -> SystemEmptyState(
                        title = "暂无学期成绩",
                        message = if (currentSemester.isBlank()) {
                            "请选择学期查看成绩"
                        } else {
                            "$currentSemester 暂无成绩记录"
                        }
                    )
                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SystemStatStrip(
                                items = listOf(
                                    "平均绩点" to String.format("%.2f", averageGpa),
                                    "总学分" to String.format("%.1f", totalCredits),
                                    "课程数" to grades.size.toString()
                                )
                            )
                            SystemSectionHeader(
                                title = "课程明细",
                                subtitle = currentSemester.ifBlank { null }
                            )
                        }
                    }
                }
            }
        }

        if (!isLoading && grades.isNotEmpty()) {
            items(grades) { item ->
                GradeItemRow(item = item)
            }
        }
    }
}

/**
 * 学期选择。
 *
 * 原先是 `SystemPicker`——一枚 56dp 的全宽表单字段，值又与顶栏副标题重复，
 * 在这一屏里是最重的一块 chrome。换成一行紧凑玻璃行 + 复用课表设置页那枚滚轮弹窗。
 */
@Composable
private fun SemesterSelector(
    semesters: List<String>,
    currentSemester: String,
    onSemesterChange: (String) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Capsule())
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = semesters.isNotEmpty()
            ) { showPicker = true }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CalendarToday,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
        )
        Text(
            text = "学期",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
        )
        Spacer(modifier = Modifier.weight(1f))
        GlassFilterChip(
            label = currentSemester.ifBlank { "选择学期" },
            selected = currentSemester.isNotBlank(),
            compact = true
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }

    if (showPicker && semesters.isNotEmpty()) {
        GlassOptionWheelDialog(
            title = "选择学期",
            options = semesters,
            selectedIndex = semesters.indexOf(currentSemester).coerceAtLeast(0),
            onConfirm = { index ->
                onSemesterChange(semesters[index])
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
private fun GradeDistributionCard(
    stats: OverallStatsUi
) {
    SystemCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant
    ) {
        SystemSectionHeader(
            title = "成绩分布",
            subtitle = "按已统计课程划分"
        )

        GlassSegmentedBar(
            segments = listOf(
                stats.excellent.toFloat() to SemanticSuccess,
                stats.good.toFloat() to SemanticInfo,
                stats.medium.toFloat() to SemanticWarning,
                stats.pass.toFloat() to SemanticDanger
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DistributionItem("优", stats.excellent, SystemTone.Success, Modifier.weight(1f))
            DistributionItem("良", stats.good, SystemTone.Info, Modifier.weight(1f))
            DistributionItem("中", stats.medium, SystemTone.Warning, Modifier.weight(1f))
            DistributionItem("及", stats.pass, SystemTone.Danger, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DistributionItem(
    label: String,
    count: Int,
    tone: SystemTone,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SystemStatusBadge(
            text = label,
            tone = tone
        )
        Text(
            text = "$count 门",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GradeItemRow(
    item: GradeItemUi
) {
    var expanded by remember { mutableStateOf(false) }
    val gradeColor = getGradeColor(item.grade)
    val hasDetail = item.detail.isNotEmpty() || item.courseCode.isNotEmpty()
    // 一枚箭头旋转，而不是上下两个图标硬切换——后者在展开动画中途是一帧突变
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MotionSpring.liquidSettle(),
        label = "gradeChevron"
    )

    SystemCard(
        modifier = Modifier.fillMaxWidth(),
        // 点击交给 SystemCard 自己的 0.97 按压缩放，不再外挂一个无反馈的 clickable
        onClick = if (hasDetail) ({ expanded = !expanded }) else null
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // 左侧：课程名 + 代码/学院
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.courseName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val subInfo = buildString {
                        if (item.courseCode.isNotEmpty()) append(item.courseCode)
                        if (item.college.isNotEmpty()) {
                            if (isNotEmpty()) append("   ")
                            append(item.college)
                        }
                    }
                    if (subInfo.isNotEmpty()) {
                        Text(
                            text = subInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 右侧：学分 + 成绩 + chevron
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${item.credits}学分",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.grade,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = gradeColor
                    )
                }

                if (hasDetail) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(chevronRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 展开区域：成绩构成进度条
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = 0.86f,
                        stiffness = 420f,
                        visibilityThreshold = IntSize.VisibilityThreshold
                    )
                ) + fadeIn(animationSpec = tween(170)),
                exit = shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = 1f,
                        stiffness = 460f,
                        visibilityThreshold = IntSize.VisibilityThreshold
                    )
                ) + fadeOut(animationSpec = tween(120))
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    SystemDivider(alpha = 0.5f)
                    Spacer(modifier = Modifier.height(10.dp))

                    if (item.detail.isNotEmpty()) {
                        Text(
                            text = "成绩构成",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val components = parseGradeComponents(item.detail)
                        components.forEachIndexed { index, comp ->
                            GradeComponentBar(
                                label = comp.label,
                                score = comp.score,
                                maxScore = 100f,
                                // 同一族浓淡阶梯，而不是紫/蓝/绿/橙/青五种循环色：
                                // 那五种颜色不携带任何语义，只是在卡片里制造噪声。
                                color = NeuPrimary.copy(
                                    alpha = (0.95f - index * 0.15f).coerceAtLeast(0.42f)
                                )
                            )
                            if (index < components.lastIndex) {
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    } else {
                        // 无分项时展示基本信息
                        Text(
                            text = "暂无分项详情",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }

                    // 元信息落到小芯片上（复用筛选面板那一枚）：原先绩点根本没露过面，
                    // 教学班只在"无分项"那条分支里以裸文字出现。
                    val metaChips = buildList {
                        if (item.gpa.isNotBlank()) add("绩点 ${item.gpa}")
                        if (item.credits.isNotBlank()) add("${item.credits} 学分")
                        if (item.courseType.isNotBlank()) add(item.courseType)
                        if (item.teachingClass.isNotBlank()) add(item.teachingClass)
                    }
                    if (metaChips.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            metaChips.forEach { label ->
                                GlassFilterChip(
                                    label = label,
                                    selected = false,
                                    compact = true
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class GradeComponent(val label: String, val score: Float)

private fun parseGradeComponents(detail: String): List<GradeComponent> {
    return detail.split(" | ").mapNotNull { part ->
        // 格式: "平时(30%): 90" 或 "平时: 90"
        val colonIdx = part.lastIndexOf(':')
        if (colonIdx < 0) return@mapNotNull null
        val label = part.substring(0, colonIdx).trim()
        val scoreStr = part.substring(colonIdx + 1).trim()
        val score = scoreStr.toFloatOrNull() ?: return@mapNotNull null
        GradeComponent(label, score)
    }
}

@Composable
private fun GradeComponentBar(
    label: String,
    score: Float,
    maxScore: Float,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        ) {
            // 展开时才涨条：这个 composable 只在展开区里被组合，
            // 于是首帧从 0 起、随弹簧涨到目标，展开动作有了"结果被填出来"的读法。
            var play by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { play = true }
            val fill by animateFloatAsState(
                targetValue = if (play) (score / maxScore).coerceIn(0f, 1f) else 0f,
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 260f),
                label = "gradeBarFill"
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = fill)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (score == score.toLong().toFloat()) score.toLong().toString()
                   else score.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun ExamScheduleContent(
    exams: List<ExamItemUi>,
    isLoading: Boolean,
    listState: LazyListState,
    topInset: Dp,
    bottomInset: Dp
) {
    when {
        isLoading -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = topInset),
                contentAlignment = Alignment.Center
            ) {
                SystemLoadingState(text = "正在加载考试安排…")
            }
        }

        exams.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = topInset),
                contentAlignment = Alignment.Center
            ) {
                SystemEmptyState(
                    title = "暂无考试安排",
                    message = "点击刷新获取最新考试信息"
                )
            }
        }

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    start = PagePadding,
                    end = PagePadding,
                    top = topInset,
                    bottom = bottomInset
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SystemStatStrip(
                            items = listOf(
                                "考试数" to exams.size.toString(),
                                "最近状态" to "已同步",
                                "查看方式" to "列表"
                            )
                        )
                        SystemSectionHeader(
                            title = "考试列表",
                            subtitle = "按时间顺序展示"
                        )
                    }
                }

                items(exams) { exam ->
                    ExamItemRow(exam = exam)
                }
            }
        }
    }
}

@Composable
private fun ExamItemRow(
    exam: ExamItemUi
) {
    val examTone = if (exam.examName.contains("期中")) SystemTone.Warning else SystemTone.Info

    SystemCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = exam.courseName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(12.dp))
            SystemStatusBadge(
                text = if (exam.examName.isBlank()) "考试" else exam.examName,
                tone = examTone
            )
        }

        ExamDetailRow(
            icon = Icons.Default.Schedule,
            text = exam.examTime.ifBlank { "未提供考试时间" }
        )
        ExamDetailRow(
            icon = Icons.Default.LocationOn,
            text = buildString {
                append(exam.location.ifBlank { "未提供地点" })
                if (exam.seatNumber.isNotBlank()) {
                    append(" · 座位 ${exam.seatNumber}")
                }
            }
        )
        ExamDetailRow(
            icon = Icons.Default.Person,
            text = exam.teacher.ifBlank { "未提供教师信息" }
        )
    }
}

@Composable
private fun ExamDetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

fun getGradeColor(grade: String): Color {
    val score = grade.replace("[^0-9]".toRegex(), "").toIntOrNull()

    if (score != null) {
        return when {
            score >= 90 -> SemanticSuccess
            score >= 80 -> SemanticInfo
            score >= 70 -> SemanticWarning
            score >= 60 -> Color(0xFFB26A00)
            else -> SemanticDanger
        }
    }

    return when (grade) {
        "优秀" -> SemanticSuccess
        "良好" -> SemanticInfo
        "中等" -> SemanticWarning
        "及格" -> Color(0xFFB26A00)
        else -> SemanticDanger
    }
}

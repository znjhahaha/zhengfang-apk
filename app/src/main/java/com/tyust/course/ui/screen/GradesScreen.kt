package com.tyust.course.ui.screen

import com.tyust.course.ui.theme.moduleEntrance
import com.tyust.course.ui.theme.ModuleMotion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemEmptyState
import com.tyust.course.ui.system.SystemLoadingState
import com.tyust.course.ui.system.SystemSectionHeader
import com.tyust.course.ui.system.SystemStatStrip
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.tyust.course.ui.system.LocalAppBackdrop
import com.tyust.course.ui.system.LocalAppOverlayBottomInset
import com.tyust.course.ui.system.SystemDivider
import com.tyust.course.ui.system.SystemPicker
import com.tyust.course.ui.system.isBackdropSupported
import com.tyust.course.ui.system.rememberGlassAccessibilityMode
import com.tyust.course.ui.system.reportNoticeAnchor
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
    val detail: String = "",
    val onDetailRequest: (() -> Unit)? = null
)

data class ExamItemUi(
    val courseName: String,
    val examTime: String,
    val location: String,
    val seatNumber: String,
    val examName: String,
    val teacher: String
)

internal fun semesterAverageGpa(grades: List<GradeItemUi>): String {
    val graded = grades.mapNotNull { grade ->
        val credit = grade.credits.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return@mapNotNull null
        val point = grade.gpa.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
        credit to point
    }
    val credits = graded.sumOf { it.first }
    return if (credits > 0) java.lang.String.format(java.util.Locale.ROOT, "%.2f", graded.sumOf { it.first * it.second } / credits) else "--"
}

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
//
// 总高不再写死：标题块要跟着系统字体缩放走，三个高度全部由下面这组 token 推导，
// 那条恒等式因此是结构性的（见 GradesHeaderMetrics.travel），不靠人肉对账。

private class GradesHeaderMetrics {
    var expanded by mutableStateOf(140.dp)
    var collapsed by mutableStateOf(68.dp)
    val travel: Dp get() = (expanded - collapsed).coerceAtLeast(1.dp)
}

@Composable
private fun rememberGradesHeaderMetrics(): GradesHeaderMetrics = remember(LocalDensity.current) { GradesHeaderMetrics() }

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
    onExportGrades: (List<GradeItemUi>) -> Unit = {},
    semesterError: String = "",
    overallError: String = "",
    examError: String = "",
    supportedTabs: Set<Int> = setOf(0, 1, 2)
) {
    val availableTabs = listOf(0, 1, 2).filter { it in supportedTabs }.ifEmpty { listOf(0, 1, 2) }
    val tabTitles = availableTabs.map { listOf("学期", "总体", "考试")[it] + if (it in supportedTabs) "" else " · 未适配" }
    val isRefreshing = semesterIsLoading || overallIsLoading || examIsLoading
    val subtitle = when (currentTab) {
        0 -> "${semesterGrades.size} 门课程"
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
    val metrics = rememberGradesHeaderMetrics()
    val travelPx = with(LocalDensity.current) { metrics.travel.toPx() }
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
    val contentTopInset = statusBarHeight + metrics.expanded
    val contentBottomInset = LocalAppOverlayBottomInset.current + 24.dp

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            // topBar slot 只测量单个子项，内联通知必须与顶栏在同一个 Column 里
            Column(modifier = Modifier.moduleEntrance(0).reportNoticeAnchor()) {
                GradesHeader(
                    subtitle = subtitle,
                    tabTitles = tabTitles,
                    currentTab = availableTabs.indexOf(currentTab).coerceAtLeast(0),
                    onTabChange = { index -> availableTabs.getOrNull(index)?.takeIf { it in supportedTabs }?.let(onTabChange) },
                    collapseFraction = headerCollapse,
                    metrics = metrics,
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
    ) { padding ->
        // 内容铺满整屏、从顶栏底下穿过；留白由各自的 contentPadding 负责（见上）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
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
                        error = semesterError,
                        listState = semesterListState,
                        topInset = contentTopInset,
                        bottomInset = contentBottomInset
                    )

                    1 -> OverallGradesContent(
                        grades = overallGrades,
                        stats = overallStats,
                        isLoading = overallIsLoading,
                        error = overallError,
                        listState = overallListState,
                        topInset = contentTopInset,
                        bottomInset = contentBottomInset
                    )

                    else -> ExamScheduleContent(
                        exams = examList,
                        isLoading = examIsLoading,
                        error = examError,
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
 * 展开态是 iOS 的大标题（直接浮在内容上）+ 一条**全宽原尺寸**的选择栏；折叠态只留选择栏。
 * 三个标签本身就说明了在哪一页，小标题是多余信息。
 *
 * 两枚动作钮**不属于任何一行**——它们是这个 Box 的第二个子节点，钉在右上角。理由有两条：
 * 1. 放进选择栏那一行就要和它抢宽度，三段中文标签会被挤成一条窄带；
 * 2. 标题块整块收掉之后，"标题行右上角"与"选择栏右侧"这两个位置的 y 几乎重合，
 *    所以芯片全程只走几 dp——真正在动的是选择栏升上来、以及它右侧连续让出的那一段。
 */
@Composable
private fun GradesHeader(
    subtitle: String, tabTitles: List<String>, currentTab: Int, onTabChange: (Int) -> Unit,
    collapseFraction: Float, metrics: GradesHeaderMetrics, sampleBackdrop: Backdrop?,
    showShare: Boolean, shareEnabled: Boolean, isRefreshing: Boolean,
    onShare: () -> Unit, onRefresh: () -> Unit
) {
    MeasuredGradesHeader(subtitle, tabTitles, currentTab, onTabChange, collapseFraction, sampleBackdrop,
        showShare, shareEnabled, isRefreshing, onShare, onRefresh) { expanded, collapsed ->
        metrics.expanded = expanded
        metrics.collapsed = collapsed
    }
}
@Composable
private fun OverallGradesContent(
    grades: List<GradeItemUi>,
    stats: OverallStatsUi,
    isLoading: Boolean,
    error: String,
    listState: LazyListState,
    topInset: Dp,
    bottomInset: Dp
) {
    val rowKeys = remember(grades) { gradeRowKeys(grades) }
    when {
        isLoading && grades.isEmpty() -> {
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
                    title = if (error.isNotBlank()) "总体成绩加载失败" else "暂无总体成绩",
                    message = error.ifBlank { "点击刷新获取最新成绩" }
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
                    Column(Modifier.moduleEntrance(1), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        GradeRefreshStatus(isLoading, error)
                        SystemStatStrip(
                            items = listOf(
                                "累计绩点" to stats.gpa.ifBlank { "--" },
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

                items(grades.size, key = { rowKeys[it] }, contentType = { "grade" }) { index ->
                    GradeItemRow(item = grades[index])
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
    error: String,
    listState: LazyListState,
    topInset: Dp,
    bottomInset: Dp
) {
    val totalCredits = remember(grades) { grades.sumOf { it.credits.toDoubleOrNull() ?: 0.0 } }
    val averageGpa = remember(grades) { semesterAverageGpa(grades) }
    val rowKeys = remember(grades) { gradeRowKeys(grades) }

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
            Column(Modifier.moduleEntrance(1), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SemesterSelector(
                    semesters = semesters,
                    currentSemester = currentSemester,
                    onSemesterChange = onSemesterChange
                )

                when {
                    isLoading && grades.isEmpty() -> SystemLoadingState(text = "正在加载学期成绩…")
                    error.isNotBlank() && grades.isEmpty() -> SystemEmptyState(title = "学期成绩加载失败", message = error)
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
                            GradeRefreshStatus(isLoading, error)
                            SystemStatStrip(
                                items = listOf(
                                    "平均绩点" to averageGpa,
                                    "总学分" to String.format("%.1f", totalCredits),
                                    "课程数" to grades.size.toString()
                                )
                            )
                            SystemSectionHeader(
                                title = "课程明细",
                                subtitle = null
                            )
                        }
                    }
                }
            }
        }

        if (grades.isNotEmpty()) {
            items(grades.size, key = { rowKeys[it] }, contentType = { "grade" }) { index ->
                GradeItemRow(item = grades[index])
            }
        }
    }
}

@Composable
private fun GradeRefreshStatus(isLoading: Boolean, error: String) {
    if (isLoading) {
        androidx.compose.material3.LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(2.dp)
        )
    } else if (error.isNotBlank()) {
        Text(error, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SemesterSelector(
    semesters: List<String>,
    currentSemester: String,
    onSemesterChange: (String) -> Unit
) {
    val selectedIndex = semesters.indexOf(currentSemester).takeIf { it >= 0 }
    SystemPicker(
        options = semesters,
        selectedIndex = selectedIndex,
        onSelect = { index -> onSemesterChange(semesters[index]) },
        modifier = Modifier.fillMaxWidth(),
        label = "学期",
        placeholder = "选择学期"
    )
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
    var expanded by rememberSaveable(item.year, item.term, item.courseCode, item.courseName, item.jxbId) { mutableStateOf(false) }
    val components = remember(item.detail) { parseGradeComponents(item.detail) }
    val notes = remember(item.detail) {
        item.detail.split(Regex("\\s*[|；;]\\s*")).filter { it.isNotBlank() && parseGradeComponents(it).isEmpty() }
    }
    val gradeColor = getGradeColor(item.grade)
    val reduced = com.tyust.course.ui.system.rememberGlassAccessibilityMode().reduceMotion
    val hasDetail = item.detail.isNotEmpty() || item.courseCode.isNotEmpty() || item.onDetailRequest != null
    SystemCard(
        modifier = Modifier.fillMaxWidth().moduleEntrance(2),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        onClick = if (hasDetail) ({ expanded = !expanded; if (expanded && item.detail.isBlank()) item.onDetailRequest?.invoke() }) else null
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
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = gradeColor
                    )
                }

                if (hasDetail) {
                    com.tyust.course.ui.system.AnimatedLineIcon(
                        com.tyust.course.ui.system.AnimatedIconSpec.Chevron, Modifier.size(20.dp),
                        state = if (expanded) com.tyust.course.ui.system.IconVisualState.Expanded else com.tyust.course.ui.system.IconVisualState.Idle,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 展开区域：成绩构成进度条
            AnimatedVisibility(
                visible = expanded,
                enter = ModuleMotion.expand(reduced),
                exit = ModuleMotion.collapse(reduced)
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    SystemDivider(alpha = 0.5f)
                    Spacer(modifier = Modifier.height(10.dp))

                    if (components.isNotEmpty()) {
                        Text(
                            text = "成绩构成",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

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
                    } else if (item.detail.isBlank()) {
                        // 无分项时展示基本信息
                        Text(
                            text = "暂无分项详情",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

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
    return detail.replace('：', ':').split(Regex("\\s*[|；;]\\s*")).mapNotNull { part ->
        // 格式: "平时(30%): 90" 或 "平时: 90"
        val colonIdx = part.lastIndexOf(':')
        if (colonIdx < 0) return@mapNotNull null
        val label = part.substring(0, colonIdx).trim()
        val scoreStr = part.substring(colonIdx + 1).trim()
        val score = scoreStr.toFloatOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
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
            val fill by animateFloatAsState(
                targetValue = (score / maxScore).coerceIn(0f, 1f),
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
    error: String,
    listState: LazyListState,
    topInset: Dp,
    bottomInset: Dp
) {
    when {
        isLoading && exams.isEmpty() -> {
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
                    title = if (error.isNotBlank()) "考试安排加载失败" else "暂无考试安排",
                    message = error.ifBlank { "点击刷新获取最新考试信息" }
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
                    Column(Modifier.moduleEntrance(1), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                    Box(Modifier.moduleEntrance(2)) { ExamItemRow(exam = exam) }
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

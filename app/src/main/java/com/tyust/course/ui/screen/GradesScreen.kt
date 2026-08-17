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
import com.tyust.course.ui.system.HeaderGlassSlab
import com.tyust.course.ui.system.LiquidSegmentedControl
import com.tyust.course.ui.system.LocalAppBackdrop
import com.tyust.course.ui.system.LocalAppOverlayBottomInset
import com.tyust.course.ui.system.LocalControlBackdrop
import com.tyust.course.ui.system.StatusBarFrost
import com.tyust.course.ui.system.SystemDivider
import com.tyust.course.ui.system.SystemPicker
import com.tyust.course.ui.system.isBackdropSupported
import com.tyust.course.ui.system.lerpDp
import com.tyust.course.ui.system.rememberGlassAccessibilityMode
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
//
// 总高不再写死：标题块要跟着系统字体缩放走，三个高度全部由下面这组 token 推导，
// 那条恒等式因此是结构性的（见 GradesHeaderMetrics.travel），不靠人肉对账。

private val GradesTitleFontSize = 28.sp
/**
 * 标题与副标题都显式钉住行高。
 *
 * 中文字体的自然行高约 1.45em——28sp 的标题实际要 40dp 左右，比目测多出一截。
 * 不钉住行高，容器就永远算不准；溢出的那部分会被之后绘制的分段栏盖掉，
 * 表现为"副标题只显示了上半截"。
 */
private val GradesTitleLineHeight = 36.sp
private val GradesSubtitleLineHeight = 18.sp
private val GradesTitleSubtitleGap = 3.dp
private val GradesTitleGap = 8.dp

/**
 * 选择栏高度。展开态取 52dp——那就是 `SystemSegmentedControl` 的默认值、这一页原本的尺寸。
 *
 * 它**独占一行、全宽**：与动作芯片共享一行会把三段中文标签挤成一条窄带（上一版的毛病）。
 * 折叠态收到 44dp，仍远离 `LiquidSegmentedControl` 的 compact 阈值（`height <= 36.dp`），
 * 于是全程都是同一档排版，不会中途翻档。
 */
private val GradesSegmentHeightExpanded = 52.dp
private val GradesSegmentHeightCollapsed = 44.dp

// 动作芯片：与课表顶栏同一套尺寸插值
private val GradesChipSizeExpanded = 34.dp
private val GradesChipSizeCollapsed = 30.dp
private val GradesChipIconExpanded = 16.dp
private val GradesChipIconCollapsed = 15.dp
private val GradesChipSpacingExpanded = 4.dp
private val GradesChipSpacingCollapsed = 3.dp
/**
 * 分段栏与芯片组之间的间距。折叠态"分段栏让出的那段"= 芯片组宽度 + 它。
 *
 * 取 12dp 而不是 8dp：分段栏按下时轨道会整块外扩约 8dp/侧（`LiquidSegmentedControl`
 * 的 layerBlock 有 16dp 宽度增益），8dp 的缝会被那一下正好吃满。
 */
private val GradesChipGap = 12.dp

private val GradesSlabInset = 12.dp
private val GradesSlabTopGap = 6.dp
private val GradesSlabBottomGap = 4.dp
/** 折叠条内壁到分段栏的呼吸量。缺了它分段栏会几乎贴满玻璃条，读成"条里又套一条"。 */
private val GradesSlabRing = 4.dp

private val GradesHeaderTopPadExpanded = 10.dp
// 折叠态的上下内边距写成【玻璃条留白 + ring】，于是分段栏在玻璃条里天然居中，
// 不靠人肉对账；两态上内边距又恰好相等，右上角那两枚芯片因此全程只走几 dp。
private val GradesHeaderTopPadCollapsed = GradesSlabTopGap + GradesSlabRing
private val GradesHeaderBottomPadExpanded = 10.dp
private val GradesHeaderBottomPadCollapsed = GradesSlabBottomGap + GradesSlabRing

/** 顶栏的三个高度与玻璃条圆角，全部由上面那组 token 推导出来。 */
private class GradesHeaderMetrics(
    /** 标题那一行的行高（芯片在展开态就按它居中）。 */
    val titleLine: Dp,
    val titleBlock: Dp,
    val expanded: Dp,
    val collapsed: Dp
) {
    /** 折叠行程。定义成差值，于是不可能与两态高度脱钩。 */
    val travel: Dp get() = expanded - collapsed

    /** 折叠态玻璃条高度的一半 = 胶囊；它同时是折射行程的上限（canUseLiquidLens）。 */
    val slabCorner: Dp get() = (collapsed - GradesSlabTopGap - GradesSlabBottomGap) / 2

    fun topPad(collapse: Float): Dp =
        lerpDp(GradesHeaderTopPadExpanded, GradesHeaderTopPadCollapsed, collapse)

    fun segmentHeight(collapse: Float): Dp =
        lerpDp(GradesSegmentHeightExpanded, GradesSegmentHeightCollapsed, collapse)

    fun chipSize(collapse: Float): Dp =
        lerpDp(GradesChipSizeExpanded, GradesChipSizeCollapsed, collapse)

    fun chipIconSize(collapse: Float): Dp =
        lerpDp(GradesChipIconExpanded, GradesChipIconCollapsed, collapse)

    fun chipSpacing(collapse: Float): Dp =
        lerpDp(GradesChipSpacingExpanded, GradesChipSpacingCollapsed, collapse)
}

@Composable
private fun rememberGradesHeaderMetrics(): GradesHeaderMetrics {
    val density = LocalDensity.current
    return remember(density.density, density.fontScale) {
        with(density) {
            // sp.toDp() 自带 fontScale：系统字体调大一档，标题块跟着长高
            val titleLine = GradesTitleLineHeight.toDp()
            val titleBlock = titleLine +
                GradesTitleSubtitleGap +
                GradesSubtitleLineHeight.toDp()
            GradesHeaderMetrics(
                titleLine = titleLine,
                titleBlock = titleBlock,
                expanded = GradesHeaderTopPadExpanded + titleBlock + GradesTitleGap +
                    GradesSegmentHeightExpanded + GradesHeaderBottomPadExpanded,
                collapsed = GradesHeaderTopPadCollapsed + GradesSegmentHeightCollapsed +
                    GradesHeaderBottomPadCollapsed
            )
        }
    }
}

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
            Column(modifier = Modifier.reportNoticeAnchor()) {
                GradesHeader(
                    subtitle = subtitle,
                    tabTitles = tabTitles,
                    currentTab = currentTab,
                    onTabChange = onTabChange,
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
    subtitle: String,
    tabTitles: List<String>,
    currentTab: Int,
    onTabChange: (Int) -> Unit,
    collapseFraction: Float,
    metrics: GradesHeaderMetrics,
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

    // 导出芯片只在成绩两页出现。走 presence 而不是直接增删：芯片组的液滴形变与融合
    // 因此同样作用在"切到考试安排"这件事上，分段栏也跟着连续变宽。
    val sharePresence by animateFloatAsState(
        targetValue = if (showShare) 1f else 0f,
        animationSpec = MotionSpring.liquidSettle(),
        label = "gradesSharePresence"
    )
    val chipSize = metrics.chipSize(collapse)
    val chipSpacing = metrics.chipSpacing(collapse)
    val segmentHeight = metrics.segmentHeight(collapse)
    // 芯片组实际占宽：刷新常驻，导出随 presence 收放
    val chipsWidth = chipSize + (chipSize + chipSpacing) * sharePresence
    val chipsReserve = chipsWidth + GradesChipGap
    // 展开态按标题那一行的行高居中，折叠态按选择栏居中。两态上内边距相同，
    // 所以这段位移只有几 dp——芯片是那个"不动的锚"。
    val chipTop = lerpDp(
        (metrics.titleLine - chipSize) / 2,
        (segmentHeight - chipSize) / 2,
        collapse
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(
                statusBarHeight +
                    lerpDp(metrics.expanded, metrics.collapsed, collapse)
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
                    cornerRadius = metrics.slabCorner,
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
                    .padding(top = metrics.topPad(collapse))
            ) {
                // 收拢方式：容器高度与内容缩放走【同一个系数】，于是绘制尺寸永远等于
                // 容器高度——既不会溢出压到下面那一行，也不会被压扁。
                // 单缩容器高度是"文字挤出轨道"的成因（Modifier.height 会夹住子件）。
                val titleFraction = (1f - collapse).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(metrics.titleBlock * titleFraction)
                        // 右侧给钉在角上的芯片让位，副标题不会被压在它们下面
                        .padding(start = PagePadding, end = PagePadding + chipsReserve)
                ) {
                    Column(
                        modifier = Modifier
                            .requiredHeight(metrics.titleBlock)
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
                            fontSize = GradesTitleFontSize,
                            // 行高与 metrics 同源：容器高度就是按这个算出来的
                            lineHeight = GradesTitleLineHeight,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(GradesTitleSubtitleGap))
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
                                lineHeight = GradesSubtitleLineHeight,
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
                        .height(segmentHeight)
                        .padding(horizontal = PagePadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 直接用 LiquidSegmentedControl 而不是 SystemCompactSegmentedControl：
                    // 后者把高度写死成 36dp，正好踩在 compact 阈值上（见 GradesSegmentHeight*）。
                    LiquidSegmentedControl(
                        options = tabTitles,
                        selectedIndex = currentTab,
                        onSelect = onTabChange,
                        modifier = Modifier.weight(1f),
                        height = segmentHeight
                    )
                    // 升到芯片那一行的同时连续让出右侧：展开态是 0，于是选择栏真的全宽
                    Spacer(modifier = Modifier.width(chipsReserve * collapse))
                }
            }

            // 钉在右上角的动作芯片。CompositionLocalProvider 不产生布局节点，
            // 所以这里的 align 仍然相对外层那个 header Box。
            LiquidActionGroup(
                spacing = chipSpacing,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = metrics.topPad(collapse) + chipTop, end = PagePadding)
            ) {
                action(
                    index = 0,
                    icon = Icons.Default.Share,
                    contentDescription = "导出成绩",
                    onClick = onShare,
                    enabled = showShare && shareEnabled && !isRefreshing,
                    buttonSize = chipSize,
                    iconSize = metrics.chipIconSize(collapse),
                    presence = sharePresence
                )
                // 刷新中不掉出这套材质——用内容槽把图标换成进度圈，芯片本身不变
                if (isRefreshing) {
                    action(
                        index = 1,
                        contentDescription = "正在刷新",
                        onClick = {},
                        enabled = false,
                        buttonSize = chipSize
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(metrics.chipIconSize(collapse)),
                            strokeWidth = 2.dp
                        )
                    }
                } else {
                    action(
                        index = 1,
                        icon = Icons.Default.Refresh,
                        contentDescription = "刷新",
                        onClick = onRefresh,
                        buttonSize = chipSize,
                        iconSize = metrics.chipIconSize(collapse)
                    )
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
 * 学期选择与登录、筛选场景共用同一套单体液态选择器：顶部始终锚定，菜单内容直接
 * 向下撑开布局，不再创建独立 Popup 或第二层玻璃表面。
 */
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

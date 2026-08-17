package com.tyust.course.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.ui.system.GlassLoadingState
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemCompactSegmentedControl
import com.tyust.course.ui.system.SystemEmptyState
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.tyust.course.ui.system.GlassMaterialRole
import com.tyust.course.ui.system.HeaderGlassSlab
import com.tyust.course.ui.system.StatusBarFrost
import com.tyust.course.ui.system.lerpDp
import com.tyust.course.ui.system.lerpSp
import com.tyust.course.ui.system.GlassMaterials
import com.tyust.course.ui.system.GlassRecipe
import com.tyust.course.ui.system.LocalAppBackdrop
import com.tyust.course.ui.system.LocalControlBackdrop
import com.tyust.course.ui.system.isBackdropSupported
import com.tyust.course.ui.system.glass.LiquidActionGroup
import com.tyust.course.ui.system.glass.glassRim
import com.tyust.course.ui.system.glass.resolvePhysicalLens
import com.tyust.course.ui.system.rememberGlassAccessibilityMode
import com.tyust.course.ui.system.reportNoticeAnchor

import com.tyust.course.ui.theme.MotionSpring
import com.tyust.course.ui.theme.NeuDivider
import com.tyust.course.ui.theme.NeuPrimary
import com.tyust.course.ui.theme.NeuSurface

import java.util.Calendar
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val ScheduleTimeColumnWidth = 36.dp
private val SchedulePeriodHeight = 84.dp

// ── 顶栏折叠几何 ────────────────────────────────────────────────
// 展开态与折叠态的高度【差】必须等于折叠行程（HeaderCollapseTravel）：
// 手指走 60dp -> 内容上移 60dp -> 顶栏下缘也上移 60dp，两者间距恒定。
// 行程和高度差一旦脱钩，网格顶端就会与收缩中的顶栏彼此追赶。
private val HeaderExpandedHeight = 122.dp   // 状态栏以下：10 + 76 + 28 + 8
private val HeaderCollapsedHeight = 68.dp   // 状态栏以下：6 + 36 + 22 + 4
private val HeaderCollapseTravel = 54.dp    // == 两者之差

private val HeaderTopPadExpanded = 10.dp
private val HeaderTopPadCollapsed = 6.dp
/** 标题 + 分段控件 + 芯片同一行：34(标题) + 6 + 36(分段) = 76。 */
private val HeaderActionRowExpanded = 76.dp
private val HeaderActionRowCollapsed = 36.dp
private val HeaderTitleGap = 6.dp
/** 分段控件自身高度。它内部写死 36dp，容器给不足就会被压扁而不是被裁。 */
private val HeaderSegmentHeight = 36.dp
private val HeaderWeekRowExpanded = 28.dp
private val HeaderWeekRowCollapsed = 22.dp
private val HeaderBottomPadExpanded = 8.dp
private val HeaderBottomPadCollapsed = 4.dp

/** 悬浮玻璃条相对屏幕边缘的内缩。前景内容不在条里，所以这个值不影响任何对齐。 */
private val HeaderSlabInset = 12.dp
/** 与状态栏磨砂之间留一道透明缝：网格清晰穿过，条的上缘才看得出折射。 */
private val HeaderSlabTopGap = 6.dp
private val HeaderSlabBottomGap = 4.dp
/**
 * 固定 29dp = 折叠态条高(58dp)的一半，于是折叠态正好是一枚胶囊。
 * 不用 percent=50：那样半可见的中途会是一枚很大的软药片，观感突兀。
 */
private val HeaderSlabCorner = 29.dp

data class ScheduleCourseUi(
    val name: String,
    val teacher: String,
    val location: String,
    val day: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val weeks: String,
    val color: Color,
    val isCustom: Boolean = false,
    val customId: String = ""
)

data class PeriodTimeUi(val period: Int, val startTime: String, val endTime: String)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ScheduleScreen(
    currentWeek: Int,
    courses: List<ScheduleCourseUi>,
    isLoading: Boolean,
    periodTimes: List<PeriodTimeUi> = emptyList(),
    periodCount: Int = 12,
    onWeekChange: (Int) -> Unit,
    onCourseClick: (ScheduleCourseUi) -> Unit,
    onSettingsClick: () -> Unit = {},
    onExportClick: () -> Unit = {},
    isNextSemester: Boolean = false,
    onToggleSemester: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val maxWeeks = 25
    val pagerState = rememberPagerState(
        initialPage = (currentWeek - 1).coerceIn(0, maxWeeks - 1),
        pageCount = { maxWeeks }
    )

    LaunchedEffect(pagerState.currentPage) {
        val newWeek = pagerState.currentPage + 1
        if (newWeek != currentWeek) {
            onWeekChange(newWeek)
        }
    }

    LaunchedEffect(currentWeek) {
        val targetPage = (currentWeek - 1).coerceIn(0, maxWeeks - 1)
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    // 所有 pager 页共用一个滚动位置：顶栏折叠进度要跟着它推导，
    // 而且左右切周时纵向位置不该跳回顶部。
    val gridScrollState = rememberScrollState()
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // 折叠行程 = 顶栏高度差，于是收缩与滚动 1:1 对消，全程跟手。
    val travelPx = with(LocalDensity.current) { HeaderCollapseTravel.toPx() }
    val headerCollapse by remember(travelPx) {
        derivedStateOf { (gridScrollState.value / travelPx).coerceIn(0f, 1f) }
    }
    // 课表内容的捕获层。顶栏玻璃采样「壁纸 + 这一层」，于是网格从顶栏底下
    // 穿过时会被折射；顶栏本身不在这一层内，不构成自采样。
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

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            // topBar slot 只测量单个子项，两个兄弟节点会叠放并让通知条压到状态栏，
            // 因此顶栏与内联通知必须在同一个 Column 里纵向排布。
            Column(modifier = Modifier.reportNoticeAnchor()) {
                WeekHeaderCompact(
                    currentWeek = pagerState.currentPage + 1,
                    onPrevClick = {
                        coroutineScope.launch {
                            if (pagerState.currentPage > 0) {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        }
                    },
                    onNextClick = {
                        coroutineScope.launch {
                            if (pagerState.currentPage < maxWeeks - 1) {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                    onSettingsClick = onSettingsClick,
                    onExportClick = onExportClick,
                    isNextSemester = isNextSemester,
                    onToggleSemester = onToggleSemester,
                    collapseFraction = headerCollapse,
                    sampleBackdrop = headerSampleBackdrop
                )
            }
        }
    ) { paddingValues ->
        when {
            isLoading && courses.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    GlassLoadingState(text = "正在同步课表…")
                }
            }

            else -> {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        // 内容捕获层挂在 pager 这个稳定节点上（不要挂进每一页）：
                        // 顶栏玻璃与芯片采样它，才能折射滚动中的网格与课程卡片。
                        .then(
                            if (contentBackdrop != null) {
                                Modifier.layerBackdrop(contentBackdrop)
                            } else {
                                Modifier
                            }
                        ),
                    beyondViewportPageCount = 0,
                    pageSpacing = 0.dp
                ) { page ->
                    val weekNumber = page + 1
                    ScheduleGrid(
                        courses = courses,
                        currentWeek = weekNumber,
                        periodTimes = periodTimes,
                        periodCount = periodCount,
                        onCourseClick = onCourseClick,
                        scrollState = gridScrollState,
                        // 【常量】而不是 paddingValues.calculateTopPadding()：后者随顶栏
                        // 一起收缩，而它施加在 verticalScroll 内部，于是顶栏每缩 1dp
                        // 内容就被额外上提 1dp——手指走 60dp、内容走 120dp。
                        topInset = statusBarHeight + HeaderExpandedHeight
                    )
                }
            }
        }
    }
}

@Composable
fun WeekHeaderCompact(
    currentWeek: Int,
    onPrevClick: () -> Unit,
    onNextClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    onExportClick: () -> Unit = {},
    isNextSemester: Boolean = false,
    onToggleSemester: () -> Unit = {},
    /** 0=未滚动（大标题直接浮在课表上）、1=已上划（收拢成一条悬浮玻璃）。 */
    collapseFraction: Float = 0f,
    /** 「壁纸 + 课表内容」的合成采样源。为空则退回无玻璃顶栏。 */
    sampleBackdrop: Backdrop? = null
) {
    val calendar = Calendar.getInstance()
    val currentDayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
    val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // CourseSelectorTheme 已根据系统模式与壁纸明暗选择配色，无需读取 GPU 像素。
    val titleColor = MaterialTheme.colorScheme.onSurface

    // 折叠全程跟手；高刚度临界阻尼弹簧只负责抹平快滚时的跳变（同 SystemTopBar）
    val collapse by animateFloatAsState(
        targetValue = collapseFraction.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
        label = "weekHeaderCollapse"
    )

    // 顶栏玻璃把自己的渲染结果导出到这一层，供板上的芯片二次采样，
    // 于是芯片折射「壁纸 + 滚动网格 + 顶栏玻璃」三者的合成——玻璃叠玻璃。
    val headerBackdrop = rememberLayerBackdrop()
    val chipBackdrop = if (sampleBackdrop != null) {
        rememberCombinedBackdrop(sampleBackdrop, headerBackdrop)
    } else {
        null
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 定高：这是"收起"的来源，也让悬浮条的几何是确定的
            .height(
                statusBarHeight +
                    lerpDp(HeaderExpandedHeight, HeaderCollapsedHeight, collapse)
            )
    ) {
        // 玻璃层：【必须是前景内容的兄弟节点，不能是它的父节点】。
        // layerBackdrop 捕获所在节点的整棵子树——挂在包含按钮的父节点上，
        // 按钮就会采样一个含有自己的图层，RenderThread 死循环直接 native 崩溃。
        // 同款写法见 SystemUi.kt 的 SystemDialog。
        //
        // 节点常驻、不用 if(collapse>0) 摘掉：一摘掉这一层就没有内容可导出，
        // 采样它的芯片会拿到空图层。强度全部由 collapse 调制。
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
                    cornerRadius = HeaderSlabCorner,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = statusBarHeight + HeaderSlabTopGap,
                            start = HeaderSlabInset,
                            end = HeaderSlabInset,
                            bottom = HeaderSlabBottomGap
                        )
                        .fillMaxHeight()
                )
            }
        }

        CompositionLocalProvider(LocalControlBackdrop provides chipBackdrop) {
            // 前景横向几何刻意保持不变（padding = PagePadding）：星期条要和网格的
            // 日期列对齐，塞进内缩 12dp 的玻璃条里就得反向补偿，多一处会飘的耦合。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(
                        top = lerpDp(HeaderTopPadExpanded, HeaderTopPadCollapsed, collapse)
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            lerpDp(HeaderActionRowExpanded, HeaderActionRowCollapsed, collapse)
                        )
                        .padding(horizontal = PagePadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 标题与分段控件同属一个纵列、芯片在右侧垂直居中——这是改动前
                    // 的构图。把芯片单独提到标题行会在分段控件右边空出一大块。
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(
                            lerpDp(HeaderTitleGap, 0.dp, collapse)
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 学期信息从分段控件"凝聚"进标题：控件缩掉的同时
                            // 「下学期 ·」从左侧挤出、把标题推向右，标题同时在缩字号。
                            // 一段连续形变，而不是字符串在某一帧突变。
                            SemesterTitlePrefix(
                                text = if (isNextSemester) "下学期 · " else "本学期 · ",
                                fontSize = 14.sp,
                                progress = ((collapse - 0.45f) / 0.55f).coerceIn(0f, 1f)
                            )
                            Text(
                                text = "第 $currentWeek 周",
                                fontSize = lerpSp(26f, 17f, collapse),
                                fontWeight = FontWeight.Bold,
                                color = titleColor,
                                letterSpacing = (-0.5).sp,
                                maxLines = 1
                            )
                        }

                        // 收拢方式：容器高度与内容缩放【同一个系数】，于是绘制尺寸
                        // 永远等于容器高度——既不会被裁出一条平边，也不会被压扁。
                        // 单独缩容器高度是上一版"文字挤出轨道"的成因：分段控件内部
                        // 写死 height(36.dp)，父约束一小它就被压扁。
                        val segmentFraction = (1f - collapse).coerceIn(0f, 1f)
                        Box(modifier = Modifier.height(HeaderSegmentHeight * segmentFraction)) {
                            SemesterCapsuleToggle(
                                isNextSemester = isNextSemester,
                                onClick = onToggleSemester,
                                modifier = Modifier.graphicsLayer {
                                    alpha = (segmentFraction * 2.2f - 0.2f).coerceIn(0f, 1f)
                                    scaleX = segmentFraction
                                    scaleY = segmentFraction
                                    transformOrigin = TransformOrigin(0f, 0f)
                                }
                            )
                        }
                    }

                    LiquidActionGroup(spacing = lerpDp(4.dp, 3.dp, collapse)) {
                        val buttonSize = lerpDp(34.dp, 30.dp, collapse)
                        val iconSize = lerpDp(16.dp, 15.dp, collapse)
                        action(
                            index = 0,
                            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "上一周",
                            onClick = onPrevClick,
                            enabled = currentWeek > 1,
                            buttonSize = buttonSize,
                            iconSize = iconSize
                        )
                        action(
                            index = 1,
                            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "下一周",
                            onClick = onNextClick,
                            enabled = currentWeek < 25,
                            buttonSize = buttonSize,
                            iconSize = iconSize
                        )
                        action(
                            index = 2,
                            icon = Icons.Default.Share,
                            contentDescription = "导出",
                            onClick = onExportClick,
                            buttonSize = buttonSize,
                            iconSize = iconSize
                        )
                        action(
                            index = 3,
                            icon = Icons.Default.Settings,
                            contentDescription = "设置",
                            onClick = onSettingsClick,
                            buttonSize = buttonSize,
                            iconSize = iconSize
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(lerpDp(HeaderWeekRowExpanded, HeaderWeekRowCollapsed, collapse))
                        .padding(horizontal = PagePadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(ScheduleTimeColumnWidth))
                    weekLabels.forEachIndexed { index, day ->
                        val isToday = index + 1 == currentDayOfWeek
                        CompactWeekdayLabel(
                            modifier = Modifier.weight(1f),
                            day = day,
                            isToday = isToday,
                            collapse = collapse
                        )
                    }
                }
            }
        }
    }
}

/**
 * 标题左侧的学期前缀。宽度按 progress 缩放，于是它是"挤出来"的而不是"闪出来"的。
 * 用 Modifier.layout 改上报宽度，而不是 animateContentSize：后者需要先测到目标宽度
 * 再补一帧动画，跟手的折叠里会慢半拍。
 */
@Composable
private fun SemesterTitlePrefix(
    text: String,
    fontSize: TextUnit,
    progress: Float
) {
    if (progress <= 0.001f) return
    Box(
        modifier = Modifier
            .graphicsLayer { alpha = progress }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val width = (placeable.width * progress).roundToInt()
                layout(width, placeable.height) { placeable.place(0, 0) }
            }
    ) {
        Text(
            text = text,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = (-0.2).sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun CompactWeekdayLabel(
    modifier: Modifier = Modifier,
    day: String,
    isToday: Boolean,
    collapse: Float = 0f
) {
    val textColor by animateColorAsState(
        targetValue = if (isToday) NeuPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "weekdayColor"
    )
    val dotScale by animateFloatAsState(
        targetValue = if (isToday) 1f else 0.65f,
        animationSpec = MotionSpring.gentle(),
        label = "weekdayDotScale"
    )
    // 折叠后只留今天那一点——"只留必要信息"落到最小的一处
    val dotColor = if (isToday) {
        NeuPrimary
    } else {
        NeuDivider.copy(alpha = NeuDivider.alpha * (1f - collapse))
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(lerpDp(3.dp, 2.dp, collapse))
    ) {
        Text(
            text = day,
            fontSize = lerpSp(13f, 11.5f, collapse),
            fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .scale(dotScale)
                .size(lerpDp(5.dp, 4.dp, collapse))
                .background(color = dotColor, shape = CircleShape)
        )
    }
}

@Composable
private fun SemesterCapsuleToggle(
    isNextSemester: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SystemCompactSegmentedControl(
        options = listOf("本学期", "下学期"),
        selectedIndex = if (isNextSemester) 1 else 0,
        onSelect = { selectedIndex ->
            val nextSemesterSelected = selectedIndex == 1
            if (nextSemesterSelected != isNextSemester) {
                onClick()
            }
        },
        // requiredHeight 无视父约束：容器在收拢过程中比 36dp 矮，
        // 普通 height 会被钳成压扁，轨道里的文字随之挤出。
        modifier = modifier
            .width(144.dp)
            .requiredHeight(HeaderSegmentHeight)
    )
}


@Composable
fun ScheduleGrid(
    courses: List<ScheduleCourseUi>,
    currentWeek: Int,
    periodTimes: List<PeriodTimeUi> = emptyList(),
    periodCount: Int = 12,
    onCourseClick: (ScheduleCourseUi) -> Unit,
    /**
     * scrollState 由调用方持有：顶栏的玻璃浓度要跟着它推导，而且所有 pager 页
     * 共用一个，左右切周时纵向位置不会跳回顶部。
     */
    scrollState: ScrollState = rememberScrollState(),
    /**
     * 顶栏高度。**施加在 verticalScroll 内部**——容器保持全出血，于是滚动位置 0
     * 时内容起始于顶栏之下，滚起来则从顶栏底下穿过，顶栏芯片才有东西可折射。
     * 加在容器上（`Modifier.padding(paddingValues)`）就成了"内容被推到顶栏下方"，
     * 芯片背后永远是空的。
     */
    topInset: Dp = 0.dp
) {
    val weeklyCourses = remember(courses, currentWeek) {
        courses.filter { isInWeek(it.weeks, currentWeek) }
    }
    val totalHeight = SchedulePeriodHeight * periodCount

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(
                start = PagePadding,
                end = PagePadding,
                top = topInset + 8.dp,
                bottom = com.tyust.course.ui.system.LocalAppOverlayBottomInset.current + 24.dp
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 去卡片化：轻玻璃衬底压到很低，让壁纸渐变与网格线透上来。
        // 这个 alpha 是"顶栏芯片能不能看出折射"的直接开关——衬底一厚，
        // 白芯片压在白板上，折射再准也是白压白。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.18f))
                .border(
                    0.5.dp,
                    Color.White.copy(alpha = 0.34f),
                    RoundedCornerShape(24.dp)
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalHeight)
            ) {
                Column(
                    modifier = Modifier
                        .width(ScheduleTimeColumnWidth)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.28f)),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    for (i in 1..periodCount) {
                        val periodTime = periodTimes.find { it.period == i }
                        Box(
                            modifier = Modifier
                                .height(SchedulePeriodHeight)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Column(
                                modifier = Modifier.padding(top = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(1.dp)
                            ) {
                                Text(
                                    text = i.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (periodTime != null) {
                                    Text(
                                        text = periodTime.startTime,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 9.sp,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = periodTime.endTime,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 9.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                // 时间列右侧阴影，柔和过渡
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    NeuDivider.copy(alpha = 0.10f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    TimetableBackground(
                        periodCount = periodCount,
                        periodHeight = SchedulePeriodHeight
                    )
                    TimetableLayout(
                        courses = weeklyCourses,
                        periodCount = periodCount,
                        periodHeight = SchedulePeriodHeight,
                        onCourseClick = onCourseClick
                    )

                    if (weeklyCourses.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            SystemEmptyState(
                                title = "本周暂无课程",
                                message = "可以切换周次、学期，或在设置中管理自定义课程。",
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }
                }
            }
        }

        if (weeklyCourses.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SystemStatusBadge(
                    text = "本周 ${weeklyCourses.size} 门课程",
                    tone = SystemTone.Info
                )
                if (weeklyCourses.any { it.isCustom }) {
                    SystemStatusBadge(
                        text = "含自定义课程",
                        tone = SystemTone.Warning
                    )
                }
            }
        }
    }
}

@Composable
private fun TimetableBackground(
    periodCount: Int,
    periodHeight: Dp
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 去斑马纹：透明列 + 极淡分隔线，壁纸从网格间透出
        Row(modifier = Modifier.fillMaxSize()) {
            repeat(7) { dayIndex ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    if (dayIndex < 6) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(NeuDivider.copy(alpha = 0.18f))
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            repeat(periodCount) { rowIndex ->
                Box(
                    modifier = Modifier
                        .height(periodHeight)
                        .fillMaxWidth()
                ) {
                    if (rowIndex < periodCount - 1) {
                        HorizontalDivider(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            color = NeuDivider.copy(alpha = 0.15f),
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TimetableLayout(
    courses: List<ScheduleCourseUi>,
    periodCount: Int = 12,
    periodHeight: Dp = SchedulePeriodHeight,
    modifier: Modifier = Modifier,
    onCourseClick: (ScheduleCourseUi) -> Unit
) {
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            courses.forEach { course ->
                CourseCard(course = course, onClick = { onCourseClick(course) })
            }
        }
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val columnWidth = width / 7f
        val compactThreshold = 50.dp.toPx()
        val ultraCompactThreshold = 42.dp.toPx()
        val cardInset = when {
            columnWidth < ultraCompactThreshold -> 1.dp.roundToPx()
            else -> 1.dp.roundToPx()
        }
        val pxPerPeriod = periodHeight.toPx()

        val placeables = measurables.mapIndexed { index, measurable ->
            val course = courses[index]
            val duration = course.endPeriod - course.startPeriod + 1
            val height = (duration * pxPerPeriod).roundToInt()
            val cardWidth = (columnWidth.roundToInt() - cardInset * 2).coerceAtLeast(1)
            val cardHeight = (height - cardInset * 2).coerceAtLeast(1)

            measurable.measure(
                constraints.copy(
                    minWidth = cardWidth,
                    maxWidth = cardWidth,
                    minHeight = cardHeight,
                    maxHeight = cardHeight
                )
            )
        }

        layout(width, (periodCount * pxPerPeriod).roundToInt()) {
            placeables.forEachIndexed { index, placeable ->
                val course = courses[index]
                val dayIndex = (course.day - 1).coerceIn(0, 6)
                val startPeriodIndex = (course.startPeriod - 1).coerceIn(0, periodCount - 1)

                val x = (dayIndex * columnWidth).roundToInt() + cardInset
                val y = (startPeriodIndex * pxPerPeriod).roundToInt() + cardInset

                placeable.place(x, y)
            }
        }
    }
}

@Composable
fun CourseCard(course: ScheduleCourseUi, onClick: () -> Unit) {
    val duration = (course.endPeriod - course.startPeriod + 1).coerceAtLeast(1)
    // 彩色半透玻璃 tile：加深填充保证壁纸上可读，边缘细亮线似透镜
    val containerColor = if (course.isCustom) course.color.copy(alpha = 0.42f) else course.color.copy(alpha = 0.32f)
    val borderColor = course.color.copy(alpha = 0.50f)
    val accentColor = course.color.copy(alpha = 0.85f)

    val nameFontSize = when {
        duration == 1 -> 10.5.sp
        duration == 2 -> 11.sp
        else -> 11.5.sp
    }
    val locationFontSize = when {
        duration <= 2 -> 9.sp
        else -> 9.5.sp
    }

    val displayLocation = course.location

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = MotionSpring.gentle(),
        label = "courseCardScale"
    )

    Surface(
        modifier = Modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            ),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(0.6.dp, borderColor)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 顶部玻璃高光渐变：模拟光源照射的反射
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(LocalDensity.current) { (duration * 20).dp })
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.35f),
                                Color.White.copy(alpha = 0.05f),
                                Color.Transparent
                            )
                        )
                    )
            )
            // 左侧彩色指示条：课程颜色标识
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                accentColor,
                                accentColor.copy(alpha = 0.3f)
                            )
                        )
                    )
            )
            // 内容区域
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 5.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = nameFontSize,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = (nameFontSize.value + 1.5).sp,
                    letterSpacing = (-0.2).sp
                )

                if (displayLocation.isNotBlank()) {
                    Text(
                        text = displayLocation,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                        fontWeight = FontWeight.Normal,
                        fontSize = locationFontSize,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = (locationFontSize.value + 1.5).sp
                    )
                }
            }
        }
    }
}

internal fun isInWeek(weeks: String?, week: Int): Boolean {
    if (weeks.isNullOrEmpty()) return true

    // 按逗号分段，每段独立解析周次范围和奇偶约束
    // 例: "1-4周,6-14周(双),15-16周" → ["1-4周", "6-14周(双)", "15-16周"]
    val segments = weeks.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }

    return segments.any { segment ->
        parseWeekSegment(segment, week)
    }
}

/**
 * 解析单个周次段，判断目标周是否匹配。
 * 支持格式:
 *   - "1-16周"        → 第1~16周
 *   - "1-13周,16周"   → 第1~13周 + 第16周
 *   - "6-14周(双)"    → 第6~14周中的偶数周
 *   - "1-16周(单)"    → 第1~16周中的奇数周
 *   - "16周"          → 仅第16周
 *   - "1-2"           → 无"周"后缀也兼容
 */
private fun parseWeekSegment(segment: String, week: Int): Boolean {
    val s = segment.trim()
    if (s.isEmpty()) return true

    // 提取奇偶约束: (单) (双) （单） （双）
    val hasOdd = s.contains("单")
    val hasEven = s.contains("双")

    // 清理: 去掉 "周"、括号、奇偶标记，只留数字和分隔符
    val cleaned = s
        .replace("周", "")
        .replace("单", "")
        .replace("双", "")
        .replace("(", "")
        .replace(")", "")
        .replace("（", "")
        .replace("）", "")
        .trim()

    if (cleaned.isEmpty()) return true

    // 解析范围: "1-13" 或 "16"
    val range = cleaned.split("-")
    val start: Int
    val end: Int
    try {
        start = range[0].trim().toInt()
        end = if (range.size > 1) {
            range.last().trim().replace(Regex("[^0-9]"), "").toInt()
        } else {
            start
        }
    } catch (_: Exception) {
        return true // 解析失败时保守返回 true，避免漏课
    }

    // 先检查是否在范围内
    if (week !in start..end) return false

    // 再检查奇偶约束（仅对该段生效）
    if (hasOdd && week % 2 == 0) return false
    if (hasEven && week % 2 == 1) return false

    return true
}

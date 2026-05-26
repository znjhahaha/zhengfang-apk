package com.tyust.course.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemEmptyState
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import com.tyust.course.ui.system.SystemDivider
import com.tyust.course.ui.system.neumorphicShadow

import com.tyust.course.ui.theme.MotionSpring
import com.tyust.course.ui.theme.NeuDivider
import com.tyust.course.ui.theme.NeuInsetBackground
import com.tyust.course.ui.theme.NeuPrimary
import com.tyust.course.ui.theme.NeuSurface

import java.util.Calendar
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val ScheduleTimeColumnWidth = 34.dp
private val SchedulePeriodHeight = 84.dp

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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
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
                onToggleSemester = onToggleSemester
            )
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
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = NeuPrimary, trackColor = NeuInsetBackground)
                        Text(
                            text = "正在同步课表…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            else -> {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    beyondBoundsPageCount = 0,
                    pageSpacing = 0.dp
                ) { page ->
                    val weekNumber = page + 1
                    ScheduleGrid(
                        courses = courses,
                        currentWeek = weekNumber,
                        periodTimes = periodTimes,
                        periodCount = periodCount,
                        onCourseClick = onCourseClick
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
    onToggleSemester: () -> Unit = {}
) {
    val calendar = Calendar.getInstance()
    val currentDayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
    val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")

    Surface(
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = PagePadding, end = PagePadding, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "第 $currentWeek 周",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            letterSpacing = (-0.5).sp
                        )
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isNextSemester) NeuPrimary.copy(alpha = 0.15f) else NeuInsetBackground,
                            contentColor = if (isNextSemester) NeuPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Text(
                                text = if (isNextSemester) "下学期" else "本学期",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WeekControlButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上一周",
                        onClick = onPrevClick,
                        enabled = currentWeek > 1
                    )
                    WeekControlButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下一周",
                        onClick = onNextClick,
                        enabled = currentWeek < 25
                    )
                    WeekControlButton(
                        icon = Icons.Default.SwapHoriz,
                        contentDescription = "切换学期",
                        onClick = onToggleSemester,
                        enabled = true
                    )
                    WeekControlButton(
                        icon = Icons.Default.Share,
                        contentDescription = "导出",
                        onClick = onExportClick,
                        enabled = true
                    )
                    WeekControlButton(
                        icon = Icons.Default.Settings,
                        contentDescription = "设置",
                        onClick = onSettingsClick,
                        enabled = true
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PagePadding)
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(ScheduleTimeColumnWidth))
                weekLabels.forEachIndexed { index, day ->
                    val isToday = index + 1 == currentDayOfWeek
                    CompactWeekdayLabel(
                        modifier = Modifier.weight(1f),
                        day = day,
                        isToday = isToday
                    )
                }
            }

            SystemDivider(alpha = 0.8f)
        }
    }
}

@Composable
private fun CompactWeekdayLabel(
    modifier: Modifier = Modifier,
    day: String,
    isToday: Boolean
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

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = day,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor
        )
        Box(
            modifier = Modifier
                .scale(dotScale)
                .size(5.dp)
                .background(
                    color = if (isToday) NeuPrimary else NeuDivider,
                    shape = CircleShape
                )
        )
    }
}

@Composable
private fun WeekControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = if (enabled) NeuSurface else NeuInsetBackground,
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .then(if (enabled) Modifier.neumorphicShadow(cornerRadius = 12.dp, elevation = 3.dp) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

@Composable
fun ScheduleGrid(
    courses: List<ScheduleCourseUi>,
    currentWeek: Int,
    periodTimes: List<PeriodTimeUi> = emptyList(),
    periodCount: Int = 12,
    onCourseClick: (ScheduleCourseUi) -> Unit
) {
    val scrollState = rememberScrollState()
    val weeklyCourses = remember(courses, currentWeek) {
        courses.filter { isInWeek(it.weeks, currentWeek) }
    }
    val totalHeight = SchedulePeriodHeight * periodCount

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = PagePadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SystemCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaterialTheme.colorScheme.surface,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            contentPadding = PaddingValues(0.dp)
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
                        .background(NeuInsetBackground.copy(alpha = 0.70f)),
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
        Row(modifier = Modifier.fillMaxSize()) {
            repeat(7) { dayIndex ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            if (dayIndex % 2 == 0) NeuSurface
                            else NeuInsetBackground.copy(alpha = 0.35f)
                        )
                ) {
                    if (dayIndex < 6) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(NeuDivider.copy(alpha = 0.15f))
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
            columnWidth < compactThreshold -> 2.dp.roundToPx()
            else -> 3.dp.roundToPx()
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
    // 新拟态玻璃风格：更柔和的半透明填充 + 顶部高光
    val containerColor = if (course.isCustom) course.color.copy(alpha = 0.28f) else course.color.copy(alpha = 0.18f)
    val borderColor = course.color.copy(alpha = 0.35f)
    val accentColor = course.color.copy(alpha = 0.70f)

    val nameFontSize = when {
        duration == 1 -> 10.sp
        duration == 2 -> 10.5.sp
        else -> 11.sp
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
        shape = RoundedCornerShape(8.dp),
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
                    .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = nameFontSize,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = (nameFontSize.value + 2).sp,
                    letterSpacing = (-0.2).sp
                )

                if (displayLocation.isNotBlank()) {
                    Text(
                        text = displayLocation,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                        fontWeight = FontWeight.Normal,
                        fontSize = locationFontSize,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = (locationFontSize.value + 2).sp
                    )
                }
            }
        }
    }
}

internal fun isInWeek(weeks: String?, week: Int): Boolean {
    if (weeks.isNullOrEmpty()) return true

    val isOddOnly = weeks.contains("单")
    val isEvenOnly = weeks.contains("双")

    if (isOddOnly && week % 2 == 0) return false
    if (isEvenOnly && week % 2 == 1) return false

    val cleanWeeks =
        weeks.replace("周", "")
            .replace("单", "")
            .replace("双", "")
            .replace("(", "")
            .replace(")", "")
            .replace("（", "")
            .replace("）", "")
            .trim()

    return try {
        when {
            cleanWeeks.contains("-") -> {
                val parts = cleanWeeks.split("-")
                val start = parts[0].trim().toInt()
                val end = parts[1].trim().replace("[^0-9]".toRegex(), "").toInt()
                week in start..end
            }
            cleanWeeks.contains(",") -> {
                cleanWeeks.split(",").any {
                    it.trim().replace("[^0-9]".toRegex(), "").toIntOrNull() == week
                }
            }
            cleanWeeks.isNotEmpty() -> {
                cleanWeeks.replace("[^0-9]".toRegex(), "").toIntOrNull() == week
            }
            else -> true
        }
    } catch (e: Exception) {
        true
    }
}

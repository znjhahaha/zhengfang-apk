package com.tyust.course.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.ui.theme.PrimaryPurple
import java.util.Calendar
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

data class ScheduleCourseUi(
        val name: String,
        val teacher: String,
        val location: String,
        val day: Int, // 1-7
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

    val pagerState =
            rememberPagerState(
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
            // 使用 scrollToPage 而不是 animateScrollToPage 避免初始化时的偏移动画
            pagerState.scrollToPage(targetPage)
        }
    }

    Scaffold(
            topBar = {
                WeekHeader(
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
            },
            containerColor = Color(0xFFF5F5F7)
    ) { paddingValues ->
        if (isLoading) {
            Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = PrimaryPurple) }
        } else {
            HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    beyondBoundsPageCount = 0,  // 防止预加载导致初次渲染时的布局偏移
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

@Composable
fun WeekHeader(
        currentWeek: Int,
        onPrevClick: () -> Unit,
        onNextClick: () -> Unit,
        onSettingsClick: () -> Unit = {},
        onExportClick: () -> Unit = {},
        isNextSemester: Boolean = false,
        onToggleSemester: () -> Unit = {}
) {
    Surface(
            color = Color.White,
            shadowElevation = 8.dp,
            shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            // Top Bar Row
            Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier.size(40.dp).background(Color(0xFFF0F0F0), CircleShape)
                ) {
                    Icon(
                            Icons.Default.Settings,
                            contentDescription = "设置",
                            tint = PrimaryPurple,
                            modifier = Modifier.size(20.dp)
                    )
                }

                Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                                Modifier.background(Color(0xFFF5F5F7), RoundedCornerShape(20.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    IconButton(
                            onClick = onPrevClick,
                            enabled = currentWeek > 1,
                            modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                                Icons.Default.ArrowBackIosNew,
                                contentDescription = "上一周",
                                modifier = Modifier.size(16.dp),
                                tint = if (currentWeek > 1) Color.Black else Color.LightGray
                        )
                    }

                    Text(
                            text = "第 ${currentWeek} 周",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    IconButton(
                            onClick = onNextClick,
                            enabled = currentWeek < 25,
                            modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                                Icons.Default.ArrowForwardIos,
                                contentDescription = "下一周",
                                modifier = Modifier.size(16.dp),
                                tint = if (currentWeek < 25) Color.Black else Color.LightGray
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                            onClick = onExportClick,
                            modifier =
                                    Modifier.size(40.dp).background(Color(0xFFF0F0F0), CircleShape)
                    ) {
                        Icon(
                                Icons.Default.Share,
                                contentDescription = "导出",
                                tint = PrimaryPurple,
                                modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                            onClick = onToggleSemester,
                            modifier = Modifier.size(40.dp).background(if(isNextSemester) PrimaryPurple else Color(0xFFF0F0F0), CircleShape)
                    ) {
                        Text(
                            text = if(isNextSemester) "下" else "本",
                            color = if(isNextSemester) Color.White else PrimaryPurple,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Date Strip
            val calendar = Calendar.getInstance()
            val currentDayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1 // Mon=1, Sun=7

            Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
            ) {
                // Time column placeholder
                Spacer(modifier = Modifier.width(48.dp))

                val weeks = listOf("一", "二", "三", "四", "五", "六", "日")
                weeks.forEachIndexed { index, day ->
                    val isToday = (index + 1) == currentDayOfWeek

                    Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                                text = day,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = if (isToday) PrimaryPurple else Color.Gray
                        )
                        if (isToday) {
                            Box(
                                    modifier =
                                            Modifier.padding(top = 4.dp)
                                                    .size(4.dp)
                                                    .background(PrimaryPurple, CircleShape)
                            )
                        }
                    }
                }
            }
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

    val weeklyCourses =
            remember(courses, currentWeek) { courses.filter { isInWeek(it.weeks, currentWeek) } }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState).padding(top = 8.dp, bottom = 24.dp)) {
        // 使用固定高度而不是 IntrinsicSize.Min，避免初次渲染时高度计算不一致
        val periodHeight = 80.dp
        val totalHeight = periodHeight * periodCount

        Row(modifier = Modifier.fillMaxWidth().height(totalHeight)) {
            // Time Column
            Column(
                    modifier = Modifier.width(48.dp).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
            ) {
                for (i in 1..periodCount) {
                    val periodTime = periodTimes.find { it.period == i }
                    Box(
                            modifier = Modifier.height(periodHeight).fillMaxWidth(),
                            contentAlignment = Alignment.TopCenter
                    ) {
                        Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text(
                                    text = i.toString(),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                            )
                            if (periodTime != null) {
                                Text(
                                        text = periodTime.startTime,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray,
                                        fontSize = 9.sp
                                )
                            }
                        }
                    }
                }
            }

            // Grid Content
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                TimetableLayout(
                        courses = weeklyCourses,
                        periodCount = periodCount,
                        periodHeight = periodHeight,
                        onCourseClick = onCourseClick
                )
            }
        }
    }
}

@Composable
fun TimetableLayout(
        courses: List<ScheduleCourseUi>,
        periodCount: Int = 12,
        periodHeight: Dp = 80.dp,
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
        val columnWidth = width / 7
        val pxPerPeriod = periodHeight.toPx()

        val placeables =
                measurables.mapIndexed { index, measurable ->
                    val course = courses[index]
                    val duration = course.endPeriod - course.startPeriod + 1
                    val height = (duration * pxPerPeriod).roundToInt()

                    measurable.measure(
                            constraints.copy(
                                    minWidth = columnWidth - 2,
                                    maxWidth = columnWidth - 2,
                                    minHeight = height - 2,
                                    maxHeight = height - 2
                            )
                    )
                }

        layout(width, (periodCount * pxPerPeriod).roundToInt()) {
            placeables.forEachIndexed { index, placeable ->
                val course = courses[index]
                val dayIndex = (course.day - 1).coerceIn(0, 6)
                val startPeriodIndex = (course.startPeriod - 1).coerceIn(0, periodCount - 1)

                // Minimal offset
                val x = dayIndex * columnWidth + 1
                val y = (startPeriodIndex * pxPerPeriod).roundToInt() + 1

                placeable.place(x, y)
            }
        }
    }
}

@Composable
fun CourseCard(course: ScheduleCourseUi, onClick: () -> Unit) {
    Surface(
            modifier =
                    Modifier.clickable(onClick = onClick)
                            .shadow(
                                    1.dp,
                                    RoundedCornerShape(4.dp)
                            ), // Reduced corner radius for more space
            shape = RoundedCornerShape(4.dp),
            color = course.color,
    ) {
        Column(
                modifier =
                        Modifier.fillMaxSize()
                                .padding(
                                        horizontal = 1.dp,
                                        vertical = 2.dp
                                ), // Ultra minimal padding
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                    text = course.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 6, // Allow even more lines
                    minLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    fontSize = 9.sp, // Ultra compact
                    lineHeight = 9.sp,
                    letterSpacing = (-0.3).sp // Squeeze characters
            )
            if (course.location.isNotEmpty()) {
                // Removed Spacer to save vertical space
                Text(
                        text = "@${course.location}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.95f),
                        fontSize = 8.sp, // Ultra compact
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        lineHeight = 8.sp,
                        letterSpacing = (-0.3).sp
                )
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

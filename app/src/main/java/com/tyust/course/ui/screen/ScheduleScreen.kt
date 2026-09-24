package com.tyust.course.ui.screen

import com.tyust.course.ui.theme.moduleEntrance

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
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
import com.tyust.course.ui.system.rememberScreenMetrics
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
import kotlin.math.ceil
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.repeatOnLifecycle
import com.tyust.course.schedule.ScheduleAgenda
import com.tyust.course.schedule.ScheduleDisplayPreferences
import com.tyust.course.schedule.ScheduleTimeBase
import com.tyust.course.schedule.ScheduleViewPosition

private val ScheduleTimeColumnWidth = 40.dp
internal val ScheduleTimeColumnShadowWidth = 8.dp
private val SchedulePeriodHeight = 84.dp

/** 窄屏收窄的时间列与网格左右留白，把省下的宽度全给七个日列。 */
private val ScheduleTimeColumnWidthTight = 40.dp
private val SchedulePeriodHeightTight = 68.dp
private val ScheduleGridPaddingTight = 10.dp

/**
 * 网格的横向几何。**星期条与网格必须调用同一对函数**——星期标签靠
 * 「相同的左右留白 + 相同宽度的时间列占位」才和日期列对齐（见 `WeekHeaderCompact`
 * 里那条注释），两边取不同的值就会整体错位。
 */
@Composable
internal fun scheduleGridPadding(): Dp =
    rememberScreenMetrics().wide(PagePadding, ScheduleGridPaddingTight)

@Composable
internal fun scheduleTimeColumnWidth(): Dp =
    rememberScreenMetrics().wide(ScheduleTimeColumnWidth, ScheduleTimeColumnWidthTight)

/** 单节课的行高。短屏收到 68dp，一屏能多看一节多。 */
@Composable
private fun schedulePeriodHeight(): Dp =
    rememberScreenMetrics().tall(SchedulePeriodHeight, SchedulePeriodHeightTight)

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
    val customId: String = "",
    val sourceId: String = "",
    val id: String = if (isCustom) "custom:$customId" else com.tyust.course.schedule.ScheduleIdentity.network(
        sourceId, name, teacher, day, startPeriod, endPeriod, weeks, location
    ),
    val hasConflict: Boolean = false,
    val isCurrent: Boolean = false,
    val isNext: Boolean = false
) {
    fun record() = com.tyust.course.schedule.ScheduleCourseRecord(id, name, teacher, location, day, startPeriod, endPeriod, weeks, isCustom)
}

data class PeriodTimeUi(val period: Int, val startTime: String, val endTime: String)

@Composable
fun ScheduleGrid(
    courses: List<ScheduleCourseUi>,
    currentWeek: Int,
    periodTimes: List<PeriodTimeUi> = emptyList(),
    periodCount: Int = 12,
    onCourseClick: (ScheduleCourseUi) -> Unit,
    /**
     * 每个 pager 页独立持有；调用方在切周时传递位置，避免相邻页测量相互夹取滚动范围。
     */
    scrollState: ScrollState = rememberScrollState(),
    /**
     * 顶栏高度。**施加在 verticalScroll 内部**——容器保持全出血，于是滚动位置 0
     * 时内容起始于顶栏之下，滚起来则从顶栏底下穿过，顶栏芯片才有东西可折射。
     * 加在容器上（`Modifier.padding(paddingValues)`）就成了"内容被推到顶栏下方"，
     * 芯片背后永远是空的。
     */
    topInset: Dp = 0.dp,
    showWeekend: Boolean = true,
    compact: Boolean = false,
    beforeGrid: @Composable () -> Unit = {},
    onCourseLongClick: (ScheduleCourseUi) -> Unit = {}
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val weeklyCourses = remember(courses, currentWeek) {
        courses.filter { isInWeek(it.weeks, currentWeek) }
    }
    val days = if (showWeekend) 7 else 5
    val visibleCourses = remember(weeklyCourses, days) { weeklyCourses.filter { it.day in 1..days } }
    val timeColumnWidth = scheduleTimeColumnWidth()
    val gridPadding = scheduleGridPadding()
    val density = LocalDensity.current
    val columnWidth = with(density) {
        ((constraints.maxWidth - gridPadding.roundToPx() * 2 - timeColumnWidth.roundToPx() -
            ScheduleTimeColumnShadowWidth.roundToPx()) / days.toFloat()).roundToInt()
    }
    // Measure the whole timetable, not just this week's cards. Paging and live
    // status changes keep one geometry while names and teachers remain complete.
    val periodHeight = rememberFullCoursePeriodHeight(courses, columnWidth,
        maxOf(schedulePeriodHeight() * if (compact) 0.85f else 1f,
            (if (compact) 70.dp else 76.dp) * density.fontScale.coerceAtLeast(1f)))
    val anchor = remember(scrollState) { GridScrollAnchor() }
    val periodPixels = with(density) { periodHeight.toPx() }
    val contentTop = with(density) { (topInset + 8.dp).toPx() }
    val totalHeight = periodHeight * periodCount
    val darkGrid = com.tyust.course.ui.system.rememberGlassDarkTheme()
    val gridTint = MaterialTheme.colorScheme.surface.copy(alpha =
        if (darkGrid || com.tyust.course.manager.AppearanceSettingsManager.mode != com.tyust.course.manager.WallpaperMode.Preset) 0.86f else 0.18f)
    val timeColumnTint = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (darkGrid) 0.4f else 0.28f)

    Column(
        modifier = Modifier
            .testTag("schedule-grid-$currentWeek")
            .fillMaxSize()
            .verticalScroll(scrollState, overscrollEffect = null)
            .onGloballyPositioned {
                // A genuine metadata/font change may need more room. Preserve the
                // visible teaching period (or bottom edge) during the layout pass.
                if (anchor.period > 0f && kotlin.math.abs(anchor.period - periodPixels) > 0.5f) {
                    val target = if (anchor.maximum > 0 && anchor.offset >= anchor.maximum - 2) scrollState.maxValue.toFloat()
                        else if (anchor.offset <= anchor.top) anchor.offset.toFloat()
                        else contentTop + (anchor.offset - anchor.top) / anchor.period * periodPixels
                    scrollState.dispatchRawDelta(target.coerceIn(0f, scrollState.maxValue.toFloat()) - scrollState.value)
                }
                anchor.period = periodPixels; anchor.top = contentTop
                anchor.maximum = scrollState.maxValue; anchor.offset = scrollState.value
            }
            .padding(
                start = gridPadding,
                end = gridPadding,
                top = topInset + 8.dp,
                bottom = com.tyust.course.ui.system.LocalAppOverlayBottomInset.current + 24.dp
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        beforeGrid()
        // 去卡片化：轻玻璃衬底压到很低，让壁纸渐变与网格线透上来。
        // 这个 alpha 是"顶栏芯片能不能看出折射"的直接开关——衬底一厚，
        // 白芯片压在白板上，折射再准也是白压白。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(gridTint)
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
                        .width(timeColumnWidth)
                        .fillMaxHeight()
                        .background(timeColumnTint),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    for (i in 1..periodCount) {
                        val periodTime = periodTimes.find { it.period == i }
                        Box(
                            modifier = Modifier
                                .height(periodHeight)
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
                        .width(ScheduleTimeColumnShadowWidth)
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
                        periodHeight = periodHeight,
                        dayCount = days
                    )
                    TimetableLayout(
                        courses = visibleCourses,
                        periodCount = periodCount,
                        periodHeight = periodHeight,
                        dayCount = days,
                        periodTimes = periodTimes,
                        onCourseClick = onCourseClick,
                        onCourseLongClick = onCourseLongClick
                    )

                    if (visibleCourses.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            SystemEmptyState(
                                title = "本周暂无课程",
                                message = "",
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }
                }
            }
        }

            Row(
                modifier = Modifier.fillMaxWidth().height(32.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                com.tyust.course.ui.system.AnimatedNumberText("本周 ${weeklyCourses.size} 门课程",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                if (weeklyCourses.any { it.isCustom }) {
                    SystemStatusBadge(
                        text = "含自定义课程",
                        tone = SystemTone.Warning
                    )
                }
            }
        if (!showWeekend) Box(Modifier.fillMaxWidth().height(24.dp * LocalDensity.current.fontScale)) {
            if (weeklyCourses.any { it.day > 5 }) Text(
                "另有 ${weeklyCourses.count { it.day > 5 }} 节周末课程，可在日视图查看",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    }
}

private fun courseNameStyle(base: TextStyle, duration: Int): TextStyle = base.copy(
    fontWeight = FontWeight.Bold,
    fontSize = when (duration) { 1 -> 10.5.sp; 2 -> 11.sp; else -> 11.5.sp },
    lineHeight = when (duration) { 1 -> 12.sp; 2 -> 12.5.sp; else -> 13.sp },
    letterSpacing = (-0.2).sp
)

private fun courseLocationStyle(base: TextStyle, duration: Int): TextStyle = base.copy(
    fontWeight = FontWeight.Normal,
    fontSize = if (duration <= 2) 9.sp else 9.5.sp,
    lineHeight = if (duration <= 2) 10.5.sp else 11.sp
)

private class GridScrollAnchor {
    var period = 0f
    var top = 0f
    var maximum = 0
    var offset = 0
}

@Composable
private fun rememberFullCoursePeriodHeight(courses: List<ScheduleCourseUi>, columnWidth: Int, minimum: Dp): Dp {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 128)
    val base = androidx.compose.material3.LocalTextStyle.current.merge(MaterialTheme.typography.labelSmall)
    return remember(courses, columnWidth, minimum, density, measurer, base) {
        with(density) {
            val textWidth = (columnWidth - 2 * 1.dp.roundToPx() - 5.dp.roundToPx() - 2.dp.roundToPx() - 2).coerceAtLeast(1)
            var height = minimum.toPx()
            courses.forEach { course ->
                val duration = (course.endPeriod - course.startPeriod + 1).coerceAtLeast(1)
                val name = measurer.measure(course.name, courseNameStyle(base, duration), constraints = Constraints(maxWidth = textWidth)).size.height
                val metadata = courseLocationStyle(base, duration)
                val teacher = measurer.measure(course.teacher.ifBlank { "教师待定" }, metadata, constraints = Constraints(maxWidth = textWidth)).size.height
                val room = measurer.measure("教室\nA1208", metadata).size.height
                // Keep the optional status lane allocated, including on minute ticks.
                val required = name + teacher + room + 25.dp.toPx()
                height = maxOf(height, kotlin.math.ceil(required / duration))
            }
            kotlin.math.ceil(height).toDp()
        }
    }
}

private fun ScheduleCourseUi.hasCardStatus() = hasConflict || isCurrent || isNext ||
    !com.tyust.course.schedule.ScheduleWeeks.parse(weeks).valid

@Composable
private fun TimetableBackground(
    periodCount: Int,
    periodHeight: Dp,
    dayCount: Int = 7
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 去斑马纹：透明列 + 极淡分隔线，壁纸从网格间透出
        Row(modifier = Modifier.fillMaxSize()) {
            repeat(dayCount) { dayIndex ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    if (dayIndex < dayCount - 1) {
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
    dayCount: Int = 7,
    periodTimes: List<PeriodTimeUi> = emptyList(),
    onCourseClick: (ScheduleCourseUi) -> Unit,
    onCourseLongClick: (ScheduleCourseUi) -> Unit = {}
) {
    val entries = remember(courses, dayCount) { timetableEntries(courses.filter { it.day in 1..dayCount }) }
    var selectedGroup by remember(courses) { mutableStateOf<List<ScheduleCourseUi>?>(null) }
    selectedGroup?.let { group ->
        com.tyust.course.ui.system.SystemDialog(onDismissRequest = { selectedGroup = null }, title = { Text("重叠课程") }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                group.forEach { course ->
                    ScheduleDayCourse(course, periodTimes, compact = false,
                        onClick = { selectedGroup = null; onCourseClick(course) },
                        onLongClick = { selectedGroup = null; onCourseLongClick(course) })
                }
            }
        }
    }
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            entries.forEach { entry ->
                androidx.compose.runtime.key(entry.display.id) { CourseCard(course = entry.display, onClick = {
                    if (entry.courses.size == 1) onCourseClick(entry.courses.first()) else selectedGroup = entry.courses
                }, onLongClick = {
                    if (entry.courses.size == 1) onCourseLongClick(entry.courses.first()) else selectedGroup = entry.courses
                }) }
            }
        }
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val columnWidth = width / dayCount.toFloat()
        val cardInset = 1.dp.roundToPx()
        val pxPerPeriod = periodHeight.toPx()

        val placeables = measurables.mapIndexed { index, measurable ->
            val course = entries[index].display
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
                val course = entries[index].display
                val dayIndex = (course.day - 1).coerceIn(0, dayCount - 1)
                val startPeriodIndex = (course.startPeriod - 1).coerceIn(0, periodCount - 1)

                val x = (dayIndex * columnWidth).roundToInt() + cardInset
                val y = (startPeriodIndex * pxPerPeriod).roundToInt() + cardInset

                placeable.place(x, y)
            }
        }
    }
}

private data class TimetableEntry(val display: ScheduleCourseUi, val courses: List<ScheduleCourseUi>)

private fun timetableEntries(courses: List<ScheduleCourseUi>): List<TimetableEntry> {
    val byId = courses.associateBy { it.id }
    return com.tyust.course.schedule.scheduleOverlapGroups(courses.map { it.record() }).map { group ->
        val originals = group.courses.map { byId.getValue(it.id) }
        val first = originals.first()
        val display = if (originals.size == 1) first else first.copy(
            id = "overlap:" + com.tyust.course.schedule.ScheduleIdentity.digest(originals.joinToString { it.id }),
            name = first.name, location = "${originals.size} 门重叠 · 点按查看",
            startPeriod = group.start, endPeriod = group.end, hasConflict = true,
            isCurrent = originals.any { it.isCurrent })
        TimetableEntry(display, originals)
    }
}

@Composable
fun CourseCard(course: ScheduleCourseUi, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    val darkCard = com.tyust.course.ui.system.rememberGlassDarkTheme()
    val focusRequester = remember { FocusRequester() }
    val focusRegistry = LocalScheduleFocus.current
    var cardBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val unknownWeeks = remember(course.weeks) { !com.tyust.course.schedule.ScheduleWeeks.parse(course.weeks).valid }
    DisposableEffect(course.id, focusRegistry) {
        focusRegistry?.register(course.id, focusRequester)
        onDispose { focusRegistry?.remove(course.id, focusRequester) }
    }
    val duration = (course.endPeriod - course.startPeriod + 1).coerceAtLeast(1)
    // 彩色半透玻璃 tile：加深填充保证壁纸上可读，边缘细亮线似透镜
    val containerColor = scheduleCardColor(course.color, if (course.isCurrent) 0.20f else 0.10f)
    val accentColor = course.color.copy(alpha = 0.85f)

    val displayLocation = com.tyust.course.schedule.ScheduleLocation.compact(course.location)

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !com.tyust.course.ui.system.rememberGlassAccessibilityMode().reduceMotion) 0.97f else 1f,
        animationSpec = androidx.compose.animation.core.tween(com.tyust.course.ui.theme.MotionDuration.Fast),
        label = "courseCardScale"
    )

    Surface(
        modifier = Modifier
            .testTag("schedule-course-${course.id}")
            .focusRequester(focusRequester)
            .onGloballyPositioned {
                cardBounds = it.boundsInWindow()
                focusRegistry?.place(course.id, it.boundsInWindow())
            }
            .semantics {
                stateDescription = listOfNotNull(if (course.hasConflict) "时间冲突" else null,
                    if (course.isCurrent) "正在上课" else null, if (course.isCustom) "自定义课程" else null,
                    if (unknownWeeks) "周次待核对" else null).joinToString("，")
            }
            .scale(scale)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onLongClickLabel = "课程快捷操作",
                onLongClick = onLongClick,
                onClick = {
                    // A course can also exist in the pager's adjacent week. The clicked copy
                    // owns the opening origin and the focus returned after dismissal.
                    focusRegistry?.register(course.id, focusRequester)
                    cardBounds?.let { focusRegistry?.place(course.id, it) }
                    onClick()
                }
            ),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = scheduleCardBorder(course.color, course.isCurrent || course.isNext)
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
                                Color.White.copy(alpha = if (darkCard) 0.08f else 0.35f),
                                Color.White.copy(alpha = if (darkCard) 0.02f else 0.05f),
                                Color.Transparent
                            )
                        )
                    )
            )
            // 左侧彩色指示条：课程颜色标识
            Box(
                modifier = Modifier
                    .width((3f + (1f - scale) * 50f).dp)
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
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = course.name,
                    modifier = Modifier.fillMaxWidth().testTag("schedule-name-${course.id}"),
                    style = courseNameStyle(MaterialTheme.typography.labelSmall, duration),
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = true,
                )

                Text(course.teacher.ifBlank { "教师待定" },
                    Modifier.fillMaxWidth().testTag("schedule-teacher-${course.id}"),
                    style = courseLocationStyle(MaterialTheme.typography.labelSmall, duration),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, softWrap = true)

                if (displayLocation.isNotBlank()) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                    val baseStyle = courseLocationStyle(MaterialTheme.typography.labelSmall, duration)
                    val room = com.tyust.course.schedule.ScheduleLocation.room(course.location).orEmpty()
                    val density = LocalDensity.current
                    // Leave a pixel rounding margin, and remeasure after scaling: modern
                    // Android font scaling is nonlinear, so a width ratio alone can wrap the room.
                    val availableWidth = (constraints.maxWidth - 2).coerceAtLeast(1).toFloat()
                    val locationStyle = remember(room, baseStyle, availableWidth, density) {
                        val roomWidth = measurer.measure(room, baseStyle, softWrap = false).size.width
                        var fitted = baseStyle.copy(fontSize = baseStyle.fontSize * minOf(1f, availableWidth / roomWidth.coerceAtLeast(1)))
                        repeat(8) {
                            if (measurer.measure(room, fitted, softWrap = false).size.width <= availableWidth) return@remember fitted
                            fitted = fitted.copy(fontSize = fitted.fontSize * 0.92f)
                        }
                        fitted
                    }
                    val label = remember(displayLocation, availableWidth, locationStyle, density) {
                        com.tyust.course.schedule.ScheduleLocation.fit(course.location, availableWidth, 2) {
                            measurer.measure(it, locationStyle, softWrap = false).size.width.toFloat()
                        }
                    }
                    Text(
                        text = label,
                        modifier = Modifier.fillMaxWidth().testTag("schedule-location-${course.id}"),
                        style = locationStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = true
                    )
                    }
                }
                // A status symbol must not reserve a column beside every line of a long address.
                if (duration > 1 && course.hasCardStatus()) com.tyust.course.ui.system.AnimatedLineIcon(
                    spec = if (course.hasConflict || unknownWeeks) com.tyust.course.ui.system.AnimatedIconSpec.Warning else com.tyust.course.ui.system.AnimatedIconSpec.Clock,
                    description = when { unknownWeeks -> "周次待核对"; course.isCurrent -> "正在上课"; course.isNext -> "下一节"; else -> "重叠课程" },
                    modifier = Modifier.size(10.dp), tint = if (unknownWeeks) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        }
    }
}

internal fun isInWeek(weeks: String?, week: Int): Boolean {
    return com.tyust.course.schedule.ScheduleWeeks.parse(weeks).visibleIn(week)
}

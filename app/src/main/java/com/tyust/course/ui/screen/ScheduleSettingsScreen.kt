package com.tyust.course.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.Capsule
import com.tyust.course.manager.AppearanceSettingsManager
import com.tyust.course.manager.ScheduleSettingsManager
import com.tyust.course.manager.ScheduleSettingsManager.PeriodTime
import com.tyust.course.ui.system.DialogHost
import com.tyust.course.ui.system.GlassCircleButton
import com.tyust.course.ui.system.GlassDatePickerDialog
import com.tyust.course.ui.system.GlassOptionWheelDialog
import com.tyust.course.ui.system.GlassTimeRangePickerDialog
import com.tyust.course.ui.system.InsetGroupedRow
import com.tyust.course.ui.system.InsetGroupedSection
import com.tyust.course.ui.system.LocalAppBackdrop
import com.tyust.course.ui.system.LocalAppOverlayBottomInset
import com.tyust.course.ui.system.LocalControlBackdrop
import com.tyust.course.ui.system.LocalDialogHost
import com.tyust.course.ui.system.LocalFloatingNotice
import com.tyust.course.ui.system.LocalModalBackdrop
import com.tyust.course.ui.system.LocalNoticeAnchor
import com.tyust.course.ui.system.NoticeAnchorState
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SectionSpacing
import com.tyust.course.ui.system.SystemTopBar
import com.tyust.course.ui.system.drawWallpaperPattern
import com.tyust.course.ui.system.isBackdropSupported
import com.tyust.course.ui.system.rememberDialogHostState
import com.tyust.course.ui.system.rememberGlassAccessibilityMode
import com.tyust.course.ui.theme.NeuPrimary
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PeriodCountMin = 8
private const val PeriodCountMax = 16

/**
 * 课表设置页。
 *
 * 这一页运行在【独立的 Dialog 窗口】里（见 ScheduleRoute），跨窗口拿不到主窗口的
 * 捕获层，所以它必须自己铺一层壁纸并导出 backdrop——否则 SystemTopBar /
 * InsetGroupedSection 之类的组件全都退化成灰卡片，整页看起来像另一个 App。
 * 同理它也自带一个 DialogHost，让嵌套的日期/时间滚轮走同窗口 portal，
 * 能压暗、有弹簧入场、并且采样得到本窗口的壁纸。
 *
 * @param onShowDatePicker 遗留的外部日期选择回调（旧 Fragment 入口在用）。
 *        为 null 时本页用自己的玻璃滚轮日期选择器。
 */
@Composable
fun ScheduleSettingsScreen(
    manager: ScheduleSettingsManager,
    onClose: () -> Unit,
    onShowDatePicker: (() -> Unit)? = null
) {
    var periodCount by remember { mutableStateOf(manager.periodCount) }
    var storedPeriodTimes by remember { mutableStateOf(manager.getPeriodTimes()) }
    var semesterStartDate by remember { mutableStateOf(manager.semesterStartDate) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showPeriodCountPicker by remember { mutableStateOf(false) }
    var editingPeriod by remember { mutableStateOf<PeriodTime?>(null) }

    val dateFormat = remember { SimpleDateFormat("yyyy年M月d日", Locale.CHINA) }
    val dateText = if (semesterStartDate > 0) dateFormat.format(Date(semesterStartDate)) else "未设置"

    // 外部（旧 Fragment 的 MaterialDatePicker）改了日期要能同步回来
    LaunchedEffect(manager.semesterStartDate) {
        semesterStartDate = manager.semesterStartDate
    }

    // getPeriodTimes() 返回存档或 12 条默认值，与 periodCount 无关——所以选了
    // 16 节之后列表仍然只有 12 行，而课表已经画 16 行。这里按 periodCount 裁剪/补齐。
    // 超出默认表的节次补空串显示"未设置"，不凭空编时间。
    val periodTimes = remember(periodCount, storedPeriodTimes) {
        val byPeriod = storedPeriodTimes.associateBy { it.period }
        val defaults = manager.getDefaultPeriodTimes().associateBy { it.period }
        (1..periodCount).map { period ->
            byPeriod[period] ?: defaults[period] ?: PeriodTime(period, "", "")
        }
    }

    val scrollState = rememberScrollState()
    val headerCollapse by remember {
        derivedStateOf { (scrollState.value / 96f).coerceIn(0f, 1f) }
    }

    // 入场错峰只在刚进入时播一次；之后任何重组都直接渲染终态
    val accessibility = rememberGlassAccessibilityMode()
    var entranceSettled by remember { mutableStateOf(accessibility.reduceMotion) }
    LaunchedEffect(Unit) {
        delay(700)
        entranceSettled = true
    }

    val wallpaperBackdrop = if (isBackdropSupported()) rememberLayerBackdrop() else null
    val dialogHostState = rememberDialogHostState()
    // 顶栏会 reportNoticeAnchor()，不隔离的话本窗口顶栏的底边会被写进主窗口的
    // 锚点状态，关掉设置页后主窗口的悬浮通知会停在一个错误的落点上。
    val noticeAnchorState = remember { NoticeAnchorState() }
    val wallpaperPreset = AppearanceSettingsManager.wallpaper

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (wallpaperBackdrop != null) {
                        Modifier.layerBackdrop(wallpaperBackdrop)
                    } else {
                        Modifier
                    }
                )
        ) {
            drawWallpaperPattern(wallpaperPreset)
        }

        CompositionLocalProvider(
            LocalAppBackdrop provides wallpaperBackdrop,
            LocalControlBackdrop provides wallpaperBackdrop,
            // 主窗口那一层跨窗口不可用，必须一起覆盖掉：否则 backdrop 不支持时
            // SystemDialog 的默认取值链会一路落到主窗口的 LocalModalBackdrop 上。
            LocalModalBackdrop provides wallpaperBackdrop,
            LocalDialogHost provides dialogHostState,
            LocalFloatingNotice provides null,
            LocalNoticeAnchor provides noticeAnchorState,
            // 主窗口给的是 96dp（底栏高度），Dialog 沿用父 composition 会把它带进来，
            // 这个窗口没有底栏，不清零就会在页面底部空出一块。
            LocalAppOverlayBottomInset provides 0.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SystemTopBar(
                    title = "课表设置",
                    subtitle = "学期起始与节次时间",
                    collapseFraction = headerCollapse,
                    navigationIcon = {
                        GlassCircleButton(
                            onClick = onClose,
                            icon = Icons.Default.Close,
                            contentDescription = "关闭",
                            size = 34.dp
                        )
                    },
                    actions = {
                        Text(
                            text = "完成",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = NeuPrimary,
                            modifier = Modifier
                                .clip(Capsule())
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClick = onClose
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(
                            start = PagePadding,
                            end = PagePadding,
                            top = 8.dp,
                            bottom = 32.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(SectionSpacing)
                ) {
                    StaggerIn(index = 0, settled = entranceSettled) {
                        InsetGroupedSection(header = "基础设置") {
                            InsetGroupedRow(
                                icon = Icons.Filled.DateRange,
                                iconTint = Color(0xFF0A84FF),
                                title = "第一周开始日期",
                                subtitle = "决定当前是第几周",
                                trailing = {
                                    Text(
                                        text = dateText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = NeuPrimary
                                    )
                                },
                                onClick = {
                                    if (onShowDatePicker != null) {
                                        onShowDatePicker()
                                    } else {
                                        showDatePicker = true
                                    }
                                }
                            )
                            InsetGroupedRow(
                                icon = Icons.Outlined.AccessTime,
                                iconTint = Color(0xFFFF9F0A),
                                title = "每天节数",
                                subtitle = "课表纵向的节次总数",
                                trailing = {
                                    Text(
                                        text = "$periodCount 节",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = NeuPrimary
                                    )
                                },
                                showDivider = false,
                                // 原先是 Material DropdownMenu；换成与节次时间同一种
                                // 滚轮弹窗，两处交互一致
                                onClick = { showPeriodCountPicker = true }
                            )
                        }
                    }

                    StaggerIn(index = 1, settled = entranceSettled) {
                        InsetGroupedSection(
                            header = "节次时间",
                            footer = "点任意一节可用滚轮修改起止时间"
                        ) {
                            periodTimes.forEachIndexed { index, periodTime ->
                                val hasTime = periodTime.startTime.isNotBlank() &&
                                    periodTime.endTime.isNotBlank()
                                // 不给图标：十几行同一枚时钟图标只是噪声，
                                // 「第 N 节」本身已经是最强的识别信息
                                InsetGroupedRow(
                                    title = "第 ${periodTime.period} 节",
                                    trailing = {
                                        GlassFilterChip(
                                            label = if (hasTime) {
                                                "${periodTime.startTime} - ${periodTime.endTime}"
                                            } else {
                                                "未设置"
                                            },
                                            selected = hasTime,
                                            compact = true
                                        )
                                    },
                                    showDivider = index != periodTimes.lastIndex,
                                    onClick = { editingPeriod = periodTime }
                                )
                            }
                        }
                    }
                }
            }

            // 三个 picker 必须写在这个 CompositionLocalProvider 【里面】。
            // 放在外面时 LocalDialogHost.current 取到的是【主窗口】那个 host
            // （MainActivity 下发的），SystemDialog 会把弹窗注册进主窗口渲染，
            // 而主窗口在这个 Dialog 窗口的后面——弹窗于是被设置页整页挡住、看不见。
            if (showDatePicker) {
                GlassDatePickerDialog(
                    title = "选择第一周周一日期",
                    initialMillis = if (semesterStartDate > 0) {
                        semesterStartDate
                    } else {
                        System.currentTimeMillis()
                    },
                    onConfirm = { millis ->
                        manager.semesterStartDate = millis
                        semesterStartDate = millis
                        showDatePicker = false
                    },
                    onDismiss = { showDatePicker = false }
                )
            }

            if (showPeriodCountPicker) {
                GlassOptionWheelDialog(
                    title = "每天节数",
                    options = (PeriodCountMin..PeriodCountMax).map { "$it 节" },
                    selectedIndex = (periodCount - PeriodCountMin)
                        .coerceIn(0, PeriodCountMax - PeriodCountMin),
                    onConfirm = { index ->
                        val count = PeriodCountMin + index
                        periodCount = count
                        manager.periodCount = count
                        showPeriodCountPicker = false
                    },
                    onDismiss = { showPeriodCountPicker = false }
                )
            }

            editingPeriod?.let { target ->
                GlassTimeRangePickerDialog(
                    title = "第 ${target.period} 节",
                    initialStart = target.startTime.ifBlank { "08:00" },
                    initialEnd = target.endTime.ifBlank { "08:45" },
                    onConfirm = { start, end ->
                        // 落盘的是当前可见的完整列表（已按 periodCount 裁剪/补齐），
                        // 否则改第 13 节时会把补齐出来的行又丢掉。
                        val updated = periodTimes.map {
                            if (it.period == target.period) {
                                PeriodTime(it.period, start, end)
                            } else {
                                it
                            }
                        }
                        manager.savePeriodTimes(updated)
                        storedPeriodTimes = updated
                        editingPeriod = null
                    },
                    onDismiss = { editingPeriod = null }
                )
            }
        }

        DialogHost(state = dialogHostState, modifier = Modifier.fillMaxSize())
    }
}

/**
 * 入场错峰：延迟 index*28ms 后淡入并上移到位。
 *
 * @param settled true 时直接渲染终态。入场结束后任何重组都不该再播一遍，
 *        这个开关就是为此存在的。
 */
@Composable
private fun StaggerIn(
    index: Int,
    settled: Boolean,
    content: @Composable () -> Unit
) {
    if (settled) {
        content()
        return
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(8) * 28L)
        shown = true
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 300f),
        label = "staggerIn"
    )
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 12.dp.toPx()
        }
    ) {
        content()
    }
}

/** 顶栏「完成」用不着一整个按钮组件。 */

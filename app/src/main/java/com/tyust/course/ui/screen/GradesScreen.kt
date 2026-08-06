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
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemEmptyState
import com.tyust.course.ui.system.SystemLoadingState
import com.tyust.course.ui.system.SystemPicker
import com.tyust.course.ui.system.SystemSectionHeader
import com.tyust.course.ui.system.SystemSegmentedControl
import com.tyust.course.ui.system.SystemStatStrip
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import com.tyust.course.ui.system.SystemTopBar
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

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            SystemTopBar(
                title = "成绩与考试",
                subtitle = subtitle,
                actions = {
                    if (currentTab != 2) {
                        val grades = if (currentTab == 0) semesterGrades else overallGrades
                        IconButton(
                            onClick = { if (grades.isNotEmpty()) onExportGrades(grades) },
                            enabled = grades.isNotEmpty() && !isRefreshing
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "导出成绩"
                            )
                        }
                    }
                    if (isRefreshing) {
                        Box(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "刷新"
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = PagePadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SystemSegmentedControl(
                options = tabTitles,
                selectedIndex = currentTab,
                onSelect = onTabChange
            )

            Box(modifier = Modifier.weight(1f)) {
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
                            isLoading = semesterIsLoading
                        )

                        1 -> OverallGradesContent(
                            grades = overallGrades,
                            stats = overallStats,
                            isLoading = overallIsLoading
                        )

                        else -> ExamScheduleContent(
                            exams = examList,
                            isLoading = examIsLoading
                        )
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
    isLoading: Boolean
) {
    when {
        isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SystemLoadingState(text = "正在加载总体成绩…")
            }
        }

        grades.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SystemEmptyState(
                    title = "暂无总体成绩",
                    message = "点击刷新获取最新成绩"
                )
            }
        }

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 160.dp),
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
    isLoading: Boolean
) {
    val totalCredits = grades.sumOf { it.credits.toDoubleOrNull() ?: 0.0 }
    val weightedGpa = grades.sumOf {
        (it.credits.toDoubleOrNull() ?: 0.0) * (it.gpa.toDoubleOrNull() ?: 0.0)
    }
    val averageGpa = if (totalCredits > 0) weightedGpa / totalCredits else 0.0

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 100.dp),
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

@Composable
private fun SemesterSelector(
    semesters: List<String>,
    currentSemester: String,
    onSemesterChange: (String) -> Unit
) {
    SystemPicker(
        options = semesters,
        selectedIndex = semesters.indexOf(currentSemester).takeIf { it >= 0 },
        onSelect = { index -> onSemesterChange(semesters[index]) },
        label = "学期",
        placeholder = "选择学期",
        leadingIcon = Icons.Default.CalendarToday
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(999.dp)
                )
        ) {
            if (stats.excellent > 0) {
                Box(
                    modifier = Modifier
                        .weight(stats.excellent.toFloat())
                        .fillMaxSize()
                        .background(SemanticSuccess)
                )
            }
            if (stats.good > 0) {
                Box(
                    modifier = Modifier
                        .weight(stats.good.toFloat())
                        .fillMaxSize()
                        .background(SemanticInfo)
                )
            }
            if (stats.medium > 0) {
                Box(
                    modifier = Modifier
                        .weight(stats.medium.toFloat())
                        .fillMaxSize()
                        .background(SemanticWarning)
                )
            }
            if (stats.pass > 0) {
                Box(
                    modifier = Modifier
                        .weight(stats.pass.toFloat())
                        .fillMaxSize()
                        .background(SemanticDanger)
                )
            }
        }

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

@Composable
private fun GradeItemRow(
    item: GradeItemUi
) {
    var expanded by remember { mutableStateOf(false) }
    val gradeColor = getGradeColor(item.grade)
    val hasDetail = item.detail.isNotEmpty() || item.courseCode.isNotEmpty()

    SystemCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { if (hasDetail) expanded = !expanded },
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant
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
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                                      else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 展开区域：成绩构成进度条
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    if (item.detail.isNotEmpty()) {
                        Text(
                            text = "成绩构成",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val components = parseGradeComponents(item.detail)
                        // 多色循环，确保不同分项有不同颜色（紫/蓝/绿/橙/青）
                        val barColors = listOf(
                            Color(0xFF7C4DFF), // 紫色
                            Color(0xFF448AFF), // 蓝色
                            Color(0xFF4CAF50), // 绿色
                            Color(0xFFFF9800), // 橙色
                            Color(0xFF00BCD4)  // 青色
                        )
                        components.forEachIndexed { index, comp ->
                            GradeComponentBar(
                                label = comp.label,
                                score = comp.score,
                                maxScore = 100f,
                                color = barColors[index % barColors.size]
                            )
                            if (index < components.lastIndex) {
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    } else {
                        // 无分项时展示基本信息
                        if (item.teachingClass.isNotEmpty()) {
                            Text(
                                text = "教学班: ${item.teachingClass}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "暂无分项详情",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
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
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = (score / maxScore).coerceIn(0f, 1f))
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
    isLoading: Boolean
) {
    when {
        isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SystemLoadingState(text = "正在加载考试安排…")
            }
        }

        exams.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SystemEmptyState(
                    title = "暂无考试安排",
                    message = "点击刷新获取最新考试信息"
                )
            }
        }

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 160.dp),
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
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant
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

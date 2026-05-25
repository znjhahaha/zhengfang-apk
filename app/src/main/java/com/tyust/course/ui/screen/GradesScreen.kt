package com.tyust.course.ui.screen

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemEmptyState
import com.tyust.course.ui.system.SystemLoadingState
import com.tyust.course.ui.system.SystemSectionHeader
import com.tyust.course.ui.system.SystemSegmentedControl
import com.tyust.course.ui.system.SystemStatStrip
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import com.tyust.course.ui.system.SystemTopBar
import com.tyust.course.ui.theme.SemanticDanger
import com.tyust.course.ui.theme.SemanticInfo
import com.tyust.course.ui.theme.SemanticSuccess
import com.tyust.course.ui.theme.SemanticWarning

data class GradeItemUi(
    val courseName: String,
    val grade: String,
    val credits: String,
    val gpa: String,
    val courseType: String
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
    onRefresh: () -> Unit
) {
    val tabTitles = listOf("学期成绩", "总体成绩", "考试安排")
    val isRefreshing = semesterIsLoading || overallIsLoading || examIsLoading
    val subtitle = when (currentTab) {
        0 -> if (currentSemester.isBlank()) "按学期查看课程成绩" else currentSemester
        1 -> "累计成绩与分布概览"
        else -> "近期考试安排"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SystemTopBar(
                title = "成绩与考试",
                subtitle = subtitle,
                actions = {
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
                contentPadding = PaddingValues(bottom = 24.dp),
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
        contentPadding = PaddingValues(bottom = 24.dp),
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SemesterSelector(
    semesters: List<String>,
    currentSemester: String,
    onSemesterChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = currentSemester.ifBlank { "选择学期" },
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            readOnly = true,
            singleLine = true,
            label = { Text("学期") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.CalendarToday,
                    contentDescription = null
                )
            },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            shape = MaterialTheme.shapes.small,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.outline,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            semesters.forEach { semester ->
                DropdownMenuItem(
                    text = { Text(semester) },
                    onClick = {
                        onSemesterChange(semester)
                        expanded = false
                    }
                )
            }
        }
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
    val gradeColor = getGradeColor(item.grade)

    SystemCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = item.courseName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.courseType.isNotBlank()) {
                        SystemStatusBadge(
                            text = item.courseType,
                            tone = SystemTone.Neutral
                        )
                    }
                    Text(
                        text = "${item.credits} 学分",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.grade,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = gradeColor
                )
                Text(
                    text = "绩点 ${item.gpa}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
                contentPadding = PaddingValues(bottom = 24.dp),
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

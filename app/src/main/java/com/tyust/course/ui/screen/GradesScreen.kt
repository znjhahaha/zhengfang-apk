package com.tyust.course.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.material3.TabPosition
import com.tyust.course.ui.theme.PrimaryPurple
import java.util.Calendar

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun GradesScreen(
    currentTab: Int,
    onTabChange: (Int) -> Unit,
    // Semester Tab Params
    semesterGrades: List<GradeItemUi>,
    semesters: List<String>,
    currentSemester: String,
    onSemesterChange: (String) -> Unit,
    semesterIsLoading: Boolean,
    
    // Overall Tab Params
    overallGrades: List<GradeItemUi>,
    overallStats: OverallStatsUi,
    overallIsLoading: Boolean,
    
    // Exam Tab Params
    examList: List<ExamItemUi>,
    examIsLoading: Boolean,
    
    onRefresh: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成绩/考试", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                ),
                actions = {
                    IconButton(onClick = onRefresh) {
                        var isRotating by remember { mutableStateOf(false) }
                        val rotation by animateFloatAsState(
                            targetValue = if (isRotating) 360f else 0f,
                            animationSpec = tween(1000, easing = LinearEasing),
                            label = "refresh_rotation"
                        )
                        
                        LaunchedEffect(semesterIsLoading || overallIsLoading || examIsLoading) {
                            if (semesterIsLoading || overallIsLoading || examIsLoading) {
                                isRotating = true
                            } else {
                                isRotating = false
                            }
                        }

                        Icon(
                            Icons.Filled.Refresh, 
                            contentDescription = "刷新",
                            modifier = Modifier.scale(if (semesterIsLoading || overallIsLoading || examIsLoading) 0.8f else 1f)
                        )
                    }
                }
            )
        },
        containerColor = Color(0xFFF5F5F7) // Light Gray Background
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            // Fancy Tab Row
            TabRow(
                selectedTabIndex = currentTab,
                containerColor = Color.White,
                contentColor = PrimaryPurple,
                indicator = { tabPositions ->
                    if (currentTab < tabPositions.size) {
                         Box(
                            Modifier
                                .myTabIndicatorOffset(tabPositions[currentTab])
                                .height(3.dp)
                                .background(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(PrimaryPurple, Color(0xFF9C27B0))
                                    ),
                                    shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                                )
                        )
                    }
                },
                divider = {
                     HorizontalDivider(color = Color.Black.copy(alpha = 0.05f))
                }
            ) {
                Tab(
                    selected = currentTab == 0,
                    onClick = { onTabChange(0) },
                    text = { Text("学期成绩", fontWeight = if(currentTab == 0) FontWeight.Bold else FontWeight.Normal) },
                    selectedContentColor = PrimaryPurple,
                    unselectedContentColor = Color.Gray
                )
                Tab(
                    selected = currentTab == 1,
                    onClick = { onTabChange(1) },
                    text = { Text("总体成绩", fontWeight = if(currentTab == 1) FontWeight.Bold else FontWeight.Normal) },
                    selectedContentColor = PrimaryPurple,
                    unselectedContentColor = Color.Gray
                )
                Tab(
                    selected = currentTab == 2,
                    onClick = { onTabChange(2) },
                    text = { Text("考试安排", fontWeight = if(currentTab == 2) FontWeight.Bold else FontWeight.Normal) },
                    selectedContentColor = PrimaryPurple,
                    unselectedContentColor = Color.Gray
                )
            }

            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { width -> width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> -width } + fadeOut()
                    } else {
                        slideInHorizontally { width -> -width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> width } + fadeOut()
                    }
                },
                label = "tab_content_anim"
            ) { targetTab ->
                if (targetTab == 0) {
                    SemesterGradesContent(
                        grades = semesterGrades,
                        semesters = semesters,
                        currentSemester = currentSemester,
                        onSemesterChange = onSemesterChange,
                        isLoading = semesterIsLoading
                    )
                } else if (targetTab == 1) {
                    OverallGradesContent(
                        grades = overallGrades,
                        stats = overallStats,
                        isLoading = overallIsLoading
                    )
                } else {
                    ExamScheduleContent(
                        exams = examList,
                        isLoading = examIsLoading
                    )
                }
            }
        }
    }
}

@Composable
fun OverallGradesContent(
    grades: List<GradeItemUi>,
    stats: OverallStatsUi,
    isLoading: Boolean
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(16.dp))
        
        // Premium Stats Card
        if (!isLoading) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(PrimaryPurple, Color(0xFF7E57C2))
                            )
                        )
                        .padding(20.dp)
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stats.gpa,
                                    style = MaterialTheme.typography.displayMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "总体绩点 (GPA)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }
                            
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                CompactStatItem(stats.credits, "已修学分", Color.White)
                                CompactStatItem("${stats.courseCount}", "总课程", Color.White)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(20.dp))
                        
                        // Visual Distribution Bar
                        GradeDistributionBar(stats)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PrimaryPurple)
            }
        } else if (grades.isEmpty()) {
            EmptyState("暂无总体成绩数据")
        } else {
             Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            ) {
                Icon(Icons.Default.List, contentDescription = null, tint = PrimaryPurple, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "课程明细", 
                    style = MaterialTheme.typography.titleMedium, 
                    fontWeight = FontWeight.Bold, 
                    color = Color.Black
                )
            }
            
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                itemsIndexed(grades) { index, item ->
                    AnimatedEntryList(index) {
                        GradeItemRow(item)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemesterGradesContent(
    grades: List<GradeItemUi>,
    semesters: List<String>,
    currentSemester: String,
    onSemesterChange: (String) -> Unit,
    isLoading: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    
    // Calculate stats
    val totalCredits = grades.sumOf { it.credits.toDoubleOrNull() ?: 0.0 }
    val weightedGPA = grades.sumOf { (it.credits.toDoubleOrNull() ?: 0.0) * (it.gpa.toDoubleOrNull() ?: 0.0) }
    val avgGPA = if (totalCredits > 0) weightedGPA / totalCredits else 0.0

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(16.dp))
        
        // Modern Semester Selector
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = currentSemester,
                onValueChange = {},
                readOnly = true,
                leadingIcon = { Icon(Icons.Default.CalendarToday, null, tint = PrimaryPurple) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryPurple,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
                    .shadow(2.dp, RoundedCornerShape(16.dp))
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Color.White)
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

        Spacer(modifier = Modifier.height(16.dp))

        // Stats Row (Instead of Card)
        if (!isLoading) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SemesterStatCard("平均绩点", String.format("%.2f", avgGPA), PrimaryPurple, Modifier.weight(1f))
                SemesterStatCard("总学分", String.format("%.1f", totalCredits), Color(0xFF2196F3), Modifier.weight(1f))
                SemesterStatCard("课程数", "${grades.size}", Color(0xFFFF9800), Modifier.weight(1f))
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PrimaryPurple)
            }
        } else if (grades.isEmpty()) {
            EmptyState("本学期暂无成绩数据")
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                itemsIndexed(grades) { index, item ->
                    AnimatedEntryList(index) {
                        GradeItemRow(item)
                    }
                }
            }
        }
    }
}

@Composable
fun SemesterStatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun CompactStatItem(value: String, label: String, contentColor: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = value, 
            style = MaterialTheme.typography.titleLarge, 
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
        Text(
            text = label, 
            style = MaterialTheme.typography.bodySmall, 
            color = contentColor.copy(alpha = 0.8f)
        )
    }
}

@Composable
fun GradeDistributionBar(stats: OverallStatsUi) {
    val total = (stats.excellent + stats.good + stats.medium + stats.pass).toFloat()
    if (total == 0f) return

    Column {
        // The Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.2f))
        ) {
            if (stats.excellent > 0) Box(Modifier.weight(stats.excellent.toFloat()).fillMaxHeight().background(Color(0xFF4CAF50)))
            if (stats.good > 0) Box(Modifier.weight(stats.good.toFloat()).fillMaxHeight().background(Color(0xFF2196F3)))
            if (stats.medium > 0) Box(Modifier.weight(stats.medium.toFloat()).fillMaxHeight().background(Color(0xFFFF9800)))
            if (stats.pass > 0) Box(Modifier.weight(stats.pass.toFloat()).fillMaxHeight().background(Color(0xFFF44336))) // Pass is red/amber? '及格' usually low. 0xFFF44336 is red.
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // The Legend
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            LegendItem("优", stats.excellent, Color(0xFF4CAF50))
            LegendItem("良", stats.good, Color(0xFF2196F3))
            LegendItem("中", stats.medium, Color(0xFFFF9800))
            LegendItem("及", stats.pass, Color(0xFFF44336))
        }
    }
}

@Composable
fun LegendItem(label: String, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(4.dp))
        Text("$label $count", style = MaterialTheme.typography.bodySmall, color = Color.White)
    }
}

@Composable
fun EmptyState(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.School, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(message, color = Color.Gray, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun AnimatedEntryList(index: Int, content: @Composable () -> Unit) {
    // Use simple fade-in animation without staggered delay to avoid scroll lag
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(150, easing = FastOutSlowInEasing))
    ) {
        content()
    }
}

@Composable
fun GradeItemRow(item: GradeItemUi) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Indicator Strip
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(getGradeColor(item.grade), RoundedCornerShape(2.dp))
            )
            
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.courseName, 
                    style = MaterialTheme.typography.titleMedium, 
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text(item.courseType, fontSize = 10.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        border = null,
                        modifier = Modifier.height(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "${item.credits} 学分", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            
            Column(horizontalAlignment = Alignment.End) {
                val gradeColor = getGradeColor(item.grade)
                Text(
                    text = item.grade,
                    color = gradeColor,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "GP: ${item.gpa}", 
                    style = MaterialTheme.typography.labelSmall, 
                    color = Color.Gray,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
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

fun Modifier.myTabIndicatorOffset(
    currentTabPosition: TabPosition
): Modifier = composed {
    val currentTabWidth by animateDpAsState(
        targetValue = currentTabPosition.width,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "tab width"
    )
    val indicatorOffset by animateDpAsState(
        targetValue = currentTabPosition.left,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "tab offset"
    )
    fillMaxWidth()
        .wrapContentSize(Alignment.BottomStart)
        .offset(x = indicatorOffset)
        .width(currentTabWidth)
}

fun getGradeColor(grade: String): Color {
    val score = grade.replace("[^0-9]".toRegex(), "").toIntOrNull()
    
    if (score != null) {
        return when {
            score >= 90 -> Color(0xFF4CAF50) // Green
            score >= 80 -> Color(0xFF2196F3) // Blue
            score >= 70 -> Color(0xFFFF9800) // Orange
            score >= 60 -> Color(0xFFFFC107) // Amber
            else -> Color(0xFFF44336) // Red
        }
    }
    
    return when (grade) {
        "优秀" -> Color(0xFF4CAF50)
        "良好" -> Color(0xFF2196F3)
        "中等" -> Color(0xFFFF9800)
        "及格" -> Color(0xFFFFC107) // Changed to Amber for better visibility
        else -> Color(0xFFF44336)
    }
}

// ============= 考试安排相关 =============

@Composable
fun ExamScheduleContent(
    exams: List<ExamItemUi>,
    isLoading: Boolean
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(16.dp))
        
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PrimaryPurple)
            }
        } else if (exams.isEmpty()) {
            EmptyState("暂无考试安排")
        } else {
            // 考试统计卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EventNote, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("共 ${exams.size} 场考试", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                itemsIndexed(exams) { index, exam ->
                    AnimatedEntryList(index) {
                        ExamItemRow(exam)
                    }
                }
            }
        }
    }
}

@Composable
fun ExamItemRow(exam: ExamItemUi) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 课程名 + 考试类型
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = exam.courseName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    modifier = Modifier.weight(1f)
                )
                SuggestionChip(
                    onClick = {},
                    label = { Text(if (exam.examName.contains("期中")) "期中" else "期末", fontSize = 10.sp) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (exam.examName.contains("期中")) Color(0xFFFFF3E0) else Color(0xFFE3F2FD),
                        labelColor = if (exam.examName.contains("期中")) Color(0xFFFF9800) else Color(0xFF2196F3)
                    ),
                    border = null,
                    modifier = Modifier.height(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 时间
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, tint = Color(0xFFF44336), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = exam.examTime,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFF44336),
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 地点 + 座位号
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${exam.location}  座位: ${exam.seatNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.DarkGray
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 教师
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = exam.teacher,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}

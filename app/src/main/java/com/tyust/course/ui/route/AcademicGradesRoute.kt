package com.tyust.course.ui.route

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.tyust.course.ui.system.rememberPageData
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.tyust.course.academic.*
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.ui.screen.ExamItemUi
import com.tyust.course.ui.screen.GradeItemUi
import com.tyust.course.ui.screen.GradesScreen
import com.tyust.course.ui.system.GlassToaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AcademicGradesRoute(school: SchoolConfig) {
    val context = LocalContext.current
    val account = UserManager.getInstance().currentAccountStorageKey
    val sessions = UserManager.getInstance().sessionState
    val session by sessions.state.collectAsState()
    val expectedSession = session.token
    val detailScope = rememberCoroutineScope()
    val detailRequests = remember(account, session.token) { mutableSetOf<String>() }
    var tab by rememberSaveable(account) { mutableIntStateOf(0) }
    val supportedTabs = buildSet {
        if (com.tyust.course.academic.plugin.AcademicProviderRegistry.hasCapability(school, "study.grades")) { add(0); add(1) }
        if (com.tyust.course.academic.plugin.AcademicProviderRegistry.hasCapability(school, "study.exams")) add(2)
    }
    LaunchedEffect(account, supportedTabs) { if (tab !in supportedTabs && supportedTabs.isNotEmpty()) tab = supportedTabs.first() }
    var report by rememberPageData("academic.grades") { AcademicGradeReport(emptyList()) }
    var reportLoaded by rememberPageData("academic.grades.loaded") { false }
    var semester by rememberSaveable(account) { mutableStateOf("") }
    var loading by remember(account) { mutableStateOf(true) }
    var error by remember(account) { mutableStateOf("") }
    var revision by remember(account) { mutableIntStateOf(0) }
    var exams by rememberPageData<List<ExamItemUi>>("academic.exams") { emptyList() }
    var examsLoaded by rememberPageData("academic.exams.loaded") { false }
    var examLoading by remember(account) { mutableStateOf(false) }
    var examError by remember(account) { mutableStateOf("") }
    var examRevision by remember(account) { mutableIntStateOf(0) }
    val semesters = remember(report) { AcademicStudyBridge.semesters(report.grades).ifEmpty { listOf(AcademicStudyReader.calendarTerm().id) } }
    fun gradeUi(grade: AcademicGrade): GradeItemUi = AcademicStudyBridge.grade(grade).copy(onDetailRequest =
        if (grade.id.isNotBlank() && grade.detail.isBlank() && com.tyust.course.academic.plugin.AcademicProviderRegistry.overrides(school, "study.gradeDetails")) ({
            if (detailRequests.add(grade.id)) detailScope.launch {
                try {
                    val details = withContext(Dispatchers.IO) { AcademicStudyBridge.reader(school, account, expectedSession).gradeDetails(grade) }
                    if (sessions.isCurrent(expectedSession)) report = report.copy(grades = report.grades.map { if (it.id == grade.id) it.copy(detail = details) else it })
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { detailRequests.remove(grade.id); if (sessions.isCurrent(expectedSession)) GlassToaster.show(e.message ?: "成绩明细加载失败") }
            }
            Unit
        }) else null)
    val semesterGrades = report.grades.filter { it.term == semester }.map(::gradeUi)
    val overallGrades = report.grades.map(::gradeUi)
    val overallStats = remember(report) { AcademicStudyBridge.stats(report) }

    LaunchedEffect(account, revision, session.token) {
        if (0 !in supportedTabs) { loading = false; error = "该学校尚未适配成绩查询"; return@LaunchedEffect }
        val expected = expectedSession
        if (revision == 0 && reportLoaded) { loading = false; return@LaunchedEffect }
        loading = true; error = ""
        try {
            val loaded = withContext(Dispatchers.IO) { AcademicStudyBridge.reader(school, account, expected).grades() }
            if (!sessions.isCurrent(expected)) return@LaunchedEffect
            report = loaded
            reportLoaded = true
            val available = AcademicStudyBridge.semesters(loaded.grades)
            if (semester.isBlank() || semester !in available) semester = available.firstOrNull() ?: AcademicStudyReader.calendarTerm().id
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!sessions.isCurrent(expected)) return@LaunchedEffect
            error = e.message ?: "成绩加载失败，请重试"
            if ((e as? AcademicException)?.status == AcademicStatus.SESSION_EXPIRED)
                com.tyust.course.network.CourseApiClient.getInstance().notifyCookieExpired(expected)
        }
        finally { if (sessions.isCurrent(expected) && coroutineContext[kotlinx.coroutines.Job]?.isActive == true) loading = false }
    }
    LaunchedEffect(account, tab, examRevision, session.token) {
        val expected = expectedSession
        if (tab != 2 || examsLoaded || 2 !in supportedTabs) return@LaunchedEffect
        examLoading = true; examError = ""
        try {
            val loaded = withContext(Dispatchers.IO) {
                val reader = AcademicStudyBridge.reader(school, account, expected)
                reader.exams(reader.catalog().currentTerm).map(AcademicStudyBridge::exam)
            }
            if (!sessions.isCurrent(expected)) return@LaunchedEffect
            exams = loaded; examsLoaded = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!sessions.isCurrent(expected)) return@LaunchedEffect
            examError = e.message ?: "考试安排加载失败，请重试"
            if ((e as? AcademicException)?.status == AcademicStatus.SESSION_EXPIRED)
                com.tyust.course.network.CourseApiClient.getInstance().notifyCookieExpired(expected)
        }
        finally { if (sessions.isCurrent(expected) && coroutineContext[kotlinx.coroutines.Job]?.isActive == true) examLoading = false }
    }
    com.tyust.course.ui.system.ReportPageContent(report.grades.isNotEmpty() || exams.isNotEmpty())
    GradesScreen(currentTab = tab, onTabChange = { tab = it },
        semesterGrades = semesterGrades,
        semesters = semesters, currentSemester = semester, onSemesterChange = { semester = it },
        semesterIsLoading = loading, overallGrades = overallGrades,
        overallStats = overallStats, overallIsLoading = loading,
        examList = exams, examIsLoading = examLoading,
        onRefresh = { if (tab == 2) { examsLoaded = false; examRevision++ } else revision++ },
        semesterError = error, overallError = error, examError = examError,
        onExportGrades = { exportAcademicGrades(context, it) }, supportedTabs = supportedTabs)
}

private fun exportAcademicGrades(context: Context, grades: List<GradeItemUi>) {
    try {
        fun cell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
        val csv = buildString {
            append('\uFEFF')
            appendLine("学年,学期,课程名称,课程代码,开课学院,学分,成绩,绩点,成绩说明")
            grades.forEach { item ->
                val year = item.year.toIntOrNull()?.let { "$it-${it + 1}" }.orEmpty()
                appendLine(listOf(year, item.term, item.courseName, item.courseCode, item.college,
                    item.credits, item.grade, item.gpa, item.detail).joinToString(",", transform = ::cell))
            }
        }
        val directory = File(context.externalCacheDir, "exports").apply { mkdirs() }
        val file = File(directory, "成绩单_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())}.csv")
        file.writeText(csv, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "导出成绩单"))
    } catch (e: Exception) { GlassToaster.show("导出失败：${e.message}") }
}

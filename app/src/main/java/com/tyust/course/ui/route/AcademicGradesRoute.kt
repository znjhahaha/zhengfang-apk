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
import com.tyust.course.manager.GradeBrowsePreferences
import com.tyust.course.manager.GradeBrowseScope
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
    val providerRevision by com.tyust.course.academic.plugin.AcademicProviderRegistry.revision.collectAsState()
    val provider = remember(school, providerRevision) {
        val registry = com.tyust.course.academic.plugin.AcademicProviderRegistry
        listOf("study.terms", "study.grades", "study.exams", "study.gradeDetails")
            .joinToString(":") { registry.operationProvider(school, it)?.digest.orEmpty() }
    }
    val account = UserManager.getInstance().currentAccountStorageKey
    key(account, provider) { AcademicGradesContent(school, account, provider) }
}

@Composable
private fun AcademicGradesContent(school: SchoolConfig, account: String, provider: String) {
    val context = LocalContext.current
    val sessions = UserManager.getInstance().sessionState
    val session by sessions.state.collectAsState()
    val expectedSession = session.token
    val detailScope = rememberCoroutineScope()
    val detailRequests = remember(session.token) { mutableSetOf<String>() }
    val gradeProvider = remember(provider) {
        com.tyust.course.academic.plugin.AcademicProviderRegistry.operationProvider(school, "study.grades")
    }
    val termFormat = gradeProvider?.manifest?.json?.optJSONObject("studyOptions")?.optString("gradeTermFormat").orEmpty()
    val preferenceScope = remember(account, school.id, gradeProvider?.manifest?.id, termFormat) {
        GradeBrowseScope(account, school.id, gradeProvider?.manifest?.id.orEmpty(), termFormat)
    }
    var browse by rememberGradeBrowseSelection(preferenceScope)
    val preferences = remember(context) { GradeBrowsePreferences.from(context) }
    val tab = browse.tab
    val semester = browse.termId
    val latestSemester by rememberUpdatedState(semester)
    fun chooseTerm(id: String, label: String) {
        browse = browse.copy(termId = id, termLabel = label)
        preferences.write(preferenceScope, browse)
    }
    fun chooseTab(value: Int) {
        browse = browse.copy(tab = value)
        preferences.write(preferenceScope, browse)
    }
    val supportedTabs = buildSet {
        if (com.tyust.course.academic.plugin.AcademicProviderRegistry.hasCapability(school, "study.grades")) { add(0); add(1) }
        if (com.tyust.course.academic.plugin.AcademicProviderRegistry.hasCapability(school, "study.exams")) add(2)
    }
    LaunchedEffect(supportedTabs) { if (tab !in supportedTabs && supportedTabs.isNotEmpty()) browse = browse.copy(tab = supportedTabs.first()) }
    val cache = "academic.grades:$account:$provider"
    var report by rememberPageData("$cache:overall") { AcademicGradeReport(emptyList()) }
    var reportLoaded by rememberPageData("$cache:overall.loaded") { false }
    var catalog by rememberPageData<AcademicStudyCatalog?>("$cache:terms") { null }
    var termReports by rememberPageData<Map<String, AcademicGradeReport>>("$cache:reports") { emptyMap() }
    var semesterChosen by rememberSaveable { mutableStateOf(semester.isNotBlank()) }
    var appliedCatalogTerm by rememberSaveable { mutableStateOf<String?>(null) }
    val standardTerms = termFormat == "academic-year-semester"
    var loading by remember { mutableStateOf(true) }
    var overallLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var overallError by remember { mutableStateOf("") }
    var catalogError by remember { mutableStateOf("") }
    var catalogLoading by remember { mutableStateOf(false) }
    var catalogAttempted by remember { mutableStateOf(catalog != null) }
    var revision by remember { mutableIntStateOf(0) }
    var overallRevision by remember { mutableIntStateOf(0) }
    var termRevision by remember { mutableIntStateOf(0) }
    var exams by rememberPageData<List<ExamItemUi>>("$cache:exams") { emptyList() }
    var examsLoaded by rememberPageData("$cache:exams.loaded") { false }
    var examLoading by remember { mutableStateOf(false) }
    var examError by remember { mutableStateOf("") }
    var examRevision by remember { mutableIntStateOf(0) }
    val calendarDate = if (standardTerms) rememberGradeCalendarDate() else null
    val terms = remember(catalog, report, termReports, standardTerms, calendarDate) {
        gradeSemesters(catalog, report.grades + termReports.values.flatMap { it.grades }, standardTerms,
            calendarDate?.calendar() ?: java.util.Calendar.getInstance())
    }
    fun loadError(e: Exception): String {
        if ((e as? AcademicException)?.status == AcademicStatus.SESSION_EXPIRED)
            com.tyust.course.network.CourseApiClient.getInstance().notifyCookieExpired(expectedSession)
        return e.message ?: "加载失败，请重试"
    }
    fun gradeUi(grade: AcademicGrade): GradeItemUi = AcademicStudyBridge.grade(grade).copy(onDetailRequest =
        if (grade.id.isNotBlank() && grade.detail.isBlank() && com.tyust.course.academic.plugin.AcademicProviderRegistry.overrides(school, "study.gradeDetails")) ({
            val detailKey = grade.term + ":" + grade.id
            if (detailRequests.add(detailKey)) detailScope.launch {
                try {
                    val details = withContext(Dispatchers.IO) { AcademicStudyBridge.reader(school, account, expectedSession).gradeDetails(grade) }
                    if (sessions.isCurrent(expectedSession)) {
                        fun update(source: AcademicGradeReport) = source.copy(grades = source.grades.map {
                            if (it.id == grade.id && it.term == grade.term) it.copy(detail = details) else it
                        })
                        report = update(report)
                        termReports = termReports.mapValues { update(it.value) }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (sessions.isCurrent(expectedSession)) GlassToaster.show(loadError(e)) }
                finally { detailRequests.remove(detailKey) }
            }
            Unit
        }) else null)

    // Catalog and overall results are independent: a failed catalog cannot discard grades.
    LaunchedEffect(revision, session.token) {
        if (0 !in supportedTabs) { loading = false; return@LaunchedEffect }
        if (catalog != null && revision == 0) return@LaunchedEffect
        catalogError = ""; catalogLoading = true
        try {
            if (com.tyust.course.academic.plugin.AcademicProviderRegistry.hasCapability(school, "study.terms")) {
                val loaded = withContext(Dispatchers.IO) { AcademicStudyBridge.reader(school, account, expectedSession).catalog() }
                if (sessions.isCurrent(expectedSession)) catalog = loaded
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (sessions.isCurrent(expectedSession)) catalogError = loadError(e) }
        finally { if (sessions.isCurrent(expectedSession) && coroutineContext[kotlinx.coroutines.Job]?.isActive == true) { catalogLoading = false; catalogAttempted = true } }
    }
    LaunchedEffect(overallRevision, session.token) {
        if (0 !in supportedTabs || (reportLoaded && overallRevision == 0)) return@LaunchedEffect
        overallLoading = true; overallError = ""
        try {
            val loaded = withContext(Dispatchers.IO) { AcademicStudyBridge.reader(school, account, expectedSession).grades() }
            if (sessions.isCurrent(expectedSession)) { report = loaded; reportLoaded = true }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (sessions.isCurrent(expectedSession)) overallError = loadError(e) }
        finally { if (sessions.isCurrent(expectedSession)) overallLoading = false }
    }
    LaunchedEffect(terms, catalog?.currentTerm?.id) {
        // Only an unchosen default may follow a late school-current-term response.
        if (semester.isBlank() || (!semesterChosen && appliedCatalogTerm != catalog?.currentTerm?.id)) {
            val initial = initialGradeSemester(terms, catalog, standardTerms,
                calendarDate?.calendar() ?: java.util.Calendar.getInstance())
            browse = applyGradeDefault(browse, semesterChosen, appliedCatalogTerm != catalog?.currentTerm?.id,
                initial, terms.firstOrNull { it.id == initial }?.name.orEmpty())
        }
        appliedCatalogTerm = catalog?.currentTerm?.id
    }
    val selectedTerm = terms.firstOrNull { it.id == semester }
    val missingTerm = semester.isNotBlank() && selectedTerm == null && catalog != null && !catalogLoading
    val selectionError = when {
        missingTerm -> "学校当前学期目录中没有此学期，请重新选择或刷新列表"
        semester.isNotBlank() && selectedTerm == null && catalogError.isNotBlank() -> catalogError
        semester.isNotBlank() && selectedTerm == null && catalogAttempted && !catalogLoading &&
            (reportLoaded || overallError.isNotBlank()) -> overallError.ifBlank { "学校暂未返回此学期，请刷新学期列表或重新选择" }
        else -> ""
    }
    LaunchedEffect(semester, selectedTerm != null, termRevision, session.token) {
        if (semester.isBlank() || 0 !in supportedTabs || selectedTerm == null) { loading = false; return@LaunchedEffect }
        val requested = semester
        if (termReports.containsKey(requested)) { loading = false; return@LaunchedEffect }
        loading = true; error = ""
        try {
            val loaded = withContext(Dispatchers.IO) { AcademicStudyBridge.reader(school, account, expectedSession).grades(selectedTerm).forSemester(requested) }
            if (sessions.isCurrent(expectedSession) && latestSemester == requested) termReports = termReports + (requested to loaded)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (sessions.isCurrent(expectedSession) && latestSemester == requested) error = loadError(e) }
        finally { if (sessions.isCurrent(expectedSession) && latestSemester == requested && coroutineContext[kotlinx.coroutines.Job]?.isActive == true) loading = false }
    }
    LaunchedEffect(tab, examRevision, session.token) {
        if (tab != 2 || examsLoaded || 2 !in supportedTabs) return@LaunchedEffect
        examLoading = true; examError = ""
        try {
            val loaded = withContext(Dispatchers.IO) {
                val reader = AcademicStudyBridge.reader(school, account, expectedSession)
                reader.exams((catalog ?: reader.catalog()).currentTerm).map(AcademicStudyBridge::exam)
            }
            if (sessions.isCurrent(expectedSession)) { exams = loaded; examsLoaded = true }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (sessions.isCurrent(expectedSession)) examError = loadError(e) }
        finally { if (sessions.isCurrent(expectedSession) && coroutineContext[kotlinx.coroutines.Job]?.isActive == true) examLoading = false }
    }
    val currentReport = termReports[semester]
    val semesterGrades = remember(currentReport, session.token) { currentReport?.grades.orEmpty().map(::gradeUi) }
    val overallGrades = remember(report, session.token) { report.grades.map(::gradeUi) }
    // Keep the saved label visible while a catalog is loading or temporarily unavailable.
    val displayTerms = remember(terms, semester, browse.termLabel) {
        if (semester.isNotBlank() && terms.none { it.id == semester })
            listOf(AcademicTerm(semester, browse.termLabel.ifBlank { semester })) + terms else terms
    }
    val semesterIds = remember(displayTerms) { displayTerms.map { it.id } }
    val semesterLabels = remember(displayTerms) { displayTerms.associate { it.id to it.name } }
    val overallStats by produceState(
        initialValue = com.tyust.course.ui.screen.OverallStatsUi("--", "--", 0, 0, 0, 0, 0), report
    ) { value = withContext(Dispatchers.Default) { AcademicStudyBridge.stats(report) } }
    com.tyust.course.ui.system.ReportPageContent(report.grades.isNotEmpty() || semesterGrades.isNotEmpty() || exams.isNotEmpty())
    com.tyust.course.ui.system.ReportInitialPageReady(when (tab) {
        1 -> reportLoaded || overallError.isNotBlank()
        2 -> examsLoaded || examError.isNotBlank()
        else -> termReports.containsKey(semester) || error.isNotBlank() || selectionError.isNotBlank() ||
            (catalogAttempted && !catalogLoading && terms.isEmpty() && (reportLoaded || overallError.isNotBlank())) || supportedTabs.isEmpty()
    })
    GradesScreen(currentTab = tab, onTabChange = ::chooseTab,
        semesterGrades = semesterGrades, semesters = semesterIds, semesterLabels = semesterLabels,
        currentSemester = semester, onSemesterChange = { semesterChosen = true; chooseTerm(it, semesterLabels[it].orEmpty()); error = "" },
        semesterIsLoading = loading || (selectedTerm == null && catalogLoading) || (terms.isEmpty() && overallLoading), overallGrades = overallGrades,
        overallStats = overallStats, overallIsLoading = overallLoading,
        examList = exams, examIsLoading = examLoading,
        onRefresh = { when (tab) { 2 -> { examsLoaded = false; examRevision++ }; 1 -> overallRevision++; else -> { termReports = termReports - semester; revision++; termRevision++ } } },
        semesterError = selectionError.ifBlank { error }.ifBlank { if (terms.isEmpty()) catalogError.ifBlank { overallError } else "" },
        overallError = overallError, examError = examError,
        onExportGrades = { exportAcademicGrades(context, it) }, supportedTabs = supportedTabs,
        semestersLoading = catalogLoading, semestersError = catalogError,
        onRefreshSemesters = {
            revision++
            if (!com.tyust.course.academic.plugin.AcademicProviderRegistry.hasCapability(school, "study.terms")) overallRevision++
        })
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

package com.tyust.course.ui.route

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.tyust.course.ui.system.rememberPageData
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.tyust.course.academic.*
import com.tyust.course.study.*
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
fun AcademicGradesRoute(school: SchoolConfig, isActive: Boolean = true) {
    val providerRevision by com.tyust.course.academic.plugin.AcademicProviderRegistry.revision.collectAsState()
    val configuration = com.tyust.course.academic.plugin.PluginJson.canonical(school.toJson())
    val provider = remember(configuration, providerRevision) {
        val registry = com.tyust.course.academic.plugin.AcademicProviderRegistry
        listOf("study.terms", "study.grades", "study.exams", "study.gradeDetails")
            .joinToString(":") { registry.operationProvider(school, it)?.digest.orEmpty() }
    }
    val account = UserManager.getInstance().currentAccountStorageKey
    // Dispose the old coroutine/effect scope even when the installed package is unchanged.
    key(account, provider, configuration) { AcademicGradesContent(school, account, provider, isActive) }
}

@Composable
private fun AcademicGradesContent(school: SchoolConfig, account: String, provider: String, isActive: Boolean) {
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
    val canQueryExams = com.tyust.course.academic.plugin.AcademicProviderRegistry.hasCapability(school, "study.exams")
    val supportedTabs = buildSet {
        if (com.tyust.course.academic.plugin.AcademicProviderRegistry.hasCapability(school, "study.grades")) { add(0); add(1) }
        add(2) // Manual exams remain available without an automatic provider.
    }
    LaunchedEffect(supportedTabs) { if (tab !in supportedTabs && supportedTabs.isNotEmpty()) browse = browse.copy(tab = supportedTabs.first()) }
    val cacheProviders = remember(provider) {
        listOf("study.terms", "study.grades", "study.exams", "study.gradeDetails").joinToString(":") {
            val manifest = com.tyust.course.academic.plugin.AcademicProviderRegistry.operationProvider(school, it)?.manifest
            manifest?.id.orEmpty() + ":" + AcademicDataScope.digest(listOf(
                com.tyust.course.academic.plugin.PluginJson.canonical(manifest?.json?.optJSONObject("builtinConfig") ?: org.json.JSONObject()),
                com.tyust.course.academic.plugin.PluginJson.canonical(manifest?.json?.optJSONObject("studyOptions") ?: org.json.JSONObject())))
        }
    }
    val dataScope = remember(account, school, cacheProviders, termFormat) {
        AcademicDataScope.forSchool(account, school, cacheProviders, termFormat)
    }
    val cache = dataScope.pageCacheKey(provider)
    val store = remember(context, sessions) { AcademicSnapshotStore.from(context, sessions) }
    fun snapshot(kind: String, term: String = "") = store.read(dataScope, kind, term)
    val savedCatalog = remember(dataScope) { runCatching { snapshot("terms")?.data?.let(StudyDataJson::catalog) }.getOrNull() }
    val savedReport = remember(dataScope) { runCatching { snapshot("overall")?.data?.let(StudyDataJson::report) }.getOrNull() }
    var overallAt by remember(dataScope) { mutableLongStateOf(snapshot("overall")?.syncedAt ?: 0L) }
    var termAt by remember(dataScope, semester) { mutableLongStateOf(snapshot("grades", semester)?.syncedAt ?: 0L) }
    var examAt by remember(dataScope) { mutableLongStateOf(0L) }
    suspend fun saveSnapshot(kind: String, term: String, data: org.json.JSONObject): Long {
        val requestJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val saved = withContext(Dispatchers.IO) { store.save(dataScope, kind, term, data, expectedSession) { requestJob?.isActive != false } }
        return if (saved) snapshot(kind, term)?.syncedAt ?: 0L else 0L
    }
    var report by rememberPageData("$cache:overall") { savedReport ?: AcademicGradeReport(emptyList()) }
    var reportLoaded by rememberPageData("$cache:overall.loaded") { savedReport != null }
    var catalog by rememberPageData<AcademicStudyCatalog?>("$cache:terms") { savedCatalog }
    var termReports by rememberPageData<Map<String, AcademicGradeReport>>("$cache:reports") {
        ((savedCatalog?.terms?.map { it.id }.orEmpty()) + semester).distinct().mapNotNull { id ->
            runCatching { snapshot("grades", id)?.data?.let(StudyDataJson::report)?.let { id to it } }.getOrNull()
        }.toMap()
    }
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
    val examTerm = semester.ifBlank { catalog?.currentTerm?.id ?: AcademicStudyReader.calendarTerm().id }
    var exams by rememberPageData<List<ExamItemUi>>("$cache:exams:$examTerm") {
        runCatching { snapshot("exams", examTerm)?.data?.let(ExamRecords::fromJson)?.map { it.exam.copy(sourceId = it.sourceId) } }.getOrNull().orEmpty()
    }
    var examsLoaded by rememberPageData("$cache:exams.loaded:$examTerm") { snapshot("exams", examTerm) != null || !canQueryExams }
    var examLoading by remember { mutableStateOf(false) }
    var examError by remember { mutableStateOf("") }
    var examRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(dataScope, examTerm) { examAt = snapshot("exams", examTerm)?.syncedAt ?: 0L }
    val examManagement = rememberExamManagement(store, dataScope, examTerm, exams)
    val calendarDate = if (standardTerms) rememberGradeCalendarDate() else null
    val localRevision by store.revision.collectAsState()
    val localExamTerms = remember(dataScope.manualScope(), localRevision) { store.manualTerms(dataScope) }
    val terms = remember(catalog, report, termReports, standardTerms, calendarDate, localExamTerms) {
        val remoteTerms = gradeSemesters(catalog, report.grades + termReports.values.flatMap { it.grades }, standardTerms,
            calendarDate?.calendar() ?: java.util.Calendar.getInstance())
        remoteTerms + localExamTerms.filter { id -> remoteTerms.none { it.id == id } }.map(::AcademicTerm)
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
                        saveSnapshot("overall", "", StudyDataJson.report(report))
                        termReports[grade.term]?.let { saveSnapshot("grades", grade.term, StudyDataJson.report(it)) }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (sessions.isCurrent(expectedSession)) GlassToaster.show(loadError(e)) }
                finally { detailRequests.remove(detailKey) }
            }
            Unit
        }) else null)

    // Catalog and overall results are independent: a failed catalog cannot discard grades.
    LaunchedEffect(dataScope, isActive, revision, session.token) {
        if (!isActive) { loading = false; return@LaunchedEffect }
        catalogError = ""; catalogLoading = true
        try {
            if (com.tyust.course.academic.plugin.AcademicProviderRegistry.hasCapability(school, "study.terms")) {
                val loaded = withContext(Dispatchers.IO) { AcademicStudyBridge.reader(school, account, expectedSession).catalog() }
                if (sessions.isCurrent(expectedSession)) { saveSnapshot("terms", "", StudyDataJson.catalog(loaded)); catalog = loaded }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (sessions.isCurrent(expectedSession)) catalogError = loadError(e) }
        finally { if (sessions.isCurrent(expectedSession) && coroutineContext[kotlinx.coroutines.Job]?.isActive == true) { catalogLoading = false; catalogAttempted = true } }
    }
    LaunchedEffect(dataScope, isActive, tab, overallRevision, session.token) {
        if (!isActive || tab == 2 || 0 !in supportedTabs) return@LaunchedEffect
        overallLoading = true; overallError = ""
        try {
            val loaded = withContext(Dispatchers.IO) { AcademicStudyBridge.reader(school, account, expectedSession).grades() }
            if (sessions.isCurrent(expectedSession)) { overallAt = saveSnapshot("overall", "", StudyDataJson.report(loaded)); report = loaded; reportLoaded = true }
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
    LaunchedEffect(dataScope, isActive, tab, semester, selectedTerm != null, termRevision, session.token) {
        if (!isActive || tab != 0 || semester.isBlank() || 0 !in supportedTabs || selectedTerm == null) { loading = false; return@LaunchedEffect }
        val requested = semester
        if (!termReports.containsKey(requested)) runCatching {
            snapshot("grades", requested)?.data?.let(StudyDataJson::report)?.let { termReports = termReports + (requested to it) }
        }
        loading = true; error = ""
        try {
            val loaded = withContext(Dispatchers.IO) { AcademicStudyBridge.reader(school, account, expectedSession).grades(selectedTerm).forSemester(requested) }
            if (sessions.isCurrent(expectedSession)) {
                val at = saveSnapshot("grades", requested, StudyDataJson.report(loaded))
                if (latestSemester == requested) { termReports = termReports + (requested to loaded); termAt = at }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (sessions.isCurrent(expectedSession) && latestSemester == requested) error = loadError(e) }
        finally { if (sessions.isCurrent(expectedSession) && latestSemester == requested && coroutineContext[kotlinx.coroutines.Job]?.isActive == true) loading = false }
    }
    LaunchedEffect(dataScope, isActive, tab, examTerm, examRevision, session.token) {
        if (!isActive || tab != 2 || !canQueryExams) {
            examLoading = false
            if (!canQueryExams) { examsLoaded = true; examError = "本校暂未支持自动查询，可手动添加考试" }
            return@LaunchedEffect
        }
        examLoading = true; examError = ""
        try {
            val loaded = withContext(Dispatchers.IO) {
                val reader = AcademicStudyBridge.reader(school, account, expectedSession)
                val requestedTerm = catalog?.terms?.firstOrNull { it.id == examTerm } ?: AcademicTerm(examTerm)
                reader.exams(requestedTerm).map(AcademicStudyBridge::exam)
            }
            if (sessions.isCurrent(expectedSession)) {
                examAt = saveSnapshot("exams", examTerm, ExamRecords.toJson(ExamRecords.automatic(examTerm, loaded)))
                exams = loaded; examsLoaded = true
            }
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
    com.tyust.course.ui.system.ReportPageContent(report.grades.isNotEmpty() || semesterGrades.isNotEmpty() || examManagement.items.isNotEmpty())
    com.tyust.course.ui.system.ReportInitialPageReady(when (tab) {
        1 -> reportLoaded || overallError.isNotBlank()
        2 -> true // The manual editor is ready even while automatic data is loading.
        else -> termReports.containsKey(semester) || error.isNotBlank() || selectionError.isNotBlank() ||
            (catalogAttempted && !catalogLoading && terms.isEmpty() && (reportLoaded || overallError.isNotBlank())) || supportedTabs.isEmpty()
    })
    GradesScreen(currentTab = tab, onTabChange = ::chooseTab,
        semesterGrades = semesterGrades, semesters = semesterIds, semesterLabels = semesterLabels,
        currentSemester = semester, onSemesterChange = { semesterChosen = true; chooseTerm(it, semesterLabels[it].orEmpty()); error = "" },
        semesterIsLoading = loading || (selectedTerm == null && catalogLoading) || (terms.isEmpty() && overallLoading), overallGrades = overallGrades,
        overallStats = overallStats, overallIsLoading = overallLoading,
        examList = examManagement.items, examIsLoading = examLoading,
        onAddExam = examManagement.add, onEditExam = examManagement.edit, onDeleteExam = examManagement.delete,
        syncStatus = academicSyncStatus(when (tab) { 2 -> examAt; 1 -> overallAt; else -> termAt },
            when (tab) { 2 -> examLoading; 1 -> overallLoading; else -> loading },
            when (tab) { 2 -> examError; 1 -> overallError; else -> error }),
        onRefresh = { when (tab) { 2 -> { examsLoaded = false; examRevision++ }; 1 -> overallRevision++; else -> { revision++; termRevision++ } } },
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

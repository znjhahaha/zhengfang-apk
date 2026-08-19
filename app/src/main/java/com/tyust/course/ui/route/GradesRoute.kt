package com.tyust.course.ui.route

import com.tyust.course.ui.system.GlassToaster
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.tyust.course.demo.DemoData
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.network.CourseApiClient
import com.tyust.course.utils.SessionRenewer
import com.tyust.course.ui.screen.ExamItemUi
import com.tyust.course.ui.screen.GradeItemUi
import com.tyust.course.ui.screen.GradesScreen
import com.tyust.course.ui.screen.OverallStatsUi
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.Calendar
import java.util.regex.Pattern

@Composable
fun GradesRoute() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDemoMode = remember { UserManager.getInstance().isDemoMode }
    
    // State
    var currentTab by remember { mutableIntStateOf(0) }
    
    var semesterGrades by remember { mutableStateOf<List<GradeItemUi>>(emptyList()) }
    var semesters by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentSemester by remember { mutableStateOf("") }
    var semesterIsLoading by remember { mutableStateOf(false) }

    var overallGrades by remember { mutableStateOf<List<GradeItemUi>>(emptyList()) }
    var overallStats by remember { mutableStateOf(OverallStatsUi("0.0", "0", 0, 0, 0, 0, 0)) }
    var overallIsLoading by remember { mutableStateOf(false) }

    // 考试安排状态
    var examList by remember { mutableStateOf<List<ExamItemUi>>(emptyList()) }
    var examIsLoading by remember { mutableStateOf(false) }

    // Init semesters
    LaunchedEffect(Unit) {
        val list = if (isDemoMode) {
            listOf("2025-2026-2", "2025-2026-1", "2024-2025-2", "2024-2025-1")
        } else {
            val calendar = Calendar.getInstance()
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH)
            val startYear = if (month >= 7) year else year - 1
            buildList {
                for (y in startYear downTo startYear - 3) {
                    add("$y-${y + 1}-1")
                    add("$y-${y + 1}-2")
                }
            }
        }
        semesters = list
        if (list.isNotEmpty()) currentSemester = list[0]
    }

    // Handlers
    fun runOnUiThread(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    fun isCurrentAccount(accountKey: String): Boolean {
        return UserManager.getInstance().currentAccountStorageKey == accountKey
    }

    fun runOnUiThreadForAccount(accountKey: String, action: () -> Unit) {
        runOnUiThread {
            if (isCurrentAccount(accountKey)) action()
        }
    }

    // 检测到登录状态失效时，先尝试静默续期，续不上才提示用户
    fun handleExpiredCookie(requestAccountKey: String, retryAction: () -> Unit) {
        if (!isCurrentAccount(requestAccountKey)) return
        val sendExpiredBroadcast = {
            val intent = Intent(CourseApiClient.ACTION_COOKIE_EXPIRED).apply {
                setPackage(context.packageName)
                putExtra(CourseApiClient.EXTRA_ACCOUNT_STORAGE_KEY, requestAccountKey)
            }
            context.sendBroadcast(intent)
        }

        if (SessionRenewer.canRenew()) {
            runOnUiThread {
                GlassToaster.show("登录状态已失效，正在自动续期…")
            }
            SessionRenewer.renew(context) { renewed ->
                if (!isCurrentAccount(requestAccountKey)) return@renew
                if (renewed) {
                    Log.d("GradesRoute", "自动续期成功，重试操作")
                    retryAction()
                } else {
                    GlassToaster.show("登录状态已失效，请重新登录")
                    sendExpiredBroadcast()
                }
            }
        } else {
            runOnUiThread {
                GlassToaster.show("登录状态已失效，请重新登录")
                sendExpiredBroadcast()
            }
        }
    }

    fun isLoginPageHtml(html: String): Boolean {
        return html.contains("用户登录") || html.contains("登 录") ||
               html.contains("slogin.html") || html.contains("id=\"pwd\"") ||
               html.contains("name=\"yhm\"") || html.contains("notLogin")
    }

    // Logic for Semester Grades
    var loadSemesterGrades by remember { mutableStateOf<(() -> Unit)?>(null) }
    loadSemesterGrades = loadSemesterGrades@{
        if (isDemoMode) {
            semesterGrades = DemoData.semesterGrades()
            semesterIsLoading = false
            return@loadSemesterGrades
        }
        val userManager = UserManager.getInstance()
        val school = userManager.currentSchool
        val requestAccountKey = userManager.currentAccountStorageKey
        val requestSemester = currentSemester
        if (school != null && requestSemester.isNotEmpty()) {

            semesterIsLoading = true
            CourseApiClient.getInstance().fetchGrades(school, requestSemester, object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThreadForAccount(requestAccountKey) {
                        semesterIsLoading = false
                        GlassToaster.show("加载失败：${e.message}")
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val json = response.body?.string() ?: ""

                    // 检测Cookie过期
                    if (isLoginPageHtml(json)) {
                        runOnUiThreadForAccount(requestAccountKey) { semesterIsLoading = false }
                        handleExpiredCookie(requestAccountKey) { loadSemesterGrades?.invoke() }
                        return
                    }

                    // 先从接口B解析基础成绩（清除接口B返回的不完整分项数据）
                    val items = GradesLogic.parseGradesJson(json).map { it.copy(detail = "") }

                    if (items.isEmpty()) {
                        runOnUiThreadForAccount(requestAccountKey) {
                            semesterIsLoading = false
                            semesterGrades = emptyList()
                        }
                    } else {
                        // 始终请求接口A获取完整分项详情
                        // 接口B可能只返回部分分项（如仅"平时"），不能作为完整分项数据使用
                        CourseApiClient.getInstance().fetchGradeDetails(school, requestSemester, object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                Log.w("GradesRoute", "Detail fetch failed: ${e.message}")
                                // 接口A失败时，尝试用接口B原始数据中的分项作为兜底
                                val fallbackItems = GradesLogic.parseGradesJson(json)
                                runOnUiThreadForAccount(requestAccountKey) {
                                    semesterIsLoading = false
                                    semesterGrades = fallbackItems
                                }
                            }

                            override fun onResponse(call: Call, response: Response) {
                                val detailJson = response.body?.string() ?: ""
                                Log.d("GradesRoute", "Detail response length: ${detailJson.length}")
                                val merged = GradesLogic.mergeDetails(items, detailJson)
                                runOnUiThreadForAccount(requestAccountKey) {
                                    semesterIsLoading = false
                                    semesterGrades = merged
                                }
                            }
                        })
                    }
                }
            })
        }
    }
    
    // Trigger load on semester change
    LaunchedEffect(currentSemester) {
        if (currentSemester.isNotEmpty()) loadSemesterGrades?.invoke()
    }

    // Logic for Overall Grades
    var loadOverallGrades by remember { mutableStateOf<(() -> Unit)?>(null) }
    loadOverallGrades = loadOverallGrades@{
        if (isDemoMode) {
            overallGrades = DemoData.overallGrades()
            overallStats = DemoData.overallStats
            overallIsLoading = false
            return@loadOverallGrades
        }
        val userManager = UserManager.getInstance()
        val school = userManager.currentSchool
        val requestAccountKey = userManager.currentAccountStorageKey
        if (school != null && !overallIsLoading) {

            overallIsLoading = true
            overallGrades = emptyList() // Clear

            CourseApiClient.getInstance().fetchOverallGradesIndex(school, object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThreadForAccount(requestAccountKey) {
                        overallIsLoading = false
                        GlassToaster.show("获取参数失败：${e.message}")
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val html = response.body?.string() ?: ""
                    
                    // Parse Index Logic
                    if (isLoginPageHtml(html)) {
                        runOnUiThreadForAccount(requestAccountKey) { overallIsLoading = false }
                        handleExpiredCookie(requestAccountKey) { loadOverallGrades?.invoke() }
                        return
                    }

                    val doc = Jsoup.parse(html)
                    val (gpa, credits, count) = GradesLogic.extractSummaryInfo(doc, html)
                    
                    // Update initial stats from summary
                    runOnUiThreadForAccount(requestAccountKey) {
                        overallStats = OverallStatsUi(gpa, credits.toString(), count, 0, 0, 0, 0)
                    }

                    val xfyqjdIds = GradesLogic.extractXfyqjdIds(doc, html)
                    val xh_id = doc.selectFirst("input[name=xh_id]")?.attr("value") ?: ""
                    val cjlrxn = doc.selectFirst("input[name=cjlrxn]")?.attr("value") ?: ""
                    val cjlrxq = doc.selectFirst("input[name=cjlrxq]")?.attr("value") ?: ""

                    if (xfyqjdIds.isEmpty()) {
                        runOnUiThreadForAccount(requestAccountKey) { overallIsLoading = false }
                        return
                    }
                    
                    // Recursive Fetch
                    GradesLogic.fetchGradesDetailsRecursive(
                        school, xfyqjdIds.toList(), 0, xh_id, cjlrxn, cjlrxq, mutableListOf(),
                        onComplete = { resultGrades ->
                             runOnUiThreadForAccount(requestAccountKey) {
                                 overallIsLoading = false
                                 overallGrades = resultGrades
                                 overallStats = GradesLogic.calculateStats(resultGrades, gpa, credits, count)
                             }
                        }
                    )
                }
            })
        }
    }

    // Logic for Exam Schedule
    var loadExamSchedule by remember { mutableStateOf<(() -> Unit)?>(null) }
    loadExamSchedule = loadExamSchedule@{
        if (isDemoMode) {
            examList = DemoData.exams()
            examIsLoading = false
            return@loadExamSchedule
        }
        val userManager = UserManager.getInstance()
        val school = userManager.currentSchool
        val requestAccountKey = userManager.currentAccountStorageKey
        if (school != null && !examIsLoading) {

            examIsLoading = true
            examList = emptyList()

            // 计算当前学年学期参数 (与课表一致的逻辑)
            val calendar = Calendar.getInstance()
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH)
            val xnm = if (month >= 7) year.toString() else (year - 1).toString()
            val xqm = if (month >= 7 || month < 2) "3" else "12" // 3=第一学期, 12=第二学期

            CourseApiClient.getInstance().fetchExamSchedule(school, xnm, xqm, object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThreadForAccount(requestAccountKey) {
                        examIsLoading = false
                        GlassToaster.show("获取考试安排失败：${e.message}")
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val json = response.body?.string() ?: ""

                    // 检测Cookie过期
                    if (isLoginPageHtml(json)) {
                        runOnUiThreadForAccount(requestAccountKey) { examIsLoading = false }
                        handleExpiredCookie(requestAccountKey) { loadExamSchedule?.invoke() }
                        return
                    }

                    val items = GradesLogic.parseExamJson(json)
                    runOnUiThreadForAccount(requestAccountKey) {
                        examIsLoading = false
                        examList = items
                    }
                }
            })
        }
    }

    GradesScreen(
        currentTab = currentTab,
        onTabChange = { 
            currentTab = it
            if (it == 0 && semesterGrades.isEmpty() && currentSemester.isNotEmpty()) loadSemesterGrades?.invoke()
            if (it == 1 && overallGrades.isEmpty()) loadOverallGrades?.invoke()
            if (it == 2 && examList.isEmpty()) loadExamSchedule?.invoke()
        },
        semesterGrades = semesterGrades,
        semesters = semesters,
        currentSemester = currentSemester,
        onSemesterChange = { currentSemester = it },
        semesterIsLoading = semesterIsLoading,
        overallGrades = overallGrades,
        overallStats = overallStats,
        overallIsLoading = overallIsLoading,
        examList = examList,
        examIsLoading = examIsLoading,
        onRefresh = {
            when (currentTab) {
                0 -> loadSemesterGrades?.invoke()
                1 -> loadOverallGrades?.invoke()
                2 -> loadExamSchedule?.invoke()
            }
        },
        onExportGrades = { grades ->
            try {
                // 生成 CSV 内容（带 BOM，确保 Excel 正确识别 UTF-8）
                val csv = buildString {
                    // UTF-8 BOM
                    append('\uFEFF')
                    // 表头 - 与用户截图一致
                    appendLine("学年,学期,课程名称,课程代码,开课学院,学分,成绩,成绩分项")
                    // 数据行
                    grades.forEach { item ->
                        // 学年: xnm "2024" -> "2024-2025"
                        val yearRaw = item.year.ifEmpty { "--" }
                        val yearDisplay = yearRaw.toIntOrNull()?.let { "$it-${it + 1}" } ?: yearRaw
                        // 学期: xqm "3" -> "1", "12" -> "2"
                        val termDisplay = when (item.term) {
                            "3" -> "1"
                            "12" -> "2"
                            else -> item.term.ifEmpty { "--" }
                        }
                        val year = escapeCsv(yearDisplay)
                        val term = escapeCsv(termDisplay)
                        val name = escapeCsv(item.courseName)
                        val code = escapeCsv(item.courseCode.ifEmpty { "--" })
                        val college = escapeCsv(item.college.ifEmpty { "--" })
                        val credits = escapeCsv(item.credits)
                        val grade = escapeCsv(item.grade)
                        val detail = escapeCsv(item.detail)
                        appendLine("$year,$term,$name,$code,$college,$credits,$grade,$detail")
                    }
                }

                // 写入外部缓存目录
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val fileName = "成绩单_$timestamp.csv"
                val cacheDir = java.io.File(context.externalCacheDir, "exports")
                cacheDir.mkdirs()
                val file = java.io.File(cacheDir, fileName)
                file.writeText(csv, Charsets.UTF_8)

                // 通过 FileProvider 分享
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "导出成绩单"))
            } catch (e: Exception) {
                Log.e("GradesRoute", "导出成绩失败: ${e.message}", e)
                GlassToaster.show("导出失败：${e.message}")
            }
        }
    )
}

// CSV 单元格转义：处理逗号、引号、换行符
private fun escapeCsv(value: String): String {
    return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
        "\"${value.replace("\"", "\"\"")}\""
    } else {
        value
    }
}

// Logic Object
private object GradesLogic {
    fun parseGradesJson(json: String): List<GradeItemUi> {
        val result = mutableListOf<GradeItemUi>()
        try {
            var items: JSONArray? = null
            try {
                val obj = JSONObject(json)
                items = obj.optJSONArray("items")
            } catch (e: Exception) {
                items = JSONArray(json)
            }
            if (items == null) return result

            // 按 kcmc|jxb_id 分组，合并分项成绩
            val grouped = LinkedHashMap<String, MutableList<JSONObject>>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val name = getOptString(item, "kcmc", "KCMC")
                if (name.isEmpty()) continue
                val jxbId = item.optString("jxb_id", item.optString("JXB_ID", ""))
                    .ifEmpty { item.optString("kch_id", item.optString("KCH_ID", "")) }
                val key = "$name|$jxbId"
                grouped.getOrPut(key) { mutableListOf() }.add(item)
            }

            for ((_, records) in grouped) {
                val first = records[0]
                val name = getOptString(first, "kcmc", "KCMC")

                // 收集分项明细
                val details = records.mapNotNull { r ->
                    val xmblmc = r.optString("xmblmc", "").trim()
                    val xmcj = r.optString("xmcj", "").trim()
                    if (xmblmc.isEmpty() || xmcj.isEmpty()) return@mapNotNull null
                    val xmbl = r.optString("xmbl", "").trim()
                    if (xmbl.isNotEmpty()) "$xmblmc(${xmbl}%): $xmcj"
                    else "$xmblmc: $xmcj"
                }.distinct()

                val jxbId = first.optString("jxb_id", first.optString("JXB_ID", ""))
                    .ifEmpty { first.optString("kch_id", first.optString("KCH_ID", "")) }

                result.add(
                    GradeItemUi(
                        courseName = name,
                        grade = getOptString(first, "cj", "CJ", "--"),
                        credits = getOptString(first, "xf", "XF", "0"),
                        gpa = getOptString(first, "jd", "JD", "0"),
                        courseType = getOptString(first, "kcxzmc", "KCXZMC", ""),
                        year = first.optString("xnm", first.optString("XNM", "")),
                        term = first.optString("xqm", first.optString("XQM", "")),
                        college = first.optString("kkbmmc", first.optString("KKBMMC", "")),
                        courseCode = first.optString("kch", first.optString("KCH", "")),
                        teachingClass = first.optString("jxbmc", first.optString("JXBMC", "")),
                        jxbId = jxbId,
                        detail = details.joinToString(" | ")
                    )
                )
            }
        } catch (e: Exception) { }
        return result
    }

    fun mergeDetails(baseGrades: List<GradeItemUi>, detailJson: String): List<GradeItemUi> {
        // 参照 Go 端 fetchViaJsonAPI 策略：
        // 1. 按 jxb_id 分组接口A的分项数据
        // 2. 通过 jxb_id / courseCode / courseName 匹配到接口B的基础成绩
        // 3. 智能推算缺失的期末成绩
        val detailGroupMap = parseDetailGroupMap(detailJson)
        if (detailGroupMap.isEmpty()) {
            Log.d("GradesRoute", "mergeDetails: detailGroupMap is empty, no detail data available")
            return baseGrades
        }

        Log.d("GradesRoute", "mergeDetails: ${detailGroupMap.size} detail groups, ${baseGrades.size} base grades")

        return baseGrades.map { grade ->
            if (grade.detail.isNotEmpty()) return@map grade

            // 按优先级匹配: jxb_id > courseCode > courseName
            val matchedKey = detailGroupMap.keys.firstOrNull { key ->
                grade.jxbId.isNotEmpty() && key == grade.jxbId
            } ?: detailGroupMap.keys.firstOrNull { key ->
                // 尝试用 courseCode 匹配 (有些学校接口A的 jxb_id 与接口B不同)
                val entries = detailGroupMap[key] ?: return@firstOrNull false
                entries.isNotEmpty() && grade.courseCode.isNotEmpty() &&
                    entries[0].optString("kch", entries[0].optString("kch_id", "")).trim() == grade.courseCode
            } ?: detailGroupMap.keys.firstOrNull { key ->
                // 尝试用课程名匹配
                val entries = detailGroupMap[key] ?: return@firstOrNull false
                entries.isNotEmpty() && entries[0].optString("kcmc", "").trim() == grade.courseName
            } ?: detailGroupMap.keys.firstOrNull { key ->
                val entries = detailGroupMap[key] ?: return@firstOrNull false
                entries.isNotEmpty() && run {
                    val entryName = entries[0].optString("kcmc", "").trim()
                    grade.courseName.startsWith(entryName) || entryName.startsWith(grade.courseName)
                }
            }

            if (matchedKey != null) {
                val records = detailGroupMap[matchedKey] ?: return@map grade
                val detail = buildDetailString(records, grade.grade)
                Log.d("GradesRoute", "mergeDetails: matched '${grade.courseName}' -> detail='$detail'")
                if (detail.isNotEmpty()) grade.copy(detail = detail) else grade
            } else {
                Log.d("GradesRoute", "mergeDetails: no match for '${grade.courseName}' (jxbId=${grade.jxbId})")
                grade
            }
        }
    }

    /**
     * 参照 Go 端逻辑，从接口A的分项记录构建 detail 字符串
     * 格式: "平时(30%): 90 | 期末(70%): 97.1"
     * 包含期末成绩智能推算功能
     */
    private fun buildDetailString(records: List<JSONObject>, totalScoreStr: String): String {
        val totalScore = totalScoreStr.replace("[^0-9.]".toRegex(), "").toDoubleOrNull() ?: 0.0
        val detailParts = mutableListOf<String>()
        var sumRatio = 0.0
        var sumWeightedScore = 0.0
        var hasQimo = false

        for (r in records) {
            val typeName = r.optString("xmblmc", "").trim()
            val ratioStr = r.optString("xmbl", "").trim()
            val scoreStr = r.optString("xmcj", "").trim()

            if (typeName.isEmpty() || scoreStr.isEmpty()) continue

            if (typeName.contains("期末")) hasQimo = true

            val ratio = ratioStr.toDoubleOrNull() ?: 0.0
            val score = scoreStr.toDoubleOrNull() ?: 0.0
            sumRatio += ratio
            sumWeightedScore += score * ratio / 100.0

            if (ratio > 0) {
                detailParts.add("$typeName(${ratio.toInt()}%): $scoreStr")
            } else {
                detailParts.add("$typeName: $scoreStr")
            }
        }

        // 智能推算期末成绩（参照 Go 端逻辑）
        if (!hasQimo && sumRatio < 100 && sumRatio > 0 && totalScore > 0) {
            val remainingRatio = 100 - sumRatio
            if (remainingRatio > 0) {
                val qimoScore = (totalScore - sumWeightedScore) / (remainingRatio / 100.0)
                val qimoFmt = if (qimoScore % 1.0 != 0.0) {
                    String.format("%.1f", qimoScore)
                } else {
                    qimoScore.toInt().toString()
                }
                detailParts.add("期末(${remainingRatio.toInt()}%): $qimoFmt")
            }
        }

        return detailParts.joinToString(" | ")
    }

    /**
     * 解析接口A的JSON数据，按 jxb_id 分组返回原始 JSONObject 列表
     * 与 Go 端 detailsGroup 逻辑一致
     */
    private fun parseDetailGroupMap(json: String): Map<String, List<JSONObject>> {
        val grouped = LinkedHashMap<String, MutableList<JSONObject>>()
        try {
            var items: JSONArray? = null
            try {
                val obj = JSONObject(json)
                items = obj.optJSONArray("items")
            } catch (e: Exception) {
                try { items = JSONArray(json) } catch (_: Exception) {}
            }
            if (items == null) {
                Log.w("GradesRoute", "parseDetailGroupMap: no 'items' array found in detail JSON")
                return grouped
            }

            Log.d("GradesRoute", "parseDetailGroupMap: ${items.length()} raw items from API A")

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                // 接口A的 jxb_id 是关联分项和总评的 key
                val jxbId = item.optString("jxb_id", "").trim()
                    .ifEmpty { item.optString("JXB_ID", "").trim() }
                if (jxbId.isEmpty()) continue
                grouped.getOrPut(jxbId) { mutableListOf() }.add(item)
            }

            Log.d("GradesRoute", "parseDetailGroupMap: ${grouped.size} groups by jxb_id")
        } catch (e: Exception) {
            Log.e("GradesRoute", "parseDetailGroupMap error: ${e.message}")
        }
        return grouped
    }
    
    fun extractSummaryInfo(doc: Document, html: String): Triple<String, Double, Int> {
        var extractedGPA = ""
        var extractedCredits = 0.0
        var extractedCourseCount = 0

        var m = Pattern.compile("\\(GPA\\)[：:]\\s*<font[^>]*>([\\d.]+)</font>", Pattern.CASE_INSENSITIVE).matcher(html)
        if (m.find()) extractedGPA = m.group(1)

        if (extractedGPA.isEmpty()) {
             m = Pattern.compile("(\\d+\\.\\d{1,2})").matcher(doc.select("font[style*=color]").text())
             if (m.find()) extractedGPA = m.group(1)
        }

        m = Pattern.compile("(?:已修|获得|总)\\s*学分[：:]\\s*([\\d.]+)").matcher(html)
        if (m.find()) extractedCredits = m.group(1).toDoubleOrNull() ?: 0.0

        m = Pattern.compile("(?:课程|门数)[：:]\\s*(\\d+)").matcher(html)
        if (m.find()) extractedCourseCount = m.group(1).toIntOrNull() ?: 0
        
        return Triple(extractedGPA, extractedCredits, extractedCourseCount)
    }
    
    fun extractXfyqjdIds(doc: Document, html: String): Set<String> {
        val ids = HashSet<String>()
        val pattern = Pattern.compile("xfyqjd_id=[\"']?([A-Fa-f0-9]{20,})[\"']?")
        val matcher = pattern.matcher(html)
        while (matcher.find()) {
            ids.add(matcher.group(1))
        }
        doc.select("input[name=xfyqjd_id], select[name=xfyqjd_id] option").forEach { 
            val v = it.attr("value")
            if (v.length > 20) ids.add(v)
        }
        return ids
    }
    
    fun fetchGradesDetailsRecursive(
        school: SchoolConfig, 
        ids: List<String>, index: Int, xh_id: String, cjlrxn: String, cjlrxq: String,
        accumulatedGrades: MutableList<GradeItemUi>,
        onComplete: (List<GradeItemUi>) -> Unit
    ) {
        if (index >= ids.size) {
            onComplete(accumulatedGrades)
            return
        }

        val xfyqjd_id = ids[index]
        val postBody = "xfyqjd_id=$xfyqjd_id&xh_id=$xh_id&cjlrxn=$cjlrxn&cjlrxq=$cjlrxq&xscjcxkz=0&cjcxkzzt=2&cjztkz=0&cjzt="

        CourseApiClient.getInstance().fetchOverallGradesData(school, postBody, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                fetchGradesDetailsRecursive(school, ids, index + 1, xh_id, cjlrxn, cjlrxq, accumulatedGrades, onComplete)
            }

            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string() ?: ""
                val items = parseGradesJson(json)
                items.forEach { newItem ->
                    if (accumulatedGrades.none { it.courseName == newItem.courseName }) {
                        accumulatedGrades.add(newItem)
                    }
                }
                fetchGradesDetailsRecursive(school, ids, index + 1, xh_id, cjlrxn, cjlrxq, accumulatedGrades, onComplete)
            }
        })
    }
    
    fun calculateStats(grades: List<GradeItemUi>, baseGPA: String, baseCredits: Double, baseCount: Int): OverallStatsUi {
        var totalCredits = 0.0
        var weightedGPA = 0.0
        var excellent = 0
        var good = 0
        var medium = 0
        var pass = 0

        grades.forEach { g ->
            val c = g.credits.toDoubleOrNull() ?: 0.0
            val gps = g.gpa.toDoubleOrNull() ?: 0.0
            totalCredits += c
            weightedGPA += c * gps
            
            val score = g.grade.replace("[^0-9]".toRegex(), "").toIntOrNull() ?: -1
            when {
                score >= 90 -> excellent++
                score >= 80 -> good++
                score >= 70 -> medium++
                score >= 60 -> pass++
            }
        }

        val displayGPA = if (baseGPA.isNotEmpty()) baseGPA else {
            if (totalCredits > 0) String.format("%.2f", weightedGPA / totalCredits) else "0.00"
        }
        val displayCredits = if (totalCredits > 0) String.format("%.1f", totalCredits) else String.format("%.1f", baseCredits)
        val displayCount = if (grades.isNotEmpty()) grades.size else baseCount

        return OverallStatsUi(displayGPA, displayCredits, displayCount, excellent, good, medium, pass)
    }

    private fun getOptString(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.optString(key, null)
            if (!value.isNullOrEmpty() && value != "null") return value
        }
        return ""
    }

    // 解析考试安排 JSON
    fun parseExamJson(json: String): List<ExamItemUi> {
        val list = mutableListOf<ExamItemUi>()
        try {
            val obj = JSONObject(json)
            val items = obj.optJSONArray("items") ?: return list
            
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val courseName = getOptString(item, "kcmc")
                if (courseName.isNotEmpty()) {
                    list.add(
                        ExamItemUi(
                            courseName = courseName,
                            examTime = getOptString(item, "kssj"),
                            location = getOptString(item, "cdmc"),
                            seatNumber = getOptString(item, "zwh"),
                            examName = getOptString(item, "ksmc"),
                            teacher = getOptString(item, "jsxx")
                        )
                    )
                }
            }
        } catch (e: Exception) { }
        return list
    }
}

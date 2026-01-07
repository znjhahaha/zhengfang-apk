package com.tyust.course.ui.route

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.network.CourseApiClient
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
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val startYear = if (month >= 7) year else year - 1
        val list = mutableListOf<String>()
        for (y in startYear downTo startYear - 3) {
            list.add("$y-${y + 1}-1")
            list.add("$y-${y + 1}-2")
        }
        semesters = list
        if (list.isNotEmpty()) currentSemester = list[0]
    }

    // Handlers
    fun runOnUiThread(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    // Logic for Semester Grades
    val loadSemesterGrades = remember {
        fun() {
            val school = UserManager.getInstance().currentSchool
            if (school == null || currentSemester.isEmpty()) return

            semesterIsLoading = true
            CourseApiClient.getInstance().fetchGrades(school, currentSemester, object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        semesterIsLoading = false
                        Toast.makeText(context, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val json = response.body?.string() ?: ""
                    val items = GradesLogic.parseGradesJson(json)
                    runOnUiThread {
                        semesterIsLoading = false
                        semesterGrades = items
                    }
                }
            })
        }
    }
    
    // Trigger load on semester change
    LaunchedEffect(currentSemester) {
        if (currentSemester.isNotEmpty()) loadSemesterGrades()
    }

    // Logic for Overall Grades
    val loadOverallGrades = remember {
        fun() {
            val school = UserManager.getInstance().currentSchool ?: return
            if (overallIsLoading) return
            
            overallIsLoading = true
            overallGrades = emptyList() // Clear

            CourseApiClient.getInstance().fetchOverallGradesIndex(school, object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        overallIsLoading = false
                        Toast.makeText(context, "获取参数失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val html = response.body?.string() ?: ""
                    
                    // Parse Index Logic
                    if (html.contains("用户登录") || html.contains("slogin.html")) {
                        runOnUiThread {
                            overallIsLoading = false
                            Toast.makeText(context, "Cookie已过期，请重新登录", Toast.LENGTH_LONG).show()
                        }
                        return
                    }

                    val doc = Jsoup.parse(html)
                    val (gpa, credits, count) = GradesLogic.extractSummaryInfo(doc, html)
                    
                    // Update initial stats from summary
                    runOnUiThread {
                        overallStats = OverallStatsUi(gpa, credits.toString(), count, 0, 0, 0, 0)
                    }

                    val xfyqjdIds = GradesLogic.extractXfyqjdIds(doc, html)
                    val xh_id = doc.selectFirst("input[name=xh_id]")?.attr("value") ?: ""
                    val cjlrxn = doc.selectFirst("input[name=cjlrxn]")?.attr("value") ?: ""
                    val cjlrxq = doc.selectFirst("input[name=cjlrxq]")?.attr("value") ?: ""

                    if (xfyqjdIds.isEmpty()) {
                        runOnUiThread { overallIsLoading = false }
                        return
                    }
                    
                    // Recursive Fetch
                    GradesLogic.fetchGradesDetailsRecursive(
                        school, xfyqjdIds.toList(), 0, xh_id, cjlrxn, cjlrxq, mutableListOf(),
                        onComplete = { resultGrades ->
                             runOnUiThread {
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
    val loadExamSchedule = remember {
        fun() {
            val school = UserManager.getInstance().currentSchool ?: return
            if (examIsLoading) return
            
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
                    runOnUiThread {
                        examIsLoading = false
                        Toast.makeText(context, "获取考试安排失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val json = response.body?.string() ?: ""
                    val items = GradesLogic.parseExamJson(json)
                    runOnUiThread {
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
            if (it == 0 && semesterGrades.isEmpty() && currentSemester.isNotEmpty()) loadSemesterGrades()
            if (it == 1 && overallGrades.isEmpty()) loadOverallGrades()
            if (it == 2 && examList.isEmpty()) loadExamSchedule()
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
                0 -> loadSemesterGrades()
                1 -> loadOverallGrades()
                2 -> loadExamSchedule()
            }
        }
    )
}

// Logic Object
private object GradesLogic {
    fun parseGradesJson(json: String): List<GradeItemUi> {
        val list = mutableListOf<GradeItemUi>()
        try {
            var items: JSONArray? = null
            try {
                val obj = JSONObject(json)
                items = obj.optJSONArray("items")
            } catch (e: Exception) {
                items = JSONArray(json)
            }

            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val name = getOptString(item, "kcmc", "KCMC")
                    if (name.isNotEmpty()) {
                        list.add(
                            GradeItemUi(
                                courseName = name,
                                grade = getOptString(item, "cj", "CJ", "--"),
                                credits = getOptString(item, "xf", "XF", "0"),
                                gpa = getOptString(item, "jd", "JD", "0"),
                                courseType = getOptString(item, "kcxzmc", "KCXZMC", "")
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) { }
        return list
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

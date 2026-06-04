package com.tyust.course.fragment

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.tyust.course.manager.UserManager
import com.tyust.course.network.CourseApiClient
import com.tyust.course.model.SchoolConfig
import com.tyust.course.ui.screen.GradeItemUi
import com.tyust.course.ui.screen.GradesScreen
import com.tyust.course.ui.screen.OverallStatsUi
import com.tyust.course.ui.theme.CourseSelectorTheme
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
import java.util.HashSet

class GradesFragment : Fragment() {

    // Global State
    private var currentTab by mutableStateOf(0)
    
    // Semester State
    private var semesterGrades by mutableStateOf<List<GradeItemUi>>(emptyList())
    private var semesters by mutableStateOf<List<String>>(emptyList())
    private var currentSemester by mutableStateOf("")
    private var semesterIsLoading by mutableStateOf(false)

    // Overall State
    private var overallGrades by mutableStateOf<List<GradeItemUi>>(emptyList())
    private var overallStats by mutableStateOf(OverallStatsUi("0.0", "0", 0, 0, 0, 0, 0))
    private var overallIsLoading by mutableStateOf(false)
    
    // Extracted Overall Data (Intermediate)
    private var extractedGPA = ""
    private var extractedCredits = 0.0
    private var extractedCourseCount = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                CourseSelectorTheme {
                    GradesScreen(
                        currentTab = currentTab,
                        onTabChange = { 
                            currentTab = it
                            if (it == 0 && semesterGrades.isEmpty() && currentSemester.isNotEmpty()) loadSemesterGrades()
                            if (it == 1 && overallGrades.isEmpty()) loadOverallGrades()
                        },
                        semesterGrades = semesterGrades,
                        semesters = semesters,
                        currentSemester = currentSemester,
                        onSemesterChange = { 
                            currentSemester = it
                            loadSemesterGrades()
                        },
                        semesterIsLoading = semesterIsLoading,
                        overallGrades = overallGrades,
                        overallStats = overallStats,
                        overallIsLoading = overallIsLoading,
                        examList = emptyList(),
                        examIsLoading = false,
                        onRefresh = {
                            if (currentTab == 0) loadSemesterGrades() else loadOverallGrades()
                        }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initSemesters()
        // Load initial data for first tab
        if (currentSemester.isNotEmpty()) {
            loadSemesterGrades()
        }
    }

    private fun initSemesters() {
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
        if (list.isNotEmpty()) {
            currentSemester = list[0]
        }
    }

    // ==================== Semester Grades Logic ====================

    private fun loadSemesterGrades() {
        val school = UserManager.getInstance().currentSchool
        if (school == null || currentSemester.isEmpty()) return

        semesterIsLoading = true
        // 接口 B: 总评成绩摘要
        CourseApiClient.getInstance().fetchGrades(school, currentSemester, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread {
                    semesterIsLoading = false
                    Toast.makeText(context, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string() ?: ""
                val items = parseGradesJson(json)

                // 检查是否所有课程都有完整的分项详情
                val hasAllDetails = items.isNotEmpty() && items.all { it.detail.isNotEmpty() }
                if (hasAllDetails) {
                    activity?.runOnUiThread {
                        semesterIsLoading = false
                        semesterGrades = items
                    }
                } else {
                    // 部分或全部课程缺少分项详情，从接口A兜底补充
                    fetchAndMergeDetails(school, items)
                }
            }
        })
    }

    private fun fetchAndMergeDetails(school: SchoolConfig, baseGrades: List<GradeItemUi>) {
        CourseApiClient.getInstance().fetchGradeDetails(school, currentSemester, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread {
                    semesterIsLoading = false
                    semesterGrades = baseGrades
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val detailJson = response.body?.string() ?: ""
                val detailEntries = parseDetailEntries(detailJson)

                val merged = baseGrades.map { grade ->
                    if (grade.detail.isNotEmpty()) return@map grade
                    val matched = detailEntries.firstOrNull { it.jxbId.isNotEmpty() && it.jxbId == grade.jxbId }
                        ?: detailEntries.firstOrNull { it.courseCode.isNotEmpty() && it.courseCode == grade.courseCode }
                        ?: detailEntries.firstOrNull { it.courseName == grade.courseName }
                        ?: detailEntries.firstOrNull { grade.courseName.startsWith(it.courseName) || it.courseName.startsWith(grade.courseName) }
                    if (matched != null && matched.detail.isNotEmpty()) grade.copy(detail = matched.detail)
                    else grade
                }

                activity?.runOnUiThread {
                    semesterIsLoading = false
                    semesterGrades = merged
                }
            }
        })
    }

    private data class DetailEntry(val courseName: String, val courseCode: String, val jxbId: String, val detail: String)

    private fun parseDetailEntries(json: String): List<DetailEntry> {
        val result = mutableListOf<DetailEntry>()
        try {
            val items = JSONObject(json).optJSONArray("items") ?: return result
            val grouped = LinkedHashMap<String, MutableList<JSONObject>>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val name = item.optString("kcmc", "").trim()
                if (name.isEmpty()) continue
                val jxbId = item.optString("jxb_id", "")
                    .ifEmpty { item.optString("kch_id", "") }
                val key = "$name|$jxbId"
                grouped.getOrPut(key) { mutableListOf() }.add(item)
            }
            for ((_, records) in grouped) {
                val first = records[0]
                val name = first.optString("kcmc", "").trim()
                val kch = first.optString("kch", "").trim().ifEmpty { first.optString("kch_id", "").trim() }
                val jxbId = first.optString("jxb_id", "").ifEmpty { first.optString("kch_id", "") }

                val details = records.mapNotNull { r ->
                    val xmblmc = r.optString("xmblmc", "").trim()
                    val xmcj = r.optString("xmcj", "").trim()
                    if (xmblmc.isEmpty() || xmcj.isEmpty()) return@mapNotNull null
                    val xmbl = r.optString("xmbl", "").trim()
                    if (xmbl.isNotEmpty()) "$xmblmc(${xmbl}%): $xmcj"
                    else "$xmblmc: $xmcj"
                }.distinct()

                if (details.isNotEmpty()) {
                    result.add(DetailEntry(name, kch, jxbId, details.joinToString(" | ")))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun parseGradesJson(json: String): List<GradeItemUi> {
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
        } catch (e: Exception) {
            Log.e("GradesFragment", "Parse error: ${e.message}")
        }
        return result
    }

    // ==================== Overall Grades Logic ====================

    private fun loadOverallGrades() {
        val school = UserManager.getInstance().currentSchool ?: return
        
        overallIsLoading = true
        // Clear previous data
        overallGrades = emptyList()

        CourseApiClient.getInstance().fetchOverallGradesIndex(school, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread {
                    overallIsLoading = false
                    Toast.makeText(context, "获取参数失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val html = response.body?.string() ?: ""
                parseIndexAndFetchDetailedGrades(html)
            }
        })
    }

    private fun parseIndexAndFetchDetailedGrades(html: String) {
        try {
            val doc = Jsoup.parse(html)
            
            // Check login
            if (html.contains("用户登录") || html.contains("slogin.html")) {
                activity?.runOnUiThread {
                    overallIsLoading = false
                    Toast.makeText(context, "Cookie已过期，请重新登录", Toast.LENGTH_LONG).show()
                }
                return
            }

            extractSummaryInfo(doc, html)
            
            val xfyqjdIds = extractXfyqjdIds(doc, html)
            val xh_id = doc.selectFirst("input[name=xh_id]")?.attr("value") ?: ""
            val cjlrxn = doc.selectFirst("input[name=cjlrxn]")?.attr("value") ?: ""
            val cjlrxq = doc.selectFirst("input[name=cjlrxq]")?.attr("value") ?: ""

            if (xfyqjdIds.isEmpty()) {
                activity?.runOnUiThread {
                    overallIsLoading = false
                    updateOverallStats(emptyList()) // Update stats only from extracted summary
                }
                return
            }

            // Recursively fetch details
            val school = UserManager.getInstance().currentSchool ?: return
            fetchGradesDetailsRecursive(school, xfyqjdIds.toList(), 0, xh_id, cjlrxn, cjlrxq, mutableListOf())

        } catch (e: Exception) {
            activity?.runOnUiThread { overallIsLoading = false }
        }
    }

    private fun fetchGradesDetailsRecursive(
        school: SchoolConfig, 
        ids: List<String>, 
        index: Int, 
        xh_id: String, 
        cjlrxn: String, 
        cjlrxq: String,
        accumulatedGrades: MutableList<GradeItemUi>
    ) {
        if (index >= ids.size) {
            activity?.runOnUiThread {
                overallIsLoading = false
                overallGrades = accumulatedGrades
                updateOverallStats(accumulatedGrades)
            }
            return
        }

        val xfyqjd_id = ids[index]
        val postBody = "xfyqjd_id=$xfyqjd_id&xh_id=$xh_id&cjlrxn=$cjlrxn&cjlrxq=$cjlrxq&xscjcxkz=0&cjcxkzzt=2&cjztkz=0&cjzt="

        CourseApiClient.getInstance().fetchOverallGradesData(school, postBody, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Skip failed request and continue
                fetchGradesDetailsRecursive(school, ids, index + 1, xh_id, cjlrxn, cjlrxq, accumulatedGrades)
            }

            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string() ?: ""
                val items = parseGradesJson(json)
                
                // Add unique items
                items.forEach { newItem ->
                    if (accumulatedGrades.none { it.courseName == newItem.courseName }) {
                        accumulatedGrades.add(newItem)
                    }
                }
                
                fetchGradesDetailsRecursive(school, ids, index + 1, xh_id, cjlrxn, cjlrxq, accumulatedGrades)
            }
        })
    }

    private fun extractSummaryInfo(doc: Document, html: String) {
        extractedGPA = ""
        extractedCredits = 0.0
        extractedCourseCount = 0

        // Regex extraction similar to Java version
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
    }

    private fun extractXfyqjdIds(doc: Document, html: String): Set<String> {
        val ids = HashSet<String>()
        // Simplified extraction logic from Java version
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

    private fun updateOverallStats(grades: List<GradeItemUi>) {
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

        val displayGPA = if (extractedGPA.isNotEmpty()) extractedGPA else {
            if (totalCredits > 0) String.format("%.2f", weightedGPA / totalCredits) else "0.00"
        }
        
        val displayCredits = if (totalCredits > 0) String.format("%.1f", totalCredits) else String.format("%.1f", extractedCredits)
        
        val displayCount = if (grades.isNotEmpty()) grades.size else extractedCourseCount

        overallStats = OverallStatsUi(
            gpa = displayGPA,
            credits = displayCredits,
            courseCount = displayCount,
            excellent = excellent,
            good = good,
            medium = medium,
            pass = pass
        )
    }

    private fun getOptString(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.optString(key, null)
            if (!value.isNullOrEmpty() && value != "null") return value
        }
        return ""
    }
}

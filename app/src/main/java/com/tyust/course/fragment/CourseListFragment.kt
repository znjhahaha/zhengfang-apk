package com.tyust.course.fragment

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.tyust.course.manager.SmartSelector
import com.tyust.course.manager.UserManager
import com.tyust.course.model.Course
import com.tyust.course.model.SchoolConfig
import com.tyust.course.network.CourseApiClient
import com.tyust.course.ui.screen.CourseListScreen
import com.tyust.course.ui.theme.CourseSelectorTheme
import com.tyust.course.utils.CourseParser
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.ArrayList

class CourseListFragment : Fragment() {

    private var courses by mutableStateOf<List<Course>>(emptyList())
    private var allCourses: List<Course> = emptyList()
    private var isLoading by mutableStateOf(false)
    private var isBatchSelecting by mutableStateOf(false)
    private var courseParams: Map<String, String>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                CourseSelectorTheme {
                    CourseListScreen(
                        courses = courses,
                        isLoading = isLoading,
                        isBatchSelecting = isBatchSelecting,
                        onRefresh = { loadCourses() },
                        onSearch = { query -> filterCourses(query) },
                        onCourseSelect = { course ->
                            if (course.isSelected) {
                                Toast.makeText(context, "暂不支持退选，请去网页版操作", Toast.LENGTH_SHORT).show()
                            } else {
                                performSelection(course)
                            }
                        },
                        onAutoGrab = { course -> showAutoGrabDialog(course) },
                        onBatchSelect = { selectedCourses ->
                            performBatchSelection(selectedCourses)
                        }
                    )
                }
            }
        }
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadCourseParams()
    }

    private fun filterCourses(query: String) {
        if (query.isEmpty()) {
            courses = allCourses
            return
        }
        val lowerQuery = query.lowercase()
        courses = allCourses.filter { course ->
            (course.name?.lowercase()?.contains(lowerQuery) == true) ||
                    (course.teacher?.lowercase()?.contains(lowerQuery) == true)
        }
    }

    private fun loadCourseParams() {
        val school = UserManager.getInstance().currentSchool
        if (school == null) {
            return
        }

        isLoading = true
        CourseApiClient.getInstance().fetchCourseParams(school, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread { loadCourses() }
            }

            override fun onResponse(call: Call, response: Response) {
                val html = response.body?.string() ?: ""
                courseParams = CourseParser.parseCourseParams(html)
                activity?.runOnUiThread { loadCourses() }
            }
        })
    }

    // ============ 三步获取流程 (复用自动抢课逻辑) ============
    
    private var indexParams = mutableMapOf<String, String>()
    private var displayParams = mutableMapOf<String, String>()
    
    // Data class for tab parameters
    data class TabParam(val kklxdm: String, val xkkz_id: String, val njdm_id: String, val zyh_id: String)
    private var tabParamsList = mutableListOf<TabParam>()
    private var currentTabIndex = 0

    private fun loadCourses() {
        val school = UserManager.getInstance().currentSchool ?: return
        isLoading = true
        courses = emptyList() // 清空列表
        
        // Start Step 1
        com.tyust.course.network.CourseApiClient.getInstance().fetchCourseParams(
            school,
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activity?.runOnUiThread {
                        isLoading = false
                        showErrorDialog("获取选课参数失败", e.message ?: "未知网络错误")
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val html = response.body?.string() ?: ""
                    activity?.runOnUiThread {
                        parseIndexParams(html)
                        if (indexParams.isEmpty()) {
                            isLoading = false
                            showErrorDialog("解析失败", "未找到选课入口或参数，可能教务系统未开放或需要重新登录。")
                        } else {
                            fetchDisplayPage(school)
                        }
                    }
                }
            }
        )
    }

    private fun showErrorDialog(title: String, message: String) {
        if (!isAdded) return
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun parseIndexParams(html: String) {
        indexParams.clear()
        tabParamsList.clear()
        
        Log.d("CourseListFragment", "parseIndexParams: HTML length = ${html.length}")
        
        try {
            // Extract hidden input values (pattern 1: name before value)
            val pattern = """<input[^>]*name="([^"]+)"[^>]*value="([^"]*)"[^>]*>""".toRegex()
            pattern.findAll(html).forEach { match ->
                indexParams[match.groupValues[1]] = match.groupValues[2]
            }
            
            // Pattern 2: value before name
            val pattern2 = """<input[^>]*value="([^"]*)"[^>]*name="([^"]+)"[^>]*>""".toRegex()
            pattern2.findAll(html).forEach { match ->
                val name = match.groupValues[2]
                if (!indexParams.containsKey(name)) {
                    indexParams[name] = match.groupValues[1]
                }
            }
            
            Log.d("CourseListFragment", "parseIndexParams: Extracted ${indexParams.size} hidden params")
            Log.d("CourseListFragment", "parseIndexParams: firstXkkzId = ${indexParams["firstXkkzId"]}")
            Log.d("CourseListFragment", "parseIndexParams: firstKklxdm = ${indexParams["firstKklxdm"]}")
            Log.d("CourseListFragment", "parseIndexParams: xkkz_id = ${indexParams["xkkz_id"]}")
            Log.d("CourseListFragment", "parseIndexParams: kklxdm = ${indexParams["kklxdm"]}")
            
            // Extract tabParams from queryCourse onclick - try multiple patterns
            // Pattern 1: queryCourse(this, 'kklxdm', 'xkkz_id', 'njdm_id', 'zyh_id')
            val queryCoursePattern = """queryCourse\s*\(\s*this\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*\)""".toRegex()
            queryCoursePattern.findAll(html).forEach { match ->
                val kklxdm = match.groupValues[1]
                val xkkz_id = match.groupValues[2]
                val njdm_id = match.groupValues[3]
                val zyh_id = match.groupValues[4]
                Log.d("CourseListFragment", "parseIndexParams: Found queryCourse: kklxdm=$kklxdm, xkkz_id=$xkkz_id")
                tabParamsList.add(TabParam(kklxdm, xkkz_id, njdm_id, zyh_id))
            }
            
            // If no queryCourse found, try alternative patterns
            if (tabParamsList.isEmpty()) {
                // Pattern 2: onclick contains queryCourse with different spacing
                val pattern3 = """onclick\s*=\s*["']queryCourse\([^)]+\)["']""".toRegex()
                val onclickMatches = pattern3.findAll(html)
                Log.d("CourseListFragment", "parseIndexParams: Found ${onclickMatches.count()} onclick with queryCourse")
                
                // Try to extract xkkz_id from URL or other patterns
                val xkkzPattern = """xkkz_id['"]*\s*[:=]\s*['"]([^'"]+)['"]""".toRegex()
                xkkzPattern.findAll(html).forEach { match ->
                    Log.d("CourseListFragment", "parseIndexParams: Found xkkz_id in HTML: ${match.groupValues[1]}")
                    if (!indexParams.containsKey("xkkz_id") || indexParams["xkkz_id"].isNullOrEmpty()) {
                        indexParams["xkkz_id"] = match.groupValues[1]
                    }
                }
            }
            
            Log.d("CourseListFragment", "parseIndexParams: tabParamsList.size = ${tabParamsList.size}")
            
        } catch (e: Exception) {
            Log.e("CourseListFragment", "Error parsing index: ${e.message}")
        }
    }

    private fun fetchDisplayPage(school: SchoolConfig) {
        // Use tabParamsList if available, otherwise use default from indexParams
        if (tabParamsList.isEmpty()) {
            val xkkz_id = indexParams["firstXkkzId"] ?: indexParams["xkkz_id"] ?: ""
            val kklxdm = indexParams["firstKklxdm"] ?: indexParams["kklxdm"] ?: "10"
            val njdm_id = indexParams["njdm_id"] ?: "2024"
            val zyh_id = indexParams["zyh_id"] ?: ""
            
            Log.w("CourseListFragment", "fetchDisplayPage: tabParamsList is empty, using indexParams")
            Log.w("CourseListFragment", "fetchDisplayPage: xkkz_id='$xkkz_id' (firstXkkzId=${indexParams["firstXkkzId"]}, xkkz_id=${indexParams["xkkz_id"]})")
            Log.w("CourseListFragment", "fetchDisplayPage: kklxdm='$kklxdm', njdm_id='$njdm_id', zyh_id='$zyh_id'")
            
            if (xkkz_id.isEmpty()) {
                Log.e("CourseListFragment", "fetchDisplayPage: xkkz_id is EMPTY! This will cause server error.")
                // Show all indexParams for debugging
                Log.e("CourseListFragment", "All indexParams: ${indexParams.entries.joinToString(", ") { "${it.key}=${it.value.take(20)}" }}")
            }
            
            tabParamsList.add(TabParam(kklxdm, xkkz_id, njdm_id, zyh_id))
        }
        
        allCourses = emptyList()
        currentTabIndex = 0
        fetchNextCategory(school)
    }

    private fun fetchNextCategory(school: SchoolConfig) {
        if (currentTabIndex >= tabParamsList.size) {
            // All done
            activity?.runOnUiThread {
                isLoading = false
                courses = allCourses
                if (allCourses.isEmpty()) {
                     Toast.makeText(context, "未获取到任何课程", Toast.LENGTH_SHORT).show()
                } else {
                     Toast.makeText(context, "共加载 ${allCourses.size} 门课程", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        
        val tab = tabParamsList[currentTabIndex]
        currentTabIndex++
        
        // Fetch Display page for this category
        com.tyust.course.network.CourseApiClient.getInstance().fetchCourseDisplayParams(
            school, tab.xkkz_id, tab.kklxdm, tab.njdm_id, tab.zyh_id,
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activity?.runOnUiThread { 
                        // Try fallback but log error
                        Log.w("CourseListFragment", "Failed to fetch display params for tab $currentTabIndex: ${e.message}")
                        fetchCategoryList(school, tab) 
                    } 
                }

                override fun onResponse(call: Call, response: Response) {
                    val html = response.body?.string() ?: ""
                    activity?.runOnUiThread {
                        parseDisplayParams(html)
                        fetchCategoryList(school, tab)
                    }
                }
            }
        )
    }

    private fun parseDisplayParams(html: String) {
        displayParams.clear()
        try {
            val pattern = """<input[^>]*name="([^"]+)"[^>]*value="([^"]*)"[^>]*>""".toRegex()
            pattern.findAll(html).forEach { match ->
                displayParams[match.groupValues[1]] = match.groupValues[2]
            }
            val pattern2 = """<input[^>]*value="([^"]*)"[^>]*name="([^"]+)"[^>]*>""".toRegex()
            pattern2.findAll(html).forEach { match ->
                val name = match.groupValues[2]
                if (!displayParams.containsKey(name)) {
                    displayParams[name] = match.groupValues[1]
                }
            }
        } catch (e: Exception) {
            Log.e("CourseListFragment", "Error parsing display: ${e.message}")
        }
    }

    private fun fetchCategoryList(school: SchoolConfig, tab: TabParam) {
        val mergedParams = mutableMapOf<String, String>()
        mergedParams.putAll(indexParams)
        mergedParams.putAll(displayParams)
        
        // Tab parameters
        mergedParams["xkkz_id"] = tab.xkkz_id
        mergedParams["kklxdm"] = tab.kklxdm
        mergedParams["njdm_id"] = tab.njdm_id
        mergedParams["zyh_id"] = tab.zyh_id
        
        // ⚠️ 关键修复：根据 kklxdm 设置正确的 rwlx 和 xklc (与Web版一致)
        val kklxdm = tab.kklxdm
        val rwlx = when {
            mergedParams["rwlx"]?.isNotEmpty() == true -> mergedParams["rwlx"]!!
            kklxdm == "01" -> "1"
            kklxdm == "10" -> "2"
            kklxdm == "05" -> "2"
            else -> "1"
        }
        val xklc = when {
            mergedParams["xklc"]?.isNotEmpty() == true -> mergedParams["xklc"]!!
            kklxdm == "01" -> "2"
            kklxdm == "10" -> "4"
            kklxdm == "05" -> "3"
            else -> "2"
        }
        
        mergedParams["rwlx"] = rwlx
        mergedParams["xklc"] = xklc
        
        // ⚠️ 关键修复：分页参数 (与Web版一致)
        // Web版: kspage=0, jspage=10 (每页10条)
        mergedParams["kspage"] = "0"
        mergedParams["jspage"] = "10"
        
        // ⚠️ 其他必需参数 (与Web版一致)
        mergedParams.putIfAbsent("xkly", "0")
        mergedParams.putIfAbsent("bklx_id", "0")
        mergedParams.putIfAbsent("sfkkjyxdxnxq", "0")
        mergedParams.putIfAbsent("kzkcgs", "0")
        mergedParams.putIfAbsent("bbhzxjxb", "0")
        mergedParams.putIfAbsent("rlkz", "0")
        mergedParams.putIfAbsent("xkzgbj", "0")
        mergedParams.putIfAbsent("jxbzb", "")
        
        // 字段默认值 (与Web版buildFormDataPart1一致)
        mergedParams.putIfAbsent("gnjkxdnj", "0")
        mergedParams.putIfAbsent("sfkknj", "0")
        mergedParams.putIfAbsent("sfkkzy", "0")
        mergedParams.putIfAbsent("kzybkxy", "0")
        mergedParams.putIfAbsent("sfznkx", "0")
        mergedParams.putIfAbsent("zdkxms", "0")
        mergedParams.putIfAbsent("sfkxq", displayParams["sfkxq"] ?: "0")
        mergedParams.putIfAbsent("sfkcfx", if (kklxdm == "05") "1" else "0")
        mergedParams.putIfAbsent("kkbk", "0")
        mergedParams.putIfAbsent("kkbkdj", "0")
        mergedParams.putIfAbsent("bklbkcj", "0")
        mergedParams.putIfAbsent("sfkgbcx", if (kklxdm == "05") "1" else "0")
        mergedParams.putIfAbsent("sfrxtgkcxd", if (kklxdm == "05") "1" else "0")
        mergedParams.putIfAbsent("tykczgxdcs", if (kklxdm == "05") "8" else "0")
        mergedParams.putIfAbsent("bjgkczxbbjwcx", if (kklxdm == "05") "1" else "0")
        mergedParams.putIfAbsent("xkxskcgskg", displayParams["xkxskcgskg"] ?: "")
        
        Log.d("CourseListFragment", "fetchCategoryList params: rwlx=$rwlx, xklc=$xklc, kspage=0, jspage=10")
        
        val postBody = mergedParams.entries.joinToString("&") { "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        
        com.tyust.course.network.CourseApiClient.getInstance().fetchAvailableCourses(
            school, postBody,
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                     activity?.runOnUiThread {
                        // Show error dialog for course fetching failure
                        showErrorDialog("获取课程失败", "类别[${tab.kklxdm}]加载失败: ${e.message}\n尝试加载下一个类别...")
                        fetchNextCategory(school) 
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val json = response.body?.string() ?: ""
                    val responseCode = response.code
                    
                    // Log response for debugging
                    Log.d("CourseListFragment", "Response code: $responseCode")
                    Log.d("CourseListFragment", "Response length: ${json.length}")
                    Log.d("CourseListFragment", "Response preview: ${json.take(500)}")
                    
                    // Check for error conditions
                    if (responseCode != 200) {
                        activity?.runOnUiThread {
                            showErrorDialog("服务器响应异常", "HTTP状态码: $responseCode\n\n响应内容: ${json.take(300)}")
                            fetchNextCategory(school)
                        }
                        return
                    }
                    
                    // Check if response indicates login required
                    if (json.contains("用户登录") || json.contains("登 录") || json.contains("统一身份认证") || json.contains("请重新登录")) {
                        activity?.runOnUiThread {
                            showErrorDialog("Cookie已过期", "需要重新登录。请返回登录页面重新输入Cookie。")
                            isLoading = false
                        }
                        return
                    }
                    
                    // Check for empty or error response
                    if (json.isEmpty()) {
                        activity?.runOnUiThread {
                            // Show detailed param info for debugging
                            val keyParams = buildString {
                                append("关键参数:\n")
                                append("• xkkz_id: ${mergedParams["xkkz_id"]}\n")
                                append("• kklxdm: ${mergedParams["kklxdm"]}\n")
                                append("• rwlx: ${mergedParams["rwlx"]}\n")
                                append("• xklc: ${mergedParams["xklc"]}\n")
                                append("• xqh_id: ${mergedParams["xqh_id"] ?: "缺失"}\n")
                                append("• jg_id: ${mergedParams["jg_id"] ?: "缺失"}\n")
                                append("• xbm: ${mergedParams["xbm"] ?: "缺失"}\n")
                                append("• xslbdm: ${mergedParams["xslbdm"] ?: "缺失"}\n")
                                append("• xkxnm: ${mergedParams["xkxnm"] ?: "缺失"}\n")
                                append("• xkxqm: ${mergedParams["xkxqm"] ?: "缺失"}\n")
                            }
                            showErrorDialog("响应为空", "服务器返回空数据。\n\n$keyParams\n\n可能原因:\n1. 选课系统未开放\n2. 参数不正确")
                            fetchNextCategory(school)
                        }
                        return
                    }
                    
                    // Try to parse JSON
                    try {
                        // Use CourseParser with params support
                        val parsedCourses = com.tyust.course.utils.CourseParser.parseCourseListFromJson(
                            json, 
                            mergedParams, 
                            displayParams
                        )
                        
                        activity?.runOnUiThread {
                            if (parsedCourses.isEmpty()) {
                                // Check if JSON has error message
                                val errorInfo = try {
                                    val jsonObj = org.json.JSONObject(json)
                                    jsonObj.optString("msg", "") + jsonObj.optString("message", "")
                                } catch (e: Exception) {
                                    try {
                                        val jsonArr = org.json.JSONArray(json)
                                        if (jsonArr.length() == 0) "返回空数组" else ""
                                    } catch (e2: Exception) {
                                        "非JSON格式: ${json.take(100)}"
                                    }
                                }
                                
                                if (errorInfo.isNotEmpty()) {
                                    Log.w("CourseListFragment", "Server returned: $errorInfo")
                                }
                            }
                            
                            if (parsedCourses.isNotEmpty()) {
                                // Ensure fields are set (redundant if CourseParser does it, but safe)
                                parsedCourses.forEach { course ->
                                    course._xkkz_id = tab.xkkz_id
                                    course.kklxdm = tab.kklxdm
                                    course.njdm_id = tab.njdm_id
                                    course.zyh_id = tab.zyh_id
                                    
                                    // These should be set by CourseParser if params were passed, but we double check
                                    if (course._rwlx.isEmpty()) course._rwlx = mergedParams["rwlx"] ?: "1"
                                    if (course._xklc.isEmpty()) course._xklc = mergedParams["xklc"] ?: "2"
                                    if (course._sfkxq.isEmpty()) course._sfkxq = displayParams["sfkxq"] ?: ""
                                    if (course._xkxskcgskg.isEmpty()) course._xkxskcgskg = displayParams["xkxskcgskg"] ?: ""
                                }
                                allCourses = allCourses + parsedCourses
                                Log.d("CourseListFragment", "Parsed ${parsedCourses.size} courses from category ${tab.kklxdm}")
                            }
                            fetchNextCategory(school)
                        }
                    } catch (e: Exception) {
                        Log.e("CourseListFragment", "Parse error: ${e.message}")
                        activity?.runOnUiThread {
                            showErrorDialog("解析失败", "无法解析课程数据: ${e.message}\n\n响应预览: ${json.take(200)}")
                            fetchNextCategory(school)
                        }
                    }
                }
            }
        )
    }

    private fun performSelection(course: Course) {
        val school = UserManager.getInstance().currentSchool
        if (school == null) {
            Toast.makeText(context, "请先选择学校", Toast.LENGTH_SHORT).show()
            return
        }

        if (course.courseId.isNullOrEmpty()) {
            Toast.makeText(context, "缺少课程ID", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(context, "正在获取选课详情: ${course.name}", Toast.LENGTH_SHORT).show()

        val xkkz_id = courseParams?.get("xkkz_id") ?: ""
        val njdm_id = courseParams?.get("njdm_id") ?: "2024"
        val zyh_id = courseParams?.get("zyh_id") ?: ""
        val kklxdm = courseParams?.get("kklxdm") ?: "01"
        val xqh_id = courseParams?.get("xqh_id") ?: ""
        val jg_id = courseParams?.get("jg_id") ?: ""
        val rwlx = courseParams?.get("rwlx") ?: "1"
        val xklc = courseParams?.get("xklc") ?: "2"

        CourseApiClient.getInstance().fetchCourseSelectionDetails(
            school, course.courseId, xkkz_id, njdm_id, zyh_id, kklxdm, xqh_id, jg_id, rwlx, xklc,
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activity?.runOnUiThread {
                        Toast.makeText(context, "获取选课详情失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val json = response.body?.string() ?: ""
                    val details = parseSelectionDetails(json)

                    if (details == null) {
                        activity?.runOnUiThread {
                            Toast.makeText(context, "获取选课参数失败", Toast.LENGTH_SHORT).show()
                        }
                        return
                    }

                    executeSelectionWithDetails(school, course, details, xkkz_id, njdm_id, zyh_id, rwlx, xklc)
                }
            }
        )
    }

    data class SelectionDetails(
        val doJxbId: String,
        val njdmId: String,
        val zyhId: String,
        val rlkz: String,
        val rlzlkz: String,
        val sxbj: String,
        val xxkbj: String,
        val cxbj: String,
        val xkxnm: String,
        val xkxqm: String,
        val jcxxId: String,
        val xkkzId: String
    )

    private fun parseSelectionDetails(json: String): SelectionDetails? {
        try {
            val arr = JSONArray(json)
            if (arr.length() > 0) {
                val obj = arr.getJSONObject(0)
                
                // 提取加密的 jxb_id
                var doJxbId = obj.optString("do_jxb_id", "")
                if (doJxbId.isEmpty() || doJxbId.length < 50) {
                    doJxbId = obj.optString("jxb_id", "")
                }
                if (doJxbId.isEmpty() || doJxbId.length < 50) return null
                
                return SelectionDetails(
                    doJxbId = doJxbId,
                    njdmId = obj.optString("njdm_id", "2024"),
                    zyhId = obj.optString("zyh_id", ""),
                    rlkz = obj.optString("rlkz", "0"),
                    rlzlkz = obj.optString("rlzlkz", "1"),
                    sxbj = obj.optString("sxbj", "1"),
                    xxkbj = obj.optString("xxkbj", "0"),
                    cxbj = obj.optString("cxbj", "0"),
                    xkxnm = obj.optString("xkxnm", "2025"),
                    xkxqm = obj.optString("xkxqm", "12"),
                    jcxxId = obj.optString("jcxx_id", ""),
                    xkkzId = obj.optString("xkkz_id", "")
                )
            }
        } catch (e: Exception) {
            Log.e("CourseListFragment", "Parse error: ${e.message}")
        }
        return null
    }

    private fun executeSelectionWithDetails(
        school: SchoolConfig, course: Course, details: SelectionDetails,
        xkkz_id: String, njdm_id: String, zyh_id: String, rwlx: String, xklc: String
    ) {
        activity?.runOnUiThread {
            Toast.makeText(context, "正在选课: ${course.name}", Toast.LENGTH_SHORT).show()
        }

        // Use params from Course object (Web compatibility)
        val finalRwlx = if (course._rwlx?.isNotEmpty() == true) course._rwlx else rwlx
        val finalXklc = if (course._xklc?.isNotEmpty() == true) course._xklc else xklc
        val finalXkkzId = if (course._xkkz_id?.isNotEmpty() == true) course._xkkz_id else details.xkkzId.ifEmpty { xkkz_id }
        val finalNjdmId = if (course.njdm_id?.isNotEmpty() == true) course.njdm_id else details.njdmId.ifEmpty { njdm_id }
        val finalZyhId = if (course.zyh_id?.isNotEmpty() == true) course.zyh_id else details.zyhId.ifEmpty { zyh_id }
        val finalKklxdm = if (course.kklxdm?.isNotEmpty() == true) course.kklxdm else "01"

        // Update params from details if not present in course
        val finalRlkz = if (course.rlkz?.isNotEmpty() == true) course.rlkz else details.rlkz
        val finalRlzlkz = if (course.rlzlkz?.isNotEmpty() == true) course.rlzlkz else details.rlzlkz
        val finalSxbj = if (course.sxbj?.isNotEmpty() == true) course.sxbj else details.sxbj
        val finalXxkbj = if (course.xxkbj?.isNotEmpty() == true) course.xxkbj else details.xxkbj
        val finalCxbj = if (course.cxbj?.isNotEmpty() == true) course.cxbj else details.cxbj
        val finalXkxnm = if (course.xkxnm?.isNotEmpty() == true) course.xkxnm else details.xkxnm
        val finalXkxqm = if (course.xkxqm?.isNotEmpty() == true) course.xkxqm else details.xkxqm

        val postBody = StringBuilder()
        postBody.append("jxb_ids=").append(details.doJxbId)
        postBody.append("&kch_id=").append(course.courseId)
        postBody.append("&kcmc=(").append(course.courseId).append(")").append(course.name ?: "")
        postBody.append("&rwlx=").append(finalRwlx)
        postBody.append("&rlkz=").append(finalRlkz)
        postBody.append("&rlzlkz=").append(finalRlzlkz)
        postBody.append("&sxbj=").append(finalSxbj)
        postBody.append("&xxkbj=").append(finalXxkbj)
        postBody.append("&qz=0")
        postBody.append("&cxbj=").append(finalCxbj)
        postBody.append("&xkkz_id=").append(finalXkkzId)
        postBody.append("&njdm_id=").append(finalNjdmId)
        postBody.append("&zyh_id=").append(finalZyhId)
        postBody.append("&kklxdm=").append(finalKklxdm)
        postBody.append("&xklc=").append(finalXklc)
        postBody.append("&xkxnm=").append(finalXkxnm)
        postBody.append("&xkxqm=").append(finalXkxqm)
        postBody.append("&jcxx_id=").append(details.jcxxId)

        CourseApiClient.getInstance().selectCourse(school, postBody.toString(), object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread {
                    Toast.makeText(context, "请求失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.body?.string() ?: ""
                val success = result.contains("\"flag\":\"1\"") || result.contains("成功")
                
                if (success) {
                    // Step 3: Verify selection (like Web version)
                    verifyCourseSelection(school, course)
                } else {
                    activity?.runOnUiThread {
                        if (result.contains("人数已满")) {
                            Toast.makeText(context, "❌ 选课失败：人数已满", Toast.LENGTH_SHORT).show()
                        } else if (result.contains("冲突")) {
                            Toast.makeText(context, "❌ 选课失败：时间冲突", Toast.LENGTH_SHORT).show()
                        } else {
                            val msg = parseSelectionError(result)
                            Toast.makeText(context, "❌ 选课失败：$msg", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    // Step 3: Verify selection result (matching Web version's verifyCourseSelection)
    private fun verifyCourseSelection(school: SchoolConfig, course: Course) {
        val postBody = StringBuilder()
        courseParams?.forEach { (key, value) ->
            if (postBody.isNotEmpty()) postBody.append("&")
            postBody.append(key).append("=").append(value)
        }

        CourseApiClient.getInstance().fetchSelectedCourses(school, postBody.toString(), object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Verification failed, but selection might have succeeded
                activity?.runOnUiThread {
                    Toast.makeText(context, "✅ 选课成功！(验证失败: ${e.message})", Toast.LENGTH_LONG).show()
                    loadCourses()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string() ?: ""
                val verified = verifyCourseInList(json, course)
                
                activity?.runOnUiThread {
                    if (verified) {
                        Toast.makeText(context, "✅ 选课成功！已验证", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "✅ 选课成功！(验证未通过，请刷新确认)", Toast.LENGTH_LONG).show()
                    }
                    loadCourses()
                }
            }
        })
    }

    private fun verifyCourseInList(json: String, course: Course): Boolean {
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val jxbId = obj.optString("jxb_id", "")
                val kchId = obj.optString("kch_id", "")
                if ((course.classId != null && jxbId == course.classId) ||
                    (course.courseId != null && kchId == course.courseId)) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w("CourseListFragment", "Verify parse error: ${e.message}")
        }
        return false
    }

    private fun parseSelectionError(json: String): String {
        try {
            val obj = JSONObject(json)
            var msg = obj.optString("msg", null)
            if (msg == null) msg = obj.optString("message", null)
            if (!msg.isNullOrEmpty()) return msg
        } catch (e: Exception) {
            // ignore
        }
        return "请重试"
    }

    private fun showAutoGrabDialog(course: Course) {
        val context = context ?: return
        AlertDialog.Builder(context)
            .setTitle("抢课Pro+")
            .setMessage("是否对 [${course.name}] 开启自动抢课模式？\n\n• 自动每1.5秒重试一次\n• 直到成功或手动停止\n• 可在\"抢课Pro+\"页面查看状态")
            .setPositiveButton("开始抢课") { _, _ ->
                startAutoGrab(course)
                Toast.makeText(context, "已开始抢课，请切换到\"抢课Pro+\"页面查看状态", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startAutoGrab(course: Course) {
        val school = UserManager.getInstance().currentSchool ?: return
        
        SmartSelector.getInstance().setCourseParams(courseParams)
        SmartSelector.getInstance().setListener(object : SmartSelector.OnStatusUpdateListener {
            override fun onUpdate(message: String) {
                activity?.runOnUiThread {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onSuccess(courseName: String) {
                activity?.runOnUiThread {
                    Toast.makeText(context, "🎉 抢课成功：$courseName", Toast.LENGTH_LONG).show()
                    loadCourses()
                }
            }
            
            override fun onQueueProgress(current: Int, total: Int, courseName: String) {
                // 队列进度更新
            }
        })

        SmartSelector.getInstance().start(course, school)
    }

    // =========================
    // 批量抢课功能
    // =========================
    private fun performBatchSelection(selectedCourses: List<Course>) {
        if (selectedCourses.isEmpty()) {
            Toast.makeText(context, "请选择要抢的课程", Toast.LENGTH_SHORT).show()
            return
        }

        val school = UserManager.getInstance().currentSchool
        if (school == null) {
            Toast.makeText(context, "请先选择学校", Toast.LENGTH_SHORT).show()
            return
        }

        activity?.runOnUiThread {
            isBatchSelecting = true
            Toast.makeText(context, "开始批量抢课，共 ${selectedCourses.size} 门课程", Toast.LENGTH_SHORT).show()
        }

        // 使用线程依次执行批量选课
        Thread {
            var successCount = 0
            var failCount = 0

            for ((index, course) in selectedCourses.withIndex()) {
                activity?.runOnUiThread {
                    Toast.makeText(context, "正在抢课 (${index + 1}/${selectedCourses.size}): ${course.name}", Toast.LENGTH_SHORT).show()
                }

                try {
                    val result = performSelectionSync(school, course)
                    if (result) {
                        successCount++
                        Log.d("BatchSelection", "✅ 选课成功: ${course.name}")
                    } else {
                        failCount++
                        Log.d("BatchSelection", "❌ 选课失败: ${course.name}")
                    }
                } catch (e: Exception) {
                    failCount++
                    Log.e("BatchSelection", "❌ 选课异常: ${course.name} - ${e.message}")
                }

                // 添加延迟避免服务器压力
                Thread.sleep(500)
            }

            activity?.runOnUiThread {
                isBatchSelecting = false
                val message = "批量抢课完成！成功: $successCount 门，失败: $failCount 门"
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                
                // 刷新课程列表
                loadCourses()
            }
        }.start()
    }

    // 同步执行单个选课（用于批量抢课）
    private fun performSelectionSync(school: SchoolConfig, course: Course): Boolean {
        val xkkz_id = course._xkkz_id ?: courseParams?.get("xkkz_id") ?: ""
        val njdm_id = course.njdm_id ?: courseParams?.get("njdm_id") ?: "2024"
        val zyh_id = course.zyh_id ?: courseParams?.get("zyh_id") ?: ""
        val kklxdm = course.kklxdm ?: courseParams?.get("kklxdm") ?: "01"
        val xqh_id = courseParams?.get("xqh_id") ?: ""
        val jg_id = courseParams?.get("jg_id") ?: ""
        val rwlx = course._rwlx ?: courseParams?.get("rwlx") ?: "1"
        val xklc = course._xklc ?: courseParams?.get("xklc") ?: "2"

        // Step 1: 获取加密的 jxb_id
        val detailsResponse = CourseApiClient.getInstance().fetchCourseSelectionDetailsSync(
            school, course.courseId ?: "", xkkz_id, njdm_id, zyh_id, kklxdm, xqh_id, jg_id, rwlx, xklc
        )
        
        if (detailsResponse == null) {
            Log.e("BatchSelection", "获取选课详情失败: ${course.name}")
            return false
        }

        val details = parseSelectionDetails(detailsResponse)
        if (details == null) {
            Log.e("BatchSelection", "解析加密ID失败: ${course.name}")
            return false
        }

        // 优先使用课程数据中的参数 (Web compatibility)
        val finalRwlx = if (course._rwlx?.isNotEmpty() == true) course._rwlx else rwlx
        val finalXklc = if (course._xklc?.isNotEmpty() == true) course._xklc else xklc
        val finalXkkzId = if (course._xkkz_id?.isNotEmpty() == true) course._xkkz_id else details.xkkzId.ifEmpty { xkkz_id }
        val finalNjdmId = if (course.njdm_id?.isNotEmpty() == true) course.njdm_id else details.njdmId.ifEmpty { njdm_id }
        val finalZyhId = if (course.zyh_id?.isNotEmpty() == true) course.zyh_id else details.zyhId.ifEmpty { zyh_id }
        val finalKklxdm = if (course.kklxdm?.isNotEmpty() == true) course.kklxdm else "01"

        // Update params from details if not present in course
        val finalRlkz = if (course.rlkz?.isNotEmpty() == true) course.rlkz else details.rlkz
        val finalRlzlkz = if (course.rlzlkz?.isNotEmpty() == true) course.rlzlkz else details.rlzlkz
        val finalSxbj = if (course.sxbj?.isNotEmpty() == true) course.sxbj else details.sxbj
        val finalXxkbj = if (course.xxkbj?.isNotEmpty() == true) course.xxkbj else details.xxkbj
        val finalCxbj = if (course.cxbj?.isNotEmpty() == true) course.cxbj else details.cxbj
        val finalXkxnm = if (course.xkxnm?.isNotEmpty() == true) course.xkxnm else details.xkxnm
        val finalXkxqm = if (course.xkxqm?.isNotEmpty() == true) course.xkxqm else details.xkxqm

        // Step 2: 执行选课
        val postBody = StringBuilder()
        postBody.append("jxb_ids=").append(details.doJxbId)
        postBody.append("&kch_id=").append(course.courseId ?: "")
        postBody.append("&kcmc=(").append(course.courseId ?: "").append(")").append(course.name ?: "")
        postBody.append("&rwlx=").append(finalRwlx)
        postBody.append("&rlkz=").append(finalRlkz)
        postBody.append("&rlzlkz=").append(finalRlzlkz)
        postBody.append("&sxbj=").append(finalSxbj)
        postBody.append("&xxkbj=").append(finalXxkbj)
        postBody.append("&qz=0")
        postBody.append("&cxbj=").append(finalCxbj)
        postBody.append("&xkkz_id=").append(finalXkkzId)
        postBody.append("&njdm_id=").append(finalNjdmId)
        postBody.append("&zyh_id=").append(finalZyhId)
        postBody.append("&kklxdm=").append(finalKklxdm)
        postBody.append("&xklc=").append(finalXklc)
        postBody.append("&xkxnm=").append(finalXkxnm)
        postBody.append("&xkxqm=").append(finalXkxqm)
        postBody.append("&jcxx_id=").append(details.jcxxId)

        val selectResponse = CourseApiClient.getInstance().selectCourseSync(school, postBody.toString())
        
        if (selectResponse == null) {
            Log.e("BatchSelection", "选课请求失败: ${course.name}")
            return false
        }

        val success = selectResponse.contains("\"flag\":\"1\"") || selectResponse.contains("成功")
        return success
    }
}

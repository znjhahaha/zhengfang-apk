package com.tyust.course.ui.route

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tyust.course.manager.CourseCacheManager
import com.tyust.course.manager.SmartSelector
import com.tyust.course.manager.UserManager
import com.tyust.course.model.Course
import com.tyust.course.model.SchoolConfig
import com.tyust.course.network.CourseApiClient
import com.tyust.course.ui.screen.CourseListScreen
import com.tyust.course.ui.route.SelectedCoursesRoute
import com.tyust.course.utils.CourseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import com.tyust.course.ui.theme.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseListRoute() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Data State
    var courses by remember { mutableStateOf<List<Course>>(emptyList()) }
    var allCourses by remember { mutableStateOf<List<Course>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isBatchSelecting by remember { mutableStateOf(false) }
    
    // UI State
    var showSelectedCourses by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    
    // Multi-Select State
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedClassIds by remember { mutableStateOf(setOf<String>()) }
    
    // Preload State
    var isPreloading by remember { mutableStateOf(false) }
    var preloadProgress by remember { mutableStateOf(0f) }
    var preloadedGroupIds by remember { mutableStateOf(setOf<String>()) }
    var hasPreloadedOnce by remember { mutableStateOf(false) }
    
    // Logic State
    var courseParams by remember { mutableStateOf<Map<String, String>?>(null) }
    var displayParams by remember { mutableStateOf<Map<String, String>>(emptyMap()) } // 🔧 新增：Display 页面参数
    
    // 🔧 交互锁：只有不在加载中 且 displayParams 包含关键参数时才允许展开详情
    val isDetailsReady = !isLoading && displayParams.containsKey("bklx_id")

    // Helpers
    fun exitMultiSelectMode() {
        isMultiSelectMode = false
        selectedClassIds = emptySet()
    }
    
    fun onSearch(query: String) {
        searchQuery = query
        courses = if (query.isEmpty()) {
            allCourses
        } else {
            allCourses.filter { 
                it.name?.contains(query, ignoreCase = true) == true || 
                it.courseId?.contains(query, ignoreCase = true) == true ||
                it.teacher.contains(query, ignoreCase = true)
            }
        }
    }

    // Helper to run on Main thread
    fun runOnUiThread(action: () -> Unit) {
        scope.launch(Dispatchers.Main) {
            action()
        }
    }

    // Load initial params
    LaunchedEffect(Unit) {
        val school = UserManager.getInstance().currentSchool
        if (school != null) {
            isLoading = true
            CourseApiClient.getInstance().fetchCourseParams(school, object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    val html = response.body?.string() ?: ""
                    val params = CourseParser.parseCourseParams(html)
                    scope.launch(Dispatchers.Main) {
                        courseParams = params
                    }
                }
            })
        }
    }

    // 🔧 课程缓存机制：加载课程（支持缓存和强制刷新）
    fun loadCoursesInternal(forceRefresh: Boolean) {
        val school = UserManager.getInstance().currentSchool ?: return
        
        // 如果不是强制刷新，先检查缓存
        if (!forceRefresh) {
            val cached = CourseCacheManager.getCachedCourses(context)
            if (cached != null && cached.isNotEmpty()) {
                val remaining = CourseCacheManager.getRemainingMinutes()
                android.util.Log.d("CourseListRoute", "使用缓存: ${cached.size} 门课程，剩余 $remaining 分钟")
                allCourses = cached
                courses = cached
                isLoading = false
                Toast.makeText(context, "已加载缓存 (${cached.size} 门，剩余 ${remaining} 分钟)", Toast.LENGTH_SHORT).show()
                
                // 🔧 关键修复：缓存模式下，后台获取 Display 参数
                // 这样展开课程详情时才能使用完整参数
                val tmpSchool = school
                scope.launch(Dispatchers.IO) {
                    // Step 1: 获取 Index 页面参数
                    CourseApiClient.getInstance().fetchCourseParams(tmpSchool, object : Callback {
                        override fun onFailure(call: Call, e: IOException) {}
                        override fun onResponse(call: Call, response: Response) {
                            val html = response.body?.string() ?: ""
                            val indexParams = mutableMapOf<String, String>()
                            val pattern = """<input[^>]*name="([^"]+)"[^>]*value="([^"]*)"[^>]*>""".toRegex()
                            pattern.findAll(html).forEach { m -> indexParams[m.groupValues[1]] = m.groupValues[2] }
                            
                            val xkkz_id = indexParams["firstXkkzId"] ?: indexParams["xkkz_id"] ?: ""
                            val kklxdm = indexParams["firstKklxdm"] ?: indexParams["kklxdm"] ?: "10"
                            val njdm_id = indexParams["njdm_id"] ?: "2024"
                            val zyh_id = indexParams["zyh_id"] ?: ""
                            
                            runOnUiThread { courseParams = indexParams }
                            
                            if (xkkz_id.isNotEmpty()) {
                                // Step 2: 获取 Display 页面参数
                                CourseApiClient.getInstance().fetchCourseDisplayParams(
                                    tmpSchool, xkkz_id, kklxdm, njdm_id, zyh_id,
                                    object : Callback {
                                        override fun onFailure(call: Call, e: IOException) {}
                                        override fun onResponse(call: Call, response: Response) {
                                            val displayHtml = response.body?.string() ?: ""
                                            val newDisplayParams = mutableMapOf<String, String>()
                                            pattern.findAll(displayHtml).forEach { m -> newDisplayParams[m.groupValues[1]] = m.groupValues[2] }
                                            android.util.Log.d("CourseListRoute", "✅ 后台获取 Display 参数: bklx_id=${newDisplayParams["bklx_id"]}, jg_id=${newDisplayParams["jg_id"]}")
                                            runOnUiThread { displayParams = newDisplayParams }
                                        }
                                    }
                                )
                            }
                        }
                    })
                }
                return
            }
        }
        
        isLoading = true
        courses = emptyList()
        displayParams = emptyMap() // 🔧 刷新时清除旧参数，防止交互锁误判
        if (forceRefresh) {
            Toast.makeText(context, "正在刷新课程列表...", Toast.LENGTH_SHORT).show()
        }

        CourseApiClient.getInstance().fetchCourseParams(school, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    isLoading = false
                    Toast.makeText(context, "获取选课参数失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val html = response.body?.string() ?: ""
                scope.launch(Dispatchers.IO) {
                    val helper = CourseListLogicHelper(context, school, 
                        onSuccess = { newCourses ->
                            // 保存到缓存
                            CourseCacheManager.saveCourses(context, newCourses)
                            runOnUiThread {
                                allCourses = newCourses
                                courses = newCourses
                                isLoading = false
                                if (forceRefresh) {
                                    Toast.makeText(context, "刷新完成: ${newCourses.size} 门课程", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onError = { msg ->
                            runOnUiThread {
                                isLoading = false
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        // 🔧 渐进式加载：每个分类完成后立即更新UI
                        onProgress = { currentCourses, completedTabs, totalTabs ->
                            runOnUiThread {
                                allCourses = currentCourses
                                courses = currentCourses
                                android.util.Log.d("CourseListRoute", "📊 渐进加载: $completedTabs/$totalTabs 分类完成，已获取 ${currentCourses.size} 门课程")
                            }
                        },
                        // 🔧 新增：接收 displayParams 更新状态
                        onDisplayParams = { params ->
                            runOnUiThread {
                                displayParams = params
                                android.util.Log.d("CourseListRoute", "✅ 更新 displayParams: ${params.size} 个参数, bklx_id=${params["bklx_id"]}")
                            }
                        }
                    )
                    helper.parseIndexParamsAndFetch(html)
                }
            }
        })
    }
    
    // 给 UI 使用的加载函数（强制刷新）
    val loadCourses = remember {
        fun() {
            hasPreloadedOnce = false // 🔧 强制刷新时重置，允许重新预加载
            loadCoursesInternal(forceRefresh = true)
        }
    }


    // Initial load (使用缓存)
    LaunchedEffect(Unit) {
        loadCoursesInternal(forceRefresh = false)
    }

    // 从"已选"切回"可选"时强制刷新，同步退课后的状态
    LaunchedEffect(showSelectedCourses) {
        if (!showSelectedCourses && allCourses.isNotEmpty()) {
            // 从已选切回可选时，强制刷新以同步 isSelected 状态
            loadCoursesInternal(forceRefresh = true)
        }
    }
    
    // Filter logic
    val onSearch: (String) -> Unit = { query ->
        searchQuery = query
        if (query.isEmpty()) {
            courses = allCourses
        } else {
            val lowerQuery = query.lowercase()
            courses = allCourses.filter { course ->
                (course.name?.lowercase()?.contains(lowerQuery) == true) ||
                        (course.teacher?.lowercase()?.contains(lowerQuery) == true)
            }
        }
    }
    
    // 构建详情请求体（40参数）- 定义在 remember 之前
    // 🔧 修复：合并 courseParams 和 displayParams，确保 bklx_id 等参数正确
    fun buildDetailsRequestBody(course: Course): String {
        val formData = mutableMapOf<String, String>()
        
        // 🔧 关键修复：合并 Index 和 Display 参数
        val mergedParams = mutableMapOf<String, String>()
        courseParams?.let { mergedParams.putAll(it) }
        mergedParams.putAll(displayParams)
        
        // 从 course 对象或 mergedParams 获取参数
        val kklxdm = course.kklxdm.ifEmpty { mergedParams["kklxdm"] ?: "09" }
        val xkkz_id = course._xkkz_id.ifEmpty { mergedParams["xkkz_id"] ?: "" }
        val njdm_id = course.njdm_id.ifEmpty { mergedParams["njdm_id"] ?: "" }
        val zyh_id = course.zyh_id.ifEmpty { mergedParams["zyh_id"] ?: "" }
        val rwlx = course._rwlx.ifEmpty { mergedParams["rwlx"] ?: "1" }
        val xklc = course._xklc.ifEmpty { mergedParams["xklc"] ?: "2" }
        
        formData["rwlx"] = rwlx
        formData["xkly"] = mergedParams["xkly"] ?: "0"
        formData["bklx_id"] = mergedParams["bklx_id"] ?: "0"
        formData["sfkkjyxdxnxq"] = mergedParams["sfkkjyxdxnxq"] ?: "0"
        formData["kzkcgs"] = mergedParams["kzkcgs"] ?: "0"  // 🔧 新增
        formData["txbsfrl"] = mergedParams["txbsfrl"] ?: "0"  // 🔧 新增
        formData["xqh_id"] = mergedParams["xqh_id"] ?: "1"
        formData["jg_id"] = mergedParams["jg_id"] ?: ""
        formData["zyh_id"] = zyh_id
        formData["zyfx_id"] = mergedParams["zyfx_id"] ?: "wfx"
        formData["njdm_id"] = njdm_id
        formData["bh_id"] = mergedParams["bh_id"] ?: ""
        formData["xbm"] = mergedParams["xbm"] ?: "2"
        formData["xslbdm"] = mergedParams["xslbdm"] ?: "wlb"
        formData["mzm"] = mergedParams["mzm"] ?: "w"
        formData["xz"] = mergedParams["xz"] ?: "4"
        formData["ccdm"] = mergedParams["ccdm"] ?: "3"
        formData["xsbj"] = mergedParams["xsbj"] ?: "0"
        formData["sfkknj"] = mergedParams["sfkknj"] ?: "0"
        formData["gnjkxdnj"] = mergedParams["gnjkxdnj"] ?: "0"  // 🔧 新增
        formData["sfkkzy"] = mergedParams["sfkkzy"] ?: "0"
        formData["kzybkxy"] = mergedParams["kzybkxy"] ?: "0"
        formData["sfznkx"] = mergedParams["sfznkx"] ?: "0"
        formData["zdkxms"] = mergedParams["zdkxms"] ?: "0"
        formData["sfkxq"] = mergedParams["sfkxq"] ?: "0"
        formData["sfkcfx"] = mergedParams["sfkcfx"] ?: "0"
        formData["bbhzxjxb"] = mergedParams["bbhzxjxb"] ?: "0"  // 🔧 新增
        formData["kkbk"] = mergedParams["kkbk"] ?: "0"
        formData["kkbkdj"] = mergedParams["kkbkdj"] ?: "0"
        formData["bklbkcj"] = mergedParams["bklbkcj"] ?: "0"  // 🔧 新增
        formData["xkxnm"] = mergedParams["xkxnm"] ?: "2025"
        formData["xkxqm"] = mergedParams["xkxqm"] ?: "12"
        formData["xkxskcgskg"] = mergedParams["xkxskcgskg"] ?: "0"
        formData["rlkz"] = mergedParams["rlkz"] ?: "0"
        formData["cdrlkz"] = mergedParams["cdrlkz"] ?: "0"
        formData["rlzlkz"] = mergedParams["rlzlkz"] ?: "1"
        formData["kklxdm"] = kklxdm
        formData["kch_id"] = course.courseId ?: ""
        formData["jxbzcxskg"] = mergedParams["jxbzcxskg"] ?: "0"
        formData["xklc"] = xklc
        formData["xkkz_id"] = xkkz_id
        formData["cxbj"] = mergedParams["cxbj"] ?: "0"
        formData["fxbj"] = mergedParams["fxbj"] ?: "0"
        
        return formData.entries.joinToString("&") { "${it.key}=${it.value}" }
    }
    
    // 解析详情响应并更新 Course 对象 - 定义在 remember 之前
    fun parseDetailsResponseAndUpdate(course: Course, json: String) {
        try {
            val array = JSONArray(json)
            if (array.length() > 0) {
                // 遍历找到匹配的教学班
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val jxbId = item.optString("jxb_id", "")
                    
                    // 找到匹配的教学班
                    if (jxbId == course.classId || course.classId.isNullOrEmpty()) {
                        // 更新时间地点信息
                        course.time = item.optString("sksj", "").replace("<br>", ", ").replace("<br/>", ", ")
                        course.location = item.optString("jxdd", "").replace("<br>", ", ").replace("<br/>", ", ")
                        course.teacher = item.optString("jsxm", course.teacher)
                        
                        // 更新容量
                        val capacity = item.optInt("jxbrl", course.capacity)
                        val selected = item.optInt("yxzrs", course.selected)
                        course.capacity = capacity
                        course.selected = selected
                        
                        // 更新 do_jxb_id (用于选课)
                        val doJxbId = item.optString("do_jxb_id", "")
                        if (doJxbId.isNotEmpty()) {
                            course.doJxbId = doJxbId
                        }
                        
                        android.util.Log.d("CourseListRoute", "更新课程详情: ${course.name}, 时间=${course.time}, 地点=${course.location}")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CourseListRoute", "解析详情失败: ${e.message}")
        }
    }
    
    // 从JSON更新课程信息 - 必须在 fetchDetailsForClasses 之前定义
    fun updateCourseFromJson(course: Course, item: JSONObject) {
        // 🔧 调试：打印完整的 JSON 对象
        android.util.Log.d("CourseListRoute", "JSON内容: $item")
        
        course.time = item.optString("sksj", "").replace("<br>", ", ").replace("<br/>", ", ")
        course.location = item.optString("jxdd", "").replace("<br>", ", ").replace("<br/>", ", ")
        
        // 🔧 提取教学班名称 (jxbmc)，如 "足球周三34"
        val jxbmc = item.optString("jxbmc", "")
        if (jxbmc.isNotEmpty()) {
            course.jxbmc = jxbmc
        }
        
        // 解析教师信息 (格式: "C11691/王波/无;J02024/董婷/副教授")
        val jsxx = item.optString("jsxx", "")
        if (jsxx.isNotEmpty()) {
            val teachers = jsxx.split(";").mapNotNull { part ->
                val segments = part.split("/")
                if (segments.size >= 2) segments[1] else null
            }
            course.teacher = teachers.joinToString(", ")
        }
        
        course.capacity = item.optInt("jxbrl", course.capacity)
        course.selected = item.optInt("yxzrs", course.selected)
        
        // 🔧 关键修复：同时更新 classId 和 doJxbId
        // classId 用于匹配，doJxbId 用于提交选课请求
        val jxbId = item.optString("jxb_id", "")
        val doJxbId = item.optString("do_jxb_id", "")
        
        if (jxbId.isNotEmpty()) {
            course.classId = jxbId
        }
        if (doJxbId.isNotEmpty()) {
            course.doJxbId = doJxbId
        }
        
        android.util.Log.d("CourseListRoute", "✅ 更新: ${course.name}, jxbmc=${course.jxbmc}, 教师=${course.teacher}")
    }
    
    // 🔧 后台并行预加载所有课程详情
    suspend fun preloadAllCourseDetails(school: SchoolConfig, courseList: List<Course>) {
        // 按课程ID分组
        val grouped = courseList.groupBy { it.courseId ?: "" }.filter { it.key.isNotEmpty() }
        val totalGroups = grouped.size
        if (totalGroups == 0) return
        
        withContext(Dispatchers.Main) {
            isPreloading = true
            preloadProgress = 0f
            preloadedGroupIds = emptySet()
        }
        
        android.util.Log.d("CourseListRoute", "🚀 开始并行预加载 $totalGroups 个课程组")
        
        // 使用信号量限制并发数（避免请求过多被服务器拒绝）
        val semaphore = Semaphore(5) // 最多5个并发请求（提升加载速度）
        var completedCount = 0
        
        // 并行获取所有课程组的详情
        val jobs = grouped.map { (courseId, classes) ->
            scope.launch(Dispatchers.IO) {
                semaphore.acquire()
                try {
                    val firstCourse = classes.firstOrNull() ?: return@launch
                    val postBody = buildDetailsRequestBody(firstCourse)
                    
                    val response = CourseApiClient.getInstance().fetchCourseSelectionDetailsSync(school, postBody)
                    if (response != null) {
                        try {
                            val array = JSONArray(response)
                            
                            // 更新课程详情
                            if (array.length() == classes.size) {
                                classes.forEachIndexed { index, course ->
                                    val item = array.getJSONObject(index)
                                    updateCourseFromJson(course, item)
                                }
                            } else {
                                // 按ID匹配
                                classes.forEach { course ->
                                    for (i in 0 until array.length()) {
                                        val item = array.getJSONObject(i)
                                        val jxbId = item.optString("jxb_id", "")
                                        val doJxbId = item.optString("do_jxb_id", "")
                                        if (jxbId == course.classId || doJxbId == course.classId) {
                                            updateCourseFromJson(course, item)
                                            break
                                        }
                                    }
                                }
                            }
                            
                            android.util.Log.d("CourseListRoute", "✅ 预加载完成: ${firstCourse.name} (${classes.size}个班)")
                        } catch (e: Exception) {
                            android.util.Log.e("CourseListRoute", "解析预加载响应失败: ${e.message}")
                        }
                    }
                } finally {
                    semaphore.release()
                    
                    // 更新进度
                    synchronized(this) {
                        completedCount++
                        val progress = completedCount.toFloat() / totalGroups
                        
                        scope.launch(Dispatchers.Main) {
                            preloadProgress = progress
                            preloadedGroupIds = preloadedGroupIds + courseId
                            
                            // 触发UI刷新（通过创建新列表引用）
                            if (completedCount % 3 == 0 || completedCount == totalGroups) {
                                courses = allCourses.toList()
                            }
                        }
                    }
                }
            }
        }
        
        // 等待所有任务完成
        jobs.forEach { it.join() }
        
        withContext(Dispatchers.Main) {
            isPreloading = false
            preloadProgress = 1f
            hasPreloadedOnce = true // 🔧 标记已完成预加载
            // 最终刷新并保存缓存
            courses = allCourses.toList()
            CourseCacheManager.saveCourses(context, allCourses)
            android.util.Log.d("CourseListRoute", "🎉 并行预加载全部完成！共 $totalGroups 个课程组")
        }
    }
    
    // 🔧 课程详情改为点击展开时加载（不再后台预加载）
    // 原预加载 LaunchedEffect 已移除
    
    // 🔧 获取课程详情（展开时调用，40参数请求）
    // 回调参数：Boolean 表示是否成功获取详情
    val fetchDetailsForClasses: (List<Course>, (Boolean) -> Unit) -> Unit = { classesList, onComplete ->
        val school = UserManager.getInstance().currentSchool
        if (school == null) {
            Toast.makeText(context, "未登录，无法获取详情", Toast.LENGTH_SHORT).show()
            onComplete(false)
        } else {
            scope.launch(Dispatchers.IO) {
                var success = false
                try {
                    // 只需要请求一次（所有教学班属于同一个课程）
                    val firstCourse = classesList.firstOrNull()
                    if (firstCourse != null) {
                        val postBody = buildDetailsRequestBody(firstCourse)
                        android.util.Log.d("CourseListRoute", "请求详情, kch_id=${firstCourse.courseId}, 共${classesList.size}个教学班")
                        
                        val response = CourseApiClient.getInstance().fetchCourseSelectionDetailsSync(school, postBody)
                        if (response != null && response.isNotEmpty()) {
                            android.util.Log.d("CourseListRoute", "详情响应长度: ${response.length}")
                            
                            // 解析响应并更新所有教学班
                            try {
                                val array = JSONArray(response)
                                android.util.Log.d("CourseListRoute", "响应包含 ${array.length()} 个教学班, 需更新 ${classesList.size} 个")
                                
                                // 如果数量匹配，按索引直接对应
                                if (array.length() == classesList.size) {
                                    android.util.Log.d("CourseListRoute", "✅ 数量匹配，使用索引对应")
                                    classesList.forEachIndexed { index, course ->
                                        val item = array.getJSONObject(index)
                                        updateCourseFromJson(course, item)
                                    }
                                } else {
                                    // 数量不匹配，尝试按 ID 匹配
                                    android.util.Log.d("CourseListRoute", "⚠️ 数量不匹配，尝试ID匹配")
                                    classesList.forEach { course ->
                                        val targetDoJxbId = course.doJxbId ?: ""
                                        val targetClassId = course.classId ?: ""
                                        
                                        for (i in 0 until array.length()) {
                                            val item = array.getJSONObject(i)
                                            val responseDoJxbId = item.optString("do_jxb_id", "")
                                            val responseJxbId = item.optString("jxb_id", "")
                                            
                                            // 尝试多种匹配方式
                                            if ((targetDoJxbId.isNotEmpty() && responseDoJxbId == targetDoJxbId) ||
                                                (targetClassId.isNotEmpty() && responseJxbId == targetClassId) ||
                                                (targetClassId.isNotEmpty() && responseDoJxbId == targetClassId)) {
                                                updateCourseFromJson(course, item)
                                                break
                                            }
                                        }
                                    }
                                }
                                success = array.length() > 0 // 只有有数据才算成功
                            } catch (e: Exception) {
                                android.util.Log.e("CourseListRoute", "解析JSON失败: ${e.message}")
                            }
                        } else {
                            android.util.Log.e("CourseListRoute", "响应为空，无法获取详情")
                        }
                    }
                    
                    withContext(Dispatchers.Main) {
                        if (!success) {
                            Toast.makeText(context, "❗获取详情失败，请重新点击展开", Toast.LENGTH_SHORT).show()
                        }
                        onComplete(success)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CourseListRoute", "获取详情失败: ${e.message}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "❗网络错误: ${e.message}", Toast.LENGTH_SHORT).show()
                        onComplete(false)
                    }
                }
            }
        }
    }

    // Selection Logic
    val performSelection = remember {
        fun(course: Course) {
            val school = UserManager.getInstance().currentSchool ?: return
            Toast.makeText(context, "正在选课: ${course.name}...", Toast.LENGTH_SHORT).show()
            
            // 使用协程异步执行选课，完成后显示结果
            scope.launch(Dispatchers.IO) {
                val logic = CourseSelectionLogic(context, school, courseParams)
                val result = logic.performSelectionSync(course)
                
                withContext(Dispatchers.Main) {
                    if (result) {
                        Toast.makeText(context, "✅ 选课成功: ${course.name}", Toast.LENGTH_LONG).show()
                        course.isSelected = true
                        // 🔧 强制刷新 UI 并保存到持久化缓存
                        courses = courses.toList()
                        allCourses = allCourses.toList()
                        CourseCacheManager.saveCourses(context, allCourses)
                    } else {
                        // 失败消息已经在 performSelectionSync 中显示
                    }
                }
            }
        }
    }
    
    // Batch Selection Logic
    val performBatchSelection = remember {
        fun(selectedCourses: List<Course>) {
            val school = UserManager.getInstance().currentSchool ?: return
            isBatchSelecting = true
            Toast.makeText(context, "开始批量抢课，共 ${selectedCourses.size} 门课程", Toast.LENGTH_SHORT).show()
            
            scope.launch(Dispatchers.IO) {
                val logic = CourseSelectionLogic(context, school, courseParams)
                var successCount = 0
                var failCount = 0
                
                selectedCourses.forEachIndexed { index, course ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "正在抢课 (${index + 1}/${selectedCourses.size}): ${course.name}", Toast.LENGTH_SHORT).show()
                    }
                    
                    val result = logic.performSelectionSync(course)
                    if (result) successCount++ else failCount++
                    
                    Thread.sleep(500)
                }
                
                withContext(Dispatchers.Main) {
                    isBatchSelecting = false
                    Toast.makeText(context, "批量抢课完成！成功: $successCount 门，失败: $failCount 门", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // 🔧 视图切换容器（带平滑动画）
    Scaffold(
        topBar = {
            Surface(
                color = NeuSurface,
                contentColor = Neutral900,
                shadowElevation = 0.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    AnimatedContent(
                    targetState = when {
                        isSearchActive -> "search"
                        isMultiSelectMode -> "multiSelect"
                        else -> "standard"
                    },
                    transitionSpec = {
                        if (targetState == "search" || initialState == "search") {
                            (fadeIn() + expandVertically(expandFrom = Alignment.Top)).togetherWith(
                                fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
                            )
                        } else {
                            fadeIn().togetherWith(fadeOut())
                        }
                    },
                    label = "TopBarMode"
                ) { mode ->
                    when (mode) {
                        "search" -> {
                            // 🔍 沉浸式搜索顶栏
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .height(64.dp)
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { 
                                    isSearchActive = false
                                    onSearch("")
                                }) { 
                                    Icon(Icons.Default.Close, contentDescription = "取消搜索", tint = Neutral900) 
                                }
                                TextField(
                                    value = searchQuery,
                                    onValueChange = { onSearch(it) },
                                    placeholder = { Text("搜索课程名、教师或ID", color = Neutral500) },
                                    modifier = Modifier.weight(1f),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Neutral100,
                                        unfocusedContainerColor = Neutral50,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        cursorColor = NeuPrimary,
                                        focusedTextColor = Neutral900,
                                        unfocusedTextColor = Neutral900,
                                        disabledTextColor = Neutral300,
                                        focusedPlaceholderColor = Neutral300,
                                        unfocusedPlaceholderColor = Neutral300
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                        }
                        "multiSelect" -> {
                            //  Sélection 模式顶栏
                            TopAppBar(
                                title = { Text("已选 ${selectedClassIds.size} 门课程", style = MaterialTheme.typography.titleMedium, color = Neutral900) },
                                navigationIcon = {
                                    IconButton(onClick = { exitMultiSelectMode() }) {
                                        Icon(Icons.Default.Close, contentDescription = "取消", tint = Neutral900)
                                    }
                                },
                                actions = {
                                    TextButton(onClick = {
                                        val selectable = courses.filter { !it.isSelected }
                                        if (selectedClassIds.size == selectable.size) {
                                            selectedClassIds = emptySet()
                                        } else {
                                            selectedClassIds = selectable.mapNotNull { it.classId }.toSet()
                                        }
                                    }) {
                                        Text("全选", color = NeuPrimary, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = Color.Transparent,
                                    titleContentColor = Neutral900,
                                    navigationIconContentColor = Neutral900
                                )
                            )
                        }
                        else -> {
                            // 🏠 标准模式：液态玻璃分段选择器
                            CenterAlignedTopAppBar(
                                title = {
                                    com.tyust.course.ui.system.SystemSegmentedControl(
                                        options = listOf("可选", "已选"),
                                        selectedIndex = if (showSelectedCourses) 1 else 0,
                                        onSelect = { index -> showSelectedCourses = index == 1 },
                                        modifier = Modifier.width(200.dp)
                                    )
                                },
                                actions = {
                                    if (!showSelectedCourses) {
                                        IconButton(onClick = { isSearchActive = true }) {
                                            Icon(Icons.Default.Search, contentDescription = "搜索", tint = Neutral900)
                                        }
                                    }
                                    IconButton(onClick = { loadCourses() }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = Neutral900)
                                    }
                                },
                                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                    containerColor = Color.Transparent
                                )
                            )
                        }
                    }
                }
                // 底部柔和渐变线
                com.tyust.course.ui.system.SystemDivider(alpha = 0.6f)
                }
            }
        },
        floatingActionButton = {
            if (isMultiSelectMode && selectedClassIds.isNotEmpty() && !showSelectedCourses) {
                ExtendedFloatingActionButton(
                    onClick = {
                        val selected = courses.filter { it.classId in selectedClassIds }
                        scope.launch {
                            performBatchSelection(selected)
                            exitMultiSelectMode()
                        }
                    },
                    containerColor = Color(0xFF4CAF50),
                    contentColor = Color.White,
                    icon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                    text = { Text("批量抢课 (${selectedClassIds.size})") }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            AnimatedContent(
                targetState = showSelectedCourses,
                transitionSpec = {
                    if (targetState) {
                        // 进入已选课程：从右向左滑入
                        (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> -width / 4 } + fadeOut())
                    } else {
                        // 返回可选课程：从左向右滑入
                        (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> width / 4 } + fadeOut())
                    }
                },
                label = "CourseViewSwitch"
            ) { targetShowSelected ->
                if (targetShowSelected) {
                    SelectedCoursesRoute()
                } else {
                    CourseListScreen(
                        courses = courses,
                        isDetailsReady = isDetailsReady,
                        isLoading = isLoading,
                        onRefresh = { loadCourses() },
                        onSearch = { onSearch(it) },
                        onCourseSelect = { course ->
                            if (course.isSelected) {
                                Toast.makeText(context, "课程已选: ${course.name}", Toast.LENGTH_SHORT).show()
                            } else {
                                scope.launch { performSelection(course) }
                            }
                        },
                        onAutoGrab = { course ->
                            scope.launch { performSelection(course) }
                        },
                        onFetchDetails = { classes, onComplete ->
                           fetchDetailsForClasses(classes, onComplete)
                        },
                        onBatchSelect = { /* 处理在 Route 层的 FAB 中 */ },
                        isBatchSelecting = isBatchSelecting,
                        isPreloading = isPreloading,
                        preloadProgress = preloadProgress,
                        preloadedGroupIds = preloadedGroupIds,
                        onAddToQueue = { course ->
                            val success = SmartSelector.getInstance().addToQueue(course)
                            if (success) {
                                // 🔧 重置该课程在 UI 中的状态，防止显示之前的抢课结果
                                try {
                                    val prefs = context.getSharedPreferences("grab_pro_prefs", Context.MODE_PRIVATE)
                                    val savedStatuses = prefs.getString("queue_item_statuses", "") ?: ""
                                    val courseKey = "${course.name ?: ""}_${course.teacher ?: ""}_${course.time ?: ""}"
                                    
                                    val statusMap = mutableMapOf<String, String>()
                                    if (savedStatuses.isNotEmpty()) {
                                        savedStatuses.split(";").forEach { pair ->
                                            val parts = pair.split("=")
                                            if (parts.size == 2) statusMap[parts[0]] = parts[1]
                                        }
                                    }
                                    statusMap[courseKey] = "WAITING"
                                    
                                    val newSaved = statusMap.entries.joinToString(";") { "${it.key}=${it.value}" }
                                    prefs.edit().putString("queue_item_statuses", newSaved).apply()
                                } catch (e: Exception) {
                                    Log.e("CourseListRoute", "Error resetting queue status: ${e.message}")
                                }
                                
                                Toast.makeText(context, "已加入抢课队列: ${course.name}", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "已经在队列中: ${course.name}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onSetTargetCourse = { course ->
                            SmartSelector.getInstance().setTargetCourse(course)
                            Toast.makeText(context, "🎯 已设为目标课程: ${course.name}", Toast.LENGTH_SHORT).show()
                        },
                        // 🔧 模糊匹配目标设置
                        onSetFuzzyMatchTarget = { courseId, courseName, xkkzId, kklxdm ->
                            SmartSelector.getInstance().setFuzzyMatchTarget(courseId, courseName, xkkzId, kklxdm)
                        },
                        isMultiSelectMode = isMultiSelectMode,
                        selectedClassIds = selectedClassIds,
                        onToggleSelection = { classId, isSelected ->
                            selectedClassIds = if (isSelected) {
                                selectedClassIds - classId
                            } else {
                                selectedClassIds + classId
                            }
                        },
                        onEnterMultiSelect = { classId ->
                            isMultiSelectMode = true
                            selectedClassIds = setOf(classId)
                        }
                    )
                }
            }
        }
    }
}

// Helper class to manage complex parsing logic (extracted from Fragment)
private class CourseListLogicHelper(
    val context: android.content.Context,
    val school: SchoolConfig,
    val onSuccess: (List<Course>) -> Unit,
    val onError: (String) -> Unit,
    // 🔧 渐进式加载回调：每加载一批就立即回调
    val onProgress: ((List<Course>, Int, Int) -> Unit)? = null, // (累积课程, 已完成Tab数, 总Tab数)
    // 🔧 新增：暴露 displayParams 给外部使用
    val onDisplayParams: ((Map<String, String>) -> Unit)? = null
) {
    private var indexParams = mutableMapOf<String, String>()
    var displayParams = mutableMapOf<String, String>()  // 🔧 改为公开
    
    data class TabParam(val kklxdm: String, val xkkz_id: String, val njdm_id: String, val zyh_id: String)
    private var tabParamsList = mutableListOf<TabParam>()
    private var currentTabIndex = 0
    private var allCourses = mutableListOf<Course>()
    
    fun parseIndexParamsAndFetch(html: String) {
        // Logic from parseIndexParams
         try {
            val pattern = """<input[^>]*name="([^"]+)"[^>]*value="([^"]*)"[^>]*>""".toRegex()
            pattern.findAll(html).forEach { match ->
                indexParams[match.groupValues[1]] = match.groupValues[2]
            }
            
            val pattern2 = """<input[^>]*value="([^"]*)"[^>]*name="([^"]+)"[^>]*>""".toRegex()
            pattern2.findAll(html).forEach { match ->
                 val name = match.groupValues[2]
                 if (!indexParams.containsKey(name)) indexParams[name] = match.groupValues[1]
            }

            val queryCoursePattern = """queryCourse\s*\(\s*this\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*,\s*['"]([^'"]*)['"]\s*\)""".toRegex()
            queryCoursePattern.findAll(html).forEach { match ->
                tabParamsList.add(TabParam(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4]))
            }
            
            if (tabParamsList.isEmpty()) {
                 tabParamsList.add(TabParam(
                     indexParams["firstKklxdm"] ?: indexParams["kklxdm"] ?: "10",
                     indexParams["firstXkkzId"] ?: indexParams["xkkz_id"] ?: "",
                     indexParams["njdm_id"] ?: "2024",
                     indexParams["zyh_id"] ?: ""
                 ))
            }
            
            fetchDisplayPage()
        } catch (e: Exception) {
            onError("解析Index失败: ${e.message}")
        }
    }
    
    private fun fetchDisplayPage() {
         if (tabParamsList.isEmpty()) { onError("未找到选课参数"); return }
         
         currentTabIndex = 0
         allCourses.clear()
         fetchNextCategory()
    }
    
    private fun fetchNextCategory() {
        if (currentTabIndex >= tabParamsList.size) {
            onSuccess(allCourses)
            return
        }
        
        val tab = tabParamsList[currentTabIndex]
        currentTabIndex++
        
        CourseApiClient.getInstance().fetchCourseDisplayParams(
            school, tab.xkkz_id, tab.kklxdm, tab.njdm_id, tab.zyh_id,
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                     // Try fetch list anyway (fallback)
                     fetchCategoryList(tab)
                }
                override fun onResponse(call: Call, response: Response) {
                    val html = response.body?.string() ?: ""
                    
                    // 检查是否返回了登录页面
                    if (html.contains("<!doctype", ignoreCase = true) || html.contains("login", ignoreCase = true)) {
                        android.util.Log.e("CourseListRoute", "⚠️ Step 2 (Display) 可能返回了登录页面! 前200字符: ${html.take(200)}")
                    }
                    
                    // Parse display params
                    val pattern = """<input[^>]*name="([^"]+)"[^>]*value="([^"]*)"[^>]*>""".toRegex()
                    pattern.findAll(html).forEach { match -> displayParams[match.groupValues[1]] = match.groupValues[2] }
                    
                    // 打印解析结果
                    android.util.Log.d("CourseListRoute", "✅ Step 2 解析了 ${displayParams.size} 个 displayParams")
                    if (displayParams.isNotEmpty()) {
                        android.util.Log.d("CourseListRoute", "   关键参数: rwlx=${displayParams["rwlx"]}, xklc=${displayParams["xklc"]}, bklx_id=${displayParams["bklx_id"]}")
                        // 🔧 回调传递 displayParams 给外部
                        onDisplayParams?.invoke(displayParams.toMap())
                    }
                    
                    fetchCategoryList(tab)
                }
            }
        )
    }
    
    // 分页状态变量（与 Web 版 course-fetcher.ts 一致）
    private var currentKspage = 0
    private var currentJspage = 10
    private var currentTab: TabParam? = null
    private var currentMergedParams = mutableMapOf<String, String>()
    
    // 🔧 服务器延迟检测：重试配置
    private var currentRetryCount = 0
    private val MAX_RETRY_COUNT = 3 // 最大重试次数
    private val RETRY_DELAY_MS = 2000L // 重试延迟（毫秒）
    
    private fun fetchCategoryList(tab: TabParam) {
        currentTab = tab
        
        // 合并参数
        currentMergedParams.clear()
        currentMergedParams.putAll(indexParams)
        currentMergedParams.putAll(displayParams)
        
        android.util.Log.d("CourseListRoute", "📊 参数统计: indexParams=${indexParams.size}个, displayParams=${displayParams.size}个, 合并后=${currentMergedParams.size}个")
        
        currentMergedParams["xkkz_id"] = tab.xkkz_id
        currentMergedParams["kklxdm"] = tab.kklxdm
        currentMergedParams["njdm_id"] = tab.njdm_id
        currentMergedParams["zyh_id"] = tab.zyh_id
        
        // rwlx 逻辑
        val kklxdm = tab.kklxdm
        if (kklxdm == "01") {
            currentMergedParams["rwlx"] = "1"
        } else if (kklxdm == "10" || kklxdm == "05") {
            currentMergedParams["rwlx"] = "2"
        }
        
        // 🔧 快速获取：初始jspage=1000一次获取大量课程，同时保留递增验证防止漏课
        // 如果服务器支持大页面，会一次返回很多课程；如果不支持，递增机制会继续获取
        currentKspage = 0
        currentJspage = 1000 // 初始值改为1000，快速获取
        currentRetryCount = 0 // 🔧 重置重试计数器
        
        android.util.Log.d("CourseListRoute", "🚀 开始快速获取分类 ${tab.kklxdm} (一次获取最多1000条)...")
        fetchCategoryPage()
    }
    
    // 获取分类的单页数据（递归调用实现多页获取）
    private fun fetchCategoryPage() {
        val tab = currentTab ?: return
        
        // 🔧 关键修复：只发送 Web 版需要的特定参数，而不是全部参数
        // 参考 course-fetcher.ts 的 buildFormDataPart1 函数
        val formData = mutableMapOf<String, String>()
        
        // 辅助函数：获取参数值（支持带后缀的字段，如 jg_id_1）
        fun getParam(baseName: String): String {
            // 优先使用不带后缀的
            currentMergedParams[baseName]?.takeIf { it.isNotEmpty() }?.let { return it }
            // 查找带后缀的版本
            for (i in 1..5) {
                currentMergedParams["${baseName}_$i"]?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return ""
        }
        
        // 基础参数
        val kklxdm = tab.kklxdm
        val rwlx = currentMergedParams["rwlx"] ?: when(kklxdm) { "01" -> "1"; "10", "05" -> "2"; else -> "1" }
        val xklc = currentMergedParams["xklc"] ?: when(kklxdm) { "01" -> "2"; "10" -> "4"; "05" -> "3"; else -> "2" }
        
        formData["rwlx"] = rwlx
        formData["xklc"] = xklc
        formData["xkly"] = currentMergedParams["xkly"] ?: "0"
        formData["bklx_id"] = currentMergedParams["bklx_id"] ?: "0"
        formData["sfkkjyxdxnxq"] = currentMergedParams["sfkkjyxdxnxq"] ?: "0"
        formData["kzkcgs"] = currentMergedParams["kzkcgs"] ?: "0"
        
        // 动态参数（从 mergedParams 获取）
        val dynamicFields = listOf(
            "xqh_id", "jg_id", "njdm_id_1", "zyh_id_1", "gnjkxdnj", "zyh_id",
            "zyfx_id", "njdm_id", "bh_id", "bjgkczxbbjwcx", "xbm", "xslbdm", "mzm", "xz",
            "ccdm", "xsbj", "sfkknj", "sfkkzy", "kzybkxy", "sfznkx", "zdkxms",
            "sfkxq", "sfkcfx", "kkbk", "kkbkdj", "bklbkcj", "sfkgbcx",
            "sfrxtgkcxd", "tykczgxdcs", "xkxnm", "xkxqm", "xkxskcgskg"
        )
        
        // 默认值（根据 Web 版）
        val defaultValues = mapOf(
            "jg_id" to "05",
            "gnjkxdnj" to "0",
            "bjgkczxbbjwcx" to if (kklxdm == "05") "1" else "0",
            "sfkknj" to "0",
            "sfkkzy" to "0",
            "kzybkxy" to "0",
            "sfznkx" to "0",
            "zdkxms" to "0",
            "sfkxq" to "0",
            "sfkcfx" to if (kklxdm == "05") "1" else "0",
            "kkbk" to "0",
            "kkbkdj" to "0",
            "bklbkcj" to "0",
            "sfkgbcx" to if (kklxdm == "05") "1" else "0",
            "sfrxtgkcxd" to if (kklxdm == "05") "1" else "0",
            "tykczgxdcs" to if (kklxdm == "05") "8" else "0"
        )
        
        for (field in dynamicFields) {
            val value = getParam(field)
            formData[field] = value.ifEmpty { defaultValues[field] ?: "" }
        }
        
        // 选项卡参数
        formData["kklxdm"] = tab.kklxdm
        formData["xkkz_id"] = tab.xkkz_id
        
        // 分页参数
        formData["kspage"] = currentKspage.toString()
        formData["jspage"] = currentJspage.toString()
        
        // 其他固定参数
        formData["bbhzxjxb"] = "0"
        formData["rlkz"] = "0"
        formData["xkzgbj"] = "0"
        formData["jxbzb"] = ""
        
        val postBody = formData.entries.joinToString("&") { "${it.key}=${it.value}" }
        
        android.util.Log.d("CourseListRoute", "📄 请求页面: kspage=$currentKspage, jspage=$currentJspage")
        
        CourseApiClient.getInstance().fetchAvailableCourses(school, postBody, object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                // 🔧 服务器延迟检测：失败时重试
                if (currentRetryCount < MAX_RETRY_COUNT) {
                    currentRetryCount++
                    android.util.Log.w("CourseListRoute", "⚠️ 请求失败 (${e.message})，等待 ${RETRY_DELAY_MS}ms 后重试 ($currentRetryCount/$MAX_RETRY_COUNT)")
                    try {
                        Thread.sleep(RETRY_DELAY_MS)
                    } catch (_: InterruptedException) {}
                    fetchCategoryPage() // 重试当前页
                } else {
                    android.util.Log.e("CourseListRoute", "❌ 重试 $MAX_RETRY_COUNT 次后仍失败，跳过此分类")
                    currentRetryCount = 0 // 重置计数器
                    fetchNextCategory()
                }
            }
            
            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string() ?: ""
                currentRetryCount = 0 // 🔧 成功后重置重试计数器
                
                // 检查 Step 3 是否返回了 HTML 而非 JSON
                if (json.trimStart().startsWith("<")) {
                    android.util.Log.e("CourseListRoute", "❌ Step 3 返回了 HTML 而非 JSON! 前300字符: ${json.take(300)}")
                }
                
                try {
                    val parsed = CourseParser.parseCourseListFromJson(json, currentMergedParams, displayParams)
                    
                    if (parsed.isEmpty()) {
                        // 没有更多数据，这个分类获取完成
                        android.util.Log.d("CourseListRoute", "✅ 分类 ${tab.kklxdm} 页面 kspage=$currentKspage 没有数据，分类获取完成")
                        
                        // 🔧 渐进式回调：每个分类完成后立即通知UI更新
                        onProgress?.invoke(allCourses.toList(), currentTabIndex, tabParamsList.size)
                        
                        fetchNextCategory()
                        return
                    }
                    
                    // 补充分类参数
                    parsed.forEach { c -> 
                        c.kklxdm = tab.kklxdm
                        c._xkkz_id = tab.xkkz_id 
                    }
                    allCourses.addAll(parsed)
                    
                    android.util.Log.d("CourseListRoute", "分类 ${tab.kklxdm} 页面 kspage=$currentKspage 获取到 ${parsed.size} 门课程")
                    
                    // 🔧 每页数据获取后就立即更新UI（不等Tab完成）
                    onProgress?.invoke(allCourses.toList(), currentTabIndex, tabParamsList.size)
                    
                    // 🔧 智能分页：根据获取数量动态调整下一页参数
                    // 确保不漏课：下一页从当前结束位置开始
                    val fetchedCount = parsed.size
                    currentKspage = currentJspage + 1
                    currentJspage = currentKspage + fetchedCount - 1 // 下一页大小基于实际获取数量
                    
                    android.util.Log.d("CourseListRoute", "📄 下一页参数: kspage=$currentKspage, jspage=$currentJspage")
                    
                    // 递归获取下一页
                    fetchCategoryPage()
                    
                } catch(e: Exception) {
                    fetchNextCategory()
                }
            }
        })
    }
}

// Logic for selection
private class CourseSelectionLogic(
    val context: android.content.Context,
    val school: SchoolConfig,
    val baseParams: Map<String, String>?
) {
    // 选课详情解析结果（与Web版 selectionDetails 结构一致）
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
        val jcxxId: String,  // Web版使用的关键参数
        val xkkzId: String
    )
    
    fun performSelection(course: Course) {
        if (course.courseId.isNullOrEmpty()) {
            Toast.makeText(context, "缺少课程ID", Toast.LENGTH_SHORT).show()
            return
        }

        // 🔧 完整参数构建（与 Web 版 course-api.ts fetchSelectionDetails 一致）
        val kklxdm = course.kklxdm?.takeIf { it.isNotEmpty() } ?: baseParams?.get("kklxdm") ?: "01"
        val xkkz_id = course._xkkz_id?.takeIf { it.isNotEmpty() } ?: baseParams?.get("xkkz_id") ?: ""
        val njdm_id = baseParams?.get("njdm_id") ?: "2024"
        val zyh_id = baseParams?.get("zyh_id") ?: ""
        val rwlx = course._rwlx?.takeIf { it.isNotEmpty() } ?: baseParams?.get("rwlx") ?: "1"
        val xklc = course._xklc?.takeIf { it.isNotEmpty() } ?: baseParams?.get("xklc") ?: "2"
        val xkly = baseParams?.get("xkly") ?: "0"
        
        // 辅助函数：获取参数值（支持带后缀的字段）
        fun getParam(baseName: String): String {
            baseParams?.get(baseName)?.takeIf { it.isNotEmpty() }?.let { return it }
            for (i in 1..5) {
                baseParams?.get("${baseName}_$i")?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return ""
        }
        
        // 构建完整的 POST body（与 Web 版一致，约40个参数）
        val formData = mutableMapOf<String, String>()
        
        formData["rwlx"] = rwlx
        formData["xkly"] = xkly
        formData["bklx_id"] = baseParams?.get("bklx_id") ?: "0"
        formData["sfkkjyxdxnxq"] = baseParams?.get("sfkkjyxdxnxq") ?: "0"
        formData["kzkcgs"] = baseParams?.get("kzkcgs") ?: "0"
        formData["xqh_id"] = getParam("xqh_id").ifEmpty { "1" }
        formData["jg_id"] = getParam("jg_id").ifEmpty { "05" }
        formData["zyh_id"] = zyh_id
        formData["zyfx_id"] = getParam("zyfx_id").ifEmpty { "wfx" }
        formData["txbsfrl"] = baseParams?.get("txbsfrl") ?: "0"
        formData["njdm_id"] = njdm_id
        formData["bh_id"] = getParam("bh_id")
        formData["xbm"] = getParam("xbm").ifEmpty { "1" }
        formData["xslbdm"] = getParam("xslbdm").ifEmpty { "421" }
        formData["mzm"] = getParam("mzm").ifEmpty { "01" }
        formData["xz"] = getParam("xz").ifEmpty { "4" }
        formData["ccdm"] = getParam("ccdm").ifEmpty { "3" }
        formData["xsbj"] = getParam("xsbj").ifEmpty { "0" }
        formData["sfkknj"] = baseParams?.get("sfkknj") ?: "0"
        formData["gnjkxdnj"] = baseParams?.get("gnjkxdnj") ?: "0"
        formData["sfkkzy"] = baseParams?.get("sfkkzy") ?: "0"
        formData["kzybkxy"] = baseParams?.get("kzybkxy") ?: "0"
        formData["sfznkx"] = baseParams?.get("sfznkx") ?: "0"
        formData["zdkxms"] = baseParams?.get("zdkxms") ?: "0"
        formData["sfkxq"] = baseParams?.get("sfkxq") ?: course._sfkxq ?: "0"
        formData["sfkcfx"] = baseParams?.get("sfkcfx") ?: "0"
        formData["bbhzxjxb"] = baseParams?.get("bbhzxjxb") ?: "0"
        formData["kkbk"] = baseParams?.get("kkbk") ?: "0"
        formData["kkbkdj"] = baseParams?.get("kkbkdj") ?: "0"
        formData["bklbkcj"] = baseParams?.get("bklbkcj") ?: "0"
        formData["xkxnm"] = getParam("xkxnm").ifEmpty { "2025" }
        formData["xkxqm"] = getParam("xkxqm").ifEmpty { "12" }
        formData["xkxskcgskg"] = baseParams?.get("xkxskcgskg") ?: course._xkxskcgskg ?: "0"
        formData["rlkz"] = baseParams?.get("rlkz") ?: "0"
        formData["cdrlkz"] = baseParams?.get("cdrlkz") ?: "0"
        formData["rlzlkz"] = baseParams?.get("rlzlkz") ?: "1"
        formData["kklxdm"] = kklxdm
        formData["kch_id"] = course.courseId!!
        formData["jxbzcxskg"] = baseParams?.get("jxbzcxskg") ?: "0"
        formData["xklc"] = xklc
        formData["xkkz_id"] = xkkz_id
        formData["cxbj"] = baseParams?.get("cxbj") ?: "0"
        formData["fxbj"] = baseParams?.get("fxbj") ?: "0"
        
        val postBody = formData.entries.joinToString("&") { "${it.key}=${it.value}" }
        
        android.util.Log.d("CourseSelectionLogic", "选课详情请求参数数量: ${formData.size}")
        android.util.Log.d("CourseSelectionLogic", "选课参数: xkkz_id=$xkkz_id, kklxdm=$kklxdm")

        CourseApiClient.getInstance().fetchCourseSelectionDetails(school, postBody,
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(context, "获取选课详情失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val json = response.body?.string() ?: ""
                    android.util.Log.d("CourseSelectionLogic", "选课详情响应(前500字符): ${json.take(500)}")
                    // 🔧 传入 course.classId 以匹配正确的教学班
                    val details = parseSelectionDetails(json, njdm_id, zyh_id, xkkz_id, course.classId)

                    if (details == null) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(context, "获取选课参数失败", Toast.LENGTH_SHORT).show()
                        }
                        return
                    }

                    executeSelectionWithDetails(school, course, details, kklxdm, rwlx, xklc)
                }
            }
        )
    }
    // 完整的3步选课流程（与Web版 selectCourseWithVerification 完全一致）
    fun performSelectionSync(course: Course): Boolean {
        val xkkz_id = course._xkkz_id ?: baseParams?.get("xkkz_id") ?: ""
        val njdm_id = course.njdm_id ?: baseParams?.get("njdm_id") ?: "2024"
        val zyh_id = course.zyh_id ?: baseParams?.get("zyh_id") ?: ""
        val kklxdm = course.kklxdm ?: baseParams?.get("kklxdm") ?: "01"
        val xqh_id = baseParams?.get("xqh_id") ?: ""
        val jg_id = baseParams?.get("jg_id") ?: ""
        val rwlx = course._rwlx?.ifEmpty { baseParams?.get("rwlx") } ?: "1"
        val xklc = course._xklc?.ifEmpty { baseParams?.get("xklc") } ?: "2"
        
        android.util.Log.d("CourseSelectionLogic", "=== 开始3步选课流程 (Web版兼容) ===")
        android.util.Log.d("CourseSelectionLogic", "课程: ${course.name} (${course.courseId})")
        android.util.Log.d("CourseSelectionLogic", "🔍 course.classId='${course.classId}', course.doJxbId='${course.doJxbId}'")
        
        // Step 0: 获取页面隐藏参数 (Web版 getPageHiddenParams)
        android.util.Log.d("CourseSelectionLogic", "Step 0: 获取页面隐藏参数...")
        val hiddenParamsHtml = CourseApiClient.getInstance().fetchPageHiddenParamsSync(school)
        val hiddenParams = parseHiddenParams(hiddenParamsHtml ?: "")
        android.util.Log.d("CourseSelectionLogic", "隐藏参数: $hiddenParams")
        
        // 合并隐藏参数（优先使用课程数据中的参数）
        val finalNjdmId = if (njdm_id.isNotEmpty()) njdm_id else hiddenParams["njdm_id"] ?: "2024"
        val finalZyhId = if (zyh_id.isNotEmpty()) zyh_id else hiddenParams["zyh_id"] ?: ""
        val finalXqhId = if (xqh_id.isNotEmpty()) xqh_id else hiddenParams["xqh_id"] ?: ""
        val finalJgId = if (jg_id.isNotEmpty()) jg_id else hiddenParams["jg_id"] ?: ""
        
        // 🔧 构建完整的 POST body（与异步版 performSelection 和 Web 版一致，约40个参数）
        fun getParam(baseName: String): String {
            hiddenParams[baseName]?.takeIf { it.isNotEmpty() }?.let { return it }
            baseParams?.get(baseName)?.takeIf { it.isNotEmpty() }?.let { return it }
            for (i in 1..5) {
                hiddenParams["${baseName}_$i"]?.takeIf { it.isNotEmpty() }?.let { return it }
                baseParams?.get("${baseName}_$i")?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return ""
        }
        
        val formData = mutableMapOf<String, String>()
        formData["rwlx"] = rwlx
        formData["xkly"] = hiddenParams["xkly"] ?: "0"
        formData["bklx_id"] = hiddenParams["bklx_id"] ?: "0"
        formData["sfkkjyxdxnxq"] = hiddenParams["sfkkjyxdxnxq"] ?: "0"
        formData["kzkcgs"] = hiddenParams["kzkcgs"] ?: "0"
        formData["xqh_id"] = finalXqhId.ifEmpty { "1" }
        formData["jg_id"] = finalJgId.ifEmpty { getParam("jg_id").ifEmpty { "05" } }
        formData["zyh_id"] = finalZyhId
        formData["zyfx_id"] = getParam("zyfx_id").ifEmpty { "wfx" }
        formData["txbsfrl"] = hiddenParams["txbsfrl"] ?: "0"
        formData["njdm_id"] = finalNjdmId
        formData["bh_id"] = getParam("bh_id")
        formData["xbm"] = getParam("xbm").ifEmpty { "1" }
        formData["xslbdm"] = getParam("xslbdm").ifEmpty { "421" }
        formData["mzm"] = getParam("mzm").ifEmpty { "01" }
        formData["xz"] = getParam("xz").ifEmpty { "4" }
        formData["ccdm"] = getParam("ccdm").ifEmpty { "3" }
        formData["xsbj"] = getParam("xsbj").ifEmpty { "0" }
        formData["sfkknj"] = hiddenParams["sfkknj"] ?: "0"
        formData["gnjkxdnj"] = hiddenParams["gnjkxdnj"] ?: "0"
        formData["sfkkzy"] = hiddenParams["sfkkzy"] ?: "0"
        formData["kzybkxy"] = hiddenParams["kzybkxy"] ?: "0"
        formData["sfznkx"] = hiddenParams["sfznkx"] ?: "0"
        formData["zdkxms"] = hiddenParams["zdkxms"] ?: "0"
        formData["sfkxq"] = hiddenParams["sfkxq"] ?: course._sfkxq ?: "0"
        formData["sfkcfx"] = hiddenParams["sfkcfx"] ?: "0"
        formData["bbhzxjxb"] = hiddenParams["bbhzxjxb"] ?: "0"
        formData["kkbk"] = hiddenParams["kkbk"] ?: "0"
        formData["kkbkdj"] = hiddenParams["kkbkdj"] ?: "0"
        formData["bklbkcj"] = hiddenParams["bklbkcj"] ?: "0"
        formData["xkxnm"] = getParam("xkxnm").ifEmpty { "2025" }
        formData["xkxqm"] = getParam("xkxqm").ifEmpty { "12" }
        formData["xkxskcgskg"] = hiddenParams["xkxskcgskg"] ?: course._xkxskcgskg ?: "0"
        formData["rlkz"] = hiddenParams["rlkz"] ?: "0"
        formData["cdrlkz"] = hiddenParams["cdrlkz"] ?: "0"
        formData["rlzlkz"] = hiddenParams["rlzlkz"] ?: "1"
        formData["kklxdm"] = kklxdm
        formData["kch_id"] = course.courseId ?: ""
        formData["jxbzcxskg"] = hiddenParams["jxbzcxskg"] ?: "0"
        formData["xklc"] = xklc
        formData["xkkz_id"] = xkkz_id
        formData["cxbj"] = hiddenParams["cxbj"] ?: "0"
        formData["fxbj"] = hiddenParams["fxbj"] ?: "0"
        
        val detailsPostBody = formData.entries.joinToString("&") { "${it.key}=${it.value}" }
        
        // Step 1: 获取选课详情（包含加密的 jxb_id 和其他参数）
        android.util.Log.d("CourseSelectionLogic", "Step 1: 获取选课详情 (do_jxb_id)...")
        android.util.Log.d("CourseSelectionLogic", "Step 1 参数数量: ${formData.size}")
        val detailsResponse = CourseApiClient.getInstance().fetchCourseSelectionDetailsSync(
            school, detailsPostBody
        )
        
        if (detailsResponse == null) {
            android.util.Log.e("CourseSelectionLogic", "Step 1 失败: 获取选课详情返回 null")
            return false
        }

        // 🔧 传入 course.classId 以匹配正确的教学班
        val details = parseSelectionDetails(detailsResponse, finalNjdmId, finalZyhId, xkkz_id, course.classId)
        if (details == null) {
            android.util.Log.e("CourseSelectionLogic", "Step 1 失败: 解析选课详情失败")
            return false
        }
        android.util.Log.d("CourseSelectionLogic", "Step 1 成功: do_jxb_id 长度=${details.doJxbId.length}")

        // Step 2: 执行选课
        android.util.Log.d("CourseSelectionLogic", "Step 2: 执行选课...")
        val postBody = buildSelectionBodyWithDetails(course, details, kklxdm, rwlx, xklc)
        val result = CourseApiClient.getInstance().selectCourseSync(school, postBody)
        
        val success = result != null && (result.contains("\"flag\":\"1\"") || result.contains("成功"))
        android.util.Log.d("CourseSelectionLogic", "Step 2 结果: success=$success, response=${result?.take(200)}")
        
        if (!success) {
            // 🔧 解析服务器返回的具体错误信息
            val errorMsg = parseServerErrorMessage(result)
            android.util.Log.e("CourseSelectionLogic", "Step 2 失败: $errorMsg")
            // 在主线程显示 Toast
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, errorMsg, android.widget.Toast.LENGTH_LONG).show()
            }
            return false
        }
        
        // Step 3: 验证选课结果 (Web版 verifyCourseSelection)
        android.util.Log.d("CourseSelectionLogic", "Step 3: 验证选课结果...")
        val verified = verifySelection(course.courseId ?: "")
        android.util.Log.d("CourseSelectionLogic", "Step 3 结果: verified=$verified")
        android.util.Log.d("CourseSelectionLogic", "=== 选课流程结束 ===")
        
        return success  // 即使验证失败，只要选课请求成功就返回true
    }
    
    // 🔧 解析服务器返回的错误信息
    private fun parseServerErrorMessage(json: String?): String {
        if (json.isNullOrEmpty()) return "服务器无响应"
        try {
            val obj = JSONObject(json)
            val msg = obj.optString("msg", "")
            if (msg.isNotEmpty()) return msg
            val flag = obj.optString("flag", "")
            if (flag == "0") return "选课失败"
        } catch (e: Exception) {
            // 不是 JSON，可能是 HTML 错误页面
            if (json.contains("<title>错误提示</title>")) {
                return "服务器返回错误页面"
            }
        }
        return "选课失败: ${json.take(100)}"
    }
    
    // 解析HTML页面中的隐藏参数
    private fun parseHiddenParams(html: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        try {
            // 使用正则表达式提取 input[type="hidden"] 的 name 和 value
            val pattern = java.util.regex.Pattern.compile(
                """<input[^>]*type\s*=\s*["']hidden["'][^>]*name\s*=\s*["']([^"']+)["'][^>]*value\s*=\s*["']([^"']*)["'][^>]*>""",
                java.util.regex.Pattern.CASE_INSENSITIVE
            )
            val matcher = pattern.matcher(html)
            while (matcher.find()) {
                val name = matcher.group(1)
                val value = matcher.group(2)
                if (name != null) {
                    params[name] = value ?: ""
                }
            }
            // 也尝试反向顺序 (value在name之前)
            val pattern2 = java.util.regex.Pattern.compile(
                """<input[^>]*value\s*=\s*["']([^"']*)["'][^>]*name\s*=\s*["']([^"']+)["'][^>]*>""",
                java.util.regex.Pattern.CASE_INSENSITIVE
            )
            val matcher2 = pattern2.matcher(html)
            while (matcher2.find()) {
                val value = matcher2.group(1)
                val name = matcher2.group(2)
                if (name != null && !params.containsKey(name)) {
                    params[name] = value ?: ""
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CourseSelectionLogic", "parseHiddenParams error: ${e.message}")
        }
        return params
    }
    
    // 验证选课是否成功 (Web版 verifyCourseSelection)
    private fun verifySelection(courseId: String): Boolean {
        try {
            // 获取已选课程列表
            val selectedCoursesJson = CourseApiClient.getInstance().fetchSelectedCoursesSync(school, "")
            if (selectedCoursesJson == null) return false
            
            // 检查课程是否在已选列表中
            val arr = JSONArray(selectedCoursesJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val kchId = obj.optString("kch_id", "")
                if (kchId == courseId) {
                    android.util.Log.d("CourseSelectionLogic", "验证成功: 找到已选课程 $courseId")
                    return true
                }
            }
            android.util.Log.w("CourseSelectionLogic", "验证未通过: 未在已选列表中找到 $courseId")
        } catch (e: Exception) {
            android.util.Log.e("CourseSelectionLogic", "verifySelection error: ${e.message}")
        }
        return false
    }
    
    // 解析选课详情响应，提取所有必要参数（与Web版 executeCourseSelection 保持一致）
    // 🔧 修复：增加 classId 参数，匹配正确的教学班
    private fun parseSelectionDetails(json: String, defaultNjdmId: String, defaultZyhId: String, defaultXkkzId: String, targetClassId: String? = null): SelectionDetails? {
        try {
            val arr = JSONArray(json)
            if (arr.length() == 0) return null
            
            android.util.Log.d("CourseSelectionLogic", "=== 开始匹配教学班 ===")
            android.util.Log.d("CourseSelectionLogic", "目标 classId: '$targetClassId' (长度: ${targetClassId?.length ?: 0})")
            android.util.Log.d("CourseSelectionLogic", "响应包含 ${arr.length()} 个教学班")
            
            // 🔧 查找匹配的教学班（优先按 classId 匹配，否则取第一个）
            var targetObj: JSONObject? = null
            
            if (!targetClassId.isNullOrEmpty()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val jxbId = obj.optString("jxb_id", "")
                    val doJxbId = obj.optString("do_jxb_id", "")
                    
                    android.util.Log.d("CourseSelectionLogic", "[$i] jxb_id='$jxbId', do_jxb_id='$doJxbId'")
                    android.util.Log.d("CourseSelectionLogic", "[$i] 比较: jxbId==targetClassId:${jxbId == targetClassId}, doJxbId==targetClassId:${doJxbId == targetClassId}")
                    
                    // 尝试多种匹配方式
                    if (jxbId == targetClassId || doJxbId == targetClassId) {
                        targetObj = obj
                        android.util.Log.d("CourseSelectionLogic", "✅ 找到匹配教学班: index=$i, jxb_id=$jxbId")
                        break
                    }
                }
            }
            
            // 没有匹配到则取第一个
            val obj = targetObj ?: arr.getJSONObject(0)
            if (targetObj == null) {
                android.util.Log.w("CourseSelectionLogic", "⚠️ 未匹配到 classId=$targetClassId，使用第一个教学班")
            }
            
            // 提取加密的 jxb_id（Web版通常是100+字符的长字符串，但有时是32字符的短ID也可用）
            var doJxbId = obj.optString("do_jxb_id", "")
            if (doJxbId.isEmpty()) {
                doJxbId = obj.optString("jxb_id", "")
            }
            
            // 与 Web 版一致：短 ID 警告但继续执行，不直接失败
            if (doJxbId.isEmpty()) {
                android.util.Log.e("CourseSelectionLogic", "jxb_id 为空，无法选课")
                return null
            }
            if (doJxbId.length < 50) {
                android.util.Log.w("CourseSelectionLogic", "⚠️ jxb_id 长度较短 (${doJxbId.length}字符)，可能是短ID，强制继续...")
            } else {
                android.util.Log.d("CourseSelectionLogic", "✅ jxb_id 验证通过，长度: ${doJxbId.length}字符")
            }
            
            // 提取所有参数（与Web版 executeCourseSelection 完全一致）
            return SelectionDetails(
                doJxbId = doJxbId,
                njdmId = obj.optString("njdm_id", defaultNjdmId),
                zyhId = obj.optString("zyh_id", defaultZyhId),
                rlkz = obj.optString("rlkz", "0"),
                rlzlkz = obj.optString("rlzlkz", "1"),
                sxbj = obj.optString("sxbj", "1"),
                xxkbj = obj.optString("xxkbj", "0"),
                cxbj = obj.optString("cxbj", "0"),
                xkxnm = obj.optString("xkxnm", "2025"),
                xkxqm = obj.optString("xkxqm", "12"),
                jcxxId = obj.optString("jcxx_id", ""),  // Web版使用的关键参数
                xkkzId = obj.optString("xkkz_id", defaultXkkzId)
            )
        } catch (e: Exception) { 
            android.util.Log.e("CourseSelectionLogic", "parseSelectionDetails error: ${e.message}")
        }
        return null
    }
    
    private fun executeSelectionWithDetails(
        school: SchoolConfig, course: Course, details: SelectionDetails,
        kklxdm: String, rwlx: String, xklc: String
    ) {
         val postBody = buildSelectionBodyWithDetails(course, details, kklxdm, rwlx, xklc)

         CourseApiClient.getInstance().selectCourse(school, postBody, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, "请求失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.body?.string() ?: ""
                val success = result.contains("\"flag\":\"1\"") || result.contains("成功")
                
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (success) {
                        Toast.makeText(context, "✅ 选课请求成功，请刷新列表确认", Toast.LENGTH_LONG).show()
                    } else {
                        // 🔧 直接显示服务器返回的错误信息
                        val errorMsg = parseServerErrorMessage(result)
                        Toast.makeText(context, "❌ $errorMsg", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }
    
    // 构建选课请求体（完全匹配Web版 executeCourseSelection 的参数顺序和格式）
    private fun buildSelectionBodyWithDetails(
        course: Course, details: SelectionDetails,
        kklxdm: String, rwlx: String, xklc: String
    ): String {
        // 优先使用课程数据中保存的参数（与Web版一致）
        val finalRwlx = if (course._rwlx?.isNotEmpty() == true) course._rwlx else rwlx
        val finalXklc = if (course._xklc?.isNotEmpty() == true) course._xklc else xklc
        val finalXkkzId = if (course._xkkz_id?.isNotEmpty() == true) course._xkkz_id else details.xkkzId
        val finalNjdmId = if (course.njdm_id?.isNotEmpty() == true) course.njdm_id else details.njdmId
        val finalZyhId = if (course.zyh_id?.isNotEmpty() == true) course.zyh_id else details.zyhId
        val finalKklxdm = if (course.kklxdm?.isNotEmpty() == true) course.kklxdm else kklxdm
        
        // 构建请求体（参数顺序与Web版 executeCourseSelection 完全一致）
        val sb = StringBuilder()
        sb.append("jxb_ids=").append(details.doJxbId)
        sb.append("&kch_id=").append(course.courseId)
        sb.append("&kcmc=(").append(course.courseId).append(")").append(course.name ?: "")
        sb.append("&rwlx=").append(finalRwlx)
        sb.append("&rlkz=").append(details.rlkz)
        sb.append("&rlzlkz=").append(details.rlzlkz)
        sb.append("&sxbj=").append(details.sxbj)
        sb.append("&xxkbj=").append(details.xxkbj)
        sb.append("&qz=0")  // qz 参数保持硬编码，与Web版一致
        sb.append("&cxbj=").append(details.cxbj)
        sb.append("&xkkz_id=").append(finalXkkzId)
        sb.append("&njdm_id=").append(finalNjdmId)
        sb.append("&zyh_id=").append(finalZyhId)
        sb.append("&kklxdm=").append(finalKklxdm)
        sb.append("&xklc=").append(finalXklc)
        sb.append("&xkxnm=").append(details.xkxnm)
        sb.append("&xkxqm=").append(details.xkxqm)
        sb.append("&jcxx_id=").append(details.jcxxId)  // Web版使用的关键参数
        return sb.toString()
    }
}


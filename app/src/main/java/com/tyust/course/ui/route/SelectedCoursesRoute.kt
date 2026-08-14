package com.tyust.course.ui.route

import com.tyust.course.ui.system.GlassToaster
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.tyust.course.manager.CourseCacheManager
import com.tyust.course.manager.UserManager
import com.tyust.course.model.Course
import com.tyust.course.network.CourseApiClient
import com.tyust.course.ui.screen.SelectedCoursesScreen
import com.tyust.course.utils.CourseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SelectedCoursesRoute() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var courses by remember { mutableStateOf<List<Course>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isDropping by remember { mutableStateOf(false) }
    
    // 缓存从服务器获取的学年/学期参数（退课 API 需要）
    var xkxnm by remember { mutableStateOf("") }
    var xkxqm by remember { mutableStateOf("") }

    fun isCurrentAccount(accountKey: String): Boolean {
        return UserManager.getInstance().currentAccountStorageKey == accountKey
    }

    fun loadSelectedCourses() {
        val userManager = UserManager.getInstance()
        val school = userManager.currentSchool ?: return
        val requestAccountKey = userManager.currentAccountStorageKey
        isLoading = true
        
        scope.launch(Dispatchers.IO) {
            try {
                // 1. 动态获取已选课程需要的参数（从选课首页提取）
                val paramsBody = withContext(Dispatchers.IO) {
                    val response = CourseApiClient.getInstance().fetchPageHiddenParamsSync(school)
                    if (response != null) {
                        val paramsMap = CourseParser.parseCourseParams(response)
                        // 缓存学年学期参数供退课使用
                        withContext(Dispatchers.Main) {
                            if (isCurrentAccount(requestAccountKey)) {
                                xkxnm = paramsMap["xkxnm"] ?: ""
                                xkxqm = paramsMap["xkxqm"] ?: ""
                            }
                        }
                        
                        // 构建符合 Web 版要求的 POST Body
                        val sb = StringBuilder()
                        val keys = listOf("jg_id", "zyh_id", "njdm_id", "zyfx_id", "bh_id", "xz", "ccdm", "xqh_id", "xkxnm", "xkxqm", "xkly")
                        keys.forEach { key ->
                            val value = paramsMap[key] ?: ""
                            if (sb.isNotEmpty()) sb.append("&")
                            sb.append(key).append("=").append(value)
                        }
                        sb.toString()
                    } else ""
                }
                
                android.util.Log.d("SelectedCoursesRoute", "发送已选课程请求参数: $paramsBody")

                // 2. 使用提取到的参数获取已选课程
                val response = CourseApiClient.getInstance().fetchSelectedCoursesSync(school, paramsBody)
                
                if (response != null) {
                    // 3. 解析课程列表
                    val parsedCourses = CourseParser.parseCourseListFromJson(response)
                    
                    withContext(Dispatchers.Main) {
                        if (!isCurrentAccount(requestAccountKey)) return@withContext
                        courses = parsedCourses
                        isLoading = false
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (!isCurrentAccount(requestAccountKey)) return@withContext
                        isLoading = false
                        GlassToaster.show("未获取到已选课程数据")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isCurrentAccount(requestAccountKey)) return@withContext
                    isLoading = false
                    GlassToaster.show("加载失败: ${e.message}")
                }
            }
        }
    }
    
    // 退课逻辑
    fun performDropCourse(course: Course) {
        val userManager = UserManager.getInstance()
        val school = userManager.currentSchool ?: return
        val requestAccountKey = userManager.currentAccountStorageKey
        val requestXkxnm = xkxnm
        val requestXkxqm = xkxqm
        val kchId = course.courseId ?: return
        val jxbIds = (if (!course.doJxbId.isNullOrEmpty()) course.doJxbId else course.classId) ?: return
        
        if (requestXkxnm.isEmpty() || requestXkxqm.isEmpty()) {
            GlassToaster.show("学年学期参数缺失，请刷新后重试")
            return
        }
        
        isDropping = true
        scope.launch(Dispatchers.IO) {
            try {
                val result = CourseApiClient.getInstance().dropCourseSync(school, kchId, jxbIds, requestXkxnm, requestXkxqm)
                withContext(Dispatchers.Main) {
                    if (!isCurrentAccount(requestAccountKey)) return@withContext
                    isDropping = false
                    // 服务器返回 "1" 或 {"flag":"1"} 都表示成功
                    if (result != null && (result.trim() == "\"1\"" || result.contains("\"flag\":\"1\""))) {
                        GlassToaster.show("退课成功: ${course.name}")
                        // 同步更新本地缓存的 isSelected 状态
                        val cached = CourseCacheManager.getCachedCourses(context, requestAccountKey)
                        if (cached != null) {
                            cached.forEach { c ->
                                if (c.classId == course.classId) c.isSelected = false
                            }
                            CourseCacheManager.saveCourses(context, cached, requestAccountKey)
                        }
                        loadSelectedCourses() // 刷新列表
                    } else {
                        val msg = if (result != null && result.contains("msg")) {
                            try {
                                org.json.JSONObject(result).optString("msg", "未知错误")
                            } catch (e: Exception) { "退课失败" }
                        } else { "退课失败" }
                        GlassToaster.show(msg)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isCurrentAccount(requestAccountKey)) return@withContext
                    isDropping = false
                    GlassToaster.show("退课异常: ${e.message}")
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        loadSelectedCourses()
    }

    SelectedCoursesScreen(
        courses = courses,
        isLoading = isLoading,
        isDropping = isDropping,
        onRefresh = { loadSelectedCourses() },
        onDropCourse = { course -> performDropCourse(course) }
    )
}

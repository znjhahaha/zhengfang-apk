package com.tyust.course.ui.route

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
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

    fun loadSelectedCourses() {
        val school = UserManager.getInstance().currentSchool ?: return
        isLoading = true
        
        scope.launch(Dispatchers.IO) {
            try {
                // 1. 动态获取已选课程需要的参数（从选课首页提取）
                val paramsBody = withContext(Dispatchers.IO) {
                    val response = CourseApiClient.getInstance().fetchPageHiddenParamsSync(school)
                    if (response != null) {
                        val paramsMap = CourseParser.parseCourseParams(response)
                        // 缓存学年学期参数供退课使用
                        xkxnm = paramsMap["xkxnm"] ?: ""
                        xkxqm = paramsMap["xkxqm"] ?: ""
                        
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
                        courses = parsedCourses
                        isLoading = false
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isLoading = false
                        Toast.makeText(context, "未获取到已选课程数据", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    Toast.makeText(context, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    // 退课逻辑
    fun performDropCourse(course: Course) {
        val school = UserManager.getInstance().currentSchool ?: return
        val kchId = course.courseId ?: return
        val jxbIds = course.classId ?: return
        
        if (xkxnm.isEmpty() || xkxqm.isEmpty()) {
            Toast.makeText(context, "学年学期参数缺失，请刷新后重试", Toast.LENGTH_SHORT).show()
            return
        }
        
        isDropping = true
        scope.launch(Dispatchers.IO) {
            try {
                val result = CourseApiClient.getInstance().dropCourseSync(school, kchId, jxbIds, xkxnm, xkxqm)
                withContext(Dispatchers.Main) {
                    isDropping = false
                    // 服务器返回 "1" 或 {"flag":"1"} 都表示成功
                    if (result != null && (result.trim() == "\"1\"" || result.contains("\"flag\":\"1\""))) {
                        Toast.makeText(context, "退课成功: ${course.name}", Toast.LENGTH_SHORT).show()
                        loadSelectedCourses() // 刷新列表
                    } else {
                        val msg = if (result != null && result.contains("msg")) {
                            try {
                                org.json.JSONObject(result).optString("msg", "未知错误")
                            } catch (e: Exception) { "退课失败" }
                        } else { "退课失败" }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isDropping = false
                    Toast.makeText(context, "退课异常: ${e.message}", Toast.LENGTH_SHORT).show()
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

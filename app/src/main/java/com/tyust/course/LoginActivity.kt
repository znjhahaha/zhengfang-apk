package com.tyust.course

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.network.CourseApiClient
import com.tyust.course.ui.screen.LoginScreen
import com.tyust.course.ui.theme.CourseSelectorTheme
import com.tyust.course.utils.CourseParser
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

class LoginActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LoginActivity"
    }

    private var isLoading by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)
    private var cookieFromWebView by mutableStateOf("")
    private var isAutoValidating by mutableStateOf(false)

    // WebView result launcher
    private val webViewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val cookie = result.data?.getStringExtra(CookieWebViewActivity.EXTRA_COOKIE_RESULT)
            if (!cookie.isNullOrBlank()) {
                cookieFromWebView = cookie
                Toast.makeText(this, "Cookie 已获取，点击登录", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize UserManager with context for SharedPreferences
        UserManager.getInstance().init(this)
        
        // 检查是否有保存的有效登录状态
        checkSavedLoginState()
        
        setContent {
            CourseSelectorTheme {
                // Use mutableStateOf for reactive schools list
                var schools by remember { mutableStateOf(UserManager.getInstance().supportedSchools) }
                
                // Ensure default school is set
                LaunchedEffect(schools) {
                    if (schools.isNotEmpty() && UserManager.getInstance().currentSchool == null) {
                        UserManager.getInstance().currentSchool = schools[0]
                    }
                }
                
                // 如果正在自动验证，显示加载状态
                LoginScreen(
                    schools = schools,
                    onSchoolSelected = { school ->
                        UserManager.getInstance().currentSchool = school
                    },
                    onLoginClick = { cookie ->
                        handleLogin(cookie)
                    },
                    onOpenWebView = {
                        openWebView()
                    },
                    onSchoolAdded = {
                        // Refresh schools list after adding
                        schools = UserManager.getInstance().supportedSchools
                    },
                    onDemoMode = {
                        handleDemoMode()
                    },
                    isLoading = isLoading || isAutoValidating,
                    errorMessage = if (isAutoValidating) "正在验证登录状态..." else errorMessage,
                    cookieValue = cookieFromWebView
                )
            }
        }
    }

    // 检查保存的登录状态，有 Cookie 直接进入主页面
    private fun checkSavedLoginState() {
        val userManager = UserManager.getInstance()
        
        if (userManager.hasSavedCookie() && userManager.currentSchool != null) {
            Log.d(TAG, "发现保存的 Cookie，直接进入主页面")
            
            val savedCookie = userManager.savedCookie
            val currentSchool = userManager.currentSchool
            
            // 设置 Cookie 到 API Client
            CourseApiClient.getInstance().setCookie(currentSchool.baseUrl, savedCookie)
            
            // 直接跳转到主页面，不验证 Cookie
            userManager.isLoggedIn = true
            
            Toast.makeText(
                this@LoginActivity,
                "欢迎回来，${userManager.studentName.ifEmpty { "同学" }}",
                Toast.LENGTH_SHORT
            ).show()
            
            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
            finish()
        }
    }

    private fun openWebView() {
        val currentSchool = UserManager.getInstance().currentSchool
        val searchKeyword = if (currentSchool != null) {
            "${currentSchool.name} 教务系统"
        } else {
            "教务系统 登录"
        }
        
        val intent = Intent(this, CookieWebViewActivity::class.java).apply {
            putExtra(CookieWebViewActivity.EXTRA_SEARCH_KEYWORD, searchKeyword)
        }
        webViewLauncher.launch(intent)
    }

    private fun handleDemoMode() {
        val userManager = UserManager.getInstance()
        
        // 设置演示模式标识和假数据
        userManager.isLoggedIn = true
        userManager.isDemoMode = true
        userManager.studentName = "演示用户"
        userManager.studentId = "2024000001"
        
        // 如果没有选择学校，使用第一个
        if (userManager.currentSchool == null && userManager.supportedSchools.isNotEmpty()) {
            userManager.currentSchool = userManager.supportedSchools[0]
        }
        
        Toast.makeText(this, "🎮 已进入演示模式", Toast.LENGTH_SHORT).show()
        
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun handleLogin(cookieStr: String) {
        val currentSchool = UserManager.getInstance().currentSchool
        if (currentSchool == null) {
            errorMessage = "请先选择学校"
            return
        }

        if (cookieStr.isBlank()) {
            errorMessage = "请输入 Cookie"
            return
        }

        isLoading = true
        errorMessage = null

        // 1. Set Cookie
        CourseApiClient.getInstance().setCookie(currentSchool.baseUrl, cookieStr.trim())

        // 2. Validate Cookie
        CourseApiClient.getInstance().validateCookie(currentSchool, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    isLoading = false
                    errorMessage = "网络请求失败: ${e.message}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val html = response.body?.string() ?: ""
                
                // 3. Logic to check success
                val isLoginPage = html.contains("用户登录") ||
                        html.contains("登 录") ||
                        html.contains("统一身份认证") ||
                        html.contains("请先登录")

                val name = CourseParser.parseStudentName(html)
                val hasWelcomeSign = html.contains("欢迎您") ||
                        html.contains("退出") ||
                        html.contains("xsxxwh") ||
                        html.contains("index_initMenu")

                val success = !isLoginPage && (name != null || hasWelcomeSign)

                runOnUiThread {
                    isLoading = false
                    if (success) {
                        val userManager = UserManager.getInstance()
                        userManager.isLoggedIn = true
                        userManager.studentName = name ?: "同学"
                        
                        // 保存 Cookie 用于下次自动登录
                        userManager.saveCookie(cookieStr.trim())
                        Log.d(TAG, "Cookie 已保存，下次可自动登录")
                        
                        Toast.makeText(
                            this@LoginActivity,
                            "登录成功！欢迎 ${name ?: "同学"}",
                            Toast.LENGTH_SHORT
                        ).show()
                        
                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                        finish()
                    } else {
                        errorMessage = if (isLoginPage) {
                            "Cookie 已过期或无效，请重新获取"
                        } else {
                            "无法解析页面，请检查 Cookie 格式"
                        }
                    }
                }
            }
        })
    }
}

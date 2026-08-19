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
import androidx.lifecycle.lifecycleScope
import com.tyust.course.demo.DemoData
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.network.CourseApiClient
import com.tyust.course.ui.screen.LoginScreen
import com.tyust.course.ui.screen.SchoolAdaptationCompletionReminder
import com.tyust.course.ui.screen.SchoolAdaptationFlow
import com.tyust.course.ui.theme.CourseSelectorTheme
import com.tyust.course.utils.CourseParser
import com.tyust.course.login.PasswordLoginCallback
import com.tyust.course.login.PasswordLoginGateway
import com.tyust.course.login.PasswordLoginGatewayFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // Binding Dialog State
    private var showBindingDialog by mutableStateOf(false)
    private var bindingStudentName by mutableStateOf("")
    private var bindingStudentId by mutableStateOf("")
    private var bindingMaxStudents by mutableStateOf(0)
    private var bindingUsedNames by mutableStateOf<Set<String>>(emptySet())
    private var pendingCookie by mutableStateOf("")
    private var pendingPasswordLogin by mutableStateOf(false)

    // Password Login State
    private var activePasswordLoginGateway: PasswordLoginGateway? = null
    private var pendingPasswordSchool: SchoolConfig? = null
    private var pendingPasswordUsername = ""
    private var pendingPasswordValue = ""
    private var captchaImageBytes by mutableStateOf<ByteArray?>(null)

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
        
            // 🔄 每次启动 App 都同步云端激活配置（获取最新的 max_students）
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    com.tyust.course.activation.ActivationManager.checkActivation(this@LoginActivity)
                }
                Log.d(TAG, "已同步云端配置")
            } catch (e: Exception) {
                Log.w(TAG, "启动同步失败: ${e.message}")
            }
            
            // 🔧 关键修复：如果是为了“重新登录”而跳转过来的，不要执行自动登录检查
            val forceRelogin = intent.getBooleanExtra("force_relogin", false)
            if (forceRelogin) {
                Log.d(TAG, "检测到强制重新登录请求，清空旧状态")
                UserManager.getInstance().logout() // 清除登录标记和旧 Cookie
                errorMessage = "请获取新的 Cookie 并登录"
            } else {
                // 检查是否有保存的有效登录状态
                checkSavedLoginState()
            }
        }
        
        setContent {
            CourseSelectorTheme {
                // Use mutableStateOf for reactive schools list
                var schools by remember { mutableStateOf(UserManager.getInstance().supportedSchools) }
                var showSchoolAdaptation by remember { mutableStateOf(false) }
                
                // 🔧 强化版学校选择记忆逻辑
                LaunchedEffect(schools) {
                    val userManager = UserManager.getInstance()
                    // 1. 如果当前没有选定学校，先尝试加载存过的
                    if (userManager.currentSchool == null) {
                        userManager.loadLoginState()
                    }
                    
                    // 2. 如果加载后依然没选中任何学校（比如第一次用），才选第一个
                    if (schools.isNotEmpty() && userManager.currentSchool == null) {
                        userManager.currentSchool = schools[0]
                    }
                }
                
                // 如果正在自动验证，显示加载状态
                if (showSchoolAdaptation) {
                    SchoolAdaptationFlow(
                        onNavigateBack = { showSchoolAdaptation = false }
                    )
                } else {
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
                    onSchoolAdaptation = {
                        showSchoolAdaptation = true
                    },
                    onPasswordLogin = { username, password ->
                        handlePasswordLogin(username, password)
                    },
                    captchaImageBytes = captchaImageBytes,
                    onCaptchaSubmit = { code ->
                        handleCaptchaSubmit(code)
                    },
                    onCaptchaRefresh = {
                        refreshCaptcha()
                    },
                    isLoading = isLoading || isAutoValidating,
                    errorMessage = if (isAutoValidating) "正在验证登录状态..." else errorMessage,
                    cookieValue = cookieFromWebView,
                    showBindingDialog = showBindingDialog,
                    bindingStudentName = bindingStudentName,
                    bindingMaxStudents = bindingMaxStudents,
                    bindingUsedNames = bindingUsedNames,
                    onConfirmBinding = {
                        showBindingDialog = false
                        com.tyust.course.manager.StudentLimitManager.recordStudent(
                            context = this@LoginActivity,
                            schoolId = UserManager.getInstance().currentSchool?.id.orEmpty(),
                            schoolName = UserManager.getInstance().currentSchool?.name.orEmpty(),
                            studentName = bindingStudentName,
                            studentId = bindingStudentId
                        )
                        proceedToMain(UserManager.getInstance(), bindingStudentName, pendingCookie)
                    },
                    onCancelBinding = {
                        showBindingDialog = false
                        discardPendingPasswordLogin()
                        errorMessage = "已取消，账号未绑定"
                    }
                )
                }
                SchoolAdaptationCompletionReminder(
                    enabled = !showSchoolAdaptation &&
                        !showBindingDialog &&
                        captchaImageBytes == null &&
                        !isLoading &&
                        !isAutoValidating
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
        DemoData.resetSession()
        UserManager.getInstance().startDemoSession(DemoData.school())
        Toast.makeText(this, "已进入本地演示模式，不会连接教务系统", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun handleLogin(cookieStr: String) {
        discardPendingPasswordLogin()
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
        pendingPasswordLogin = false

        // 🔄 先同步云端激活配置（获取最新的 max_students）
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    com.tyust.course.activation.ActivationManager.checkActivation(this@LoginActivity)
                }
                Log.d(TAG, "激活配置已同步，max_students=${com.tyust.course.activation.ActivationManager.getMaxStudents(this@LoginActivity)}")
            } catch (e: Exception) {
                Log.w(TAG, "激活配置同步失败: ${e.message}")
            }
            
            // 同步完成后继续登录流程
            pendingPasswordLogin = false
            performLoginValidation(currentSchool, cookieStr)
        }
    }
    
    private fun performLoginValidation(currentSchool: SchoolConfig, cookieStr: String) {
        // 1. Set Cookie
        CourseApiClient.getInstance().setCookie(currentSchool.baseUrl, cookieStr.trim())

        // 2. Validate Cookie
        CourseApiClient.getInstance().validateCookie(currentSchool, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    isLoading = false
                    errorMessage = "网络请求失败: ${e.message}"
                    discardPendingPasswordLogin()
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
                val studentId = CourseParser.parseStudentId(html)
                val hasWelcomeSign = html.contains("欢迎您") ||
                        html.contains("退出") ||
                        html.contains("xsxxwh") ||
                        html.contains("index_initMenu")

                val success = !isLoginPage && (name != null || hasWelcomeSign)

                runOnUiThread {
                    isLoading = false
                    if (success) {
                        val userManager = UserManager.getInstance()
                        if (userManager.currentSchool?.id != currentSchool.id) {
                            errorMessage = "学校已切换，请重新登录"
                            discardPendingPasswordLogin()
                            return@runOnUiThread
                        }
                        val studentNameParsed = name ?: "同学"
                        val studentIdParsed = studentId ?: ""
                        
                        // 保存信息
                        userManager.studentName = studentNameParsed
                        if (studentIdParsed.isNotEmpty()) {
                            userManager.studentId = studentIdParsed
                        }
                        
                        val maxStudents = com.tyust.course.activation.ActivationManager.getMaxStudents(this@LoginActivity)
                        val bindingCheck = com.tyust.course.manager.StudentLimitManager.checkCanUseStudent(
                            context = this@LoginActivity,
                            schoolId = currentSchool.id,
                            schoolName = currentSchool.name,
                            studentName = studentNameParsed,
                            studentId = studentIdParsed,
                            maxStudents = maxStudents
                        )

                        if (!bindingCheck.allowed) {
                            errorMessage = bindingCheck.reason
                            discardPendingPasswordLogin()
                            return@runOnUiThread
                        }

                        if (bindingCheck.alreadyBound || maxStudents <= 0) {
                            if (!bindingCheck.alreadyBound) {
                                Log.d(TAG, "超级账户，无需绑定确认")
                                com.tyust.course.manager.StudentLimitManager.recordStudent(
                                    context = this@LoginActivity,
                                    schoolId = currentSchool.id,
                                    schoolName = currentSchool.name,
                                    studentName = studentNameParsed,
                                    studentId = studentIdParsed
                                )
                            }
                            proceedToMain(userManager, studentNameParsed, cookieStr)
                            return@runOnUiThread
                        }

                        bindingStudentName = studentNameParsed
                        bindingStudentId = studentIdParsed
                        bindingMaxStudents = maxStudents
                        bindingUsedNames = bindingCheck.usedNames
                        pendingCookie = cookieStr
                        showBindingDialog = true

                    } else {
                        discardPendingPasswordLogin()
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
    
    /**
     * 登录成功后进入主界面
     */
    private fun proceedToMain(userManager: UserManager, studentName: String, cookieStr: String) {
        if (pendingPasswordLogin) {
            val passwordSchool = pendingPasswordSchool
            if (passwordSchool == null || userManager.currentSchool?.id != passwordSchool.id) {
                errorMessage = "学校已切换，请重新登录"
                discardPendingPasswordLogin()
                return
            }
        }
        userManager.isLoggedIn = true
        userManager.studentName = studentName
        
        // 保存 Cookie 用于下次自动登录
        if (pendingPasswordLogin) {
            userManager.savePasswordLogin(
                pendingPasswordUsername,
                cookieStr.trim(),
                pendingPasswordValue
            )
        } else {
            userManager.saveCookieLogin(cookieStr.trim())
        }
        discardPendingPasswordLogin()
        Log.d(TAG, "Cookie 已保存，下次可自动登录")
        
        Toast.makeText(
            this@LoginActivity,
            "登录成功！欢迎 $studentName",
            Toast.LENGTH_SHORT
        ).show()
        
        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
        finish()
    }

    // ============ 密码登录 ============

    private fun handlePasswordLogin(username: String, password: String) {
        val school = UserManager.getInstance().currentSchool
        Log.d(TAG, "handlePasswordLogin: school=${school?.name}, baseUrl=${school?.getBaseUrl()}, fullPath=${school?.getFullBasePath()}")
        if (school == null) {
            errorMessage = "请先选择学校"
            return
        }
        if (username.isBlank() || password.isBlank()) {
            errorMessage = "请输入学号和密码"
            return
        }

        isLoading = true
        errorMessage = null
        captchaImageBytes = null

        pendingPasswordUsername = username
        pendingPasswordValue = password
        pendingPasswordSchool = school
        activePasswordLoginGateway?.clearSensitiveState()
        val gateway = PasswordLoginGatewayFactory.create(school)
        activePasswordLoginGateway = gateway

        gateway.login(school, username, password, object : PasswordLoginCallback {
            override fun onSuccess(cookie: String) {
                runOnUiThread {
                    if (UserManager.getInstance().currentSchool?.id != school.id) {
                        isLoading = false
                        errorMessage = "学校已切换，请重新登录"
                        discardPendingPasswordLogin()
                        return@runOnUiThread
                    }
                    gateway.clearSensitiveState()
                    isLoading = false
                    Log.d(TAG, "密码登录成功")
                    pendingPasswordLogin = true
                    // 复用现有验证流程
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                com.tyust.course.activation.ActivationManager.checkActivation(this@LoginActivity)
                            }
                        } catch (_: Exception) {}
                        performLoginValidation(school, cookie)
                    }
                }
            }

            override fun onCaptchaRequired(imageBytes: ByteArray) {
                runOnUiThread {
                    isLoading = false
                    captchaImageBytes = imageBytes
                    Log.d(TAG, "Captcha received: ${imageBytes.size} bytes, dialog should show")
                }
            }

            override fun onCaptchaInvalid() {
                runOnUiThread {
                    isLoading = false
                    errorMessage = "验证码错误，请重新输入"
                    refreshCaptcha()
                }
            }

            override fun onInvalidCredentials() {
                runOnUiThread {
                    isLoading = false
                    errorMessage = "用户名或密码不正确"
                    discardPendingPasswordLogin()
                    Log.d(TAG, "onInvalidCredentials called")
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    isLoading = false
                    errorMessage = message
                    discardPendingPasswordLogin()
                    Log.e(TAG, "onError called: $message")
                }
            }
        })
    }

    private fun handleCaptchaSubmit(code: String) {
        isLoading = true
        errorMessage = null
        // 不清除 captchaImageBytes，保持弹窗可见直到收到响应

        val gateway = activePasswordLoginGateway
        if (gateway == null) {
            isLoading = false
            errorMessage = "登录会话已失效，请重新登录"
            return
        }
        gateway.submitCaptcha(code, object : PasswordLoginCallback {
            override fun onSuccess(cookie: String) {
                Log.d(TAG, "Captcha submit: login SUCCESS")
                runOnUiThread {
                    gateway.clearSensitiveState()
                    isLoading = false
                    captchaImageBytes = null  // 成功时清除
                    val school = pendingPasswordSchool
                    if (school == null || UserManager.getInstance().currentSchool?.id != school.id) {
                        errorMessage = "学校已切换，请重新登录"
                        discardPendingPasswordLogin()
                        return@runOnUiThread
                    }
                    pendingPasswordLogin = true
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                com.tyust.course.activation.ActivationManager.checkActivation(this@LoginActivity)
                            }
                        } catch (_: Exception) {}
                        performLoginValidation(school, cookie)
                    }
                }
            }

            override fun onCaptchaRequired(imageBytes: ByteArray) {
                Log.d(TAG, "Captcha submit: server returned new captcha, size=${imageBytes.size}")
                runOnUiThread {
                    isLoading = false
                    captchaImageBytes = imageBytes  // 刷新图片
                }
            }

            override fun onCaptchaInvalid() {
                Log.d(TAG, "Captcha submit: captcha INVALID")
                runOnUiThread {
                    isLoading = false
                    errorMessage = "验证码错误，请重新输入"
                    refreshCaptcha()
                }
            }

            override fun onInvalidCredentials() {
                Log.d(TAG, "Captcha submit: INVALID credentials")
                runOnUiThread {
                    isLoading = false
                    errorMessage = "用户名或密码不正确"
                    captchaImageBytes = null
                    discardPendingPasswordLogin()
                }
            }

            override fun onError(message: String) {
                Log.e(TAG, "Captcha submit error: $message")
                runOnUiThread {
                    isLoading = false
                    captchaImageBytes = null
                    errorMessage = message
                    discardPendingPasswordLogin()
                }
            }
        })
    }

    private fun refreshCaptcha() {
        activePasswordLoginGateway?.refreshCaptcha { bytes ->
            runOnUiThread {
                if (bytes != null) captchaImageBytes = bytes
            }
        }
    }

    private fun discardPendingPasswordLogin() {
        activePasswordLoginGateway?.clearSensitiveState()
        activePasswordLoginGateway = null
        pendingPasswordSchool = null
        pendingPasswordUsername = ""
        pendingPasswordValue = ""
        pendingPasswordLogin = false
    }

    override fun onDestroy() {
        discardPendingPasswordLogin()
        super.onDestroy()
    }
}

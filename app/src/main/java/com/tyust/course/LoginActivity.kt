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
import com.tyust.course.academic.AcademicGatewayFactory
import com.tyust.course.academic.AcademicPasswordLoginGateway
import com.tyust.course.AcademicWebViewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

class LoginActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.tyust.course.manager.AppThemeCoordinator.wrapContext(newBase))
    }

    companion object {
        private const val TAG = "LoginActivity"
        const val EXTRA_RETURN_TO_CALLER = "return_to_caller"
    }

    private var isLoading by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)
    private var cookieFromWebView by mutableStateOf("")
    private var academicWebPageUrl = ""
    private var academicValidationGeneration = 0
    private var isAutoValidating by mutableStateOf(false)
    private var selectedLoginSchool: SchoolConfig? = null
    private var validationCall: Call? = null
    private val validationAccounts = mutableMapOf<String, SchoolConfig>()
    private var validationJob: kotlinx.coroutines.Job? = null

    // Binding Dialog State
    private var showBindingDialog by mutableStateOf(false)
    private var bindingStudentName by mutableStateOf("")
    private var bindingStudentId by mutableStateOf("")
    private var bindingMaxStudents by mutableStateOf(0)
    private var bindingUsedCount by mutableStateOf(0)
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
            val gateway = activePasswordLoginGateway as? AcademicPasswordLoginGateway
            if (gateway?.webLogin != null) {
                val school = pendingPasswordSchool ?: return@registerForActivityResult
                val page = result.data?.getStringExtra(AcademicWebViewActivity.EXTRA_PAGE_URL).orEmpty()
                isLoading = true
                lifecycleScope.launch {
                    try {
                        val identity = withContext(Dispatchers.IO) { gateway.resumeWebLogin(cookie.orEmpty(), page) }
                        if (selectedLoginSchool?.id != school.id) return@launch
                        if (identity.status != com.tyust.course.academic.AcademicStatus.SUCCESS) throw IllegalStateException(identity.message.ifBlank { "学校尚未确认登录" })
                        pendingPasswordLogin = true
                        finishAcademicLogin(school, cookie.orEmpty(), identity.studentName, identity.studentId)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { errorMessage = e.message ?: "网页登录未完成" }
                    finally { isLoading = false }
                }
                return@registerForActivityResult
            }
            if (!cookie.isNullOrBlank()) {
                cookieFromWebView = cookie
                academicWebPageUrl = result.data?.getStringExtra(AcademicWebViewActivity.EXTRA_PAGE_URL).orEmpty()
                Toast.makeText(this, "Cookie 已获取，点击登录", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.tyust.course.ui.theme.StartupLogoAnimation.install(this)
        super.onCreate(savedInstanceState)
        
        // Initialize UserManager with context for SharedPreferences
        UserManager.getInstance().init(this)
        selectedLoginSchool = UserManager.getInstance().currentSchool
        
            // 🔄 每次启动 App 都同步云端激活配置（获取最新的 max_students）
        lifecycleScope.launch {
            if (!BuildConfig.UI_PREVIEW) try {
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
                errorMessage = "请重新登录以继续使用"
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
                    if (selectedLoginSchool == null) {
                        userManager.loadLoginState()
                        selectedLoginSchool = userManager.currentSchool
                    }
                    
                    // 2. 如果加载后依然没选中任何学校（比如第一次用），才选第一个
                    if (schools.isNotEmpty() && selectedLoginSchool == null) {
                        selectedLoginSchool = schools[0]
                    }
                }
                
                // 如果正在自动验证，显示加载状态
                if (showSchoolAdaptation) {
                    SchoolAdaptationFlow(
                        onNavigateBack = { showSchoolAdaptation = false }
                    )
                } else {
                    LoginScreen(
                    onBack = if (intent.getBooleanExtra(EXTRA_RETURN_TO_CALLER, false)) {
                        { onBackPressedDispatcher.onBackPressed() }
                    } else null,
                    schools = schools,
                    onSchoolSelected = { school ->
                        selectedLoginSchool = school
                        academicValidationGeneration++
                        validationCall?.cancel()
                        validationJob?.cancel()
                        clearValidationSessions()
                        discardPendingPasswordLogin()
                        isLoading = false
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
                    onServiceCenter = { startActivity(Intent(this@LoginActivity, com.tyust.course.academic.plugin.CampusServiceCenterActivity::class.java)) },
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
                    bindingUsedCount = bindingUsedCount,
                    onConfirmBinding = {
                        showBindingDialog = false
                        val recorded = com.tyust.course.manager.StudentLimitManager.recordStudent(
                            context = this@LoginActivity,
                            schoolId = selectedLoginSchool?.id.orEmpty(),
                            schoolName = selectedLoginSchool?.name.orEmpty(),
                            studentName = bindingStudentName,
                            studentId = bindingStudentId
                        )
                        if (recorded) {
                            proceedToMain(UserManager.getInstance(), bindingStudentName, pendingCookie)
                        } else {
                            discardPendingPasswordLogin()
                            errorMessage = "该设备所有学校合计最多绑定 3 个学生账号"
                        }
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
                com.tyust.course.ui.screen.UsageNotice()
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
        val currentSchool = selectedLoginSchool
        if (currentSchool != null && AcademicGatewayFactory.supports(currentSchool)) {
            val hosts = java.util.ArrayList<String>().apply {
                add(currentSchool.domain)
                addAll(currentSchool.allowedAcademicHosts)
            }
            val intent = Intent(this, AcademicWebViewActivity::class.java).apply {
                val challenge = (activePasswordLoginGateway as? AcademicPasswordLoginGateway)?.webLogin
                putExtra(AcademicWebViewActivity.EXTRA_START_URL, challenge?.getString("url") ?: AcademicGatewayFactory.loginUrl(currentSchool))
                putExtra(AcademicWebViewActivity.EXTRA_COOKIE_URL, currentSchool.fullBasePath.trimEnd('/') + "/")
                if (challenge == null) putExtra(AcademicWebViewActivity.EXTRA_SEARCH_KEYWORD, "${currentSchool.name} 教务系统 登录")
                putStringArrayListExtra(AcademicWebViewActivity.EXTRA_ALLOWED_HOSTS, hosts)
            }
            webViewLauncher.launch(intent)
            return
        }
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
        val currentSchool = selectedLoginSchool
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
        val generation = ++academicValidationGeneration
        validationCall?.cancel()
        validationJob?.cancel()
        clearValidationSessions()
        val validationKey = "login-validation-" + java.util.UUID.randomUUID()
        validationAccounts[validationKey] = currentSchool
        val academicGateway = activePasswordLoginGateway as? AcademicPasswordLoginGateway
        if (AcademicGatewayFactory.supports(currentSchool)) {
            val username = pendingPasswordUsername.ifBlank {
                runCatching { android.net.Uri.parse(academicWebPageUrl).getQueryParameter("xh") }.getOrNull().orEmpty()
            }
            val pluginAuth = com.tyust.course.academic.plugin.AcademicProviderRegistry.overrides(currentSchool, "auth.start")
            val key = if (pluginAuth && pendingPasswordLogin) AcademicGatewayFactory.accountKey(currentSchool, username) else validationKey
            validationJob = lifecycleScope.launch {
                val result = runCatching { withContext(Dispatchers.IO) {
                    if (currentSchool.academicSystem == "auto" && !com.tyust.course.academic.plugin.AcademicProviderRegistry.hasBinding(currentSchool) && AcademicGatewayFactory.detect(currentSchool, key) == null)
                        throw IllegalStateException("无法识别教务系统，请在学校配置中手动选择")
                    if (!pluginAuth || !pendingPasswordLogin) AcademicGatewayFactory.importCookie(currentSchool, key, cookieStr, username = username)
                    (academicGateway?.completedPlugin?.takeIf { pluginAuth && pendingPasswordLogin }
                        ?: AcademicGatewayFactory.create(currentSchool, key)).validateSession()
                } }
                if (generation != academicValidationGeneration || selectedLoginSchool?.id != currentSchool.id) return@launch
                result.onSuccess { identity ->
                    if (identity.status != com.tyust.course.academic.AcademicStatus.SUCCESS) {
                        isLoading = false
                        errorMessage = identity.message.ifBlank { "未能验证登录，请在教务网页完成登录后重试" }
                        discardPendingPasswordLogin()
                    } else {
                        val id = identity.studentId.ifBlank { academicGateway?.studentId.orEmpty() }.ifBlank { username }
                        if (id.isBlank()) {
                            isLoading = false
                            errorMessage = "已登录，但未识别到学号，请使用密码登录或重新打开教务网页"
                            discardPendingPasswordLogin()
                        } else {
                            UserManager.getInstance().updateSchoolConfig(currentSchool)
                            finishAcademicLogin(currentSchool, cookieStr, identity.studentName.ifBlank { academicGateway?.studentName.orEmpty() }, id)
                        }
                    }
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    isLoading = false
                    errorMessage = it.message ?: "教务登录验证失败"
                    discardPendingPasswordLogin()
                }
            }
            return
        }
        // 1. Set Cookie
        CourseApiClient.getInstance().setCookie(currentSchool.baseUrl, cookieStr.trim(), validationKey)

        // 2. Validate Cookie
        validationCall = CourseApiClient.getInstance().validateCookie(currentSchool, validationKey, object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    if (generation != academicValidationGeneration || isFinishing || isDestroyed) return@runOnUiThread
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
                    if (generation != academicValidationGeneration || isFinishing || isDestroyed) return@runOnUiThread
                    isLoading = false
                    if (success) {
                        val userManager = UserManager.getInstance()
                        if (selectedLoginSchool?.id != currentSchool.id) {
                            errorMessage = "学校已切换，请重新登录"
                            discardPendingPasswordLogin()
                            return@runOnUiThread
                        }
                        val studentNameParsed = name ?: "同学"
                        val studentIdParsed = studentId ?: ""
                        
                        // 保存信息
                        bindingStudentName = studentNameParsed
                        bindingStudentId = studentIdParsed
                        
                        val maxStudents = com.tyust.course.activation.ActivationManager.getMaxStudents(this@LoginActivity)
                        val bindingCheck = com.tyust.course.manager.StudentLimitManager.checkCanUseStudent(
                            context = this@LoginActivity,
                            schoolId = currentSchool.id,
                            studentName = studentNameParsed,
                            studentId = studentIdParsed
                        )

                        if (!bindingCheck.allowed) {
                            errorMessage = bindingCheck.reason
                            discardPendingPasswordLogin()
                            return@runOnUiThread
                        }

                        if (bindingCheck.alreadyBound) {
                            proceedToMain(userManager, studentNameParsed, cookieStr)
                            return@runOnUiThread
                        }

                        bindingStudentName = studentNameParsed
                        bindingStudentId = studentIdParsed
                        bindingMaxStudents = maxStudents
                        bindingUsedNames = bindingCheck.usedNames
                        bindingUsedCount = bindingCheck.usedCount
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

    private fun finishAcademicLogin(currentSchool: SchoolConfig, cookieStr: String, parsedName: String, parsedId: String) {
        val studentNameParsed = parsedName.ifBlank { "同学" }
        val studentIdParsed = parsedId
        val userManager = UserManager.getInstance()
        bindingStudentName = studentNameParsed
        bindingStudentId = studentIdParsed
        val bindingCheck = com.tyust.course.manager.StudentLimitManager.checkCanUseStudent(
            context = this, schoolId = currentSchool.id,
            studentName = studentNameParsed, studentId = studentIdParsed
        )
        runOnUiThread {
            isLoading = false
            if (!bindingCheck.allowed) {
                errorMessage = bindingCheck.reason; discardPendingPasswordLogin(); return@runOnUiThread
            }
            if (bindingCheck.alreadyBound) {
                proceedToMain(userManager, studentNameParsed, cookieStr)
            } else {
                bindingStudentName = studentNameParsed; bindingStudentId = studentIdParsed
                bindingMaxStudents = com.tyust.course.activation.ActivationManager.getMaxStudents(this)
                bindingUsedNames = bindingCheck.usedNames; bindingUsedCount = bindingCheck.usedCount
                pendingCookie = cookieStr; showBindingDialog = true
            }
        }
    }
    
    /**
     * 登录成功后进入主界面
     */
    private fun proceedToMain(userManager: UserManager, studentName: String, cookieStr: String) {
        if (pendingPasswordLogin) {
            val passwordSchool = pendingPasswordSchool
            if (passwordSchool == null || selectedLoginSchool?.id != passwordSchool.id) {
                errorMessage = "学校已切换，请重新登录"
                discardPendingPasswordLogin()
                return
            }
        }
        userManager.currentSchool = selectedLoginSchool ?: return
        userManager.isLoggedIn = true
        userManager.studentId = bindingStudentId
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
        
        if (intent.getBooleanExtra(EXTRA_RETURN_TO_CALLER, false)) {
            setResult(RESULT_OK)
        } else {
            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
        }
        finish()
    }

    // ============ 密码登录 ============

    private fun handlePasswordLogin(username: String, password: String) {
        val school = selectedLoginSchool
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
                    if (selectedLoginSchool?.id != school.id) {
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

            override fun onWebLoginRequired(message: String) {
                runOnUiThread {
                    isLoading = false
                    errorMessage = message
                    UserManager.getInstance().updateSchoolConfig(school)
                    if ((activePasswordLoginGateway as? AcademicPasswordLoginGateway)?.webLogin == null) discardPendingPasswordLogin()
                    openWebView()
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
                    if (school == null || selectedLoginSchool?.id != school.id) {
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

    private fun clearValidationSessions() {
        validationAccounts.forEach { (key, school) ->
            CourseApiClient.getInstance().clearCookies(key)
            AcademicGatewayFactory.invalidate(school, key)
        }
        validationAccounts.clear()
    }

    override fun onDestroy() {
        academicValidationGeneration++
        validationCall?.cancel()
        validationJob?.cancel()
        clearValidationSessions()
        discardPendingPasswordLogin()
        super.onDestroy()
    }
}

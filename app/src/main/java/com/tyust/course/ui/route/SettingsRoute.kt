package com.tyust.course.ui.route

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemConfirmDialog
import com.tyust.course.ui.system.SystemSecondaryButton
import com.tyust.course.ui.system.SystemPrimaryButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.tyust.course.LoginActivity
import com.tyust.course.login.PasswordLoginCallback
import com.tyust.course.login.PasswordLoginManager
import com.tyust.course.manager.UserManager
import com.tyust.course.network.CourseApiClient
import com.tyust.course.ui.screen.SettingsScreen
import com.tyust.course.update.UpdateManager
import com.tyust.course.update.UpdateDialog
import com.tyust.course.activation.ActivationManager
import com.tyust.course.manager.StudentLimitManager
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.LinearProgressIndicator
import com.tyust.course.ui.system.SystemDivider
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import com.tyust.course.ui.theme.NeuPrimary
import com.tyust.course.ui.theme.Neutral500
import com.tyust.course.ui.theme.SemanticSuccess
import com.tyust.course.ui.theme.SemanticWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onAccountChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    
    var studentName by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }
    var schoolName by remember { mutableStateOf("") }
    
    // UI States
    var showSchoolDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showCreditsDialog by remember { mutableStateOf(false) }
    var showQuotaDialog by remember { mutableStateOf(false) }
    
    // Quota States
    var isSuper by remember { mutableStateOf(false) }
    var quotaInfo by remember { mutableStateOf("") }
    var quotaUsedCount by remember { mutableIntStateOf(0) }
    var quotaMaxCount by remember { mutableIntStateOf(0) }
    var quotaBoundNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var quotaAccounts by remember { mutableStateOf<List<UserManager.AccountRecord>>(emptyList()) }
    var currentAccountKey by remember { mutableStateOf("") }
    var canRefreshCookie by remember { mutableStateOf(false) }
    var isRefreshingCookie by remember { mutableStateOf(false) }
    
    // Update States
    val updateManager = remember { UpdateManager.getInstance(context) }
    var updateInfo by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    val currentVersion = remember { updateManager.getCurrentVersionName() }


    fun refreshAccountUiState() {
        val userManager = UserManager.getInstance()
        val name = userManager.studentName
        val school = userManager.currentSchool

        studentName = name ?: "同学"
        deviceId = ActivationManager.getSavedDeviceId(context)
        schoolName = school?.name ?: "未选择"

        val maxStudents = ActivationManager.getMaxStudents(context)
        val usedNames = StudentLimitManager.getUsedStudentNames(context)
        val usedCount = StudentLimitManager.getUsedCount(context)
        isSuper = maxStudents <= 0
        quotaUsedCount = usedCount
        quotaMaxCount = maxStudents
        quotaBoundNames = usedNames.toList()
        quotaAccounts = userManager.accountsForCurrentSchool
        currentAccountKey = userManager.currentAccountKey
        canRefreshCookie = userManager.loginMode == "password"
        quotaInfo = if (isSuper) {
            "无限制"
        } else {
            "$usedCount / $maxStudents"
        }
    }

    LaunchedEffect(Unit) {
        refreshAccountUiState()
    }
    
    fun performLogout() {
        UserManager.getInstance().clearLoginState()
        val intent = Intent(context, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
        // If context is not activity, clean task might need validation but usually safe
    }
    
    fun checkForUpdate() {
        isCheckingUpdate = true
        Toast.makeText(context, "正在检查更新...", Toast.LENGTH_SHORT).show()
        
        updateManager.checkForUpdate { info ->
            isCheckingUpdate = false
            if (info != null) {
                updateInfo = info
                showUpdateDialog = true
            } else {
                Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    fun startDownload() {
        val info = updateInfo ?: return
        isDownloading = true
        downloadProgress = 0
        
        updateManager.downloadApk(
            downloadUrl = info.downloadUrl,
            onProgress = { progress ->
                downloadProgress = progress
            },
            onComplete = { file ->
                isDownloading = false
                if (file != null && file.exists()) {
                    updateManager.installApk(file)
                    showUpdateDialog = false
                } else {
                    Toast.makeText(context, "下载失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    fun refreshCookieManually() {
        val userManager = UserManager.getInstance()
        val school = userManager.currentSchool
        if (isRefreshingCookie) return
        if (school == null) {
            Toast.makeText(context, "请先选择学校", Toast.LENGTH_SHORT).show()
            return
        }
        if (userManager.loginMode != "password") {
            Toast.makeText(context, "仅密码登录账号支持手动更新 Cookie", Toast.LENGTH_SHORT).show()
            return
        }
        if (!userManager.canAutoRelogin()) {
            Toast.makeText(context, "当前会话未保存密码，请重新使用密码登录后再更新", Toast.LENGTH_LONG).show()
            return
        }

        val requestAccountKey = userManager.currentAccountStorageKey
        val requestSchoolId = school.id
        val requestUsername = userManager.username
        val requestPassword = userManager.sessionPassword
        isRefreshingCookie = true
        PasswordLoginManager().login(school, requestUsername, requestPassword, object : PasswordLoginCallback {
            private var hasNotifiedCancellation = false

            private fun isRequestCurrent(): Boolean {
                val currentSchool = userManager.currentSchool
                return userManager.currentAccountStorageKey == requestAccountKey &&
                    currentSchool?.id == requestSchoolId &&
                    userManager.username == requestUsername
            }

            private fun postToUi(block: () -> Unit) {
                android.os.Handler(android.os.Looper.getMainLooper()).post post@{
                    if (!isRequestCurrent()) {
                        isRefreshingCookie = false
                        if (!hasNotifiedCancellation) {
                            hasNotifiedCancellation = true
                            Toast.makeText(context, "账号已切换，本次 Cookie 更新已取消", Toast.LENGTH_SHORT).show()
                        }
                        return@post
                    }
                    block()
                }
            }

            override fun onSuccess(cookie: String) {
                postToUi {
                    userManager.savePasswordLogin(requestUsername, cookie, requestPassword)
                    userManager.refreshRuntimeForCurrentAccount()
                    isRefreshingCookie = false
                    refreshAccountUiState()
                    Toast.makeText(context, "Cookie 已更新", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onCaptchaRequired(imageBytes: ByteArray) {
                postToUi {
                    isRefreshingCookie = false
                    Toast.makeText(context, "更新 Cookie 需要验证码，请重新使用密码登录", Toast.LENGTH_LONG).show()
                }
            }

            override fun onCaptchaInvalid() {
                postToUi {
                    isRefreshingCookie = false
                    Toast.makeText(context, "验证码校验失败，请重新使用密码登录", Toast.LENGTH_LONG).show()
                }
            }

            override fun onInvalidCredentials() {
                postToUi {
                    isRefreshingCookie = false
                    Toast.makeText(context, "密码已失效，请重新登录", Toast.LENGTH_LONG).show()
                }
            }

            override fun onError(message: String) {
                postToUi {
                    isRefreshingCookie = false
                    Toast.makeText(context, "更新失败：$message", Toast.LENGTH_LONG).show()
                }
            }
        })
    }
    
    // Update Dialog
    if (showUpdateDialog && updateInfo != null) {
        UpdateDialog(
            updateInfo = updateInfo!!,
            currentVersion = currentVersion,
            onDismiss = { 
                showUpdateDialog = false 
                updateInfo = null
            },
            onUpdate = { startDownload() },
            downloadProgress = downloadProgress,
            isDownloading = isDownloading
        )
    }
    
    SettingsScreen(
        studentName = studentName,
        studentId = deviceId,
        schoolName = schoolName,
        currentVersion = currentVersion,
        onSchoolSelect = { showSchoolDialog = true },
        onCookieConfig = { performLogout() },
        onClearCache = { showClearCacheDialog = true },
        onCheckUpdate = { checkForUpdate() },
        onAbout = { showAboutDialog = true },
        onCredits = { showCreditsDialog = true },
        onLogout = { showLogoutDialog = true },
        onQuotaClick = { showQuotaDialog = true },
        onRefreshCookieClick = { refreshCookieManually() },
        onLogExport = { com.tyust.course.utils.LogUtils.exportLogs(context) },
        isSuper = isSuper,
        quotaInfo = quotaInfo,
        canRefreshCookie = canRefreshCookie,
        isRefreshingCookie = isRefreshingCookie
    )
    
    // Dialogs
    if (showLogoutDialog) {
        SimpleConfirmDialog(
            title = "退出登录",
            text = "确定要退出登录吗？",
            onConfirm = { 
                performLogout() 
                showLogoutDialog = false
            },
            onDismiss = { showLogoutDialog = false }
        )
    }
    
    if (showClearCacheDialog) {
        SimpleConfirmDialog(
            title = "清除缓存",
            text = "确定要清除所有本地缓存数据吗？",
            onConfirm = { 
                Toast.makeText(context, "缓存已清除", Toast.LENGTH_SHORT).show()
                showClearCacheDialog = false
            },
            onDismiss = { showClearCacheDialog = false }
        )
    }
    
    if (showAboutDialog) {
        SystemDialog(
            onDismissRequest = { showAboutDialog = false },
            title = {
                Text(
                    text = "更新历史",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                SystemPrimaryButton(
                    text = "关闭",
                    onClick = { showAboutDialog = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val updates = listOf(
                    "2026-06-08" to "修复课程筛选条件无法显示问题：改为按自主选课页面真实运行来源获取动态筛选项，支持年级、学院、专业、开课学院、课程类别、课程性质、课程归属、教学模式、上课星期/节次、教学班、是否重修和有无余量等筛选条件；筛选请求参数与网页端保持一致。",
                    "2026-06-08" to "完善多账号数据隔离：课程缓存、课表、已选课程、成绩、设置页 Cookie 更新、Cookie 过期广播、抢课队列、抢课服务日志与服务广播均按账号分槽；切换账号后页面状态自动重置，旧账号请求不会写入当前账号界面。",
                    "2026-06-07" to "修复 vivo/oppo 等设备上抢课、成绩、设置页面闪退问题（Backdrop 液态玻璃 GPU 兼容性）；修复复杂周次格式（如\"1-4周,6-14周(双),15-16周\"）无法正确识别的 Bug；新增缺失的 ProGuard 规则文件，修复 Theme 安全转型。",
                    "2026-06-05" to "新增全局 Cookie 有效性定期检查（CookieWatchdog），提升后台长效稳定性；优化接口响应拦截，捕获 JSON 响应中的失效状态并自动唤起登录提示，显著增强会话失效处理的鲁棒性。",
                    "2026-06-04" to "修复平时成绩详情只展示一项的Bug；成绩导出支持导出为 UTF-8 BOM CSV 数据单；优化登录密码输入下的统一认证平台温馨提示；引入防误触式 GitHub Star 引导弹窗，支持最多3次展示不同阶段求赞文案；将原本的关于界面重构为更新历史卡片与开源致谢面板。",
                    "2026-06-03" to "清理内部文档与更新配置。",
                    "2026-06-02" to "优化滚动体验，增加页面底部内边距，防止底部导航栏遮挡内容；重构 README 引入 iOS 新拟态玻璃 UI 截图。",
                    "2026-06-01" to "修复底部导航栏部分情况下点击失效以及液态效果裁切问题，优化过渡动画。",
                    "2026-05-31" to "引入液态玻璃视觉效果，系统 UI 重构，发布 1.0.54 版本。",
                    "2026-05-26" to "优化已选列表卡片与切换开关，引入全新五彩新拟态主题与微交互动效；修复退课接口未带加密 ID 导致的问题；修复日历分享配置路径缺失导致的崩溃。",
                    "2026-05-25" to "升级新拟态玻璃化 UI 设计，优化课程表截断和星期对齐问题，优化数据加载动画。",
                    "2026-04-18" to "成绩查询 UI 深度优化，增强安全防护（引入限流与安全指纹检测机制）。",
                    "2026-04-09" to "深度 UI/UX 重构，渲染极简系统工具风，优化性能与细节体验。"
                )

                updates.forEach { (date, desc) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(com.tyust.course.ui.theme.NeuPrimary, CircleShape)
                            )
                            Text(
                                text = date,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp),
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }

    if (showCreditsDialog) {
        SystemDialog(
            onDismissRequest = { showCreditsDialog = false },
            title = {
                Text(
                    text = "致谢与关于",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                SystemPrimaryButton(
                    text = "我知道了",
                    onClick = { showCreditsDialog = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "特别致谢",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "本应用基于多项优秀的开源技术构建，衷心感谢以下开源项目及社区的支持：\n" +
                            "• Jetpack Compose & Kotlin\n" +
                            "• OkHttp3 & Gson\n" +
                            "• Jsoup (HTML 解析库)\n" +
                            "• Material Design 3\n" +
                            "• AndroidLiquidGlass 动效库",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )

                com.tyust.course.ui.system.SystemDivider()

                Text(
                    text = "关于作者",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "作者：znjhahaha\n" +
                            "GitHub 仓库：https://github.com/znjhahaha/zhengfang-apk",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )

                com.tyust.course.ui.system.SystemDivider()

                Text(
                    text = "本软件为开源免费项目，仅供个人学习与技术交流使用，严禁用于任何商业目的与倒卖。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    lineHeight = 16.sp
                )
            }
        }
    }
    
    if (showQuotaDialog) {
        QuotaStatusDialog(
            isSuper = isSuper,
            usedCount = quotaUsedCount,
            maxCount = quotaMaxCount,
            boundNames = quotaBoundNames,
            accounts = quotaAccounts,
            currentAccountKey = currentAccountKey,
            onSwitchAccount = { accountKey ->
                if (accountKey == currentAccountKey) return@QuotaStatusDialog
                val switched = UserManager.getInstance().switchToAccount(accountKey)
                if (switched) {
                    refreshAccountUiState()
                    showQuotaDialog = false
                    onAccountChanged()
                    Toast.makeText(context, "已切换账号", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "账号切换失败，请重新登录", Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { showQuotaDialog = false }
        )
    }


    
    if (showSchoolDialog) {
        var animateTrigger by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { animateTrigger = true }

        fun dismiss() {
            animateTrigger = false
        }

        if (!animateTrigger) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(300)
                showSchoolDialog = false
            }
        }

        SystemDialog(
            onDismissRequest = { dismiss() },
            title = {
                Text(
                    text = "选择学校",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            dismissButton = {
                SystemSecondaryButton(
                    text = "取消",
                    onClick = { dismiss() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            val schools = remember { UserManager.getInstance().supportedSchools }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp) // 限制最大高度，防止学校列表过多时把 Dialog 挤出屏幕外
            ) {
                items(schools) { school ->
                    Text(
                        text = school.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                UserManager.getInstance().clearLoginState()
                                UserManager.getInstance().currentSchool = school
                                Toast.makeText(context, "已切换到: ${school.name}", Toast.LENGTH_SHORT).show()
                                performLogout()
                                dismiss()
                            }
                            .padding(vertical = 14.dp, horizontal = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun QuotaStatusDialog(
    isSuper: Boolean,
    usedCount: Int,
    maxCount: Int,
    boundNames: List<String>,
    accounts: List<UserManager.AccountRecord>,
    currentAccountKey: String,
    onSwitchAccount: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val safeMax = maxCount.coerceAtLeast(0)
    val safeUsed = usedCount.coerceAtLeast(0)
    val usageRatio = if (!isSuper && safeMax > 0) {
        (safeUsed.toFloat() / safeMax.toFloat()).coerceIn(0f, 1f)
    } else {
        1f
    }
    val statusText = if (isSuper) "超级用户" else "普通用户"
    val quotaText = if (isSuper) "无限制" else "$safeUsed / $safeMax"

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "当前账号配额",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "设备绑定与名额使用情况",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "知道了",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuotaInfoRow(label = "身份", value = statusText)
                    QuotaInfoRow(label = "配额", value = quotaText)
                    if (!isSuper) {
                        LinearProgressIndicator(
                            progress = { usageRatio },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = if (usageRatio >= 1f) SemanticWarning else NeuPrimary,
                            trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            SystemDivider(alpha = 0.5f)

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "已绑定账号",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (boundNames.isEmpty()) {
                    Text(
                        text = "当前设备尚未绑定账号。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        boundNames.forEachIndexed { index, name ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        modifier = Modifier.size(24.dp),
                                        color = NeuPrimary.copy(alpha = 0.12f),
                                        shape = CircleShape
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "${index + 1}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = NeuPrimary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (accounts.isNotEmpty()) {
                SystemDivider(alpha = 0.5f)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "切换账号",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    accounts.forEach { account ->
                        val isCurrent = account.key == currentAccountKey
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isCurrent) { onSwitchAccount(account.key) },
                            color = if (isCurrent) NeuPrimary.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = account.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${account.accountIdText} · ${if (account.loginMode == "password") "密码登录" else "Cookie 登录"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                SystemStatusBadge(
                                    text = if (isCurrent) "当前" else "切换",
                                    tone = if (isCurrent) SystemTone.Info else SystemTone.Neutral
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = "说明：同一设备仅允许绑定同一学校账号，普通配额最多 3 个；切换账号会同步切换 Cookie 与本地账号上下文。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun QuotaInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun SimpleConfirmDialog(
    title: String, 
    text: String, 
    onConfirm: () -> Unit, 
    onDismiss: () -> Unit,
    confirmText: String = "确定",
    showCancel: Boolean = true
) {
    SystemConfirmDialog(
        title = title,
        text = text,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmText = confirmText,
        showCancel = showCancel
    )
}



package com.tyust.course.ui.route

import com.tyust.course.ui.system.GlassToaster
import android.content.Intent
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
import com.tyust.course.ui.system.BindingManagementContent
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
import com.tyust.course.login.PasswordLoginGatewayFactory
import com.tyust.course.manager.AppearanceSettingsManager
import com.tyust.course.academic.plugin.PluginPages
import com.tyust.course.manager.UserManager
import com.tyust.course.network.CourseApiClient
import com.tyust.course.ui.screen.SettingsScreen
import com.tyust.course.ui.screen.SchoolAdaptationFlow
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
import androidx.compose.material.icons.filled.Check
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
    onAccountChanged: () -> Unit = {},
    onSurveyCenter: () -> Unit = {},
    surveyUnreadCount: Int = 0,
    onAnnouncementCenter: () -> Unit = {},
    announcementUnreadCount: Int = 0
) {
    val context = LocalContext.current
    val isDemoMode = remember { UserManager.getInstance().isDemoMode }
    
    var studentName by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }
    var schoolName by remember { mutableStateOf("") }
    
    // UI States
    var showSchoolDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showAcademicSupport by remember { mutableStateOf(false) }
    var showCreditsDialog by remember { mutableStateOf(false) }
    var showQuotaDialog by remember { mutableStateOf(false) }
    var showAccountManagerDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var pendingPasswordDelete by remember { mutableStateOf<UserManager.AccountRecord?>(null) }
    var pendingAccountDelete by remember { mutableStateOf<UserManager.AccountRecord?>(null) }
    var showSchoolAdaptation by remember { mutableStateOf(false) }
    var showWallpaperDialog by remember { mutableStateOf(false) }
    var showThemeDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showStartupPageDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showClassQuietDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val classQuietManager = remember(context) { com.tyust.course.schedule.ClassQuietManager.get(context) }
    val pageRevision by PluginPages.revision.collectAsState()
    val startupPages = remember(pageRevision) { PluginPages.registry.pages() }
    val startupRoute = remember(pageRevision) { PluginPages.registry.preferredStartup() }
    val startupPageName = startupPages.firstOrNull { it.id == startupRoute }?.title ?: "页面暂不可用，暂用课表"
    val currentWallpaperName = com.tyust.course.manager.AppearanceSettingsManager.currentWallpaperName
    
    // Quota States
    var isSuper by remember { mutableStateOf(false) }
    var quotaInfo by remember { mutableStateOf("") }
    var quotaUsedCount by remember { mutableIntStateOf(0) }
    var quotaMaxCount by remember { mutableIntStateOf(0) }
    var quotaBoundNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var quotaAccounts by remember { mutableStateOf<List<UserManager.AccountRecord>>(emptyList()) }
    // 账号管理走全量列表（跨学校）：这里是"管理"，不该被当前学校过滤掉
    var allAccounts by remember { mutableStateOf<List<UserManager.AccountRecord>>(emptyList()) }
    var accountsWithPassword by remember { mutableStateOf<Set<String>>(emptySet()) }
    var currentAccountKey by remember { mutableStateOf("") }
    var canRefreshCookie by remember { mutableStateOf(false) }
    val session by UserManager.getInstance().sessionState.state.collectAsState()
    var cookieUpdateFeedback by remember {
        mutableStateOf<Pair<com.tyust.course.manager.SessionToken, com.tyust.course.ui.system.SymbolResult>?>(null)
    }
    val cookieUpdateResult = cookieUpdateFeedback?.takeIf { it.first == session.token }?.second
        ?: com.tyust.course.ui.system.SymbolResult.None
    val recovery by com.tyust.course.utils.SessionRenewer.state.collectAsState()
    val isRefreshingCookie = recovery.token == session.token &&
        recovery.phase == com.tyust.course.utils.RecoveryPhase.Restoring
    val relogin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    
    // Update States
    val updateManager = remember { UpdateManager.getInstance(context) }
    val updateSnapshot by updateManager.state.collectAsState()
    val isCheckingUpdate = updateSnapshot.check == UpdateManager.Check.CHECKING
    val currentVersion = remember { updateManager.getCurrentVersionName() }


    fun refreshAccountUiState() {
        val userManager = UserManager.getInstance()
        val name = userManager.studentName
        val school = userManager.currentSchool

        if (isDemoMode) {
            studentName = name ?: "演示用户"
            deviceId = "LOCAL-DEMO"
            schoolName = school?.name ?: "正方演示大学（演示数据）"
            isSuper = true
            quotaUsedCount = 0
            quotaMaxCount = 0
            quotaBoundNames = emptyList()
            quotaAccounts = emptyList()
            allAccounts = emptyList()
            accountsWithPassword = emptySet()
            currentAccountKey = userManager.currentAccountKey
            canRefreshCookie = false
            quotaInfo = "本地演示"
            return
        }

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
        allAccounts = userManager.savedAccounts
        accountsWithPassword = allAccounts
            .filter { userManager.hasSavedPassword(it.key) }
            .map { it.key }
            .toSet()
        currentAccountKey = userManager.currentAccountKey
        canRefreshCookie = userManager.loginMode == "password"
        quotaInfo = if (isSuper) {
            "无限制"
        } else {
            "$usedCount / $maxStudents"
        }
    }

    LaunchedEffect(session.token) {
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

    fun switchAccount(accountKey: String, onSwitched: () -> Unit) {
        if (accountKey == currentAccountKey) return
        if (UserManager.getInstance().switchToAccount(accountKey)) {
            refreshAccountUiState()
            onSwitched()
            onAccountChanged()
            GlassToaster.show("已切换账号")
        } else {
            val record = UserManager.getInstance().savedAccounts.firstOrNull { it.key == accountKey }
            val check = record?.let { StudentLimitManager.checkCanUseStudent(context, it.schoolId, it.studentName.orEmpty(), it.studentId.orEmpty()) }
            GlassToaster.show(if (check != null && !check.allowed) check.reason + "，请在配额管理中解绑" else "账号切换失败，请重新登录")
        }
    }

    /** 只删密码：账号还在，但不再自动续期，下次失效需要手动登录。 */
    fun deleteAccountPassword(record: UserManager.AccountRecord) {
        UserManager.getInstance().deletePassword(record.key)
        refreshAccountUiState()
        GlassToaster.show("已删除该账号保存的密码")
    }

    /** 彻底删号：账号记录 + 已存密码 + 运行期 Cookie + 本地课程缓存。 */
    fun deleteAccountEntirely(record: UserManager.AccountRecord) {
        val userManager = UserManager.getInstance()
        val isCurrent = record.key == userManager.currentAccountKey
        val storageKey = userManager.deleteAccount(record.key)
        if (storageKey.isNotEmpty()) {
            com.tyust.course.manager.CourseCacheManager.clearAccountCache(context, storageKey)
        }
        refreshAccountUiState()
        if (isCurrent) {
            // 当前账号被删掉，会话已经没有依据了，直接回登录页
            GlassToaster.show("账号已删除，请重新登录")
            performLogout()
        } else {
            GlassToaster.show("账号已删除")
        }
    }
    
    fun checkForUpdate() {
        if (isDemoMode) { GlassToaster.show("本地演示模式不执行更新检查"); return }
        updateManager.checkForUpdate(manual = true)
    }

    fun refreshCookieManually() {
        if (isDemoMode || isRefreshingCookie) return
        cookieUpdateFeedback = null
        val user = UserManager.getInstance()
        val expected = user.sessionState.token
        com.tyust.course.utils.SessionRenewer.request(expected, manual = true) { result ->
            when (result) {
                is com.tyust.course.utils.SessionRecoveryResult.Recovered -> {
                    if (user.sessionState.isCurrent(result.token)) {
                        cookieUpdateFeedback = result.token to com.tyust.course.ui.system.SymbolResult.Success
                        refreshAccountUiState()
                        GlassToaster.show("登录状态已更新")
                    }
                }
                is com.tyust.course.utils.SessionRecoveryResult.NeedsLogin -> {
                    if (user.sessionState.isCurrent(expected)) {
                        cookieUpdateFeedback = expected to com.tyust.course.ui.system.SymbolResult.Failure
                        GlassToaster.show(
                        result.message.ifBlank { when (result.reason) {
                            com.tyust.course.utils.RecoveryFailure.Network -> "暂时无法连接，请稍后重试"
                            com.tyust.course.utils.RecoveryFailure.Login -> "登录处理失败，请重试或使用网页登录"
                            com.tyust.course.utils.RecoveryFailure.Storage -> "保存失败，请重试"
                            else -> "需要重新登录以更新登录状态"
                        } }
                        )
                    }
                }
                com.tyust.course.utils.SessionRecoveryResult.Superseded -> Unit
            }
        }
    }
    
    if (showSchoolAdaptation) {
        com.tyust.course.ui.system.GlassSubpage(onDismiss = { showSchoolAdaptation = false }) { close ->
            SchoolAdaptationFlow(onNavigateBack = close)
        }
    }

    val usagePreferences by com.tyust.course.usage.UsageStatsManager.preferences.collectAsState()
    SettingsScreen(
        studentName = studentName,
        studentId = deviceId,
        schoolName = schoolName,
        currentVersion = currentVersion,
        onSchoolSelect = {
            if (isDemoMode) GlassToaster.show("演示学校固定为本地数据源") else showSchoolDialog = true
        },
        onCookieConfig = {
            relogin.launch(Intent(context, LoginActivity::class.java).apply {
                putExtra("force_relogin", true)
                putExtra(LoginActivity.EXTRA_RETURN_TO_CALLER, true)
            })
        },
        onAccountManage = {
            if (isDemoMode) GlassToaster.show("本地演示模式不读取真实账号") else showAccountManagerDialog = true
        },
        savedAccountCount = allAccounts.size,
        onClearCache = { showClearCacheDialog = true },
        onCheckUpdate = { checkForUpdate() },
        onAbout = { showAboutDialog = true },
        onCredits = { showCreditsDialog = true },
        isDemoMode = isDemoMode,
        onLogout = { if (isDemoMode) performLogout() else showLogoutDialog = true },
        onQuotaClick = { showQuotaDialog = true },
        onRefreshCookieClick = { refreshCookieManually() },
        onLogExport = { com.tyust.course.utils.LogUtils.exportLogs(context) },
        onErrorReport = {
            val report = com.tyust.course.diagnostics.AppDiagnostics.latest(context)
            if (report != null) com.tyust.course.diagnostics.AppDiagnostics.showReport(context, report)
            else android.widget.Toast.makeText(context, "暂未记录错误", android.widget.Toast.LENGTH_SHORT).show()
        },
        onSchoolAdaptation = {
            if (isDemoMode) GlassToaster.show("本地演示模式不连接学校适配服务") else showSchoolAdaptation = true
        },
        onSurveyCenter = onSurveyCenter,
        surveyUnreadCount = surveyUnreadCount,
        onAnnouncementCenter = onAnnouncementCenter,
        announcementUnreadCount = announcementUnreadCount,
        onWallpaperSelect = { showWallpaperDialog = true },
        wallpaperName = currentWallpaperName,
        themeName = AppearanceSettingsManager.themeMode.label,
        onThemeSelect = { showThemeDialog = true },
        startupPageName = startupPageName,
        onStartupPageSelect = { showStartupPageDialog = true },
        glassEffectEnabled = AppearanceSettingsManager.glassEffectEnabled,
        onGlassEffectChange = { AppearanceSettingsManager.updateGlassEffect(it) },
        navBarAutoCollapseEnabled = AppearanceSettingsManager.navBarAutoCollapseEnabled,
        onNavBarAutoCollapseChange = { AppearanceSettingsManager.updateNavBarAutoCollapse(it) },
        usageEnabled = usagePreferences.enabled,
        onUsageEnabledChange = com.tyust.course.usage.UsageStatsManager::setEnabled,
        classQuietSummary = classQuietManager.status.summary,
        onClassQuietSettings = { showClassQuietDialog = true },
        isSuper = isSuper,
        quotaInfo = quotaInfo,
        canRefreshCookie = canRefreshCookie,
        isRefreshingCookie = isRefreshingCookie,
        academicSystemName = com.tyust.course.academic.AcademicCapabilities.name(UserManager.getInstance().currentSchool?.academicSystem),
        onAcademicSupport = { showAcademicSupport = true },
        onAcademicPlugins = { context.startActivity(Intent(context, com.tyust.course.academic.plugin.PluginCenterActivity::class.java)) },
        onMapServices = { context.startActivity(Intent(context, com.tyust.course.academic.plugin.PluginMapSettingsActivity::class.java)) },
        onCampusServices = { context.startActivity(Intent(context, com.tyust.course.academic.plugin.CampusServiceCenterActivity::class.java)) },
        onQuickFeedback = { com.tyust.course.academic.plugin.PluginFeedback.open(context) }
    )
    if (showAcademicSupport) com.tyust.course.ui.screen.AcademicSupportDialog(
        UserManager.getInstance().currentSchool?.academicSystem, onDismiss = { showAcademicSupport = false })
    
    if (showThemeDialog) {
        com.tyust.course.ui.screen.AppThemeSettingsDialog { showThemeDialog = false }
    }
    if (showClassQuietDialog) {
        com.tyust.course.ui.screen.ClassQuietSettingsDialog { showClassQuietDialog = false }
    }
    if (showStartupPageDialog) {
        com.tyust.course.ui.screen.StartupPageSettingsDialog(
            pages = startupPages,
            selectedRoute = startupRoute,
            onPageChange = {
                PluginPages.registry.setStartup(it)
            },
            onDismiss = { showStartupPageDialog = false }
        )
    }
    if (showWallpaperDialog) {
        com.tyust.course.ui.screen.WallpaperSettingsDialog(
            onDismiss = { showWallpaperDialog = false }
        )
    }

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
                GlassToaster.show("缓存已清除")
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
                    "2026-11-02 · 1.0.131" to "新增今日校园服务所需的独立认证、学校网络授权及提交核验宿主能力，同步 SDK 3.9.0 / API 3；学习通与今日校园服务插件需加入 QQ 群 1074017033 独立下载。今日校园支持任务与要求读取、本人真实材料确认办理，异地办理以学校规则为准；无人值守自动签到和自动销假尚未实现，真实提交尚未验收。",
                    "2026-10-20 · 1.0.127" to "新增上课自动静音，入口在设置的课表自动化中，默认关闭；按本学期课表上下课切换声音，支持单双周、自定义课程和调停课，保留课中手动调整；修复脚本浏览器章节题页和 iframe 页面函数读取，以及插件长日志滚动。SDK 3.7.2 / API 3 兼容，学习通插件继续独立本地交付。",
                    "2026-10-15 · 1.0.126" to "修复河北传媒统一认证回调与教务会话复用，青果通用协议支持已配置的 CAS AES 账号密码登录；同步 SDK 3.7.1 认证跳转及网络用途规则。学习通插件独立本地交付，不随 App 上架。",
                    "2026-10-14 · 1.0.125" to "修复辽宁工程技术大学输入账号密码后仍需在学校网页重复填写的问题，保留校方加密和滑块验证，完成认证后核对会话并返回 App；完善动态页面加载、重复提交及手动修改输入的处理。",
                    "2026-10-14 · 1.0.124" to "新增青果教务和湖南汽车工程职业大学适配，支持统一认证账号密码登录、课表、成绩、考试及选课查询；新增辽宁工程技术大学 WebVPN 教务入口，完善课表、成绩和考试读取；修复安装学校插件并选择使用后，登录仍走旧内置适配的问题，选择后保留原学校与账号记录。两校选退课以学校实际开放情况为准。",
                    "2026-10-14" to "新增原生插件后台作业队列，启动一次即固定本轮作业与自动操作范围，离开插件页后继续运行，并可随时从插件页或常驻通知停止；修复多页面存在同名按钮时后台操作可能选错页面的问题，自动操作绑定自己启动的作业及实际文档。另行安装学习通 0.2.8 r8 插件后，作业页新增独立设置，支持自动提交与自动下一份，默认关闭并按账号保存，仅在平台确认已提交或保存成功后继续；作业缺少答案、保存失败、出现额外提示或提交结果不明时会暂停并保留结果与恢复入口，每份作业的保存与提交不会自动重复发送，同时新增本轮处理记录。",
                    "2026-10-13" to "新增多套作息时间预设，可在当前学期应用与撤回；修复统一登录连续跳转后无法进入教务；修复后台课程提醒可能丢失，覆盖提前十分钟提醒，冷启动与系统恢复会重新登记，避免未送达提醒被下周覆盖，登录过期后仍可使用本地课表提醒；签到选点接入高德原生地图，支持缩放、点选与坐标换算，手动选点不消耗搜索额度；新增设置中的地图服务，可接入自己的高德服务密钥，仅供本人设备搜索，失效或额度耗尽时明确提示；修复国内地址解析过度截断与校区、小区、航站楼名称丢失；修复插件多页面与后台任务并发时脚本引擎崩溃、页面持续加载和插件进程失败，并修复学习进度已保存却显示任务出错。",
                    "2026-10-12" to "新增公告中心，可在设置中查看未读公告、搜索、分类筛选和全部标为已读，重要公告可弹窗提醒并支持图文、封面与多个跳转按钮；课表支持按账号设置每周起始日，周次、列顺序、提醒和日历导出保持一致；新增早、晚自习时间段并按账号和学期保存、可独立提醒；新增桌面课程倒计时小组件并补齐各类小组件预览，修复部分桌面无法列出小组件；修复脚本浏览器在收起页面、按主页或切换应用后暂停定时与网络请求，修复关闭浏览器时登录状态未完成保存。",
                    "2026-10-11" to "修复校园服务作为底部标签页时标题贴住状态栏的问题，滚动内容从状态栏安全区域下方开始显示；原生插件页面上下增加少量留白，改善学习通等插件内容贴近页面边缘的显示，同时保留导航、键盘避让与底栏占位；完善地点搜索服务的中文结果筛选与备用数据源，太原武宿等地点可返回相关结果供选择。",
                    "2026-10-10" to "新增树维 EAMS 与超星智慧教务，内置电子科技大学成都学院、武汉职业技术大学配置，沿用现有登录、课表、成绩、考试与选课页面，支持图片验证码、统一认证续接和培养方案查询；完善原生插件多账号隔离、脚本管理与前台任务，补齐扫码、拍照、位置选择和图片验证，手势签到改为九宫格滑动绘制并可清空重画，修复活动缺经纬度被当作零坐标与部分设备打开地图崩溃；恢复登录首页布局、学校分组、调课与节假日补停课、单门课提醒覆盖、小组件背景与透明度设置，地图与地点搜索改用自有服务不再依赖加速工具；脚本更新优先走自有域名，原站不可用时才回退；安装包按设备架构提供 64 位、32 位与通用兼容包。",
                    "2026-10-07" to "修复网页插件打开输入法时键盘上方出现大块空白、网页内容被遮挡，网页底部贴合键盘上沿；修复网页加载完成后输入框偶尔点不出输入法，键盘开关和页面刷新不再重载网页或抢走焦点；启动首屏统一为一份设置，设置页与服务中心显示并修改同一选择，旧版本留下的服务中心启动设置不再覆盖设置页选择的课表，冷启动进入所选首屏，后台切回与旋转屏幕保留当前页面；成绩与插件中心的折射和色散改为先合成文字与局部底图再统一绘制，修复透明文字层偏黑与原色碎片，并修复轻微拖动分段滑块时反复刷新字形导致的闪烁，拖动期间保持字重，松手后停止无效刷新。",
                    "2026-10-06" to "新增通用网页纯浏览模式，开发者配置网址即可接入学习、工具、社区与校园网站，无需逆向接口；网页统一紧凑工具栏、首次加载过渡与导航历史恢复，网站账号与教务账号相互隔离，服务中心改用紧凑卡片并可固定导航；成绩与插件中心改用真实文字排版驱动折射，修复分段滑块的蓝色叠层与背景采样，恢复文字放大效果；插件首页精简为列表与条件摘要，分类和学校移入筛选面板；有缓存课表时自动刷新遇到登录过期不再打扰，手动同步提供重新登录入口。",
                    "2026-10-05" to "插件中心的大标题、分类、学校筛选和搜索可随列表滚动收起，收起后保留发现与已安装入口及快捷操作；新增共用条件的浮动搜索与筛选面板，保留列表位置并适配大字体和短窗口；插件开发工具新增本地组件预览；修正筛选浮层开合时的图层采样异常，补齐选中滑块的背景回退与静止选中效果；更多菜单改用课表样式，顶部改为悬浮玻璃分层，类型与学校筛选改为玻璃胶囊。",
                    "2026-10-04" to "首次进入课程、课表和成绩页面先显示静态骨架，数据就绪后淡入，缓存、空结果与错误分别处理；成绩分段栏文字独立绘制，修复滚动时标签漂移、消失；按账号、学校与成绩提供者记住成绩学期与分段，重启或重新登录后恢复原选择，学期目录为空或加载失败不会跳回当前学期；弹窗改为更清晰的轻透磨砂面板，统一主次按钮与左对齐正文，长文本确认可滚动查看；下拉菜单恢复原有轻透玻璃外观，并保留弹窗清晰度调整。",
                    "2026-10-02" to "选课列表支持手动加载更多，避免一次加载全部分页拖慢首屏；可见课程渐进加载教学班与名额，支持展开全部、收起全部和失败重试；修复教学班占位数量与未知名额的显示，减少重复查询轮次；登录页与设置页新增解绑非当前账号，解绑后保留已保存账号、凭据与课表缓存。",
                    "2026-10-01" to "更新检查增加多个来源，网络失败时明确提示，不再误报已是最新版本；下载支持后台继续、暂停、断点恢复和失败切换线路，普通更新可稍后处理，下载完成后先校验文件与官方签名再提供安装；修复检查更新出现反向版本箭头和旧版日志的问题，无可用更新时显示当前版本与结果，仅可安装的新版本展示日志与下载线路；首次进入课程等页面先显示加载状态并在后台准备，等待期间可切换其他页面；插件管理、安装卸载、授权、学校管理和更新弹窗的操作按钮恢复胶囊外形，大字体下间距更舒展。",
                    "2026-09-30" to "已记住的校园服务登录在重启后自动恢复，无需反复登录，登录失效时保留授权并提示重登；学校登录按准确站点一次授权，减少重复确认；修复部分机型插件进程启动异常并完善启动诊断，课表刷新或节次补充失败时保留已有数据；成绩新增内置常用学期并合并学校列表，可隐藏未来学年，修复学期下拉文字发虚消失、收起缩进与关闭液态玻璃时的顶栏遮罩；恢复网页选课的筛选、多选、应用与清空，选课未开放时明确提示并禁用；设置首页恢复退出预览与退出登录，插件分类与账号设置更精简并支持大屏；课程详情可显示课程号、学分、教学班与授课内容，课表每天节数随有效作息同步。",
                    "2026-09-29" to "修复设置、抢课、问卷、课程编辑等页面顶部文字与按钮在未滚动时消失，统一覆盖共用顶栏页面并保留折叠动画与实时玻璃效果；修复部分学校成绩页学期列表为空或缺少历史学期，改为合并课表与成绩查询页的学期选项，选择器增加加载、失败提示与刷新重试；插件授权支持一键允许并记住，普通关闭和同账号重登可复用授权，已读更新可并入教务登录授权，并加强凭证隔离、请求范围检查、数据外传授权与撤销管理；修复通知启动误用空图标播放动画，减少页面切换的重复绘制。",
                    "2026-09-28" to "修复原生插件打开即提示“调用已取消”，恢复页面、按钮、后台任务与命令调用；同校插件可经授权共用教务登录令牌，覆盖各类教务入口，升级后重新登录一次即可；完善选课能力判断、手动队列顺序、成绩学期切换与登录失效恢复，专用单点登录按学校入口使用；扩大临时教务缓存并放宽登录处理时限，减少大课表与登录解析的容量和超时问题；修复壁纸下玻璃控件可读性、导航切换显示与课表日期对齐。",
                    "2026-09-26" to "登录页新增学校搜索，整合已保存学校与插件目录，选择适配、确认权限后即可使用；同校不同作者的插件可经授权复用已有教务登录，支持查看与撤销授权；插件页面、服务账号与本地数据按学校和账号隔离，升级先暂存、新增权限确认后生效；升级沿用原插件编号并集中展示版本历史，插件详情新增当前版本公开源码入口；可一次设置整学期课前提醒；修复小尺寸桌面组件的教室截断，日期与星期保持对齐；完善部分正方学校登录回调兼容并识别选课关闭提示。",
                    "2026-09-24" to "课表与三种桌面组件完整显示课程名和教师，组件随尺寸调整字号并保留时间与教室；修复课表滚动到底部回跳、翻页覆盖当前日期、日期标题缩进与跨月对齐、选中星期残留旧日期，并优先显示缓存后在后台更新；修复部分设备插件进程启动或绑定失败；设置新增底部导航栏自动收起开关；登录页新增学校插件入口，无需登录即可安装签名插件并选择本校适配；新增学校统一认证支持，内置湖北汽车工业学院与山东石油化工学院适配；修复部分学校网址识别异常。",
                    "2026-09-23" to "插件中心改为“本校／已安装”视图，安装后按学校域名、端口与路径匹配适配，支持手动选择、冲突处理与本校停用；新增通用原生插件框架，可组合页面、表单、列表与绘图，并开放受限文件、隔离存储、凭据与会话、设备交互、任务、通知与导航能力；问卷改用弹窗合并提醒；重设计单课、双课与今日时间轴三种桌面组件，课程状态标记不再改变课表行高；修复周课表滚动到底部回跳，各分页独立保存滚动位置；学校网址识别改为后台执行并提供可复制的错误报告与崩溃查看。",
                    "2026-09-22" to "新增插件中心，支持下载学校适配、导入插件、更新回退与开发预览；新增独立校园服务页面，服务账号与教务账号分开管理，支持模块排序、隐藏与恢复；开放表格、时间线、柱状图、网格入口、多行文本与开关等原生扩展，可在课程首页、课表与成绩页提供入口；扫码、文件选择、通知与日历操作需逐次授权；新增单课、双课程与今日时间轴桌面组件；改善网页登录布局、教务地址识别与已有登录流程；修复正方接口路径与参数处理、教学班匹配与队列兼容，队列等待重试时继续轮询其他课程。",
                    "2026-09-16" to "新增问卷中心，可在应用内查看、搜索并填写问卷，支持收藏、浏览历史与手动填写标记，按账号保存在本机，重要问卷提供可关闭的轻提醒，离线可查看缓存；新增启动首屏设置，可选择课程、课表、抢课、成绩或设置作为启动页；教务网页登录新增网址输入与学校入口搜索，补齐新版强智教务的验证码输入与刷新；手动抢课队列可指定教学班并同时匹配教师与上课时间；修复部分正方教务分类与列表加载异常、会话检查初始化异常，以及课表周数显示与恢复被覆盖的问题。",
                    "2026-09-15" to "新增正方新版教务的统一身份认证登录，同类正方学校可自动识别，无需单独适配；新增河北传媒学院，可直接选择并登录；修复登录成功后仍提示未识别到学号的问题；重构课表缓存，回到课表页恢复更快；课表设置保存学期开始日期后立即跳到当前周；玻璃渲染新增图层捕获，抢课页与底栏动效更流畅。",
                    "2026-09-13" to "修正玻璃显示、拖动手感与课表排版：底栏、分段栏和数值滑块直接跟随手指，改善反向拖动与松手衔接，分段栏弹性收敛；修复课程标签书本图标显示异常、底栏玻璃图标停留在上一帧；修正抢课开始按钮的背景折射位置与圆弧菜单轮廓，减少重复采样与大面积离屏计算；课表顶栏按可用宽度和字号调整排列，星期与课程列准确对齐；长教室名完整换行，课程格高度随内容调整；课程详情打开更快。",
                    "2026-09-12" to "应用名称统一，补齐多类教务、课表管理与界面交互：支持新正方、旧正方、新强智、旧强智，补齐账密登录及旧系统图片验证码获取与重试；四类教务共用可选与已选课程、搜索筛选、单门与批量选退课、智能匹配和定时任务；目标轮次未开放时继续等待，网络异常退避重试，遇到验证码或登录失效时暂停以免重复提交；新增自定义课程编辑、时间冲突提示、删除撤销、学期起始日期与节次时间设置；统一学期成绩与考试安排并支持导出；抢课工作台开始按钮支持真实背景折射与圆弧菜单。",
                    "2026-09-03" to "适配新版正方教务系统，部分学校教务升级后选课失败的问题得到修复，选课参数会按学校自动适配，可正常查课、选课与抢课；修复课程名括号匹配，输入全角括号也能匹配教务里的半角括号课程名，搜索与抢课匹配不再漏；添加学校时支持填写带端口的域名。",
                    "2026-08-31" to "继续完善较新系统的液态玻璃：设置里的开关、滑块与取色器按下时，能看到脚下轨道的颜色透过玻璃弯曲浮现；修复按住开关时旋钮消失、折射颜色偏深偏暗的问题；修复更换预置、纯色或图片壁纸后，底栏与分段控件仍停留在上一张壁纸的折射，壁纸一变就统一重新采集；拖动滑块时折射实时跟随，静止时不产生额外开销。",
                    "2026-08-30" to "让较新系统也有真正的液态玻璃折射：按钮、开关、分段控件、选择器、弹窗与底栏都能看到背景被边缘弯曲、圆角处泛出彩色；色散改为按多个波长分别采样再合成，颜色只在圆角附近出现；修复底栏选中指示器静止时的彩边、换页后内容停留在上一页的问题；折射底图只在内容变化与滚动时重新采集，静止时不产生额外开销。",
                    "2026-08-19" to "修复正式版里液态玻璃折射与色散看不见的问题，壁纸新增一层极淡微纹理，玻璃控件因此有了可折射的内容，拖动与点按开关时能看到背景被弯曲、边缘泛出彩色；设置新增液态玻璃总开关，关闭后改用不透明材质，更省电也更清晰，重启后保持；修复关闭玻璃或较旧系统上自定义图片壁纸整张消失、只剩纯色的问题。",
                    "2026-08-18" to "带来全新的液态玻璃界面，按压、拖动、切换都有真实的形变与回弹；新增自定义壁纸，可选预置背景、纯色或自己的照片，支持模糊与暗化调节，界面深浅颜色会跟着壁纸自动适配；课表与成绩顶栏改为随滚动收起，筛选面板改为浮层；新增浙江工业大学统一身份认证，密码加密保存在本机，登录过期时自动续期；针对较短屏幕重新计算布局间距，弹窗按钮与列表末项不再被系统导航栏遮挡；修复自定义壁纸选图偶尔失败、抢课页与成绩页部分文字被遮挡、切换选项时指示器错位。",
                    "2026-07-14" to "重构 Liquid Glass 视觉与兼容策略：引入 Navigation、Control、Modal、Interactive 角色化材质，静止态使用中性折射并仅在交互时短暂增强流体反馈；移除 vivo、oppo、oneplus、realme 等厂商硬编码，设备默认启用 Backdrop，仅在上一进程发生崩溃、native 崩溃或 ANR 后按当前版本自动降级。",
                    "2026-07-14" to "完善统一液态控件与 TYUST SSO 登录：优化按钮、开关、分段选择器、锚定 Picker、弹窗和登录面板的层级与可访问性；修复 TYUST SSO 空 form action、首次提交及中间响应处理，并补充自动化测试。",
                    "2026-07-13" to "稳定液态玻璃导航与控件：收敛 Backdrop 参数和 API 31/32 回退路径，降低 RuntimeShader 与 RenderThread 风险，修复快速切换时的交互状态问题。",
                    "2026-07-11" to "新增统一登录适配工作流：支持学校适配状态查询、申请入口与 TYUST 专用统一认证流程，同时保持其他学校原有登录方式不变。",
                    "2026-07-10" to "新增 TYUST SSO 账号密码登录，完善登录协议解析、验证码与错误状态处理，并改进真实环境登录诊断信息。",
                    "2026-07-10" to "完成 TYUST SSO 协议登录的设计与验证方案，明确首次请求、表单提交、重定向和教务会话建立链路。",
                    "2026-06-13" to "支持单账号并行抢课：队列、关键词和模糊候选可在当前账号内并行尝试，最多同时处理 2 门课程；并行 worker 使用独立课程上下文，避免课程参数串号，同时保持切换账号前必须停止抢课的单账号边界。",
                    "2026-06-13" to "修复抢课健康检查误判：区分快速探针失败与真实慢响应，低于 3000ms 的连接失败不再显示为响应超时，也不再跳过抢课尝试；健康检查复用教务请求链路与账号 Cookie，提升不同网络环境下的判定稳定性。",
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
            deviceId = deviceId,
            isSuper = isSuper,
            usedCount = quotaUsedCount,
            maxCount = quotaMaxCount,
            boundNames = quotaBoundNames,
            onBindingsChanged = ::refreshAccountUiState,
            accounts = quotaAccounts,
            currentAccountKey = currentAccountKey,
            onSwitchAccount = { accountKey ->
                switchAccount(accountKey) { showQuotaDialog = false }
            },
            onDismiss = { showQuotaDialog = false }
        )
    }

    if (showAccountManagerDialog) {
        AccountManagerDialog(
            accounts = allAccounts,
            currentAccountKey = currentAccountKey,
            accountsWithPassword = accountsWithPassword,
            onSwitchAccount = { accountKey ->
                switchAccount(accountKey) { showAccountManagerDialog = false }
            },
            onDeletePassword = { pendingPasswordDelete = it },
            onDeleteAccount = { pendingAccountDelete = it },
            onRelogin = { showAccountManagerDialog = false; relogin.launch(Intent(context, LoginActivity::class.java).apply {
                putExtra("force_relogin", true); putExtra(LoginActivity.EXTRA_RETURN_TO_CALLER, true)
            }) },
            onDismiss = { showAccountManagerDialog = false }
        )
    }

    pendingPasswordDelete?.let { record ->
        SimpleConfirmDialog(
            title = "删除已保存的密码",
            text = "删除后「${record.displayName}」将无法在登录状态失效时自动续期，" +
                "需要你手动重新登录。账号本身与本地数据不会被删除。",
            confirmText = "删除密码",
            onConfirm = {
                deleteAccountPassword(record)
                pendingPasswordDelete = null
            },
            onDismiss = { pendingPasswordDelete = null }
        )
    }

    pendingAccountDelete?.let { record ->
        SimpleConfirmDialog(
            title = "删除账号",
            text = "将删除「${record.displayName}」的账号记录、已保存的密码、登录状态与本地课程缓存，" +
                "此操作不可恢复。绑定名额不会自动释放，可在配额管理中解绑。",
            confirmText = "删除账号",
            onConfirm = {
                deleteAccountEntirely(record)
                pendingAccountDelete = null
                showAccountManagerDialog = false
            },
            onDismiss = { pendingAccountDelete = null }
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
                    text = "学校与教务",
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
            TextButton(onClick = { showAcademicSupport = true }) { Text("当前适配与支持限制") }
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
                                GlassToaster.show("已切换到：${school.name}")
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
    deviceId: String,
    isSuper: Boolean,
    usedCount: Int,
    maxCount: Int,
    boundNames: List<String>,
    onBindingsChanged: () -> Unit,
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

            Text("设备 ID：${deviceId.ifBlank { "未获取" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

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
                BindingManagementContent(onChanged = onBindingsChanged)
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
                text = "说明：同一设备可绑定不同学校的学生账号，所有学校合计最多 3 个；切换账号会同步切换 Cookie 与本地账号上下文。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun AccountManagerDialog(
    accounts: List<UserManager.AccountRecord>,
    currentAccountKey: String,
    accountsWithPassword: Set<String>,
    onSwitchAccount: (String) -> Unit,
    onDeletePassword: (UserManager.AccountRecord) -> Unit,
    onDeleteAccount: (UserManager.AccountRecord) -> Unit,
    onRelogin: () -> Unit,
    onDismiss: () -> Unit
) {
    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "账号管理",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "切换账号、管理已保存的密码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "完成",
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TextButton(onClick = onRelogin) { Text("重新登录 · 保留已存账号") }
            if (accounts.isEmpty()) {
                Text(
                    text = "本机还没有保存任何账号。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                accounts.forEach { account ->
                    val isCurrent = account.key == currentAccountKey
                    val hasPassword = accountsWithPassword.contains(account.key)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = if (isCurrent) NeuPrimary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = account.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${account.accountIdText} · " +
                                            if (account.loginMode == "password") "密码登录" else "Cookie 登录",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = account.schoolName.ifBlank { "未记录学校" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                SystemStatusBadge(
                                    text = if (hasPassword) "已存密码" else "未存密码",
                                    tone = if (hasPassword) SystemTone.Success else SystemTone.Neutral
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isCurrent) {
                                    SystemStatusBadge(text = "当前账号", tone = SystemTone.Info)
                                } else {
                                    AccountActionButton(
                                        text = "切换",
                                        onClick = { onSwitchAccount(account.key) }
                                    )
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                if (hasPassword) {
                                    AccountActionButton(
                                        text = "删除密码",
                                        onClick = { onDeletePassword(account) }
                                    )
                                }
                                AccountActionButton(
                                    text = "删除账号",
                                    tint = com.tyust.course.ui.theme.SemanticDanger,
                                    onClick = { onDeleteAccount(account) }
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = "密码经系统密钥库加密后仅保存在本机，用于登录状态失效时自动续期；" +
                    "退出登录不会删除它。删除账号不会自动释放绑定名额，可在配额管理中解绑。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}

/** 账号卡里的小动作按钮：轻量胶囊，避免三个实心按钮在一行里互相抢注意力。 */
@Composable
private fun AccountActionButton(
    text: String,
    tint: Color = NeuPrimary,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = tint
        )
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

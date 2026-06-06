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
import com.tyust.course.LoginActivity
import com.tyust.course.manager.UserManager
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
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute() {
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
    
    val scope = rememberCoroutineScope()
    
    // Quota States
    var isSuper by remember { mutableStateOf(false) }
    var quotaInfo by remember { mutableStateOf("") }
    var quotaDialogMessage by remember { mutableStateOf("") }
    
    // Update States
    val updateManager = remember { UpdateManager.getInstance(context) }
    var updateInfo by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    val currentVersion = remember { updateManager.getCurrentVersionName() }


    LaunchedEffect(Unit) {
        val name = UserManager.getInstance().studentName
        val school = UserManager.getInstance().currentSchool

        studentName = name ?: "同学"
        deviceId = ActivationManager.getSavedDeviceId(context)
        schoolName = school?.name ?: "未选择"
        
        // Load quota info
        val maxStudents = ActivationManager.getMaxStudents(context)
        isSuper = maxStudents <= 0
        if (isSuper) {
            quotaInfo = "无限制"
        } else {
            val usedCount = StudentLimitManager.getUsedCount(context)
            quotaInfo = "$usedCount / $maxStudents"
        }
        
        // Prepare quota dialog message
        val usedNames = StudentLimitManager.getUsedStudentNames(context)
        quotaDialogMessage = buildString {
            append("📊 设备绑定详情\n\n")
            if (isSuper) {
                append("✨ 身份：超级用户\n")
                append("📈 配额：无限制\n")
            } else {
                append("📈 配额：${usedNames.size} / $maxStudents\n")
            }
            append("━━━━━━━━━━━━━━━\n")
            if (usedNames.isNotEmpty()) {
                append("👥 已绑定账号：\n")
                usedNames.forEachIndexed { index, n ->
                    append("${index + 1}. $n\n")
                }
            } else {
                append("ℹ️ 暂未绑定任何账号\n")
            }
            append("━━━━━━━━━━━━━━━\n\n")
            append("💡 说明：激活名额一旦绑定无法自行解绑。")
        }
        

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
        onLogExport = { com.tyust.course.utils.LogUtils.exportLogs(context) },
        isSuper = isSuper,
        quotaInfo = quotaInfo
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
                    "2026-06-06" to "修复后台子线程弹出 Toast 导致 Cookie 过期时应用闪退的问题，提升自动重新登录逻辑的稳定性。",
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
        SimpleConfirmDialog(
            title = "当前账号配额",
            text = quotaDialogMessage,
            onConfirm = { showQuotaDialog = false },
            onDismiss = { showQuotaDialog = false },
            confirmText = "我知道了",
            showCancel = false
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



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
import androidx.compose.foundation.layout.*
import com.tyust.course.LoginActivity
import com.tyust.course.manager.UserManager
import com.tyust.course.ui.screen.SettingsScreen
import com.tyust.course.update.UpdateManager
import com.tyust.course.update.UpdateDialog
import com.tyust.course.announcement.AnnouncementHistoryScreen
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
    var showHistory by remember { mutableStateOf(false) }
    var showQuotaDialog by remember { mutableStateOf(false) }
    var showFeedbackDialog by remember { mutableStateOf(false) }
    var isSubmittingFeedback by remember { mutableStateOf(false) }
    
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

    // If history is showing, show the history screen
    if (showHistory) {
        AnnouncementHistoryScreen(onBack = { showHistory = false })
        return
    }
    
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
        onAnnouncementHistory = { showHistory = true },
        onCookieConfig = { performLogout() },
        onClearCache = { showClearCacheDialog = true },
        onCheckUpdate = { checkForUpdate() },
        onAbout = { showAboutDialog = true },
        onLogout = { showLogoutDialog = true },
        onQuotaClick = { showQuotaDialog = true },
        onLogExport = { com.tyust.course.utils.LogUtils.exportLogs(context) },
        onFeedback = { showFeedbackDialog = true },
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
          SimpleConfirmDialog(
            title = "关于",
            text = "正方教务工具 Android版\n\n版本: 1.0.0\n\n功能特性:\n• 课程信息查询\n• 智能抢课Pro+\n• 课表查看\n• 成绩查询\n\n本应用仅供学习交流使用",
            onConfirm = { showAboutDialog = false },
            onDismiss = { showAboutDialog = false },
            confirmText = "确定",
            showCancel = false
        )
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

    if (showFeedbackDialog) {
        FeedbackDialog(
            isSubmitting = isSubmittingFeedback,
            onDismiss = { if (!isSubmittingFeedback) showFeedbackDialog = false },
            onSubmit = { content, contact, screenshot, includeLogs ->
                scope.launch {
                    isSubmittingFeedback = true
                    try {
                        val result = com.tyust.course.network.FeedbackManager.submitFeedback(
                            context, content, contact, screenshot, includeLogs
                        )
                        if (result.isSuccess) {
                            Toast.makeText(context, "感谢您的反馈！", Toast.LENGTH_SHORT).show()
                            showFeedbackDialog = false
                        } else {
                            Toast.makeText(context, "发送失败: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "网络错误: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        isSubmittingFeedback = false
                    }
                }
            }
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

        Dialog(onDismissRequest = { dismiss() }) {
             androidx.compose.animation.AnimatedVisibility(
                visible = animateTrigger,
                enter = androidx.compose.animation.scaleIn(animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                )) + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300)),
                exit = androidx.compose.animation.scaleOut(animationSpec = androidx.compose.animation.core.tween(200)) + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(200))
            ) {
                 Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "选择学校",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        val schools = remember { UserManager.getInstance().supportedSchools }
                        LazyColumn {
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
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                        
                        TextButton(
                            onClick = { dismiss() },
                            modifier = Modifier.align(Alignment.End).padding(top = 8.dp)
                        ) {
                            Text("取消")
                        }
                    }
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
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText) }
        },
        dismissButton = if (showCancel) {
            { TextButton(onClick = onDismiss) { Text("取消") } }
        } else null
    )
}

@Composable
fun FeedbackDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String, String?, Boolean) -> Unit,
    isSubmitting: Boolean = false
) {
    var content by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var screenshotBase64 by remember { mutableStateOf<String?>(null) }
    var includeLogs by remember { mutableStateOf(true) }
    var isCompressing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope() // 移到这里

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            isCompressing = true
            // 在 IO 线程处理图片
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bitmap = BitmapFactory.decodeStream(input)
                        // 压缩图片
                        val outputStream = ByteArrayOutputStream()
                        // 调整尺寸到最大 1280 像素
                        val ratio = Math.min(1280f / bitmap.width, 1280f / bitmap.height).coerceAtMost(1f)
                        val resized = if (ratio < 1f) {
                            Bitmap.createScaledBitmap(
                                bitmap, 
                                (bitmap.width * ratio).toInt(), 
                                (bitmap.height * ratio).toInt(), 
                                true
                            )
                        } else bitmap
                        
                        resized.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                        val bytes = outputStream.toByteArray()
                        val base64 = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                        screenshotBase64 = base64
                    }
                } catch (e: Exception) {
                    Log.e("Feedback", "图片处理失败", e)
                } finally {
                    isCompressing = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text("意见反馈", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("反馈内容") },
                    placeholder = { Text("请描述您遇到的问题或建议...") },
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    maxLines = 5,
                    enabled = !isSubmitting
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = contact,
                    onValueChange = { contact = it },
                    label = { Text("联系方式 (可选)") },
                    placeholder = { Text("微信/QQ/邮箱") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSubmitting
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                // 日志勾选框
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isSubmitting) { includeLogs = !includeLogs }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = includeLogs,
                        onCheckedChange = { includeLogs = it },
                        enabled = !isSubmitting
                    )
                    Text(
                        text = "附带运行日志（推荐，便于排查问题）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSubmitting) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 图片选择预览区域
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (screenshotBase64 != null) {
                        Box(modifier = Modifier.size(60.dp)) {
                            // 简单的 Base64 图片预览
                            val bitmap = remember(screenshotBase64) {
                                val pureBase64 = screenshotBase64!!.substringAfter(",")
                                val bytes = Base64.decode(pureBase64, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Screenshot",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            if (!isSubmitting) {
                                IconButton(
                                    onClick = { screenshotBase64 = null },
                                    modifier = Modifier
                                        .size(20.dp)
                                        .align(Alignment.TopEnd)
                                        .background(Color.Red.copy(alpha = 0.7f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove",
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    
                    TextButton(
                        onClick = { launcher.launch("image/*") },
                        enabled = !isSubmitting && !isCompressing
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddAPhoto,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isCompressing) "处理中..." else if (screenshotBase64 == null) "添加截图" else "更换截图")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (content.isNotBlank()) onSubmit(content, contact, screenshotBase64, includeLogs) },
                enabled = content.isNotBlank() && !isCompressing && !isSubmitting
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("提交中...")
                } else {
                    Text("提交")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting
            ) {
                Text("取消")
            }
        }
    )
}

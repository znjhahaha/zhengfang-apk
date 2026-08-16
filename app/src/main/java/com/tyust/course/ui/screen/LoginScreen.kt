package com.tyust.course.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.ui.system.isBackdropSupported
import com.tyust.course.ui.system.rememberGlassAccessibilityMode
import com.tyust.course.ui.system.DialogHost
import com.tyust.course.ui.system.LocalAppBackdrop
import com.tyust.course.ui.system.LocalControlBackdrop
import com.tyust.course.ui.system.LocalDialogHost
import com.tyust.course.ui.system.rememberDialogHostState
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSecondaryButton
import com.tyust.course.ui.system.SystemSegmentedControl
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemPicker
import com.tyust.course.ui.system.glass.glassSheet
import com.tyust.course.ui.theme.*

import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import com.tyust.course.model.SchoolConfig
import com.tyust.course.manager.AppearanceSettingsManager
import com.tyust.course.manager.UserManager
import com.tyust.course.ui.system.GlassTextField
import com.tyust.course.ui.system.drawWallpaperPattern

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    schools: List<SchoolConfig>,
    onSchoolSelected: (SchoolConfig) -> Unit,
    onLoginClick: (cookie: String) -> Unit,
    onOpenWebView: () -> Unit = {},
    onSchoolAdded: () -> Unit = {},
    onDemoMode: () -> Unit = {},
    onSchoolAdaptation: () -> Unit = {},
    isLoading: Boolean = false,
    errorMessage: String? = null,
    cookieValue: String = "",
    // Password login
    onPasswordLogin: ((username: String, password: String) -> Unit)? = null,
    captchaImageBytes: ByteArray? = null,
    onCaptchaSubmit: ((code: String) -> Unit)? = null,
    onCaptchaRefresh: (() -> Unit)? = null,
    // New parameters for binding dialog
    showBindingDialog: Boolean = false,
    bindingStudentName: String = "",
    bindingMaxStudents: Int = 0,
    bindingUsedNames: Set<String> = emptySet(),
    onConfirmBinding: () -> Unit = {},
    onCancelBinding: () -> Unit = {}
) {
    var cookie by remember { mutableStateOf(cookieValue) }
    var loginTab by remember { mutableStateOf(if (onPasswordLogin != null) 0 else 1) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showCaptchaDialog by remember { mutableStateOf(false) }
    var captchaInput by remember { mutableStateOf("") }
    var captchaSubmitting by remember { mutableStateOf(false) }
    var captchaDismissed by remember { mutableStateOf(false) }
    
    // Update cookie when external value changes
    LaunchedEffect(cookieValue) {
        if (cookieValue.isNotEmpty()) {
            cookie = cookieValue
        }
    }
    var showPassword by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var showEditSchoolDialog by remember { mutableStateOf(false) }
    val accessibility = rememberGlassAccessibilityMode()
    val loginPanelEnter = if (accessibility.reduceMotion) {
        androidx.compose.animation.EnterTransition.None
    } else {
        slideInVertically(
            initialOffsetY = { 100 },
            animationSpec = androidx.compose.animation.core.spring(
                stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy
            )
        ) + fadeIn(animationSpec = androidx.compose.animation.core.tween(300))
    }
    
    LaunchedEffect(Unit) {
        visible = true
    }

    // 验证码弹窗触发
    LaunchedEffect(captchaImageBytes) {
        if (captchaImageBytes != null) {
            // 新的验证码图片到达，重置所有状态
            captchaInput = ""
            captchaSubmitting = false
            captchaDismissed = false
            showCaptchaDialog = true
        } else {
            showCaptchaDialog = false
        }
    }
    
    // 与主界面同款 Aurora 流体壁纸，登录卡的玻璃采样有真实的多彩层次
    val wallpaperPreset = AppearanceSettingsManager.wallpaper
    val backdrop = if (isBackdropSupported()) {
        rememberLayerBackdrop {
            drawWallpaperPattern(wallpaperPreset)
            drawContent()
        }
    } else {
        null
    }

    // 登录页原先根本没有把 backdrop 提供出去，于是页面内的选择器、分段控件和
    // 所有弹窗都只能走"不透明白底"回退——「添加学校」「编辑学校配置」看起来
    // 像另一个 App 就是这个原因。
    //
    // DialogHost 同样是必需的：没有它，SystemDialog 会退回平台 Dialog（独立窗口），
    // 而玻璃采样的是本窗口的图层，跨窗口取不到（MainActivity 里那条注释就是这件事）。
    // 挂上之后弹窗在同一窗口渲染，玻璃、压暗、返回键与点击外部关闭都成立。
    val dialogHostState = rememberDialogHostState()
    CompositionLocalProvider(
        LocalAppBackdrop provides backdrop,
        LocalControlBackdrop provides backdrop,
        LocalDialogHost provides dialogHostState
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(wallpaperPreset.baseColor)
    ) {
        if (backdrop != null) {
            Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop))
        } else {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawWallpaperPattern(wallpaperPreset)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App Icon
            AnimatedVisibility(
                visible = visible,
                enter = androidx.compose.animation.scaleIn(initialScale = 0.9f, animationSpec = androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessLow, dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy)) + fadeIn(animationSpec = androidx.compose.animation.core.tween(300))
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val iconShape = RoundedCornerShape(24.dp)
                    Surface(
                        modifier = Modifier.size(90.dp),
                        shape = iconShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        border = androidx.compose.foundation.BorderStroke(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.56f)
                        ),
                        shadowElevation = 2.dp,
                        tonalElevation = 0.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.School,
                                contentDescription = "School Icon",
                                modifier = Modifier.size(48.dp),
                                tint = NeuPrimary
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Text(
                        text = "正方教务助手",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Neutral900,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = "正方教务系统第三方客户端",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Neutral500,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Login Card (Glassmorphism / Outline style)
            AnimatedVisibility(
                visible = visible,
                enter = loginPanelEnter
            ) {
                val sheetShape = RoundedCornerShape(28.dp)
                // 配方搬到了 Modifier.glassSheet（引导页复用同一件），参数一一对齐，视觉不变
                val cardGlassMod = if (backdrop != null && isBackdropSupported()) {
                    Modifier
                        .fillMaxWidth()
                        .glassSheet(backdrop = backdrop, cornerRadius = 28.dp)
                } else {
                    null
                }

                val cardInner: @Composable ColumnScope.() -> Unit = {
                    // Title with Settings Button
                        Box(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (loginTab == 0 && onPasswordLogin != null) "登录教务系统" else "Cookie 登录",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Neutral900,
                                modifier = Modifier.align(Alignment.Center)
                            )
                            
                            // Settings button
                            IconButton(
                                onClick = { showEditSchoolDialog = true },
                                modifier = Modifier.align(Alignment.CenterEnd).size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "编辑学校配置",
                                    tint = Neutral500,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = if (loginTab == 0 && onPasswordLogin != null) {
                                "使用教务系统的学号与密码登录"
                            } else {
                                "请从浏览器复制教务系统登录后的会话 Cookie"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Neutral500,
                            textAlign = TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        // School Selector
                        Text(
                            text = "选择学校",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Neutral700,
                            modifier = Modifier.align(Alignment.Start)
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        var selectedSchool by remember { mutableStateOf<SchoolConfig?>(null) }
                        var showAddSchoolDialog by remember { mutableStateOf(false) }
                        
                        // Initialize selected school
                        LaunchedEffect(schools) {
                            val userManager = UserManager.getInstance()
                            if (userManager.getCurrentSchool() != null) {
                                selectedSchool = userManager.getCurrentSchool()
                            } else if (schools.isNotEmpty() && selectedSchool == null) {
                                selectedSchool = schools[0]
                                onSchoolSelected(schools[0])
                            }
                        }

                        val selectedSchoolIndex = schools
                            .indexOfFirst { it.id == selectedSchool?.id }
                            .takeIf { it >= 0 }
                        SystemPicker(
                            options = schools.map { it.name },
                            selectedIndex = selectedSchoolIndex,
                            onSelect = { index ->
                                selectedSchool = schools[index]
                                onSchoolSelected(schools[index])
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = "请选择学校",
                            actionLabel = "+ 添加学校",
                            onAction = { showAddSchoolDialog = true },
                            backdrop = backdrop
                        )
                        
                        // Add School Dialog
                        if (showAddSchoolDialog) {
                            AddSchoolDialog(
                                onDismiss = { showAddSchoolDialog = false },
                                onConfirm = { name, urlData, protocol ->
                                    val parts = urlData.split("|")
                                    val domain = parts[0].trim()
                                    val basePath = if (parts.size > 1) parts[1] else "/jwglxt"
                                    
                                    val domainPattern = Regex("^[a-zA-Z0-9][a-zA-Z0-9.\\-]*[a-zA-Z0-9]$")
                                    val isValidDomain = domain.length >= 2 && domainPattern.matches(domain)
                                    
                                    if (isValidDomain) {
                                        showAddSchoolDialog = false
                                        val schoolId = "custom_${System.currentTimeMillis()}"
                                        val schoolName = if (name.isNotBlank()) name else domain.split(".").firstOrNull()?.uppercase() ?: domain
                                        val newSchool = SchoolConfig(schoolId, schoolName, domain, protocol).apply {
                                            this.basePath = basePath
                                        }
                                        com.tyust.course.manager.UserManager.getInstance().addCustomSchool(newSchool)
                                        onSchoolAdded()
                                        selectedSchool = newSchool
                                        onSchoolSelected(newSchool)
                                    }
                                }
                            )
                        }
                        
                        // Edit School Config Dialog
                        if (showEditSchoolDialog && selectedSchool != null) {
                            EditSchoolConfigDialog(
                                school = selectedSchool!!,
                                onDismiss = { showEditSchoolDialog = false },
                                onSave = { updatedSchool ->
                                    showEditSchoolDialog = false
                                    com.tyust.course.manager.UserManager.getInstance().updateSchoolConfig(updatedSchool)
                                    selectedSchool = updatedSchool
                                    onSchoolSelected(updatedSchool)
                                    onSchoolAdded()
                                }
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(20.dp))

                        // 登录方式切换
                        if (onPasswordLogin != null) {
                            SystemSegmentedControl(
                                options = listOf("密码登录", "Cookie 登录"),
                                selectedIndex = loginTab,
                                onSelect = { loginTab = it },
                                backdrop = backdrop
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        if (loginTab == 0 && onPasswordLogin != null) {
                            // 密码登录表单
                            Text(
                                text = "账号密码",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Neutral700,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            GlassTextField(
                                value = username,
                                onValueChange = { username = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "学号",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                minHeight = 50.dp
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            GlassTextField(
                                value = password,
                                onValueChange = { password = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "密码",
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailing = {
                                    IconButton(
                                        onClick = { showPassword = !showPassword },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = null,
                                            tint = Neutral500,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                },
                                minHeight = 50.dp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                color = SemanticInfoContainer.copy(alpha = 0.5f),
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, SemanticInfo.copy(alpha = 0.2f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = SemanticInfo,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "提示：部分学校的教务系统已接入统一身份认证平台。如无法在此直接输入教务密码登录，建议使用“内嵌浏览器自动获取”或“Cookie 登录”方式。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SemanticInfo,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        } else {
                        
                        // Cookie Input（玻璃多行输入）
                        GlassTextField(
                            value = cookie,
                            onValueChange = { cookie = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            placeholder = "粘贴 Cookie 字符串",
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            singleLine = false,
                            trailing = {
                                IconButton(
                                    onClick = { showPassword = !showPassword },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showPassword) "隐藏" else "显示",
                                        tint = Neutral500,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        )
                        } // end else (cookie tab)
                        
                        // Error Message
                        AnimatedVisibility(visible = errorMessage != null) {
                            Column {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = errorMessage ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SemanticDanger
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        // Login Button
                        if (loginTab == 0 && onPasswordLogin != null) {
                            SystemPrimaryButton(
                                text = if (isLoading) "登录中…" else "密码登录",
                                onClick = { onPasswordLogin(username, password) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                enabled = username.isNotBlank() && password.isNotBlank() && !isLoading
                            )
                        } else {
                            SystemPrimaryButton(
                                text = if (isLoading) "登录中…" else "Cookie 登录",
                                onClick = { onLoginClick(cookie) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                enabled = cookie.isNotBlank() && !isLoading
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // WebView Cookie Button - 密码模式下点击自动切换到 Cookie 登录
                        if (loginTab == 0 && onPasswordLogin != null) {
                            // 密码模式：不显示内嵌浏览器按钮，显示提示文字
                            TextButton(
                                onClick = { loginTab = 1 },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "切换到 Cookie 登录 →",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Neutral500
                                )
                            }
                        } else {
                            SystemSecondaryButton(
                                text = "内嵌浏览器自动获取",
                                onClick = onOpenWebView,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.OpenInBrowser,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = Neutral700
                                    )
                                }
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        TextButton(
                            onClick = onSchoolAdaptation,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "申请 / 查看统一登录适配",
                                style = MaterialTheme.typography.labelLarge,
                                color = NeuPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Demo Mode Button
                        TextButton(
                            onClick = { onDemoMode() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "体验只读演示模式",
                                style = MaterialTheme.typography.labelLarge,
                                color = Neutral500,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                }

                if (cardGlassMod != null) {
                    Box(modifier = cardGlassMod) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp)
                                .padding(top = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            cardInner()
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = sheetShape,
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.72f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.60f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp)
                                .padding(top = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            cardInner()
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(40.dp))
            
            // Version Text
            AnimatedVisibility(visible = visible, enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(800))) {
                Text(
                    text = "正方教务助手 · 第三方客户端",
                    style = MaterialTheme.typography.labelSmall,
                    color = Neutral300,
                    letterSpacing = 1.sp
                )
            }
        }

        // 弹窗层：与内容列同级，压在最上面。
        // imePadding 是必需的——添加学校/验证码弹窗里都有输入框，键盘弹起时
        // 弹窗要在剩余空间里重新居中，否则确认按钮会被键盘压住。
        DialogHost(
            state = dialogHostState,
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        )
    }

    // Student Binding Confirmation Dialog
    if (showBindingDialog) {
        BindingConfirmationDialog(
            studentName = bindingStudentName,
            maxStudents = bindingMaxStudents,
            usedNames = bindingUsedNames,
            onConfirm = onConfirmBinding,
            onDismiss = onCancelBinding
        )
    }

    // 验证码弹窗
    if (showCaptchaDialog && captchaImageBytes != null) {
        SystemDialog(
            onDismissRequest = { showCaptchaDialog = false },
            title = {
                Text(
                    text = "请输入验证码",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            confirmButton = {
                SystemPrimaryButton(
                    text = if (captchaSubmitting) "提交中…" else "确认",
                    onClick = {
                        captchaSubmitting = true
                        onCaptchaSubmit?.invoke(captchaInput)
                    },
                    enabled = captchaInput.isNotBlank() && !captchaSubmitting,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                SystemSecondaryButton(
                    text = "取消",
                    onClick = {
                        showCaptchaDialog = false
                        captchaDismissed = true
                        // 不调用 refreshCaptcha，避免更新 captchaImageBytes 触发 LaunchedEffect 重新弹窗
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val bitmap = remember(captchaImageBytes) {
                    BitmapFactory.decodeByteArray(captchaImageBytes, 0, captchaImageBytes.size)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "验证码",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { onCaptchaRefresh?.invoke() }) {
                    Text("看不清？点击刷新")
                }
                Spacer(modifier = Modifier.height(8.dp))
                GlassTextField(
                    value = captchaInput,
                    onValueChange = { captchaInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "验证码",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    minHeight = 50.dp
                )
            }
        }
    }
    } // 关闭 CompositionLocalProvider
}

@Composable
fun BindingConfirmationDialog(
    studentName: String,
    maxStudents: Int,
    usedNames: Set<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "确认绑定账号",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "确认绑定",
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            SystemSecondaryButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val iconContainerShape = RoundedCornerShape(16.dp)
            Surface(
                modifier = Modifier.size(64.dp),
                shape = iconContainerShape,
                color = NeuPrimary.copy(alpha = 0.10f),
                border = androidx.compose.foundation.BorderStroke(
                    0.5.dp,
                    NeuPrimary.copy(alpha = 0.16f)
                )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.School,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = NeuPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "检测到新账号：「$studentName」",
                style = MaterialTheme.typography.titleMedium,
                color = NeuPrimary,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Info Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "绑定配额：",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${usedNames.size} / $maxStudents",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (usedNames.size >= maxStudents) SemanticDanger else SemanticSuccess
                        )
                    }

                    if (usedNames.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "已绑定：${usedNames.joinToString("、")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "确认后此账号将与本设备永久绑定，占用 1 个名额，且无法撤销。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSchoolDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, protocol: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var basePath by remember { mutableStateOf("/jwglxt") }
    var protocol by remember { mutableStateOf("https") }
    
    // Smart URL parsing function
    fun parseUrl(url: String) {
        if (url.isBlank()) return
        
        var cleanUrl = url.trim()
        
        // Extract protocol
        when {
            cleanUrl.startsWith("https://") -> {
                protocol = "https"
                cleanUrl = cleanUrl.removePrefix("https://")
            }
            cleanUrl.startsWith("http://") -> {
                protocol = "http"
                cleanUrl = cleanUrl.removePrefix("http://")
            }
        }
        
        // Extract domain
        val pathStart = cleanUrl.indexOf('/')
        if (pathStart > 0) {
            domain = cleanUrl.substring(0, pathStart)
            val pathPart = cleanUrl.substring(pathStart)
            
            // Find base path - look for common patterns
            val commonPaths = listOf("/jwglxt", "/jwxt", "/jwxs", "/jw")
            var foundPath = false
            
            for (commonPath in commonPaths) {
                if (pathPart.startsWith(commonPath + "/") || pathPart == commonPath) {
                    basePath = commonPath
                    foundPath = true
                    break
                }
            }
            
            // If no common path found, check if it starts directly with module paths
            // This means basePath should be empty
            if (!foundPath) {
                val directPaths = listOf("/xtgl/", "/xsxk/", "/kbcx/", "/cjcx/", "/xsxy/")
                for (directPath in directPaths) {
                    if (pathPart.startsWith(directPath)) {
                        basePath = ""  // No base path, modules are at root
                        foundPath = true
                        break
                    }
                }
            }
            
            // If still not found, try to extract the first path segment
            if (!foundPath && pathPart.length > 1) {
                val secondSlash = pathPart.indexOf('/', 1)
                if (secondSlash > 1) {
                    val firstSegment = pathPart.substring(0, secondSlash)
                    // Check if it looks like a module path or a base path
                    val modulePatterns = listOf("xtgl", "xsxk", "kbcx", "cjcx", "xsxy")
                    val segmentName = firstSegment.removePrefix("/")
                    if (modulePatterns.any { segmentName.startsWith(it) }) {
                        basePath = ""  // Direct module access
                    } else {
                        basePath = firstSegment
                    }
                }
            }
        } else {
            domain = cleanUrl.split("?")[0]
        }
    }
    
    // Domain validation
    val domainPattern = Regex("^[a-zA-Z0-9][a-zA-Z0-9.\\-]*[a-zA-Z0-9]$")
    val isValidDomain = domain.length >= 2 && domainPattern.matches(domain) && domain.contains(".")
    val showError = urlInput.isNotBlank() && domain.isNotBlank() && !isValidDomain
    
    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "添加学校",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "添加",
                onClick = {
                    onConfirm(name, "$domain|$basePath", protocol)
                },
                enabled = isValidDomain,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            SystemSecondaryButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SchoolFormPanel {
                SchoolFormPanelTitle(
                    icon = Icons.Default.AutoAwesome,
                    text = "智能识别"
                )
                SchoolFormField(
                    label = "教务系统网址",
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = "http://jwxt.example.edu.cn/jwglxt",
                    helper = "粘贴登录后的任意教务页面地址，下面的字段会自动填好"
                )
                SystemPrimaryButton(
                    text = "自动识别",
                    onClick = { parseUrl(urlInput) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = urlInput.isNotBlank()
                )
            }

            SchoolFormSectionTitle("基本信息")

            SchoolFormField(
                label = "学校名称（可选）",
                value = name,
                onValueChange = { name = it },
                placeholder = "例如：XX 大学"
            )

            SchoolFormField(
                label = "教务系统域名",
                value = domain,
                onValueChange = { domain = it },
                placeholder = "jwxt.example.edu.cn",
                error = if (showError) "请输入有效域名" else null
            )

            SchoolFormField(
                label = "基础路径",
                value = basePath,
                onValueChange = { basePath = it },
                placeholder = "/jwglxt 或留空",
                helper = "如 /jwglxt、/jwxt；模块直接挂在根目录时留空"
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "协议",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 两个选项用分段控件，不用下拉：一次点击就能选完，也和全 App 一致
                SystemSegmentedControl(
                    options = listOf("HTTPS", "HTTP"),
                    selectedIndex = if (protocol == "https") 0 else 1,
                    onSelect = { index -> protocol = if (index == 0) "https" else "http" },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = if (protocol == "https") "加密连接，推荐优先尝试" else "非加密连接",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }
}

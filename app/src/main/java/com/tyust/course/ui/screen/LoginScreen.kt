package com.tyust.course.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.tyust.course.ui.system.GlassWindowHost
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
    onServiceCenter: () -> Unit = {},
    onSchoolAdaptation: () -> Unit = {},
    onBack: (() -> Unit)? = null,
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
    bindingUsedCount: Int = bindingUsedNames.size,
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
    
    GlassWindowHost {
    val backdrop = LocalControlBackdrop.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("login-screen")
            .semantics { contentDescription = "login-screen" }
    ) {

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
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(com.tyust.course.R.mipmap.ic_launcher),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Text(
                        text = androidx.compose.ui.res.stringResource(com.tyust.course.R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.sp
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = "课表、成绩与选课",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Login Card (Glassmorphism / Outline style)
            AnimatedVisibility(
                visible = visible,
                enter = loginPanelEnter
            ) {
                val cardInner: @Composable ColumnScope.() -> Unit = {
                    // Title with Settings Button
                        Box(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (loginTab == 0 && onPasswordLogin != null) "登录教务系统" else "Cookie 登录",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
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
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        
                        // Keep the user's current choice when the list refreshes after add/edit.
                        LaunchedEffect(schools) {
                            val chosenId = selectedSchool?.id
                                ?: UserManager.getInstance().currentSchool?.id
                            val school = schools.firstOrNull { it.id == chosenId } ?: schools.firstOrNull()
                            if (school != null) {
                                selectedSchool = school
                                onSchoolSelected(school)
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
                            actionLabel = "添加学校",
                            onAction = { showAddSchoolDialog = true },
                            backdrop = backdrop,
                            maxLabelLines = 2
                        )
                        Text(
                            text = if (selectedSchoolIndex == null) com.tyust.course.academic.AcademicCapabilities.FOUR_SYSTEMS
                                else com.tyust.course.academic.AcademicCapabilities.name(schools[selectedSchoolIndex].academicSystem),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                        
                        // Add School Dialog
                        if (showAddSchoolDialog) {
                            AddSchoolDialog(
                                onDismiss = { showAddSchoolDialog = false },
                                onConfirm = { draft ->
                                    val newSchool = draft.toSchoolConfig()
                                    com.tyust.course.manager.UserManager.getInstance().addCustomSchool(newSchool)
                                    selectedSchool = newSchool
                                    onSchoolSelected(newSchool)
                                    onSchoolAdded()
                                    showAddSchoolDialog = false
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
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                        tint = MaterialTheme.colorScheme.onSurface
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

                        TextButton(onClick = onServiceCenter, modifier = Modifier.fillMaxWidth()) { Text("打开服务中心与通用工具") }
                        // Demo Mode Button
                        TextButton(
                            onClick = { onDemoMode() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "体验只读演示模式",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    cardInner()
                }
            }
            
            Spacer(modifier = Modifier.height(40.dp))
            
            // Version Text
            AnimatedVisibility(visible = visible, enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(800))) {
                Text(
                    text = "教务助手 · 第三方客户端",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.sp
                )
            }
        }

    }

    // Student Binding Confirmation Dialog
    if (showBindingDialog) {
        BindingConfirmationDialog(
            studentName = bindingStudentName,
            maxStudents = bindingMaxStudents,
            usedNames = bindingUsedNames,
            usedCount = bindingUsedCount,
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
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
    usedCount: Int,
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
                            text = "$usedCount / $maxStudents",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (usedCount >= maxStudents) SemanticDanger else SemanticSuccess
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
                text = "不同学校可共用设备，合计最多绑定 $maxStudents 个学生账号。确认后占用 1 个名额，且无法撤销。",
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
    onConfirm: (com.tyust.course.model.SchoolFormDraft) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var basePath by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf("https") }
    var academicSystem by remember { mutableStateOf("auto") }
    var detectionSource by remember { mutableStateOf("pending") }
    var addressError by remember { mutableStateOf<String?>(null) }

    val draft = com.tyust.course.model.SchoolFormDraft(name, domain, protocol, basePath, academicSystem, detectionSource)
    val showError = domain.isNotBlank() && !draft.isValidDomain
    
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
                    onConfirm(draft)
                },
                enabled = draft.isValid && addressError == null,
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
            SchoolUrlRecognition(
                selectedType = academicSystem,
                addressKey = "$protocol|$domain|$basePath",
                manualType = detectionSource == "manual" || detectionSource == "legacy",
                onAddress = { protocol = it.protocol; domain = it.domain; basePath = it.basePath },
                onDetected = { academicSystem = it.id; detectionSource = "automatic" },
                onInvalidAddress = { addressError = it }
            )

            SchoolFormSectionTitle("基本信息")

            SchoolAcademicSystemField(academicSystem) { academicSystem = it; detectionSource = if (it == "auto") "pending" else "manual" }

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
                helper = "非标准端口可直接带上，如 jw.example.edu.cn:30443",
                error = if (showError) "请输入有效域名" else null
            )

            SchoolFormField(
                label = "基础路径",
                value = basePath,
                onValueChange = { basePath = it },
                placeholder = "/jwglxt、/jsxsd 或留空",
                helper = "优先从完整网址解析；教务位于网站根目录时留空"
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

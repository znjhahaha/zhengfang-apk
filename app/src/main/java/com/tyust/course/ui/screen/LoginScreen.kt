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
import com.tyust.course.ui.system.GlassRecipe
import com.tyust.course.ui.system.isBackdropSupported
import com.tyust.course.ui.system.isLensSupported
import com.tyust.course.ui.system.LocalAppBackdrop
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSecondaryButton
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.theme.*

import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.tyust.course.model.SchoolConfig
import com.tyust.course.manager.UserManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    schools: List<SchoolConfig>,
    onSchoolSelected: (SchoolConfig) -> Unit,
    onLoginClick: (cookie: String) -> Unit,
    onOpenWebView: () -> Unit = {},
    onSchoolAdded: () -> Unit = {},
    onDemoMode: () -> Unit = {},
    isLoading: Boolean = false,
    errorMessage: String? = null,
    cookieValue: String = "",
    // New parameters for binding dialog
    showBindingDialog: Boolean = false,
    bindingStudentName: String = "",
    bindingMaxStudents: Int = 0,
    bindingUsedNames: Set<String> = emptySet(),
    onConfirmBinding: () -> Unit = {},
    onCancelBinding: () -> Unit = {}
) {
    var cookie by remember { mutableStateOf(cookieValue) }
    
    // Update cookie when external value changes
    LaunchedEffect(cookieValue) {
        if (cookieValue.isNotEmpty()) {
            cookie = cookieValue
        }
    }
    var showPassword by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var showEditSchoolDialog by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        visible = true
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Neutral50)
    ) {
        val backdrop = if (isBackdropSupported()) {
            rememberLayerBackdrop {
                drawRect(Neutral50)
                drawCircle(
                    color = BrandPrimary.copy(alpha = 0.18f),
                    radius = size.minDimension * 0.55f,
                    center = Offset(size.width * 0.18f, size.height * 0.22f)
                )
                drawCircle(
                    color = SemanticInfo.copy(alpha = 0.12f),
                    radius = size.minDimension * 0.65f,
                    center = Offset(size.width * 0.85f, size.height * 0.78f)
                )
                drawCircle(
                    color = SemanticSuccess.copy(alpha = 0.08f),
                    radius = size.minDimension * 0.40f,
                    center = Offset(size.width * 0.50f, size.height * 0.50f)
                )
                drawContent()
            }
        } else {
            null
        }

        if (backdrop != null) {
            Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop))
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
                    val iconGlassMod = if (backdrop != null && isBackdropSupported()) {
                        Modifier
                            .size(90.dp)
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { iconShape },
                                effects = {
                                    vibrancy()
                                    blur(2f.dp.toPx())
                                    if (isLensSupported()) {
                                        lens(
                                            refractionHeight = 20f.dp.toPx(),
                                            refractionAmount = 36f.dp.toPx(),
                                            depthEffect = true,
                                            chromaticAberration = true
                                        )
                                    }
                                },
                                onDrawSurface = {
                                    drawRect(Color.White.copy(alpha = 0.30f))
                                    drawRoundRect(
                                        color = Color.White.copy(alpha = 0.45f),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f.dp.toPx()),
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f.dp.toPx())
                                    )
                                }
                            )
                    } else {
                        Modifier.size(90.dp)
                    }
                    
                    if (backdrop != null && isBackdropSupported()) {
                        Box(
                            modifier = iconGlassMod,
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.School,
                                contentDescription = "School Icon",
                                modifier = Modifier.size(48.dp),
                                tint = NeuPrimary
                            )
                        }
                    } else {
                        Surface(
                            modifier = Modifier.size(90.dp),
                            shape = iconShape,
                            color = Color.White,
                            shadowElevation = 8.dp,
                            tonalElevation = 4.dp
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
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Text(
                        text = "抢课助手",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Neutral900,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = "极致纯粹的规则代理工具",
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
                enter = slideInVertically(
                    initialOffsetY = { 100 },
                    animationSpec = androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessLow, dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy)
                ) + fadeIn(animationSpec = androidx.compose.animation.core.tween(300))
            ) {
                val sheetShape = RoundedCornerShape(28.dp)
                val cardGlassMod = if (backdrop != null && isBackdropSupported()) {
                    Modifier
                        .fillMaxWidth()
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { sheetShape },
                            effects = {
                                vibrancy()
                                blur(GlassRecipe.SheetBlurDp.dp.toPx())
                                if (isLensSupported()) {
                                    lens(
                                        refractionHeight = GlassRecipe.SheetRefractionHeightDp.dp.toPx(),
                                        refractionAmount = GlassRecipe.SheetRefractionAmountDp.dp.toPx(),
                                        depthEffect = true,
                                        chromaticAberration = true
                                    )
                                }
                            },
                            onDrawSurface = {
                                drawRect(Color.White.copy(alpha = GlassRecipe.SheetSurfaceAlpha))
                                drawRoundRect(
                                    color = Color.White.copy(alpha = 0.35f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(28f.dp.toPx()),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f.dp.toPx())
                                )
                            }
                        )
                } else {
                    null
                }

                val cardInner: @Composable ColumnScope.() -> Unit = {
                    // Title with Settings Button
                        Box(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Cookie 登录",
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
                            text = "请从浏览器复制登录状态下所需的会话凭证",
                            style = MaterialTheme.typography.bodySmall,
                            color = Neutral500,
                            textAlign = TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        // School Selector
                        Text(
                            text = "选择教务系统预设配置",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Neutral700,
                            modifier = Modifier.align(Alignment.Start)
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        var expanded by remember { mutableStateOf(false) }
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

                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val tfShape = RoundedCornerShape(16.dp)
                            val tfModifier = if (backdrop != null && isBackdropSupported()) {
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                                    .drawBackdrop(
                                        backdrop = backdrop,
                                        shape = { tfShape },
                                        effects = {
                                            vibrancy()
                                            blur(6f.dp.toPx())
                                            if (isLensSupported()) {
                                                lens(
                                                    refractionHeight = 6f.dp.toPx(),
                                                    refractionAmount = 8f.dp.toPx(),
                                                    chromaticAberration = true
                                                )
                                            }
                                        },
                                        onDrawSurface = {
                                            drawRect(Color.White.copy(alpha = 0.15f))
                                            drawRoundRect(
                                                color = Color.White.copy(alpha = 0.25f),
                                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f.dp.toPx()),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.8f.dp.toPx())
                                            )
                                        }
                                    )
                            } else {
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                            }

                            TextField(
                                value = selectedSchool?.name ?: "请选择学校",
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = tfModifier,
                                shape = tfShape,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = if (backdrop != null) Color.Transparent else Neutral100,
                                    unfocusedContainerColor = if (backdrop != null) Color.Transparent else Neutral100,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedTextColor = Neutral900,
                                    unfocusedTextColor = Neutral900
                                )
                            )

                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                modifier = Modifier.background(Color.White)
                            ) {
                                schools.forEach { school ->
                                    DropdownMenuItem(
                                        text = { Text(school.name, color = Neutral900) },
                                        onClick = {
                                            selectedSchool = school
                                            onSchoolSelected(school)
                                            expanded = false
                                        }
                                    )
                                }
                                HorizontalDivider(color = Neutral200, thickness = 0.5.dp)
                                DropdownMenuItem(
                                    text = { 
                                        Text(
                                            text = "+ 配置新终端",
                                            color = NeuPrimary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        showAddSchoolDialog = true
                                    }
                                )
                            }
                        }
                        
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
                        
                        Text(
                            text = "安全会话凭据",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Neutral700,
                            modifier = Modifier.align(Alignment.Start)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Cookie Input (Minimalist TextField)
                        val cookieTfShape = RoundedCornerShape(16.dp)
                        val cookieTfModifier = if (backdrop != null && isBackdropSupported()) {
                            Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { cookieTfShape },
                                    effects = {
                                        vibrancy()
                                        blur(6f.dp.toPx())
                                        if (isLensSupported()) {
                                            lens(
                                                refractionHeight = 6f.dp.toPx(),
                                                refractionAmount = 8f.dp.toPx(),
                                                chromaticAberration = true
                                            )
                                        }
                                    },
                                    onDrawSurface = {
                                        drawRect(Color.White.copy(alpha = 0.15f))
                                        drawRoundRect(
                                            color = Color.White.copy(alpha = 0.25f),
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f.dp.toPx()),
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.8f.dp.toPx())
                                        )
                                    }
                                )
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        }

                        TextField(
                            value = cookie,
                            onValueChange = { cookie = it },
                            modifier = cookieTfModifier,
                            placeholder = { Text("粘贴 Cookie 字符串...", color = Neutral500) },
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showPassword) "隐藏" else "显示",
                                        tint = Neutral500
                                    )
                                }
                            },
                            shape = cookieTfShape,
                            singleLine = false,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = if (backdrop != null) Color.Transparent else Neutral100,
                                unfocusedContainerColor = if (backdrop != null) Color.Transparent else Neutral100,
                                focusedIndicatorColor = if (errorMessage != null) SemanticDanger else Color.Transparent,
                                unfocusedIndicatorColor = if (errorMessage != null) SemanticDanger else Color.Transparent,
                                focusedTextColor = Neutral900,
                                unfocusedTextColor = Neutral900,
                                cursorColor = NeuPrimary
                            )
                        )
                        
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
                        
                        // Login Button (Liquid Physics Glass Button)
                        SystemPrimaryButton(
                            text = if (isLoading) "登入中…" else "登入控制台",
                            onClick = { onLoginClick(cookie) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = cookie.isNotBlank() && !isLoading
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // WebView Cookie Button (Liquid Physics Glass Button)
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
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
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
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Neutral200.copy(alpha = 0.5f))
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
                    text = "Tyust Course Matrix • Version 2.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = Neutral300,
                    letterSpacing = 1.sp
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
            onConfirm = onConfirmBinding,
            onDismiss = onCancelBinding
        )
    }
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
                text = "🔐 确认绑定账号",
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
            val backdrop = LocalAppBackdrop.current
            val useGlass = backdrop != null && isBackdropSupported()
            val iconContainerShape = RoundedCornerShape(16.dp)
            val iconModifier = if (useGlass && backdrop != null) {
                Modifier
                    .size(64.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { iconContainerShape },
                        effects = {
                            vibrancy()
                            blur(GlassRecipe.CardBlurDp.dp.toPx())
                            if (isLensSupported()) {
                                lens(6f.dp.toPx(), 8f.dp.toPx(), chromaticAberration = true)
                            }
                        },
                        onDrawSurface = {
                            drawRect(Color.White.copy(alpha = 0.20f))
                        }
                    )
            } else {
                Modifier.size(64.dp)
            }

            if (useGlass && backdrop != null) {
                Box(modifier = iconModifier, contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.School,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = NeuPrimary
                    )
                }
            } else {
                Surface(
                    modifier = Modifier.size(64.dp),
                    shape = iconContainerShape,
                    color = NeuPrimary.copy(alpha = 0.1f)
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
                            text = "📊 绑定配额：",
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
                            text = "👥 已绑定：${usedNames.joinToString("、")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "⚠️ 确认后此账号将与本设备永久绑定，完成后将占用 1 个名额，无法撤销。",
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
    var protocolExpanded by remember { mutableStateOf(false) }
    
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
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加学校", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Smart URL input section
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = "🪄 智能识别",
                            style = MaterialTheme.typography.labelLarge,
                            color = NeuPrimary,
                            fontWeight = FontWeight.Medium
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text("粘贴教务系统 URL") },
                            placeholder = { Text("http://jwxt.xxx.edu.cn/...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Button(
                            onClick = { parseUrl(urlInput) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = NeuPrimary),
                            enabled = urlInput.isNotBlank()
                        ) {
                            Text("自动识别")
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // School Name Input
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("学校名称 (可选)") },
                    placeholder = { Text("例如: XX大学") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Domain Input
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text("教务系统域名") },
                    placeholder = { Text("jwxt.example.edu.cn") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    isError = showError,
                    supportingText = if (showError) {
                        { Text("请输入有效域名", color = MaterialTheme.colorScheme.error) }
                    } else null
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Base Path Input
                OutlinedTextField(
                    value = basePath,
                    onValueChange = { basePath = it },
                    label = { Text("基础路径") },
                    placeholder = { Text("/jwglxt 或留空") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    supportingText = { Text("如 /jwglxt、/jwxt 或留空") }
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Protocol Dropdown
                ExposedDropdownMenuBox(
                    expanded = protocolExpanded,
                    onExpandedChange = { protocolExpanded = !protocolExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = protocol.uppercase(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("协议") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeuPrimary,
                            unfocusedBorderColor = NeuPrimary.copy(alpha = 0.5f)
                        )
                    )
                    
                    ExposedDropdownMenu(
                        expanded = protocolExpanded,
                        onDismissRequest = { protocolExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("HTTPS (推荐)") },
                            onClick = {
                                protocol = "https"
                                protocolExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("HTTP") },
                            onClick = {
                                protocol = "http"
                                protocolExpanded = false
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    // Pass domain with basePath encoded
                    onConfirm(name, "$domain|$basePath", protocol) 
                },
                enabled = isValidDomain,
                colors = ButtonDefaults.buttonColors(containerColor = NeuPrimary)
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

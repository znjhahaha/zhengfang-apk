package com.tyust.course.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.R
import com.tyust.course.model.SchoolConfig
import com.tyust.course.ui.theme.SystemBlue
import com.tyust.course.ui.theme.Neutral50
import com.tyust.course.ui.theme.Neutral100
import com.tyust.course.ui.theme.Neutral500
import com.tyust.course.ui.theme.Neutral900
import com.tyust.course.ui.theme.SemanticDanger
import com.tyust.course.ui.theme.SemanticSuccess
import com.tyust.course.ui.theme.SemanticWarning
import com.tyust.course.ui.theme.SurfaceWhite
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemDivider
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    studentName: String,
    studentId: String,
    schoolName: String,
    currentVersion: String = "1.0.0",
    onSchoolSelect: () -> Unit,
    onCookieConfig: () -> Unit,
    onClearCache: () -> Unit,
    onCheckUpdate: () -> Unit,
    onAbout: () -> Unit,
    onLogout: () -> Unit,
    onQuotaClick: () -> Unit = {},
    onLogExport: () -> Unit = {},
    isSuper: Boolean = false,
    quotaInfo: String = ""
) {
    val context = LocalContext.current

    Scaffold(
        containerColor = Neutral50,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceWhite,
                    titleContentColor = Neutral900
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // 用户信息横条
            SettingsHeader(studentName, studentId, schoolName, isSuper, quotaInfo)

            Spacer(modifier = Modifier.height(16.dp))

            // 第一组：教务助手
            SettingsGroupTitle("教务助手")
            SystemCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                SettingsRow(
                    icon = Icons.Outlined.School,
                    title = "学校选择",
                    subtitle = schoolName.ifEmpty { "未选择" },
                    onClick = onSchoolSelect
                )
                SystemDivider(modifier = Modifier.padding(start = 56.dp))
                SettingsRow(
                    icon = Icons.Outlined.AssignmentInd,
                    title = "配额 / 身份",
                    subtitle = if (isSuper) "👑 超级用户" else quotaInfo.ifEmpty { "普通用户" },
                    tintColor = if (isSuper) Color(0xFFFFD700) else SystemBlue,
                    onClick = onQuotaClick
                )
                SystemDivider(modifier = Modifier.padding(start = 56.dp))
                SettingsRow(
                    icon = Icons.Outlined.Cookie,
                    title = "配置凭证",
                    subtitle = "Cookie / Token",
                    onClick = onCookieConfig
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 第二组：数据与安全
            SettingsGroupTitle("数据与安全")
            SystemCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                SettingsRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = "检查更新",
                    tintColor = SemanticSuccess,
                    onClick = onCheckUpdate
                )
                SystemDivider(modifier = Modifier.padding(start = 56.dp))
                SettingsRow(
                    icon = Icons.Outlined.Delete,
                    title = "清除缓存",
                    tintColor = SemanticWarning,
                    onClick = onClearCache
                )
                SystemDivider(modifier = Modifier.padding(start = 56.dp))
                SettingsRow(
                    icon = Icons.Outlined.ContentPasteSearch,
                    title = "导出日志",
                    tintColor = SystemBlue,
                    onClick = onLogExport
                )
                SystemDivider(modifier = Modifier.padding(start = 56.dp))
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = "关于应用",
                    onClick = onAbout
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 退出登录
            Box(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                OutlinedButton(
                    onClick = onLogout,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SemanticDanger
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SemanticDanger.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("退出登录", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// 用户信息横条 — 极简、无装饰
@Composable
fun SettingsHeader(name: String, id: String, school: String, isSuper: Boolean = false, quotaInfo: String = "") {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically() + fadeIn(animationSpec = tween(500))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceWhite)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像首字
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Neutral100),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.take(1),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = SystemBlue
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Neutral900
                    )
                    if (isSuper) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "👑",
                            fontSize = 14.sp
                        )
                    }
                }
                Text(
                    text = school.ifEmpty { "未登录" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Neutral500
                )
            }
        }
        // iOS 设置页不需要在独立 Block 的底部加强制切分线
    }
}

// 分组标题
@Composable
fun SettingsGroupTitle(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Neutral500,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp)
    )
}

// Things 3 风格列表行
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tintColor: Color = Neutral500,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tintColor,
            modifier = Modifier.size(22.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = Neutral900,
                fontWeight = FontWeight.Normal
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Neutral500
                )
            }
        }

        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Neutral500.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp)
        )
    }
}

// 保留旧 SettingsItem 的签名以兼容其他调用方 (如果有)
@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    showBadge: Boolean = false,
    onClick: () -> Unit
) {
    SettingsRow(icon = icon, title = title, subtitle = subtitle, tintColor = iconColor, onClick = onClick)
}

// 保留旧 SettingsCompactItem 的签名以兼容其他调用方 (如果有)
@Composable
fun SettingsCompactItem(
    icon: ImageVector,
    title: String,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    showBadge: Boolean = false,
    onClick: () -> Unit
) {
    SettingsRow(icon = icon, title = title, tintColor = iconColor, onClick = onClick)
}

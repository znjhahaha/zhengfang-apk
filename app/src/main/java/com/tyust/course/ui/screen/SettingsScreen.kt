package com.tyust.course.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.R
import com.tyust.course.model.SchoolConfig
import com.tyust.course.ui.theme.PrimaryPurple
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
    onAnnouncementHistory: () -> Unit,
    onCookieConfig: () -> Unit,
    onClearCache: () -> Unit,
    onCheckUpdate: () -> Unit,
    onAbout: () -> Unit,
    onLogout: () -> Unit,
    onQuotaClick: () -> Unit = {},
    onLogExport: () -> Unit = {},
    onFeedback: () -> Unit = {},
    onFeedbackHistory: () -> Unit = {},
    isSuper: Boolean = false,
    quotaInfo: String = "",
    hasNewFeedbackReply: Boolean = false
) {
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
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
            // User Header Card
            SettingsHeader(studentName, studentId, schoolName, isSuper, quotaInfo)
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 1. 教务助手 (Compact Grid)
            SettingsGroupTitle("教务助手")
            Card(
                modifier = Modifier.padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp), // 圆角稍微改小一点适配紧凑风格
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) { // 卡片内边距减小到 8dp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        SettingsCompactItem(
                            icon = Icons.Outlined.School,
                            title = "学校选择",
                            onClick = onSchoolSelect
                        )
                        SettingsCompactItem(
                            icon = Icons.Outlined.Campaign,
                            title = "历史公告",
                            iconColor = PrimaryPurple,
                            onClick = onAnnouncementHistory
                        )
                        SettingsCompactItem(
                            icon = Icons.Outlined.AssignmentInd,
                            title = "配额/身份",
                            iconColor = if (isSuper) Color(0xFFFFD700) else PrimaryPurple,
                            onClick = onQuotaClick
                        )
                        SettingsCompactItem(
                            icon = Icons.Outlined.Cookie,
                            title = "配置凭证",
                            onClick = onCookieConfig
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp)) // 组间距减小到 8dp
            
            // 2. 数据与安全 (Compact Grid)
            SettingsGroupTitle("数据与安全")
            Card(
                modifier = Modifier.padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        SettingsCompactItem(
                            icon = Icons.Filled.SystemUpdate,
                            title = "检查更新",
                            iconColor = Color(0xFF4CAF50),
                            onClick = onCheckUpdate
                        )
                        SettingsCompactItem(
                            icon = Icons.Outlined.Delete,
                            title = "清除缓存",
                            iconColor = Color(0xFFFF9800),
                            onClick = onClearCache
                        )
                        SettingsCompactItem(
                            icon = Icons.Outlined.ContentPasteSearch,
                            title = "导出日志",
                            iconColor = Color(0xFF2196F3),
                            onClick = onLogExport
                        )
                        // 占位符保持对齐
                         Spacer(modifier = Modifier.width(64.dp))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 3. 交流与反馈 (Compact Grid)
            SettingsGroupTitle("交流与反馈")
            Card(
                modifier = Modifier.padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        SettingsCompactItem(
                            icon = Icons.Outlined.Message,
                            title = "提交反馈",
                            onClick = onFeedback
                        )
                        SettingsCompactItem(
                            icon = Icons.Outlined.History,
                            title = "我的反馈",
                            iconColor = Color(0xFF9C27B0),
                            showBadge = hasNewFeedbackReply,
                            onClick = onFeedbackHistory
                        )
                        SettingsCompactItem(
                            icon = Icons.Outlined.Info,
                            title = "关于应用",
                            onClick = onAbout
                        )
                         // 占位符保持对齐
                         Spacer(modifier = Modifier.width(64.dp))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Logout Button
            Box(modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth()) {
                Button(
                    onClick = onLogout,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("退出登录", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsHeader(name: String, id: String, school: String, isSuper: Boolean = false, quotaInfo: String = "") {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically() + fadeIn(animationSpec = tween(500))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Avatar Placeholder
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name.take(1),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryPurple
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        SuggestionChip(
                            onClick = {},
                            label = { Text(school) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f),
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            border = null,
                            modifier = Modifier.height(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "设备ID: ${id.ifBlank { "未获取" }}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        
                        // Quota / Super Status
                        if (isSuper || quotaInfo.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            if (isSuper) {
                                Surface(
                                    color = Color(0xFFFFD700),
                                    shape = RoundedCornerShape(8.dp),
                                    shadowElevation = 2.dp
                                ) {
                                    Text(
                                        text = " 👑 超级用户 ",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF5C4033),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            } else {
                                Surface(
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                                ) {
                                    Text(
                                        text = " 📊 配额: $quotaInfo ",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsGroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge, // 换更小的字体风格
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp) // 极小的垂直间距
    )
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    showBadge: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            if (showBadge) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color.Red)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun SettingsCompactItem(
    icon: ImageVector,
    title: String,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    showBadge: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp) // 减小触控区域宽度
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp) // 减小垂直内边距
    ) {
        Box(
            contentAlignment = Alignment.TopEnd
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp) // 减小图标背景块大小
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp) // 略微减小图标
                )
            }
            
            if (showBadge) {
                Box(
                    modifier = Modifier
                        .offset(x = 3.dp, y = (-3).dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color.Red)
                        .border(1.dp, Color.White, CircleShape)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp)) // 减小文字间距
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            fontSize = 11.sp // 略微减小字号以适应更窄宽度
        )
    }
}

package com.tyust.course.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AssignmentInd
import androidx.compose.material.icons.outlined.ContentPasteSearch
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SectionSpacing
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemDestructiveButton
import com.tyust.course.ui.system.SystemDivider
import com.tyust.course.ui.system.SystemListItem
import com.tyust.course.ui.system.SystemSectionHeader
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import com.tyust.course.ui.system.SystemTopBar
import com.tyust.course.ui.theme.SemanticSuccess
import com.tyust.course.ui.theme.SemanticWarning
import com.tyust.course.ui.theme.NeuPrimary

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
    onCredits: () -> Unit,
    onLogout: () -> Unit,
    onQuotaClick: () -> Unit = {},
    onRefreshCookieClick: () -> Unit = {},
    onLogExport: () -> Unit = {},
    onSchoolAdaptation: () -> Unit = {},
    onWallpaperSelect: () -> Unit = {},
    wallpaperName: String = "",
    isSuper: Boolean = false,
    quotaInfo: String = "",
    canRefreshCookie: Boolean = false,
    isRefreshingCookie: Boolean = false
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            SystemTopBar(
                title = "设置",
                subtitle = "账号管理与偏好设置"
            )
        }
    ) { padding ->
        // 内容延伸到玻璃顶栏下方，滚动时从顶栏底下穿过（padding 施加在滚动内容内部）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = PagePadding,
                    end = PagePadding,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = com.tyust.course.ui.system.LocalAppOverlayBottomInset.current + 24.dp
                ),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing)
        ) {
            SettingsHeader(
                name = studentName,
                studentId = studentId,
                school = schoolName,
                isSuper = isSuper,
                quotaInfo = quotaInfo,
                canRefreshCookie = canRefreshCookie,
                isRefreshingCookie = isRefreshingCookie,
                onQuotaClick = onQuotaClick,
                onRefreshCookieClick = onRefreshCookieClick
            )

            SettingsSection(title = "账号与教务") {
                SettingsActionItem(
                    icon = Icons.Outlined.School,
                    title = "学校选择",
                    subtitle = schoolName.ifBlank { "未选择学校" },
                    tintColor = NeuPrimary,
                    onClick = onSchoolSelect
                )
                SystemDivider(modifier = Modifier.padding(start = 68.dp))
                SettingsActionItem(
                    icon = Icons.Outlined.AssignmentInd,
                    title = "配额 / 身份",
                    subtitle = if (isSuper) "超级用户 · 无限制" else quotaInfo.ifBlank { "普通用户" },
                    tintColor = if (isSuper) SemanticSuccess else NeuPrimary,
                    onClick = onQuotaClick
                )
                SystemDivider(modifier = Modifier.padding(start = 68.dp))
                SettingsActionItem(
                    icon = Icons.Outlined.Cookie,
                    title = "凭证管理",
                    subtitle = "更新登录状态与身份信息",
                    tintColor = NeuPrimary,
                    onClick = onCookieConfig
                )
            }

            SettingsSection(title = "外观") {
                SettingsActionItem(
                    icon = Icons.Outlined.Palette,
                    title = "背景颜色",
                    subtitle = wallpaperName.ifBlank { "选择应用背景色" },
                    tintColor = NeuPrimary,
                    onClick = onWallpaperSelect
                )
            }

            SettingsSection(title = "应用与支持") {
                SettingsActionItem(
                    icon = Icons.Outlined.SystemUpdate,
                    title = "检查更新",
                    subtitle = "当前版本 $currentVersion",
                    tintColor = SemanticSuccess,
                    onClick = onCheckUpdate
                )
                SystemDivider(modifier = Modifier.padding(start = 68.dp))
                SettingsActionItem(
                    icon = Icons.Outlined.ContentPasteSearch,
                    title = "导出日志",
                    subtitle = "导出本地运行日志",
                    tintColor = NeuPrimary,
                    onClick = onLogExport
                )
                SystemDivider(modifier = Modifier.padding(start = 68.dp))
                SettingsActionItem(
                    icon = Icons.Outlined.School,
                    title = "统一登录适配",
                    subtitle = "申请学校支持或查看适配进度",
                    tintColor = NeuPrimary,
                    onClick = onSchoolAdaptation
                )
                SystemDivider(modifier = Modifier.padding(start = 68.dp))
                SettingsActionItem(
                    icon = Icons.Outlined.Info,
                    title = "更新历史",
                    subtitle = "应用的更新日志与时间线",
                    tintColor = NeuPrimary,
                    onClick = onAbout
                )
                SystemDivider(modifier = Modifier.padding(start = 68.dp))
                SettingsActionItem(
                    icon = Icons.Outlined.FavoriteBorder,
                    title = "致谢与关于",
                    subtitle = "开源项目致谢与作者声明",
                    tintColor = NeuPrimary,
                    onClick = onCredits
                )
            }

            SettingsSection(title = "数据与安全") {
                SettingsActionItem(
                    icon = Icons.Outlined.Delete,
                    title = "清除缓存",
                    subtitle = "释放本地存储空间",
                    tintColor = SemanticWarning,
                    onClick = onClearCache
                )
            }

            SystemDestructiveButton(
                text = "退出登录",
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )

            Text(
                text = "Tyust Course Matrix · $currentVersion",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun SettingsHeader(
    name: String,
    studentId: String,
    school: String,
    isSuper: Boolean,
    quotaInfo: String,
    canRefreshCookie: Boolean,
    isRefreshingCookie: Boolean,
    onQuotaClick: () -> Unit,
    onRefreshCookieClick: () -> Unit
) {
    SystemCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .padding(2.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .align(Alignment.Center)
                ) {
                    androidx.compose.material3.Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = name.take(1).ifBlank { "同" },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = name.ifBlank { "同学" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = school.ifBlank { "未选择学校" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "设备 ID：${studentId.ifBlank { "未获取" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSuper) {
                SystemStatusBadge(
                    text = "超级用户",
                    tone = SystemTone.Success
                )
            }
            if (quotaInfo.isNotBlank()) {
                Box(modifier = Modifier.clickable(onClick = onQuotaClick)) {
                    SystemStatusBadge(
                        text = if (isSuper) "无限制" else "配额 $quotaInfo",
                        tone = if (isSuper) SystemTone.Info else SystemTone.Neutral
                    )
                }
            }
            if (canRefreshCookie) {
                Box(
                    modifier = Modifier.clickable(
                        enabled = !isRefreshingCookie,
                        onClick = onRefreshCookieClick
                    )
                ) {
                    SystemStatusBadge(
                        text = if (isRefreshingCookie) "更新中" else "更新 Cookie",
                        tone = SystemTone.Info
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SystemSectionHeader(title = title)
        SystemCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaterialTheme.colorScheme.surface,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tintColor: Color,
    onClick: () -> Unit
) {
    SystemListItem(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tintColor,
                modifier = Modifier.size(18.dp)
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    )
}

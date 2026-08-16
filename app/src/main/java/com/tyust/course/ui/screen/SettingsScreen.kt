package com.tyust.course.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AssignmentInd
import androidx.compose.material.icons.outlined.ContentPasteSearch
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.InsetGroupedRow
import com.tyust.course.ui.system.InsetGroupedSection
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SectionSpacing
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import com.tyust.course.ui.system.SystemTopBar
import com.tyust.course.ui.theme.SemanticDanger

@Composable
fun SettingsScreen(
    studentName: String,
    studentId: String,
    schoolName: String,
    currentVersion: String = "1.0.0",
    onSchoolSelect: () -> Unit,
    onCookieConfig: () -> Unit,
    onAccountManage: () -> Unit = {},
    savedAccountCount: Int = 0,
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
    val scrollState = rememberScrollState()
    // 折叠进度随滚动偏移连续变化（约 96px 行程），全程跟手
    val headerCollapse by remember {
        derivedStateOf { (scrollState.value / 96f).coerceIn(0f, 1f) }
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            SystemTopBar(
                title = "设置",
                subtitle = "账号、外观与应用偏好",
                collapseFraction = headerCollapse
            )
        }
    ) { padding ->
        // 内容延伸到玻璃顶栏下方，滚动时从顶栏底下穿过（padding 施加在滚动内容内部）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
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

            InsetGroupedSection(header = "账号与教务") {
                SettingsRow(
                    icon = Icons.Outlined.School,
                    iconTint = Color(0xFF0A84FF),
                    title = "学校选择",
                    subtitle = schoolName.ifBlank { "未选择学校" },
                    onClick = onSchoolSelect
                )
                SettingsRow(
                    icon = Icons.Outlined.AssignmentInd,
                    iconTint = if (isSuper) Color(0xFF34C759) else Color(0xFF5E5CE6),
                    title = "配额 / 身份",
                    subtitle = if (isSuper) "超级用户 · 无限制" else quotaInfo.ifBlank { "普通用户" },
                    onClick = onQuotaClick
                )
                SettingsRow(
                    icon = Icons.Outlined.ManageAccounts,
                    iconTint = Color(0xFF32ADE6),
                    title = "账号管理",
                    subtitle = if (savedAccountCount > 0) {
                        "已保存 $savedAccountCount 个账号 · 可切换或删除"
                    } else {
                        "切换账号、删除已存密码与账号"
                    },
                    onClick = onAccountManage
                )
                SettingsRow(
                    icon = Icons.Outlined.Cookie,
                    iconTint = Color(0xFFFF9F0A),
                    title = "重新登录",
                    subtitle = "退出当前会话并返回登录页（保留已存密码）",
                    onClick = onCookieConfig,
                    showDivider = false
                )
            }

            InsetGroupedSection(header = "外观") {
                SettingsRow(
                    icon = Icons.Outlined.Palette,
                    iconTint = Color(0xFFBF5AF2),
                    title = "背景",
                    subtitle = wallpaperName.ifBlank { "选择背景色或图片" },
                    onClick = onWallpaperSelect,
                    showDivider = false
                )
            }

            InsetGroupedSection(header = "应用与支持") {
                SettingsRow(
                    icon = Icons.Outlined.SystemUpdate,
                    iconTint = Color(0xFF34C759),
                    title = "检查更新",
                    subtitle = "当前版本 $currentVersion",
                    onClick = onCheckUpdate
                )
                SettingsRow(
                    icon = Icons.Outlined.ContentPasteSearch,
                    iconTint = Color(0xFF64D2FF),
                    title = "导出日志",
                    subtitle = "导出本地运行日志",
                    onClick = onLogExport
                )
                SettingsRow(
                    icon = Icons.Outlined.School,
                    iconTint = Color(0xFF0A84FF),
                    title = "统一登录适配",
                    subtitle = "申请学校支持或查看适配进度",
                    onClick = onSchoolAdaptation
                )
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    iconTint = Color(0xFF8E8E93),
                    title = "更新历史",
                    subtitle = "应用的更新日志与时间线",
                    onClick = onAbout
                )
                SettingsRow(
                    icon = Icons.Outlined.FavoriteBorder,
                    iconTint = Color(0xFFFF375F),
                    title = "致谢与关于",
                    subtitle = "开源项目致谢与作者声明",
                    onClick = onCredits,
                    showDivider = false
                )
            }

            InsetGroupedSection(header = "数据与安全") {
                SettingsRow(
                    icon = Icons.Outlined.Delete,
                    iconTint = Color(0xFFFF9F0A),
                    title = "清除缓存",
                    subtitle = "释放本地存储空间",
                    onClick = onClearCache,
                    showDivider = false
                )
            }

            InsetGroupedSection {
                InsetGroupedRow(
                    title = "退出登录",
                    icon = Icons.AutoMirrored.Filled.ExitToApp,
                    iconTint = SemanticDanger,
                    titleColor = SemanticDanger,
                    showDivider = false,
                    onClick = onLogout
                )
            }

            Text(
                text = "正方教务助手 · $currentVersion",
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
    // 裸玻璃容器（不走 Surface，避免 elevation 阴影在半透色下泛白）
    val heroShape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(heroShape)
            .background(Color.White.copy(alpha = 0.62f))
            .border(0.5.dp, Color.White.copy(alpha = 0.55f), heroShape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 渐变光晕头像：外圈柔光环 + 内部彩色渐变圆
            Box(
                modifier = Modifier.size(60.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF0A84FF).copy(alpha = 0.35f),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                )
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(Color(0xFF0A84FF), Color(0xFF5E5CE6))
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name.take(1).ifBlank { "同" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
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

/** 设置行：InsetGroupedRow + 彩色图标 chip + chevron。 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    showDivider: Boolean = true,
    onClick: () -> Unit
) {
    InsetGroupedRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconTint = iconTint,
        showDivider = showDivider,
        onClick = onClick,
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    )
}

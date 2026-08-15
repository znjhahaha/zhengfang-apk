package com.tyust.course.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tyust.course.manager.UserManager
import com.tyust.course.network.AdaptationStatus
import com.tyust.course.network.AdaptedSchoolItem
import com.tyust.course.network.SchoolAdaptationItem
import com.tyust.course.network.SchoolAdaptationManager
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SystemCard
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemEmptyState
import com.tyust.course.ui.system.SystemLoadingState
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSecondaryButton
import com.tyust.course.ui.system.SystemStatusBadge
import com.tyust.course.ui.system.SystemTone
import com.tyust.course.ui.system.SystemIconButton
import com.tyust.course.ui.system.SystemTopBar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun SchoolAdaptationHistoryScreen(
    onNavigateBack: () -> Unit,
    onNewRequest: () -> Unit,
    refreshKey: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<SchoolAdaptationItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var adaptedSchools by remember { mutableStateOf<List<AdaptedSchoolItem>>(emptyList()) }
    var isCatalogLoading by remember { mutableStateOf(true) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var catalogExpanded by remember { mutableStateOf(false) }

    suspend fun loadCatalog() {
        isCatalogLoading = true
        catalogError = null
        SchoolAdaptationManager.getAdaptedSchools()
            .onSuccess { adaptedSchools = it }
            .onFailure { catalogError = "已适配学校暂时无法加载" }
        isCatalogLoading = false
    }

    suspend fun loadRequests() {
        isLoading = true
        errorMessage = null
        SchoolAdaptationManager.getMyRequests(context)
            .onSuccess { items = it }
            .onFailure {
                errorMessage = "加载失败，请检查网络后重试"
            }
        isLoading = false
    }

    LaunchedEffect(refreshKey, "catalog") {
        loadCatalog()
    }
    LaunchedEffect(refreshKey, "requests") {
        loadRequests()
    }

    Scaffold(
        topBar = {
            SystemTopBar(
                title = "统一登录适配",
                subtitle = "查看申请状态和作者回复",
                navigationIcon = {
                    SystemIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        onClick = onNavigateBack
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch { loadCatalog() }
                            scope.launch { loadRequests() }
                        },
                        enabled = !isLoading && !isCatalogLoading
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = PagePadding,
                end = PagePadding,
                top = 20.dp,
                bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                AdaptedSchoolsSection(
                    schools = adaptedSchools,
                    isLoading = isCatalogLoading,
                    errorMessage = catalogError,
                    expanded = catalogExpanded,
                    onToggleExpanded = { catalogExpanded = !catalogExpanded },
                    onRetry = { scope.launch { loadCatalog() } }
                )
            }
            item {
                SystemPrimaryButton(
                    text = if (items.isEmpty()) "提交适配申请" else "提交新申请",
                    onClick = onNewRequest,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }
                )
            }
            item {
                Text(
                    text = "我的申请",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            when {
                isLoading -> item {
                    SystemLoadingState(
                        text = "正在加载适配进度…",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                errorMessage != null -> item {
                    SystemCard {
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        SystemSecondaryButton(
                            text = "重试",
                            onClick = { scope.launch { loadRequests() } }
                        )
                    }
                }
                items.isEmpty() -> item {
                    Text(
                        text = "暂无适配申请。当前学校未列出时，可以提交临时测试账号协助适配。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> items(items, key = { it.id }) { item ->
                    AdaptationHistoryCard(item)
                }
            }
        }
    }
}

@Composable
private fun AdaptedSchoolsSection(
    schools: List<AdaptedSchoolItem>,
    isLoading: Boolean,
    errorMessage: String?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRetry: () -> Unit
) {
    SystemCard {
        Text(
            text = "已适配学校",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "以下学校已完成统一登录验证，不代表使用相同教务系统的其他学校也已适配。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        when {
            isLoading -> SystemLoadingState(
                text = "正在加载学校目录…",
                modifier = Modifier.fillMaxWidth()
            )
            errorMessage != null -> {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                SystemSecondaryButton(text = "重新加载", onClick = onRetry)
            }
            schools.isEmpty() -> Text(
                text = "暂未发布已适配学校。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> {
                val visibleSchools = if (expanded) schools else schools.take(3)
                visibleSchools.forEach { school ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = school.schoolName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (school.description.isNotBlank()) {
                            Text(
                                text = school.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "发布时间：${formatAdaptationTime(school.publishedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (schools.size > 3) {
                    SystemSecondaryButton(
                        text = if (expanded) "收起" else "查看全部（${schools.size}）",
                        onClick = onToggleExpanded
                    )
                }
            }
        }
    }
}

@Composable
fun SchoolAdaptationCompletionReminder(
    enabled: Boolean = true,
    accountScopeKey: String = UserManager.getInstance().currentAccountStorageKey
) {
    val context = LocalContext.current
    var pendingItems by remember(accountScopeKey) {
        mutableStateOf<List<SchoolAdaptationItem>>(emptyList())
    }
    var requestCompleted by remember(accountScopeKey) { mutableStateOf(false) }

    LaunchedEffect(enabled, accountScopeKey, requestCompleted) {
        if (!enabled || requestCompleted) return@LaunchedEffect
        SchoolAdaptationManager.getMyRequests(context).onSuccess { items ->
            pendingItems = SchoolAdaptationManager.pendingPasswordChangeItems(context, items)
        }
        requestCompleted = true
    }

    if (enabled && pendingItems.isNotEmpty()) {
        val schoolNames = pendingItems.map { it.schoolName }.distinct().joinToString("、")
        SystemDialog(
            onDismissRequest = { pendingItems = emptyList() },
            useVisualEffects = false,
            title = {
                Text(
                    text = "适配已完成",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            dismissButton = {
                SystemSecondaryButton(
                    text = "稍后提醒",
                    onClick = { pendingItems = emptyList() },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                SystemPrimaryButton(
                    text = "我已修改密码",
                    onClick = {
                        SchoolAdaptationManager.markPasswordChanged(
                            context,
                            pendingItems.map { it.id }
                        )
                        pendingItems = emptyList()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Text(
                text = "$schoolNames 的统一登录适配已完成。请立即修改提交时使用的学校账号密码，避免临时密码继续有效。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AdaptationHistoryCard(item: SchoolAdaptationItem) {
    SystemCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.schoolName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = formatAdaptationTime(item.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            SystemStatusBadge(
                text = item.status.label,
                tone = item.status.toTone()
            )
        }

        if (item.academicSystemUrl.isNotBlank()) {
            Text(
                text = "教务地址：${item.academicSystemUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (item.ssoUrl.isNotBlank()) {
            Text(
                text = "统一认证：${item.ssoUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!item.replyMessage.isNullOrBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "作者回复",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = item.replyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!item.repliedAt.isNullOrBlank()) {
                    Text(
                        text = formatAdaptationTime(item.repliedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        when (item.status) {
            AdaptationStatus.WAITING_FOR_USER -> Text(
                text = "作者需要补充信息，请通过提交时填写的联系方式沟通。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )

            AdaptationStatus.COMPLETED -> Text(
                text = "适配已完成，请立即修改学校账号密码。",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )

            else -> Unit
        }
    }
}

private fun AdaptationStatus.toTone(): SystemTone = when (this) {
    AdaptationStatus.COMPLETED -> SystemTone.Success
    AdaptationStatus.UNSUPPORTED -> SystemTone.Danger
    AdaptationStatus.WAITING_FOR_USER -> SystemTone.Warning
    AdaptationStatus.SUBMITTED,
    AdaptationStatus.PENDING_VERIFICATION,
    AdaptationStatus.AUTHOR_REPLIED -> SystemTone.Neutral
    else -> SystemTone.Info
}

private fun formatAdaptationTime(value: String?): String {
    if (value.isNullOrBlank()) return ""
    return runCatching {
        val input = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val output = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        input.parse(value.replace("Z", "").substringBefore('.'))?.let(output::format)
    }.getOrNull() ?: value.take(16).replace("T", " ")
}
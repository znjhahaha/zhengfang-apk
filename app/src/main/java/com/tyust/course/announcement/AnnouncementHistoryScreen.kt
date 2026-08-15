package com.tyust.course.announcement

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.announcement.AnnouncementManager
import com.tyust.course.announcement.AnnouncementDialog
import com.tyust.course.ui.system.SystemIconButton
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementHistoryScreen(
    onBack: () -> Unit
) {
    var announcements by remember { mutableStateOf<List<AnnouncementManager.Announcement>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedAnnouncement by remember { mutableStateOf<AnnouncementManager.Announcement?>(null) }
    
    LaunchedEffect(Unit) {
        announcements = AnnouncementManager.fetchAllAnnouncements()
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("公告历史", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    SystemIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        onClick = onBack
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White,
                    titleContentColor = Color.Black
                )
            )
        },
        containerColor = Color(0xFFF8F9FA)
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (announcements.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Campaign,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("暂无公告历史", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(announcements) { ann ->
                        AnnouncementItem(ann) {
                            selectedAnnouncement = ann
                        }
                    }
                }
            }
        }
    }

    // 复用已优化好的弹窗查看详情
    selectedAnnouncement?.let { ann ->
        AnnouncementDialog(
            announcement = ann,
            onDismiss = { selectedAnnouncement = null }
        )
    }
}

@Composable
fun AnnouncementItem(
    announcement: AnnouncementManager.Announcement,
    onClick: () -> Unit
) {
    val (primaryColor, secondaryColor, icon) = when (announcement.type) {
        "warning" -> Triple(Color(0xFFFF9800), Color(0xFFFFF3E0), Icons.Default.Warning)
        "important" -> Triple(Color(0xFFF44336), Color(0xFFFFEBEE), Icons.Default.Campaign)
        else -> Triple(Color(0xFF2196F3), Color(0xFFE3F2FD), Icons.Default.Info)
    }

    // 解析日期：ID 格式通常为 YYYYMMDD_HHMMSS_mmm
    val dateStr = try {
        val rawDate = announcement.id.split("_")[0]
        if (rawDate.length == 8) {
            "${rawDate.substring(0, 4)}-${rawDate.substring(4, 6)}-${rawDate.substring(6, 8)}"
        } else {
            ""
        }
    } catch (e: Exception) {
        ""
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(secondaryColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1.0f)) {
                Text(
                    text = announcement.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1A1A)
                )
                if (dateStr.isNotEmpty()) {
                    Text(
                        text = dateStr,
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

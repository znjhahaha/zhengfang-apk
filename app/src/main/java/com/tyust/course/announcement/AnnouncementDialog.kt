package com.tyust.course.announcement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.Material3RichText
import com.tyust.course.ui.system.LiquidButton
import com.tyust.course.ui.system.LiquidButtonStyle
import com.tyust.course.ui.system.SystemDialog
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * 公告弹窗（玻璃 SystemDialog 版），按公告类型着色。
 */
@Composable
fun AnnouncementDialog(
    announcement: AnnouncementManager.Announcement,
    onDismiss: () -> Unit
) {
    // 根据类型选择颜色和图标
    val (primaryColor, secondaryColor, icon) = when (announcement.type) {
        "warning" -> Triple(Color(0xFFFF9800), Color(0xFFFFF3E0), Icons.Default.Warning)
        "important" -> Triple(Color(0xFFF44336), Color(0xFFFFEBEE), Icons.Default.Campaign)
        else -> Triple(Color(0xFF2196F3), Color(0xFFE3F2FD), Icons.Default.Info)
    }

    SystemDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(
                shape = CircleShape,
                color = secondaryColor,
                shadowElevation = 4.dp,
                modifier = Modifier.size(72.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = primaryColor
                    )
                }
            }
        },
        title = {
            Text(
                text = announcement.title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                letterSpacing = 0.5.sp
            )
        },
        confirmButton = {
            LiquidButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                style = LiquidButtonStyle.SolidTinted,
                tint = primaryColor,
                shape = RoundedCornerShape(16.dp),
                cornerRadius = 16.dp
            ) {
                Text(
                    text = "确认",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    ) {
        // 可滚动的正文内容，限制最大高度
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (announcement.contentType == "markdown") {
                Material3RichText(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                ) {
                    Markdown(content = announcement.content)
                }
            } else {
                Text(
                    text = announcement.content,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    lineHeight = 26.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                )
            }
        }
    }
}

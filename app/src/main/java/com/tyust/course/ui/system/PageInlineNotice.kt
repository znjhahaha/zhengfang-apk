package com.tyust.course.ui.system

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Immutable
data class PageInlineNotice(
    val message: String,
    val onClick: () -> Unit
)

val LocalPageInlineNotice = staticCompositionLocalOf<PageInlineNotice?> { null }

/**
 * 由页面顶栏在自身内容之后承载，出现时参与测量并推开正文，
 * 不再以全局浮层覆盖顶栏操作区。
 */
@Composable
fun PageInlineNoticeHost(modifier: Modifier = Modifier) {
    val notice = LocalPageInlineNotice.current
    var retainedNotice by remember { mutableStateOf(notice) }

    LaunchedEffect(notice) {
        if (notice != null) retainedNotice = notice
    }

    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        modifier = modifier.fillMaxWidth()
    ) {
        retainedNotice?.let { currentNotice ->
            LiquidButton(
                onClick = currentNotice.onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = LiquidButtonStyle.Surface,
                shape = RoundedCornerShape(16.dp),
                cornerRadius = 16.dp,
                minHeight = 44.dp,
                horizontalPadding = 14.dp,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp)
                )
                Text(
                    text = currentNotice.message,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
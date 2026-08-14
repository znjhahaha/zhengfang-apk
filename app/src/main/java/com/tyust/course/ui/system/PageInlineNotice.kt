package com.tyust.course.ui.system

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.tyust.course.ui.theme.SemanticWarning

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
            NoticeStrip(
                message = currentNotice.message,
                onClick = currentNotice.onClick
            )
        }
    }
}

/**
 * 通知条自成一档材质：语义色薄玻璃，没有按钮的实心表面和投影，
 * 因此不会在顶栏下方形成一圈灰壳。
 */
@Composable
private fun NoticeStrip(
    message: String,
    onClick: () -> Unit
) {
    val isLightTheme = !isSystemInDarkTheme()
    val shape = RoundedCornerShape(13.dp)
    val backdrop = LocalControlBackdrop.current?.takeIf { isBackdropSupported() }
    val surfaceColor = SemanticWarning.copy(alpha = if (isLightTheme) 0.14f else 0.20f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            vibrancy()
                            blur(5.dp.toPx())
                        },
                        onDrawSurface = { drawRect(surfaceColor) }
                    )
                } else {
                    Modifier
                        .clip(shape)
                        .background(
                            Color.White.copy(alpha = if (isLightTheme) 0.72f else 0.10f)
                        )
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .heightIn(min = 38.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = SemanticWarning,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
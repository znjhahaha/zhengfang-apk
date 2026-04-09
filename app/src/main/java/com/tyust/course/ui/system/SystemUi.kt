package com.tyust.course.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.theme.SystemBlue
import com.tyust.course.ui.theme.Neutral50
import com.tyust.course.ui.theme.Neutral100
import com.tyust.course.ui.theme.Neutral200
import com.tyust.course.ui.theme.Neutral500
import com.tyust.course.ui.theme.Neutral700
import com.tyust.course.ui.theme.Neutral900
import com.tyust.course.ui.theme.SurfaceWhite

// 1. SystemTopBar (紧凑、极简、无底色大字标头)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemTopBar(
    title: String,
    subtitle: String? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Column {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                if (subtitle != null) {
                    Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = Neutral500)
                }
            }
        },
        navigationIcon = navigationIcon ?: {},
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = SurfaceWhite,
            titleContentColor = Neutral900,
            actionIconContentColor = Neutral900
        )
    )
}

// 2. SystemCard (极度趋于边距的、弱圆角、带细分割线的块区，取消阴影投影)
@Composable
fun SystemCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = SurfaceWhite,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val mod = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else {
        modifier
    }
    
    Card(
        modifier = mod.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp), // iOS Inset Grouped 圆角加大
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

// 3. SystemListItem (Things 3 风格列表项，Edge-to-Edge 宽，保证高度 >= 48dp)
@Composable
fun SystemListItem(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp) // ui-ux-pro-max: 最低 48dp 触控标准
            .background(SurfaceWhite)
            .clickable(enabled = onClick != null, onClick = onClick ?: {})
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Box(modifier = Modifier.padding(end = 12.dp)) {
                leadingIcon()
            }
        }
        
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = Neutral900)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = Neutral500)
            }
        }
        
        if (trailingContent != null) {
            trailingContent()
        }
    }
}

// 4. SystemDivider (通用的中性极细分隔线)
@Composable
fun SystemDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = 0.5.dp, color = Neutral200.copy(alpha=0.6f))
}

// 5. SystemStatStrip (展示数据汇总的 Terminal 横条，GrabPro 或成绩单上方使用)
@Composable
fun SystemStatStrip(
    modifier: Modifier = Modifier,
    items: List<Pair<String, String>>
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Neutral50)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (label, value) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Neutral900)
                Text(text = label, style = MaterialTheme.typography.labelSmall, color = Neutral500)
            }
        }
    }
}

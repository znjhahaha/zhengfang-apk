package com.tyust.course.ui.screen

import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tyust.course.schedule.*
import com.tyust.course.ui.system.SystemDialog
import java.util.Calendar

@Composable
fun ScheduleWidgetPicker(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val preview = remember {
        val monday = ScheduleDates.mondayOfWeek(System.currentTimeMillis())
        val now = (monday.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 10) }.timeInMillis
        val base = ScheduleTimeBase(ScheduleTimeBase.dateFromMillis(monday.timeInMillis),
            mapOf(1 to "08:00", 2 to "10:00", 3 to "14:00"), mapOf(1 to "09:40", 2 to "11:40", 3 to "15:40"))
        val courses = listOf(
            ScheduleCourseRecord("sample-1", "高等数学", "张老师", "博学楼 A205", 1, 1, 1, "1-16周"),
            ScheduleCourseRecord("sample-2", "计算机网络", "李老师", "明理楼 B302", 1, 2, 2, "1-16周"),
            ScheduleCourseRecord("sample-3", "大学英语", "陈老师", "博学楼 A102", 1, 3, 3, "1-16周"))
        ScheduleWidgetState.from(ScheduleSnapshot("", "", "", courses, base, now, true), now)
    }
    SystemDialog(onDismissRequest = onDismiss, title = { Text("选择桌面组件") }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("样式预览 · 示例课程。添加后显示当前账号的本地课表，随浅深色切换。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (style in ScheduleWidgetStyle.entries) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(style.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { ScheduleWidgetUpdater.requestPin(context, style); onDismiss() },
                        modifier = Modifier.semantics { contentDescription = "添加${style.title}" }) { Text("添加到桌面") }
                }
                Text(style.description, style = MaterialTheme.typography.bodySmall)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val width = minOf(maxWidth.value.toInt(), if (style == ScheduleWidgetStyle.Single) 88 else 176)
                    val height = if (style == ScheduleWidgetStyle.Double) width / 2 else width
                    AndroidView(factory = { ctx -> FrameLayout(ctx) }, modifier = Modifier.width(width.dp).height(height.dp),
                        update = { host ->
                            host.removeAllViews()
                            val themed = com.tyust.course.manager.AppThemeCoordinator.wrapContext(host.context)
                            host.addView(ScheduleWidgetRenderer.views(themed, preview, width, height, style).apply(themed, host),
                                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                            // Preview cards have no navigation; only the explicit add button acts.
                            fun disable(view: android.view.View) {
                                view.isClickable = false
                                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) disable(view.getChildAt(i))
                            }
                            disable(host)
                        })
                }
            }
            Text("三种样式均完整显示课名与教师，并保留时间和地点。长按组件调大尺寸，文字更舒展，还可显示结束时间和更多课程。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

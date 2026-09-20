package com.tyust.course.ui.screen

import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tyust.course.schedule.*
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemPrimaryButton
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
            ScheduleCourseRecord("sample-1", "高等数学", "", "博学楼 A205", 1, 1, 1, "1-16周"),
            ScheduleCourseRecord("sample-2", "计算机网络", "", "明理楼 B302", 1, 2, 2, "1-16周"),
            ScheduleCourseRecord("sample-3", "大学英语", "", "博学楼 A102", 1, 3, 3, "1-16周"))
        ScheduleWidgetState.from(ScheduleSnapshot("", "", "", courses, base, now, true), now)
    }
    SystemDialog(onDismissRequest = onDismiss, title = { Text("选择桌面组件") }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("样式预览 · 示例课程。添加后显示当前账号的本地课表，随浅深色切换。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (style in ScheduleWidgetStyle.entries) {
                Text(style.title, style = MaterialTheme.typography.titleMedium)
                Text(style.description, style = MaterialTheme.typography.bodySmall)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val width = maxWidth.value.toInt()
                    val height = if (style == ScheduleWidgetStyle.Timeline) 240 else 150
                    AndroidView(factory = { ctx -> FrameLayout(ctx) }, modifier = Modifier.fillMaxWidth().height(height.dp),
                        update = { host ->
                            host.removeAllViews()
                            host.addView(ScheduleWidgetRenderer.views(host.context, preview, width, height, style).apply(host.context, host))
                            // Preview cards have no navigation; only the explicit add button acts.
                            fun disable(view: android.view.View) {
                                view.isClickable = false
                                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) disable(view.getChildAt(i))
                            }
                            disable(host)
                        })
                }
                SystemPrimaryButton("添加${style.title}", { ScheduleWidgetUpdater.requestPin(context, style); onDismiss() }, Modifier.fillMaxWidth())
            }
        }
    }
}

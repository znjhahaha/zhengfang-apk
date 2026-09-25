package com.tyust.course.ui.screen

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tyust.course.schedule.*
import com.tyust.course.ui.system.*

@Composable
fun SemesterReminderSection(accountLabel: String, term: String, summary: SemesterReminderSummary,
    canChange: Boolean, onChange: (Boolean) -> Unit, onConfigureTime: () -> Unit) {
    val context = LocalContext.current
    val scheduler = remember(context) { ScheduleReminderScheduler.get(context) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { scheduler.reconcile() }
    InsetGroupedSection(header = "课前提醒", footer = "仅设置这个账号所选学期的完整课表，包含手动课程。新课程默认关闭，之后可逐门调整。") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("账号：$accountLabel", style = MaterialTheme.typography.bodyMedium)
            Text("学期：${term.ifBlank { "尚未加载" }} · 已开启 ${summary.enabled}/${summary.total} 门", style = MaterialTheme.typography.bodyMedium)
            val details = listOf(
                ReminderAvailability.Scheduled to "已安排", ReminderAvailability.NeedsPermission to "待授权",
                ReminderAvailability.NeedsTime to "待补全时间", ReminderAvailability.InvalidWeeks to "周次待核对",
                ReminderAvailability.NoUpcoming to "没有后续课次", ReminderAvailability.InactiveAccount to "账号未激活"
            ).mapNotNull { (state, label) -> summary.count(state).takeIf { it > 0 }?.let { "$label $it 门" } }
            if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (!canChange) Text("当前学期课表加载完成且有课程后可批量设置。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SystemPrimaryButton(text = "全部开启", onClick = { onChange(true) }, enabled = canChange, modifier = Modifier.weight(1f))
                SystemSecondaryButton(text = "全部关闭", onClick = { onChange(false) }, enabled = canChange, modifier = Modifier.weight(1f))
            }
            if (summary.count(ReminderAvailability.NeedsPermission) > 0) SystemSecondaryButton(
                text = "处理通知与闹钟权限", onClick = {
                    val permissions = scheduler.permissions()
                    if (!permissions.notifications && Build.VERSION.SDK_INT >= 33 &&
                        androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        val intent = if (!permissions.exactAlarms && Build.VERSION.SDK_INT >= 31)
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                        else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        runCatching { context.startActivity(intent) }
                    }
                }, modifier = Modifier.fillMaxWidth())
            if (summary.count(ReminderAvailability.NeedsTime) > 0) SystemSecondaryButton(
                text = "补全学期日期与节次时间", onClick = onConfigureTime, modifier = Modifier.fillMaxWidth())
        }
    }
}

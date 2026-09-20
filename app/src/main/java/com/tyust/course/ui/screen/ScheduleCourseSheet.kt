package com.tyust.course.ui.screen

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tyust.course.schedule.*
import com.tyust.course.ui.system.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScheduleCourseSheet(course: ScheduleCourseUi, account: String, term: String,
    allCourses: List<ScheduleCourseUi>, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    onConfigureTime: () -> Unit, sourceCenterX: Float? = null, sourceBounds: Rect? = null) {
    val host = LocalDialogHost.current
    val ownHost = rememberDialogHostState()
    val targetHost = host ?: ownHost
    val sheet = remember(course.id) { ScheduleBottomSheetState() }
    SideEffect { sheet.sourceBounds = sourceBounds }
    var handle by remember { mutableStateOf<DialogHandle?>(null) }
    var deleteAfterExit by remember(course.id) { mutableStateOf(false) }
    val close = { targetHost.dismiss(handle) }
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentDelete by rememberUpdatedState(onDelete)
    val body: @Composable () -> Unit = {
        ScheduleCourseSheetContent(course, account, term, allCourses, sheet, close, onEdit,
            { deleteAfterExit = true; close() }, onConfigureTime, sourceCenterX)
    }
    val currentBody by rememberUpdatedState(body)
    DisposableEffect(targetHost, course.id) {
        val owner = targetHost.show({
            if (deleteAfterExit) { deleteAfterExit = false; currentDelete() } else currentDismiss()
        }, DialogPresentation.Bottom, bottomSheet = sheet,
            saveableKey = "schedule-detail:$account:$term:${course.id}") { currentBody() }
        handle = owner
        onDispose { targetHost.dismiss(owner, notify = false) }
    }
    if (host == null) Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogHost(ownHost)
    }
}

@Composable
private fun ScheduleCourseSheetContent(course: ScheduleCourseUi, account: String, term: String,
    allCourses: List<ScheduleCourseUi>, state: ScheduleBottomSheetState, close: () -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit, onConfigureTime: () -> Unit, sourceCenterX: Float?) {
    val context = LocalContext.current
    val scheduler = remember(context) { ScheduleReminderScheduler.get(context) }
    val revision = scheduler.revision
    val key = remember(account, term, course.id) { CourseReminderKey(account, term, course.id) }
    val record = remember(key, revision) { scheduler.find(key) }
    val status = remember(key, revision) { scheduler.status(key) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { scheduler.reconcile() }
    val conflicts = remember(course, allCourses) { scheduleConflicts(course.record(), allCourses.map { it.record() }) }
    val timeRange = remember(account, term, course, revision) {
        val base = scheduler.timeBase(account, term)
        val times = com.tyust.course.manager.ScheduleSettingsManager.getInstance().getPeriodTimes(account)
        val start = base?.periodStarts?.get(course.startPeriod) ?: times.firstOrNull { it.period == course.startPeriod }?.startTime
        val end = base?.periodEnds?.get(course.endPeriod) ?: times.firstOrNull { it.period == course.endPeriod }?.endTime
        if (start.isNullOrBlank() || end.isNullOrBlank()) "" else "$start–$end"
    }
    val description = when (status.availability) {
        ReminderAvailability.Off -> if (term.isBlank()) "学期信息加载后可设置提醒" else "开启后，在上课前通知你"
        ReminderAvailability.NeedsPermission -> "待授权：允许通知和精确闹钟后生效"
        ReminderAvailability.NeedsTime -> "待补全本学期第一周周一日期或节次时间"
        ReminderAvailability.InvalidWeeks -> "周次待核对"
        ReminderAvailability.Scheduled -> "下次提醒：" + SimpleDateFormat("M月d日 E HH:mm", Locale.CHINA).format(Date(status.next!!.triggerAt))
        ReminderAvailability.NoUpcoming -> "本学期没有后续课次"
        ReminderAvailability.InactiveAccount -> "当前账号暂未安排提醒"
    }
    CourseDetailContent(
        CourseDetailUiState(course, conflicts, record?.enabled == true, term.isNotBlank(), description,
            record?.enabled == true && status.availability == ReminderAvailability.NeedsPermission,
            status.availability == ReminderAvailability.NeedsTime, !ScheduleWeeks.parse(course.weeks).valid, sourceCenterX, timeRange),
        state, close, onReminderChanged = { scheduler.setEnabled(key, course.record(), it) },
        onPermission = {
            val permissions = scheduler.permissions()
            if (!permissions.notifications && Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                val intent = if (!permissions.exactAlarms && Build.VERSION.SDK_INT >= 31)
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                else if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }
            }
        }, onConfigureTime, onEdit, onDelete
    )
}

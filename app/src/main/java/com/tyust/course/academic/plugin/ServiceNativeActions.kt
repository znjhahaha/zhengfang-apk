package com.tyust.course.academic.plugin

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.academic.AcademicSession
import kotlinx.coroutines.*
import org.json.JSONObject

internal data class NativeTicket(val operation: JSONObject, val params: JSONObject, val session: AcademicSession, val epoch: Long)
internal class ServiceNativeController(private val runtime: ServicePluginSession) {
    var active by mutableStateOf<NativeTicket?>(null)
    var confirming by mutableStateOf(false)
    val busy get() = active != null
    fun isCurrent(ticket: NativeTicket): Boolean = active === ticket && runtime.session === ticket.session && runtime.session.epoch == ticket.epoch && runCatching { runtime.requireActive() }.isSuccess
    fun request(link: JSONObject) {
        check(active == null) { "请先完成当前系统操作" }
        runtime.requireActive()
        val operation = ServiceNativePolicy.request(runtime.pkg.manifest, link)
        active = NativeTicket(operation, JSONObject(link.optJSONObject("params")?.toString() ?: "{}"), runtime.session, runtime.session.epoch)
        confirming = true
    }
}

@Composable
internal fun rememberServiceNativeActions(runtime: ServicePluginSession, onResult: (JSONObject) -> Unit, onMessage: (String) -> Unit): ServiceNativeController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(runtime) { ServiceNativeController(runtime) }
    val deliver by rememberUpdatedState(onResult)
    val message by rememberUpdatedState(onMessage)
    DisposableEffect(controller) { onDispose { controller.active = null; controller.confirming = false } }
    fun valid(ticket: NativeTicket): Boolean = controller.isCurrent(ticket)
    fun finish(ticket: NativeTicket, status: String, data: JSONObject = JSONObject()) {
        val live = valid(ticket)
        if (controller.active === ticket) { controller.active = null; controller.confirming = false }
        if (live) deliver(data.put("operationId", ticket.operation.getString("id")).put("status", status))
        else message("账号或服务会话已改变，本次结果已丢弃")
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        controller.active?.takeIf { it.operation.getString("kind") == "scanCode" }?.let { ticket ->
            val text = result.contents
            when { text == null -> finish(ticket, "cancelled"); text.length > 4000 -> finish(ticket, "error"); else -> finish(ticket, "success", JSONObject().put("text", text)) }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        controller.active?.takeIf { it.operation.getString("kind") == "pickFile" }?.let { ticket ->
            if (uri == null) finish(ticket, "cancelled")
            else if (!valid(ticket)) finish(ticket, "cancelled")
            else scope.launch {
                try {
                    val data = withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
                        val type = resolver.getType(uri).orEmpty()
                        require(type in PluginJson.strings(ticket.operation.getJSONArray("mimeTypes"))) { "所选文件类型不匹配" }
                        var name = "所选文件"
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) require(cursor.getLong(sizeColumn) in 0..ServiceNativePolicy.FILE_BYTES.toLong()) { "文件不能超过 64 KiB" }
                                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (nameColumn >= 0) name = cursor.getString(nameColumn).orEmpty().take(200)
                            }
                        }
                        val bytes = resolver.openInputStream(uri)?.use { it.readBytesBounded(ServiceNativePolicy.FILE_BYTES) } ?: error("无法读取所选文件")
                        JSONObject().put("name", name).put("mimeType", type).put("size", bytes.size).put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    }
                    finish(ticket, "success", data)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message(e.message ?: "文件读取失败"); finish(ticket, "error") }
            }
        }
    }
    fun notify(ticket: NativeTicket) {
        if (!valid(ticket)) { finish(ticket, "cancelled"); return }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) { finish(ticket, "unavailable"); return }
        val channelId = "plugin-${runtime.pkg.manifest.id}"
        if (Build.VERSION.SDK_INT >= 26) {
            val platform = context.getSystemService(NotificationManager::class.java)
            platform.createNotificationChannel(NotificationChannel(channelId, runtime.pkg.manifest.name, NotificationManager.IMPORTANCE_DEFAULT))
            if (platform.getNotificationChannel(channelId).importance == NotificationManager.IMPORTANCE_NONE) { finish(ticket, "unavailable"); return }
        }
        val intent = Intent(context, ServicePluginActivity::class.java).putExtra("pluginId", runtime.pkg.manifest.id)
            .putExtra("preview", !runtime.pkg.official).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, runtime.pkg.manifest.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channelId).setSmallIcon(com.tyust.course.R.drawable.ic_course_reminder)
            .setContentTitle(ticket.params.getString("title")).setContentText(ticket.params.getString("body"))
            .setSubText(runtime.pkg.manifest.name).setStyle(NotificationCompat.BigTextStyle().bigText(ticket.params.getString("body")))
            .setContentIntent(pending).setAutoCancel(true).build()
        try { manager.notify(runtime.pkg.manifest.id, ticket.operation.getString("id").hashCode(), notification); finish(ticket, "success") }
        catch (_: SecurityException) { finish(ticket, "unavailable") }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.active?.takeIf { it.operation.getString("kind") == "notification" }?.let { if (granted) notify(it) else finish(it, "cancelled") }
    }
    fun launch(ticket: NativeTicket) {
        controller.confirming = false
        if (!valid(ticket)) { finish(ticket, "cancelled"); return }
        try {
            when (ticket.operation.getString("kind")) {
                "scanCode" -> scanner.launch(ScanOptions().setPrompt("${runtime.pkg.manifest.name} · 扫码结果将交给此插件").setBeepEnabled(false).setOrientationLocked(true))
                "pickFile" -> filePicker.launch(PluginJson.strings(ticket.operation.getJSONArray("mimeTypes")).toTypedArray())
                "notification" -> if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else notify(ticket)
                "calendar" -> {
                    val params = ticket.params
                    val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, params.getString("title"))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ServiceNativePolicy.instant(params.getString("startAt")))
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ServiceNativePolicy.instant(params.getString("endAt")))
                        .putExtra(CalendarContract.Events.EVENT_LOCATION, params.optString("location"))
                        .putExtra(CalendarContract.Events.DESCRIPTION, params.optString("description"))
                    context.startActivity(intent)
                    finish(ticket, "opened")
                }
            }
        } catch (_: android.content.ActivityNotFoundException) { finish(ticket, "unavailable") }
        catch (e: Exception) { message(e.message ?: "系统操作未完成"); finish(ticket, "error") }
    }
    if (controller.confirming) controller.active?.let { ticket ->
        val kind = ticket.operation.getString("kind")
        SystemDialog(onDismissRequest = { finish(ticket, "cancelled") }, title = { Text(ticket.operation.getString("title")) },
            content = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${runtime.pkg.manifest.name} 请求使用此能力。")
                Text(ticket.operation.getString("reason"))
                Text(when (kind) {
                    "scanCode" -> "使用相机扫描，识别文字会交给此插件；不会自动打开扫码链接。"
                    "pickFile" -> "仅将你选中的文件名称和内容交给此插件，最大 64 KiB。请确认文件适合分享；插件后续仍受已声明的网络权限约束。"
                    "notification" -> "发送一条通知：\n${ticket.params.getString("title")}\n${ticket.params.getString("body")}"
                    else -> "打开系统日历编辑：\n${ticket.params.getString("title")}\n${ticket.params.getString("startAt")} — ${ticket.params.getString("endAt")}\n是否保存由你在日历中确认。"
                }, style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton({ launch(ticket) }, modifier = Modifier.testTag("service-native-allow")) { Text("允许一次") } },
            dismissButton = { TextButton({ finish(ticket, "cancelled") }, modifier = Modifier.testTag("service-native-deny")) { Text("不允许") } })
    }
    return controller
}

package com.tyust.course.academic.plugin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/** A single visible foreground service owns all explicitly started continuous work. */
internal object PluginForegroundWork {
    data class Entry(val pluginId: String, val title: String, val stop: (String) -> Unit)
    private val entries = ConcurrentHashMap<String, Entry>()
    val revision = MutableStateFlow(0L)
    fun items() = entries.values.toList()
    @Synchronized fun changed() { revision.value++ }
    fun add(app: Context, handle: String, entry: Entry) {
        entries[handle] = entry
        try { ContextCompat.startForegroundService(app, Intent(app, PluginForegroundService::class.java)) }
        catch (e: Exception) { entries.remove(handle); throw PluginException(PluginErrorCode.PERMISSION_DENIED, "系统未允许持续任务，请回到插件页面重试") }
        changed()
    }
    fun remove(app: Context, handle: String) {
        entries.remove(handle); changed()
        // Refresh only an existing service; never start one from the background after a stop.
        PluginForegroundService.current?.refresh()
    }
    fun busy(pluginId: String) = entries.values.any { it.pluginId == pluginId }
    fun stopPlugin(pluginId: String, reason: String = "stopped") { entries.values.filter { it.pluginId == pluginId }.toList().forEach { it.stop(reason) } }
    fun stopAll(reason: String) { entries.values.toList().forEach { it.stop(reason) } }
}

class PluginForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); current = this
        if (android.os.Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "插件持续任务", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) PluginForegroundWork.stopAll("stopped")
        refresh()
        return START_NOT_STICKY
    }
    internal fun refresh() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(mainLooper).post { refresh() }; return
        }
        val entries = PluginForegroundWork.items()
        if (entries.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        val open = PendingIntent.getActivity(this, 9101, Intent(this, NativePluginActivity::class.java)
            .putExtra("pluginId", entries.first().pluginId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 9102, Intent(this, PluginForegroundService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(com.tyust.course.R.drawable.ic_course_reminder)
            .setContentTitle("${entries.size} 个插件任务正在运行").setContentText(entries.joinToString(" · ") { it.title }.take(160))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "停止全部", stop).build()
        startForeground(9100, notification)
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:plugin-continuous").apply {
                setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
            }
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) { PluginForegroundWork.stopAll("interrupted"); stopSelf() }
    override fun onDestroy() {
        current = null
        PluginForegroundWork.stopAll("interrupted")
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null; super.onDestroy()
    }
    companion object { internal var current: PluginForegroundService? = null; private const val CHANNEL = "plugin-continuous"; private const val STOP = "com.tyust.course.plugin.STOP_CONTINUOUS" }
}

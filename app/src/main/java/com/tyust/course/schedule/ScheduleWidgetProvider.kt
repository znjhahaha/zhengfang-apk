package com.tyust.course.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import com.tyust.course.R
import com.tyust.course.manager.AppThemeCoordinator
import com.tyust.course.manager.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

open class ScheduleWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = ScheduleWidgetUpdater.update(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = ScheduleWidgetUpdater.update(context)
    override fun onDeleted(context: Context, ids: IntArray) = ScheduleWidgetUpdater.update(context)
    override fun onDisabled(context: Context) = ScheduleWidgetUpdater.update(context)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in setOf(ScheduleWidgetUpdater.ACTION_REFRESH, Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_DATE_CHANGED)) ScheduleWidgetUpdater.update(context)
    }
}

class ScheduleSingleWidgetProvider : ScheduleWidgetProvider()
class ScheduleTimelineWidgetProvider : ScheduleWidgetProvider()

enum class ScheduleWidgetStyle(val title: String, val description: String, val provider: Class<out AppWidgetProvider>) {
    Single("简洁单课", "1×1 · 完整课名与教师，保留时间、地点", ScheduleSingleWidgetProvider::class.java),
    Double("双课程", "2×1 · 两课并排，课名、教师完整显示", ScheduleWidgetProvider::class.java),
    Timeline("课程时间轴", "2×2 · 完整课名与教师，拉大查看更多课程", ScheduleTimelineWidgetProvider::class.java)
}

/** Only local cache reads and an inexact, non-wakeup boundary alarm; never performs authentication. */
object ScheduleWidgetUpdater {
    const val ACTION_REFRESH = "com.tyust.course.action.REFRESH_SCHEDULE_WIDGET"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private val observed = mutableListOf<Pair<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>>()

    fun requestPin(context: Context, style: ScheduleWidgetStyle = ScheduleWidgetStyle.Double) {
        val manager = AppWidgetManager.getInstance(context)
        if (android.os.Build.VERSION.SDK_INT >= 26 && manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(context, style.provider), null, null)
        } else com.tyust.course.ui.system.GlassToaster.show("长按桌面，在小组件中添加“${style.title}”")
    }

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val refresh = Runnable { update(app) }
        for (name in listOf("schedule_cache", "schedule_settings", "course_reminders", "course_selector_prefs", "appearance_settings")) {
            val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                handler.removeCallbacks(refresh)
                handler.postDelayed(refresh, 100)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            observed += prefs to listener
        }
        scope.launch { UserManager.getInstance().sessionState.state.collect { update(app) } }
    }

    internal fun activeSnapshot(context: Context): ScheduleSnapshot? {
        val user = UserManager.getInstance()
        val session = user.sessionState.state.value
        val school = user.currentSchool ?: return null
        if (user.isDemoMode || (!user.isLoggedIn && !(session.expired && user.hasSavedCookie())) ||
            session.token.accountStorageKey != user.currentAccountStorageKey ||
            user.savedAccounts.none { it.key == user.currentAccountKey }) return null
        return ScheduleRepository(context).snapshot(user.currentAccountStorageKey, school.id)
    }

    fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = ScheduleWidgetStyle.entries.flatMap { style ->
            manager.getAppWidgetIds(ComponentName(context, style.provider)).map { it to style }
        }
        if (ids.isEmpty()) { cancel(context); return }
        val now = System.currentTimeMillis()
        val state = ScheduleWidgetState.from(activeSnapshot(context), now)
        ids.forEach { (id, style) ->
            val options = manager.getAppWidgetOptions(id)
            manager.updateAppWidget(id, ScheduleWidgetRenderer.responsiveViews(context, state, options, style))
        }
        cancel(context)
        state.agenda?.let { agenda ->
            context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC,
                agenda.nextChangeAt.coerceAtLeast(now + 1_000), alarm(context))
        }
    }

    private fun alarm(context: Context) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ScheduleWidgetProvider::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun cancel(context: Context) { context.getSystemService(AlarmManager::class.java).cancel(alarm(context)) }
}

package com.tyust.course.academic.plugin.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.tyust.course.academic.plugin.AcademicProviderRegistry
import com.tyust.course.academic.plugin.PluginManifest
import com.tyust.course.academic.plugin.PluginPackage
import com.tyust.course.academic.plugin.PluginPages
import com.tyust.course.academic.plugin.PluginWidgetContract
import com.tyust.course.academic.plugin.PluginJson
import com.tyust.course.academic.plugin.widgetInvalid
import com.tyust.course.ui.system.GlassToaster
import org.json.JSONArray
import org.json.JSONObject

/**
 * Coordinator for launcher widgets contributed by native plugins.
 *
 * Everything here reads the plugin's persisted snapshot and never executes plugin JavaScript, so a
 * widget stays cheap and safe even when the launcher refreshes it while the app is idle.
 */
object PluginWidgets {
    const val ACTION_REFRESH = "com.tyust.course.academic.plugin.widget.REFRESH"
    const val ACTION_CYCLE = "com.tyust.course.academic.plugin.widget.CYCLE"

    private const val REQUEST_COUNTDOWN = 40_001

    val providerClasses: List<Class<out AppWidgetProvider>> = listOf(
        PluginWidgetSmallProvider::class.java,
        PluginWidgetMediumProvider::class.java,
        PluginWidgetLargeProvider::class.java,
    )

    /** `widget.render@1` result: which templates and launcher sizes this host supports. */
    fun renderResult(): JSONObject = JSONObject()
        .put("templates", JSONArray(PluginWidgetContract.TEMPLATES.sorted()))
        .put("sizes", JSONArray(PluginWidgetContract.SIZES.sorted()))

    /** `widget.publish@1`: validate, persist and refresh every bound instance of the plugin. */
    internal fun publish(context: Context, manifest: PluginManifest, input: JSONObject, provenance: WidgetSnapshotScope? = null): JSONObject {
        val widgetId = input.optString("widgetId")
        if (!PluginWidgetContract.declaresWidgets(manifest)) {
            widgetInvalid("插件未声明小组件")
        }
        val declaration = PluginWidgetContract.declaration(manifest, widgetId)
            ?: widgetInvalid("插件未声明小组件：$widgetId")
        if (!declaration.push) widgetInvalid("拉取式小组件由宿主拉取，不接受插件写入快照")
        val viewKey = declaration.viewKey(input.optString("viewKey"))
        val snapshot = input.optJSONObject("snapshot")
            ?: widgetInvalid("缺少小组件快照")
        val clean = PluginWidgetContract.sanitize(declaration, snapshot)
        PluginWidgetContract.validateActions(manifest, clean)
        val at = System.currentTimeMillis()
        WidgetSnapshotStore.save(context, manifest.id, widgetId, viewKey, clean, at, provenance)
        if (WidgetSnapshotStore.totalBytes(context, manifest.id) > PluginWidgetContract.MAX_PLUGIN_BYTES) {
            WidgetSnapshotStore.clearWidget(context, manifest.id, widgetId)
            widgetInvalid("小组件快照总量超过 256 KiB")
        }
        refreshPlugin(context, manifest.id)
        rescheduleCountdown(context)
        return JSONObject().put("published", true).put("at", at)
    }

    fun refreshAll(context: Context) {
        refreshIds(context, PluginWidgetBindings.all(context).map { it.appWidgetId }.toIntArray())
        rescheduleCountdown(context)
    }

    /** Account changes retire persisted data and the already rendered launcher view. */
    fun academicAccountChanged(context: Context) {
        synchronized(WidgetSnapshotStore) {
            WidgetSnapshotStore.clearAcademic(context)
            // Login restoration can precede registry initialization at process startup.
            runCatching { refreshAll(context) }
        }
        runCatching { PluginWidgetPull.requestAll(context) }
    }

    fun refreshPlugin(context: Context, pluginId: String) {
        refreshIds(context, PluginWidgetBindings.forPlugin(context, pluginId).map { it.appWidgetId }.toIntArray())
    }

    fun refreshId(context: Context, appWidgetId: Int) {
        if (appWidgetId <= 0) return
        refreshIds(context, intArrayOf(appWidgetId))
    }

    fun handle(context: Context, action: String?, appWidgetId: Int) {
        when (action) {
            ACTION_REFRESH -> {
                if (appWidgetId > 0) {
                    refreshId(context, appWidgetId)
                    PluginWidgetPull.requestForBindings(context, intArrayOf(appWidgetId))
                } else {
                    refreshAll(context)
                    PluginWidgetPull.requestAll(context)
                }
                rescheduleCountdown(context)
            }
            ACTION_CYCLE -> {
                if (appWidgetId > 0) {
                    val binding = PluginWidgetBindings.read(context, appWidgetId) ?: return
                    PluginWidgetBindings.save(context, binding.copy(offset = binding.offset + 1))
                    refreshId(context, appWidgetId)
                } else {
                    refreshAll(context)
                }
            }
        }
    }

    /** Disabled plugins retain configuration so enabling them restores the same launcher instances. */
    fun availabilityChanged(context: Context, pluginId: String) {
        synchronized(WidgetSnapshotStore) {
            PluginWidgetPullService.cancel(context, pluginId)
            refreshPlugin(context, pluginId)
            rescheduleCountdown(context)
        }
        PluginWidgetPull.requestForBindings(context, PluginWidgetBindings.forPlugin(context, pluginId).map { it.appWidgetId }.toIntArray())
    }

    /** Removes snapshots and bindings when the plugin is uninstalled. */
    fun clearPlugin(context: Context, pluginId: String) = synchronized(WidgetSnapshotStore) {
        val manager = AppWidgetManager.getInstance(context)
        PluginWidgetBindings.forPlugin(context, pluginId).forEach { binding ->
            runCatching {
                manager?.updateAppWidget(
                    binding.appWidgetId,
                    PluginWidgetRenderer.responsive(
                        context, null, binding, null, 0L,
                        manager.getAppWidgetOptions(binding.appWidgetId) ?: Bundle(),
                    ),
                )
            }
            PluginWidgetBindings.delete(context, binding.appWidgetId)
        }
        WidgetSnapshotStore.clearPlugin(context, pluginId)
        PluginWidgetPullService.cancel(context, pluginId)
        rescheduleCountdown(context)
    }

    /** Pins a new instance from the app; falls back to the launcher's add dialog hint. */
    fun requestPin(context: Context, family: PluginWidgetContract.Family) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val provider = PluginWidgetRenderer.componentName(context, family)
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            runCatching { manager.isRequestPinAppWidgetSupported }.getOrDefault(false)
        val callback = if (supported) PluginWidgetPinRequests.callback(context, family) else null
        val pinned = runCatching {
            callback != null && manager.requestPinAppWidget(provider, null, callback)
        }.getOrDefault(false)
        if (!pinned && callback != null) PluginWidgetPinRequests.discard(callback)
        if (!pinned) GlassToaster.show("长按桌面，从「小组件」中添加 ${family.label}")
    }

    /**
     * Countdown ticks on the launcher through Chronometer; a single boundary alarm per pending
     * `endAt` re-renders the widget once the countdown is over. Exact alarms are used only when the
     * system already granted them, otherwise the inexact idle-friendly variant keeps the app free of
     * extra permission prompts.
     */
    fun rescheduleCountdown(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_COUNTDOWN,
            Intent(context, PluginWidgetSmallProvider::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val now = System.currentTimeMillis()
        val next = PluginWidgetBindings.all(context).mapNotNull { binding ->
            val pkg = installed(context, binding.pluginId) ?: return@mapNotNull null
            val declaration = PluginWidgetContract.declaration(pkg.manifest, binding.widgetId) ?: return@mapNotNull null
            WidgetSnapshotStore.read(context, binding, declaration)
                ?.payload?.optJSONObject("timer")?.optLong("endAt")?.takeIf { it > now }
        }.minOrNull()
        alarms.cancel(pending)
        if (next == null) return
        val at = next + 1_000L
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) {
            runCatching { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
                .onFailure { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    /** Refreshes exactly the given launcher instances (system update, publish fan-out). */
    fun refreshIds(context: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context) ?: return
        ids.forEach { id -> runCatching { updateOne(context, manager, id) } }
    }

    private fun updateOne(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val binding = PluginWidgetBindings.read(context, appWidgetId) ?: return
        val pkg = installed(context, binding.pluginId)
        val declaration = pkg?.let { PluginWidgetContract.declaration(it.manifest, binding.widgetId) }
        val staleAfter = declaration?.staleAfterSeconds ?: PluginWidgetContract.DEFAULT_STALE_SECONDS
        val snapshot = if (declaration == null) null else
            WidgetSnapshotStore.read(context, binding, declaration)
        val bindingFingerprint = PluginWidgetBindings.fingerprint(binding)
        val snapshotFingerprint = snapshot?.let { PluginJson.canonical(it.root) }
        val views = PluginWidgetRenderer.responsive(
            context,
            if (declaration == null) null else pkg,
            binding,
            snapshot,
            staleAfter,
            manager.getAppWidgetOptions(appWidgetId) ?: Bundle(),
        )
        // Building RemoteViews may run on IO and block. Only the final apply shares the
        // snapshot-retirement/binding-mutation lock; never revive a captured old owner.
        synchronized(WidgetSnapshotStore) {
            val currentBinding = PluginWidgetBindings.read(context, appWidgetId) ?: return
            if (PluginWidgetBindings.fingerprint(currentBinding) != bindingFingerprint) return
            val currentPackage = installed(context, binding.pluginId)
            if (currentPackage?.digest != pkg?.digest) return
            val currentDeclaration = currentPackage?.let { PluginWidgetContract.declaration(it.manifest, binding.widgetId) }
            val currentSnapshot = currentDeclaration?.let { WidgetSnapshotStore.read(context, currentBinding, it) }
            if (currentSnapshot?.let { PluginJson.canonical(it.root) } != snapshotFingerprint) return
            manager.updateAppWidget(appWidgetId, views)
        }
    }

    private fun installed(context: Context, pluginId: String): PluginPackage? =
        runCatching { AcademicProviderRegistry.packages().active(pluginId)?.takeIf(PluginPages::available) }.getOrNull()

    /** Kept for diagnostics: maps a stored binding back to its declared view keys. */
    internal fun viewKeys(context: Context, appWidgetId: Int): List<String> {
        val binding = PluginWidgetBindings.read(context, appWidgetId) ?: return emptyList()
        val pkg = installed(context, binding.pluginId) ?: return emptyList()
        return PluginWidgetContract.declaration(pkg.manifest, binding.widgetId)?.views?.map { it.key }.orEmpty()
    }
}

package com.tyust.course.academic.plugin.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.tyust.course.academic.plugin.AcademicProviderRegistry
import com.tyust.course.academic.plugin.PluginWidgetContract

/**
 * Base provider for plugin widgets.
 *
 * Launcher providers are static (declared in the manifest), so all three size families share this
 * behaviour: read the binding, read the plugin's stored snapshot, render RemoteViews. No plugin code
 * is ever executed from here.
 */
abstract class PluginWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        PluginWidgets.refreshIds(context, appWidgetIds)
        PluginWidgets.rescheduleCountdown(context)
        // Launcher broadcasts have a ~10s budget: enqueue pull reads instead of running plugin code here.
        PluginWidgetPull.requestForBindings(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            PluginWidgets.ACTION_REFRESH, PluginWidgets.ACTION_CYCLE -> {
                val id = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID,
                )
                PluginWidgets.handle(context, intent.action, id)
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            -> {
                PluginWidgets.refreshAll(context)
                // Boot/time changes invalidate any pulled snapshot; jobs are persisted so only enqueue reads.
                PluginWidgetPull.requestAll(context)
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        PluginWidgets.refreshId(context, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val plugins = appWidgetIds.toList().mapNotNull { PluginWidgetBindings.read(context, it)?.pluginId }.distinct()
        appWidgetIds.forEach { PluginWidgetBindings.delete(context, it) }
        plugins.filter { id -> PluginWidgetBindings.forPlugin(context, id).none { binding ->
            val pkg = AcademicProviderRegistry.packages().active(id)
            pkg?.let { PluginWidgetContract.declaration(it.manifest, binding.widgetId)?.push == false } == true
        } }.forEach { PluginWidgetPullService.cancel(context, it) }
        PluginWidgets.rescheduleCountdown(context)
    }
}

/** 1x1 / 2x1 / 4x1 family. */
class PluginWidgetSmallProvider : PluginWidgetProvider()

/** 2x2 family. */
class PluginWidgetMediumProvider : PluginWidgetProvider()

/** 4x2 family. */
class PluginWidgetLargeProvider : PluginWidgetProvider()

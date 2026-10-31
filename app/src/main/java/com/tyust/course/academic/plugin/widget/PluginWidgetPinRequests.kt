package com.tyust.course.academic.plugin.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.tyust.course.academic.plugin.PluginWidgetContract
import org.json.JSONObject
import java.util.UUID

/** Private one-use pin tickets bind the launcher's real id to the requested provider family. */
internal object PluginWidgetPinRequests {
    private const val PREFS = "plugin-widget-pin-requests"
    private const val LIFETIME_MS = 30 * 60_000L
    private const val SCHEME = "plugin-widget-pin"

    fun callback(context: Context, family: PluginWidgetContract.Family): PendingIntent {
        val ticket = UUID.randomUUID().toString()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val edit = prefs.edit()
        prefs.all.forEach { (key, value) ->
            val created = runCatching { JSONObject(value as String).getLong("created") }.getOrDefault(0)
            if (created + LIFETIME_MS < now) edit.remove(key)
        }
        check(edit.putString(ticket, JSONObject().put("family", family.name).put("created", now).toString()).commit())
        val intent = Intent(context, PluginWidgetConfigActivity::class.java)
            .setData(Uri.Builder().scheme(SCHEME).authority(ticket).build())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The launcher supplies EXTRA_APPWIDGET_ID via fill-in extras. Component and data are fixed;
        // an immutable callback discards that id. Unique tickets prevent concurrent pins colliding.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_ONE_SHOT or mutable)
    }

    fun discard(callback: PendingIntent) {
        callback.cancel()
        // Failed requests expire; the opaque PendingIntent exposes no ticket to its creator.
    }

    fun isCallback(intent: Intent): Boolean = intent.data?.scheme == SCHEME

    @Synchronized
    fun consume(context: Context, intent: Intent, family: PluginWidgetContract.Family?): Boolean {
        if (!isCallback(intent) || family == null) return false
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id <= 0) return false
        val provider = AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider ?: return false
        if (provider != PluginWidgetRenderer.componentName(context, family)) return false
        val ticket = intent.data?.authority ?: return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val request = runCatching { JSONObject(prefs.getString(ticket, null) ?: return false) }.getOrNull() ?: return false
        val now = System.currentTimeMillis()
        if (request.optString("family") != family.name || now - request.optLong("created") !in 0..LIFETIME_MS) return false
        return prefs.edit().remove(ticket).commit()
    }
}

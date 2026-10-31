package com.tyust.course.academic.plugin.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.app.PendingIntent
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.tyust.course.R
import com.tyust.course.academic.plugin.NativePluginActivity
import com.tyust.course.academic.plugin.PluginCenterActivity
import com.tyust.course.academic.plugin.PluginJson
import com.tyust.course.academic.plugin.PluginPackage
import com.tyust.course.academic.plugin.PluginWidgetContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps a published snapshot onto RemoteViews. Widgets only ever read stored JSON here, so no plugin
 * code runs inside the launcher process.
 */
object PluginWidgetRenderer {

    fun responsive(
        context: Context,
        pkg: PluginPackage?,
        binding: PluginWidgetBindings.Binding,
        snapshot: WidgetSnapshotStore.Snapshot?,
        staleAfterSeconds: Long,
        options: Bundle,
    ): RemoteViews {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sizes = sizes(options)
            if (sizes.isNotEmpty()) return multiSize(context, pkg, binding, snapshot, staleAfterSeconds, sizes)
        }
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        return views(context, pkg, binding, snapshot, staleAfterSeconds, width, height)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun sizes(options: Bundle): List<SizeF> =
        options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            ?.filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }
            ?.distinct()?.take(16).orEmpty()

    @RequiresApi(Build.VERSION_CODES.S)
    private fun multiSize(
        context: Context,
        pkg: PluginPackage?,
        binding: PluginWidgetBindings.Binding,
        snapshot: WidgetSnapshotStore.Snapshot?,
        staleAfterSeconds: Long,
        sizes: List<SizeF>,
    ): RemoteViews = RemoteViews(sizes.associateWith { size ->
        views(context, pkg, binding, snapshot, staleAfterSeconds, size.width.toInt(), size.height.toInt())
    })

    fun views(
        context: Context,
        pkg: PluginPackage?,
        binding: PluginWidgetBindings.Binding,
        snapshot: WidgetSnapshotStore.Snapshot?,
        staleAfterSeconds: Long,
        widthDp: Int,
        heightDp: Int,
    ): RemoteViews {
        val layout = when (binding.template) {
            "lines" -> R.layout.plugin_widget_lines
            "grid" -> R.layout.plugin_widget_grid
            "countdown" -> R.layout.plugin_widget_countdown
            else -> R.layout.plugin_widget_text
        }
        val family = binding.family()
        val dark = when (binding.theme) {
            "dark" -> true
            "light" -> false
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val view = RemoteViews(context.packageName, layout)
        // Launcher applies RemoteViews diffs: fields we do not touch keep the previous render's
        // value, so clear every optional slot before filling the ones this snapshot uses.
        hideAll(view)
        val compact = heightDp in 1..89
        val narrow = widthDp < 200
        // A single launcher cell (1x1) has room for the headline only: the title row, subtitle and
        // footer would each steal a line and clip the value.
        val showHeader = !(compact && (narrow || (binding.template == "countdown" && widthDp < 260)))
        visible(view, R.id.plugin_widget_header, showHeader)
        val palette = palette(context, dark)
        val padding = when (family) {
            PluginWidgetContract.Family.Large -> 16
            PluginWidgetContract.Family.Medium -> 12
            else -> if (widthDp >= 220) 12 else 8
        }.let { dp(context, it) }
        view.setViewPadding(R.id.plugin_widget_surface, padding, padding, padding, padding)
        // Reuse the built-in schedule widget frame instead of the plugin-only surface drawable.
        view.setInt(R.id.plugin_widget_surface, "setBackgroundResource", palette.frame)
        view.setTextColor(R.id.plugin_widget_title, palette.primary)
        view.setTextColor(R.id.plugin_widget_subtitle, palette.secondary)
        view.setTextColor(R.id.plugin_widget_footer, palette.secondary)
        view.setTextColor(R.id.plugin_widget_empty, palette.secondary)
        view.setTextColor(R.id.plugin_widget_action, palette.accent)
        view.setTextColor(R.id.plugin_widget_badge, palette.accent)
        view.setTextColor(R.id.plugin_widget_main, palette.primary)
        view.setTextColor(R.id.plugin_widget_timer_label, palette.secondary)
        view.setTextColor(R.id.plugin_widget_chronometer, palette.accent)
        applyTextScale(view, family, widthDp)

        if (pkg == null) {
            empty(view, "插件已移除或停用", "轻触打开插件中心")
            view.setOnClickPendingIntent(R.id.plugin_widget_surface,
                PendingIntent.getActivity(context, binding.appWidgetId * 10 + 1,
                    Intent(context, PluginCenterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return view
        }
        if (snapshot == null) {
            empty(view, "等待插件数据", "轻触刷新")
            view.setOnClickPendingIntent(R.id.plugin_widget_surface, broadcast(context, binding.appWidgetId, PluginWidgets.ACTION_REFRESH))
            return view
        }
        // A snapshot with no rows is the plugin telling the host it has nothing to show right now.
        if (PluginJson.objects(snapshot.payload.optJSONArray("rows") ?: JSONArray()).isEmpty()) {
            val label = snapshot.payload.optJSONObject("empty")?.optString("text").orEmpty().ifBlank { "暂无数据" }
            empty(view, label, "轻触刷新")
            view.setOnClickPendingIntent(R.id.plugin_widget_surface, broadcast(context, binding.appWidgetId, PluginWidgets.ACTION_REFRESH))
            return view
        }

        val payload = snapshot.payload
        val header = payload.optJSONObject("header") ?: JSONObject()
        val rows = rotate(PluginJson.objects(payload.optJSONArray("rows") ?: JSONArray()), binding.offset)
        val title = header.optString("title").ifBlank { binding.viewKey.ifBlank { "小组件" } }
        val subtitle = header.optString("subtitle")
        val footer = payload.optString("footer")
        val stale = staleAfterSeconds > 0 && System.currentTimeMillis() - snapshot.updatedAt > staleAfterSeconds * 1_000L

        view.setTextViewText(R.id.plugin_widget_title, title)
        when (binding.template) {
            "lines" -> renderLines(view, context, rows, family, widthDp, heightDp, compact, palette)
            "grid" -> renderGrid(view, context, rows, family, widthDp, heightDp, compact, palette)
            "countdown" -> renderCountdown(view, payload, rows, subtitle, compact)
            else -> renderText(view, header, rows, subtitle, compact, widthDp)
        }
        if (!compact && binding.template != "text" && binding.template != "countdown") {
            view.setTextViewText(R.id.plugin_widget_subtitle, subtitle)
            visible(view, R.id.plugin_widget_subtitle, subtitle.isNotBlank())
        }
        val footerText = listOf(footer, header.optString("date"), if (stale) "数据陈旧 · 轻触刷新" else "")
            .filter(String::isNotBlank).joinToString(" · ")
        // One-row widgets (1x1 / 2x1 / 4x1) have no vertical room for a footer line.
        val showFooter = footerText.isNotBlank() && !compact && binding.template != "grid"
        view.setTextViewText(R.id.plugin_widget_footer, if (showFooter) footerText else "")
        visible(view, R.id.plugin_widget_footer, showFooter)

        val actions = PluginJson.objects(payload.optJSONArray("actions") ?: JSONArray())
        val action = actions.firstOrNull()
        val actionLabel = action?.optString("label").orEmpty().ifBlank { "刷新" }
        view.setTextViewText(R.id.plugin_widget_action, if (actions.isEmpty()) "⟳" else actionLabel)
        visible(view, R.id.plugin_widget_action, (family != PluginWidgetContract.Family.Small || widthDp >= 180) && !(compact && narrow))
        view.setOnClickPendingIntent(R.id.plugin_widget_action, actionIntent(context, binding, pkg, action))

        val pages = PluginJson.objects(pkg.manifest.contributes.optJSONArray("pages") ?: JSONArray())
            .map { it.optString("id") }.filter(String::isNotBlank)
        val openPage = actions.firstOrNull { it.optString("kind") == "openPage" }?.optString("pageId")
            ?: pages.firstOrNull()
        view.setOnClickPendingIntent(R.id.plugin_widget_surface, if (openPage != null) activity(context, binding, pkg, openPage)
            else broadcast(context, binding.appWidgetId, PluginWidgets.ACTION_REFRESH))
        return view
    }

    private fun renderText(view: RemoteViews, header: JSONObject, rows: List<JSONObject>, subtitle: String,
                          compact: Boolean, widthDp: Int) {
        val row = rows.firstOrNull()
        val main = row?.optString("title").orEmpty().ifBlank { subtitle }
        val line = row?.optString("subtitle").orEmpty()
        view.setTextViewText(R.id.plugin_widget_main, main)
        visible(view, R.id.plugin_widget_main, main.isNotBlank())
        if (compact) {
            // One row of height: keep the headline only; a subtitle line would clip the value.
            view.setTextViewTextSize(R.id.plugin_widget_main, TypedValue.COMPLEX_UNIT_SP,
                if (widthDp >= 220) 16f else if (widthDp >= 120) 13f else 10f)
        } else {
            val detail = line.ifBlank { subtitle }
            val second = rows.getOrNull(1)?.optString("title").orEmpty()
            val shown = if (rows.size > 1) listOf(line, second).filter(String::isNotBlank).joinToString(" · ") else detail
            view.setTextViewText(R.id.plugin_widget_subtitle, shown)
            visible(view, R.id.plugin_widget_subtitle, shown.isNotBlank())
        }
        val badge = row?.optString("badge").orEmpty()
        view.setTextViewText(R.id.plugin_widget_badge, badge)
        visible(view, R.id.plugin_widget_badge, badge.isNotBlank())
    }

    /** Clears optional slots first: launcher diff updates would otherwise keep stale text. */
    private fun hideAll(view: RemoteViews) {
        view.setViewVisibility(R.id.plugin_widget_grid_row_1, View.GONE)
        view.setViewVisibility(R.id.plugin_widget_grid_row_2, View.GONE)
        view.setViewVisibility(R.id.plugin_widget_progress, View.GONE)
        for (id in intArrayOf(
            R.id.plugin_widget_empty, R.id.plugin_widget_main, R.id.plugin_widget_subtitle,
            R.id.plugin_widget_footer, R.id.plugin_widget_badge, R.id.plugin_widget_timer_label,
            R.id.plugin_widget_chronometer, R.id.plugin_widget_row_1, R.id.plugin_widget_row_2,
            R.id.plugin_widget_row_3, R.id.plugin_widget_row_4, R.id.plugin_widget_cell_1,
            R.id.plugin_widget_cell_2, R.id.plugin_widget_cell_3, R.id.plugin_widget_cell_4,
            R.id.plugin_widget_cell_5, R.id.plugin_widget_cell_6,
        )) {
            view.setViewVisibility(id, View.GONE)
            view.setTextViewText(id, "")
        }
    }

    private fun renderLines(view: RemoteViews, context: Context, rows: List<JSONObject>,
                            family: PluginWidgetContract.Family?, widthDp: Int, heightDp: Int,
                            compact: Boolean, palette: Palette) {
        val capacity = if (compact) 1 else when (family) {
            PluginWidgetContract.Family.Large -> if (heightDp >= 160) 5 else 4
            PluginWidgetContract.Family.Medium -> 4
            else -> if (widthDp >= 220) 2 else 1
        }
        val gap = dp(context, 3)
        val pad = dp(context, 5)
        val ids = intArrayOf(R.id.plugin_widget_row_1, R.id.plugin_widget_row_2, R.id.plugin_widget_row_3, R.id.plugin_widget_row_4)
        for (index in ids.indices) {
            val id = ids[index]
            val row = rows.getOrNull(index)
            // Narrow cards hide the secondary line: a two-line row plus a badge does not fit.
            val text = row?.let { if (widthDp < 220) head(it) else line(it) }.orEmpty()
            view.setTextViewText(id, text)
            val shown = index < capacity && text.isNotBlank()
            visible(view, id, shown)
            if (shown) {
                // Reuse the built-in course-card frame for each row, like the timeline widget.
                view.setInt(id, "setBackgroundResource", palette.card)
                val bottom = pad + if (index < ids.size - 1) gap else 0
                view.setViewPadding(id, pad, pad, pad, bottom)
                view.setTextColor(id, palette.primary)
            }
        }
        if (rows.size > capacity) {
            val more = "还有 ${rows.size - capacity} 项，轻触查看"
            view.setTextViewText(R.id.plugin_widget_footer, more)
            visible(view, R.id.plugin_widget_footer, true)
        }
    }

    private fun head(row: JSONObject): String =
        listOf(row.optString("title"), row.optString("time")).filter(String::isNotBlank).joinToString("  ")

    private fun line(row: JSONObject): String {
        val title = row.optString("title")
        val time = row.optString("time")
        val subtitle = row.optString("subtitle")
        val badge = row.optString("badge")
        val head = listOf(title, time).filter(String::isNotBlank).joinToString("  ")
        val tail = listOf(subtitle, badge).filter(String::isNotBlank).joinToString(" · ")
        return if (tail.isBlank()) head else "$head\n$tail"
    }

    private fun renderGrid(view: RemoteViews, context: Context, rows: List<JSONObject>,
                           family: PluginWidgetContract.Family?, widthDp: Int, heightDp: Int,
                           compact: Boolean, palette: Palette) {
        val narrow = widthDp < 200
        // A grid row is a fixed line of 2 (narrow) or 3 cells, so the capacity must be a multiple of
        // the column count: otherwise the last row degenerates into a single full-width card, which
        // reads as a misaligned grid.
        val cells = if (compact) 2 else when {
            narrow -> 4
            family == PluginWidgetContract.Family.Large -> if (rows.size >= 6) 6 else 3
            else -> 3
        }
        val pad = dp(context, 5)
        visible(view, R.id.plugin_widget_grid_row_1, rows.isNotEmpty())
        val columns = if (narrow || compact) 2 else 3
        visible(view, R.id.plugin_widget_grid_row_2, cells > columns && rows.size > columns)
        // The layout is a fixed 3-column table; at 2x2 / 1x1 width the third column has no room,
        // so paint two columns and leave cell_3 / cell_6 hidden.
        val ids = if (narrow) intArrayOf(
            R.id.plugin_widget_cell_1, R.id.plugin_widget_cell_2,
            R.id.plugin_widget_cell_4, R.id.plugin_widget_cell_5,
        ) else intArrayOf(
            R.id.plugin_widget_cell_1, R.id.plugin_widget_cell_2, R.id.plugin_widget_cell_3,
            R.id.plugin_widget_cell_4, R.id.plugin_widget_cell_5, R.id.plugin_widget_cell_6,
        )
        for (index in ids.indices) {
            val row = rows.getOrNull(index)
            val text = row?.let { cell(it) }.orEmpty()
            view.setTextViewText(ids[index], text)
            val shown = index < cells && text.isNotBlank()
            visible(view, ids[index], shown)
            if (shown) {
                // Same rounded course-card asset the built-in grid widget paints its cells with.
                view.setInt(ids[index], "setBackgroundResource", palette.card)
                view.setViewPadding(ids[index], pad, pad, pad, pad)
                view.setTextColor(ids[index], palette.primary)
            }
        }
    }

    private fun cell(row: JSONObject): String {
        val title = row.optString("title")
        val subtitle = row.optString("subtitle")
        val time = row.optString("time")
        return listOf(title, subtitle, time).filter(String::isNotBlank).joinToString(" · ")
    }

    private fun renderCountdown(view: RemoteViews, payload: JSONObject, rows: List<JSONObject>, subtitle: String,
                                compact: Boolean) {
        val timer = payload.optJSONObject("timer")
        if (timer == null) {
            val row = rows.firstOrNull()
            view.setTextViewText(R.id.plugin_widget_timer_label, row?.optString("title").orEmpty().ifBlank { subtitle })
            visible(view, R.id.plugin_widget_chronometer, false)
            visible(view, R.id.plugin_widget_timer_label, true)
            return
        }
        val now = System.currentTimeMillis()
        val startAt = timer.optLong("startAt", 0L)
        val endAt = timer.optLong("endAt")
        val remaining = endAt - now
        val span = (endAt - startAt).coerceAtLeast(1L)
        val elapsed = (now - startAt).coerceIn(0L, span)
        val forward = timer.optString("direction", "backward") == "forward"
        val derived = if (forward) elapsed.toFloat() / span else 1f - elapsed.toFloat() / span
        val reported = if (timer.has("progress")) timer.optDouble("progress", derived.toDouble()).toFloat() else derived
        val progress = reported.coerceIn(0f, 1f)
        val full = progress >= 0.999f
        val empty = progress <= 0.001f
        val baseLabel = timer.optString("label").ifBlank { if (remaining > 0) "剩余时间" else "已结束" }
        val label = when {
            full -> timer.optString("fullText").ifBlank { baseLabel }
            empty -> timer.optString("emptyText").ifBlank { baseLabel }
            else -> baseLabel
        }
        view.setTextViewText(R.id.plugin_widget_timer_label, label)
        // One-row countdowns keep the ticking clock only; the label would push it out of the frame.
        visible(view, R.id.plugin_widget_timer_label, !compact || full || empty)
        val countdown = timer.optBoolean("countdown", true) && remaining > 0 && !full && !empty
        visible(view, R.id.plugin_widget_chronometer, countdown)
        if (countdown) {
            // One-row frames are ~78dp tall: a 26sp clock plus padding clips at the baseline.
            if (compact) {
                view.setTextViewTextSize(R.id.plugin_widget_chronometer, TypedValue.COMPLEX_UNIT_SP, 20f)
            }
            val base = SystemClock.elapsedRealtime() + remaining
            view.setChronometer(R.id.plugin_widget_chronometer, base, null, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) view.setChronometerCountDown(R.id.plugin_widget_chronometer, true)
        }
        // 进度条：轨道复用内置色条素材，长短由 progress/direction 决定，满/空时用 fullText/emptyText 顶替 label。
        val showProgress = timer.optBoolean("showProgress", false)
        visible(view, R.id.plugin_widget_progress, showProgress)
        if (showProgress) {
            view.setProgressBar(R.id.plugin_widget_progress, 1000, Math.round(progress * 1000f), false)
        }
    }

    private fun empty(view: RemoteViews, text: String, hint: String) {
        view.setTextViewText(R.id.plugin_widget_empty, text)
        view.setTextViewText(R.id.plugin_widget_title, "")
        visible(view, R.id.plugin_widget_header, false)
        visible(view, R.id.plugin_widget_title, false)
        visible(view, R.id.plugin_widget_subtitle, false)
        visible(view, R.id.plugin_widget_main, false)
        visible(view, R.id.plugin_widget_badge, false)
        visible(view, R.id.plugin_widget_footer, false)
        visible(view, R.id.plugin_widget_action, false)
        visible(view, R.id.plugin_widget_empty, true)
        if (hint.isNotBlank()) view.setContentDescription(R.id.plugin_widget_surface, hint)
    }

    private fun applyTextScale(view: RemoteViews, family: PluginWidgetContract.Family?, widthDp: Int) {
        val (title, main) = when (family) {
            PluginWidgetContract.Family.Large -> 15 to 20
            PluginWidgetContract.Family.Medium -> 14 to 17
            else -> if (widthDp >= 220) 14 to 17 else 12 to 14
        }
        view.setTextViewTextSize(R.id.plugin_widget_title, TypedValue.COMPLEX_UNIT_SP, title.toFloat())
        view.setTextViewTextSize(R.id.plugin_widget_main, TypedValue.COMPLEX_UNIT_SP, main.toFloat())
        view.setTextViewTextSize(R.id.plugin_widget_chronometer, TypedValue.COMPLEX_UNIT_SP, (main + 8).toFloat())
    }

    /**
     * Same colours and frames the built-in schedule widgets use
     * (see `ScheduleWidgetPresentation`), so plugin widgets sit on the same visual language.
     */
    private class Palette(
        val primary: Int,
        val secondary: Int,
        val accent: Int,
        val frame: Int,
        val card: Int,
    )

    private fun palette(context: Context, dark: Boolean): Palette {
        @Suppress("UNUSED_VARIABLE") val unused = context
        return if (dark) Palette(
            0xFFF1F4F8.toInt(), 0xFFB8C2D0.toInt(), 0xFF88BFFF.toInt(),
            R.drawable.schedule_widget_dark, R.drawable.schedule_widget_card_dark,
        ) else Palette(
            0xFF1D2433.toInt(), 0xFF536078.toInt(), 0xFF0069D9.toInt(),
            R.drawable.schedule_widget_light, R.drawable.schedule_widget_card_light,
        )
    }

    private fun themed(context: Context, dark: Boolean): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        return context.createConfigurationContext(configuration)
    }

    private fun activity(context: Context, binding: PluginWidgetBindings.Binding, pkg: PluginPackage, pageId: String): PendingIntent =
        PendingIntent.getActivity(context, binding.appWidgetId * 10 + 2,
            Intent(context, NativePluginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("pluginId", pkg.manifest.id)
                .putExtra("pageId", pageId)
                .putExtra("pageParams", "{}"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun actionIntent(
        context: Context,
        binding: PluginWidgetBindings.Binding,
        pkg: PluginPackage,
        action: JSONObject?,
    ): PendingIntent {
        if (action == null) return broadcast(context, binding.appWidgetId, PluginWidgets.ACTION_REFRESH)
        return when (action.optString("kind")) {
            "openPage" -> activity(context, binding, pkg, action.optString("pageId"))
            "cycle" -> broadcast(context, binding.appWidgetId, PluginWidgets.ACTION_CYCLE)
            else -> broadcast(context, binding.appWidgetId, PluginWidgets.ACTION_REFRESH)
        }
    }

    private fun broadcast(context: Context, appWidgetId: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(context, appWidgetId * 10 + 3,
            Intent(context, PluginWidgetSmallProvider::class.java)
                .setAction(action)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun visible(view: RemoteViews, id: Int, show: Boolean) =
        view.setViewVisibility(id, if (show) View.VISIBLE else View.GONE)

    /** "Change one" rotates the visible rows locally; the launcher never re-runs plugin code. */
    private fun rotate(rows: List<JSONObject>, offset: Int): List<JSONObject> {
        if (offset == 0 || rows.size < 2) return rows
        val shift = offset.mod(rows.size)
        return rows.drop(shift) + rows.take(shift)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    internal fun componentName(context: Context, family: PluginWidgetContract.Family): ComponentName = when (family) {
        PluginWidgetContract.Family.Medium -> ComponentName(context, PluginWidgetMediumProvider::class.java)
        PluginWidgetContract.Family.Large -> ComponentName(context, PluginWidgetLargeProvider::class.java)
        else -> ComponentName(context, PluginWidgetSmallProvider::class.java)
    }
}

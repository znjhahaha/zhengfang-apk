package com.tyust.course.academic.plugin.widget

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tyust.course.R
import com.tyust.course.academic.plugin.PluginPackageStore
import com.tyust.course.academic.plugin.PluginWidgetContract
import org.json.JSONObject

/**
 * Debug-only harness: paints every contributed widget with its stored snapshot at every declared
 * launcher size, in both themes, without pinning real widgets to the launcher.
 *
 * Launch with:
 * `adb shell am start -n com.tyust.course.dev/com.tyust.course.academic.plugin.widget.PluginWidgetPreviewActivity`
 */
class PluginWidgetPreviewActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(32))
        }
        val pkg = PluginPackageStore(this).list()
            .firstOrNull { PluginWidgetContract.declaresWidgets(it.manifest) }
        if (pkg == null) {
            root.addView(label("没有安装声明了 contributes.widgets 的插件", 16f, true))
            setContentView(ScrollView(this).apply { addView(root) })
            return
        }
        val declarations = PluginWidgetContract.declarations(pkg.manifest)
        root.addView(label("插件 ${pkg.manifest.id} · ${pkg.manifest.version}", 16f, true))
        for (theme in listOf("light", "dark")) {
            root.addView(label("主题：$theme", 15f, true))
            for (declaration in declarations) {
                val instance = PluginWidgetBindings.forPlugin(this, pkg.manifest.id).firstOrNull { it.widgetId == declaration.id }
                val snapshot = if (declaration.push) {
                    WidgetSnapshotStore.read(this, pkg.manifest.id, declaration.id, declaration.viewKey(null))
                } else instance?.let { WidgetSnapshotStore.read(this, it, declaration) }
                root.addView(label(
                    "${declaration.id} · ${declaration.template} · ${declaration.sizes.joinToString()} · " +
                        if (snapshot == null) "无快照" else "快照 ${snapshot.updatedAt}",
                    13f, true,
                ))
                val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (size in declaration.sizes) {
                    val (widthDp, heightDp) = launcherDp(size)
                    val holder = FrameLayout(this)
                    strip.addView(holder, LinearLayout.LayoutParams(dp(widthDp), dp(heightDp)).apply {
                        marginEnd = dp(8)
                        bottomMargin = dp(8)
                    })
                    val binding = PluginWidgetBindings.Binding(
                        appWidgetId = 0,
                        pluginId = pkg.manifest.id,
                        widgetId = declaration.id,
                        viewKey = declaration.viewKey(null),
                        template = declaration.template,
                        theme = theme,
                        account = "",
                        options = JSONObject(),
                        size = size,
                        boundAt = 0L,
                    )
                    val views = PluginWidgetRenderer.views(
                        this, pkg, binding, snapshot, declaration.staleAfterSeconds, widthDp, heightDp,
                    )
                    holder.addView(views.apply(this, holder), FrameLayout.LayoutParams(dp(widthDp), dp(heightDp)))
                    holder.post { measure(declaration.id, size, holder) }
                }
                root.addView(HorizontalScrollView(this).apply { addView(strip) })
            }
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** Debug aid: log the measured geometry of the grid slots so layout bugs are provable. */
    private fun measure(widgetId: String, size: String, root: FrameLayout) {
        val named = linkedMapOf(
            "row_1" to R.id.plugin_widget_row_1, "row_2" to R.id.plugin_widget_row_2,
            "cell_1" to R.id.plugin_widget_cell_1, "cell_2" to R.id.plugin_widget_cell_2,
            "cell_3" to R.id.plugin_widget_cell_3, "cell_4" to R.id.plugin_widget_cell_4,
            "cell_5" to R.id.plugin_widget_cell_5, "cell_6" to R.id.plugin_widget_cell_6,
            "header" to R.id.plugin_widget_header, "main" to R.id.plugin_widget_main,
        )
        for ((name, id) in named) {
            val view = root.findViewById<View>(id) ?: continue
            val parent = view.parent as? View
            val loc = IntArray(2).also { view.getLocationOnScreen(it) }
            Log.d(
                "WidgetPreview",
                "$widgetId/$size $name vis=${view.visibility} w=${view.width} x=${view.left} screenX=${loc[0]} screenY=${loc[1]} " +
                    "parentW=${parent?.width.orZero()} text='${(view as? TextView)?.text?.toString()?.replace('\n', '|')}'",
            )
        }
    }

    private fun Int?.orZero(): Int = this ?: 0

    /** Approximate Pixel-launcher cell dp for each declared size (5 columns, ~78dp per cell). */    private fun launcherDp(size: String): Pair<Int, Int> = when (size) {
        "1x1" -> 78 to 78
        "2x1" -> 156 to 78
        "4x1" -> 312 to 78
        "2x2" -> 156 to 156
        "4x2" -> 312 to 156
        else -> 156 to 78
    }

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, dp(10), 0, dp(4))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

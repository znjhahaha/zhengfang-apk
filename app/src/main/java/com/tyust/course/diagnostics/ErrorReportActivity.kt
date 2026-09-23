package com.tyust.course.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tyust.course.academic.plugin.PluginFeedback

/** A plain Android surface remains usable even when the failing path was a glass renderer. */
class ErrorReportActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.tyust.course.manager.AppThemeCoordinator.wrapContext(newBase))
    }
    companion object {
        const val EXTRA_REPORT = "error_report"
        const val EXTRA_PENDING = "pending_error"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppDiagnostics.enterReport()
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra(EXTRA_REPORT) ?: AppDiagnostics.latest(this).orEmpty()
        val recovered = intent.getBooleanExtra(EXTRA_PENDING, false)
        if (recovered) AppDiagnostics.acknowledge(this)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val ink = if (dark) Color.rgb(235, 239, 245) else Color.rgb(31, 38, 47)
        val space = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (dark) Color.rgb(28, 33, 41) else Color.rgb(248, 249, 252))
            setPadding(space, space, space, space)
        }
        fun text(value: String, size: Float) = TextView(this).apply {
            text = value; textSize = size; setTextColor(ink); setPadding(0, space / 2, 0, space / 2)
        }
        root.addView(text(if (recovered) "上次运行发生错误" else "错误详情", 22f))
        root.addView(text("可复制报告并通过快捷反馈提交。报告不会自动上传。", 14f))
        val details = text(report.ifBlank { "暂未记录错误。" }, 12f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(details) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        fun copy() {
            val clipboard = getSystemService(ClipboardManager::class.java) ?: return
            clipboard.setPrimaryClip(ClipData.newPlainText("应用错误报告", report))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "错误报告已复制", Toast.LENGTH_SHORT).show()
        }
        fun button(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                minHeight = space * 3
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        button("复制日志", ::copy)
        button("复制并反馈") {
            copy()
            runCatching { PluginFeedback.open(this, errorCode = "APP_RUNTIME_ERROR") }
                .onFailure { Toast.makeText(this, "无法打开反馈页面，请稍后重试", Toast.LENGTH_SHORT).show() }
        }
        button("返回应用") { finish() }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(space + bars.left, space + bars.top, space + bars.right, space + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}

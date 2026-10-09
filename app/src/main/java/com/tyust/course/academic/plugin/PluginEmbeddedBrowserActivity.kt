package com.tyust.course.academic.plugin

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.*
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession.PromptDelegate.*
import org.mozilla.geckoview.GeckoView

/** UI only: its sessions remain owned by the bound private service when the page is minimized. */
class PluginEmbeddedBrowserActivity : Activity() {
    private lateinit var browser: GeckoView
    private lateinit var title: TextView
    private lateinit var progress: ProgressBar
    private var attached: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT < 26 || EmbeddedBrowserRuntime.profile == null) { finish(); return }
        EmbeddedBrowserRuntime.activity = this
        val handle = intent.getStringExtra("handle")
        if (handle != null && EmbeddedBrowserRuntime.page(handle) != null) EmbeddedBrowserRuntime.visible = handle
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xfffafafa.toInt()) }
        root.setOnApplyWindowInsetsListener { v, insets -> v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom); insets }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        bar.addView(Button(this).apply { text = "返回"; setOnClickListener { back() } })
        title = TextView(this).apply { setTextColor(0xff202124.toInt()); textSize = 14f; maxLines = 2 }
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply { text = "收起"; setOnClickListener { finish() } })
        root.addView(bar)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(-1, (2 * resources.displayMetrics.density).toInt().coerceAtLeast(1)))
        browser = GeckoView(this)
        root.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); render()
    }
    internal fun render() {
        if (!::browser.isInitialized || isFinishing) return
        val page = EmbeddedBrowserRuntime.pages[EmbeddedBrowserRuntime.visible]
        if (page == null) { finish(); return }
        if (attached != page.handle) { browser.releaseSession(); browser.setSession(page.session); attached = page.handle }
        updateBar()
    }
    internal fun updateBar() {
        if (!::title.isInitialized) return
        val page = EmbeddedBrowserRuntime.pages[EmbeddedBrowserRuntime.visible] ?: return
        val host = runCatching { java.net.URI(page.url).host }.getOrNull().orEmpty()
        title.text = "${EmbeddedBrowserRuntime.title}\n$host"
        progress.visibility = if (page.loading) View.VISIBLE else View.GONE
    }
    private fun back() {
        val page = EmbeddedBrowserRuntime.pages[EmbeddedBrowserRuntime.visible]
        if (page?.back == true) page.session.goBack() else finish()
    }
    @Deprecated("Deprecated in Java") override fun onBackPressed() = back()
    override fun onDestroy() {
        if (::browser.isInitialized) browser.releaseSession()
        if (EmbeddedBrowserRuntime.activity === this) EmbeddedBrowserRuntime.activity = null
        if (!isChangingConfigurations) {
            val page = attached?.let(EmbeddedBrowserRuntime::page)
            if (page != null && page.scriptHandle == null) EmbeddedBrowserRuntime.close(page.handle)
        }
        super.onDestroy()
    }
    internal fun alert(prompt: AlertPrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        AlertDialog.Builder(this).setTitle(prompt.title ?: "原页面提示").setMessage(prompt.message).setPositiveButton("确定") { _, _ -> result.complete(prompt.dismiss()) }
            .setOnCancelListener { result.complete(prompt.dismiss()) }.show()
        return result
    }
    internal fun confirm(prompt: ButtonPrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        AlertDialog.Builder(this).setTitle(prompt.title ?: "原页面确认").setMessage(prompt.message)
            .setPositiveButton("确定") { _, _ -> result.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE)) }
            .setNegativeButton("取消") { _, _ -> result.complete(prompt.dismiss()) }.setOnCancelListener { result.complete(prompt.dismiss()) }.show()
        return result
    }
    internal fun input(prompt: TextPrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        val field = EditText(this).apply { setText(prompt.defaultValue.orEmpty()); isSingleLine = true }
        AlertDialog.Builder(this).setTitle(prompt.title ?: "原页面输入").setMessage(prompt.message).setView(field)
            .setPositiveButton("确定") { _, _ -> result.complete(prompt.confirm(field.text.toString())) }
            .setNegativeButton("取消") { _, _ -> result.complete(prompt.dismiss()) }.setOnCancelListener { result.complete(prompt.dismiss()) }.show()
        return result
    }
}

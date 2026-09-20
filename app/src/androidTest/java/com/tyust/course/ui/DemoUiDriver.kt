package com.tyust.course.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import com.tyust.course.BuildConfig
import com.tyust.course.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses the activity's actual display and never waits for Compose animations to be idle. */
internal class DemoUiDriver : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val device = UiDevice.getInstance(instrumentation)
    private val previousTimeout = Configurator.getInstance().waitForIdleTimeout
    @Volatile var foreground: Activity? = null
        private set
    @Volatile var main: MainActivity? = null
        private set
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            foreground = activity
            if (activity is MainActivity) main = activity
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    init {
        assumeTrue("Only the isolated demo variant is allowed", BuildConfig.UI_PREVIEW)
        Configurator.getInstance().waitForIdleTimeout = 0
        onMain {
            val user = com.tyust.course.manager.UserManager.getInstance()
            user.sessionState.replace(user.currentAccountStorageKey)
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        app.startActivity(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("Demo activity did not resume") { main != null }
        waitText("课程")
        SystemClock.sleep(1600)
    }

    fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun shell(command: String): String = device.executeShellCommand(command)
    fun await(message: String, timeout: Long = 12_000L, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        if (!condition()) {
            runCatching { screenshot("failure-${SystemClock.uptimeMillis()}") }
            android.util.Log.e("DemoUiDriver", dumpNodes())
            throw AssertionError(message)
        }
    }

    private fun nodes(text: String, firstOnly: Boolean = false): List<AccessibilityNodeInfo> {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun matches(node: AccessibilityNodeInfo): Boolean {
            // API 31/32 have no clearCache(). Refresh the provider snapshot before
            // inspecting selection, visibility or an ancestor from the previous page.
            if (!node.refresh()) return false
            if (!node.isVisibleToUser) return false
            return sequenceOf(node.text, node.contentDescription, node.stateDescription, node.viewIdResourceName)
                .filterNotNull().any { value ->
                    value.toString().let { it == text || it.split('\n', '，').any { label -> label.trim() == text } }
                }
        }
        val display = foreground?.display?.displayId ?: return emptyList()
        val roots = instrumentation.uiAutomation.windowsOnAllDisplays.get(display).orEmpty().mapNotNull { it.root }
        // Ask the accessibility provider first. Walking every course and log entry can
        // outlast a short demo run before the stop button is even inspected.
        val direct = roots.flatMap { it.findAccessibilityNodeInfosByText(text) }.filter(::matches)
        if (direct.isNotEmpty()) return if (firstOnly) direct.take(1) else direct
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun find(node: AccessibilityNodeInfo?) {
            if (node == null || firstOnly && result.isNotEmpty()) return
            if (matches(node)) result += node
            for (index in 0 until node.childCount) {
                if (firstOnly && result.isNotEmpty()) break
                find(node.getChild(index))
            }
        }
        roots.forEach(::find)
        return result
    }

    fun hasText(text: String): Boolean = nodes(text, firstOnly = true).isNotEmpty()
    fun waitSelected(text: String) = await("Selected state was not retained: $text") {
        nodes(text).any { node ->
            generateSequence(node) { it.parent }.take(4).any { it.refresh() && (it.isSelected || it.isChecked) }
        }
    }
    private fun dumpNodes(): String {
        fun describe(node: AccessibilityNodeInfo?): String {
            if (node == null) return ""
            val bounds = Rect().also(node::getBoundsInScreen)
            return "${node.text} | ${node.contentDescription} | ${node.stateDescription} | ${node.isVisibleToUser} | $bounds\n" +
                (0 until node.childCount).joinToString("") { describe(node.getChild(it)) }
        }
        return instrumentation.uiAutomation.windowsOnAllDisplays.get(foreground?.display?.displayId ?: 0).orEmpty().joinToString("\n") { describe(it.root) }
    }
    fun waitText(text: String, present: Boolean = true) = await("Unexpected visibility for '$text': expected $present") { hasText(text) == present }
    fun boundsOf(text: String, bottomMost: Boolean = false): Rect {
        fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
        var result: Rect? = null
        await("Missing $text") {
            val matches = nodes(text, firstOnly = !bottomMost).map(::bounds).filterNot { it.isEmpty }
            result = if (bottomMost) matches.maxByOrNull { it.bottom } else matches.minByOrNull { it.top }
            result != null
        }
        return requireNotNull(result)
    }
    fun click(text: String, bottomMost: Boolean = false) {
        val rect = boundsOf(text, bottomMost)
        val display = requireNotNull(foreground).display!!.displayId
        device.executeShellCommand("input -d $display tap ${rect.centerX()} ${rect.centerY()}")
        SystemClock.sleep(400)
    }
    fun scrollTo(text: String) {
        SystemClock.sleep(800)
        repeat(12) {
            if (hasText(text)) return
            val activity = requireNotNull(foreground)
            val view = activity.window.decorView
            val x = view.width / 2
            shell("input -d ${activity.display!!.displayId} swipe $x ${view.height * 3 / 4} $x ${view.height * 11 / 20} 450")
            SystemClock.sleep(400)
        }
        waitText(text)
    }
    fun longClick(text: String) {
        val rect = boundsOf(text)
        val display = requireNotNull(foreground).display!!.displayId
        device.executeShellCommand("input -d $display swipe ${rect.centerX()} ${rect.centerY()} ${rect.centerX()} ${rect.centerY()} 1000")
        SystemClock.sleep(450)
    }
    fun navigate(text: String) {
        if (!hasText(text)) {
            listOf("课程", "课表", "抢课", "成绩", "设置").firstOrNull(::hasText)?.let {
                click(it, bottomMost = true)
                SystemClock.sleep(500)
            }
        }
        click(text, bottomMost = true); SystemClock.sleep(800); waitSelected(text)
    }
    fun back() { onMain { (foreground as ComponentActivity).onBackPressedDispatcher.onBackPressed() }; SystemClock.sleep(450) }

    fun screenshot(name: String) {
        val activity = requireNotNull(foreground)
        val bitmap = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        onMain { PixelCopy.request(activity.window, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper())) }
        assertTrue("Screenshot failed", latch.await(5, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS)
        val directory = File(activity.getExternalFilesDir(null), "flow-validation").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "$name.json").writeText(org.json.JSONObject()
            .put("package", activity.packageName).put("activity", activity.javaClass.name)
            .put("displayId", activity.display?.displayId).put("width", bitmap.width).put("height", bitmap.height)
            .put("fontScale", activity.resources.configuration.fontScale)
            .put("screenWidthDp", activity.resources.configuration.screenWidthDp)
            .put("nightMode", activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            .toString(2))
        bitmap.recycle()
    }

    override fun close() {
        onMain { foreground?.finish(); main?.finish() }
        app.unregisterActivityLifecycleCallbacks(callbacks)
        Configurator.getInstance().waitForIdleTimeout = previousTimeout
    }
}

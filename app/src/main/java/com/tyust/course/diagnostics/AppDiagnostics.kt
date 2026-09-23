package com.tyust.course.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import com.tyust.course.BuildConfig
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Local, bounded reports. Never store exception messages, HTTP bodies, URLs or credentials. */
object AppDiagnostics {
    private const val MAX_CHARS = 48 * 1024
    private const val LATEST = "latest.txt"
    private const val PENDING = "pending.txt"
    private val installed = AtomicBoolean()
    private val crashing = AtomicBoolean()
    private var foreground = WeakReference<Activity>(null)
    @Volatile private var pendingReport: String? = null
    @Volatile private var reporting = false
    private var currentRun = JSONObject()

    fun install(app: Application) {
        if (!installed.compareAndSet(false, true)) return
        val previousRun = read(app, "run.json")?.let { runCatching { JSONObject(it) }.getOrNull() }
        currentRun = JSONObject().put("started", System.currentTimeMillis())
            .put("environment", environment()).put("stage", "启动")
        write(app, "run.json", currentRun.toString())
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { failedThread, failure ->
            try {
                if (crashing.compareAndSet(false, true)) {
                    val report = capture(app, "未处理异常", failure)
                    if (!reporting) write(app, PENDING, report)
                }
            } catch (_: Throwable) {
                // Reporting must never replace the original failure or keep a broken process alive.
            } finally {
                try { previousHandler?.uncaughtException(failedThread, failure) }
                finally { android.os.Process.killProcess(android.os.Process.myPid()) }
            }
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                foreground = WeakReference(activity)
                reporting = activity is ErrorReportActivity
                if (!reporting) {
                    markStage(app, activity.javaClass.simpleName)
                    showPending(activity)
                }
            }
            override fun onActivityPaused(activity: Activity) {
                if (foreground.get() === activity) foreground.clear()
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        thread(name = "app-error-recovery", isDaemon = true) {
            val report = read(app, PENDING) ?: systemExitReport(app, previousRun)
            if (report != null) Handler(Looper.getMainLooper()).post {
                pendingReport = report
                foreground.get()?.let(::showPending)
            }
        }
    }

    @Synchronized
    fun markStage(context: Context, stage: String) {
        // Callers supply fixed operation labels, never user input.
        currentRun.put("stage", stage.take(80))
        write(context, "run.json", currentRun.toString())
    }

    @Synchronized
    fun capture(context: Context, operation: String, failure: Throwable?): String {
        val report = buildString {
            appendLine("应用错误报告")
            appendLine("时间：${time(System.currentTimeMillis())}")
            appendLine(environment())
            appendLine("操作：$operation")
            appendLine("阶段：${currentRun.optString("stage", "未知")}")
            appendLine("记录线程：${if (Looper.myLooper() == Looper.getMainLooper()) "主线程" else "后台线程"}")
            appendLine()
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var cause = failure
            var depth = 0
            while (cause != null && depth++ < 8 && seen.add(cause)) {
                appendLine(cause.javaClass.name)
                cause.stackTrace.take(64).forEach { appendLine("    at $it") }
                cause = cause.cause
                if (cause != null) appendLine("Caused by:")
            }
            if (failure == null) appendLine("操作未完成，未提供异常调用栈。")
            appendLine()
            appendLine("此报告仅保存在本机，不含异常消息、账号、密码、Cookie 或网页内容。")
        }.take(MAX_CHARS)
        write(context, LATEST, report)
        return report
    }

    fun latest(context: Context): String? = read(context, LATEST)

    fun showReport(context: Context, report: String, pending: Boolean = false) {
        context.startActivity(Intent(context, ErrorReportActivity::class.java)
            .putExtra(ErrorReportActivity.EXTRA_REPORT, report.take(MAX_CHARS))
            .putExtra(ErrorReportActivity.EXTRA_PENDING, pending)
            .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    fun acknowledge(context: Context) {
        pendingReport = null
        runCatching { AtomicFile(file(context, PENDING)).delete() }
    }

    fun enterReport() { reporting = true }

    private fun showPending(activity: Activity) {
        val report = pendingReport ?: return
        if (activity is ErrorReportActivity || activity.isFinishing || activity.isDestroyed) { return }
        runCatching { showReport(activity, report, pending = true) }.onSuccess { pendingReport = null }
    }

    private fun systemExitReport(context: Context, previous: JSONObject?): String? = runCatching {
        if (Build.VERSION.SDK_INT < 30 || previous == null) return@runCatching null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return@runCatching null
        val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 8).firstOrNull {
                it.processName == context.packageName && it.timestamp >= previous.optLong("started", Long.MAX_VALUE) &&
                    it.reason in setOf(ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)
            } ?: return@runCatching null
        val report = buildString {
            appendLine("应用异常退出报告")
            appendLine("时间：${time(exit.timestamp)}")
            appendLine(previous.optString("environment", "版本信息不可用"))
            appendLine("阶段：${previous.optString("stage", "未知")}")
            appendLine("系统原因：${if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "原生崩溃" else "应用无响应"}")
            appendLine("退出状态：${exit.status}")
            appendLine()
            appendLine("系统未提供可直接展示的 Java 调用栈。请在反馈中补充当时的操作步骤。")
            appendLine("此报告仅保存在本机，不读取系统跟踪文件或业务数据。")
        }
        write(context, LATEST, report)
        write(context, PENDING, report)
        report
    }.getOrNull()

    private fun environment() = "App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
        "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n设备：${Build.MANUFACTURER} ${Build.MODEL}"

    private fun time(timestamp: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date(timestamp))
    private fun file(context: Context, name: String): File = File(context.noBackupFilesDir, "diagnostics").let {
        it.mkdirs(); File(it, name)
    }

    @Synchronized
    private fun read(context: Context, name: String): String? = runCatching {
        AtomicFile(file(context, name)).openRead().use { input ->
            if (input.channel.size() > MAX_CHARS * 4L) return@use null
            input.reader(Charsets.UTF_8).readText().take(MAX_CHARS)
        }
    }.getOrNull()

    @Synchronized
    private fun write(context: Context, name: String, text: String) {
        runCatching {
            val atomic = AtomicFile(file(context, name))
            val output = atomic.startWrite()
            try { output.write(text.take(MAX_CHARS).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
            catch (failure: Exception) { atomic.failWrite(output) }
        }
    }
}

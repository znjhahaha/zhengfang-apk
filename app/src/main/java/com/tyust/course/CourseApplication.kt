package com.tyust.course

import android.app.Application
import com.tyust.course.manager.AppearanceSettingsManager
import com.tyust.course.manager.AppThemeCoordinator
import com.tyust.course.ui.system.GlassRuntimeGuard

class CourseApplication : Application() {
    private var mainProcess = false
    override fun onCreate() {
        super.onCreate()
        // Isolated UIDs cannot query ActivityManager on API 24-27. Exit before
        // process discovery or any singleton that reads application storage.
        if (android.os.Process.myUid() != applicationInfo.uid) return
        val processName = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else {
            runCatching { java.io.File("/proc/self/cmdline").inputStream().use {
                val bytes = ByteArray(256)
                val count = it.read(bytes)
                String(bytes, 0, count.coerceAtLeast(0)).substringBefore('\u0000')
            } }.getOrNull()
        }
        if (processName == packageName) {
            mainProcess = true
            com.tyust.course.diagnostics.AppDiagnostics.install(this)
            com.tyust.course.academic.plugin.AcademicProviderRegistry.initialize(this)
            GlassRuntimeGuard.initialize(this)
            AppearanceSettingsManager.initialize(this)
            AppThemeCoordinator.initialize(this)
            com.tyust.course.schedule.ScheduleReminderScheduler.get(this).start(this)
            com.tyust.course.schedule.ScheduleWidgetUpdater.start(this)
            com.tyust.course.usage.UsageStatsManager.initialize(this)
            com.tyust.course.survey.SurveyVisitTracker.initialize(this)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (mainProcess) {
            AppThemeCoordinator.configurationChanged()
            com.tyust.course.schedule.ScheduleWidgetUpdater.update(this)
        }
    }
}

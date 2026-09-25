package com.tyust.course.academic.plugin

import android.app.Activity
import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object PluginUpdates {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    fun start(app: Application) {
        app.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(480_032, ComponentName(app, PluginUpdateJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(24 * 60 * 60 * 1000L).build())
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { scope.launch { runCatching { check(app) } } }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
    suspend fun check(app: Context, force: Boolean = false) = gate.withLock {
        if (AcademicProviderRegistry.usingLocalCatalog) return@withLock
        val prefs = app.getSharedPreferences("plugin-updates", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", true)) return@withLock
        val store = AcademicProviderRegistry.packages()
        for (candidate in store.staged()) store.activateStaged(candidate.manifest.id)
        AcademicProviderRegistry.reload()
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("checkedAt", 0) < 86_400_000L) return@withLock
        // Failed checks are throttled briefly; a successful check owns the daily interval.
        if (!force && now - prefs.getLong("attemptedAt", 0) < 900_000L) return@withLock
        prefs.edit().putLong("attemptedAt", now).commit()
        val catalog = AcademicProviderRegistry.catalog()
        val entries = catalog.check()
        val known = AcademicProviderRegistry.knownPackages().filter { it.official }
        for (installed in known) {
            val next = entries.firstOrNull { it.getString("id") == installed.manifest.id } ?: continue
            if (PluginUpdatePolicy.compare(next.getString("version"), installed.manifest.version) <= 0) continue
            try {
                catalog.update(installed.manifest.id, stageOnly = true)
                store.activateStaged(installed.manifest.id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* An invalid download leaves the verified active package intact. */ }
        }
        prefs.edit().putLong("checkedAt", now).commit()
        AcademicProviderRegistry.reload()
    }
}

class PluginUpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        job = scope.launch { val failed = runCatching { PluginUpdates.check(this@PluginUpdateJobService) }.isFailure; jobFinished(params, failed) }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { job?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

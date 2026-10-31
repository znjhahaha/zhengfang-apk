package com.tyust.course.academic.plugin.widget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Scheduled pull refreshes for pull-mode widgets.
 *
 * One periodic job per plugin keeps the launcher's 30 minute fallback from being the only refresh; a
 * one-off job covers saves, boots and `onUpdate` (broadcast budgets are too small to run a plugin).
 */
class PluginWidgetPullService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val pluginId = params.extras.getString(EXTRA_PLUGIN_ID) ?: return false
        running[params.jobId] = scope.launch {
            try {
                PluginWidgetPull.refreshPlugin(this@PluginWidgetPullService, pluginId)
            } catch (e: CancellationException) {
                // Stopped by the system or superseded; the next schedule retries.
            } catch (t: Throwable) {
                Log.w(TAG, "pull job failed plugin=$pluginId", t)
            } finally {
                running.remove(params.jobId)
                withContext(NonCancellable + Dispatchers.Main) { jobFinished(params, false) }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PluginWidgetPull"
        private const val EXTRA_PLUGIN_ID = "pluginId"
        private const val PERIODIC_MS = 15L * 60 * 1000
        private const val FLEX_MS = 5L * 60 * 1000

        private fun scheduler(context: Context): JobScheduler? =
            context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler

        /** Stable per-plugin job id inside the host's job id space. */
        private fun periodicId(pluginId: String): Int = 0x5700_0000.toInt() or (pluginId.hashCode() and 0x00FF_FFFF)

        private fun immediateId(pluginId: String): Int = 0x5800_0000.toInt() or (pluginId.hashCode() and 0x00FF_FFFF)

        fun schedule(context: Context, pluginId: String) {
            val scheduler = scheduler(context) ?: return
            // Repair persisted jobs created before the plugin identity was included.
            if (scheduler.getPendingJob(periodicId(pluginId))?.extras?.getString(EXTRA_PLUGIN_ID) == pluginId) return
            val info = JobInfo.Builder(periodicId(pluginId), ComponentName(context, PluginWidgetPullService::class.java))
                .setExtras(PersistableBundle().apply { putString(EXTRA_PLUGIN_ID, pluginId) })
                .setPeriodic(PERIODIC_MS, FLEX_MS)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(info) }.onFailure {
                Log.w(TAG, "cannot schedule periodic pull plugin=$pluginId", it)
            }
        }

        /** Queues an off-cycle refresh; repeated calls collapse into one pending job. */
        fun requestImmediate(context: Context, pluginId: String) {
            val scheduler = scheduler(context) ?: return
            runCatching { scheduler.cancel(immediateId(pluginId)) }
            val info = JobInfo.Builder(immediateId(pluginId), ComponentName(context, PluginWidgetPullService::class.java))
                .setExtras(PersistableBundle().apply { putString(EXTRA_PLUGIN_ID, pluginId) })
                .setMinimumLatency(0)
                .build()
            runCatching { scheduler.schedule(info) }
        }

        fun cancel(context: Context, pluginId: String) {
            val scheduler = scheduler(context) ?: return
            runCatching { scheduler.cancel(periodicId(pluginId)) }
            runCatching { scheduler.cancel(immediateId(pluginId)) }
        }
    }
}

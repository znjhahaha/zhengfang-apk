package com.tyust.course.academic.plugin.widget

import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.tyust.course.BuildConfig
import com.tyust.course.academic.plugin.*
import com.tyust.course.manager.UserManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Force the real OS JobService, then verify independent persisted results from QuickJS. */
@RunWith(AndroidJUnit4::class)
class PluginWidgetJobDeviceTest {
    @Test fun periodicAndRefreshJobsExecuteAndKeepInstanceResultsSeparate() = runBlocking {
        assumeTrue(BuildConfig.UI_PREVIEW)
        val app = ApplicationProvider.getApplicationContext<Context>()
        val user = UserManager.getInstance()
        val previousSchool = user.currentSchool
        user.currentSchool = null
        val pluginId = "test.widget.jobs" + UUID.randomUUID().toString().replace("-", "")
        val source = "globalThis.plugin={data:{query:({input})=>({items:[{id:'row',title:'instance-'+input.appWidgetId}]})}};".toByteArray()
        val manifest = JSONObject("""{"id":"$pluginId","name":"Synthetic jobs","version":"1.0.0","apiVersion":3,
          "kind":"widget","entry":"index.js","minAppVersionCode":130,"permissions":["widget","runtime"],"capabilities":["data.query"],
          "requires":[{"name":"widget.render","version":1},{"name":"data.query","version":1}],
          "network":[],"files":{"index.js":"${PluginJson.sha256(source)}"},"contributes":{"pages":[],"entries":[],
          "dataProviders":[{"id":"$pluginId.data","title":"Synthetic"}],"widgets":[
          {"id":"pull","title":"Pull","sizes":["2x2"],"template":"text","refresh":{"mode":"pull","providerId":"$pluginId.data"}}]}}""")
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            listOf("manifest.json" to manifest.toString().toByteArray(), "index.js" to source).forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry()
            }
        } }.toByteArray()
        val pkg = AcademicProviderRegistry.packages().install(bytes, true)
        AcademicProviderRegistry.reload()
        val declaration = PluginWidgetContract.declaration(pkg.manifest, "pull")!!
        val bindings = (900001..900002).map { id ->
            PluginWidgetBindings.Binding(id, pluginId, "pull", "", "text", "light", "", JSONObject(), "2x2", System.currentTimeMillis())
                .also { PluginWidgetBindings.save(app, it) }
        }
        val jobs = app.getSystemService(JobScheduler::class.java)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun snapshot(binding: PluginWidgetBindings.Binding) = WidgetSnapshotStore.read(app, binding, declaration)
        suspend fun awaitResults(after: Long) = withTimeout(30_000) {
            while (bindings.any { (snapshot(it)?.updatedAt ?: 0) <= after }) delay(100)
        }
        try {
            PluginWidgetPullService.schedule(app, pluginId)
            val periodic = jobs.allPendingJobs.single { it.extras.getString("pluginId") == pluginId && it.isPeriodic }
            device.executeShellCommand("cmd jobscheduler run -f ${app.packageName} ${periodic.id}")
            awaitResults(0)
            bindings.forEach { assertEquals("instance-${it.appWidgetId}", snapshot(it)!!.payload.getJSONArray("rows").getJSONObject(0).getString("title")) }
            val previous = bindings.maxOf { snapshot(it)!!.updatedAt }
            PluginWidgets.handle(app, PluginWidgets.ACTION_REFRESH, bindings.first().appWidgetId)
            // Immediate jobs may already be executing by the time the pending list is read.
            jobs.allPendingJobs.firstOrNull { it.extras.getString("pluginId") == pluginId && !it.isPeriodic }?.let {
                device.executeShellCommand("cmd jobscheduler run -f ${app.packageName} ${it.id}")
            }
            awaitResults(previous)
            assertTrue(bindings.all { snapshot(it)!!.updatedAt > previous })
            AcademicProviderRegistry.packages().deactivate(pluginId)
            AcademicProviderRegistry.reload()
            PluginWidgets.availabilityChanged(app, pluginId)
            assertTrue(jobs.allPendingJobs.none { it.extras.getString("pluginId") == pluginId })
        } finally {
            PluginWidgets.clearPlugin(app, pluginId)
            AcademicProviderRegistry.removePlugin(pkg)
            AcademicProviderRegistry.reload()
            user.currentSchool = previousSchool
        }
    }
}

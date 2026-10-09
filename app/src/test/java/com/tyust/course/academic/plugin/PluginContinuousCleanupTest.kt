package com.tyust.course.academic.plugin

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class PluginContinuousCleanupTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val pkg = PluginPackage(PluginManifest(JSONObject("""{
        "id":"test.continuous.cleanup","kind":"native","version":"1.0.0","apiVersion":3,
        "servers":[{"id":"site","origin":"https://course.test","authentication":"web"}]
    }""")), "", "synthetic", false)

    private fun seed(account: String, handle: String): File {
        val scope = PluginStorageScope.base("service:${pkg.manifest.id}:site", account, pkg.manifest.id, true) + "\u0000https://course.test"
        val cache = File(app.noBackupFilesDir, "plugin-userscripts/${PluginStorageScope.hash(scope)}/fixture").apply { mkdirs() }
        File(cache, "original.user.js").writeText("// synthetic original")
        val task = File(app.noBackupFilesDir, "native-continuous-tasks/$handle.json")
        task.parentFile!!.mkdirs()
        task.writeText(JSONObject().put("handle", handle).put("namespace", scope).put("status", "stopped").put("pluginId", pkg.manifest.id).toString())
        return cache
    }

    @Test fun accountRemovalErasesOnlyItsScriptAndForegroundRecords() {
        val accounts = PluginServiceAccounts(app)
        val other = accounts.select(pkg, "site", "Other")
        val otherCache = seed(other, "t" + "1".repeat(32))
        val selected = accounts.select(pkg, "site", "Selected")
        val selectedCache = seed(selected, "t" + "2".repeat(32))
        accounts.clearCurrent(pkg, "site", selected)
        assertFalse(selectedCache.exists())
        assertFalse(File(app.noBackupFilesDir, "native-continuous-tasks/t${"2".repeat(32)}.json").exists())
        assertTrue(otherCache.exists())
        assertTrue(File(app.noBackupFilesDir, "native-continuous-tasks/t${"1".repeat(32)}.json").exists())
        accounts.clearPlugin(pkg)
        assertFalse(otherCache.exists())
        assertFalse(File(app.noBackupFilesDir, "native-continuous-tasks/t${"1".repeat(32)}.json").exists())
    }
}

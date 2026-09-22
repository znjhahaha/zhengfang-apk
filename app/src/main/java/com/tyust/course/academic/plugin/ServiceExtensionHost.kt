package com.tyust.course.academic.plugin

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tyust.course.manager.UserManager
import com.tyust.course.model.SchoolConfig
import com.tyust.course.ui.system.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ServiceExtensionEntry(val pkg: PluginPackage, val declaration: JSONObject)
class ServiceEntryPreferences(context: Context, private val accountScope: String) {
    private val preferences = context.getSharedPreferences("service-entry-visibility", Context.MODE_PRIVATE)
    private fun key(pluginId: String, entryId: String, placement: String) = PluginJson.sha256("$accountScope\u0000$pluginId\u0000$entryId\u0000$placement".toByteArray())
    fun visible(pluginId: String, entryId: String, placement: String) = preferences.getBoolean(key(pluginId, entryId, placement), true)
    fun setVisible(pluginId: String, entryId: String, placement: String, visible: Boolean) { preferences.edit().putBoolean(key(pluginId, entryId, placement), visible).apply() }
    companion object {
        fun accountScope() = UserManager.getInstance().let { "${it.currentSchool?.id.orEmpty()}:${it.currentAccountStorageKey}" }
        fun placementName(value: String) = when (value) { "home" -> "课程首页"; "schedule" -> "课表页"; "grades" -> "成绩页"; else -> value }
        fun declared(packages: List<PluginPackage>, school: SchoolConfig?, placement: String): List<ServiceExtensionEntry> = packages
            .filter { it.official && it.manifest.isService && school != null && ServicePluginContract.matches(it.manifest, school) }
            .flatMap { pkg -> PluginJson.objects(pkg.manifest.service!!.getJSONArray("entries")).filter { placement in PluginJson.strings(it.optJSONArray("placements")) }.map { ServiceExtensionEntry(pkg, it) } }
            .sortedWith(compareBy({ it.declaration.getInt("order") }, { it.pkg.manifest.id }, { it.declaration.getString("id") }))
    }
}

/** Static entry labels never execute plugin code until the user opens a service. */
@Composable
fun ServiceExtensionHost(placement: String?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val account = ServiceEntryPreferences.accountScope()
    var entries by remember(account, placement) { mutableStateOf<List<ServiceExtensionEntry>>(emptyList()) }
    LaunchedEffect(account, placement, lifecycle) {
        if (placement != null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val school = UserManager.getInstance().currentSchool
            entries = withContext(Dispatchers.IO) {
                val preferences = ServiceEntryPreferences(context, account)
                try {
                    ServiceEntryPreferences.declared(AcademicProviderRegistry.packages().list(), school, placement)
                        .filter { AcademicProviderRegistry.isEnabled(it.pkg.manifest.id) && preferences.visible(it.pkg.manifest.id, it.declaration.getString("id"), placement) }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { emptyList() }
            }
            awaitCancellation()
        }
    }
    val baseInset = LocalAppOverlayBottomInset.current
    val extra = if (entries.isEmpty()) 0.dp else 60.dp
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalAppOverlayBottomInset provides baseInset + extra) { content() }
        if (entries.isNotEmpty()) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = baseInset + 6.dp)
            .horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp).testTag("service-entries-$placement"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            entries.forEach { entry ->
                LiquidButton({ ServicePluginActivity.open(context, entry.pkg, entry.declaration.getString("pageId")) },
                    style = LiquidButtonStyle.Tinted, minHeight = 44.dp, modifier = Modifier.testTag("service-entry-${entry.pkg.manifest.id}-${entry.declaration.getString("id")}")) {
                    Text(entry.declaration.getString("title"))
                }
            }
        }
    }
}

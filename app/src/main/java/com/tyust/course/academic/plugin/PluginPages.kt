package com.tyust.course.academic.plugin

import android.content.Context
import android.content.Intent
import com.tyust.course.MainActivity
import com.tyust.course.manager.UserManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

object PluginPages {
    private var app: Context? = null
    private val changed = MutableStateFlow(0L)
    val revision = changed.asStateFlow()
    val requested = MutableStateFlow<String?>(null)
    var registry = PluginPageRegistry()
        private set
    fun initialize(context: Context) {
        app = context.applicationContext
        val prefs = context.getSharedPreferences("plugin-pages-v3", Context.MODE_PRIVATE)
        registry = PluginPageRegistry(runCatching { JSONObject(prefs.getString("state", "{}")!!) }.getOrDefault(JSONObject())) {
            check(prefs.edit().putString("state", it.toString()).commit()) { "页面设置保存失败" }
            changed.value++
        }
        refresh()
    }
    fun capabilities(): Map<String, Int> = app?.let { context -> PluginJson.objects(JSONArray(context.assets.open("academic-plugin/host-capabilities.json").bufferedReader().use { it.readText() })).associate { it.getString("name") to it.getInt("version") } }.orEmpty()
    fun available(pkg: PluginPackage): Boolean {
        val school = UserManager.getInstance().currentSchool
        return AcademicProviderRegistry.isEnabled(pkg.manifest.id) &&
            (school?.let { AcademicProviderRegistry.isEnabled(pkg.manifest.id, it) && AcademicProviderRegistry.matches(pkg, it) }
                ?: (!pkg.manifest.isAcademic && (pkg.manifest.json.optJSONArray("matches")?.length() ?: 0) == 0)) &&
            runCatching { PluginPlatformContract.requireCompatible(pkg.manifest, com.tyust.course.BuildConfig.VERSION_CODE, capabilities()) }.isSuccess
    }
    fun refresh() {
        if (app == null) return
        val all = AcademicProviderRegistry.packages().list().filter { it.manifest.isNative }
        val active = all.filter(::available).map { it.manifest.id }.toSet()
        registry.synchronize(all.map { it.manifest }, active, capabilities())
    }
    fun open(context: Context, route: String, params: JSONObject = JSONObject()) {
        val page = registry.page(route) ?: throw PluginException(PluginErrorCode.UNSUPPORTED, "页面已移除或插件已停用")
        if (page.pluginId == null) {
            requested.value = route
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("pageId", route).putExtra("pageParams", params.toString()))
        } else {
            val pkg = AcademicProviderRegistry.packages().active(page.pluginId) ?: throw PluginException(PluginErrorCode.UNSUPPORTED, "插件未安装")
            NativePluginActivity.open(context, pkg, page.id, params)
        }
    }
}

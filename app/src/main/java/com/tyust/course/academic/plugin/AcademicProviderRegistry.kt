package com.tyust.course.academic.plugin

import android.content.Context
import com.tyust.course.academic.*
import com.tyust.course.model.SchoolConfig
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Installed packages are resolved once per adapter, so running tasks retain their exact source/version. */
object AcademicProviderRegistry {
    private var app: Context? = null
    private var store: PluginPackageStore? = null
    @Volatile private var installed: Map<String, PluginPackage> = emptyMap()
    fun initialize(context: Context) { app = context.applicationContext; store = PluginPackageStore(context, localKeys()); runCatching { reload() } }
    fun packages(): PluginPackageStore = store ?: error("Plugin registry has not been initialized")
    private fun preferences() = app?.getSharedPreferences("plugin-local-catalog", Context.MODE_PRIVATE)
    private fun localKeys(): Map<String, java.security.PublicKey> {
        if (!com.tyust.course.BuildConfig.DEBUG) return emptyMap()
        return runCatching { val key = JSONObject(preferences()?.getString("key", null) ?: return emptyMap())
            mapOf(key.getString("keyId") to PluginPackageVerifier.publicKey(key.getString("spki"))) }.getOrDefault(emptyMap())
    }
    fun configureLocalCatalog(url: String, publicKey: String) {
        require(com.tyust.course.BuildConfig.DEBUG) { "本地目录仅供调试版本使用" }
        val parsed = java.net.URI(url)
        require(parsed.scheme == "http" && parsed.host in setOf("127.0.0.1", "localhost", "10.0.2.2") && parsed.userInfo == null) { "本地验收目录必须使用回环地址" }
        val key = JSONObject(publicKey)
        PluginPackageVerifier.publicKey(key.getString("spki"))
        require(key.getString("keyId").isNotBlank())
        preferences()?.edit()?.putString("url", url)?.putString("key", publicKey)?.commit()
        store = PluginPackageStore(app!!, localKeys()); reload()
    }
    fun catalog(): PluginCatalogClient? {
        if (!com.tyust.course.BuildConfig.DEBUG) return null
        val url = preferences()?.getString("url", null) ?: return null
        return PluginCatalogClient(url, localKeys(), packages())
    }
    fun reload() { installed = store?.list().orEmpty().associateBy { it.manifest.id } }
    fun resolve(school: SchoolConfig): PluginPackage? {
        val explicit = school.academicProvider.orEmpty()
        if (explicit.startsWith("builtin.")) return null
        if (explicit.isNotBlank()) return installed[explicit]
            ?: throw AcademicException(AcademicStatus.UNSUPPORTED, "该学校适配尚未安装，请导入或恢复内置适配")
        return installed.values.firstOrNull { it.official && it.manifest.school.getString("id") == school.id }
    }
    fun hasBinding(school: SchoolConfig) = school.academicProvider.orEmpty().isNotBlank() && !school.academicProvider.startsWith("builtin.") ||
        installed.values.any { it.official && it.manifest.school.getString("id") == school.id }
    fun hasCapability(school: SchoolConfig, operation: String): Boolean = runCatching {
        val pkg = resolve(school)
        pkg == null || operation in pkg.manifest.capabilities || pkg.manifest.baseProvider != null
    }.getOrDefault(false)
    fun overrides(school: SchoolConfig, operation: String): Boolean = runCatching { operation in resolve(school)?.manifest?.capabilities.orEmpty() }.getOrDefault(false)
    fun adapter(school: SchoolConfig, session: AcademicSession): PluginAcademicAdapter? {
        val pkg = resolve(school) ?: return null
        val baseSchool = pkg.manifest.baseProvider?.let { provider -> SchoolConfig.fromJson(school.toJson()).apply {
            academicProvider = ""; academicSystem = provider.removePrefix("builtin.").let { if (it == "legacy_zf") "zf" else it }
        } }
        val base = baseSchool?.let { AcademicGatewayFactory.createBuiltin(it, session) }
        val study = baseSchool?.let { AcademicStudyReader(it, session, AcademicHttpTransport(it, session)) }
        return PluginAcademicAdapter(app ?: error("Plugin registry has not been initialized"), pkg, session, base, study, baseSchool)
    }
    fun school(pkg: PluginPackage): SchoolConfig = SchoolConfig.fromJson(pkg.manifest.school).apply {
        academicProvider = pkg.manifest.id
        academicSystem = pkg.manifest.baseProvider?.removePrefix("builtin.") ?: "auto"
    }
}

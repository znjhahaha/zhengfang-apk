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
    const val OFFICIAL_WEBSITE = "https://plugins.hidisiwa.xyz"
    const val OFFICIAL_CATALOG = "$OFFICIAL_WEBSITE/academic-plugins/catalog.json"
    @Volatile private var localEndpoint: String? = null
    val usingLocalCatalog: Boolean get() = localEndpoint != null
    @Volatile private var installed: Map<String, PluginPackage> = emptyMap()
    fun initialize(context: Context) { app = context.applicationContext; localEndpoint = null; store = PluginPackageStore(context, trustedKeys()); runCatching { reload() } }
    fun packages(): PluginPackageStore = store ?: error("Plugin registry has not been initialized")
    private fun preferences() = app?.getSharedPreferences("plugin-local-catalog", Context.MODE_PRIVATE)
    private fun localKeys(): Map<String, java.security.PublicKey> {
        if (!com.tyust.course.BuildConfig.DEBUG) return emptyMap()
        return runCatching { val key = JSONObject(preferences()?.getString("key", null) ?: return emptyMap())
            mapOf(key.getString("keyId") to PluginPackageVerifier.publicKey(key.getString("spki"))) }.getOrDefault(emptyMap())
    }
    private fun trustedKeys(): Map<String, java.security.PublicKey> {
        val key = JSONObject(app!!.assets.open("academic-plugin/official-catalog-key.json").bufferedReader().use { it.readText() })
        return localKeys() + mapOf(key.getString("keyId") to PluginPackageVerifier.publicKey(key.getString("spki")))
    }
    fun configureLocalCatalog(url: String, publicKey: String) {
        require(com.tyust.course.BuildConfig.DEBUG) { "本地目录仅供调试版本使用" }
        val parsed = java.net.URI(url)
        require(parsed.scheme == "http" && parsed.host in setOf("127.0.0.1", "localhost", "10.0.2.2") && parsed.userInfo == null) { "本地验收目录必须使用回环地址" }
        val key = JSONObject(publicKey)
        PluginPackageVerifier.publicKey(key.getString("spki"))
        require(key.getString("keyId").isNotBlank())
        preferences()?.edit()?.remove("url")?.putString("key", publicKey)?.commit()
        localEndpoint = url
        store = PluginPackageStore(app!!, trustedKeys()); reload()
    }
    fun catalog(): PluginCatalogClient = PluginCatalogClient(localEndpoint ?: OFFICIAL_CATALOG, trustedKeys(), packages())
    fun restoreOfficialCatalog() { localEndpoint = null; preferences()?.edit()?.remove("url")?.apply() }
    fun services(school: SchoolConfig): List<PluginPackage> = installed.values.filter { it.manifest.isService && ServicePluginContract.matches(it.manifest, school) }
    fun isEnabled(id: String): Boolean = id in installed
    fun reload() { installed = store?.list().orEmpty().associateBy { it.manifest.id } }
    fun resolve(school: SchoolConfig): PluginPackage? {
        val explicit = school.academicProvider.orEmpty()
        if (explicit.startsWith("builtin.")) return null
        if (explicit.isNotBlank()) return installed[explicit]?.takeUnless { it.manifest.isService }
            ?: throw AcademicException(AcademicStatus.UNSUPPORTED, "该学校适配尚未安装，请导入或恢复内置适配")
        return installed.values.firstOrNull { !it.manifest.isService && it.official && it.manifest.school.getString("id") == school.id }
    }
    fun hasBinding(school: SchoolConfig) = school.academicProvider.orEmpty().isNotBlank() && !school.academicProvider.startsWith("builtin.") ||
        installed.values.any { !it.manifest.isService && it.official && it.manifest.school.getString("id") == school.id }
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
        require(!pkg.manifest.isService) { "校园服务不能替换学校教务" }
        academicProvider = pkg.manifest.id
        academicSystem = pkg.manifest.baseProvider?.removePrefix("builtin.") ?: "auto"
    }
}

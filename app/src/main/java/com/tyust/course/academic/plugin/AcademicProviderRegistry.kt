package com.tyust.course.academic.plugin

import kotlinx.coroutines.flow.asStateFlow

import com.tyust.course.manager.UserManager
import android.content.Context
import com.tyust.course.academic.*
import com.tyust.course.model.SchoolConfig
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Installed packages are resolved once per adapter, so running tasks retain their exact source/version. */
object AcademicProviderRegistry {
    private val providerChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revision = providerChanges.asStateFlow()
    private fun providersChanged() { providerChanges.value++; PluginPages.refresh() }

    fun operationProvider(school: SchoolConfig, operation: String): PluginPackage? = runCatching {
        val pkg = resolve(school) ?: return@runCatching null
        if (operation in pkg.manifest.capabilities) pkg
        else if (operation == "selection.filters" && "selection.catalog" in pkg.manifest.capabilities) null
        else BuiltinAcademicInheritance.providers[pkg.manifest.baseProvider]?.let(::protocolPackage)
            ?.takeIf { operation in it.manifest.capabilities }
    }.getOrNull()

    private var app: Context? = null
    private var store: PluginPackageStore? = null
    const val OFFICIAL_WEBSITE = "https://plugins.hidisiwa.xyz"
    const val OFFICIAL_CATALOG = "$OFFICIAL_WEBSITE/academic-plugins/catalog.json"
    const val OFFICIAL_CATALOG_V3 = "$OFFICIAL_WEBSITE/academic-plugins-v3/catalog.json"
    const val OFFICIAL_CATALOG_V2 = "$OFFICIAL_WEBSITE/academic-plugins-v3/catalog-v2.json"
    @Volatile private var localEndpoint: String? = null
    val usingLocalCatalog: Boolean get() = localEndpoint != null
    @Volatile private var installed: Map<String, PluginPackage> = emptyMap()
    @Volatile private var bundled: Map<String, PluginPackage> = emptyMap()
    fun initialize(context: Context) {
        app = context.applicationContext; localEndpoint = null
        PluginTrace.initialize(context)
        bundled = BundledAcademicProviders.load { context.assets.open(it).use { stream -> stream.readBytes() } }
        store = PluginPackageStore(context, trustedKeys()); runCatching { reload() }
    }
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
    fun catalog(): PluginCatalogClient = PluginCatalogClient(localEndpoint ?: OFFICIAL_CATALOG_V2, trustedKeys(), packages(), if (localEndpoint == null) OFFICIAL_CATALOG_V3 else null)
    fun knownPackage(id: String): PluginPackage? = installed[id] ?: bundled[id]
    fun knownPackages(): List<PluginPackage> = (bundled + installed).values.toList()
    fun installedOverride(pkg: PluginPackage) = store?.activeDigest(pkg.manifest.id) != null
    private fun removalFallback(pkg: PluginPackage, school: SchoolConfig): String? =
        bundled[pkg.manifest.id]?.takeIf { matches(it, school) }?.manifest?.id
            ?: pkg.manifest.baseProvider?.takeIf { BuiltinAcademicInheritance.providers[it] in bundled }
    private fun affectedSchools(pkg: PluginPackage): List<SchoolConfig> {
        val user = UserManager.getInstance()
        return (user.supportedSchools + listOfNotNull(user.currentSchool)).associateBy { it.id }.values.filter {
            manualChoice(it) == pkg.manifest.id || runCatching { resolve(it)?.manifest?.id == pkg.manifest.id }.getOrDefault(false)
        }
    }
    fun removalDescription(pkg: PluginPackage): String {
        val schools = affectedSchools(pkg)
        val affected = schools.joinToString("\n") { school ->
            school.name + if (removalFallback(pkg, school) != null && installedOverride(pkg)) "：恢复对应内置适配" else "：需要重新选择适配"
        }
        return "取消插件调用与后续任务，移除插件登录状态和授权。保留学校、已缓存课表成绩及待核对的业务记录。" +
            if (affected.isBlank()) "" else "\n\n$affected"
    }
    fun removePlugin(pkg: PluginPackage) {
        val current = store?.active(pkg.manifest.id)
        if (current != null && current.digest != pkg.digest) throw PluginException(PluginErrorCode.CONFLICT, "插件版本已改变，请重新查看后卸载")
        val schools = affectedSchools(pkg)
        val fallbacks = schools.associateWith { if (installedOverride(pkg)) removalFallback(pkg, it) else null }
        if (installedOverride(pkg)) packages().deactivate(pkg.manifest.id) else setEnabled(pkg.manifest.id, false)
        for ((school, fallback) in fallbacks) {
            school.academicProvider = fallback ?: "unconfigured"
            UserManager.getInstance().updateSchoolConfig(school)
            choose(school, school.academicProvider)
        }
        reload()
    }
    fun hasBuiltinFallback(pkg: PluginPackage): Boolean = pkg.manifest.isAcademic &&
        UserManager.getInstance().getSchoolById(pkg.manifest.school.optString("id"))?.let { removalFallback(pkg, it) != null } == true
    fun restoreBuiltin(pkg: PluginPackage) {
        val school = UserManager.getInstance().getSchoolById(pkg.manifest.school.getString("id")) ?: return
        val fallback = removalFallback(pkg, school) ?: return
        if (fallback == pkg.manifest.id) packages().deactivate(pkg.manifest.id)
        school.academicProvider = fallback
        UserManager.getInstance().updateSchoolConfig(school); choose(school, fallback); reload()
    }
    fun restoreOfficialCatalog() { localEndpoint = null; preferences()?.edit()?.remove("url")?.apply() }
    fun services(school: SchoolConfig): List<PluginPackage> = installed.values.filter { (it.manifest.isService || it.manifest.isNative) && isEnabled(it.manifest.id, school) && matches(it, school) }
    fun matches(pkg: PluginPackage, school: SchoolConfig): Boolean {
        val manifest = pkg.manifest
        if (manifest.isService && ServicePluginContract.matches(manifest, school)) return true
        val metadata = if (pkg.official) store?.metadata(manifest.id) else null
        val aliases = listOf("matches", "aliases").flatMap { metadata?.optJSONArray(it)?.let(PluginJson::objects).orEmpty() }
        val declared = if (manifest.isNative) manifest.json.optJSONArray("matches")?.let(PluginJson::objects).orEmpty() else emptyList()
        if (manifest.isNative && !manifest.isAcademic && declared.isEmpty() && aliases.isEmpty() && !manifest.json.has("school")) return true
        return (aliases + declared + listOfNotNull(PluginSchoolMatcher.primary(manifest))).any { PluginSchoolMatcher.matches(it, school) }
    }
    private fun schoolPrefs() = app?.getSharedPreferences("plugin-school-bindings", Context.MODE_PRIVATE)
    private fun schoolKey(school: SchoolConfig) = PluginJson.sha256(PluginSchoolMatcher.key(school).toByteArray())
    fun isEnabled(id: String, school: SchoolConfig) = isEnabled(id) && schoolPrefs()?.getBoolean("disabled:${schoolKey(school)}:$id", false) != true
    fun setSchoolEnabled(id: String, school: SchoolConfig, enabled: Boolean) {
        if (!enabled) app?.let { PluginAcademicSession.revoke(it, id); PluginDataGuard.revoke(it, id) }
        schoolPrefs()?.edit()?.putBoolean("disabled:${schoolKey(school)}:$id", !enabled)?.apply()
        providersChanged()
    }
    @Synchronized fun choose(school: SchoolConfig, id: String?) { schoolPrefs()?.edit()?.apply { if (id == null) remove("provider:${schoolKey(school)}") else putString("provider:${schoolKey(school)}", id) }?.apply(); providersChanged() }
    fun manualChoice(school: SchoolConfig): String = schoolPrefs()?.getString("provider:${schoolKey(school)}", null) ?: school.academicProvider.orEmpty()
    fun candidates(school: SchoolConfig): List<PluginPackage> = installed.values.filter { it.manifest.isAcademic && it.official && isEnabled(it.manifest.id, school) && matches(it, school) }.sortedBy { it.manifest.id }
    fun contributions(school: SchoolConfig, kind: String): List<Pair<PluginPackage, JSONObject>> = services(school).filter { it.manifest.isNative }.flatMap { pkg -> pkg.manifest.contributes.optJSONArray(kind)?.let(PluginJson::objects).orEmpty().map { pkg to it } }
    fun isEnabled(id: String): Boolean = (id in bundled || store?.activeDigest(id) != null) && schoolPrefs()?.getBoolean("disabled:global:$id", false) != true
    fun setEnabled(id: String, enabled: Boolean) {
        if (!enabled) { PluginOperation.cancelPlugin(id); PluginAcademicSession.cancelProvider(id); PluginHttpClients.clearPlugin(id) }
        if (!enabled) app?.let { PluginAcademicSession.revoke(it, id); PluginDataGuard.revoke(it, id) }
        schoolPrefs()?.edit()?.putBoolean("disabled:global:$id", !enabled)?.commit()
        if (!enabled) app?.let { NativePluginTasks.stopPlugin(it, id); PluginForegroundWork.stopPlugin(id, "interrupted"); PluginLegacyWebSessions.revoke(id) }
        providersChanged()
    }
    fun isCurrentPackage(id: String, digest: String): Boolean = (store?.activeDigest(id) ?: bundled[id]?.digest) == digest
    fun reload() { installed = store?.list().orEmpty().associateBy { it.manifest.id }; providersChanged() }
    fun resolve(school: SchoolConfig): PluginPackage? {
        val explicit = manualChoice(school)
        if (explicit == "unconfigured") throw AcademicException(AcademicStatus.UNSUPPORTED, "本校尚未配置教务适配，请选择或安装适配")
        if (explicit.startsWith("builtin.")) return builtin(school, explicit.removePrefix("builtin."))
        if (explicit in GenericAcademicProtocols.providers.values) return builtin(school, GenericAcademicProtocols.providers.entries.first { it.value == explicit }.key)
        if (explicit.isNotBlank() && !isEnabled(explicit, school) && explicit in installed) return null
        if (explicit.isNotBlank()) return installed[explicit]?.takeIf { it.manifest.isAcademic }
            ?: builtin(school)?.takeIf { it.manifest.id == explicit }
            ?: throw AcademicException(AcademicStatus.UNSUPPORTED, "该学校适配尚未安装，请导入或恢复内置适配")
        val candidates = candidates(school)
        if (candidates.size > 1) throw AcademicException(AcademicStatus.UNSUPPORTED, "本校有多个教务适配，请到插件中心选择并记忆")
        return candidates.singleOrNull() ?: builtin(school)
    }
    private fun protocolPackage(id: String): PluginPackage? = installed[id]?.takeIf { it.official && isEnabled(id) } ?: bundled[id]?.takeIf { isEnabled(id) }
    private fun builtin(school: SchoolConfig, choice: String = school.academicSystem): PluginPackage? {
        BundledAcademicProviders.matching(school, choice)?.let { return protocolPackage(it.id)?.takeIf { pkg -> isEnabled(pkg.manifest.id, school) } }
        val selected = if (choice == "auto") school.academicSystem else choice
        // Explicitly changing a specialized vendor to another built-in cannot silently select an unrelated school.
        if (school.academicSystem in setOf("jinzhi", "chengfang") && selected != school.academicSystem) return null
        return GenericAcademicProtocols.providers[selected]?.let { id -> protocolPackage(id)?.takeIf { isEnabled(it.manifest.id, school) }?.let { GenericAcademicProtocols.bind(it, school) } }
    }
    fun prepareBuiltinSchool(school: SchoolConfig) {
        val pkg = resolve(school)?.takeIf { it.bundled && it.manifest.id !in GenericAcademicProtocols.providers.values } ?: return
        school.academicSystem = pkg.manifest.school.getString("academicSystem")
        // The bundled providers own the site's root context, including browser cookie import.
        school.basePath = ""
    }
    fun builtinAdapter(school: SchoolConfig, session: AcademicSession): PluginAcademicAdapter? {
        val choice = manualChoice(school)
        if (choice.startsWith("builtin.") && choice !in setOf("builtin.auto", "builtin.${school.academicSystem}")) return null
        return builtin(school)?.let { PluginAcademicAdapter(app ?: error("Plugin registry has not been initialized"), it, session) }
    }
    fun hasBinding(school: SchoolConfig): Boolean {
        val choice = manualChoice(school)
        if (choice.startsWith("builtin.")) return builtin(school, choice.removePrefix("builtin.")) != null
        return choice.isNotBlank() || candidates(school).isNotEmpty() || builtin(school) != null
    }
    fun hasCapability(school: SchoolConfig, operation: String): Boolean = operationProvider(school, operation) != null
    fun overrides(school: SchoolConfig, operation: String): Boolean = runCatching {
        val pkg = resolve(school) ?: return@runCatching false
        operation in pkg.manifest.capabilities || BuiltinAcademicInheritance.providers[pkg.manifest.baseProvider]?.let {
            operation in protocolPackage(it)?.manifest?.capabilities.orEmpty()
        } == true
    }.getOrDefault(false)
    fun adapter(school: SchoolConfig, session: AcademicSession): PluginAcademicAdapter? {
        val pkg = resolve(school) ?: return null
        return adapterFor(pkg, school, session)
    }
    internal fun authenticationPackage(school: SchoolConfig): PluginPackage? {
        val pkg = resolve(school) ?: return null
        if ("auth.start" in pkg.manifest.capabilities) return pkg
        val id = BuiltinAcademicInheritance.providers[pkg.manifest.baseProvider] ?: return null
        return protocolPackage(id)?.let { BuiltinAcademicInheritance.inherit(pkg, it, school) }
    }
    fun adapterFor(pkg: PluginPackage, school: SchoolConfig, session: AcademicSession): PluginAcademicAdapter {
        val baseSchool = pkg.manifest.baseProvider?.let { provider -> SchoolConfig.fromJson(school.toJson()).apply {
            academicProvider = ""; academicSystem = provider.removePrefix("builtin.").let { if (it == "legacy_zf") "zf" else it }
        } }
        val context = app ?: error("Plugin registry has not been initialized")
        val inherited = BuiltinAcademicInheritance.providers[pkg.manifest.baseProvider]?.let { id ->
            BuiltinAcademicInheritance.inherit(pkg, protocolPackage(id) ?: throw AcademicException(AcademicStatus.UNSUPPORTED, "公共协议插件已停用或缺失"), school)
        }
        val base = inherited?.let { PluginAcademicAdapter(context, it, session) }
        val study = base as? AcademicStudyAdapter
        return PluginAcademicAdapter(context, pkg, session, base, study, baseSchool, inheritedPackage = inherited)
    }
    fun school(pkg: PluginPackage): SchoolConfig = SchoolConfig.fromJson(pkg.manifest.school).apply {
        require(pkg.manifest.isAcademic) { "普通界面插件不能替换学校教务" }
        academicProvider = pkg.manifest.id
        academicSystem = pkg.manifest.baseProvider?.removePrefix("builtin.") ?: pkg.manifest.school.optString("academicSystem", "auto")
    }
}

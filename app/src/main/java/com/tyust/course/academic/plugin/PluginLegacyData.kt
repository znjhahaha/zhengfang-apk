package com.tyust.course.academic.plugin

import android.content.Context
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionKey
import com.tyust.course.model.SchoolConfig

/** Keep 1.0.87 native data in its original account scope while removing version
 * suffixes. Adopting a declared server creates an independent service account. */
object PluginLegacyData {
    private fun legacy(pkg: PluginPackage): Boolean = pkg.manifest.isNative &&
        listOf("category", "servers", "services", "serviceDependencies").none(pkg.manifest.json::has) &&
        listOf("commands", "settings").none(pkg.manifest.contributes::has) &&
        pkg.manifest.contributes.optJSONArray("pages")?.let(PluginJson::objects).orEmpty().none { it.has("renderer") || it.has("dynamic") }
    private fun versions(pkg: PluginPackage): List<PluginPackage> = listOf(pkg) +
        runCatching { AcademicProviderRegistry.packages().lineage(pkg.manifest.id).filter { it != pkg.digest }.mapNotNull { digest ->
            runCatching { AcademicProviderRegistry.packages().readDigest(digest) }.getOrNull()
        } }.getOrDefault(emptyList())
    fun retainsAccountScope(app: Context, pkg: PluginPackage): Boolean {
        if (pkg.manifest.json.has("servers")) return false
        val prefs = app.getSharedPreferences("plugin-data-scopes", Context.MODE_PRIVATE)
        val key = "${pkg.manifest.id}/legacyAccountScope/${!pkg.official}"
        if (prefs.getBoolean(key, false)) return true
        return versions(pkg).any { it.official == pkg.official && legacy(it) }.also { if (it) check(prefs.edit().putBoolean(key, true).commit()) }
    }
    fun session(app: Context, pkg: PluginPackage, school: SchoolConfig?, account: String): AcademicSession {
        if (retainsAccountScope(app, pkg)) {
            val key = school?.let(PluginSchoolMatcher::key) ?: "global"
            return AcademicSession(AcademicSessionKey(key, if (pkg.official) "$key:$account" else "dev:${pkg.digest}"),
                school?.let(PluginSchoolMatcher::endpoint)?.toString() ?: "https://invalid.example/")
        }
        return AcademicSession(AcademicSessionKey(if (pkg.manifest.isAcademic) school?.let(PluginSchoolMatcher::key) ?: "global" else "plugin:${pkg.manifest.id}",
            if (pkg.manifest.isAcademic) account else "default"), "https://invalid.example/")
    }
    fun namespaces(app: Context, pkg: PluginPackage, session: AcademicSession): List<String> {
        if (!pkg.official || session.key.schoolId.startsWith("service:")) return emptyList()
        val prefs = app.getSharedPreferences("plugin-data-scopes", Context.MODE_PRIVATE)
        val key = "${pkg.manifest.id}/legacyNamespaces/" + PluginStorageScope.hash(PluginStorageScope.session(session, pkg.manifest.id, false))
        val saved = prefs.getStringSet(key, emptySet()).orEmpty()
        val found = versions(pkg).filter { it.official && it.manifest.id == pkg.manifest.id && legacy(it) }
            .map { session.key.schoolId + "\u0000" + session.key.accountKey + "\u0000" + it.digest }
        val all = saved + found
        if (all != saved) check(prefs.edit().putStringSet(key, all).commit())
        return all.toList()
    }
}

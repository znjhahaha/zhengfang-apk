package com.tyust.course.academic.plugin

import com.tyust.course.model.SchoolConfig
import org.json.JSONObject

data class SchoolSearchProvider(val id: String, val name: String, val version: String,
    val school: SchoolConfig, val incompatibleReason: String? = null, val installed: Boolean = false)
data class SchoolSearchResult(val school: SchoolConfig, val configured: Boolean, val providers: List<SchoolSearchProvider>)

object PluginSchoolSearch {
    fun catalog(entries: List<JSONObject>, appVersion: Int, capabilities: Map<String, Int>,
        installed: List<PluginManifest>): List<SchoolSearchProvider> = entries.mapNotNull { entry ->
        val selected = PluginUpdatePolicy.select(entry, appVersion, capabilities, installed)
        val release = selected ?: (entry.optJSONArray("releases")?.let(PluginJson::objects) ?: listOf(entry))
            .filter { PluginUpdatePolicy.version(it.optString("version")) != null && !it.optBoolean("withdrawn") }
            .maxWithOrNull { a, b -> PluginUpdatePolicy.compare(a.getString("version"), b.getString("version")) }
            ?: return@mapNotNull null
        val kind = release.optString("kind", entry.optString("kind"))
        if (kind == "service" || kind == "native" && !release.optBoolean("academic") && !release.optJSONObject("contributes").let { it?.optBoolean("academic") == true }) return@mapNotNull null
        val schoolJson = release.optJSONObject("school") ?: entry.optJSONObject("school") ?: return@mapNotNull null
        val school = SchoolConfig.fromJson(schoolJson)
        if (school.id.isNullOrBlank() || school.name.isNullOrBlank() || PluginSchoolMatcher.endpoint(school) == null)
            throw PluginException(PluginErrorCode.VALIDATION_FAILED, "签名目录中的学校配置无效")
        val reason = if (selected != null) null else when {
            release.optInt("minAppVersionCode") > appVersion -> "需要 App 版本代码 ${release.optInt("minAppVersionCode")} 或以上"
            release.optInt("apiVersion", 1) !in 1..PluginLimits.API_VERSION -> "需要更新的插件 API"
            else -> "当前宿主能力或依赖服务版本不兼容"
        }
        SchoolSearchProvider(entry.getString("id"), release.optString("name", entry.optString("name", school.name)),
            release.getString("version"), school, reason)
    }

    fun merge(query: String, local: List<SchoolConfig>, remote: List<SchoolSearchProvider>,
        installed: List<SchoolSearchProvider> = emptyList()): List<SchoolSearchResult> {
        val rows = local.distinctBy { it.id }.map { SchoolSearchResult(it, true, emptyList()) }.toMutableList()
        // Active, verified packages remain selectable even when a newer release needs another host.
        val providers = (remote + installed).associateBy { it.id }.values
        for (provider in providers) {
            val index = rows.indexOfFirst { it.school.id == provider.school.id || PluginSchoolMatcher.key(it.school) == PluginSchoolMatcher.key(provider.school) }
            if (index < 0) rows += SchoolSearchResult(provider.school, false, listOf(provider))
            else rows[index] = rows[index].copy(providers = rows[index].providers + provider)
        }
        val needle = query.trim().replace(Regex("\\s+"), "")
        return rows.filter { needle.isBlank() || it.school.name.replace(Regex("\\s+"), "").contains(needle, ignoreCase = true) ||
            it.providers.any { p -> p.school.name.replace(Regex("\\s+"), "").contains(needle, ignoreCase = true) } }
            .sortedWith(compareByDescending<SchoolSearchResult> { it.school.name == needle }.thenBy { it.school.name })
    }

    fun shouldOfferAdd(query: String, submitted: Boolean, catalogSucceeded: Boolean, matches: List<SchoolSearchResult>) =
        submitted && query.isNotBlank() && catalogSucceeded && matches.isEmpty()

    /** Preserve existing IDs, custom endpoints and account settings; never merge by name alone. */
    fun existing(local: List<SchoolConfig>, incoming: SchoolConfig): SchoolConfig? = local.firstOrNull { it.id == incoming.id }
        ?: local.firstOrNull { PluginSchoolMatcher.key(it) == PluginSchoolMatcher.key(incoming) }
}

/** Consent/download callbacks belong to a single visible search session. */
class SchoolSearchSession {
    private var generation = 0L
    private var request: Long? = null
    fun begin(): Long? = if (request != null) null else (++generation).also { request = it }
    fun current(token: Long) = request == token && generation == token
    fun cancel() { generation++; request = null }
    fun finish(token: Long) { if (current(token)) request = null }
}

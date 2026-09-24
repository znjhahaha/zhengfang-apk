package com.tyust.course.academic.plugin

import org.json.JSONArray
import org.json.JSONObject

/** Signed metadata selects a compatible stable release before any package is downloaded. */
object PluginUpdatePolicy {
    private val stable = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
    fun version(value: String): List<Long>? = stable.matchEntire(value)?.groupValues?.drop(1)?.map { it.toLongOrNull() ?: return null }
    fun compare(a: String, b: String): Int {
        val left = version(a) ?: return -1; val right = version(b) ?: return 1
        for (index in 0..2) { val difference = left[index].compareTo(right[index]); if (difference != 0) return difference }
        return 0
    }
    fun compatible(release: JSONObject, versionCode: Int, capabilities: Map<String, Int>, installed: List<PluginManifest>): Boolean {
        if (release.optInt("apiVersion", 1) !in 1..PluginLimits.API_VERSION || release.optInt("minAppVersionCode", 0) > versionCode ||
            PluginPlatformContract.requirements(release, capabilities).isNotEmpty()) return false
        fun offers(manifests: List<JSONObject>) = manifests.flatMap { it.optJSONArray("services")?.let(PluginJson::objects).orEmpty() }
            .map { it.getString("name") to it.getInt("version") }.toSet()
        val previous = offers(installed.map { it.json })
        val projected = installed.filter { it.id != release.optString("id") }.map { it.json } + release
        val services = offers(projected)
        if (release.optJSONArray("serviceDependencies")?.let(PluginJson::objects).orEmpty().any { dependency -> dependency.optBoolean("required") &&
                (dependency.getString("name") to dependency.getInt("version")) !in services }) return false
        val dependedOn = installed.filter { it.id != release.optString("id") }.flatMap { it.json.optJSONArray("serviceDependencies")?.let(PluginJson::objects).orEmpty() }
        return dependedOn.none { dependency -> val contract = dependency.getString("name") to dependency.getInt("version")
            contract in previous && contract !in services }
    }
    fun select(entry: JSONObject, versionCode: Int, capabilities: Map<String, Int>, installed: List<PluginManifest>): JSONObject? {
        val releases = entry.optJSONArray("releases")?.let(PluginJson::objects) ?: listOf(entry)
        if (releases.size > 200 || releases.map { it.optString("version") }.distinct().size != releases.size)
            throw PluginException(PluginErrorCode.VALIDATION_FAILED, "目录发布历史重复或超过上限")
        return releases.map { release -> JSONObject(release.toString()).apply { put("id", entry.getString("id"));
            for (key in listOf("name", "description")) if (!has(key) && entry.has(key)) put(key, entry.get(key)) } }
            .filter { version(it.optString("version")) != null && !it.optBoolean("withdrawn") && compatible(it, versionCode, capabilities, installed) }
            .maxWithOrNull { a, b -> compare(a.getString("version"), b.getString("version")) }
    }
    fun expanded(previous: PluginManifest?, next: PluginManifest): List<String> {
        if (previous == null) return next.permissions.toList() + if (next.network.isNotEmpty()) listOf("网络访问") else emptyList()
        val added = (next.permissions - previous.permissions).toMutableList()
        val oldRules = previous.network.map(PluginJson::canonical).toSet()
        if (next.network.any { PluginJson.canonical(it) !in oldRules }) added += "网络范围"
        val oldServers = previous.json.optJSONArray("servers")?.let(PluginJson::objects).orEmpty().associate { it.getString("id") to it.getString("origin") }
        if (next.json.optJSONArray("servers")?.let(PluginJson::objects).orEmpty().any { oldServers[it.getString("id")] != it.getString("origin") }) added += "服务来源"
        return added.distinct()
    }
}

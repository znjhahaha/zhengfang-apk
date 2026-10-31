package com.tyust.course.academic.plugin

import com.tyust.course.model.SchoolConfig
import org.json.JSONObject

/** Local discovery never starts a download or changes the selected school adapter. */
internal object PluginDiscovery {
    enum class Type(val label: String) { ALL("全部"), ACADEMIC("教务适配"), CAMPUS("校园服务"), TOOL("通用工具") }
    fun type(entry: JSONObject): Type = when {
        entry.optString("kind") in setOf("configuration", "independent", "extension") ||
            "auth.start" in PluginJson.strings(entry.optJSONArray("capabilities")) -> Type.ACADEMIC
        hasCampusServices(entry) -> Type.CAMPUS
        else -> Type.TOOL
    }
    fun hasCampusServices(entry: JSONObject): Boolean = entry.optString("kind") == "service" ||
        listOf(entry.optJSONArray("services"), entry.optJSONArray("servers"),
            entry.optJSONObject("contributes")?.optJSONArray("services")).any { (it?.length() ?: 0) > 0 }
    fun typeLabel(entry: JSONObject): String {
        // Widget plugins are surfaced as "小组件" regardless of the academic/campus classification beneath.
        if (entry.optString("kind") == "widget") return "小组件" + if (hasCampusServices(entry)) " · 含校园服务" else ""
        return type(entry).label + if (type(entry) == Type.ACADEMIC && hasCampusServices(entry)) " · 含校园服务" else ""
    }
    private fun rules(entry: JSONObject) = listOf("matches", "aliases").flatMap {
        entry.optJSONArray(it)?.let(PluginJson::objects).orEmpty()
    }
    fun universal(entry: JSONObject) = entry.optString("kind") in setOf("native", "widget") &&
        entry.optJSONObject("school") == null && rules(entry).isEmpty()

    fun matches(entry: JSONObject, school: SchoolConfig): Boolean {
        if (universal(entry) || rules(entry).any { PluginSchoolMatcher.matches(it, school) }) return true
        val primary = entry.optJSONObject("school")
        if (primary?.optString("id") == school.id) return true
        if (primary != null && runCatching { PluginSchoolMatcher.key(SchoolConfig.fromJson(primary)) == PluginSchoolMatcher.key(school) }.getOrDefault(false)) return true
        val service = entry.optJSONObject("service")
        return school.id in PluginJson.strings(service?.optJSONArray("schoolIds")) ||
            school.domain.lowercase() in PluginJson.strings(service?.optJSONArray("academicHosts"))
    }

    fun filter(entries: List<JSONObject>, school: SchoolConfig?, query: String, onlySchool: Boolean,
               schools: List<SchoolConfig> = emptyList(), type: Type = Type.ALL): List<JSONObject> {
        val words = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        return entries.filter { entry ->
            val match = school != null && matches(entry, school)
            val text = listOf(entry.optString("name"), entry.optString("id"), entry.optString("description"),
                entry.optJSONObject("school")?.optString("name").orEmpty(), entry.optJSONArray("schoolNames")?.toString().orEmpty(),
                entry.optJSONArray("features")?.toString().orEmpty(),
                rules(entry).joinToString(" ") { it.optString("host") },
                if (match) school.name else "",
                if (words.isEmpty()) "" else schools.filter { matches(entry, it) }.joinToString(" ") { it.name }).joinToString(" ")
            (type == Type.ALL || PluginDiscovery.type(entry) == type) && (!onlySchool || match && !universal(entry)) && words.all { text.contains(it, ignoreCase = true) }
        }.sortedWith(compareByDescending<JSONObject> { school != null && matches(it, school) }
            .thenBy { it.optString("name", it.optString("id")) })
    }

    fun scope(entry: JSONObject, school: SchoolConfig?): String = when {
        universal(entry) -> "适用所有学校"
        school != null && matches(entry, school) -> "适用本校 · ${school.name}"
        !entry.optJSONObject("school")?.optString("name").isNullOrBlank() -> "适用于 ${entry.getJSONObject("school").getString("name")}"
        entry.optString("kind") in setOf("configuration", "independent", "extension") -> "学校教务 · ${entry.optString("name")}"
        else -> "校园服务 · 查看适用学校"
    }
}

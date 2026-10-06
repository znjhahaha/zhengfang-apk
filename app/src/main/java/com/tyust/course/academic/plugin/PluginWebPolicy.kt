package com.tyust.course.academic.plugin

import org.json.JSONObject
import java.net.URI

/** Navigation scope is independent of the original bridge origin and its capabilities. */
internal class PluginWebPolicy(val origin: String, page: JSONObject) {
    val browser = page.optJSONObject("web")?.optString("mode") == "browser"
    private val navigation = setOf(origin) + PluginJson.strings(page.optJSONObject("web")?.optJSONArray("navigationOrigins"))
    val initialUrl = origin + page.optString("path", "/")
    fun allows(url: String): Boolean = exactOrigin(url)?.let { it in navigation } == true
    fun bridges(url: String): Boolean = !browser && exactOrigin(url) == origin
    fun external(url: String): Boolean = exactOrigin(url)?.let { URI(it).scheme in setOf("http", "https") } == true
    fun supported(profiles: Boolean, messages: Boolean) = profiles && (browser || messages)
    companion object {
        fun exactOrigin(url: String): String? = runCatching {
            val u = URI(url)
            if (u.scheme !in setOf("http", "https") || u.host == null || u.rawUserInfo != null || u.rawAuthority?.contains('*') == true) null
            else "${u.scheme}://${u.rawAuthority}"
        }.getOrNull()
        fun academicScope(page: JSONObject?, token: Any): String =
            if (page?.optJSONObject("web")?.optString("mode") == "browser") "web" else token.toString()
    }
}
